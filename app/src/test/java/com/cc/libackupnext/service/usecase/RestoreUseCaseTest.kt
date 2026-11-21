package com.cc.libackupnext.service.usecase

import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupProgress
import com.cc.libackupnext.data.BackupRequest
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.SnapshotOverride
import com.cc.libackupnext.data.UserAccount
import com.cc.libackupnext.data.dirAppInfo
import com.cc.libackupnext.service.gateway.DeviceGateway
import com.cc.libackupnext.service.gateway.RepoWriter
import com.cc.libackupnext.service.progress.DefaultOperationPlanner
import com.cc.libackupnext.service.target.BackupTargets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复编排的行为测试：**不依赖 Android、不绑 root 服务**。
 *
 * 重点是一条容易漏的收尾：**目录恢复成功后要把它加回备份目标**。
 * 不加的话，恢复出来的目录在列表里一直是「已移除 / 只剩备份」的划线状态，
 * 用户得自己再用 + 号加一次 —— 明明文件已经回到磁盘上了。
 */
class RestoreUseCaseTest {

    // ==================== 假实现 ====================

    private class FakeTargets(initial: Set<String> = emptySet()) : BackupTargets {
        private val state = MutableStateFlow(initial)
        override val dirs: StateFlow<Set<String>> = state.asStateFlow()

        /** 记录被加回来的路径，断言用。 */
        val added = mutableListOf<String>()

        override fun addDir(path: String) {
            added += path
            state.value = state.value + path
        }

        override fun removeDir(path: String) {
            state.value = state.value - path
        }
    }

    private class FakeWriter : RepoWriter {
        var restoreResult: Result<Unit> = Result.success(Unit)
        var restoredIds: List<String> = emptyList()

        override suspend fun restoreSnapshots(
            app: AppInfo,
            snapshots: List<BackupSnapshot>,
            onProgress: (BackupProgress) -> Unit
        ): Result<Unit> {
            restoredIds = snapshots.map { it.id }
            return restoreResult
        }

        override suspend fun write(
            request: BackupRequest,
            overrides: Map<String, SnapshotOverride>,
            deleteIds: List<String>,
            batchId: String?,
            operationId: Long,
            iconAlreadyBackedUp: Boolean?,
            parentIds: Map<SnapshotKind, String>?,
            onProgress: (BackupProgress) -> Unit
        ): Result<Unit> = TODO()

        override suspend fun deleteSnapshots(ids: List<String>): Result<Unit> = TODO()
        override suspend fun deleteSelected(
            entry: BackupEntry,
            groups: Set<BackupGroup>
        ): Result<Unit> = TODO()

        override suspend fun deleteAll(packageName: String): Result<Int> = TODO()
        override suspend fun updateEntryDescription(
            entry: BackupEntry,
            description: String?
        ): Result<Unit> = TODO()

        override suspend fun prune(): Result<Int> = TODO()
        override suspend fun estimatePrune(): Result<Long> = TODO()
        override suspend fun exportSelfApk() = Unit
    }

    /** 只关心「恢复前停应用」；其余设备操作在恢复编排里用不到。 */
    private class FakeDevice : DeviceGateway {
        var stopped: String? = null

        override suspend fun stop(packageName: String, userId: Int) {
            stopped = packageName
        }

        override suspend fun pause(packageName: String, userId: Int): Boolean = TODO()
        override suspend fun resume(packageName: String, userId: Int) = TODO()
        override suspend fun setWifiEnabled(enabled: Boolean): Result<Unit> = TODO()
        override suspend fun getUsers(): List<UserAccount> = TODO()
        override suspend fun getInstalledApps(userId: Int): List<AppInfo> = TODO()
        override suspend fun getIcon(packageName: String, userId: Int): ByteArray? = TODO()
        override suspend fun existingDirs(paths: List<String>): Result<Set<String>> = TODO()
        override suspend fun forceStop(packageName: String, userId: Int) = TODO()
        override suspend fun freeze(packageName: String, userId: Int) = TODO()
        override suspend fun unfreeze(packageName: String, userId: Int) = TODO()
        override suspend fun clearData(packageName: String, userId: Int) = TODO()
        override suspend fun uninstall(packageName: String, userId: Int) = TODO()
    }

    // ==================== 造数据 ====================

    private fun entry(label: String, id: String = "s1") = BackupEntry(
        title = "测试",
        time = 0,
        snapshots = listOf(BackupSnapshot(id = id, label = label, tags = "", time = 0))
    )

    private fun app() = AppInfo(
        name = "测试",
        packageName = "com.test",
        version = "1.0.0",
        versionCode = 1,
        userId = 0
    )

    private fun useCase(writer: FakeWriter, targets: FakeTargets) =
        DefaultRestoreUseCase(writer, DefaultOperationPlanner, FakeDevice(), targets)

    // ==================== 用例 ====================

    @Test
    fun `目录恢复成功后加回备份目标`() = runBlocking {
        val targets = FakeTargets(setOf("/other"))
        val path = "/fake/DCIM"

        val result = useCase(FakeWriter(), targets).restore(
            app = dirAppInfo(path, onlyInBackup = true),
            entry = entry("$path:dir"),
            groups = setOf(BackupGroup.DIR),
            installed = false
        )

        assertTrue(result.isSuccess)
        assertEquals("恢复出来的目录要重新成为备份目标", listOf(path), targets.added)
        assertEquals("原有目标不能被覆盖", setOf("/other", path), targets.dirs.value)
    }

    @Test
    fun `应用恢复成功不动备份目标`() = runBlocking {
        val targets = FakeTargets(setOf("/other"))

        val result = useCase(FakeWriter(), targets).restore(
            app = app(),
            entry = entry("com.test:apk"),
            groups = setOf(BackupGroup.APK),
            installed = false
        )

        assertTrue(result.isSuccess)
        assertTrue("应用恢复不该碰目录目标", targets.added.isEmpty())
        assertEquals(setOf("/other"), targets.dirs.value)
    }

    @Test
    fun `目录恢复失败时不能加回备份目标`() = runBlocking {
        val targets = FakeTargets()
        val path = "/fake/DCIM"
        val writer = FakeWriter().apply {
            restoreResult = Result.failure(IllegalStateException("写入失败"))
        }

        val result = useCase(writer, targets).restore(
            app = dirAppInfo(path, onlyInBackup = true),
            entry = entry("$path:dir"),
            groups = setOf(BackupGroup.DIR),
            installed = false
        )

        assertTrue(result.isFailure)
        assertFalse("失败说明目录没写回去，不该变成目标", path in targets.dirs.value)
    }
}
