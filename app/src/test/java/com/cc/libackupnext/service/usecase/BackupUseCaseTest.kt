package com.cc.libackupnext.service.usecase

import com.cc.libackupnext.data.AppBackupSummary
import com.cc.libackupnext.data.AppInfo
import com.cc.libackupnext.data.BackupEntry
import com.cc.libackupnext.data.BackupGroup
import com.cc.libackupnext.data.BackupProgress
import com.cc.libackupnext.data.BackupRequest
import com.cc.libackupnext.data.BackupSnapshot
import com.cc.libackupnext.data.RepoOverview
import com.cc.libackupnext.data.SnapshotKind
import com.cc.libackupnext.data.SnapshotOverride
import com.cc.libackupnext.data.UserAccount
import com.cc.libackupnext.data.prefs.BackupPrefs
import com.cc.libackupnext.service.gateway.DeviceGateway
import com.cc.libackupnext.service.gateway.RepoReader
import com.cc.libackupnext.service.gateway.RepoWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份编排的行为测试：**不依赖 Android、不绑 root 服务**。
 *
 * 这正是阶段 2~5 拆分的直接产出：`DefaultBackupUseCase` 的四个依赖
 * （reader / writer / prefs / appControl）全是接口，换成假的就能在 JVM 里跑。
 * 拆分之前这些行为只能靠真机手测。
 *
 * 重点覆盖三条容易回归的策略：
 * 1. 备份期间暂停应用、结束后一定恢复（否则应用会「打不开」）；
 * 2. 单个备份成功要导出自身 APK，批量则留给整批结束统一做；
 * 3. 清理过期备份失败不能影响备份结果。
 */
class BackupUseCaseTest {

    // ==================== 假实现 ====================

    private class FakePrefs(
        override var retentionLimit: Int = 0,
        override var backupSelf: Boolean = false,
    ) : BackupPrefs {
        override var keepScreenOn: Boolean = false
        override var fetchIconFromRepo: Boolean = false
        private val extraDirsState = MutableStateFlow<Set<String>>(emptySet())
        override var extraDirs: Set<String>
            get() = extraDirsState.value
            set(value) {
                extraDirsState.value = value
            }
        override val extraDirsFlow: StateFlow<Set<String>> = extraDirsState.asStateFlow()
        private val paused = mutableSetOf<String>()

        override fun addPausedApp(key: String) { paused += key }
        override fun removePausedApp(key: String) { paused -= key }
        override fun pausedApps(): Set<String> = paused.toSet()
        override fun clearPausedApps() { paused.clear() }
    }

    /** 只关心暂停 / 恢复；其余设备操作在备份编排里用不到。 */
    private class FakeDevice : DeviceGateway {
        var paused = false
        var resumed = false

        override suspend fun pause(packageName: String, userId: Int): Boolean {
            paused = true
            return true
        }

        override suspend fun resume(packageName: String, userId: Int) {
            paused = false
            resumed = true
        }

        override suspend fun stop(packageName: String, userId: Int) = Unit

        override suspend fun setWifiEnabled(enabled: Boolean): Result<Unit> = TODO()
        override suspend fun getUsers(): List<UserAccount> = TODO()
        override suspend fun getInstalledApps(userId: Int): List<AppInfo> = TODO()
        override suspend fun getIcon(packageName: String, userId: Int): ByteArray? = TODO()
        override suspend fun forceStop(packageName: String, userId: Int) = TODO()
        override suspend fun freeze(packageName: String, userId: Int) = TODO()
        override suspend fun unfreeze(packageName: String, userId: Int) = TODO()
        override suspend fun clearData(packageName: String, userId: Int) = TODO()
        override suspend fun uninstall(packageName: String, userId: Int) = TODO()
    }

    private class FakeWriter : RepoWriter {
        var writeResult: Result<Unit> = Result.success(Unit)
        var writeCount = 0
        var exported = false
        var deletedIds: List<String> = emptyList()
        var lastBatchId: String? = null

        override suspend fun write(
            request: BackupRequest,
            overrides: Map<String, SnapshotOverride>,
            deleteIds: List<String>,
            batchId: String?,
            operationId: Long,
            iconAlreadyBackedUp: Boolean?,
            parentIds: Map<SnapshotKind, String>?,
            onProgress: (BackupProgress) -> Unit
        ): Result<Unit> {
            writeCount++
            lastBatchId = batchId
            return writeResult
        }

        override suspend fun exportSelfApk() { exported = true }

        override suspend fun deleteSnapshots(ids: List<String>): Result<Unit> {
            deletedIds = ids
            return Result.success(Unit)
        }

        override suspend fun restoreSnapshots(
            app: AppInfo,
            snapshots: List<BackupSnapshot>,
            onProgress: (BackupProgress) -> Unit
        ): Result<Unit> = TODO()

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
    }

