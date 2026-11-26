//! Android <-> rustic 的 JNI 桥接。
//!
//! 与 Kotlin 侧只通过一块 SharedMemory 交互，JNI 参数只有一个直接缓冲区：
//!
//! ```text
//! [0, 64KB)        请求 / 响应帧
//! [64KB, 128KB)    备份进度帧（备份过程中不断刷新）
//! ```
//!
//! 帧格式统一为「大端 i32 长度 + UTF-8 JSON」。
//! 仓库地址和密码由 `nativeSetRepo` 单独注入一次（绑定 / 初始化成功 / 换仓库时），
//! 业务请求 JSON 里只放命令本身的参数，不再带 path / password。
//! 唯一例外是 `initRepo`：它带候选 path / password，只用来建库 / 校验密码，
//! 不写全局凭据；成功与否由主进程判断，成功才保存并重新注入。
//! 响应：`{"ok":true}` 或 `{"ok":false,"error":"..."}`
//! 进度：`{"stage":"apk","done":123,"total":456,"finished":false}`

use std::collections::BTreeMap;
use std::fmt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use jni::objects::{JByteBuffer, JObject, JString};
use jni::sys::jint;
use jni::EnvUnowned;
use rustic_backend::choose::BackendOptions;
use rustic_core::{
    BackupOptions, ConfigOptions, Credentials, KeyOptions, LsOptions, PathList, Progress,
    ProgressBars, ProgressType, Repository, RepositoryBackends, RepositoryOptions, RusticProgress,
    SnapshotOptions, StringList,
};
use rustic_core::repofile::SnapshotFile;
use rustic_core::repofile::NodeType;
use rustic_core::{LimitOption, PruneOptions};
// ErrorReportingBackend 委托读写的类型。0.14 起 backend / id 是 pub(crate)，只能从根部导入。
use rustic_core::{BytesList, FileType, Id, ReadBackend, WriteBackend};
use bytes::Bytes;
use serde::{Deserialize, Serialize};

use std::ffi::c_void;
use std::io::Write;
use std::panic;
use std::sync::OnceLock;

// ==================== native 崩溃留痕 ====================

/// root 服务传进来的日志目录（filesDir/logs）。panic 时往这里追加 rust-panic.txt。
static LOG_DIR: OnceLock<String> = OnceLock::new();

/// `RusticServer.nativeSetLogDir(path: String): Int`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeSetLogDir<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
) -> jint {
    let outcome = env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let value: String = env.get_string(&path)?.into();
        let _ = LOG_DIR.set(value);
        Ok(0)
    });

    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

/// release 是 panic=abort，catch_unwind 拦不住；只能在 abort 之前把信息写下来。
/// 同时写 logcat（导出包会抓）和文件（logcat 被冲掉也还在）。
fn install_panic_hook() {
    panic::set_hook(Box::new(|info| {
        let payload = if let Some(text) = info.payload().downcast_ref::<&str>() {
            (*text).to_string()
        } else if let Some(text) = info.payload().downcast_ref::<String>() {
            text.clone()
        } else {
            "unknown payload".to_string()
        };
        let location = info
            .location()
            .map(|loc| format!("{}:{}:{}", loc.file(), loc.line(), loc.column()))
            .unwrap_or_else(|| "unknown".to_string());
        let thread = std::thread::current().name().unwrap_or("?").to_string();
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);

        let text = format!(
            "[libackup-rust] PANIC (process will abort)\ntime(ms): {stamp}\nthread: {thread}\nlocation: {location}\npayload: {payload}\n"
        );

        android_log_fatal(&text);

        if let Some(dir) = LOG_DIR.get() {
            let _ = std::fs::create_dir_all(dir);
            let file = std::path::Path::new(dir).join("rust-panic.txt");
            if let Ok(mut out) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(file)
            {
                let _ = write!(out, "===== {stamp} =====\n{text}\n");
                let _ = out.sync_all();
            }
        }
    }));
}

/// 真机走 liblog，panic 信息进 logcat（导出包会抓）。
#[cfg(target_os = "android")]
fn android_log_fatal(text: &str) {
    use std::ffi::{c_char, c_int, CString};

    extern "C" {
        fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
    }
    if let (Ok(tag), Ok(fmt), Ok(msg)) = (
        CString::new("LiBackupRust"),
        CString::new("%s"),
        CString::new(text),
    ) {
        // 6 = ANDROID_LOG_FATAL
        unsafe {
            __android_log_print(6, tag.as_ptr(), fmt.as_ptr(), msg.as_ptr());
        }
    }
}

/// 桌面（跑 `cargo test`）没有 liblog，链接不到 `__android_log_print`，
/// 测试二进制会因为一个符号直接链接失败。桌面只需要看得到这句话。
#[cfg(not(target_os = "android"))]
fn android_log_fatal(text: &str) {
    eprint!("{text}");
}

/// 库加载时安装 panic hook，不改动任何现有 JNI 函数名。
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: *mut c_void, _reserved: *mut c_void) -> jint {
    install_panic_hook();
    0x0001_0006 // JNI_VERSION_1_6
}

/// 进度区在共享内存里的起始位置。
/// 请求 / 响应帧可用的大小。
///
/// 快照列表要一次性塞进响应里，一条约 200 字节；以前只有 64KB（约 320 条），
/// 备份几次就会超，超了会被静默截断、客户端解析必然失败。
const RESPONSE_AREA: usize = 512 * 1024;

/// 进度区起始偏移：留够响应空间，进度环形缓冲放在后面。
const PROGRESS_OFFSET: usize = RESPONSE_AREA;

/// 进度区开头 4 字节是事件计数，后面是一圈固定大小的槽位。
const PROGRESS_COUNTER_SIZE: usize = 4;
const PROGRESS_SLOT_SIZE: usize = 256;
const PROGRESS_SLOT_COUNT: usize = (64 * 1024 - PROGRESS_COUNTER_SIZE) / PROGRESS_SLOT_SIZE;

#[derive(Deserialize)]
struct BackupRequest {
    items: Vec<BackupItem>,
    /// 备份时要排除的路径模式（gitignore 语义，`!` 开头表示排除）
    #[serde(default)]
    excludes: Vec<String>,
    /// 批量备份的批次标识。
    ///
    /// 同一次批量操作里每个应用都会带同一个值，仓库只打开一次（省掉每次
    /// 563ms 的 scrypt + 读索引）；换批次 / 不传就重新打开。
    #[serde(default)]
    batch: Option<String>,
}

#[derive(Deserialize)]
struct BackupItem {
    /// 展示用：icon / apk / obb / user / user_de / data
    stage: String,
    label: String,
    tags: Vec<String>,
    sources: Vec<String>,
    /// 快照里保存的路径前缀，用来隐藏 /data/... 这些真实目录
    #[serde(default)]
    as_path: Option<String>,
    /// 客户端预判「没有可备份内容」时给出的说明
    #[serde(default)]
    skip_reason: Option<String>,
    /// 快照备注
    #[serde(default)]
    description: Option<String>,
    /// 上一次同类快照的 id，作为 parent 做增量备份。
    /// 不传的话 rustic 会调 `SnapshotFile::latest()`，也就是**把仓库里所有快照列一遍**
    /// 才能挑出 parent —— 每个应用每种类型各一次，批量备份时这是很大一笔开销。
    #[serde(default)]
    parent: Option<String>,
    // 过滤规则glab
    #[serde(default)]
    excludes: Vec<String>,

    #[serde(default)]
    ignore_inode: bool,
}

/// 初始化 / 校验仓库用的**候选**凭据：只跟着这一次请求走，不写全局配置。
/// `path` 现在既可以是本地路径，也可以是带协议前缀的仓库位置。
#[derive(Deserialize)]
struct InitRequest {
    path: String,
    password: String,
    /// 透传给 BackendOptions.options；本地仓库为空 map。
    #[serde(default)]
    options: BTreeMap<String, String>,
    /// true = 直接建库（主进程已判定是空仓库）；false = 只打开。
    #[serde(default)]
    force_init: bool,
}

#[derive(Deserialize)]
struct ListRequest {
    /// 包名；留空表示列出全部快照
    #[serde(default)]
    package_name: String,
}

#[derive(Deserialize)]
struct UpdateDescriptionRequest {
    ids: Vec<String>,
    #[serde(default)]
    description: Option<String>,
}

#[derive(Deserialize)]
struct DeleteRequest {
    ids: Vec<String>,
}

#[derive(Deserialize)]
struct RestoreItem {
    id: String,
    dest: String,
    /// 恢复后删除目标目录里快照没有的文件
    #[serde(default)]
    delete: bool,
    /// 这一步的快照类型（apk / user / data …）。进度帧用它标 stage，
    /// 读端才知道这些字节该挂到哪一步上。
    #[serde(default)]
    stage: String,
}

#[derive(Deserialize)]
struct RestoreRequest {
    items: Vec<RestoreItem>,
}

#[derive(Serialize, Clone)]
struct SnapshotInfo {
    id: String,
    label: String,
    tags: String,
    time: i64,
    size: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    description: Option<String>,
}

#[derive(Serialize)]
struct ListResponse {
    ok: bool,
    snapshots: Vec<SnapshotInfo>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
}

impl ListResponse {
    fn ok(snapshots: Vec<SnapshotInfo>) -> Self {
        Self {
            ok: true,
            snapshots,
            error: None,
            reason: None,
        }
    }

    fn err(failure: impl Into<Failure>) -> Self {
        let failure = failure.into();
        Self {
            ok: false,
            snapshots: Vec::new(),
            reason: failure.reason_wire(),
            error: Some(failure.message),
        }
    }
}

#[derive(Serialize)]
struct BridgeResponse {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
}

impl BridgeResponse {
    fn ok() -> Self {
        Self {
            ok: true,
            error: None,
            reason: None,
        }
    }

    fn err(failure: impl Into<Failure>) -> Self {
        Self::from(failure.into())
    }
}

impl From<Failure> for BridgeResponse {
    fn from(failure: Failure) -> Self {
        Self {
            ok: false,
            reason: failure.reason_wire(),
            error: Some(failure.message),
        }
    }
}

/// 失败原因：只回答「是什么问题」，中文文案由主进程按 wire 值查表。
///
/// 判定只在 [classify_error] 里做 —— 只有这里能看到 error_code、io 错误类型
/// 和 source 链；主进程拿到的只有一段文本，靠它猜必然出错。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Reason {
    /// 密码错：仓库结构完整，但所有 key 都用这个密码解不开
    Password,
    /// 连不上远程后端（rest 请求失败 / 超时）
    Unreachable,
    /// 远程后端拒绝访问（HTTP 401 / 403）
    Auth,
    /// 超时
    Timeout,
    /// 磁盘满
    NoSpace,
    /// 数据校验失败
    CorruptData,
}

impl Reason {
    /// 跨进程的稳定标识，和 Kotlin 的 `FailureReason.wire` 逐字对应。
    fn wire(self) -> &'static str {
        match self {
            Reason::Password => "password",
            Reason::Unreachable => "unreachable",
            Reason::Auth => "auth",
            Reason::Timeout => "timeout",
            Reason::NoSpace => "no_space",
            Reason::CorruptData => "corrupt_data",
        }
    }

    /// 传输层原因：和 Kotlin 的 `FailureReason.isTransport` 逐字对应。
    /// 只有这三类要立刻写 `interrupted` 帧并停掉写操作；密码错 / 数据损坏 / 磁盘满
    /// 是正常失败，走原来的响应路径即可。
    fn is_transport(self) -> bool {
        matches!(self, Reason::Unreachable | Reason::Auth | Reason::Timeout)
    }
}

/// 一次失败：原始文本 + 原因。
///
/// `message` 是底层原文（已去掉 Backtrace / 文档链接），不是给用户看的文案：
/// 已知原因时主进程只显示一句中文，原文只进日志；未知原因才拿它兜底。
#[derive(Debug)]
struct Failure {
    message: String,
    reason: Option<Reason>,
}

impl Failure {
    fn app(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            reason: None,
        }
    }

    /// 加一段前缀，原因不变（「备份 apk 失败：」这类上下文）。
    fn context(self, prefix: impl fmt::Display) -> Self {
        Self {
            message: format!("{prefix}{}", self.message),
            reason: self.reason,
        }
    }

    /// 响应里的 `reason` 值：只有认得出来的原因才写。
    fn reason_wire(&self) -> Option<String> {
        self.reason.map(|reason| reason.wire().to_string())
    }
}

/// 业务代码里大量 `ok_or_else(|| "...".to_string())` / `format!` 都还能用 `?` 直接抛。
impl From<String> for Failure {
    fn from(message: String) -> Self {
        Self::app(message)
    }
}

impl From<&str> for Failure {
    fn from(message: &str) -> Self {
        Self::app(message)
    }
}

/// 只用于拼接；原因要靠 [Failure::context] 传递，`format!` 之后会丢。
impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

/// 五类文件的存在情况；「完整 / 缺失」由主进程判定。
#[derive(Clone, Copy)]
struct RepoLayout {
    config: bool,
    keys: bool,
    data: bool,
    index: bool,
    snapshots: bool,
}

#[derive(Deserialize)]
struct RepoLayoutRequest {
    path: String,
    #[serde(default)]
    options: BTreeMap<String, String>,
}

#[derive(Serialize)]
struct LayoutResponse {
    ok: bool,
    config: bool,
    keys: bool,
    data: bool,
    index: bool,
    snapshots: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
}

impl LayoutResponse {
    fn ok(layout: RepoLayout) -> Self {
        Self {
            ok: true,
            config: layout.config,
            keys: layout.keys,
            data: layout.data,
            index: layout.index,
            snapshots: layout.snapshots,
            error: None,
            reason: None,
        }
    }

    fn err(failure: Failure) -> Self {
        Self {
            ok: false,
            config: false,
            keys: false,
            data: false,
            index: false,
            snapshots: false,
            reason: failure.reason_wire(),
            error: Some(failure.message),
        }
    }
}

// ==================== 仓库凭据 ====================

/// 仓库地址、密码和后端选项：由 `nativeSetRepo` 在绑定 / 初始化成功 / 换仓库时写入一次。
///
/// 业务请求 JSON 里不再带 path / password，都从这里读；`initRepo` 是例外，
/// 它自带的候选凭据只用于本次建库 / 校验，不写进这里。
/// `generation` 每次注入递增，三个仓库缓存拿它当 key —— 换仓库 / 改密码后
/// 即使缓存没来得及清，也不会错误复用旧仓库实例。
#[derive(Clone)]
struct RepoConfig {
    /// 本地文件系统路径，或逻辑仓库标识（remote: 等）
    path: String,
    password: String,
    /// 透传给 BackendOptions.options 的后端选项
    options: BTreeMap<String, String>,
    generation: u64,
}

static REPO_CONFIG: Mutex<Option<RepoConfig>> = Mutex::new(None);
static REPO_GENERATION: AtomicU64 = AtomicU64::new(0);

/// 空路径 / 空密码视为「没有配置」，避免拿半截凭据去开仓库。
fn set_repo(path: &str, password: &str, options: BTreeMap<String, String>) {
    let generation = REPO_GENERATION.fetch_add(1, Ordering::SeqCst) + 1;
    if let Ok(mut guard) = REPO_CONFIG.lock() {
        *guard = if path.is_empty() || password.is_empty() {
            None
        } else {
            Some(RepoConfig {
                path: path.to_string(),
                password: password.to_string(),
                options,
                generation,
            })
        };
    }

    // 换了仓库 / 密码 / 后端选项：三级缓存里的 Repository 全部作废。
    // restic 服务由 rclone_bridge 按 key（位置 + inline 配置）自行切换，这里不再碰。
    clear_repo_cache();
    invalidate_indexed_repo();
    invalidate_backup_repo();
}

fn repo_config() -> Result<RepoConfig, Failure> {
    REPO_CONFIG
        .lock()
        .ok()
        .and_then(|guard| (*guard).clone())
        .ok_or_else(|| Failure::app("仓库尚未配置，请先初始化仓库"))
}

/// 缓存标识：路径 + 凭据代数。密码不进 key，代数已经能区分每次注入。
fn repo_key(config: &RepoConfig) -> String {
    format!("{}\0{}", config.path, config.generation)
}

/// `RusticServer.nativeSetRepo(path, password, options): Int`
///
/// options 是 JSON 对象字符串；空串 / 非法 JSON 都退化成空 map（本地仓库就是这种情况）。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeSetRepo<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    password: JString<'local>,
    options: JString<'local>,
) -> jint {
    let outcome = env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let path: String = env.get_string(&path)?.into();
        let password: String = env.get_string(&password)?.into();
        let options: String = env.get_string(&options)?.into();
        let options: BTreeMap<String, String> =
            serde_json::from_str(&options).unwrap_or_default();
        set_repo(&path, &password, options);
        Ok(0)
    });

    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

