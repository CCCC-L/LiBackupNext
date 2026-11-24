package com.cc.libackupnext.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败原因表本身的约束：文案和 wire 都不能重复、不能为空。
 *
 * 加新原因时最容易忘写文案，或者复制粘贴改漏一个词 —— 这条测试会直接拦住。
 */
class FailureReasonTest {

    @Test
    fun `每个原因都有文案`() {
        FailureReason.entries.forEach { reason ->
            assertTrue("${reason.name} 缺文案", reason.text.isNotBlank())
        }
    }

    @Test
    fun `文案不重复`() {
        val texts = FailureReason.entries.map { it.text }
        assertEquals("文案重复：$texts", texts.size, texts.toSet().size)
    }

    @Test
    fun `wire 不重复`() {
        val wires = FailureReason.entries.map { it.wire }
        assertEquals("wire 重复：$wires", wires.size, wires.toSet().size)
    }

    @Test
    fun `wire 能解析回原值`() {
        FailureReason.entries.forEach { reason ->
            assertEquals(reason, FailureReason.of(reason.wire))
        }
        assertNull(FailureReason.of("not_a_reason"))
        assertNull(FailureReason.of(null))
    }

    /** 只有网络类原因才算断连：密码错、目录缺件停 serve 只会掩盖真正的问题。 */
    @Test
    fun `断连类原因只有三个`() {
        val transport = FailureReason.entries.filter { it.isTransport }
        assertEquals(
            setOf(FailureReason.UNREACHABLE, FailureReason.AUTH, FailureReason.TIMEOUT),
            transport.toSet()
        )
        assertFalse(FailureReason.PASSWORD.isTransport)
        assertFalse(FailureReason.REPO_INCOMPLETE.isTransport)
    }
}
