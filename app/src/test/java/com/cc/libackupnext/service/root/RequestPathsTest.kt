package com.cc.libackupnext.service.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份源路径的两条规则：FUSE 视图要换成 /data 视角、inode 不可信时关掉比较。
 *
 * 都是纯函数（不碰 Android、不碰 root），所以能在桌面上直接跑。
 * 这两条规则错一个，要么备份读不到文件，要么每次备份都全量重读。
 */
class RequestPathsTest {

    @Test
    fun `主存储路径换成 data media 视角`() {
        assertEquals("/data/media/0/DCIM", mediaPath("/storage/emulated/0/DCIM"))
        assertEquals("/data/media/0", mediaPath("/storage/emulated/0"))
    }

    @Test
    fun `不在主存储下的路径原样返回`() {
        assertEquals("/data/user/0/com.example", mediaPath("/data/user/0/com.example"))
        // SD 卡不在改写范围内：root 能读，但 inode 由用户态分配（见 ignoreInodeFor）
        assertEquals("/storage/1A2B-3C4D/Music", mediaPath("/storage/1A2B-3C4D/Music"))
    }

    @Test
    fun `data 下的源保持比较 inode`() {
        assertFalse(ignoreInodeFor(listOf("/data/user/0/com.example")))
        assertFalse(ignoreInodeFor(listOf("/data/user_de/0/com.example")))
        assertFalse(ignoreInodeFor(listOf("/data/media/0/Android/data/com.example")))
        assertFalse(ignoreInodeFor(listOf("/data/app/~~abc==/com.example-def==/base.apk")))
    }

    @Test
    fun `FUSE 视图上的源关掉 inode 比较`() {
        assertTrue(ignoreInodeFor(listOf("/storage/1A2B-3C4D/Music")))
        assertTrue(ignoreInodeFor(listOf("/storage/emulated/10/DCIM")))
    }

    @Test
    fun `没有源的时候不关`() {
        // 空 sources 的 item 会被跳过，这里只是别把默认值弄反
        assertFalse(ignoreInodeFor(emptyList()))
    }
}
