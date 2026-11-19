package com.cc.libackupnext.data

/**
 * 开源软件声明页的唯一数据源：名称 / 开源协议 / 分组 / 上游仓库。
 *
 * 协议取自各上游仓库的 LICENSE 文件与 crates.io 的 license 字段（Rust crate）；
 * 「A / B」表示双协议任选其一。Metric 仓库没有 LICENSE 文件，如实写未标注，
 * 不替上游猜。这里不维护版本号：升级依赖版本不需要同步改清单。
 * 分组顺序即页面顺序：参考项目在最前，其余按来源分组。
 */
data class OpenSourceLib(
    val name: String,
    val license: String,
    val group: String,
    val url: String
)

private const val ANDROIDX_URL = "https://github.com/androidx/androidx"
private const val RUSTIC_URL = "https://github.com/rustic-rs/rustic_core"
private const val APACHE = "Apache License 2.0"
private const val MIT = "MIT License"
private const val DUAL = "$MIT / $APACHE"

/** 顺序即页面上的分组与条目顺序；Go 侧只列 rclone 本身，不展开 go.mod 的间接依赖。 */
val openSourceLibs: List<OpenSourceLib> = listOf(
    // ===== 参考项目（同时在关于页特别鸣谢里出现），按用户要求放最前 =====
    OpenSourceLib(
        "数据备份 DataBackup", "GNU GPL v3.0", "参考项目",
        "https://github.com/XayahSuSuSu/Android-DataBackup"
    ),
    OpenSourceLib("备份工具箱", "", "参考项目", ""),
    OpenSourceLib(
        "Metric", "", "参考项目",
        "https://github.com/ItosEO/Metric"
    ),

    // ===== 备份引擎：rustic_bridge / rclone_bridge（Rust）=====
    OpenSourceLib("rustic_core", DUAL, "备份引擎 · Rust", RUSTIC_URL),
    OpenSourceLib(
        "rustic_backend", DUAL, "备份引擎 · Rust",
        "$RUSTIC_URL/tree/main/crates/backend"
    ),
    OpenSourceLib(
        "jni", DUAL, "备份引擎 · Rust",
        "https://github.com/jni-rs/jni-rs"
    ),
    OpenSourceLib(
        "serde / serde_json", DUAL, "备份引擎 · Rust",
        "https://github.com/serde-rs/serde"
    ),
    OpenSourceLib(
        "rayon", DUAL, "备份引擎 · Rust",
        "https://github.com/rayon-rs/rayon"
    ),
    OpenSourceLib(
        "reqwest", DUAL, "备份引擎 · Rust",
        "https://github.com/seanmonstar/reqwest"
    ),
    OpenSourceLib(
        "libc", DUAL, "备份引擎 · Rust",
        "https://github.com/rust-lang/libc"
    ),
    OpenSourceLib(
        "jiff", "The Unlicense / $MIT", "备份引擎 · Rust",
        "https://github.com/BurntSushi/jiff"
    ),

    // ===== 远程后端（Go）：只列 rclone，go.mod 里的间接依赖不逐条展开 =====
    OpenSourceLib(
        "rclone", MIT, "远程后端 · Go",
        "https://github.com/rclone/rclone"
    ),

    // ===== 界面与 Android 库 =====
    OpenSourceLib("Jetpack Compose", APACHE, "界面与 Android 库", ANDROIDX_URL),
    OpenSourceLib("Material 3", APACHE, "界面与 Android 库", ANDROIDX_URL),
    OpenSourceLib("Material Icons Extended", APACHE, "界面与 Android 库", ANDROIDX_URL),
    OpenSourceLib("AndroidX Activity Compose", APACHE, "界面与 Android 库", ANDROIDX_URL),
    OpenSourceLib("AndroidX Core KTX", APACHE, "界面与 Android 库", ANDROIDX_URL),
    OpenSourceLib("AndroidX Lifecycle", APACHE, "界面与 Android 库", ANDROIDX_URL),
    OpenSourceLib(
        "material-kolor", MIT, "界面与 Android 库",
        "https://github.com/jordond/MaterialKolor"
    ),
    OpenSourceLib(
        "libsu", APACHE, "界面与 Android 库",
        "https://github.com/topjohnwu/libsu"
    ),
    OpenSourceLib(
        "Refine", MIT, "界面与 Android 库",
        "https://github.com/RikkaApps/HiddenApiRefinePlugin"
    ),
    OpenSourceLib(
        "RikkaX ParcelableList", MIT, "界面与 Android 库",
        "https://github.com/RikkaApps/RikkaX"
    ),
    OpenSourceLib(
        "kotlinx-serialization", APACHE, "界面与 Android 库",
        "https://github.com/Kotlin/kotlinx.serialization"
    ),
    OpenSourceLib(
        "Kotlin", APACHE, "界面与 Android 库",
        "https://github.com/JetBrains/kotlin"
    ),

    // ===== 仅测试 / 仅编译期 =====
    OpenSourceLib(
        "JUnit", "Eclipse Public License 1.0", "仅测试 / 编译期",
        "https://github.com/junit-team/junit4"
    ),
    OpenSourceLib(
        "AndroidX Test / Espresso", APACHE, "仅测试 / 编译期",
        "https://github.com/android/android-test"
    ),
    OpenSourceLib("Compose UI Test", APACHE, "仅测试 / 编译期", ANDROIDX_URL),
    OpenSourceLib(
        "AOSP Hidden API", APACHE, "仅测试 / 编译期",
        "https://github.com/aosp-mirror/platform_frameworks_base"
    )
)
