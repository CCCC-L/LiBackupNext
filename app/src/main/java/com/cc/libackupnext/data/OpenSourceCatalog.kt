package com.cc.libackupnext.data

/**
 * 开源软件声明页的唯一数据源：按分组声明的库清单。
 *
 * 协议取自各上游仓库的 LICENSE 文件与 crates.io 的 license 字段（Rust crate）；
 * 「A / B」表示双协议任选其一。Metric 仓库没有 LICENSE 文件，如实写未标注，
 * 不替上游猜。这里不维护版本号：升级依赖版本不需要同步改清单。
 */
data class OpenSourceLib(
    val name: String,
    val license: String,
    val url: String
)

/** 页面上的一组卡片：组标题 + 组内的库。 */
data class OpenSourceGroup(
    val title: String,
    val libs: List<OpenSourceLib>
)

private const val ANDROIDX_URL = "https://github.com/androidx/androidx"
private const val RUSTIC_URL = "https://github.com/rustic-rs/rustic_core"
private const val APACHE = "Apache License 2.0"
private const val MIT = "MIT License"
private const val DUAL = "$MIT / $APACHE"

/** 顺序即页面上的分组与条目顺序；Go 侧只列 rclone 本身，不展开 go.mod 的间接依赖。 */
val openSourceGroups: List<OpenSourceGroup> = listOf(
    // ===== 参考项目（同时在关于页特别鸣谢里出现），按用户要求放最前 =====
    OpenSourceGroup(
        title = "参考项目",
        libs = listOf(
            OpenSourceLib(
                "数据备份 DataBackup", "GNU GPL v3.0",
                "https://github.com/XayahSuSuSu/Android-DataBackup"
            ),
            OpenSourceLib("备份工具箱", "", ""),
            OpenSourceLib(
                "Metric", "",
                "https://github.com/ItosEO/Metric"
            )
        )
    ),

    // ===== 备份引擎：rustic_bridge / rclone_bridge（Rust）=====
    OpenSourceGroup(
        title = "备份引擎 · Rust",
        libs = listOf(
            OpenSourceLib("rustic_core", DUAL, RUSTIC_URL),
            OpenSourceLib(
                "rustic_backend", DUAL,
                "$RUSTIC_URL/tree/main/crates/backend"
            ),
            OpenSourceLib(
                "jni", DUAL,
                "https://github.com/jni-rs/jni-rs"
            ),
            OpenSourceLib(
                "serde / serde_json", DUAL,
                "https://github.com/serde-rs/serde"
            ),
            OpenSourceLib(
                "rayon", DUAL,
                "https://github.com/rayon-rs/rayon"
            ),
            OpenSourceLib(
                "reqwest", DUAL,
                "https://github.com/seanmonstar/reqwest"
            ),
            OpenSourceLib(
                "libc", DUAL,
                "https://github.com/rust-lang/libc"
            ),
            OpenSourceLib(
                "jiff", "The Unlicense / $MIT",
                "https://github.com/BurntSushi/jiff"
            )
        )
    ),

    // ===== 远程后端（Go）：只列 rclone，go.mod 里的间接依赖不逐条展开 =====
    OpenSourceGroup(
        title = "远程后端 · Go",
        libs = listOf(
            OpenSourceLib("rclone", MIT, "https://github.com/rclone/rclone")
        )
    ),

    // ===== 界面与 Android 库 =====
    OpenSourceGroup(
        title = "界面与 Android 库",
        libs = listOf(
            OpenSourceLib("Jetpack Compose", APACHE, ANDROIDX_URL),
            OpenSourceLib("Material 3", APACHE, ANDROIDX_URL),
            OpenSourceLib("Material Icons Extended", APACHE, ANDROIDX_URL),
            OpenSourceLib("AndroidX Activity Compose", APACHE, ANDROIDX_URL),
            OpenSourceLib("AndroidX Core KTX", APACHE, ANDROIDX_URL),
            OpenSourceLib("AndroidX Lifecycle", APACHE, ANDROIDX_URL),
            OpenSourceLib(
                "material-kolor", MIT,
                "https://github.com/jordond/MaterialKolor"
            ),
            OpenSourceLib(
                "libsu", APACHE,
                "https://github.com/topjohnwu/libsu"
            ),
            OpenSourceLib(
                "Refine", MIT,
                "https://github.com/RikkaApps/HiddenApiRefinePlugin"
            ),
            OpenSourceLib(
                "RikkaX ParcelableList", MIT,
                "https://github.com/RikkaApps/RikkaX"
            ),
            OpenSourceLib(
                "kotlinx-serialization", APACHE,
                "https://github.com/Kotlin/kotlinx.serialization"
            ),
            OpenSourceLib(
                "Kotlin", APACHE,
                "https://github.com/JetBrains/kotlin"
            )
        )
    ),

    // ===== 仅测试 / 仅编译期 =====
    OpenSourceGroup(
        title = "仅测试 / 编译期",
        libs = listOf(
            OpenSourceLib(
                "JUnit", "Eclipse Public License 1.0",
                "https://github.com/junit-team/junit4"
            ),
            OpenSourceLib(
                "AndroidX Test / Espresso", APACHE,
                "https://github.com/android/android-test"
            ),
            OpenSourceLib("Compose UI Test", APACHE, ANDROIDX_URL),
            OpenSourceLib(
                "AOSP Hidden API", APACHE,
                "https://github.com/aosp-mirror/platform_frameworks_base"
            )
        )
    )
)
