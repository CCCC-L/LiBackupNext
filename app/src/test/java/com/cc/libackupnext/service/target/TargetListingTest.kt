package com.cc.libackupnext.service.target

import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupTarget
import com.cc.libackupnext.data.target.TargetSpecs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 行组装规则：**备份目标 ∪ 仓库里有备份的目录**，再去掉「磁盘上没了、也没备份」的。
 *
 * 钉住三条产品语义：
 * 1. 移除该文件夹 = 只退出备份目标，备份保留 → 行继续出现（划线置灰、可恢复）；
 * 2. 目录被删了、也没有备份 → 直接不显示（既不能备份也不能恢复，留着只是噪音）；
 * 3. 目录被删了、但还有备份 → 必须显示（那是唯一能恢复它的入口）。
 *
 * `exists` 是注入的，所以这组用例不需要真的碰磁盘，也不依赖 Android。
 */
class TargetListingTest {

    private val root: File = Files.createTempDirectory("libackup-listing").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun dir(name: String): String = File(root, name).apply { mkdirs() }.absolutePath

    private fun missingDir(name: String): String = File(root, name).absolutePath

    private fun app(packageName: String, onlyInBackup: Boolean = false) = AppInfo(
        name = packageName,
        packageName = packageName,
        onlyInBackup = onlyInBackup
    )

    // ==================== 文件夹 ====================

    @Test
    fun `行 = 备份目标 并 仓库里有备份的目录`() {
        val kept = dir("kept")
        val removed = dir("removed")

        val rows = TargetListing.dirRows(extraDirs = setOf(kept), backedUpDirs = setOf(removed))

        assertEquals(listOf(kept, removed), rows.map { it.packageName })
        assertFalse(rows.first { it.packageName == kept }.onlyInBackup)
        assertTrue(rows.first { it.packageName == removed }.onlyInBackup)
    }

    @Test
    fun `同一个路径只出一行，且以备份目标为准`() {
        val path = dir("same")

        val rows = TargetListing.dirRows(extraDirs = setOf(path), backedUpDirs = setOf(path))

        assertEquals(1, rows.size)
        assertFalse("两边都有时不算「只剩备份」", rows.single().onlyInBackup)
    }

    @Test
    fun `行按路径排序，不依赖集合的遍历顺序`() {
        val b = dir("b")
        val a = dir("a")

        val rows = TargetListing.dirRows(extraDirs = linkedSetOf(b, a))

        assertEquals(listOf(a, b), rows.map { it.packageName })
    }