// ==================== 初始化 ====================

/// `RusticServer.nativeInitRepo(buffer: ByteBuffer): Int`
///
/// 请求：`{"path":"仓库位置","password":"候选密码","options":{}}`，只用于本次建库 /
/// 校验密码，不读写全局仓库凭据。返回 0 成功，1 业务失败，2 共享内存不可用。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeInitRepo<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    let outcome = env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let ptr = env.get_direct_buffer_address(&buffer)?;
        let capacity = env.get_direct_buffer_capacity(&buffer)?;

        if ptr.is_null() || capacity <= 4 {
            return Ok(2);
        }

        Ok(handle_init(ptr, capacity))
    });

    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

/// `RusticServer.nativeRepoLayout(buffer: ByteBuffer): Int`
///
/// 请求 `{"path","options"}`，只查五类文件在不在，不需要密码、不打开仓库。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeRepoLayout<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        let request = match read_frame_at(ptr, capacity, 0).and_then(|bytes| {
            serde_json::from_slice::<RepoLayoutRequest>(&bytes)
                .map_err(|e| Failure::app(e.to_string()))
        }) {
            Ok(request) => request,
            Err(failure) => {
                write_layout_response(ptr, capacity, LayoutResponse::err(failure));
                return 1;
            }
        };

        match repo_layout(&request.path, &request.options) {
            Ok(layout) => {
                write_layout_response(ptr, capacity, LayoutResponse::ok(layout));
                0
            }
            Err(failure) => {
                write_layout_response(ptr, capacity, LayoutResponse::err(failure));
                1
            }
        }
    })
}

fn write_layout_response(ptr: *mut u8, capacity: usize, response: LayoutResponse) {
    if let Ok(json) = serde_json::to_vec(&response) {
        write_frame_at(ptr, capacity, 0, &json);
    }
}

fn handle_init(ptr: *mut u8, capacity: usize) -> jint {
    let request = match read_frame_at(ptr, capacity, 0)
        .and_then(|bytes| serde_json::from_slice::<InitRequest>(&bytes).map_err(|e| Failure::app(e.to_string())))
    {
        Ok(request) => request,
        Err(message) => {
            write_response(ptr, capacity, &BridgeResponse::err(message));
            return 1;
        }
    };

    match init_repo(&request.path, &request.password, &request.options, request.force_init) {
        Ok(()) => {
            write_response(ptr, capacity, &BridgeResponse::ok());
            0
        }
        Err(message) => {
            write_response(ptr, capacity, &BridgeResponse::err(message));
            1
        }
    }
}

/// 建库或打开仓库，**不读写全局 `REPO_CONFIG`，也不碰缓存**（失败无需回滚）。
///
/// 结构判定不在这里：主进程先用 [repo_layout] 查清楚再决定传哪个 `force_init`。
/// 放在这里判的话，「空仓库」「密码错」「缺 keys」会纠缠在同一个 C002 上
/// （本地 backend 列不存在的目录只记日志、返回空列表，open 看不出区别）。
fn init_repo(
    path: &str,
    password: &str,
    options: &BTreeMap<String, String>,
    force_init: bool,
) -> Result<(), Failure> {
    // 本地路径和 remote:/rest:/opendal: 走同一个工厂，不再用文件系统判断仓库是否存在。
    // 建库要写 config / key，远端首次建目录可能慢，按重档给预算。
    let backends = repository_backends(path, &with_budget(options, Budget::Bulk))?;
    let repo_opts = repo_options();
    let credentials = Credentials::password(password);

    if force_init {
        return init_empty_repo(&repo_opts, &backends, &credentials);
    }

    Repository::new(&repo_opts, &backends)
        .map_err(|e| describe_error(&e))?
        .open(&credentials)
        .map(|_| ())
        .map_err(|e| describe_error(&e))
}

/// 空仓库里建库：唯一的成功路径，失败原因原样交出去（不再和 open 的错误拼在一起）。
fn init_empty_repo(
    opts: &RepositoryOptions,
    backends: &RepositoryBackends,
    credentials: &Credentials,
) -> Result<(), Failure> {
    Repository::new(opts, backends)
        .map_err(|e| describe_error(&e))?
        .init(credentials, &KeyOptions::default(), &ConfigOptions::default())
        .map(|_| ())
        .map_err(|e| describe_error(&e))
}

/// 查五类文件在不在（不打开仓库，不需要密码）。
///
/// 本地必须查「目录在不在」而不是「里面有没有东西」：init 会把 data/index/snapshots
/// 建成空目录，用 list 查内容会把刚建好的仓库判成「什么都没有」。
fn repo_layout(path: &str, options: &BTreeMap<String, String>) -> Result<RepoLayout, Failure> {
    // 本地仓库直接看文件系统：backend 的 list 对不存在的目录只记日志、返回空，
    // 区分不了「不存在」和「存在但空」
    if let Some(root) = local_root(path) {
        return Ok(RepoLayout {
            config: root.join("config").is_file(),
            keys: root.join("keys").is_dir(),
            data: root.join("data").is_dir(),
            index: root.join("index").is_dir(),
            snapshots: root.join("snapshots").is_dir(),
        });
    }

    // 远程只有 config 可信：restic 协议（rclone serve 同样）对不存在的目录返回
    // 200 + 空列表，keys/data/index/snapshots 的「列成功 = 存在」全是假阳性，
    // 空目录会被判成缺件。config 在 = 完整仓库，不在 = 空目录（建不建库由主进程定）。
    // 只是读一个 config，算快操作
    let backends = repository_backends(path, &with_budget(options, Budget::Fast))?;
    let repository =
        Repository::new(&repo_options(), &backends).map_err(|e| describe_error(&e))?;

    let complete = repository
        .config_id()
        .map_err(|e| describe_error(&e))?
        .is_some();
    Ok(RepoLayout {
        config: complete,
        keys: complete,
        data: complete,
        index: complete,
        snapshots: complete,
    })
}

/// 本地仓库的根目录；远程（rest: / opendal: / remote: / rclone: / 带 scheme）返回 None。
fn local_root(location: &str) -> Option<PathBuf> {
    if location.contains("://")
        || location.starts_with("rest:")
        || location.starts_with("opendal:")
        || location.starts_with("remote:")
        || location.starts_with("rclone:")
    {
        return None;
    }
    // rustic 支持 local:/path 写法，其余是裸路径
    Some(PathBuf::from(location.strip_prefix("local:").unwrap_or(location)))
}

// ==================== 查询快照 ====================

/// `RusticServer.nativeSetCacheDir(path: String): Int`
///
/// 传空串表示关闭缓存。root 服务启动时调一次。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeSetCacheDir<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
) -> jint {
    let outcome = env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let value: String = env.get_string(&path)?.into();

        if let Ok(mut guard) = CACHE_DIR.lock() {
            *guard = if value.is_empty() {
                None
            } else {
                Some(PathBuf::from(value))
            };
        }

        Ok(0)
    });

    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

/// `RusticServer.nativeListSnapshots(buffer: ByteBuffer): Int`
///
/// 请求：`{"package_name":"包名"}`
/// 响应：`{"ok":true,"snapshots":[{"id","label","tags","time","description"}]}`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeListSnapshots<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    let outcome = env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let ptr = env.get_direct_buffer_address(&buffer)?;
        let capacity = env.get_direct_buffer_capacity(&buffer)?;

        if ptr.is_null() || capacity <= 4 {
            return Ok(2);
        }

        Ok(handle_list(ptr, capacity))
    });

    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

fn handle_list(ptr: *mut u8, capacity: usize) -> jint {
    let request = match read_frame_at(ptr, capacity, 0)
        .and_then(|bytes| serde_json::from_slice::<ListRequest>(&bytes).map_err(|e| Failure::app(e.to_string())))
    {
        Ok(request) => request,
        Err(message) => {
            write_list_response(ptr, capacity, ListResponse::err(message));
            return 1;
        }
    };

    match list_snapshots(&request) {
        Ok(mut snapshots) => {
            // 按实际容量裁剪：描述可能很长，500 条不一定塞得下
            loop {
                let response = ListResponse::ok(snapshots.clone());

                match serde_json::to_vec(&response) {
                    Ok(json) if json.len() + 4 <= capacity => {
                        write_frame_at(ptr, capacity, 0, &json);
                        break;
                    }
                    Ok(_) if snapshots.is_empty() => {
                        write_list_response(ptr, capacity, ListResponse::ok(Vec::new()));
                        break;
                    }
                    Ok(_) => {
                        snapshots.pop();
                    }
                    Err(_) => {
                        write_list_response(
                            ptr,
                            capacity,
                            ListResponse::err("序列化快照失败"),
                        );
                        break;
                    }
                }
            }
            0
        }
        Err(failure) => {
            write_list_response(ptr, capacity, ListResponse::err(failure));
            1
        }
    }
}

fn list_snapshots(request: &ListRequest) -> Result<Vec<SnapshotInfo>, Failure> {
    let repository = open_repo_cached()?;

    // 应用：label 形如 `<包名>:<类型>`，按前缀过滤（纯查找，不做语义处理）
    // 目录：label 形如 `<绝对路径>:dir`，整条精确匹配（避免 /a/DCIM 命中 /a/DCIM2）
    let prefix = format!("{}:", request.package_name);
    let dir_label = if request.package_name.starts_with('/') {
        Some(format!("{}:dir", request.package_name))
    } else {
        None
    };
    let filter_by_package = !request.package_name.is_empty();

    // 带条件的查询：rustic 会在读取时就用 predicate 过滤，不用先全量拿到内存里
    let mut snapshots: Vec<SnapshotInfo> = repository
        .get_matching_snapshots(|snap| match &dir_label {
            Some(label) => snap.label == *label,
            None => !filter_by_package || snap.label.starts_with(&prefix),
        })
        .map_err(|e| describe_error(&e))?
        .into_iter()
        .map(|snap| SnapshotInfo {
            id: snap.id.to_string(),
            label: snap.label.clone(),
            tags: snap.tags.to_string(),
            time: snap.time.timestamp().as_millisecond(),
            size: snap
                .summary
                .as_ref()
                .map(|summary| summary.total_bytes_processed)
                .unwrap_or(0),
            description: snap.description.clone(),
        })
        .collect();

    // 新的在前。全量列表要够长，否则备份次数多了会把老快照截掉，
    // 备份数统计和「删除全部」都会不准；按包名查只需要很少几条。
    // 512KB 响应区约能放 2600 条（一条约 200 字节）。
    let limit = if filter_by_package { 500 } else { 2000 };
    snapshots.sort_by(|a, b| b.time.cmp(&a.time));
    snapshots.truncate(limit);

    Ok(snapshots)
}

/// 本地缓存目录，由 Kotlin 侧在服务启动时传入（root 进程的 cacheDir/rustic）。
///
/// 有了它 rustic 会把索引、tree 之类的数据缓存到本地，第二次查询不用重新读。
static CACHE_DIR: Mutex<Option<PathBuf>> = Mutex::new(None);

/// 统一的仓库选项：有缓存目录就用缓存，没有就退回不缓存。
fn repo_options() -> RepositoryOptions {
    let mut options = RepositoryOptions::default();

    let dir = CACHE_DIR.lock().ok().and_then(|guard| (*guard).clone());
    match dir {
        Some(dir) => {
            if std::fs::create_dir_all(&dir).is_ok() {
                options.no_cache = false;
                options.cache_dir = Some(dir);
            } else {
                options.no_cache = true;
            }
        }
        None => options.no_cache = true,
    }

    options
}

/// cancel 之后的短路错误：文本带 rest 请求特征，`classify_error` 会归成 Unreachable，
/// 所以即使中断帧没赶上、由 JNI 的响应路径先返回，主进程仍按断连处理。
fn cancelled_error() -> Box<rustic_core::RusticError> {
    rustic_core::RusticError::new(
        rustic_core::ErrorKind::Other,
        "error sending request for url (cancelled)",
    )
}

/// 远程仓库后端的包装层：传输层错误一出现就写 `interrupted` 帧并置 `cancel`，
/// 让后续读写立刻失败、archiver 快速收尾，不必等源字节读满。
/// 只提供机制，不做业务判定 —— 什么算断连由 `classify_error` 决定。
struct ErrorReportingBackend {
    inner: Arc<dyn WriteBackend>,
    /// 已经报过断连。并发的在途请求可能同时失败，只有第一个写帧。
    reported: AtomicBool,
    /// 报过之后所有读写直接失败，让 archiver 快速收尾。
    cancel: AtomicBool,
}

impl ErrorReportingBackend {
    fn new(inner: Arc<dyn WriteBackend>) -> Self {
        Self {
            inner,
            reported: AtomicBool::new(false),
            cancel: AtomicBool::new(false),
        }
    }

    fn is_cancelled(&self) -> bool {
        self.cancel.load(Ordering::SeqCst)
    }

    /// 认不出来的错误（密码错 / 数据损坏 / 磁盘满）不上报、不取消：它们是正常失败。
    fn report(&self, error: &rustic_core::RusticError) {
        let reason = match classify_error(error) {
            Some(reason) if reason.is_transport() => reason,
            _ => return,
        };
        // 抢报告权：并发的在途请求会同时失败，只有第一个写帧
        if self.reported.swap(true, Ordering::SeqCst) {
            return;
        }
        // 先落帧再置 cancel：置位后后续调用会被短路，帧必须已经写进缓冲区。
        // ACTIVE_PROGRESS 为空说明这次不是备份 / 恢复（列目录、改备注等），静默跳过。
        if let Ok(guard) = ACTIVE_PROGRESS.lock() {
            if let Some(ctx) = guard.as_ref() {
                ctx.emit_interrupted(&current_stage(), reason.wire());
            }
        }
        self.cancel.store(true, Ordering::SeqCst);
    }

    fn observe<T>(&self, result: rustic_core::RusticResult<T>) -> rustic_core::RusticResult<T> {
        if let Err(error) = &result {
            self.report(error);
        }
        result
    }
}

impl ReadBackend for ErrorReportingBackend {
    fn location(&self) -> String {
        self.inner.location()
    }

    fn list_with_size(
        &self,
        tpe: FileType,
    ) -> rustic_core::RusticResult<Vec<(Id, u32)>> {
        if self.is_cancelled() {
            return Err(cancelled_error());
        }
        self.observe(self.inner.list_with_size(tpe))
    }

    fn read_full(
        &self,
        tpe: FileType,
        id: &Id,
    ) -> rustic_core::RusticResult<Bytes> {
        if self.is_cancelled() {
            return Err(cancelled_error());
        }
        self.observe(self.inner.read_full(tpe, id))
    }

    fn read_partial(
        &self,
        tpe: FileType,
        id: &Id,
        cacheable: bool,
        offset: u32,
        length: u32,
    ) -> rustic_core::RusticResult<Bytes> {
        if self.is_cancelled() {
            return Err(cancelled_error());
        }
        self.observe(self.inner.read_partial(tpe, id, cacheable, offset, length))
    }

    fn warmup_path(&self, tpe: FileType, id: &Id) -> String {
        self.inner.warmup_path(tpe, id)
    }

    fn list(&self, tpe: FileType) -> rustic_core::RusticResult<Vec<Id>> {
        if self.is_cancelled() {
            return Err(cancelled_error());
        }
        self.observe(self.inner.list(tpe))
    }

    fn needs_warm_up(&self) -> bool {
        self.inner.needs_warm_up()
    }

    fn warm_up(
        &self,
        tpe: FileType,
        id: &Id,
    ) -> rustic_core::RusticResult<()> {
        if self.is_cancelled() {
            return Err(cancelled_error());
        }
        self.observe(self.inner.warm_up(tpe, id))
    }
}

impl WriteBackend for ErrorReportingBackend {
    fn create(&self) -> rustic_core::RusticResult<()> {
        if self.is_cancelled() {
            return Err(cancelled_error());
        }
        self.observe(self.inner.create())
    }

    fn write_bytes(
        &self,
        tpe: FileType,
        id: &Id,
        cacheable: bool,
        content: BytesList,
    ) -> rustic_core::RusticResult<()> {
        if self.is_cancelled() {
            return Err(cancelled_error());
        }
        self.observe(self.inner.write_bytes(tpe, id, cacheable, content))
    }

