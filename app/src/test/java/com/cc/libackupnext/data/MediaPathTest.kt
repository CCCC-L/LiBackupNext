package com.cc.libackupnext.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 路径换算的三条规则。
 *
 * 钉住的是「配置 / 展示 / 物理」三层各自该拿到什么字符串 —— 这一层算错，
 * 备份会读错用户的空间，而且**不会报错**（rustic 那边只会当成路径不存在跳过）。
 */
class MediaPathTest {

    // ==================== userOf ====================

    @Test
    fun `模拟卷的归属用户`() {
        assertEquals(0, MediaPath.userOf("/storage/emulated/0/DCIM"))
        assertEquals(10, MediaPath.userOf("/storage/emulated/10/DCIM"))
        assertEquals(10, MediaPath.userOf("/data/media/10/DCIM"))
        assertEquals(0, MediaPath.userOf("/storage/emulated/0"))
    }

    @Test
    fun `SD 卡和共享挂载不归属任何用户`() {
        assertNull(MediaPath.userOf("/storage/ABCD-1234/DCIM"))
        assertNull(MediaPath.userOf("/mnt/media_rw/ABCD-1234/DCIM"))
        assertNull(MediaPath.userOf("/data/user/0/com.example"))
        assertNull(MediaPath.userOf("DCIM"))
    }

    @Test
    fun `前缀像但不是模拟卷的路径不算`() {
        assertNull("0foo 不是合法的 user 段", MediaPath.userOf("/storage/emulated/0foo/DCIM"))
        assertNull(MediaPath.userOf("/storage/emulatedX/10/DCIM"))
    }

    // ==================== normalize ====================

    @Test
    fun `配置层把任意用户归一到主用户`() {
        assertEquals("/storage/emulated/0/DCIM", MediaPath.normalize("/storage/emulated/10/DCIM"))
        assertEquals("/storage/emulated/0/DCIM", MediaPath.normalize("/storage/emulated/0/DCIM"))
        assertEquals("/storage/emulated/0", MediaPath.normalize("/storage/emulated/10"))
    }

    @Test
    fun `归一幂等`() {
        val once = MediaPath.normalize("/storage/emulated/10/DCIM")
        assertEquals(once, MediaPath.normalize(once))
    }

    @Test
    fun `归一不动 SD 卡`() {
        assertEquals("/storage/ABCD-1234/DCIM", MediaPath.normalize("/storage/ABCD-1234/DCIM"))
    }

    // ==================== forUser ====================

    @Test
    fun `展开到指定用户的空间`() {
        assertEquals("/storage/emulated/10/DCIM", MediaPath.forUser("/storage/emulated/0/DCIM", 10))
        assertEquals("/storage/emulated/0/DCIM", MediaPath.forUser("/storage/emulated/10/DCIM", 0))
    }

    @Test
    fun `同一个配置项在不同用户下展开成不同路径`() {
        val config = MediaPath.normalize("/storage/emulated/10/DCIM")

        assertEquals("/storage/emulated/0/DCIM", MediaPath.forUser(config, 0))
        assertEquals("/storage/emulated/10/DCIM", MediaPath.forUser(config, 10))
    }

    @Test
    fun `展开不动 SD 卡`() {
        assertEquals("/storage/ABCD-1234/DCIM", MediaPath.forUser("/storage/ABCD-1234/DCIM", 10))
    }

    // ==================== physical ====================

    @Test
    fun `物理路径按路径自己的用户换算`() {
        assertEquals("/data/media/0/DCIM", MediaPath.physical("/storage/emulated/0/DCIM"))
        assertEquals("/data/media/10/DCIM", MediaPath.physical("/storage/emulated/10/DCIM"))
    }

    @Test
    fun `物理路径换算幂等`() {
        val once = MediaPath.physical("/storage/emulated/10/DCIM")
        assertEquals(once, MediaPath.physical(once))
    }

    @Test
    fun `物理路径不动 SD 卡`() {
        assertEquals("/storage/ABCD-1234/DCIM", MediaPath.physical("/storage/ABCD-1234/DCIM"))
    }

    @Test
    fun `配置到物理的完整链路`() {
        val config = MediaPath.normalize("/storage/emulated/10/Pictures")

        assertEquals(
            "/data/media/10/Pictures",
            MediaPath.physical(MediaPath.forUser(config, 10))
        )
        assertEquals(
            "/data/media/0/Pictures",
            MediaPath.physical(MediaPath.forUser(config, 0))
        )
    }

    @Test
    fun `尾斜杠归一，配置键不依赖它`() {
        assertEquals("/storage/emulated/0/DCIM", MediaPath.normalize("/storage/emulated/10/DCIM/"))
        assertEquals("/data/media/10/DCIM", MediaPath.physical("/storage/emulated/10/DCIM/"))
        assertEquals("/storage/emulated/10/DCIM", MediaPath.forUser("/storage/emulated/0/DCIM/", 10))
    }

    // ==================== backupKey ====================

    @Test
    fun `同一个文件夹在不同用户下的备份身份相同`() {
        // 这条不成立的话：在用户 0 备份过的 test，切到用户 10 就找不到那条备份
        assertEquals(
            MediaPath.backupKey("/storage/emulated/0/test"),
            MediaPath.backupKey("/storage/emulated/10/test")
        )
    }

    @Test
    fun `应用包名和 SD 卡的备份身份原样返回`() {
        assertEquals("com.example.app", MediaPath.backupKey("com.example.app"))
        assertEquals("/storage/1A2B-3C4D/Music", MediaPath.backupKey("/storage/1A2B-3C4D/Music"))
    }
}
