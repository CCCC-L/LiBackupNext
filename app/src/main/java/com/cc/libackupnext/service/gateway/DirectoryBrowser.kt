package com.cc.libackupnext.service.gateway

import com.cc.libackupnext.data.RemoteRepo

/**
 * 目录浏览：给仓库配置表单里的文件夹选择器用。
 *
 * 本地和远程各一个入口，但都不暴露「后端怎么连」——调用方只关心
 * 「这一层下面有哪些子目录」。rclone 的 serve / 后端类型选择留在这个接口的实现里。
 */
interface DirectoryBrowser {

    /**
     * 列出本地某一层下的子目录。
     *
     * @param create 非空表示「顺手建出这个子目录」再返回它的内容
     */
    suspend fun listLocalDirs(path: String, create: String? = null): Result<List<String>>

    /**
     * 列出远程某一层下的子目录；[path] 是服务内的相对路径，从 "/" 开始。
     *
     * @param create 非空表示「顺手建出这个子目录」再返回它的内容
     */
    suspend fun listRemoteDirs(
        repo: RemoteRepo,
        path: String,
        create: String? = null
    ): Result<List<String>>
}
