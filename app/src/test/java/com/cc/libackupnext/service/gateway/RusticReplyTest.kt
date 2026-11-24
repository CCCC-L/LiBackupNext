package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.FailureReason
import com.cc.libackupnext.data.json.OkResponse
import com.cc.libackupnext.service.json.toJsonText
import com.cc.libackupnext.service.rclone.RcloneFailureClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败归因与文案的纯函数测试。
 *
 * 这是「远程备份看不到正确报错」「密码对却提示密码错」两个 bug 的回归点：
 * - 原因由底层给（[RusticFailure.reason]），不是主进程按「仓库是不是远程」猜；
 * - 已知原因只给一句中文，原文不进 UI；
 * - 认不出原因才拿原文兜底，不能编一个错的结论。
 */
class RusticReplyTest {

    private fun reply(ok: Boolean, error: String?, reason: String? = null): String =
        OkResponse(ok = ok, error = error, reason = reason).toJsonText()

    // ==================== 协议解析 ====================

    @Test
    fun `空串是成功`() {
        assertNull(parseRusticReply(""))
        assertNull(parseRusticReply(null))
    }

    @Test
    fun `ok 响应是成功`() {
        assertNull(parseRusticReply(reply(ok = true, error = null)))
    }

    @Test
    fun `没有原因的失败按未知处理`() {
        val failure = parseRusticReply(reply(ok = false, error = "snapshot not found"))
        assertEquals("snapshot not found", failure?.message)
        assertNull(failure?.reason)
        assertFalse(failure!!.transport)
    }

    @Test
    fun `密码错不是断连`() {
        val failure = parseRusticReply(
            reply(ok = false, error = "long english", reason = FailureReason.PASSWORD.wire)
        )
        assertEquals(FailureReason.PASSWORD, failure?.reason)
        // 密码错跟网络无关：不能停 serve，也不能让批量以为「网不好」
        assertFalse(failure!!.transport)
    }

    @Test
    fun `连不上算断连`() {
        val failure = parseRusticReply(
            reply(ok = false, error = "Backoff failed", reason = FailureReason.UNREACHABLE.wire)
        )
        assertTrue(failure!!.transport)
    }

    /** 少数 root 侧分支直接返回一句中文（不是 JSON）：按未知原因原样显示，不能当成成功。 */
    @Test
    fun `非 JSON 的错误文本按未知原因处理`() {
        val failure = parseRusticReply("恢复异常：磁盘已满")
        assertEquals("恢复异常：磁盘已满", failure?.message)
        assertNull(failure?.reason)
    }

    /** 查询类响应（ok / error / reason 三件套）走同一套语义。 */
    @Test
    fun `查询响应按 reason 判类别`() {
        assertTrue(failureOf("boom", FailureReason.AUTH.wire, "查询快照失败").transport)
        assertNull(failureOf("boom", null, "查询快照失败").reason)
        assertEquals("查询快照失败", failureOf(null, null, "查询快照失败").message)
    }

    // ==================== 文案 ====================

    @Test
    fun `已知原因只给一句中文，原文不进 UI`() {
        val brief = BackendMessage.shorten(
            source = BackendSource.RUSTIC,
            raw = "The password that has been entered, seems to be incorrect. " +
                    "No suitable key found for the given password.",
            reason = FailureReason.PASSWORD
        )

        assertEquals("密码有误，请确认密码", brief.text)
        // 已知原因不给原文：detail 为 null，弹窗不会显示那段英文
        assertNull(brief.detail)
    }

    @Test
    fun `仓库损坏和密码错是两句话`() {
        assertEquals(
            "仓库目录有缺失，无法打开",
            BackendMessage.shorten(BackendSource.RUSTIC, "x", FailureReason.REPO_INCOMPLETE).text
        )
        assertEquals(
            "密码有误，请确认密码",
            BackendMessage.shorten(BackendSource.RUSTIC, "x", FailureReason.PASSWORD).text
        )
    }

    @Test
    fun `未知原因拿原文兜底并保留换行`() {
        val brief = BackendMessage.shorten(
            source = BackendSource.RUSTIC,
            raw = "`rustic_core` experienced an error related to `the repository`.\n\n" +
                    "Message:\nsomething unexpected\n\nCaused by:\ninner detail",
            reason = null
        )

        assertEquals("something unexpected Caused by: inner detail", brief.text)
        assertTrue(brief.detail!!.contains('\n'))
        assertTrue(brief.detail!!.contains("Caused by:"))
    }

    // ==================== rclone 分类 ====================

    @Test
    fun `rclone 认证失败优先于连不上`() {
        val raw = "serve/start 失败：NewFs: couldn't connect to https://dav.example.com: 401 Unauthorized"
        assertEquals(FailureReason.AUTH, RcloneFailureClassifier.classify(raw))
    }

    @Test
    fun `rclone 连不上与超时`() {
        assertEquals(
            FailureReason.UNREACHABLE,
            RcloneFailureClassifier.classify("NewFs: couldn't connect to remote: dial tcp: connection refused")
        )
        assertEquals(
            FailureReason.TIMEOUT,
            RcloneFailureClassifier.classify("dial tcp 10.0.0.2:443: i/o timeout")
        )
    }

    @Test
    fun `rclone 端口号里的 401 不算认证失败`() {
        val raw = "couldn't connect to 127.0.0.1:40123: connection refused"
        assertEquals(FailureReason.UNREACHABLE, RcloneFailureClassifier.classify(raw))
    }

    @Test
    fun `rclone 认不出的错误返回 null`() {
        assertNull(RcloneFailureClassifier.classify("some totally unknown failure"))
    }
}
