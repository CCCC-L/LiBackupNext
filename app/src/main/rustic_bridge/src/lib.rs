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
//! 请求：`{"path":"/storage/emulated/0/xxx","password":"..."}`
//! 响应：`{"ok":true}` 或 `{"ok":false,"error":"..."}`
//! 进度：`{"stage":"apk","done":123,"total":456,"finished":false}`

use std::fmt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use jni::objects::{JByteBuffer, JObject, JString};
use jni::sys::jint;
use jni::EnvUnowned;
use rustic_backend::LocalBackend;
use rustic_core::{
    BackupOptions, ConfigOptions, Credentials, KeyOptions, LsOptions, PathList, Repository,
    RepositoryBackends, RepositoryOptions, SnapshotOptions, StringList,
};
use rustic_core::repofile::SnapshotFile;
use rustic_core::repofile::NodeType;
use rustic_core::{LimitOption, PruneOptions};
use serde::{Deserialize, Serialize};

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
struct InitRequest {
    path: String,
    password: String,
}

#[derive(Deserialize)]
struct BackupRequest {
    path: String,
    password: String,
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
    ///
    /// 不传的话 rustic 会调 `SnapshotFile::latest()`，也就是**把仓库里所有快照列一遍**
    /// 才能挑出 parent —— 每个应用每种类型各一次，批量备份时这是很大一笔开销。
    #[serde(default)]
    parent: Option<String>,
}

#[derive(Deserialize)]
struct ListRequest {
    path: String,
    password: String,
    /// 包名；留空表示列出全部快照
    #[serde(default)]
    package_name: String,
}

#[derive(Deserialize)]
struct UpdateDescriptionRequest {
    path: String,
    password: String,
    ids: Vec<String>,
    #[serde(default)]
    description: Option<String>,
}

#[derive(Deserialize)]
struct DeleteRequest {
    path: String,
    password: String,
    ids: Vec<String>,
}

#[derive(Deserialize)]
struct PruneRequest {
    path: String,
    password: String,
}

#[derive(Deserialize)]
struct RestoreItem {
    id: String,
    dest: String,
    /// 恢复后删除目标目录里快照没有的文件
    #[serde(default)]
    delete: bool,
}

#[derive(Deserialize)]
struct RestoreRequest {
    path: String,
    password: String,
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
}

#[derive(Serialize)]
struct BridgeResponse {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

impl BridgeResponse {
    fn ok() -> Self {
        Self {
            ok: true,
            error: None,
        }
    }