    fn remove(
        &self,
        tpe: FileType,
        id: &Id,
        cacheable: bool,
    ) -> rustic_core::RusticResult<()> {
        if self.is_cancelled() {
            return Err(cancelled_error());
        }
        self.observe(self.inner.remove(tpe, id, cacheable))
    }
}

/// 交给 rustic_backend 现成的 RestBackend。自定义 option 不会透传给后端。
/// 其余前缀（本地路径 / rest: / opendal:）照旧由 rustic_backend 分派。
///
/// `remote:` 是本项目的逻辑前缀（不含端口和凭据）：先让 librclone 起一个只监听
/// 127.0.0.1 的 restic 服务，再换成 `rest:<url>`。
fn repository_backends(
    location: &str,
    options: &BTreeMap<String, String>,
) -> Result<RepositoryBackends, Failure> {
    let mut effective = location.to_string();
    let mut backend_options = options.clone();

    if location.starts_with("remote:") {
        // rest_url 由主进程向 RcloneService.ensureServe 临时取回，含本次会话的
        // 随机 token 与端口；这里只做字符串拼接，不再加载或调用 librclone。
        let url = backend_options
            .remove("rest_url")
            .ok_or_else(|| Failure::app("缺少远程服务地址"))?;
        effective = format!("rest:{url}");
    }

    let backends = BackendOptions::default()
        .repository(effective.as_str())
        .options(backend_options)
        .to_backends()
        .map_err(|e| describe_error(&e))?;

    // 只有远程仓库需要「错误一产生就送达」：本地仓库没有连接可断，
    // 包一层只是多一次间接调用。repo_hot 原样透传，不改写冷热分层语义。
    if location.starts_with("remote:") {
        let wrapped = Arc::new(ErrorReportingBackend::new(backends.repository()));
        Ok(RepositoryBackends::new(wrapped, backends.repo_hot()))
    } else {
        Ok(backends)
    }
}

/// 远程操作的超时档位。每一档只有一个判定者：快档由 rustic 的
/// `http-read-timeout` 判；重档由 rclone 的 `Timeout` 判，这里只是比它长的保险丝。
/// 重档还会整包重试 2 次，让短抖动有机会接上。
#[derive(Clone, Copy)]
enum Budget {
    /// 列快照 / 改备注 / 删快照。
    Fast,
    /// 备份 / 恢复 / prune / 建库（传 32MB 的 pack）。
    Bulk,
}

impl Budget {
    /// 两次读之间的空闲上限（不是总时长）：慢但持续的传输不会被打断，卡住才断。
    /// 上传也一样 —— 发完 body 要等响应，远端断了就卡在这一步。
    fn read_timeout(self) -> &'static str {
        match self {
            Budget::Fast => "10s",
            Budget::Bulk => "40s",
        }
    }

    /// 整包重试次数。只有重档给 —— 每次重试都要重传整个 32MB 的 pack；
    /// 快档给了会把「10s 出结论」拖成 30s。
    fn retry(self) -> &'static str {
        match self {
            Budget::Fast => "0",
            Budget::Bulk => "2",
        }
    }
}

/// 按档位补齐远程超时参数；其余选项原样保留。
fn with_budget(
    options: &BTreeMap<String, String>,
    budget: Budget,
) -> BTreeMap<String, String> {
    let mut options = options.clone();
    options.insert(
        "http-read-timeout".to_string(),
        budget.read_timeout().to_string(),
    );
    options.insert("retry".to_string(), budget.retry().to_string());
    // 打的是 127.0.0.1，正常秒连，这个值只是兜底
    options.insert("http-connect-timeout".to_string(), "5s".to_string());
    options
}

/// 打开仓库（不需要索引，列表/改备注都够用）。
///
/// 走 `new_with_progress`：备份时 rustic 内部的字节进度会经 [BridgeProgressBars]
/// 写进共享内存。列表 / prune / check 这些操作没有挂进度端，emit 会静默跳过。
fn open_repo(
    config: &RepoConfig,
    budget: Budget,
) -> Result<rustic_core::Repository<rustic_core::OpenStatus>, Failure> {
    let backends = repository_backends(&config.path, &with_budget(&config.options, budget))?;

    Repository::new_with_progress(&repo_options(), &backends, BridgeProgressBars)
        .map_err(|e| describe_error(&e))?
        .open(&Credentials::password(&config.password))
        .map_err(|e| describe_error(&e))
}

/// 缓存的仓库实例：同一时间只保留一个（应用只会打开一个仓库）。
///
/// `Repository<OpenStatus>` 里只有 config、派生好的密钥和 backend，
/// **不含快照列表和索引**，所以仓库内容变了也不会读到旧数据，
/// 每次查询照旧重新列 `snapshots/` 目录。
struct CachedRepo {
    /// 仓库标识：路径 + 凭据代数（见 [repo_key]）。
    key: String,
    repo: Arc<Repository<rustic_core::OpenStatus>>,
    last_used: Instant,
}

/// 空闲超过这个时间就丢掉缓存，避免长期占着远程连接（本地仓库只是省一次 scrypt）。
const REPO_CACHE_IDLE: Duration = Duration::from_secs(600);

static OPEN_REPO: Mutex<Option<CachedRepo>> = Mutex::new(None);

fn clear_repo_cache() {
    if let Ok(mut guard) = OPEN_REPO.lock() {
        *guard = None;
    }
}

/// 取仓库：命中缓存就直接复用（省掉 scrypt 派生），否则打开并缓存。
fn open_repo_cached() -> Result<Arc<Repository<rustic_core::OpenStatus>>, Failure> {
    let config = repo_config()?;
    let key = repo_key(&config);

    if let Ok(mut guard) = OPEN_REPO.lock() {
        let reusable = guard
            .as_ref()
            .is_some_and(|cached| cached.key == key && cached.last_used.elapsed() < REPO_CACHE_IDLE);

        if reusable {
            let cached = guard.as_mut().expect("checked above");
            cached.last_used = Instant::now();
            return Ok(cached.repo.clone());
        }

        // 换了仓库 / 密码，或者空闲太久
        *guard = None;
    }

    let repo = Arc::new(open_repo(&config, Budget::Fast)?);
    if let Ok(mut guard) = OPEN_REPO.lock() {
        *guard = Some(CachedRepo {
            key,
            repo: repo.clone(),
            last_used: Instant::now(),
        });
    }
    Ok(repo)
}

/// 缓存的「已建索引」仓库。
///
/// `to_indexed()` 要把整个仓库的索引读进来，再加上 `open_repo` 的 scrypt 密钥派生，
/// 一次要几百毫秒。恢复一个应用会按 apk / user / user_de / data / permissions
/// 分 5 次调用，每次都重来一遍 —— 光这块固定开销就要 3~4 秒（实测 1.4GB 的
/// Termux 里 user_de 只有几百个文件却花了 845ms，就是它的锅）。
///
/// 索引只在写操作（备份 / 删除快照 / 整理仓库）之后才会变，所以那几处会主动失效。
/// 快照列表本身不缓存，每次照旧从 `snapshots/` 目录重新读，不会读到旧数据。
struct CachedIndexedRepo {
    key: String,
    repo: Arc<Repository<rustic_core::IndexedFullStatus>>,
    last_used: Instant,
}

static OPEN_INDEXED: Mutex<Option<CachedIndexedRepo>> = Mutex::new(None);

/// 丢掉索引缓存：仓库内容变了（备份 / 删除 / 整理）之后必须调。
fn invalidate_indexed_repo() {
    if let Ok(mut guard) = OPEN_INDEXED.lock() {
        *guard = None;
    }
}

/// 取「已建索引」的仓库：命中缓存直接复用，省掉 scrypt 派生和读索引。
fn open_indexed_repo_cached() -> Result<Arc<Repository<rustic_core::IndexedFullStatus>>, Failure> {
    let config = repo_config()?;
    let key = repo_key(&config);

    if let Ok(mut guard) = OPEN_INDEXED.lock() {
        let reusable = guard
            .as_ref()
            .is_some_and(|cached| cached.key == key && cached.last_used.elapsed() < REPO_CACHE_IDLE);

        if reusable {
            let cached = guard.as_mut().expect("checked above");
            cached.last_used = Instant::now();
            return Ok(cached.repo.clone());
        }

        *guard = None;
    }

    let repo = Arc::new(
        open_repo(&config, Budget::Bulk)?
            .to_indexed()
            .map_err(|e| describe_error(&e))?,
    );

    if let Ok(mut guard) = OPEN_INDEXED.lock() {
        *guard = Some(CachedIndexedRepo {
            key,
            repo: repo.clone(),
            last_used: Instant::now(),
        });
    }
    Ok(repo)
}

/// 备份用的「已建索引」仓库缓存，只在同一个批次内有效。
///
/// 打开仓库（scrypt 密钥派生）+ 读一遍索引实测 563ms，批量备份 96 个应用
/// 就是 54 秒的纯开销。同批次内复用同一个 `Repository` 就能省掉它。
///
/// 为什么按批次隔离：仓库索引在备份过程中会变（新写入的 pack 会落成新的索引文件），
/// 缓存的读索引看不到这些新 blob，跨批次复用会让「重复备份同一个应用」失去去重、
/// 把数据再存一遍。所以只允许同批次内复用，批次一换立刻重开。
struct CachedBackupRepo {
    key: String,
    repo: Arc<Repository<rustic_core::IndexedIdsStatus>>,
    last_used: Instant,
}

static OPEN_BACKUP: Mutex<Option<CachedBackupRepo>> = Mutex::new(None);

/// 丢掉备份用的仓库缓存：备份失败、整理仓库、换仓库时都要调。
fn invalidate_backup_repo() {
    if let Ok(mut guard) = OPEN_BACKUP.lock() {
        *guard = None;
    }
}

fn open_backup_repo_cached(
    batch: Option<&str>,
) -> Result<Arc<Repository<rustic_core::IndexedIdsStatus>>, Failure> {
    let config = repo_config()?;
    // 没有批次标识就不缓存：单次备份本来也只会用一次
    let key = match batch {
        Some(batch) if !batch.is_empty() => {
            format!("{}\0batch\0{batch}", repo_key(&config))
        }
        _ => {
            let backends =
                repository_backends(&config.path, &with_budget(&config.options, Budget::Bulk))?;
            let repository =
                Repository::new_with_progress(&repo_options(), &backends, BridgeProgressBars)
                    .map_err(|e| describe_error(&e))?;
            return Ok(Arc::new(
                repository
                    .open(&Credentials::password(&config.password))
                    .map_err(|e| describe_error(&e))?
                    .to_indexed_ids()
                    .map_err(|e| describe_error(&e))?,
            ));
        }
    };

    if let Ok(mut guard) = OPEN_BACKUP.lock() {
        let reusable = guard
            .as_ref()
            .is_some_and(|cached| cached.key == key && cached.last_used.elapsed() < REPO_CACHE_IDLE);

        if reusable {
            let cached = guard.as_mut().expect("checked above");
            cached.last_used = Instant::now();
            return Ok(cached.repo.clone());
        }

        *guard = None;
    }

    let backends = repository_backends(&config.path, &with_budget(&config.options, Budget::Bulk))?;
    let repository = Repository::new_with_progress(&repo_options(), &backends, BridgeProgressBars)
        .map_err(|e| describe_error(&e))?;

    let repo = Arc::new(
        repository
            .open(&Credentials::password(&config.password))
            .map_err(|e| describe_error(&e))?
            .to_indexed_ids()
            .map_err(|e| describe_error(&e))?,
    );

    if let Ok(mut guard) = OPEN_BACKUP.lock() {
        *guard = Some(CachedBackupRepo {
            key,
            repo: repo.clone(),
            last_used: Instant::now(),
        });
    }
    Ok(repo)
}

fn write_list_response(ptr: *mut u8, capacity: usize, response: ListResponse) {
    if let Ok(json) = serde_json::to_vec(&response) {
        write_frame_at(ptr, capacity, 0, &json);
    }
}

// ==================== 仓库写访问闸门 ====================

/// 仓库内容的**写**访问闸门。
///
/// 这是 DAO 对自己持有的资源（仓库句柄、索引 / 仓库实例这几个 static 缓存）做的
/// 并发保护，**不是业务策略**：谁该写、写什么、要不要拦下并提示用户，仍然由
/// service 层决定。放在这里的原因是这些资源只有这里持有，任何调用方都绕不过去。
///
/// 为什么必须有：`:root` 里的每个 JNI 入口都跑在自己的 binder 线程上，两笔写调用
/// 会同时进 rustic。整理仓库用的是 `instant_delete`，它会把「还没进索引的新包」
/// 当成无引用数据直接删掉——此时若有一笔备份刚写完包还没写索引，那个包就没了，
/// 仓库直接损坏。上游 0.14.0 修的是「并发 prune 之间」的问题，它没法替调用方
/// 保证「同一时刻只有一笔写」。
///
/// 只挡写：列表 / 概览 / 估算这类只读调用不进闸门，备份跑几分钟也不会把界面卡住。
static REPO_WRITE_GATE: Mutex<()> = Mutex::new(());

/// 进入写闸门，离开作用域时（guard drop）自动放行。
///
/// 中毒（持锁线程 panic）也照样放行：release 是 `panic = "abort"`，进程会直接
/// 退出，不可能留下一个中毒的锁；真出现了也只该继续用，不该让后续写操作永久失败。
fn enter_write_gate() -> std::sync::MutexGuard<'static, ()> {
    REPO_WRITE_GATE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

// ==================== 备份概况 ====================

/// 概况里的一条快照：只带 id / label / 大小。
///
/// 同一次备份里所有快照的 tag、时间、备注都一样，所以这些提到 [PackageOverview] 上，
/// 单条只留真正随快照变化的部分，省响应体积。
#[derive(Serialize)]
struct OverviewSnapshot {
    id: String,
    label: String,
    size: u64,
}

/// 一个包的备份概况：备份次数 + 最近一次备份 + 最新 apk 快照的信息。
#[derive(Serialize)]
struct PackageOverview {
    package: String,
    /// 备份次数（按 tag 里的时间戳分组）
    count: u32,
    /// 最近一次备份的时间戳（毫秒）
    time: i64,
    /// 最近一次备份的 tag（`应用名|版本|用户名|时间戳`），组内所有快照共用
    tags: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    description: Option<String>,
    /// 最近一次备份包含的快照（删除 / 覆盖 / 恢复要用它的 id）
    snapshots: Vec<OverviewSnapshot>,
    /// 有没有 apk 备份：没有 apk 的包不会出现在「只在备份里」的应用列表
    has_apk: bool,
    /// 最新一条 apk 快照的 tag。应用名 / 版本从这里取，
    /// 可能比最近一次备份更早（那次没勾 apk），所以单独带一份。
    #[serde(skip_serializing_if = "Option::is_none")]
    apk_tags: Option<String>,
    apk_time: i64,
    /// 仓库里已经有 `包名:icon` 快照：备份时不用再存一次图标
    has_icon: bool,
}

#[derive(Serialize)]
struct OverviewResponse {
    ok: bool,
    packages: Vec<PackageOverview>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
}

/// 快照属于哪一次备份：优先用 tag 里的时间戳，解析不出来退回快照时间。
///
/// 这里只为了分组取一个稳定的 key，完整的 tag 语义解析仍然在 Kotlin 的 SnapshotMeta。
fn backup_stamp(snapshot: &SnapshotFile) -> i64 {
    snapshot
        .tags
        .to_string()
        .split(',')
        .find(|tag| tag.contains('|'))
        .and_then(|tag| tag.rsplit('|').next())
        .and_then(|stamp| stamp.trim().parse::<i64>().ok())
        .unwrap_or_else(|| snapshot.time.timestamp().as_millisecond())
}