    @Test
    fun `目录被删、也没有备份时，行直接不显示`() {
        val gone = missingDir("gone")

        val rows = TargetListing.dirRows(extraDirs = setOf(gone), exists = { false })

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `目录被删但还有备份时，行仍然出现`() {
        val gone = missingDir("gone")

        val rows = TargetListing.dirRows(
            extraDirs = setOf(gone),
            backedUpDirs = setOf(gone),
            exists = { false }
        )

        assertEquals(listOf(gone), rows.map { it.packageName })
    }

    // ==================== 多用户 ====================

    @Test
    fun `配置里的路径按当前用户展开`() {
        val config = "/storage/emulated/0/DCIM"

        val rows = TargetListing.dirRows(
            extraDirs = setOf(config),
            currentUserId = 10,
            exists = { true }
        )

        assertEquals(listOf("/storage/emulated/10/DCIM"), rows.map { it.packageName })
        assertEquals(10, rows.single().userId)
    }

    @Test
    fun `同一个配置在不同用户下展开成不同路径`() {
        val config = "/storage/emulated/10/DCIM"

        val asPrimary = TargetListing.dirRows(extraDirs = setOf(config), exists = { true })
        val asTen = TargetListing.dirRows(
            extraDirs = setOf(config),
            currentUserId = 10,
            exists = { true }
        )

        assertEquals(listOf("/storage/emulated/0/DCIM"), asPrimary.map { it.packageName })
        assertEquals(listOf("/storage/emulated/10/DCIM"), asTen.map { it.packageName })
    }

    @Test
    fun `存在性是按展开后的路径问的`() {
        val asked = mutableListOf<String>()

        TargetListing.dirRows(
            extraDirs = setOf("/storage/emulated/0/DCIM"),
            currentUserId = 10,
            exists = { path ->
                asked += path
                false
            }
        )

        assertEquals(listOf("/storage/emulated/10/DCIM"), asked)
    }

    @Test
    fun `SD 卡不展开，任何用户视角下都是同一条`() {
        val sd = "/storage/1A2B-3C4D/Music"

        val rows = TargetListing.dirRows(
            extraDirs = setOf(sd),
            currentUserId = 10,
            exists = { true }
        )

        assertEquals(listOf(sd), rows.map { it.packageName })
        assertEquals("SD 卡不归属任何用户", 0, rows.single().userId)
    }

    @Test
    fun `划线只看「已移除备份目标」，磁盘存在性由界面注入`() {
        val kept = dir("kept")
        val removed = dir("removed")

        val rows = TargetListing.dirRows(extraDirs = setOf(kept), backedUpDirs = setOf(removed))
        val spec = TargetSpecs.of(BackupTarget.DIR)

        assertFalse(spec.isMissing(rows.first { it.packageName == kept }))
        assertTrue(spec.isMissing(rows.first { it.packageName == removed }))
    }

    // ==================== 一页的行（批量页） ====================

    @Test
    fun `文件夹新建页只列备份目标，不列已移除的目录`() {
        val kept = dir("kept")
        val removed = dir("removed")

        val rows = TargetListing.rows(
            target = BackupTarget.DIR,
            apps = emptyList(),
            create = true,
            hasBackup = { true },
            extraDirs = setOf(kept),
            backedUpDirs = setOf(removed)
        )

        assertEquals(listOf(kept), rows.map { it.packageName })
    }

    @Test
    fun `文件夹管理页列出已移除但还有备份的目录，并标成只在备份里`() {
        val kept = dir("kept")
        val removed = dir("removed")

        val rows = TargetListing.rows(
            target = BackupTarget.DIR,
            apps = emptyList(),
            create = false,
            hasBackup = { true },
            extraDirs = setOf(kept),
            backedUpDirs = setOf(removed)
        )

        assertEquals(setOf(kept, removed), rows.map { it.packageName }.toSet())
        assertFalse(rows.first { it.packageName == kept }.onlyInBackup)
        assertTrue(rows.first { it.packageName == removed }.onlyInBackup)
    }

    @Test
    fun `管理页不列一个备份都没有的目录`() {
        val rows = TargetListing.rows(
            target = BackupTarget.DIR,
            apps = emptyList(),
            create = false,
            hasBackup = { false }
        )

        assertTrue(rows.isEmpty())
    }

    // ==================== 应用 / 固定目标 ====================

    @Test
    fun `应用新建页不列只在备份里的应用，管理页要列`() {
        val apps = listOf(app("com.installed"), app("com.only.in.backup", onlyInBackup = true))

        val create = TargetListing.rows(
            target = BackupTarget.APP,
            apps = apps,
            create = true,
            hasBackup = { true }
        )
        val manage = TargetListing.rows(
            target = BackupTarget.APP,
            apps = apps,
            create = false,
            hasBackup = { true }
        )

        assertEquals(listOf("com.installed"), create.map { it.packageName })
        assertEquals(
            setOf("com.installed", "com.only.in.backup"),
            manage.map { it.packageName }.toSet()
        )
    }

    @Test
    fun `管理页只留有备份的应用`() {
        val rows = TargetListing.rows(
            target = BackupTarget.APP,
            apps = listOf(app("com.a"), app("com.b")),
            create = false,
            hasBackup = { it == "com.b" }
        )

        assertEquals(listOf("com.b"), rows.map { it.packageName })
    }

    @Test
    fun `固定目标永远只有一行`() {
        BackupTarget.entries.filter { it.isFixed }.forEach { target ->
            val rows = TargetListing.rows(
                target = target,
                apps = emptyList(),
                create = true,
                hasBackup = { false }
            )

            assertEquals("$target 应该只有一行", 1, rows.size)
            assertEquals(target.fixedId, rows.single().packageName)
        }
    }

    // ==================== 概况投影 ====================

    @Test
    fun `备份概况里只有绝对路径才算目录`() {
        val keys = listOf("com.app", "/storage/emulated/0/DCIM", "wifi", "messaging")
        val summaries = keys.associateWith { key ->
            AppBackupSummary(packageName = key, count = 0, latest = BackupEntry())
        }

        assertEquals(
            setOf("/storage/emulated/0/DCIM"),
            TargetListing.backedUpDirs(summaries)
        )
    }

    @Test
    fun `备份概况里的目录归一成用户无关的身份`() {
        val keys = listOf(
            "/storage/emulated/0/DCIM",
            "/storage/emulated/10/DCIM",
            "/storage/1A2B-3C4D/Music",
            "com.app",
            "wifi"
        )
        val summaries = keys.associateWith { key ->
            AppBackupSummary(packageName = key, count = 1, latest = BackupEntry())
        }

        assertEquals(
            "两个用户的 DCIM 是同一个身份；SD 卡归一不动它",
            setOf("/storage/emulated/0/DCIM", "/storage/1A2B-3C4D/Music"),
            TargetListing.backedUpDirs(summaries)
        )
    }

    @Test
    fun `别的用户备份过的目录，在当前用户视角下也出一行`() {
        val rows = TargetListing.dirRows(
            extraDirs = emptySet(),
            backedUpDirs = setOf("/storage/emulated/0/test"),
            currentUserId = 10,
            exists = { false }
        )

        assertEquals(listOf("/storage/emulated/10/test"), rows.map { it.packageName })
        assertTrue("只在备份里（当前用户的空间里没有）", rows.single().onlyInBackup)
    }
}