    fn err(message: impl Into<String>) -> Self {
        Self {
            ok: false,
            error: Some(message.into()),
        }
    }
}

// ==================== 初始化 ====================

/// `RusticService.nativeInitRepo(buffer: ByteBuffer): Int`
///
/// 返回 0 成功，1 业务失败，2 共享内存不可用。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativeInitRepo<'local>(
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

fn handle_init(ptr: *mut u8, capacity: usize) -> jint {
    let request = match read_frame_at(ptr, capacity, 0)
        .and_then(|bytes| serde_json::from_slice::<InitRequest>(&bytes).map_err(|e| e.to_string()))
    {
        Ok(request) => request,
        Err(message) => {
            write_response(ptr, capacity, &BridgeResponse::err(message));
            return 1;
        }
    };

    match init_repo(&request.path, &request.password) {
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

fn init_repo(path: &str, password: &str) -> Result<(), String> {
    // 换仓库 / 改密码都从这里走，先丢掉旧实例
    clear_repo_cache();
    invalidate_indexed_repo();
    invalidate_backup_repo();

    let dir = std::path::Path::new(path);
    if !dir.exists() {
        return Err(format!("仓库目录不存在：{path}"));
    }
    if !dir.is_dir() {
        return Err(format!("所选路径不是目录：{path}"));
    }

    let backend = LocalBackend::new(path, Vec::<(String, String)>::new())
        .map_err(|e| format!("无法打开仓库目录：{}", describe_error(&e)))?;

    let backends = RepositoryBackends::new(Arc::new(backend), None);

    let options = repo_options();

    let repository = Repository::new(&options, &backends)
        .map_err(|e| format!("无法访问仓库目录：{}", describe_error(&e)))?;

    let credentials = Credentials::password(password);

    // 目录里已经有 config 说明是现成的 rustic 仓库，此时只校验密码
    if dir.join("config").is_file() {
        let opened = repository.open(&credentials).map_err(|e| {
            format!(
                "该目录已经是 rustic 仓库，但密码校验失败：{}",
                describe_error(&e)
            )
        })?;
        cache_repo(path, password, opened);
        return Ok(());
    }

    let created = repository
        .init(
            &credentials,
            &KeyOptions::default(),
            &ConfigOptions::default(),
        )
        .map_err(|e| format!("创建仓库失败：{}", describe_error(&e)))?;
    cache_repo(path, password, created);

    Ok(())
}

// ==================== 查询快照 ====================

/// `RusticService.nativeSetCacheDir(path: String): Int`
///
/// 传空串表示关闭缓存。root 服务启动时调一次。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativeSetCacheDir<'local>(
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

/// `RusticService.nativeListSnapshots(buffer: ByteBuffer): Int`
///
/// 请求：`{"path":"...","password":"...","package_name":"包名"}`
/// 响应：`{"ok":true,"snapshots":[{"id","label","tags","time","description"}]}`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativeListSnapshots<'local>(
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
        .and_then(|bytes| serde_json::from_slice::<ListRequest>(&bytes).map_err(|e| e.to_string()))
    {
        Ok(request) => request,
        Err(message) => {
            write_list_response(ptr, capacity, ListResponse { ok: false, snapshots: Vec::new(), error: Some(message) });
            return 1;
        }
    };

    match list_snapshots(&request) {
        Ok(mut snapshots) => {
            // 按实际容量裁剪：描述可能很长，500 条不一定塞得下
            loop {
                let response = ListResponse {
                    ok: true,
                    snapshots: snapshots.clone(),
                    error: None,
                };

                match serde_json::to_vec(&response) {
                    Ok(json) if json.len() + 4 <= capacity => {
                        write_frame_at(ptr, capacity, 0, &json);
                        break;
                    }
                    Ok(_) if snapshots.is_empty() => {
                        write_list_response(ptr, capacity, ListResponse {
                            ok: true,
                            snapshots: Vec::new(),
                            error: None,
                        });
                        break;
                    }
                    Ok(_) => {
                        snapshots.pop();
                    }
                    Err(_) => {
                        write_list_response(ptr, capacity, ListResponse {
                            ok: false,
                            snapshots: Vec::new(),
                            error: Some("序列化快照失败".to_string()),
                        });
                        break;
                    }
                }
            }
            0
        }
        Err(message) => {
            write_list_response(ptr, capacity, ListResponse { ok: false, snapshots: Vec::new(), error: Some(message) });
            1
        }
    }
}

fn list_snapshots(request: &ListRequest) -> Result<Vec<SnapshotInfo>, String> {
    let repository = open_repo_cached(&request.path, &request.password)?;

    // label 形如 `<包名>:<类型>`；包名非空时按前缀过滤（纯查找，不做语义处理）
    let prefix = format!("{}:", request.package_name);
    let filter_by_package = !request.package_name.is_empty();

    // 带条件的查询：rustic 会在读取时就用 predicate 过滤，不用先全量拿到内存里
    let mut snapshots: Vec<SnapshotInfo> = repository
        .get_matching_snapshots(|snap| !filter_by_package || snap.label.starts_with(&prefix))
        .map_err(|e| format!("读取快照失败：{}", describe_error(&e)))?
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

    let dir = CACHE_DIR.lock().ok().and_then(|guard| guard.clone());
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

/// 打开仓库（不需要索引，列表/改备注都够用）。
fn open_repo(path: &str, password: &str) -> Result<rustic_core::Repository<rustic_core::OpenStatus>, String> {
    let backend = LocalBackend::new(path, Vec::<(String, String)>::new())
        .map_err(|e| format!("无法打开仓库目录：{}", describe_error(&e)))?;

    let backends = RepositoryBackends::new(Arc::new(backend), None);

    let options = repo_options();

    Repository::new(&options, &backends)
        .map_err(|e| format!("无法访问仓库：{}", describe_error(&e)))?
        .open(&Credentials::password(password))
        .map_err(|e| format!("打开仓库失败（密码是否正确？）：{}", describe_error(&e)))
}

/// 缓存的仓库实例：同一时间只保留一个（应用只会打开一个仓库）。
///
/// `Repository<OpenStatus>` 里只有 config、派生好的密钥和 backend，
/// **不含快照列表和索引**，所以仓库内容变了也不会读到旧数据，
/// 每次查询照旧重新列 `snapshots/` 目录。
struct CachedRepo {
    /// 仓库标识：完整地址 + 全部凭据（现在只有「路径 + 密码」）。
    ///
    /// 将来接远程仓库（S3 / SFTP 等）时，要把 URL、access key 之类的
    /// 凭据一起拼进 key，任何一个变了都必须重新打开。
    key: String,
    repo: Arc<Repository<rustic_core::OpenStatus>>,
    last_used: Instant,
}

/// 空闲超过这个时间就丢掉缓存，避免长期占着远程连接（本地仓库只是省一次 scrypt）。
const REPO_CACHE_IDLE: Duration = Duration::from_secs(600);

static OPEN_REPO: Mutex<Option<CachedRepo>> = Mutex::new(None);

fn repo_key(path: &str, password: &str) -> String {
    // 用 \0 分隔，避免 path 里带分隔符造成误判
    format!("{path}\0{password}")
}

fn cache_repo(path: &str, password: &str, repo: Repository<rustic_core::OpenStatus>) {
    if let Ok(mut guard) = OPEN_REPO.lock() {
        *guard = Some(CachedRepo {
            key: repo_key(path, password),
            repo: Arc::new(repo),
            last_used: Instant::now(),
        });
    }
}

fn clear_repo_cache() {
    if let Ok(mut guard) = OPEN_REPO.lock() {
        *guard = None;
    }
}

/// 取仓库：命中缓存就直接复用（省掉 scrypt 派生），否则打开并缓存。
fn open_repo_cached(
    path: &str,
    password: &str,
) -> Result<Arc<Repository<rustic_core::OpenStatus>>, String> {
    let key = repo_key(path, password);

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

    let repo = Arc::new(open_repo(path, password)?);
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
fn open_indexed_repo_cached(
    path: &str,
    password: &str,
) -> Result<Arc<Repository<rustic_core::IndexedFullStatus>>, String> {
    let key = repo_key(path, password);

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
        open_repo(path, password)?
            .to_indexed()
            .map_err(|e| format!("读取仓库索引失败：{}", describe_error(&e)))?,
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
    path: &str,
    password: &str,
    batch: Option<&str>,
) -> Result<Arc<Repository<rustic_core::IndexedIdsStatus>>, String> {
    // 没有批次标识就不缓存：单次备份本来也只会用一次
    let key = match batch {
        Some(batch) if !batch.is_empty() => format!("{}\0batch\0{batch}", repo_key(path, password)),
        _ => {
            let backend = LocalBackend::new(path, Vec::<(String, String)>::new())
                .map_err(|e| format!("无法打开仓库目录：{}", describe_error(&e)))?;
            let backends = RepositoryBackends::new(Arc::new(backend), None);
            let repository = Repository::new(&repo_options(), &backends)
                .map_err(|e| format!("无法访问仓库：{}", describe_error(&e)))?;
            return Ok(Arc::new(
                repository
                    .open(&Credentials::password(password))
                    .map_err(|e| format!("打开仓库失败（密码是否正确？）：{}", describe_error(&e)))?
                    .to_indexed_ids()
                    .map_err(|e| format!("读取仓库索引失败：{}", describe_error(&e)))?,
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

    let backend = LocalBackend::new(path, Vec::<(String, String)>::new())
        .map_err(|e| format!("无法打开仓库目录：{}", describe_error(&e)))?;
    let backends = RepositoryBackends::new(Arc::new(backend), None);
    let repository = Repository::new(&repo_options(), &backends)
        .map_err(|e| format!("无法访问仓库：{}", describe_error(&e)))?;

    let repo = Arc::new(
        repository
            .open(&Credentials::password(password))
            .map_err(|e| format!("打开仓库失败（密码是否正确？）：{}", describe_error(&e)))?
            .to_indexed_ids()
            .map_err(|e| format!("读取仓库索引失败：{}", describe_error(&e)))?,
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

// ==================== 备份概况 ====================

#[derive(Deserialize)]
struct OverviewRequest {
    path: String,
    password: String,
}

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

fn build_overview(request: &OverviewRequest) -> Result<Vec<PackageOverview>, String> {
    let repository = open_repo_cached(&request.path, &request.password)?;

    let snapshots = repository
        .get_matching_snapshots(|_| true)
        .map_err(|e| format!("读取快照失败：{}", describe_error(&e)))?;

    // 包名 -> 该包的所有快照（图标快照不算一次备份）
    let mut by_package: std::collections::BTreeMap<String, Vec<&SnapshotFile>> =
        std::collections::BTreeMap::new();

    // 已经有图标快照的包：顺带统计出来，省得备份时每个应用再单独查一次
    let mut icon_packages: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();

    for snapshot in &snapshots {
        let Some((package, kind)) = snapshot.label.rsplit_once(':') else {
            continue;
        };
        if package.is_empty() {
            continue;
        }
        if kind == "icon" {
            icon_packages.insert(package.to_string());
            continue;
        }
        by_package
            .entry(package.to_string())
            .or_default()
            .push(snapshot);
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

/// `RusticService.nativeOverview(buffer: ByteBuffer): Int`
///
/// 一次拿到所有包的备份概况：每个包一条，只带最近一次备份的快照。
/// 相比「拉全量快照列表」，响应大小只跟应用数量有关，跟备份次数无关。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativeOverview<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        let request = match read_frame_at(ptr, capacity, 0).and_then(|bytes| {
            serde_json::from_slice::<OverviewRequest>(&bytes).map_err(|e| e.to_string())
        }) {
            Ok(request) => request,
            Err(message) => {
                write_overview_response(
                    ptr,
                    capacity,
                    OverviewResponse {
                        ok: false,
                        packages: Vec::new(),
                        error: Some(message),
                    },
                );
                return 1;
            }
        };

        match build_overview(&request) {
            Ok(packages) => {
                write_overview_response(
                    ptr,
                    capacity,
                    OverviewResponse {
                        ok: true,
                        packages,
                        error: None,
                    },
                );
                0
            }
            Err(message) => {
                write_overview_response(
                    ptr,
                    capacity,
                    OverviewResponse {
                        ok: false,
                        packages: Vec::new(),
                        error: Some(message),
                    },
                );
                1
            }
        }
    })
}

/// `RusticService.nativeUpdateDescription(buffer: ByteBuffer): Int`
///
/// 请求：`{"path","password","ids":["..."],"description":"..."|null}`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativeUpdateDescription<'local>(
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
        serde_json::from_slice::<UpdateDescriptionRequest>(&bytes).map_err(|e| e.to_string())
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

fn update_descriptions(request: &UpdateDescriptionRequest) -> Result<(), String> {
    if request.ids.is_empty() {
        return Ok(());
    }

    let repository = open_repo_cached(&request.path, &request.password)?;

    let mut snapshots = repository
        .get_snapshots(&request.ids)
        .map_err(|e| format!("读取快照失败：{}", describe_error(&e)))?;

    if snapshots.is_empty() {
        return Err("没有找到对应的快照".to_string());
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
        .map_err(|e| format!("保存快照失败：{}", describe_error(&e)))?;

    let result = repository
        .delete_snapshots(&old_ids)
        .map_err(|e| format!("删除旧快照失败：{}", describe_error(&e)));

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

/// `RusticService.nativeDeleteSnapshots(buffer: ByteBuffer): Int`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativeDeleteSnapshots<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        let request = match read_frame_at(ptr, capacity, 0).and_then(|bytes| {
            serde_json::from_slice::<DeleteRequest>(&bytes).map_err(|e| e.to_string())
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

fn delete_snapshots(request: &DeleteRequest) -> Result<(), String> {
    if request.ids.is_empty() {
        return Ok(());
    }

    // 快照删掉后索引里的引用也变了，缓存必须作废
    invalidate_indexed_repo();

    let repository = open_repo_cached(&request.path, &request.password)?;

    let ids = repository
        .get_snapshots(&request.ids)
        .map_err(|e| format!("读取快照失败：{}", describe_error(&e)))?
        .into_iter()
        .map(|snap| snap.id)
        .collect::<Vec<_>>();

    let result = repository
        .delete_snapshots(&ids)
        .map_err(|e| format!("删除快照失败：{}", describe_error(&e)));

    // 结束时再失效一次：删除期间如果有 listSnapshots 把「删之前的索引」缓存进来，
    // 之后 10 分钟内都会看到已经删掉的快照
    invalidate_indexed_repo();
    result
}

// ==================== 清理孤儿图标 ====================

#[derive(Deserialize)]
struct CleanupRequest {
    path: String,
    password: String,
}

#[derive(Serialize)]
struct CleanupResponse {
    ok: bool,
    removed: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

/// 删掉「备份已经清空、但 `包名:icon` 还留着」的图标快照。
///
/// 图标快照不属于任何一次备份（它没有时间戳分组），所以按备份删的时候碰不到它；
/// 应用的所有备份都删光之后，这个图标就变成了仓库里的垃圾，需要单独回收。
fn clean_orphan_icons(request: &CleanupRequest) -> Result<u32, String> {
    let repository = open_repo_cached(&request.path, &request.password)?;

    let snapshots = repository
        .get_matching_snapshots(|_| true)
        .map_err(|e| format!("读取快照失败：{}", describe_error(&e)))?;

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
        .map_err(|e| format!("删除孤立图标失败：{}", describe_error(&e)))?;

    invalidate_indexed_repo();
    Ok(removed)
}

fn write_cleanup_response(ptr: *mut u8, capacity: usize, response: CleanupResponse) {
    if let Ok(json) = serde_json::to_vec(&response) {
        write_frame_at(ptr, capacity, 0, &json);
    }
}

/// `RusticService.nativeCleanOrphanIcons(buffer: ByteBuffer): Int`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativeCleanOrphanIcons<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        let request = match read_frame_at(ptr, capacity, 0).and_then(|bytes| {
            serde_json::from_slice::<CleanupRequest>(&bytes).map_err(|e| e.to_string())
        }) {
            Ok(request) => request,
            Err(message) => {
                write_cleanup_response(
                    ptr,
                    capacity,
                    CleanupResponse {
                        ok: false,
                        removed: 0,
                        error: Some(message),
                    },
                );
                return 1;
            }
        };

        match clean_orphan_icons(&request) {
            Ok(removed) => {
                write_cleanup_response(
                    ptr,
                    capacity,
                    CleanupResponse {
                        ok: true,
                        removed,
                        error: None,
                    },
                );
                0
            }
            Err(message) => {
                write_cleanup_response(
                    ptr,
                    capacity,
                    CleanupResponse {
                        ok: false,
                        removed: 0,
                        error: Some(message),
                    },
                );
                1
            }
        }
    })
}

/// `RusticService.nativePrune(buffer: ByteBuffer): Int`
///
/// 删除快照后回收空间：rustic 删快照只是删引用，数据要 prune 才真正释放。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativePrune<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        let request = match read_frame_at(ptr, capacity, 0)
            .and_then(|bytes| serde_json::from_slice::<PruneRequest>(&bytes).map_err(|e| e.to_string()))
        {
            Ok(request) => request,
            Err(message) => {
                write_response(ptr, capacity, &BridgeResponse::err(message));
                return 1;
            }
        };

        match prune_repo(&request) {
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
    // 立刻回收：不标记等待，也不保留 23 小时
    options.instant_delete = true;
    options.keep_delete = jiff::Span::new();
    options.fast_repack = true;
    options.max_repack = LimitOption::Unlimited;
    options.max_unused = LimitOption::Percentage(0);
    options
}

fn prune_repo(request: &PruneRequest) -> Result<(), String> {
    // 整理会重写 pack 和索引，缓存必须作废
    invalidate_indexed_repo();
    invalidate_backup_repo();

    let repository = open_indexed_repo_cached(&request.path, &request.password)?;

    let options = prune_options();

    let plan = repository
        .prune_plan(&options)
        .map_err(|e| format!("计算回收计划失败：{}", describe_error(&e)))?;

    let result = repository
        .prune(&options, plan)
        .map_err(|e| format!("回收空间失败：{}", describe_error(&e)));

    // 整理重写了 pack 和索引，收尾再失效一次
    invalidate_indexed_repo();
    invalidate_backup_repo();
    result
}

/// 只算不删：估算这次整理能回收多少字节。
///
/// 算法：整包删除的字节 + 重打包时被丢弃的未使用字节 + 无引用的包文件。
fn prune_estimate(request: &PruneRequest) -> Result<u64, String> {
    // 只算不删，可以放心复用缓存（估算和真正整理之间通常只隔几秒）
    let repository = open_indexed_repo_cached(&request.path, &request.password)?;

    let options = prune_options();
    let plan = repository
        .prune_plan(&options)
        .map_err(|e| format!("计算回收计划失败：{}", describe_error(&e)))?;

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
}

fn write_estimate(ptr: *mut u8, capacity: usize, result: Result<u64, String>) {
    let response = match result {
        Ok(bytes) => EstimateResponse {
            ok: true,
            bytes,
            error: None,
        },
        Err(message) => EstimateResponse {
            ok: false,
            bytes: 0,
            error: Some(message),
        },
    };

    if let Ok(bytes) = serde_json::to_vec(&response) {
        write_frame_at(ptr, capacity, 0, &bytes);
    }
}

/// `RusticService.nativePruneEstimate(buffer: ByteBuffer): Int`
///
/// 只算不删，返回 `{"ok":true,"bytes":可回收字节数}`。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativePruneEstimate<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        let request = match read_frame_at(ptr, capacity, 0)
            .and_then(|bytes| serde_json::from_slice::<PruneRequest>(&bytes).map_err(|e| e.to_string()))
        {
            Ok(request) => request,
            Err(message) => {
                write_estimate(ptr, capacity, Err(message));
                return 1;
            }
        };

        match prune_estimate(&request) {
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

/// `RusticService.nativeRestore(buffer: ByteBuffer): Int`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativeRestore<'local>(
    mut env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    run_simple(env, buffer, |ptr, capacity| {
        let request = match read_frame_at(ptr, capacity, 0).and_then(|bytes| {
            serde_json::from_slice::<RestoreRequest>(&bytes).map_err(|e| e.to_string())
        }) {
            Ok(request) => request,
            Err(message) => {
                write_response(ptr, capacity, &BridgeResponse::err(message));
                return 1;
            }
        };

        match restore_snapshots(&request) {
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

fn restore_snapshots(request: &RestoreRequest) -> Result<(), String> {
    if request.items.is_empty() {
        return Ok(());
    }

    // 恢复一个应用会分 5 次进来（apk / user / user_de / data / permissions），
    // 每次都重开仓库 + 读索引的话，固定开销就有 3~4 秒，所以走缓存。
    let repository = open_indexed_repo_cached(&request.path, &request.password)?;

    for item in &request.items {
        let snapshot = repository
            .get_snapshots(&[item.id.as_str()])
            .map_err(|e| format!("读取快照失败：{}", describe_error(&e)))?
            .into_iter()
            .next()
            .ok_or_else(|| format!("找不到快照 {}", item.id))?;

        restore_snapshot(
            repository.as_ref(),
            &snapshot,
            std::path::Path::new(&item.dest),
            item.delete,
        )
        .map_err(|e| format!("恢复 {} 失败：{e}", item.id))?;
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
) -> Result<(), String> {
    use std::io::Write;

    lower_thread_priority_once();

    let open = repository
        .open_file(node)
        .map_err(|e| format!("读取文件失败：{}", describe_error(&e)))?;

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
                .map_err(|e| format!("读取数据块失败：{}", describe_error(&e)))?;
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

                handles.push(scope.spawn(move || -> Result<(), String> {
                    lower_thread_priority_once();

                    let mut offset = start;
                    while offset < end {
                        let len = RESTORE_CHUNK.min(end - offset);
                        let data = repository
                            .read_file_at(open, offset, len)
                            .map_err(|e| format!("读取数据块失败：{}", describe_error(&e)))?;
                        write_at(file, &data, offset as u64)
                            .map_err(|e| format!("{}: {e}", target.display()))?;
                        offset += len;
                    }
                    Ok(())
                }));
            }

            let mut failure: Option<String> = None;
            for handle in handles {
                match handle.join() {
                    Ok(Ok(())) => {}
                    Ok(Err(message)) => {
                        failure.get_or_insert(message);
                    }
                    Err(_) => {
                        failure.get_or_insert_with(|| "恢复线程异常退出".to_string());
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

/// 把快照内容展开到 dest（快照里的路径都是相对路径）。
///
/// 目录先建好，文件内容用固定线程池并行写、每块 1 MiB，避免大文件整个读进内存。
fn restore_snapshot<S: rustic_core::IndexedFull + Sync>(
    repository: &Repository<S>,
    snapshot: &SnapshotFile,
    dest: &std::path::Path,
    delete: bool,
) -> Result<(), String> {
    // 空路径就是快照根节点
    let root = repository
        .node_from_path(snapshot.tree, std::path::Path::new(""))
        .map_err(|e| format!("读取快照根节点失败：{}", describe_error(&e)))?;

    let streamer = repository
        .ls(&root, &LsOptions::default())
        .map_err(|e| format!("读取快照内容失败：{}", describe_error(&e)))?;

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
        let (path, node) = entry.map_err(|e| format!("读取快照条目失败：{}", describe_error(&e)))?;
        if path.as_os_str().is_empty() {
            continue;
        }

        written.insert(path.clone());
        let target = dest.join(&path);

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
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(restore_threads())
            .thread_name(|index| format!("restore-{index}"))
            .build()
            .map_err(|e| format!("创建恢复线程池失败：{e}"))?;

        pool.install(|| {
            use rayon::prelude::*;
            files
                .par_iter()
                .try_for_each(|(target, node)| restore_file(repository, node, target))
        })?;
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
) -> Result<(), String> {
    let mut roots: Vec<std::path::PathBuf> = Vec::new();
    for path in written {
        if let Some(first) = path.components().next() {
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
) -> Result<(), String> {
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

/// `RusticService.nativeBackup(buffer: ByteBuffer): Int`
///
/// 返回 0 成功，1 业务失败，2 共享内存不可用。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_RusticService_nativeBackup<'local>(
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
        .and_then(|bytes| serde_json::from_slice::<BackupRequest>(&bytes).map_err(|e| e.to_string()))
    {
        Ok(request) => request,
        Err(message) => {
            write_response(ptr, capacity, &BridgeResponse::err(message));
            return 1;
        }
    };

    let progress = Arc::new(ProgressCtx::new(ptr, capacity));

    match backup_repo(&request, progress.clone()) {
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

fn backup_repo(request: &BackupRequest, progress: Arc<ProgressCtx>) -> Result<(), String> {
    // 备份会写入新的 pack 和索引，恢复用的索引缓存必须作废
    invalidate_indexed_repo();

    // 这一段是「每个应用一次」的固定开销：scrypt 密钥派生 + 读一遍索引。
    // 同批次内复用，命中缓存就不用再付。
    let repository =
        open_backup_repo_cached(&request.path, &request.password, request.batch.as_deref())?;

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
) -> Result<(), String> {
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
                .unwrap_or_else(|| format!("路径不存在：{}", item.sources.join("、")));
            progress.emit_skipped(&item.stage, &reason);
            continue;
        }

        let mut snapshot_options = SnapshotOptions::default();
        snapshot_options.label = Some(item.label.clone());
        if !item.tags.is_empty() {
            let tags = item.tags.join(",");
            snapshot_options.tags = vec![tags
                .parse::<StringList>()
                .map_err(|e| format!("tag 非法 `{tags}`：{e}"))?];
        }

        snapshot_options.description.clone_from(&item.description);

        let snapshot = SnapshotFile::from_options(&snapshot_options)
            .map_err(|e| format!("构造快照失败：{}", describe_error(&e)))?;

        let mut backup_options = BackupOptions::default();
        backup_options.as_path = item.as_path.as_ref().map(PathBuf::from);
        // 排除规则由 Kotlin 侧传入（gitignore 语义：`!` 开头表示排除）
        backup_options.excludes.globs = request.excludes.clone();
        // 进度是按「步骤」显示的，不需要总字节数；关掉这一步能省掉一整遍目录扫描
        backup_options.no_scan = true;

        // 直接指定 parent：省掉 rustic 内部的「列全部快照找最新」这一遍扫描。
        // 客户端从备份概况里拿到同类型最新快照的 id 传过来；没传就退回默认行为。
        if let Some(parent) = &item.parent {
            backup_options.parent_opts.parents = vec![parent.clone()];
        }

        let paths: PathList = sources.into_iter().collect();

        let snapshot = repository
            .backup(&backup_options, &paths, snapshot)
            .map_err(|e| format!("备份 {} 失败：{}", item.stage, describe_error(&e)))?;

        let files = snapshot
            .summary
            .as_ref()
            .map(|summary| summary.total_files_processed);

        progress.emit_with_files(&item.stage, "done", files);
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

    /// 写入一条状态：`{"stage":"apk","state":"done","files":12,"reason":"..."}`
    fn emit(&self, stage: &str, state: &str) {
        self.write_event(stage, state, None, None);
    }

    fn emit_with_files(&self, stage: &str, state: &str, files: Option<u64>) {
        self.write_event(stage, state, files, None);
    }

    fn emit_skipped(&self, stage: &str, reason: &str) {
        self.write_event(stage, "skipped", None, Some(reason));
    }

    fn write_event(
        &self,
        stage: &str,
        state: &str,
        files: Option<u64>,
        reason: Option<&str>,
    ) {
        let _guard = self.write_lock.lock();
        let mut json = format!(
            r#"{{"stage":"{}","state":"{}""#,
            escape_json(stage),
            state
        );
        if let Some(files) = files {
            json.push_str(&format!(r#","files":{files}"#));
        }
        if let Some(reason) = reason {
            json.push_str(&format!(r#","reason":"{}""#, escape_json(reason)));
        }
        json.push('}');
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

// ==================== 帧读写 ====================

/// 读取指定偏移处的「大端 i32 长度 + JSON」帧。
fn read_frame_at(ptr: *mut u8, capacity: usize, offset: usize) -> Result<Vec<u8>, String> {
    if offset + 4 > capacity {
        return Err("共享内存太小".to_string());
    }

    unsafe {
        let header = std::slice::from_raw_parts(ptr.add(offset), 4);
        let length = i32::from_be_bytes([header[0], header[1], header[2], header[3]]);

        if length < 0 {
            return Err(format!("数据长度非法：{length}"));
        }

        let length = length as usize;
        if offset + 4 + length > capacity {
            return Err("数据超出共享内存".to_string());
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

/// rustic 的 Display 里混了文档链接、issue 模板等噪音，这里只保留错误本身。
fn describe_error(error: &rustic_core::RusticError) -> String {
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

    let out = out.trim().to_string();
    if out.is_empty() {
        text
    } else {
        out
    }
}

#[cfg(test)]
mod tests {
    use super::{backup_repo, init_repo, read_frame_at, BackupItem, BackupRequest, ProgressCtx};
    use std::sync::Arc;

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
        init_repo(&repo, "rustic").expect("init 应该成功");

        let request = BackupRequest {
            path: repo,
            password: "rustic".to_string(),
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
            }],
        };

        let mut buffer = vec![0u8; 128 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).expect("backup 应该成功");

        let snapshots = repo_dir.join("snapshots");
        let count = std::fs::read_dir(&snapshots).map(|d| d.count()).unwrap_or(0);
        assert!(count > 0, "没有生成快照");

        let _ = std::fs::remove_dir_all(&base);
    }

    #[test]
    fn init_creates_repository() {
        let dir = std::env::temp_dir().join(format!("rustic-bridge-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);

        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.to_string_lossy().to_string();
        init_repo(&path, "rustic").expect("init 应该成功");

        // 初始化后仓库目录里应该有 config 文件
        assert!(dir.join("config").exists(), "仓库缺少 config 文件");

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
        init_repo(&repo, "rustic").expect("init 应该成功");

        let request = BackupRequest {
            path: repo,
            password: "rustic".to_string(),
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
            }],
        };

        let mut buffer = vec![0u8; 128 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).expect("backup 应该成功");

        // 用仓库 API 读回快照，确认两个源目录的内容都进去了
        let backend = rustic_backend::LocalBackend::new(&request.path, Vec::<(String, String)>::new())
            .unwrap();
        let backends = rustic_core::RepositoryBackends::new(Arc::new(backend), None);
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
        init_repo(&repo, "rustic").unwrap();

        // 目录：as_path = 包名；文件：as_path = 文件名
        let request = BackupRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
            items: vec![
                BackupItem {
                    stage: "user".to_string(),
                    label: "pkg:user".to_string(),
                    tags: vec!["pkg".to_string()],
                    sources: vec![dir.to_string_lossy().to_string()],
                    as_path: Some("com.example".to_string()),
                    skip_reason: None,
                    description: None,
                },
                BackupItem {
                    stage: "apk".to_string(),
                    label: "pkg:apk".to_string(),
                    tags: vec!["pkg".to_string()],
                    sources: vec![dir.join("base.apk").to_string_lossy().to_string()],
                    as_path: Some("base.apk".to_string()),
                    skip_reason: None,
                    description: None,
                },
            ],
        };

        let mut buffer = vec![0u8; 128 * 1024];
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
        init_repo(&repo, "rustic").unwrap();

        let request = BackupRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
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
                },
                BackupItem {
                    stage: "icon".to_string(),
                    label: "com.example:icon".to_string(),
                    tags: vec!["com.example".to_string()],
                    sources: vec![src.join("a.txt").to_string_lossy().to_string()],
                    as_path: Some("icon.png".to_string()),
                    skip_reason: None,
                    description: None,
                },
            ],
        };

        let mut buffer = vec![0u8; 128 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).unwrap();

        let list = super::list_snapshots(&super::ListRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
            package_name: "com.example".to_string(),
        })
        .expect("查询应该成功");

        assert_eq!(list.len(), 2, "两个快照都应该被 label 前缀匹配到");

        // 前缀相同的另一个包名不能被误伤
        let other = super::list_snapshots(&super::ListRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
            package_name: "com.exam".to_string(),
        })
        .expect("查询应该成功");
        assert!(other.is_empty(), "com.exam 不应该匹配 com.example 的快照");

        let none = super::list_snapshots(&super::ListRequest {
            path: repo,
            password: "rustic".to_string(),
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
        init_repo(&repo, "rustic").unwrap();

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
        };

        let request = BackupRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
            items: vec![item("apk"), item("user")],
        };

        let mut buffer = vec![0u8; 128 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).unwrap();

        let ids: Vec<String> = super::list_snapshots(&super::ListRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
            package_name: "com.example".to_string(),
        })
        .unwrap()
        .into_iter()
        .map(|s| s.id)
        .collect();
        assert_eq!(ids.len(), 2);

        // 写入备注
        super::update_descriptions(&super::UpdateDescriptionRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
            ids: ids.clone(),
            description: Some("更新前备份".to_string()),
        })
        .expect("写备注应该成功");

        let after: Vec<_> = super::list_snapshots(&super::ListRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
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
            path: repo,
            password: "rustic".to_string(),
            ids: new_ids,
            description: None,
        })
        .expect("移除备注应该成功");

        let cleared: Vec<_> = super::list_snapshots(&super::ListRequest {
            path: base.join("repo").to_string_lossy().to_string(),
            password: "rustic".to_string(),
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
        init_repo(&repo, "rustic").unwrap();

        let request = BackupRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
            items: vec![BackupItem {
                stage: "user".to_string(),
                label: "com.example:user".to_string(),
                tags: vec!["com.example".to_string()],
                sources: vec![src.to_string_lossy().to_string()],
                as_path: Some("com.example".to_string()),
                skip_reason: None,
                description: None,
            }],
        };

        let mut buffer = vec![0u8; 128 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).unwrap();

        let snap = super::list_snapshots(&super::ListRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
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
            path: repo,
            password: "rustic".to_string(),
            items: vec![super::RestoreItem {
                id: snap.id,
                dest: out.to_string_lossy().to_string(),
                delete: true,
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
        init_repo(&repo, "rustic").unwrap();

        let request = BackupRequest {
            path: repo.clone(),
            password: "rustic".to_string(),
            items: vec![BackupItem {
                stage: "apk".to_string(),
                label: "com.example:apk".to_string(),
                tags: vec!["酷安|1.10.2(2410151)|机主|1792520620636".to_string()],
                sources: vec![src.to_string_lossy().to_string()],
                as_path: Some("base.apk".to_string()),
                skip_reason: None,
                description: None,
            }],
        };

        let mut buffer = vec![0u8; 128 * 1024];
        let progress = Arc::new(ProgressCtx::new(buffer.as_mut_ptr(), buffer.len()));
        backup_repo(&request, progress).unwrap();

        let snap = super::list_snapshots(&super::ListRequest {
            path: repo,
            password: "rustic".to_string(),
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

        let json = br#"{"path":"/tmp/x","password":"p"}"#;
        let header = (json.len() as i32).to_be_bytes();
        unsafe {
            std::ptr::copy_nonoverlapping(header.as_ptr(), ptr, 4);
            std::ptr::copy_nonoverlapping(json.as_ptr(), ptr.add(4), json.len());
        }

        let bytes = read_frame_at(ptr, capacity, 0).expect("读取帧失败");
        assert_eq!(bytes, json);
    }
}
