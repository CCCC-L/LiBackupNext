package com.cc.libackupnext.service.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份源路径的规则：inode 不可信时关掉比较。
 *
 * 路径换算（FUSE → /data/media）在 [com.cc.libackupnext.data.MediaPath] 里，那边单独测。
 * 这条规则错一个，要么备份读不到文件，要么每次备份都全量重读。
 */
class RequestPathsTest {

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