fn build_overview() -> Result<Vec<PackageOverview>, Failure> {
    let repository = open_repo_cached()?;

    let snapshots = repository
        .get_matching_snapshots(|_| true)
        .map_err(|e| describe_error(&e))?;

    // 包名 -> 该包的所有快照（图标快照不算一次备份）
    let mut by_package: std::collections::BTreeMap<String, Vec<&SnapshotFile>> =
        std::collections::BTreeMap::new();

    // 已经有图标快照的包：顺带统计出来，省得备份时每个应用再单独查一次
    let mut icon_packages: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();

    for snapshot in &snapshots {
        // label 一律是 `<键>:<类型>`：应用是 `<包名>:<类型>`，目录是 `<绝对路径>:dir`
        let Some((package, kind)) = snapshot.label.rsplit_once(':') else {
            continue;
        };
        if package.is_empty() {
            continue;
        }
        let package = package.to_string();
        if kind == "icon" {
            icon_packages.insert(package.clone());
            continue;
        }
        by_package.entry(package).or_default().push(snapshot);
    }

    let mut result = Vec::with_capacity(by_package.len());

    for (package, group) in by_package {
        // 同一次备份产生的 apk / user / user_de / data 属于同一组
        let mut by_stamp: std::collections::BTreeMap<i64, Vec<&SnapshotFile>> =
            std::collections::BTreeMap::new();
        for snapshot in group.iter().copied() {
            by_stamp
                .entry(backup_stamp(snapshot))
                .or_default()
                .push(snapshot);
        }

        let count = by_stamp.len() as u32;

        let Some((&time, latest)) = by_stamp.iter().next_back() else {
            continue;
        };

        let tags = latest
            .first()
            .map(|snapshot| snapshot.tags.to_string())
            .unwrap_or_default();

        let description = latest
            .iter()
            .find_map(|snapshot| snapshot.description.clone())
            .filter(|text| !text.trim().is_empty());

        let latest_snapshots = latest
            .iter()
            .map(|snapshot| OverviewSnapshot {
                id: snapshot.id.to_string(),
                label: snapshot.label.clone(),
                size: snapshot
                    .summary
                    .as_ref()
                    .map(|summary| summary.total_bytes_processed)
                    .unwrap_or(0),
            })
            .collect::<Vec<_>>();

        // 最新一条 apk 快照：应用名 / 版本以它为准
        let apk = group
            .iter()
            .copied()
            .filter(|snapshot| snapshot.label.ends_with(":apk"))
            .map(|snapshot| (backup_stamp(snapshot), snapshot))
            .max_by_key(|(stamp, _)| *stamp)
            .map(|(_, snapshot)| snapshot);

        let has_icon = icon_packages.contains(&package);

        result.push(PackageOverview {
            package,
            count,
            time,
            tags,
            description,
            snapshots: latest_snapshots,
            has_apk: apk.is_some(),
            apk_tags: apk.map(|snapshot| snapshot.tags.to_string()),
            apk_time: apk.map(backup_stamp).unwrap_or(0),
            has_icon,
        });
    }

    // 最近备份的在前
    result.sort_by(|a, b| b.time.cmp(&a.time));
    Ok(result)
}

fn write_overview_response(ptr: *mut u8, capacity: usize, response: OverviewResponse) {
    if let Ok(json) = serde_json::to_vec(&response) {
        write_frame_at(ptr, capacity, 0, &json);
    }
}

/// `RusticServer.nativeOverview(buffer: ByteBuffer): Int`
///
/// 一次拿到所有包的备份概况：每个包一条，只带最近一次备份的快照。
/// 相比「拉全量快照列表」，响应大小只跟应用数量有关，跟备份次数无关。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeOverview<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        match build_overview() {
            Ok(packages) => {
                write_overview_response(
                    ptr,
                    capacity,
                    OverviewResponse {
                        ok: true,
                        packages,
                        error: None,
                        reason: None,
                    },
                );
                0
            }
            Err(failure) => {
                write_overview_response(
                    ptr,
                    capacity,
                    OverviewResponse {
                        ok: false,
                        packages: Vec::new(),
                        reason: failure.reason_wire(),
                        error: Some(failure.message),
                    },
                );
                1
            }
        }
    })
}

/// `RusticServer.nativeUpdateDescription(buffer: ByteBuffer): Int`
///
/// 请求：`{"ids":["..."],"description":"..."|null}`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeUpdateDescription<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    let outcome = env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let ptr = env.get_direct_buffer_address(&buffer)?;
        let capacity = env.get_direct_buffer_capacity(&buffer)?;

        if ptr.is_null() || capacity <= 4 {
            return Ok(2);
        }

        Ok(handle_update_description(ptr, capacity))
    });

    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

fn handle_update_description(ptr: *mut u8, capacity: usize) -> jint {
    let request = match read_frame_at(ptr, capacity, 0).and_then(|bytes| {
        serde_json::from_slice::<UpdateDescriptionRequest>(&bytes).map_err(|e| Failure::app(e.to_string()))
    }) {
        Ok(request) => request,
        Err(message) => {
            write_response(ptr, capacity, &BridgeResponse::err(message));
            return 1;
        }
    };

    match update_descriptions(&request) {
        Ok(()) => {
            write_response(ptr, capacity, &BridgeResponse::ok());
            0
        }
        Err(message) => {
            write_response(ptr, capacity, &BridgeResponse::err(message));
            1
        }
    }
}

fn update_descriptions(request: &UpdateDescriptionRequest) -> Result<(), Failure> {
    let _gate = enter_write_gate();

    if request.ids.is_empty() {
        return Ok(());
    }

    let repository = open_repo_cached()?;

    let mut snapshots = repository
        .get_snapshots(&request.ids)
        .map_err(|e| describe_error(&e))?;

    if snapshots.is_empty() {
        return Err(Failure::app("snapshot not found"));
    }

    // 快照 id 是内容哈希，改完 description 会变成新 id，所以先记住旧 id 再删
    let old_ids = snapshots.iter().map(|snap| snap.id).collect::<Vec<_>>();

    let description = request
        .description
        .clone()
        .filter(|text| !text.trim().is_empty());

    for snapshot in &mut snapshots {
        snapshot.description.clone_from(&description);
    }

    repository
        .save_snapshots(snapshots)
        .map_err(|e| describe_error(&e))?;

    let result = repository
        .delete_snapshots(&old_ids)
        .map_err(|e| describe_error(&e));

    // 备注改了就是新快照 id，缓存里还是旧的，必须清掉
    invalidate_indexed_repo();
    result
}

/// 简单的 JNI 包装：取缓冲区地址和容量，交给具体实现。
fn run_simple<'local>(
    mut env: EnvUnowned<'local>,
    buffer: JByteBuffer<'local>,
    handler: impl Fn(*mut u8, usize) -> jint,
) -> jint {
    let outcome = env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let ptr = env.get_direct_buffer_address(&buffer)?;
        let capacity = env.get_direct_buffer_capacity(&buffer)?;

        if ptr.is_null() || capacity <= 4 {
            return Ok(2);
        }

        Ok(handler(ptr, capacity))
    });

    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

/// `RusticServer.nativeDeleteSnapshots(buffer: ByteBuffer): Int`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeDeleteSnapshots<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        let request = match read_frame_at(ptr, capacity, 0).and_then(|bytes| {
            serde_json::from_slice::<DeleteRequest>(&bytes).map_err(|e| Failure::app(e.to_string()))
        }) {
            Ok(request) => request,
            Err(message) => {
                write_response(ptr, capacity, &BridgeResponse::err(message));
                return 1;
            }
        };

        match delete_snapshots(&request) {
            Ok(()) => {
                write_response(ptr, capacity, &BridgeResponse::ok());
                0
            }
            Err(message) => {
                write_response(ptr, capacity, &BridgeResponse::err(message));
                1
            }
        }
    })
}

fn delete_snapshots(request: &DeleteRequest) -> Result<(), Failure> {
    let _gate = enter_write_gate();

    if request.ids.is_empty() {
        return Ok(());
    }

    // 快照删掉后索引里的引用也变了，缓存必须作废
    invalidate_indexed_repo();

    let repository = open_repo_cached()?;

    let ids = repository
        .get_snapshots(&request.ids)
        .map_err(|e| describe_error(&e))?
        .into_iter()
        .map(|snap| snap.id)
        .collect::<Vec<_>>();

    let result = repository
        .delete_snapshots(&ids)
        .map_err(|e| describe_error(&e));

    // 结束时再失效一次：删除期间如果有 listSnapshots 把「删之前的索引」缓存进来，
    // 之后 10 分钟内都会看到已经删掉的快照
    invalidate_indexed_repo();
    result
}

// ==================== 清理孤儿图标 ====================

#[derive(Serialize)]
struct CleanupResponse {
    ok: bool,
    removed: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
}

/// 删掉「备份已经清空、但 `包名:icon` 还留着」的图标快照。
///
/// 图标快照不属于任何一次备份（它没有时间戳分组），所以按备份删的时候碰不到它；
/// 应用的所有备份都删光之后，这个图标就变成了仓库里的垃圾，需要单独回收。
fn clean_orphan_icons() -> Result<u32, Failure> {
    let _gate = enter_write_gate();

    // 和 prune 是一条链（用户看到的是「整理仓库」一件事），按重档给预算。
    // 不走缓存：只在 prune 前跑一次，多花的一次 scrypt 可以忽略。
    let config = repo_config()?;
    let repository = open_repo(&config, Budget::Bulk)?;

    let snapshots = repository
        .get_matching_snapshots(|_| true)
        .map_err(|e| describe_error(&e))?;

    // 包名 -> 是否还有真实备份（非 icon）
    let mut has_backup: std::collections::BTreeMap<&str, bool> =
        std::collections::BTreeMap::new();
    // 包名 -> 图标快照
    let mut icons: std::collections::BTreeMap<&str, Vec<&SnapshotFile>> =
        std::collections::BTreeMap::new();

    for snapshot in &snapshots {
        let Some((package, kind)) = snapshot.label.rsplit_once(':') else {
            continue;
        };
        if package.is_empty() {
            continue;
        }

        if kind == "icon" {
            icons.entry(package).or_default().push(snapshot);
        } else {
            has_backup.insert(package, true);
        }
    }

    let orphan_ids = icons
        .into_iter()
        .filter(|(package, _)| !has_backup.get(package).copied().unwrap_or(false))
        .flat_map(|(_, group)| group.into_iter().map(|snapshot| snapshot.id))
        .collect::<Vec<_>>();

    if orphan_ids.is_empty() {
        return Ok(0);
    }

    let removed = orphan_ids.len() as u32;
    repository
        .delete_snapshots(&orphan_ids)
        .map_err(|e| describe_error(&e))?;

    invalidate_indexed_repo();
    Ok(removed)
}

fn write_cleanup_response(ptr: *mut u8, capacity: usize, response: CleanupResponse) {
    if let Ok(json) = serde_json::to_vec(&response) {
        write_frame_at(ptr, capacity, 0, &json);
    }
}

/// `RusticServer.nativeCleanOrphanIcons(buffer: ByteBuffer): Int`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeCleanOrphanIcons<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        match clean_orphan_icons() {
            Ok(removed) => {
                write_cleanup_response(
                    ptr,
                    capacity,
                    CleanupResponse {
                        ok: true,
                        removed,
                        error: None,
                        reason: None,
                    },
                );
                0
            }
            Err(failure) => {
                write_cleanup_response(
                    ptr,
                    capacity,
                    CleanupResponse {
                        ok: false,
                        removed: 0,
                        reason: failure.reason_wire(),
                        error: Some(failure.message),
                    },
                );
                1
            }
        }
    })
}

/// `RusticServer.nativePrune(buffer: ByteBuffer): Int`
///
/// 删除快照后回收空间：rustic 删快照只是删引用，数据要 prune 才真正释放。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativePrune<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        match prune_repo() {
            Ok(()) => {
                write_response(ptr, capacity, &BridgeResponse::ok());
                0
            }
            Err(message) => {
                write_response(ptr, capacity, &BridgeResponse::err(message));
                1
            }
        }
    })
}

/// 整理仓库的固定参数。
///
/// 只有「整理仓库」一个入口，统一做彻底清理：所有半空的包都重新打包，
/// 直接拷贝 blob（不重新加密压缩），比默认 repack 快很多。
fn prune_options() -> PruneOptions {
    let mut options = PruneOptions::default();
    // 立刻回收：不标记等待，也不保留 23 小时。
    options.instant_delete = true;
    options.keep_marked = jiff::Span::new();
    options.fast_repack = true;
    options.max_repack = LimitOption::Unlimited;
    options.max_unused = LimitOption::Percentage(0);
    options
}

fn prune_repo() -> Result<(), Failure> {
    let _gate = enter_write_gate();

    // 整理会重写 pack 和索引，缓存必须作废
    invalidate_indexed_repo();
    invalidate_backup_repo();

    let repository = open_indexed_repo_cached()?;

    let options = prune_options();

    let plan = repository
        .prune_plan(&options)
        .map_err(|e| describe_error(&e))?;

    let result = repository
        .prune(&options, plan)
        .map_err(|e| describe_error(&e));

    // 整理重写了 pack 和索引，收尾再失效一次
    invalidate_indexed_repo();
    invalidate_backup_repo();
    result
}

/// 只算不删：估算这次整理能回收多少字节。
///
/// 算法：整包删除的字节 + 重打包时被丢弃的未使用字节 + 无引用的包文件。
fn prune_estimate() -> Result<u64, Failure> {
    // 只算不删，可以放心复用缓存（估算和真正整理之间通常只隔几秒）
    let repository = open_indexed_repo_cached()?;

    let options = prune_options();
    let plan = repository
        .prune_plan(&options)
        .map_err(|e| describe_error(&e))?;

    let stats = &plan.stats;
    let blobs = stats.size_sum();

    Ok(stats.size_to_delete.remove
        + blobs.remove
        + blobs.repackrm
        + stats.size_unref)
}

#[derive(Serialize)]
struct EstimateResponse {
    ok: bool,
    bytes: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
}

fn write_estimate(ptr: *mut u8, capacity: usize, result: Result<u64, Failure>) {
    let response = match result {
        Ok(bytes) => EstimateResponse {
            ok: true,
            bytes,
            error: None,
            reason: None,
        },
        Err(failure) => EstimateResponse {
            ok: false,
            bytes: 0,
            reason: failure.reason_wire(),
            error: Some(failure.message),
        },
    };

    if let Ok(bytes) = serde_json::to_vec(&response) {
        write_frame_at(ptr, capacity, 0, &bytes);
    }
}

/// `RusticServer.nativePruneEstimate(buffer: ByteBuffer): Int`
///
/// 只算不删，返回 `{"ok":true,"bytes":可回收字节数}`。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativePruneEstimate<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        match prune_estimate() {
            Ok(bytes) => {
                write_estimate(ptr, capacity, Ok(bytes));
                0
            }
            Err(message) => {
                write_estimate(ptr, capacity, Err(message));
                1
            }
        }
    })
}

/// `RusticServer.nativeRestore(buffer: ByteBuffer): Int`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeRestore<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        let request = match read_frame_at(ptr, capacity, 0).and_then(|bytes| {
            serde_json::from_slice::<RestoreRequest>(&bytes).map_err(|e| Failure::app(e.to_string()))
        }) {
            Ok(request) => request,
            Err(message) => {
                write_response(ptr, capacity, &BridgeResponse::err(message));
                return 1;
            }
        };

        // 恢复的字节进度走和备份同一套环形缓冲（这块共享内存本来就有进度区），
        // root 侧的 Kotlin 用 RusticProgressPoller 读
        set_current_stage("");
        set_active_progress(Arc::new(ProgressCtx::new(ptr, capacity)));

        let result = restore_snapshots(&request);
        clear_active_progress();

        match result {
            Ok(()) => {
                write_response(ptr, capacity, &BridgeResponse::ok());
                0
            }
            Err(message) => {
                write_response(ptr, capacity, &BridgeResponse::err(message));
                1
            }
        }
    })
}

fn restore_snapshots(request: &RestoreRequest) -> Result<(), Failure> {
    // 恢复只读仓库，但它要读的包可能正被整理仓库删掉，所以和写操作互斥
    let _gate = enter_write_gate();

    if request.items.is_empty() {
        return Ok(());
    }

    // 恢复一个应用会分 5 次进来（apk / user / user_de / data / permissions），
    // 每次都重开仓库 + 读索引的话，固定开销就有 3~4 秒，所以走缓存。
    let repository = open_indexed_repo_cached()?;

    for item in &request.items {
        let snapshot = repository
            .get_snapshots(&[item.id.as_str()])
            .map_err(|e| describe_error(&e))?
            .into_iter()
            .next()
            .ok_or_else(|| Failure::app(format!("备份里没有这个快照：{}", item.id)))?;

        // 字节进度的 stage 由调用方指定：恢复是逐个快照调进来的，
        // Rust 侧只认 id，不知道这是 apk 还是 data
        set_current_stage(&item.stage);
        let bytes = BytesProgress::default();

        restore_snapshot(
            repository.as_ref(),
            &snapshot,
            std::path::Path::new(&item.dest),
            item.delete,
            &bytes,
        )
        .map_err(|e| e.context(format!("恢复 {} 失败：", item.id)))?;
    }

    Ok(())
}

/// 恢复时同时写文件的线程数：手机上 4 个足够，再多只会和前台抢 CPU / IO。
const RESTORE_THREADS: usize = 4;

