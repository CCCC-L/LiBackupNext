package com.cc.libackupnext.data

/**
 * 批量页的两种模式。
 */
enum class BackupBatchMode {
    /** 新建应用备份：列出所有已安装应用。 */
    CREATE,

    /** 管理应用备份：只列出仓库里有备份的应用，可批量删除 / 覆盖 / 恢复。 */
    MANAGE
}
