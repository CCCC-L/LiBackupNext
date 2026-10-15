# 锂备份 · LiBackupNext

一个基于 [rustic](https://github.com/rustic-rs/rustic) 的Android应用备份工具。
需要 **root**，支持批量备份、多用户、增量去重。

```
主进程（Compose UI）
  AppListScreen / AppManagerScreen / AppBackupScreen
        │  AIDL：阻塞调用 + 进度回调
        ▼
:root 进程
  RusticService（快照增删查） / AppManagerService（应用操作）
        │  JNI + 共享内存
        ▼
librustic_bridge.so（rustic_core / rustic_backend）
        │
        ▼
仓库目录（本地文件夹，restic 格式）
```

## 特性

- **整应用备份**：apk / obb / user 数据 / user_de 数据 / 外部存储数据 / 已授予的运行时权限
- **批量操作**：多选应用一次备份、恢复、删除、覆盖
- **增量去重**：rustic 的内容寻址存储，重复数据只存一份；重复备份只上传变化的部分
- **多用户**：备份 / 恢复指定 Android 用户（工作资料、分身）
- **冻结 / 卸载 / 清空数据**：不离开应用就能处理
- **应用图标**：备份时顺带存一份图标快照，应用卸载后列表里仍能显示
- **进度通知**：前台服务 + 流体云（Live Update），后台也能看到进度
- **仓库整理**：回收已删除备份占用的空间，顺带清理没有备份的应用残留的图标

## 环境要求

| 项 | 版本 |
|---|---|
| Android | 15（API 35）及以上 |
| root | Magisk / KernelSU 等 |
| JDK | 21 |
| Android SDK | compileSdk 37（缺失的组件 AGP 会自动下载） |
| NDK | 30.0.16248370 |
| Rust | stable + `aarch64-linux-android` target |
| cargo-ndk | 任意近期版本 |

## 构建

```bash
# 首次：装 cargo-ndk 和 Android target
rustup target add aarch64-linux-android
cargo install cargo-ndk

# debug 包（arm64 + x86_64，模拟器也能跑）
./gradlew :app:assembleDebug

# 正式包（默认两个 ABI）
./gradlew :app:assembleRelease

# 只要 arm64，编译时间减半
./gradlew :app:assembleRelease -PrustAbis=arm64-v8a

# 打版本号（不传就是 versionCode=1 / versionName=1.0）
./gradlew :app:assembleRelease -PversionName=1.2.3 -PversionCode=10203
```

## 仓库结构

```
app/
  src/main/java/com/cc/libackupnext/
    data/          纯数据模型 + SharedPreferences（AppPrefs / BackupEntry / SnapshotMeta）
    entity/        AppInfo
    service/       业务层：RusticService（快照）、AppManagerService（应用操作）、
                   RootManager（授权）、RootServiceManager（root 服务连接）、ProgressService（前台服务）
    ui/            Compose 界面：screen/（页面）、dialog/（弹窗）、util/（卡片 / 图标 / 格式化）
  src/main/aidl/   IRusticService / IAppManagerService / IBackupProgressCallback
  src/main/rustic_bridge/   Rust 侧 JNI 实现（lib.rs）+ Cargo.toml
hidden-api/        隐藏 API 的编译期 stub（compileOnly，不进 APK）
```

分层约定：**UI 只负责展示和临时交互状态**，请求拼装、仓库判断、分组换算都在 `service` 层；
Rust 侧只做快照 CRUD，不掺业务语义。

## 备份格式

每次备份按「类型」各生成一个快照，label 形如 `<包名>:<类型>`：

| 类型 | 内容                                                         |
|---|--------------------------------------------------------------|
| `apk` | 安装包（`as_path` 隐藏真实路径）                             |
| `obb` | `/data/media/<用户>/Android/obb/<包名>`（跟着 apk 一起备份） |
| `user` | `/data/user/<用户>/<包名>`                                   |
| `user_de` | `/data/user_de/<用户>/<包名>`                                |
| `data` | `/data/media/<用户>/Android/data/<包名>`                     |
| `permissions` | 运行时权限列表（跟着 user 一起备份）                         |
| `icon` | 应用图标，一个包只存一份                                     |

同一次备份的所有快照共用一条 tag：`应用名|版本|用户名|时间戳`，靠时间戳把它们归成一组。
（拼成一个 tag 是因为 rustic 的 tags 是 `BTreeSet`，拆开会被排序丢顺序。）

## 依赖

- [rustic](https://github.com/rustic-rs/rustic) — 仓库格式与备份引擎
- [libsu](https://github.com/topjohnwu/libsu) — root 服务
- [Refine](https://github.com/RikkaW/Refine) — 隐藏 API 访问
- [rikkax.parcelablelist](https://github.com/RikkaW/ParcelableList) — AIDL 传 Parcelable 列表
- Jetpack Compose / Material 3