/// 分块读写的块大小，和 rustic 的平均分块大小一致，避免跨 blob 反复读。
const RESTORE_CHUNK: usize = 1024 * 1024;

/// 恢复线程的 nice 值：让内核优先调度前台 UI，恢复慢一点也不卡。
const RESTORE_NICE: i32 = 10;

/// 大文件内部再切几段并行读写。
///
/// 恢复是按「文件」并行的：应用数据里几百个文件能跑满 [RESTORE_THREADS] 个线程，
/// 但 APK 这种单个几百 MB 的文件只占一个线程，实测只有 35~40 MB/s
/// （微信 APK 解包要 7.5 秒）。这里把大文件本身按偏移切段，每段一个线程
/// 用 `write_at` 定位写，线程之间不共享任何可变状态。
const RESTORE_FILE_THREADS: usize = 4;

/// 小于这个大小就不折腾了，直接单线程写（别给几 KB 的小文件起线程）。
const RESTORE_PARALLEL_MIN: usize = 4 * 1024 * 1024;

/// 把当前线程降为后台优先级（每个线程只设一次）。
fn lower_thread_priority_once() {
    use std::cell::Cell;

    thread_local! {
        static DONE: Cell<bool> = const { Cell::new(false) };
    }

    DONE.with(|done| {
        if done.get() {
            return;
        }
        // Linux 上 nice 是按线程算的，who=0 就是当前线程
        #[cfg(unix)]
        unsafe {
            libc::setpriority(libc::PRIO_PROCESS, 0, RESTORE_NICE);
        }
        done.set(true);
    });
}

/// 恢复线程数：最多 4 个，且给系统留一个核。
fn restore_threads() -> usize {
    let cores = std::thread::available_parallelism()
        .map(std::num::NonZeroUsize::get)
        .unwrap_or(2);
    RESTORE_THREADS.min(cores.saturating_sub(1)).max(1)
}

/// 把单个文件按块读出并写入目标路径。
fn restore_file<S: rustic_core::IndexedFull + Sync>(
    repository: &Repository<S>,
    node: &rustic_core::repofile::Node,
    target: &std::path::Path,
) -> Result<(), Failure> {
    use std::io::Write;

    lower_thread_priority_once();

    let open = repository
        .open_file(node)
        .map_err(|e| describe_error(&e))?;

    // 上一轮可能留下同名目录 / 软链：直接 File::create 会 EISDIR，或者写穿到链接指向的文件
    remove_existing(target);

    let size = node.meta.size as usize;
    let mut file =
        std::fs::File::create(target).map_err(|e| format!("{}: {e}", target.display()))?;

    if size < RESTORE_PARALLEL_MIN {
        // 小文件：单线程顺序写，省掉起线程的开销
        let mut offset = 0usize;
        while offset < size {
            let len = RESTORE_CHUNK.min(size - offset);
            let data = repository
                .read_file_at(&open, offset, len)
                .map_err(|e| describe_error(&e))?;
            file.write_all(&data)
                .map_err(|e| format!("{}: {e}", target.display()))?;
            offset += len;
        }
    } else {
        // 大文件：按偏移切成几段并行读写，每段自己定位写，互不干扰
        let workers = RESTORE_FILE_THREADS.min(size.div_ceil(RESTORE_PARALLEL_MIN));
        let segment = size.div_ceil(workers);

        // 只借用，不移动：几个线程共用同一个文件句柄和同一个 blob 列表
        let file = &file;
        let open = &open;
        let outcome = std::thread::scope(|scope| {
            let mut handles = Vec::with_capacity(workers);

            for index in 0..workers {
                let start = index * segment;
                let end = (start + segment).min(size);
                if start >= end {
                    continue;
                }

                handles.push(scope.spawn(move || -> Result<(), Failure> {
                    lower_thread_priority_once();

                    let mut offset = start;
                    while offset < end {
                        let len = RESTORE_CHUNK.min(end - offset);
                        let data = repository
                            .read_file_at(open, offset, len)
                            .map_err(|e| describe_error(&e))?;
                        write_at(file, &data, offset as u64)
                            .map_err(|e| format!("{}: {e}", target.display()))?;
                        offset += len;
                    }
                    Ok(())
                }));
            }

            let mut failure: Option<Failure> = None;
            for handle in handles {
                match handle.join() {
                    Ok(Ok(())) => {}
                    Ok(Err(error)) => {
                        failure.get_or_insert(error);
                    }
                    Err(_) => {
                        failure.get_or_insert_with(|| Failure::app("恢复线程异常退出"));
                    }
                }
            }
            failure.map_or(Ok(()), Err)
        });

        outcome?;
    }

    file.flush()
        .map_err(|e| format!("{}: {e}", target.display()))?;
    drop(file);

    // 还原文件权限，有些应用数据必须是 0600
    if let Some(mode) = node.meta.mode {
        set_mode(target, mode);
    }

    // 还原修改时间：不写回去的话恢复出来的文件全是「刚刚」
    if let Some(mtime) = node.meta.mtime {
        restore_mtime(target, mtime.as_second(), mtime.subsec_nanosecond().max(0) as u32);
    }

    Ok(())
}

/// 把时间写回文件 / 目录。
fn restore_mtime(path: &std::path::Path, secs: i64, nanos: u32) {
    let Ok(secs) = u64::try_from(secs) else { return };
    let time = std::time::UNIX_EPOCH + std::time::Duration::new(secs, nanos);
    let Ok(handle) = std::fs::File::open(path) else { return };
    let times = std::fs::FileTimes::new()
        .set_modified(time)
        .set_accessed(time);
    let _ = handle.set_times(times);
}

/// 把快照里的相对路径安全地拼到恢复目标目录下。
///
/// 快照里的节点名是**仓库内容**，不能当可信输入：一个被篡改或损坏的仓库里放一个
/// 名为 `..` 的节点，`dest.join("..")` 就跑到目标目录外面去了。`:root` 是 uid 0，
/// 出界等于任意位置写文件，而且 [restore_file] 第一步是 [remove_existing] —— 先删再写。
///
/// 上游 0.14.0 在 `LocalDestination::path()` 里补了同样的逐组件校验（拒绝 RootDir /
/// ParentDir / Prefix）；本项目自己实现了恢复循环、没走那个类型，所以这里得自己挡。
fn join_inside_dest(
    dest: &std::path::Path,
    rel: &std::path::Path,
) -> Result<std::path::PathBuf, Failure> {
    use std::path::Component;

    let mut target = dest.to_path_buf();
    for component in rel.components() {
        match component {
            Component::Normal(part) => target.push(part),
            // 快照路径理论上不会出现 `.`，出现了也不算越界，直接跳过
            Component::CurDir => {}
            Component::Prefix(_) | Component::RootDir | Component::ParentDir => {
                return Err(format!(
                    "快照里的路径不合法（越出恢复目标）：{}",
                    rel.display()
                )
                .into());
            }
        }
    }
    Ok(target)
}

/// 把快照内容展开到 dest（快照里的路径都是相对路径）。
///
/// 目录先建好，文件内容用固定线程池并行写、每块 1 MiB，避免大文件整个读进内存。
fn restore_snapshot<S: rustic_core::IndexedFull + Sync>(
    repository: &Repository<S>,
    snapshot: &SnapshotFile,
    dest: &std::path::Path,
    delete: bool,
    bytes: &BytesProgress,
) -> Result<(), Failure> {
    // 空路径就是快照根节点
    let root = repository
        .node_from_path(snapshot.tree, std::path::Path::new(""))
        .map_err(|e| describe_error(&e))?;

    let streamer = repository
        .ls(&root, &LsOptions::default())
        .map_err(|e| describe_error(&e))?;

    let mut written: std::collections::HashSet<std::path::PathBuf> =
        std::collections::HashSet::new();
    // 目录权限最后再设：先设成只读的话，后面的文件就写不进去了
    let mut dir_modes: Vec<(std::path::PathBuf, u32)> = Vec::new();
    // 目录的修改时间要等所有文件写完再设（写文件会改动父目录的 mtime）
    let mut dir_times: Vec<(std::path::PathBuf, i64, u32)> = Vec::new();
    // 先把目录建好，文件收集起来并行写
    let mut files: Vec<(std::path::PathBuf, rustic_core::repofile::Node)> = Vec::new();
    // 符号链接必须建成真正的链接（以前当空文件写，Termux 这类应用直接废掉）
    let mut symlinks: Vec<(std::path::PathBuf, std::path::PathBuf)> = Vec::new();
    // 硬链接
    let mut hardlink_primary: std::collections::HashMap<(u64, u64), std::path::PathBuf> =
        std::collections::HashMap::new();
    let mut hardlink_followers: Vec<(std::path::PathBuf, std::path::PathBuf)> = Vec::new();

    for entry in streamer {
        let (path, node) = entry.map_err(|e| describe_error(&e))?;
        if path.as_os_str().is_empty() {
            continue;
        }

        // 路径先过闸。不合法就整次恢复失败，而不是跳过这一条：跳过等于告诉用户
        // 「恢复成功」，实际少了一部分数据，比直接报错更糟。
        let target = join_inside_dest(dest, &path)?;

        // written 里存的是快照内的相对路径，[remove_extras] 会拿它的首段拼目标目录，
        // 所以必须先过闸再记录
        written.insert(path.clone());

        // 有 subtree 的就是目录
        if node.subtree.is_some() {
            std::fs::create_dir_all(&target).map_err(|e| format!("{}: {e}", target.display()))?;
            if let Some(mode) = node.meta.mode {
                dir_modes.push((target.clone(), mode));
            }
            if let Some(mtime) = node.meta.mtime {
                dir_times.push((
                    target.clone(),
                    mtime.as_second(),
                    mtime.subsec_nanosecond().max(0) as u32,
                ));
            }
            continue;
        }

        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
        }

        // 符号链接：记下目标，等文件和目录都就位后统一创建
        if matches!(&node.node_type, NodeType::Symlink { .. }) {
            let link = node.node_type.to_link().to_path_buf();
            symlinks.push((target, link));
            continue;
        }

        // fifo / socket / 设备节点在应用数据目录里还原不了，跳过
        if !matches!(&node.node_type, NodeType::File | NodeType::Dir) {
            continue;
        }

        // 硬链接：同一 (device, inode) 只写第一份，后面的建成链接。
        // 不依赖 meta.links —— 有些快照里它并不准，靠「inode 重复出现」判断更可靠。
        if node.meta.inode != 0 {
            let key = (node.meta.device_id, node.meta.inode);
            if let Some(primary) = hardlink_primary.get(&key) {
                hardlink_followers.push((target, primary.clone()));
                continue;
            }
            hardlink_primary.insert(key, target.clone());
        }

        files.push((target, node));
    }

    if !files.is_empty() {
        // 总量直接来自快照元数据：恢复不需要扫盘，也不需要走 rustic 的 ProgressBars
        bytes.set_length(files.iter().map(|(_, node)| node.meta.size).sum());

        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(restore_threads())
            .thread_name(|index| format!("restore-{index}"))
            .build()
            .map_err(|e| format!("无法创建恢复线程：{e}"))?;

        pool.install(|| {
            use rayon::prelude::*;
            files.par_iter().try_for_each(|(target, node)| {
                let result = restore_file(repository, node, target);
                // 失败的文件不计入进度：进度条走完了但少文件，比慢一点更糟
                if result.is_ok() {
                    bytes.inc(node.meta.size);
                }
                result
            })
        })?;

        bytes.finish();
    }

    // 建硬链接：等 primary 的内容全部落盘之后再连
    for (target, primary) in hardlink_followers {
        remove_existing(&target);
        std::fs::hard_link(&primary, &target)
            .map_err(|e| format!("{}: {e}", target.display()))?;
    }

    // 建符号链接：放在设目录权限之前，否则只读目录里建不了
    for (target, link) in symlinks {
        remove_existing(&target);
        create_symlink(&target, &link)
            .map_err(|e| format!("{}: {e}", target.display()))?;
    }

    // 由深到浅设置目录权限
    dir_modes.sort_by_key(|(path, _)| std::cmp::Reverse(path.components().count()));
    for (path, mode) in dir_modes {
        set_mode(&path, mode);
    }

    // 目录时间也由深到浅写回，避免被父目录的写入覆盖
    dir_times.sort_by_key(|(path, _, _)| std::cmp::Reverse(path.components().count()));
    for (path, secs, nanos) in dir_times {
        restore_mtime(&path, secs, nanos);
    }

    // delete：把目标目录里快照没有的文件删掉（等价 rustic 的 --delete）
    if delete {
        remove_extras(dest, &written)?;
    }

    Ok(())
}

/// 目标位置如果有东西就清掉，保证后续 create / symlink 不会撞车。
fn remove_existing(path: &std::path::Path) {
    if let Ok(meta) = std::fs::symlink_metadata(path) {
        if meta.is_dir() {
            let _ = std::fs::remove_dir_all(path);
        } else {
            let _ = std::fs::remove_file(path);
        }
    }
}

#[cfg(unix)]
fn create_symlink(target: &std::path::Path, link: &std::path::Path) -> std::io::Result<()> {
    std::os::unix::fs::symlink(link, target)
}

#[cfg(not(unix))]
fn create_symlink(_target: &std::path::Path, _link: &std::path::Path) -> std::io::Result<()> {
    Ok(())
}

/// 在指定偏移写一块数据（Android 上是 pwrite）。
///
/// 大文件并行恢复靠它：几个线程各写自己那一段，不需要加锁，也不用管顺序。
#[cfg(unix)]
fn write_at(file: &std::fs::File, data: &[u8], offset: u64) -> std::io::Result<()> {
    use std::os::unix::fs::FileExt;
    file.write_all_at(data, offset)
}

#[cfg(not(unix))]
fn write_at(_file: &std::fs::File, _data: &[u8], _offset: u64) -> std::io::Result<()> {
    Ok(())
}

/// 设置权限位（Windows 上编译时是空实现，真机是 Android）。
#[cfg(unix)]
fn set_mode(path: &std::path::Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode & 0o7777));
}

#[cfg(not(unix))]
fn set_mode(_path: &std::path::Path, _mode: u32) {}

/// 删掉 dest 下不在 written 里的条目，只处理快照出现过的顶层目录，避免误删别的应用。
fn remove_extras(
    dest: &std::path::Path,
    written: &std::collections::HashSet<std::path::PathBuf>,
) -> Result<(), Failure> {
    let mut roots: Vec<std::path::PathBuf> = Vec::new();
    for path in written {
        // 只认普通组件：这一步会真的删文件，不能给 `..` / 绝对路径任何机会
        if let Some(std::path::Component::Normal(first)) = path.components().next() {
            let root = dest.join(first);
            if !roots.contains(&root) {
                roots.push(root);
            }
        }
    }

    for root in roots {
        let mut extras: Vec<std::path::PathBuf> = Vec::new();
        collect_extras(dest, &root, written, &mut extras)?;

        // 由深到浅删，保证父目录在子项之后处理
        extras.sort_by_key(|p| std::cmp::Reverse(p.components().count()));
        for extra in extras {
            // 用 symlink_metadata：符号链接要当文件删，不能跟进去
            let is_dir = std::fs::symlink_metadata(&extra)
                .map(|meta| meta.is_dir())
                .unwrap_or(false);

            if is_dir {
                let _ = std::fs::remove_dir_all(&extra);
            } else {
                let _ = std::fs::remove_file(&extra);
            }
        }
    }

    Ok(())
}

fn collect_extras(
    dest: &std::path::Path,
    dir: &std::path::Path,
    written: &std::collections::HashSet<std::path::PathBuf>,
    out: &mut Vec<std::path::PathBuf>,
) -> Result<(), Failure> {
    let entries = match std::fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(_) => return Ok(()),
    };

    for entry in entries.flatten() {
        let path = entry.path();
        let relative = match path.strip_prefix(dest) {
            Ok(relative) => relative.to_path_buf(),
            Err(_) => continue,
        };

        if !written.contains(&relative) {
            out.push(path);
            continue;
        }

        // 只递归真实目录，符号链接不跟进去
        let is_real_dir = entry
            .file_type()
            .map(|tpe| tpe.is_dir())
            .unwrap_or(false);

        if is_real_dir {
            collect_extras(dest, &path, written, out)?;
        }
    }

    Ok(())
}

// ==================== 备份 ====================