    private class FakeReader : RepoReader {
        var snapshotsResult: Result<List<BackupSnapshot>> = Result.success(emptyList())

        override suspend fun listSnapshots(packageName: String?): Result<List<BackupSnapshot>> =
            snapshotsResult

        override suspend fun repoOverview(): Result<List<RepoOverview>> = TODO()
        override suspend fun loadEntries(
            packageName: String,
            appName: String
        ): Result<List<BackupEntry>> = TODO()

        override suspend fun backupApps(userId: Int, overview: List<RepoOverview>?): List<AppInfo> =
            TODO()

        override suspend fun backupOverview(
            appNames: Map<String, String>,
            overview: List<RepoOverview>?
        ): List<AppBackupSummary> = TODO()

        override suspend fun backupOverviewResult(
            appNames: Map<String, String>
        ): Result<List<AppBackupSummary>> = TODO()

        override suspend fun loadIcon(packageName: String): Result<ByteArray> = TODO()
        override suspend fun systemDataCounts(): Map<SnapshotKind, Int> = TODO()
    }

    // ==================== 造数据 ====================

    private fun app() = AppInfo(
        name = "测试",
        packageName = "com.test",
        version = "1.0.0",
        versionCode = 1,
        installTime = 0,
        updateTime = 0,
        userId = 0,
        isSystem = false,
        isFrozen = false
    )

    private fun useCase(
        reader: FakeReader = FakeReader(),
        writer: FakeWriter = FakeWriter(),
        prefs: FakePrefs = FakePrefs(),
        control: FakeDevice = FakeDevice(),
    ) = DefaultBackupUseCase(reader, writer, prefs, control)

    // ==================== 用例 ====================

    @Test
    fun `没勾选任何内容时直接失败，不碰仓库`() = runBlocking {
        val writer = FakeWriter()
        val result = useCase(writer = writer).backup(app(), emptySet())

        assertTrue(result.isFailure)
        assertEquals(0, writer.writeCount)
    }

    @Test
    fun `备份期间暂停应用，结束后一定恢复`() = runBlocking {
        val control = FakeDevice()
        val result = useCase(control = control).backup(app(), setOf(BackupGroup.APK))

        assertTrue(result.isSuccess)
        // 结束后必须恢复，否则用户看到的是「应用打不开」
        assertTrue("备份结束后必须恢复应用", control.resumed)
        assertFalse(control.paused)
    }

    @Test
    fun `备份失败也要恢复应用`() = runBlocking {
        val control = FakeDevice()
        val writer = FakeWriter().apply {
            writeResult = Result.failure(IllegalStateException("仓库满了"))
        }

        val result = useCase(writer = writer, control = control)
            .backup(app(), setOf(BackupGroup.APK))

        assertTrue(result.isFailure)
        assertTrue("失败路径同样要恢复应用", control.resumed)
    }

    @Test
    fun `单个备份成功后导出自身 APK`() = runBlocking {
        val writer = FakeWriter()
        useCase(writer = writer).backup(app(), setOf(BackupGroup.APK))

        assertTrue("单个备份要顺手把自身安装包放进仓库", writer.exported)
    }

    @Test
    fun `批量备份不导出自身，留给整批结束后统一做`() = runBlocking {
        val writer = FakeWriter()
        useCase(writer = writer).backup(
            app(), setOf(BackupGroup.APK), batchId = "batch-1"
        )

        assertFalse("批量模式由调用方统一导出", writer.exported)
        assertEquals("batch-1", writer.lastBatchId)
    }

    @Test
    fun `清理失败不影响备份结果`() = runBlocking {
        // 保留数开启，但查快照失败 —— 清理是收尾，不能把备份判成失败
        val prefs = FakePrefs(retentionLimit = 1)
        val reader = FakeReader().apply {
            snapshotsResult = Result.failure(IllegalStateException("查快照失败"))
        }
        val writer = FakeWriter()

        val result = useCase(reader = reader, writer = writer, prefs = prefs)
            .backup(app(), setOf(BackupGroup.APK), knownBackupCount = 5)

        assertTrue("清理失败不该回滚备份", result.isSuccess)
        assertEquals("清理失败时返回 0 条", 0, result.getOrNull())
    }

    @Test
    fun `没到保留上限时跳过清理，不查仓库`() = runBlocking {
        val prefs = FakePrefs(retentionLimit = 5)
        val reader = FakeReader()
        val writer = FakeWriter()

        // 已知 2 行，加这次是 3，没到 5
        val result = useCase(reader = reader, writer = writer, prefs = prefs)
            .backup(app(), setOf(BackupGroup.APK), knownBackupCount = 2)

        assertTrue(result.isSuccess)
        assertEquals("不该删任何东西", emptyList<String>(), writer.deletedIds)
    }
}
