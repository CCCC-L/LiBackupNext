package com.cc.libackupnext.data

/**
 * 模拟卷（`/storage/emulated/<userId>`）的路径换算。
 *
 * 整个多用户文件夹备份只靠这里的三条规则，别处不许再手写 `/storage/emulated` 前缀：
 *
 * 1. **配置层存「用户无关」的路径** —— [normalize] 把 user 段统一成主用户 0，
 *    所以「备份 DCIM」这件事在所有用户视角下是同一个配置项；
 * 2. **展示 / 备份 / 恢复前展开** —— [forUser] 换成当前用户空间里的绝对路径，
 *    展开一次之后全链路只认绝对路径，下游不再需要 `currentUserId`；
 * 3. **交给 root 读写前换成物理路径** —— [physical]，`/storage/emulated/N` 是 FUSE
 *    视图，root 在那个路径上会被权限挡住，只有 `/data/media/N` 才读得到。
 *
 * 不归属任何用户的路径（SD 卡、`/mnt` 共享挂载）四个函数一律原样返回 ——
 * 它们本来就对所有用户可见，换算反而会算错。
 */
object MediaPath {

    /** `/storage/emulated/<userId>[/剩余部分]` */
    private val EMULATED = Regex("""^/storage/emulated/(\d+)(/.*)?$""")

    /** `/data/media/<userId>[/剩余部分]` —— 只用于反查归属，不参与换算 */
    private val PHYSICAL = Regex("""^/data/media/(\d+)(/.*)?$""")

    /** 主用户 id：它的空间就是 `normalize` 之后的基准。 */
    const val PRIMARY_USER_ID = 0

    /**
     * 路径归属的用户。
     *
     * 返回 `null` 表示**不归属任何用户**（SD 卡、共享挂载、普通系统路径），
     * 而不是「属于主用户」—— 这两件事在界面上不一样：前者在哪个视角下都正常显示。
     */
    fun userOf(path: String): Int? =
        (EMULATED.find(path) ?: PHYSICAL.find(path))
            ?.groupValues?.get(1)
            ?.toIntOrNull()

    /**
     * 配置层归一：`/storage/emulated/<任意>/X` → `/storage/emulated/0/X`。
     *
     * 幂等，顺手去掉尾斜杠 —— 配置键不该因为多打一个 `/` 就变成另一条。
     * 不归属用户的路径原样返回。写进 `extraDirs` 之前必须过一遍，
     * 否则「在用户 10 里加的 DCIM」和「在用户 0 里加的 DCIM」会变成两条配置。
     */
    fun normalize(path: String): String =
        EMULATED.find(path)?.let {
            "/storage/emulated/$PRIMARY_USER_ID${it.groupValues[2].trimEnd('/')}"
        } ?: path

    /** 展开到 [userId] 的空间；不归属用户的路径原样返回。 */
    fun forUser(path: String, userId: Int): String =
        EMULATED.find(normalize(path))?.let {
            "/storage/emulated/$userId${it.groupValues[2].trimEnd('/')}"
        } ?: path

    /** root 进程能读写的物理路径；不归属用户的路径原样返回。幂等。 */
    fun physical(path: String): String =
        EMULATED.find(path)?.let {
            "/data/media/${it.groupValues[1]}${it.groupValues[2].trimEnd('/')}"
        } ?: path

    /**
     * 备份身份：查仓库 / 查备份概况时用的 key。
     *
     * 值和 [normalize] 一样，单独起名是因为语义不同 —— 它不是「配置怎么存」，
     * 而是「这条备份属于哪个文件夹」。**目录的备份列表跨用户共享**（和应用按包名
     * 共享是同一条规则），所以拿当前用户空间里的绝对路径去查仓库之前要先过这里。
     * 应用包名、SD 卡路径都原样返回。
     */
    fun backupKey(path: String): String = normalize(path)
}