/// `RusticServer.nativeBackup(buffer: ByteBuffer): Int`
///
/// 返回 0 成功，1 业务失败，2 共享内存不可用。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RusticServer_nativeBackup<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    let outcome = env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let ptr = env.get_direct_buffer_address(&buffer)?;
        let capacity = env.get_direct_buffer_capacity(&buffer)?;

        if ptr.is_null() || capacity <= PROGRESS_OFFSET + 8 {
            return Ok(2);
        }

        Ok(handle_backup(ptr, capacity))
    });

    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}

fn handle_backup(ptr: *mut u8, capacity: usize) -> jint {
    let request = match read_frame_at(ptr, capacity, 0)
        .and_then(|bytes| serde_json::from_slice::<BackupRequest>(&bytes).map_err(|e| Failure::app(e.to_string())))
    {
        Ok(request) => request,
        Err(message) => {
            write_response(ptr, capacity, &BridgeResponse::err(message));
            return 1;
        }
    };

    let progress = Arc::new(ProgressCtx::new(ptr, capacity));

    // 让 rustic 内部的字节进度（ProgressBars）能写到这块 buffer 上。
    // 必须在 backup_repo 之前挂、之后摘：缓存的 Repository 如果拿着上一次调用
    // 留下的旧地址，emit 会因为全局槽为空而静默跳过，不会写野指针。
    set_current_stage("");
    set_active_progress(progress.clone());
    let result = backup_repo(&request, progress.clone());
    clear_active_progress();

    match result {
        Ok(()) => {
            progress.finish();
            write_response(ptr, capacity, &BridgeResponse::ok());
            0
        }
        Err(message) => {
            progress.finish();
            write_response(ptr, capacity, &BridgeResponse::err(message));
            1
        }
    }
}

fn backup_repo(request: &BackupRequest, progress: Arc<ProgressCtx>) -> Result<(), Failure> {
    // 整批备份占住写闸门：批量备份期间任何一笔 prune / 删除都要排队，
    // 否则刚写完还没进索引的包会被当成无引用数据回收掉
    let _gate = enter_write_gate();

    // 备份会写入新的 pack 和索引，恢复用的索引缓存必须作废
    invalidate_indexed_repo();

    // 这一段是「每个应用一次」的固定开销：scrypt 密钥派生 + 读一遍索引。
    // 同批次内复用，命中缓存就不用再付。
    let repository =
        open_backup_repo_cached(request.batch.as_deref())?;

    let result = backup_items(request, repository.as_ref(), progress.clone());

    // 收尾必须失效：备份期间如果有 listSnapshots 跑过一次，它会把「备份前的索引」
    // 重新缓存进来，之后读到的快照列表就缺了这次刚写进去的应用（最长 10 分钟）。
    invalidate_indexed_repo();

    // 备份过程中任何一步失败，缓存的索引就可能和磁盘对不上，直接丢掉
    if result.is_err() {
        invalidate_backup_repo();
    }
    result
}

fn backup_items(
    request: &BackupRequest,
    repository: &Repository<rustic_core::IndexedIdsStatus>,
    progress: Arc<ProgressCtx>,
) -> Result<(), Failure> {
    for item in &request.items {
        progress.emit(&item.stage, "running");

        let sources: Vec<PathBuf> = item
            .sources
            .iter()
            .map(PathBuf::from)
            .filter(|path| path.exists())
            .collect();

        if sources.is_empty() {
            // 没有可备份的路径就跳过，不能让 LocalSource 拿到空列表
            let reason = item
                .skip_reason
                .clone()
                .unwrap_or_else(|| format!("源路径不存在：{}", item.sources.join(", ")));
            progress.emit_skipped(&item.stage, &reason);
            continue;
        }

        let mut snapshot_options = SnapshotOptions::default();
        snapshot_options.label = Some(item.label.clone());
        if !item.tags.is_empty() {
            let tags = item.tags.join(",");
            snapshot_options.tags = vec![tags
                .parse::<StringList>()
                .map_err(|e| format!("标签格式不合法（{tags}）：{e}"))?];
        }

        snapshot_options.description.clone_from(&item.description);

        let snapshot = SnapshotFile::from_options(&snapshot_options)
            .map_err(|e| describe_error(&e))?;

        let mut backup_options = BackupOptions::default();
        backup_options.as_path = item.as_path.as_ref().map(PathBuf::from);
        // 排除规则由 Kotlin 侧传入（gitignore 语义：`!` 开头表示排除）。
        // item 自己带了就用自己的，否则用整批共用的。
        backup_options.excludes.globs = if item.excludes.is_empty() {
            request.excludes.clone()
        } else {
            item.excludes.clone()
        };
        // 保留默认的 no_scan = false：rustic 会在独立线程里并行扫一遍源目录拿总字节，
        // 给进度条当分母（`archiver` 里是 `scope.spawn`，和备份内容读并行，不阻塞主流程）。
        // 关掉它进度条就永远只有「已处理多少」，没有「总共多少」。

        // 直接指定 parent：省掉 rustic 内部的「列全部快照找最新」这一遍扫描。
        // 客户端从备份概况里拿到同类型最新快照的 id 传过来；没传就退回默认行为。
        if let Some(parent) = &item.parent {
            backup_options.parent_opts.parents = vec![parent.clone()];
        }

        // inode 比较：0.13.0 里 `is_parent` 把条件写反了（`!ignore_inode`），所以
        // 默认**从不**比较 inode；0.14.0 修正后默认比较。
        //
        // 本项目的备份源几乎都落在真实文件系统（/data/user、/data/user_de、
        // /data/media、/data/app），inode 跨备份、跨重启稳定，比较它只会更准
        // （比如被「复制替换」的文件：mtime 被保留、inode 变了，以前会漏判）。
        // 唯一例外是 SD 卡 / 非主用户这类 FUSE 视图 —— 那里的 inode 编号由用户态
        // 分配，重挂载后可能整体变一遍。对它们由客户端传 ignore_inode 关掉比较：
        // 真改过内容时 ctime 一定会变，靠 ctime 仍然能发现，不损失正确性。
        backup_options.parent_opts.ignore_inode = item.ignore_inode;

        let paths: PathList = sources.into_iter().collect();

        let snapshot = repository
            .backup(&backup_options, &paths, snapshot)
            // 前缀不能吃掉类别：传输层失败照样要报成「连不上」
            .map_err(|e| describe_error(&e).context(format!("备份 {} 失败：", item.stage)))?;

        // 0.14.0 起快照摘要带 error_count：源文件读不出来（SELinux 拒绝、权限不够、
        // 文件正在被写）时 rustic 只打日志、照样出快照、照样返回成功。不把这个数
        // 报上去，用户看到的就是「备份成功」，实际少了一部分数据。
        let (files, errors) = snapshot
            .summary
            .as_ref()
            .map(|summary| {
                (
                    Some(summary.total_files_processed),
                    summary.error_count,
                )
            })
            .unwrap_or((None, 0));

        progress.emit_done(&item.stage, files, errors);
    }

    Ok(())
}

// ==================== 进度 ====================

struct ProgressCtx {
    ptr: *mut u8,
    capacity: usize,
    counter: AtomicU32,
    write_lock: Mutex<()>,
}

// 指针只在 JNI 调用期间有效，且所有写入都加了锁
unsafe impl Send for ProgressCtx {}
unsafe impl Sync for ProgressCtx {}

impl fmt::Debug for ProgressCtx {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ProgressCtx").finish()
    }
}

impl ProgressCtx {
    fn new(ptr: *mut u8, capacity: usize) -> Self {
        Self {
            ptr,
            capacity,
            counter: AtomicU32::new(0),
            write_lock: Mutex::new(()),
        }
    }

    /// 写入一条状态：`{"stage":"apk","state":"done","files":12,"errors":3,"reason":"..."}`
    ///
    /// `errors` 是这次快照里**读不出来的源文件个数**（0 就不写这个字段）。
    fn emit(&self, stage: &str, state: &str) {
        // 记下当前步骤：字节进度帧只带 total / current，步骤名要在这里补，
        // 否则读端拿到一堆字节数却不知道该挂到哪一步上
        if state == "running" && !stage.is_empty() {
            set_current_stage(stage);
        }
        self.write_event(stage, state, None, None, None);
    }

    /// 一个步骤备份完成：带上文件数和读取失败数。
    fn emit_done(&self, stage: &str, files: Option<u64>, errors: u64) {
        self.write_event(stage, "done", files, (errors > 0).then_some(errors), None);
    }

    fn emit_skipped(&self, stage: &str, reason: &str) {
        self.write_event(stage, "skipped", None, None, Some(reason));
    }

    /// 传输层错误：后端已经判出「连不上」，立刻写一条帧让主进程停掉进度。
    /// `reason` 是 `FailureReason.wire`，文案由主进程查表，不在这里拼中文。
    /// 和字节帧走同一个环形缓冲，读端 60ms 内就能拿到。
    fn emit_interrupted(&self, stage: &str, reason: &str) {
        self.write_event(stage, "interrupted", None, None, Some(reason));
    }

    fn write_event(
        &self,
        stage: &str,
        state: &str,
        files: Option<u64>,
        errors: Option<u64>,
        reason: Option<&str>,
    ) {
        let mut json = format!(
            r#"{{"stage":"{}","state":"{}""#,
            escape_json(stage),
            state
        );
        if let Some(files) = files {
            json.push_str(&format!(r#","files":{files}"#));
        }
        if let Some(errors) = errors {
            json.push_str(&format!(r#","errors":{errors}"#));
        }
        if let Some(reason) = reason {
            json.push_str(&format!(r#","reason":"{}""#, escape_json(reason)));
        }
        json.push('}');
        self.write_raw(&json);
    }

    /// 字节进度：`{"stage":"data","state":"progress","total":…,"current":…}`。
    ///
    /// 和步骤事件走同一个环形缓冲，读端按 `state` 区分。
    fn write_progress(&self, stage: &str, total: u64, current: u64) {
        let json = format!(
            r#"{{"stage":"{}","state":"progress","total":{total},"current":{current}}}"#,
            escape_json(stage)
        );
        self.write_raw(&json);
    }

    /// 往环形缓冲写一格并推进计数。所有事件共用这一条路径。
    fn write_raw(&self, json: &str) {
        let _guard = self.write_lock.lock();
        let index = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        let slot = ((index - 1) as usize) % PROGRESS_SLOT_COUNT;
        let offset = PROGRESS_OFFSET + PROGRESS_COUNTER_SIZE + slot * PROGRESS_SLOT_SIZE;
        write_frame_at(self.ptr, self.capacity, offset, json.as_bytes());

        // 槽位写好之后再推进计数，读端不会读到半截数据
        let counter_ptr = unsafe { self.ptr.add(PROGRESS_OFFSET) };
        let header = index.to_be_bytes();
        unsafe {
            std::ptr::copy_nonoverlapping(header.as_ptr(), counter_ptr, 4);
        }
    }

    fn finish(&self) {
        self.emit("", "finished");
    }
}

// ==================== rustic 字节进度 ====================

/// 当前这次 JNI 调用可写的进度端。
///
/// **必须放全局，不能挂在 `ProgressBars` 实例上**：`Repository` 会被
/// `open_indexed_repo_cached()` 缓存并跨多次 JNI 调用复用，而每次调用拿到的
/// direct buffer 地址可能不同。挂在实例上，第二次调用写的就是已经失效的地址。
static ACTIVE_PROGRESS: Mutex<Option<Arc<ProgressCtx>>> = Mutex::new(None);

/// 当前正在备份的步骤（`apk` / `user` / `data` …）。
///
/// rustic 的字节进度回调只给一个 `"backing up..."` 前缀，没有步骤信息；
/// 步骤由 `backup_items` 在开始每一项时设置，字节帧读它补上 stage。
static CURRENT_STAGE: Mutex<String> = Mutex::new(String::new());

fn set_current_stage(stage: &str) {
    if let Ok(mut guard) = CURRENT_STAGE.lock() {
        guard.clear();
        guard.push_str(stage);
    }
}

fn current_stage() -> String {
    CURRENT_STAGE.lock().map(|g| g.clone()).unwrap_or_default()
}

fn set_active_progress(ctx: Arc<ProgressCtx>) {
    if let Ok(mut guard) = ACTIVE_PROGRESS.lock() {
        *guard = Some(ctx);
    }
}

fn clear_active_progress() {
    if let Ok(mut guard) = ACTIVE_PROGRESS.lock() {
        *guard = None;
    }
}

/// 写帧节流间隔：`inc` 每 1 MiB 调一次，7 GB 就是 7000 次；
/// 读端 60ms 才轮询一次，写那么密纯属浪费。
const PROGRESS_THROTTLE_MS: u64 = 200;

/// 进程启动时刻，用于节流计时（`Instant` 不能直接放进 atomic）。
static PROGRESS_EPOCH: OnceLock<Instant> = OnceLock::new();

fn elapsed_ms() -> u64 {
    PROGRESS_EPOCH
        .get_or_init(Instant::now)
        .elapsed()
        .as_millis() as u64
}

/// rustic 内部每调用一次 `progress_bytes()` 就创建一条，对应一个快照的字节进度。
#[derive(Debug, Default)]
struct BytesProgress {
    total: AtomicU64,
    current: AtomicU64,
    last_emit_ms: AtomicU64,
}

impl RusticProgress for BytesProgress {
    /// 必须返回 false：`archiver` 里只有 `!p.is_hidden()` 才会去扫描源目录
    /// 拿总字节数，返回 true 就永远没有分母。
    fn is_hidden(&self) -> bool {
        false
    }

    fn set_length(&self, len: u64) {
        self.total.store(len, Ordering::Relaxed);
        self.emit(true);
    }

    fn set_title(&self, _title: &str) {}

    fn inc(&self, inc: u64) {
        self.current.fetch_add(inc, Ordering::Relaxed);
        self.emit(false);
    }

    fn finish(&self) {
        self.emit(true);
    }
}

impl BytesProgress {
    fn emit(&self, force: bool) {
        let now = elapsed_ms();
        if !force {
            let last = self.last_emit_ms.load(Ordering::Relaxed);
            if now.saturating_sub(last) < PROGRESS_THROTTLE_MS {
                return;
            }
        }
        self.last_emit_ms.store(now, Ordering::Relaxed);

        let Ok(guard) = ACTIVE_PROGRESS.lock() else {
            return;
        };
        let Some(ctx) = guard.as_ref() else {
            return;
        };
        ctx.write_progress(
            &current_stage(),
            self.total.load(Ordering::Relaxed),
            self.current.load(Ordering::Relaxed),
        );
    }
}

/// 把 rustic 的进度回调接到共享内存上。
///
/// 零大小类型：`Repository` 会被缓存复用，挂状态就会在两次调用之间串味。
#[derive(Debug, Clone, Copy)]
struct BridgeProgressBars;

impl ProgressBars for BridgeProgressBars {
    fn progress(&self, progress_type: ProgressType, _prefix: &str) -> Progress {
        match progress_type {
            // 只有字节进度值得上报。Spinner / Counter 是索引文件和收尾用的，
            // 对用户没意义，而且频率高，写进环形缓冲只会把真正的进度挤掉。
            ProgressType::Bytes => Progress::new(BytesProgress::default()),
            _ => Progress::hidden(),
        }
    }
}

// ==================== 帧读写 ====================

/// 读取指定偏移处的「大端 i32 长度 + JSON」帧。
fn read_frame_at(ptr: *mut u8, capacity: usize, offset: usize) -> Result<Vec<u8>, Failure> {
    if offset + 4 > capacity {
        return Err(Failure::app("共享内存太小"));
    }

    unsafe {
        let header = std::slice::from_raw_parts(ptr.add(offset), 4);
        let length = i32::from_be_bytes([header[0], header[1], header[2], header[3]]);

        if length < 0 {
            return Err(format!("数据长度非法：{length}").into());
        }

        let length = length as usize;
        if offset + 4 + length > capacity {
            return Err(Failure::app("数据超出共享内存"));
        }

        Ok(std::slice::from_raw_parts(ptr.add(offset + 4), length).to_vec())
    }
}

/// 在指定偏移处写入「大端 i32 长度 + JSON」帧。
fn write_frame_at(ptr: *mut u8, capacity: usize, offset: usize, bytes: &[u8]) {
    if offset + 4 > capacity {
        return;
    }

    let available = capacity - offset - 4;

    // 装不下就写一条错误响应。以前是直接截断，客户端拿到半截 JSON 解析必失败，
    // 表现成「莫名其妙一个备份都没有」，还查不出原因。
    if bytes.len() > available {
        let message = format!(
            r#"{{"ok":false,"error":"响应过大：{} 字节，缓冲区只有 {} 字节"}}"#,
            bytes.len(),
            available
        );
        let payload = message.as_bytes();
        let length = payload.len().min(available);
        unsafe {
            std::ptr::copy_nonoverlapping(
                (length as i32).to_be_bytes().as_ptr(),
                ptr.add(offset),
                4,
            );
            std::ptr::copy_nonoverlapping(payload.as_ptr(), ptr.add(offset + 4), length);
        }
        return;
    }

    let header = (bytes.len() as i32).to_be_bytes();
    unsafe {
        std::ptr::copy_nonoverlapping(header.as_ptr(), ptr.add(offset), 4);
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), ptr.add(offset + 4), bytes.len());
    }
}

fn write_response(ptr: *mut u8, capacity: usize, response: &BridgeResponse) {
    if let Ok(json) = serde_json::to_vec(response) {
        write_frame_at(ptr, capacity, 0, &json);
    }
}

fn escape_json(value: &str) -> String {
    value
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', " ")
}

// ==================== 失败原因判定 ====================

/// 失败原因的唯一判定点（只有这里能看 error_code / io 类型 / source 链）。
///
/// 顺序：官方 error_code → reqwest（在 io 外层，先判才能拿到 401/403）→
/// io kind → 文本兜底。认不出来返回 None，让主进程显示原文，不瞎猜。
fn classify_error(error: &rustic_core::RusticError) -> Option<Reason> {
    if error.is_incorrect_password() {
        return Some(Reason::Password);
    }
    // C003：解密后校验不一致 —— 数据损坏
    if error.is_code("C003") {
        return Some(Reason::CorruptData);
    }

    let mut cause: Option<&(dyn std::error::Error + 'static)> = Some(error);
    while let Some(current) = cause {
        if let Some(http) = current.downcast_ref::<reqwest::Error>() {
            return Some(match http.status().map(|status| status.as_u16()) {
                Some(401) | Some(403) => Reason::Auth,
                _ if http.is_timeout() => Reason::Timeout,
                // 404 也归这里：仓库结构在打开前已经由主进程查过，open 阶段还拿不到
                // 响应就是这条 rest 通道不通
                _ => Reason::Unreachable,
            });
        }
        if let Some(io) = current.downcast_ref::<std::io::Error>() {
            // StorageFull 还没稳定，只能认 ENOSPC 和文本
            if io.raw_os_error() == Some(28) || io.to_string().contains("No space left on device")
            {
                return Some(Reason::NoSpace);
            }
            return match io.kind() {
                std::io::ErrorKind::TimedOut => Some(Reason::Timeout),
                // 仓库里的文件缺失（keys / index / 快照 / pack）：数据不完整
                std::io::ErrorKind::NotFound => Some(Reason::CorruptData),
                // 权限错不单独归类：root 进程不会遇到，真出现说明环境异常，交给原文兜底
                _ => None,
            };
        }
        cause = current.source();
    }

    // 上游若换了错误包装方式，source 链里取不到类型时按文本兜底，只认特征明显的写法。
    let text = error.to_string();
    if text.contains("status server error")
        || text.contains("status client error")
        || text.contains("error sending request for url")
    {
        return Some(Reason::Unreachable);
    }
    None
}

/// rustic 的错误全文里可能带 `http://user:pass@host/...` 形式的 rest_url，
/// 抹掉 userinfo 再交给 Kotlin（这段文本只进日志）。
fn redact_userinfo(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(scheme) = rest.find("://") {
        let head = &rest[..scheme + 3];
        let after = &rest[scheme + 3..];
        match after.find('@') {
            Some(at)
                if !after[..at].contains('/')
                    && !after[..at].chars().any(char::is_whitespace) =>
            {
                out.push_str(head);
                out.push_str("***@");
                rest = &after[at + 1..];
            }
            _ => {
                out.push_str(head);
                rest = after;
            }
        }
    }
    out.push_str(rest);
    out
}

/// 去掉 Display 里的文档链接 / issue 模板 / Backtrace，只留错误本身 + 原因。
fn describe_error(error: &rustic_core::RusticError) -> Failure {
    let text = error.to_string();
    let mut out = String::new();

    for line in text.lines() {
        let line = line.trim();

        if line.is_empty() {
            if !out.is_empty() && !out.ends_with("\n\n") {
                out.push('\n');
            }
            continue;
        }

        // Backtrace 是 Display 的最后一节，从这里起后面全部丢掉（含栈帧和那句提示）。
        if line.starts_with("Backtrace:") {
            break;
        }

        if line.starts_with("For more information, see:")
            || line.starts_with("We believe this is a bug")
            || line.starts_with("Related issues:")
            || line.starts_with("- http")
            || line.starts_with("http")
            || line.starts_with("Some additional details")
            || line.starts_with("Please report it by opening an issue")
            || line.starts_with("If you can, please attach")
            || line.starts_with("Thank you for helping")
        {
            continue;
        }

        out.push_str(line);
        out.push('\n');
    }

    let cleaned = out.trim().to_string();
    let full = if cleaned.is_empty() { text } else { cleaned };
    let message = redact_userinfo(&full);

    Failure {
        message,
        reason: classify_error(error),
    }
}

#[cfg(test)]
mod tests {
    use super::{
        backup_repo, clear_active_progress, describe_error, init_repo, read_frame_at, repo_layout,
        set_active_progress, set_current_stage, set_repo, BackupItem, BackupRequest, BytesProgress,
        ProgressCtx, Reason, PROGRESS_COUNTER_SIZE, PROGRESS_OFFSET, PROGRESS_SLOT_COUNT,
        PROGRESS_SLOT_SIZE,
    };
    use rustic_core::RusticProgress;
    use std::sync::{Arc, Mutex};

    /// Backtrace 是 Display 的最后一节，从源头截断，日志和 UI 都不再出现这一段。
    #[test]
    fn describe_error_drops_backtrace_section() {
        let err = rustic_core::RusticError::new(
            rustic_core::ErrorKind::Other,
            "No space left on device",
        );
        let failure = describe_error(&err);
        let text = &failure.message;
        assert!(text.contains("No space left on device"), "{text}");
        assert!(!text.contains("Backtrace"), "{text}");
        assert!(!text.contains("disabled backtrace"), "{text}");
        assert!(!text.contains("RUST_BACKTRACE"), "{text}");
        // 认不出原因时不能硬猜一个类别：主进程会拿原文兜底，编错了比不编更糟
        assert_eq!(failure.reason, None, "{text}");
    }

    /// 凭据现在是进程级静态状态，测试必须串行跑，否则会互相覆盖仓库路径。
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    fn setup_repo(path: &str) -> std::sync::MutexGuard<'static, ()> {
        let guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        set_repo(path, "rustic", Default::default());
        init_repo(path, "rustic", &Default::default(), true).expect("init 应该成功");
        guard
    }

    #[test]
    fn backup_creates_snapshot() {
        let base = std::env::temp_dir().join(format!("rustic-backup-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        let repo_dir = base.join("repo");
        let src_dir = base.join("src");
        std::fs::create_dir_all(&repo_dir).unwrap();
        std::fs::create_dir_all(&src_dir).unwrap();
        std::fs::write(src_dir.join("hello.txt"), b"hello rustic").unwrap();

        let repo = repo_dir.to_string_lossy().to_string();
        let _guard = setup_repo(&repo);

        let request = BackupRequest {
            items: vec![BackupItem {
                stage: "apk".to_string(),
                label: "com.example:apk".to_string(),
                tags: vec![
                    "com.example".to_string(),
                    "user:0".to_string(),
                    "1700000000".to_string(),
                ],
                sources: vec![src_dir.to_string_lossy().to_string()],
                as_path: None,
                skip_reason: None,
                description: None,
                parent: None,
                excludes: Vec::new(),
                ignore_inode: false,
            }],
            excludes: Vec::new(),
            batch: None,
        };

        let mut buffer = vec![0u8; 576 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).expect("backup 应该成功");

        let snapshots = repo_dir.join("snapshots");
        let count = std::fs::read_dir(&snapshots).map(|d| d.count()).unwrap_or(0);
        assert!(count > 0, "没有生成快照");

        let _ = std::fs::remove_dir_all(&base);
    }

    /// 端到端：rustic 的字节进度必须真的流到共享内存里。
    ///
    /// 这条链路有三处容易断，任何一处都会让帧被静默丢弃、UI 永远停在不确定态：
    /// `is_hidden()` 返回 true、`no_scan = true`、全局进度端没挂上。
    #[test]
    fn backup_emits_byte_progress() {
        let base = std::env::temp_dir().join(format!("rustic-byte-progress-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        let repo_dir = base.join("repo");
        let src_dir = base.join("src");
        std::fs::create_dir_all(&repo_dir).unwrap();
        std::fs::create_dir_all(&src_dir).unwrap();
        std::fs::write(src_dir.join("hello.txt"), b"hello rustic").unwrap();

        let repo = repo_dir.to_string_lossy().to_string();
        // setup_repo 持着 TEST_LOCK：进度端是全局的，不能和别的测试并行
        let _guard = setup_repo(&repo);

        let request = BackupRequest {
            items: vec![BackupItem {
                stage: "user".to_string(),
                label: "com.example:user".to_string(),
                tags: vec!["com.example".to_string()],
                sources: vec![src_dir.to_string_lossy().to_string()],
                as_path: None,
                skip_reason: None,
                description: None,
                parent: None,
                excludes: Vec::new(),
                ignore_inode: false,
            }],
            excludes: Vec::new(),
            batch: None,
        };

        let mut buffer = vec![0u8; PROGRESS_OFFSET + 64 * 1024];
        let capacity = buffer.len();
        let ptr = buffer.as_mut_ptr();
        let progress = Arc::new(ProgressCtx::new(ptr, capacity));

        set_current_stage("");
        set_active_progress(progress.clone());
        let result = backup_repo(&request, progress);
        clear_active_progress();
        result.expect("backup 应该成功");

        // 字节帧和步骤帧穿插在同一圈槽位里，要全部扫一遍
        let mut totals = Vec::new();
        for slot in 0..PROGRESS_SLOT_COUNT {
            let offset = PROGRESS_OFFSET + PROGRESS_COUNTER_SIZE + slot * PROGRESS_SLOT_SIZE;
            let Ok(bytes) = read_frame_at(ptr, capacity, offset) else {
                continue;
            };
            let Ok(text) = String::from_utf8(bytes) else {
                continue;
            };
            let Ok(value) = serde_json::from_str::<serde_json::Value>(&text) else {
                continue;
            };
            if value["state"] == "progress" && value["stage"] == "user" {
                totals.push(value["total"].as_u64().unwrap_or(0));
            }
        }

        assert!(!totals.is_empty(), "没有收到任何字节进度帧");
        assert!(
            totals.iter().any(|&total| total > 0),
            "字节进度的总量一直是 0：{totals:?}"
        );

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn init_creates_repository() {
        let dir = std::env::temp_dir().join(format!("rustic-bridge-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);

        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.to_string_lossy().to_string();
        let _guard = setup_repo(&path);

        // 初始化后仓库目录里应该有 config 文件
        assert!(dir.join("config").exists(), "仓库缺少 config 文件");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 建一个空仓库并返回目录，供「结构」类测试复用。
    fn init_temp_repo(prefix: &str, password: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("{prefix}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        init_repo(&dir.to_string_lossy(), password, &Default::default(), true)
            .expect("首次建库应成功");
        dir
    }

    /// 密码错：config 和 key 都在、只是解不开 —— 必须报密码。
    #[test]
    fn init_reports_wrong_password() {
        let dir = init_temp_repo("rustic-pwd-test", "right-password");

        let error = init_repo(
            &dir.to_string_lossy(),
            "wrong-password",
            &Default::default(),
            false,
        )
        .expect_err("密码错必须失败");

        assert_eq!(error.reason, Some(Reason::Password), "{}", error.message);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 空目录：五类都不存在 —— 主进程据此走「建库」。
    #[test]
    fn repo_layout_reports_empty_dir() {
        let dir = std::env::temp_dir().join(format!("rustic-layout-empty-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        let layout = repo_layout(&dir.to_string_lossy(), &Default::default()).unwrap();

        assert!(!layout.config, "空目录不该有 config");
        assert!(!layout.keys, "空目录不该有 keys");
        assert!(!layout.data && !layout.index && !layout.snapshots);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 刚建好的仓库五类都在：rustic 的 init 会把 data / index / snapshots 建成空目录。
    /// 主进程据此判「完整」→ 直接打开。
    #[test]
    fn repo_layout_after_init_reports_all_five() {
        let dir = init_temp_repo("rustic-layout-test", "rustic");

        let layout = repo_layout(&dir.to_string_lossy(), &Default::default()).unwrap();

        assert!(layout.config, "config 应该存在");
        assert!(layout.keys, "keys 应该存在");
        assert!(layout.data, "data 应该存在（init 会建空目录）");
        assert!(layout.index, "index 应该存在");
        assert!(layout.snapshots, "snapshots 应该存在");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 删掉 keys：config 还在 —— 主进程据此报「仓库目录有缺失」，而不是密码错。
    #[test]
    fn repo_layout_sees_missing_keys() {
        let dir = init_temp_repo("rustic-layout-nokeys", "rustic");
        std::fs::remove_dir_all(dir.join("keys")).unwrap();

        let layout = repo_layout(&dir.to_string_lossy(), &Default::default()).unwrap();

        assert!(layout.config, "config 还在");
        assert!(!layout.keys, "keys 已被删掉");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn backup_multiple_sources() {
        let base = std::env::temp_dir().join(format!("rustic-multi-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        let repo_dir = base.join("repo");
        let dir_a = base.join("a");
        let dir_b = base.join("b");
        std::fs::create_dir_all(&repo_dir).unwrap();
        std::fs::create_dir_all(&dir_a).unwrap();
        std::fs::create_dir_all(&dir_b).unwrap();
        std::fs::write(dir_a.join("a.txt"), b"aaaaaaaaaa").unwrap();
        std::fs::write(dir_b.join("b.txt"), b"bbbbbbbbbbbb").unwrap();

        let repo = repo_dir.to_string_lossy().to_string();
        let _guard = setup_repo(&repo);

        let request = BackupRequest {
            items: vec![BackupItem {
                stage: "user".to_string(),
                label: "com.example:user".to_string(),
                tags: vec!["com.example".to_string()],
                sources: vec![
                    dir_a.to_string_lossy().to_string(),
                    dir_b.to_string_lossy().to_string(),
                ],
                as_path: None,
                skip_reason: None,
                description: None,
                parent: None,
                excludes: Vec::new(),
                ignore_inode: false,
            }],
            excludes: Vec::new(),
            batch: None,
        };

        let mut buffer = vec![0u8; 576 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).expect("backup 应该成功");

        // 用仓库 API 读回快照，确认两个源目录的内容都进去了
        let backends = super::repository_backends(&repo, &Default::default()).unwrap();
        let mut options = rustic_core::RepositoryOptions::default();
        options.no_cache = true;
        let opened = rustic_core::Repository::new(&options, &backends)
            .unwrap()
            .open(&rustic_core::Credentials::password("rustic"))
            .unwrap()
            .to_indexed_ids()
            .unwrap();

        let ids: Vec<String> = std::fs::read_dir(repo_dir.join("snapshots"))
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().to_string())
            .collect();
        let snaps = opened.get_snapshots(&ids).unwrap();
        println!("snaps={} details={:?}", snaps.len(), snaps.iter().map(|s| (s.label.clone(), s.summary.as_ref().map(|x| x.total_bytes_processed))).collect::<Vec<_>>());
        let total: u64 = snaps.iter().filter_map(|s| s.summary.as_ref()).map(|s| s.total_bytes_processed).sum();
        assert_eq!(total, 22, "两个源目录的内容都应该被备份，实际 {total} 字节");
    }

    #[test]
    fn backup_with_as_path() {
        let base = std::env::temp_dir().join(format!("rustic-asp-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        let repo_dir = base.join("repo");
        let dir = base.join("data");
        std::fs::create_dir_all(&repo_dir).unwrap();
        std::fs::create_dir_all(dir.join("files")).unwrap();
        std::fs::write(dir.join("files").join("a.txt"), b"aaaaaaaaaa").unwrap();
        std::fs::write(dir.join("base.apk"), b"bbbbbbbbbbbb").unwrap();

        let repo = repo_dir.to_string_lossy().to_string();
        let _guard = setup_repo(&repo);

        // 目录：as_path = 包名；文件：as_path = 文件名
        let request = BackupRequest {
            items: vec![
                BackupItem {
                    stage: "user".to_string(),
                    label: "pkg:user".to_string(),
                    tags: vec!["pkg".to_string()],
                    sources: vec![dir.to_string_lossy().to_string()],
                    as_path: Some("com.example".to_string()),
                    skip_reason: None,
                    description: None,
                    parent: None,
                    excludes: Vec::new(),
                    ignore_inode: false,
                },
                BackupItem {
                    stage: "apk".to_string(),
                    label: "pkg:apk".to_string(),
                    tags: vec!["pkg".to_string()],
                    sources: vec![dir.join("base.apk").to_string_lossy().to_string()],
                    as_path: Some("base.apk".to_string()),
                    skip_reason: None,
                    description: None,
                    parent: None,
                    excludes: Vec::new(),
                    ignore_inode: false,
                },
            ],
            excludes: Vec::new(),
            batch: None,
        };

        let mut buffer = vec![0u8; 576 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).expect("带 as_path 的备份应该成功");

        let ids: Vec<String> = std::fs::read_dir(repo_dir.join("snapshots"))
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().to_string())
            .collect();
        assert_eq!(ids.len(), 2, "应该生成两个快照");

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn list_snapshots_by_label_prefix() {
        let base = std::env::temp_dir().join(format!("rustic-list-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        let repo_dir = base.join("repo");
        let src = base.join("src");
        std::fs::create_dir_all(&repo_dir).unwrap();
        std::fs::create_dir_all(&src).unwrap();
        std::fs::write(src.join("a.txt"), b"hello").unwrap();

        let repo = repo_dir.to_string_lossy().to_string();
        let _guard = setup_repo(&repo);

        let request = BackupRequest {
            items: vec![
                BackupItem {
                    stage: "apk".to_string(),
                    label: "com.example:apk".to_string(),
                    tags: vec![
                        "com.example".to_string(),
                        "version:1.0(1)".to_string(),
                        "user:owner".to_string(),
                        "1700000000000".to_string(),
                    ],
                    sources: vec![src.to_string_lossy().to_string()],
                    as_path: Some("com.example".to_string()),
                    skip_reason: None,
                    description: None,
                    parent: None,
                    excludes: Vec::new(),
                    ignore_inode: false,
                },
                BackupItem {
                    stage: "icon".to_string(),
                    label: "com.example:icon".to_string(),
                    tags: vec!["com.example".to_string()],
                    sources: vec![src.join("a.txt").to_string_lossy().to_string()],
                    as_path: Some("icon.png".to_string()),
                    skip_reason: None,
                    description: None,
                    parent: None,
                    excludes: Vec::new(),
                    ignore_inode: false,
                },
            ],
            excludes: Vec::new(),
            batch: None,
        };

        let mut buffer = vec![0u8; 576 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).unwrap();

        let list = super::list_snapshots(&super::ListRequest {
            package_name: "com.example".to_string(),
        })
        .expect("查询应该成功");

        assert_eq!(list.len(), 2, "两个快照都应该被 label 前缀匹配到");

        // 前缀相同的另一个包名不能被误伤
        let other = super::list_snapshots(&super::ListRequest {
            package_name: "com.exam".to_string(),
        })
        .expect("查询应该成功");
        assert!(other.is_empty(), "com.exam 不应该匹配 com.example 的快照");

        let none = super::list_snapshots(&super::ListRequest {
            package_name: "com.other".to_string(),
        })
        .expect("查询应该成功");
        assert!(none.is_empty(), "别的包名不应该匹配到快照");

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn update_and_remove_description() {
        let base = std::env::temp_dir().join(format!("rustic-desc-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        let repo_dir = base.join("repo");
        let src = base.join("src");
        std::fs::create_dir_all(&repo_dir).unwrap();
        std::fs::create_dir_all(&src).unwrap();
        std::fs::write(src.join("a.txt"), b"hello").unwrap();

        let repo = repo_dir.to_string_lossy().to_string();
        let _guard = setup_repo(&repo);

        let item = |stage: &str| BackupItem {
            stage: stage.to_string(),
            label: format!("com.example:{stage}"),
            tags: vec![
                "com.example".to_string(),
                "version:1.0(1)".to_string(),
                "1700000000000".to_string(),
            ],
            sources: vec![src.to_string_lossy().to_string()],
            as_path: Some("com.example".to_string()),
            skip_reason: None,
            description: None,
            parent: None,
            excludes: Vec::new(),
            ignore_inode: false,
        };

        let request = BackupRequest {
            items: vec![item("apk"), item("user")],
            excludes: Vec::new(),
            batch: None,
        };

        let mut buffer = vec![0u8; 576 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).unwrap();

        let ids: Vec<String> = super::list_snapshots(&super::ListRequest {
            package_name: "com.example".to_string(),
        })
        .unwrap()
        .into_iter()
        .map(|s| s.id)
        .collect();
        assert_eq!(ids.len(), 2);

        // 写入备注
        super::update_descriptions(&super::UpdateDescriptionRequest {
            ids: ids.clone(),
            description: Some("更新前备份".to_string()),
        })
        .expect("写备注应该成功");

        let after: Vec<_> = super::list_snapshots(&super::ListRequest {
            package_name: "com.example".to_string(),
        })
        .unwrap();
        assert_eq!(after.len(), 2, "改备注后数量不应该变");
        assert!(
            after.iter().all(|s| s.description.as_deref() == Some("更新前备份")),
            "两个快照都应该带上备注"
        );

        // 移除备注
        let new_ids: Vec<String> = after.iter().map(|s| s.id.clone()).collect();
        super::update_descriptions(&super::UpdateDescriptionRequest {
            ids: new_ids,
            description: None,
        })
        .expect("移除备注应该成功");

        let cleared: Vec<_> = super::list_snapshots(&super::ListRequest {
            package_name: "com.example".to_string(),
        })
        .unwrap();
        assert_eq!(cleared.len(), 2);
        assert!(cleared.iter().all(|s| s.description.is_none()), "备注应该被清空");

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn restore_snapshot_to_dest() {
        let base = std::env::temp_dir().join(format!("rustic-restore-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        let repo_dir = base.join("repo");
        let src = base.join("src");
        let out = base.join("out");
        std::fs::create_dir_all(&repo_dir).unwrap();
        std::fs::create_dir_all(src.join("sub")).unwrap();
        std::fs::write(src.join("a.txt"), b"hello world").unwrap();
        std::fs::write(src.join("sub").join("b.bin"), b"0123456789").unwrap();

        let repo = repo_dir.to_string_lossy().to_string();
        let _guard = setup_repo(&repo);

        let request = BackupRequest {
            items: vec![BackupItem {
                stage: "user".to_string(),
                label: "com.example:user".to_string(),
                tags: vec!["com.example".to_string()],
                sources: vec![src.to_string_lossy().to_string()],
                as_path: Some("com.example".to_string()),
                skip_reason: None,
                description: None,
                parent: None,
                excludes: Vec::new(),
                ignore_inode: false,
            }],
            excludes: Vec::new(),
            batch: None,
        };

        let mut buffer = vec![0u8; 576 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).unwrap();

        let snap = super::list_snapshots(&super::ListRequest {
            package_name: "com.example".to_string(),
        })
        .unwrap()
        .into_iter()
        .next()
        .unwrap();

        // 目标目录里放个快照没有的文件，恢复后应该被删掉
        std::fs::create_dir_all(out.join("com.example")).unwrap();
        std::fs::write(out.join("com.example").join("stale.txt"), b"stale").unwrap();

        super::restore_snapshots(&super::RestoreRequest {
            items: vec![super::RestoreItem {
                id: snap.id,
                dest: out.to_string_lossy().to_string(),
                delete: true,
                stage: "user".to_string(),
            }],
        })
        .expect("恢复应该成功");

        assert_eq!(
            std::fs::read(out.join("com.example").join("a.txt")).unwrap(),
            b"hello world"
        );
        assert_eq!(
            std::fs::read(out.join("com.example").join("sub").join("b.bin")).unwrap(),
            b"0123456789"
        );
        assert!(
            !out.join("com.example").join("stale.txt").exists(),
            "delete 应该清掉快照里没有的文件"
        );

        let _ = std::fs::remove_dir_all(&base);
    }

    /// 恢复的字节进度：总量直接来自快照元数据（不用扫盘），写文件时按文件大小累加。
    #[test]
    fn restore_emits_byte_progress() {
        let base =
            std::env::temp_dir().join(format!("rustic-restore-progress-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        let repo_dir = base.join("repo");
        let src = base.join("src");
        let out = base.join("out");
        std::fs::create_dir_all(&repo_dir).unwrap();
        std::fs::create_dir_all(&src).unwrap();
        std::fs::create_dir_all(&out).unwrap();

        // 64 KiB 整块，断言时能精确对上
        std::fs::write(src.join("a.bin"), vec![7u8; 64 * 1024]).unwrap();

        let repo = repo_dir.to_string_lossy().to_string();
        let _guard = setup_repo(&repo);

        let request = BackupRequest {
            items: vec![BackupItem {
                stage: "user".to_string(),
                label: "com.example:user".to_string(),
                tags: vec!["com.example".to_string()],
                sources: vec![src.to_string_lossy().to_string()],
                as_path: Some("com.example".to_string()),
                skip_reason: None,
                description: None,
                parent: None,
                excludes: Vec::new(),
                ignore_inode: false,
            }],
            excludes: Vec::new(),
            batch: None,
        };

        let mut backup_buffer = vec![0u8; PROGRESS_OFFSET + 64 * 1024];
        let backup_progress = Arc::new(ProgressCtx::new(
            backup_buffer.as_mut_ptr(),
            backup_buffer.len(),
        ));
        backup_repo(&request, backup_progress).expect("backup 应该成功");

        let snap = super::list_snapshots(&super::ListRequest {
            package_name: "com.example".to_string(),
        })
        .unwrap()
        .into_iter()
        .next()
        .unwrap();

        let mut buffer = vec![0u8; PROGRESS_OFFSET + 64 * 1024];
        let capacity = buffer.len();
        let ptr = buffer.as_mut_ptr();
        set_current_stage("");
        set_active_progress(Arc::new(ProgressCtx::new(ptr, capacity)));

        let result = super::restore_snapshots(&super::RestoreRequest {
            items: vec![super::RestoreItem {
                id: snap.id,
                dest: out.to_string_lossy().to_string(),
                delete: false,
                stage: "user".to_string(),
            }],
        });
        clear_active_progress();
        result.expect("恢复应该成功");

        let mut totals = Vec::new();
        for slot in 0..PROGRESS_SLOT_COUNT {
            let offset = PROGRESS_OFFSET + PROGRESS_COUNTER_SIZE + slot * PROGRESS_SLOT_SIZE;
            let Ok(bytes) = read_frame_at(ptr, capacity, offset) else {
                continue;
            };
            let Ok(text) = String::from_utf8(bytes) else {
                continue;
            };
            let Ok(value) = serde_json::from_str::<serde_json::Value>(&text) else {
                continue;
            };
            if value["state"] == "progress" && value["stage"] == "user" {
                totals.push(value["total"].as_u64().unwrap_or(0));
            }
        }

        assert!(!totals.is_empty(), "没有收到恢复的字节进度帧");
        assert!(
            totals.iter().any(|&total| total == 64 * 1024),
            "总量应该等于文件大小：{totals:?}"
        );

        let _ = std::fs::remove_dir_all(&base);
    }

    /// tag 用 `|` 拼成一个整体，写入读回顺序不变（多个 tag 会被 BTreeSet 排序）。
    #[test]
    fn single_tag_survives_roundtrip() {
        let base = std::env::temp_dir().join(format!("rustic-tags-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);

        let repo_dir = base.join("repo");
        let src = base.join("src");
        std::fs::create_dir_all(&repo_dir).unwrap();
        std::fs::create_dir_all(&src).unwrap();
        std::fs::write(src.join("a.txt"), b"hi").unwrap();

        let repo = repo_dir.to_string_lossy().to_string();
        let _guard = setup_repo(&repo);

        let request = BackupRequest {
            items: vec![BackupItem {
                stage: "apk".to_string(),
                label: "com.example:apk".to_string(),
                tags: vec!["酷安|1.10.2(2410151)|机主|1792520620636".to_string()],
                sources: vec![src.to_string_lossy().to_string()],
                as_path: Some("base.apk".to_string()),
                skip_reason: None,
                description: None,
                parent: None,
                excludes: Vec::new(),
                ignore_inode: false,
            }],
            excludes: Vec::new(),
            batch: None,
        };

        let mut buffer = vec![0u8; 576 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).unwrap();

        let snap = super::list_snapshots(&super::ListRequest {
            package_name: "com.example".to_string(),
        })
        .unwrap()
        .into_iter()
        .next()
        .unwrap();

        assert_eq!(
            snap.tags.to_string(),
            "酷安|1.10.2(2410151)|机主|1792520620636",
            "单个 tag 里的顺序不会被 rustic 打乱"
        );

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn frame_roundtrip() {
        let mut buffer = vec![0u8; 1024];
        let ptr = buffer.as_mut_ptr();
        let capacity = buffer.len();

        let json = br#"{"package_name":"com.example"}"#;
        let header = (json.len() as i32).to_be_bytes();
        unsafe {
            std::ptr::copy_nonoverlapping(header.as_ptr(), ptr, 4);
            std::ptr::copy_nonoverlapping(json.as_ptr(), ptr.add(4), json.len());
        }

        let bytes = read_frame_at(ptr, capacity, 0).expect("读取帧失败");
        assert_eq!(bytes, json);
    }

    /// 进度帧是**跨进程协议**：字段名必须和 Kotlin 侧 `BackupProgress` 对得上，
    /// 否则「有几个文件没读到」这条信息会静默丢掉，界面又变回「备份成功」。
    #[test]
    fn progress_frame_carries_error_count() {
        let mut buffer = vec![0u8; PROGRESS_OFFSET + 64 * 1024];
        let ptr = buffer.as_mut_ptr();
        let capacity = buffer.len();
        let progress = ProgressCtx::new(ptr, capacity);

        progress.emit_done("data", Some(12), 3);
        let first = read_frame_at(ptr, capacity, PROGRESS_OFFSET + PROGRESS_COUNTER_SIZE)
            .expect("读取进度帧失败");
        let first = String::from_utf8(first).unwrap();
        assert_eq!(
            first,
            r#"{"stage":"data","state":"done","files":12,"errors":3}"#
        );

        // 0 不写这个字段：没出错时帧和以前完全一样，老客户端也不受影响
        progress.emit_done("apk", Some(1), 0);
        let second = read_frame_at(
            ptr,
            capacity,
            PROGRESS_OFFSET + PROGRESS_COUNTER_SIZE + PROGRESS_SLOT_SIZE,
        )
        .expect("读取进度帧失败");
        let second = String::from_utf8(second).unwrap();
        assert_eq!(second, r#"{"stage":"apk","state":"done","files":1}"#);
    }

    /// 字节进度帧同样是跨进程协议：Kotlin 侧 `BackupProgress` 的 `total` / `current`
    /// 必须和这里对得上，否则进度条永远停在不确定态。
    #[test]
    fn bytes_progress_frame_carries_total_and_current() {
        // 进度端是全局的，和别的测试同时写会互相覆盖
        let _guard = TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());

        let mut buffer = vec![0u8; PROGRESS_OFFSET + 64 * 1024];
        let capacity = buffer.len();
        let ptr = buffer.as_mut_ptr();

        set_current_stage("data");
        set_active_progress(Arc::new(ProgressCtx::new(ptr, capacity)));

        let progress = BytesProgress::default();
        progress.set_length(1000);
        progress.inc(250);
        // inc 有 200ms 节流，finish 是强制写，正好把累计值带出来
        progress.finish();
        clear_active_progress();

        // 别的测试可能同时在写这块缓冲，直接扫全部槽位找自己那条
        let mut found = None;
        for slot in 0..PROGRESS_SLOT_COUNT {
            let offset = PROGRESS_OFFSET + PROGRESS_COUNTER_SIZE + slot * PROGRESS_SLOT_SIZE;
            let Ok(bytes) = read_frame_at(ptr, capacity, offset) else {
                continue;
            };
            let Ok(text) = String::from_utf8(bytes) else {
                continue;
            };
            if text.contains(r#""state":"progress""#) && text.contains(r#""current":250"#) {
                found = Some(text);
                break;
            }
        }

        let text = found.expect("没有找到字节进度帧");
        assert!(text.contains(r#""stage":"data""#), "{text}");
        assert!(text.contains(r#""current":250"#), "{text}");
    }
}
