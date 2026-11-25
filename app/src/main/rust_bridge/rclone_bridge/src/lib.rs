//! rclone 的 JNI 桥接：独立 cdylib，不含任何 rustic / 快照代码。
//!
//! 只和 [RcloneServer] 交互，参数是一块 576KB 的 SharedMemory 直接缓冲区；
//! frame 0 是「大端 i32 长度 + UTF-8 JSON」，格式与 rustic_bridge 一致。
//! librclone.so（Go c-shared）由本库按请求里的绝对路径 dlopen，凭据只走
//! 内存里的 inline 配置，不落盘。

use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::io::Write;
use std::panic;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use jni::objects::{JByteBuffer, JObject, JString};
use jni::sys::jint;
use jni::EnvUnowned;
use serde::Deserialize;
use serde_json::{json, Value};

// ==================== native 崩溃留痕 ====================

static LOG_DIR: OnceLock<String> = OnceLock::new();

/// `RcloneServer.nativeSetLogDir(path: String): Int`
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RcloneServer_nativeSetLogDir<'local>(
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

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: *mut c_void, _reserved: *mut c_void) -> jint {
    install_panic_hook();
    0x0001_0006 // JNI_VERSION_1_6
}

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
            "[libackup-rclone] PANIC (process will abort)\ntime(ms): {stamp}\nthread: {thread}\nlocation: {location}\npayload: {payload}\n"
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

fn android_log_fatal(text: &str) {
    extern "C" {
        fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
    }
    if let (Ok(tag), Ok(fmt), Ok(msg)) = (
        CString::new("LiBackupRclone"),
        CString::new("%s"),
        CString::new(text),
    ) {
        unsafe {
            __android_log_print(6, tag.as_ptr(), fmt.as_ptr(), msg.as_ptr());
        }
    }
}

// ==================== 帧协议 ====================

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

fn write_frame_at(ptr: *mut u8, capacity: usize, offset: usize, bytes: &[u8]) {
    if offset + 4 > capacity {
        return;
    }
    let available = capacity - offset - 4;
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

fn write_json(ptr: *mut u8, capacity: usize, value: &Value) {
    if let Ok(bytes) = serde_json::to_vec(value) {
        write_frame_at(ptr, capacity, 0, &bytes);
    }
}

fn write_error(ptr: *mut u8, capacity: usize, message: &str) {
    write_json(ptr, capacity, &json!({ "ok": false, "error": message }));
}

/// 统一外壳：拿到直接缓冲区地址后交给具体 handler。
fn with_buffer<'local, F>(
    mut env: EnvUnowned<'local>,
    buffer: JByteBuffer<'local>,
    handler: F,
) -> jint
where
    F: FnOnce(*mut u8, usize) -> jint,
{
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

// ==================== 目录浏览 ====================

#[derive(Deserialize)]
struct ListDirsRequest {
    lib: String,
    fs: String,
    #[serde(default)]
    path: String,
    #[serde(default)]
    create: Option<String>,
}

/// 请求：{"lib":"...","fs":"{...}","path":"/","create":null,"fixup":"..."}
/// 响应：{"ok":true,"dirs":[...]} 或 {"ok":false,"error":"..."}
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RcloneServer_nativeListDirs<'local>(
    env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    with_buffer(env, buffer, handle_list_dirs)
}

fn handle_list_dirs(ptr: *mut u8, capacity: usize) -> jint {
    let request = match read_frame_at(ptr, capacity, 0)
        .and_then(|bytes| {
            serde_json::from_slice::<ListDirsRequest>(&bytes).map_err(|e| e.to_string())
        })
    {
        Ok(request) => request,
        Err(message) => {
            write_error(ptr, capacity, &message);
            return 1;
        }
    };
    let outcome = (|| -> Result<Vec<String>, String> {
        ensure_loaded(&request.lib)?;
        let (fs, insecure) = prepare_fs(&request.fs)?;
        list_dirs(&fs, &request.path, request.create.as_deref(), insecure)
    })();
    match outcome {
        Ok(dirs) => {
            write_json(ptr, capacity, &json!({ "ok": true, "dirs": dirs }));
            0
        }
        Err(message) => {
            write_error(ptr, capacity, &message);
            1
        }
    }
}

// ==================== 远程 restic 服务 ====================

#[derive(Deserialize)]
struct EnsureServeRequest {
    lib: String,
    fs: String,
    key: String,
}

/// 请求：{"lib":"...","fs":"{...}","key":"<location>|<fs>"}
/// 响应：{"ok":true,"url":"http://user:pass@127.0.0.1:port/"} 或错误
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RcloneServer_nativeEnsureServe<'local>(
    env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    with_buffer(env, buffer, handle_ensure_serve)
}

fn handle_ensure_serve(ptr: *mut u8, capacity: usize) -> jint {
    let request = match read_frame_at(ptr, capacity, 0)
        .and_then(|bytes| {
            serde_json::from_slice::<EnsureServeRequest>(&bytes).map_err(|e| e.to_string())
        })
    {
        Ok(request) => request,
        Err(message) => {
            write_error(ptr, capacity, &message);
            return 1;
        }
    };
    let outcome = (|| -> Result<String, String> {
        ensure_loaded(&request.lib)?;
        let (fs, insecure) = prepare_fs(&request.fs)?;
        remote_url(&fs, &request.key, insecure)
    })();
    match outcome {
        Ok(url) => {
            write_json(ptr, capacity, &json!({ "ok": true, "url": url }));
            0
        }
        Err(message) => {
            write_error(ptr, capacity, &message);
            1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RcloneServer_nativeStopServe<'local>(
    env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    with_buffer(env, buffer, |ptr, capacity| {
        stop_serve();
        write_json(ptr, capacity, &json!({ "ok": true }));
        0
    })
}

/// `RcloneServer.nativeTransferStats(buffer)`：读 rclone 的传输统计。
///
/// 响应：`{"ok":true,"bytes":N,"speed":S}` 或 `{"ok":false,"error":"..."}`
///
/// `core/stats` 是进程内累计值，这里不做减法，基线由读端自己减。
#[no_mangle]
pub extern "system" fn Java_com_cc_libackupnext_service_root_RcloneServer_nativeTransferStats<'local>(
    env: EnvUnowned<'local>,
    _this: JObject<'local>,
    buffer: JByteBuffer<'local>,
) -> jint {
    with_buffer(env, buffer, |ptr, capacity| match rpc("core/stats", &json!({})) {
        Ok(value) => {
            let bytes = value.get("bytes").and_then(Value::as_u64).unwrap_or(0);
            let speed = value.get("speed").and_then(Value::as_f64).unwrap_or(0.0);
            write_json(
                ptr,
                capacity,
                &json!({ "ok": true, "bytes": bytes, "speed": speed }),
            );
            0
        }
        Err(message) => {
            write_error(ptr, capacity, &message);
            1
        }
    })
}

// ==================== librclone FFI ====================
//
// librclone 的进程内 FFI 封装。
//
// `librclone.so` 由 :root 进程按 nativeLibraryDir 里的绝对路径 dlopen，
// 之后所有远程能力都通过 `RcloneRPC(method, json)` 在进程内调用，
// 不起子进程，凭据只走内存 inline 配置、不落盘。
// Go runtime 不能卸载，所以句柄故意不 dlclose。
//
// 负责三件事：
//   1. 加载 / 初始化库、把服务密码转成 rclone 要求的混淆形式；
//   2. 用 `serve/start type=restic` 起一个只监听 127.0.0.1 的仓库服务，
//      返回给 `RestBackend` 用的 URL，并管住它的生命周期；
//   3. 用 `operations/list` / `operations/mkdir` 给全屏目录选择器列目录。

#[repr(C)]
struct RpcResult {
    output: *mut c_char,
    status: c_int,
}

type InitializeFn = unsafe extern "C" fn();
type RpcFn = unsafe extern "C" fn(*const c_char, *const c_char) -> RpcResult;
type FreeStringFn = unsafe extern "C" fn(*mut c_char);
type ObscureFn = unsafe extern "C" fn(*const c_char) -> *mut c_char;

struct Api {
    rpc: RpcFn,
    free_string: FreeStringFn,
    obscure: ObscureFn,
}

static API: OnceLock<Api> = OnceLock::new();

fn dl_message() -> String {
    unsafe {
        let ptr = libc::dlerror();
        if ptr.is_null() {
            "未知错误".to_string()
        } else {
            CStr::from_ptr(ptr).to_string_lossy().into_owned()
        }
    }
}

unsafe fn symbol<T>(handle: *mut c_void, name: &str) -> Result<T, String> {
    let cname = CString::new(name).map_err(|e| format!("符号名无效：{e}"))?;
    let ptr = libc::dlsym(handle, cname.as_ptr());
    if ptr.is_null() {
        return Err(format!("librclone 缺少 {name}：{}", dl_message()));
    }
    Ok(std::mem::transmute_copy(&ptr))
}

/// 第一次用到远程能力时才加载；重复调用是空操作。
fn ensure_loaded(path: &str) -> Result<(), String> {
    if API.get().is_some() {
        return Ok(());
    }
    let cpath = CString::new(path).map_err(|e| format!("librclone 路径无效：{e}"))?;
    unsafe {
        let handle = libc::dlopen(cpath.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
        if handle.is_null() {
            return Err(format!("加载 librclone 失败：{}", dl_message()));
        }
        let initialize: InitializeFn = symbol(handle, "RcloneInitialize")?;
        let rpc: RpcFn = symbol(handle, "RcloneRPC")?;
        let free_string: FreeStringFn = symbol(handle, "RcloneFreeString")?;
        let obscure: ObscureFn = symbol(handle, "RcloneObscure")?;
        initialize();
        // 不 dlclose：Go 的 GC/后台线程不支持卸载，句柄随进程一起退出即可
        let _ = API.set(Api {
            rpc,
            free_string,
            obscure,
        });
    }
    Ok(())
}

/// 调一次 RC 方法；非 200 时把 rclone 返回的 error 原样带出来。
fn rpc(method: &str, input: &Value) -> Result<Value, String> {
    let api = API.get().ok_or_else(|| "librclone 尚未加载".to_string())?;
    let cmethod = CString::new(method).map_err(|e| e.to_string())?;
    let cinput = CString::new(input.to_string()).map_err(|e| e.to_string())?;
    unsafe {
        let raw = (api.rpc)(cmethod.as_ptr(), cinput.as_ptr());
        if raw.output.is_null() {
            return Err(format!("{method} 没有返回内容"));
        }
        let text = CStr::from_ptr(raw.output).to_string_lossy().into_owned();
        (api.free_string)(raw.output);
        let value: Value = serde_json::from_str(&text)
            .unwrap_or_else(|_| json!({ "error": text.trim().to_string() }));
        if raw.status != 200 {
            let message = value
                .get("error")
                .and_then(Value::as_str)
                .unwrap_or("远程服务调用失败");
            return Err(format!("{method} 失败：{message}"));
        }
        Ok(value)
    }
}

/// 带超时的 RC 调用：`serve/start` 的 NewFs 在远端不可达时能卡十几分钟，
/// 超时后主线程直接报错，后台线程跑完会自己停掉刚建好的 serve。
fn rpc_with_timeout(method: &str, input: &Value, timeout: Duration) -> Result<Value, String> {
    let (tx, rx) = mpsc::channel();
    let method_owned = method.to_string();
    let input_owned = input.clone();
    let abandoned = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&abandoned);

    std::thread::spawn(move || {
        let result = rpc(&method_owned, &input_owned);
        if flag.load(Ordering::SeqCst) {
            // 主线程已经不等了：它要是真建出了 serve，顺手停掉
            if let Ok(response) = &result {
                if let Some(id) = response.get("id").and_then(Value::as_str) {
                    let _ = rpc("serve/stop", &json!({ "id": id }));
                }
            }
            return;
        }
        let _ = tx.send(result);
    });

    match rx.recv_timeout(timeout) {
        Ok(result) => result,
        Err(_) => {
            abandoned.store(true, Ordering::SeqCst);
            Err(format!("启动远程服务超时（{}s）", timeout.as_secs()))
        }
    }
}

/// inline 配置里的 pass 会被 rclone 当作「已混淆密码」去解密，明文必须转换一次。
/// 服务端 HTTP basic auth 的 user/pass 不走 Reveal，不需要转换。
fn obscure(plain: &str) -> Result<String, String> {
    let api = API.get().ok_or_else(|| "librclone 尚未加载".to_string())?;
    let cplain = CString::new(plain).map_err(|e| e.to_string())?;
    unsafe {
        let ptr = (api.obscure)(cplain.as_ptr());
        if ptr.is_null() {
            return Err("服务密码转换失败".to_string());
        }
        let result = CStr::from_ptr(ptr).to_string_lossy().into_owned();
        (api.free_string)(ptr);
        Ok(result)
    }
}

/// 把 Kotlin 传来的 inline fs 配置（含明文密码）转成可直接喂给 RC 的 JSON。
///
/// 返回 `(配置, 是否允许不安全连接)`：`insecure` 是本项目自定义的键，rclone 的
/// webdav backend 不认得它，先剥离，再作为 RC 顶层的全局参数下发。
fn prepare_fs(fs_json: &str) -> Result<(Value, bool), String> {
    let mut fs: Value =
        serde_json::from_str(fs_json).map_err(|e| format!("远程服务配置无效：{e}"))?;
    if let Some(pass) = fs.get("pass").and_then(Value::as_str).map(str::to_string) {
        if !pass.is_empty() {
            fs["pass"] = json!(obscure(&pass)?);
        }
    }
    let insecure = fs
        .as_object_mut()
        .and_then(|o| o.remove("insecure"))
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    Ok((fs, insecure))
}

// ==================== restic 仓库服务 ====================

struct Serve {
    /// 哪个逻辑仓库在用这个服务；换仓库就停掉重起
    key: String,
    url: String,
    id: String,
}

static SERVE: Mutex<Option<Serve>> = Mutex::new(None);

fn token() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let seq = COUNTER.fetch_add(1, Ordering::Relaxed);
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!("{stamp:x}{seq:x}")
}

/// 注入新凭据 / 换仓库时停掉旧服务，避免残留监听和旧连接。
fn stop_serve() {
    if let Ok(mut guard) = SERVE.lock() {
        if let Some(old) = guard.take() {
            let _ = rpc("serve/stop", &json!({ "id": old.id }));
        }
    }
}

/// 起（或复用）一个 `serve restic`，返回给 `RestBackend` 的 URL。
///
/// `key` 用逻辑仓库位置，配合 set_repo 时的 stop_serve 保证配置变化后不复用旧连接。
fn remote_url(fs: &Value, key: &str, insecure: bool) -> Result<String, String> {
    let mut guard = SERVE
        .lock()
        .map_err(|_| "远程服务状态不可用".to_string())?;
    if let Some(serve) = guard.as_ref() {
        if serve.key == key {
            return Ok(serve.url.clone());
        }
    }
    if let Some(old) = guard.take() {
        let _ = rpc("serve/stop", &json!({ "id": old.id }));
    }

    let user = token();
    let pass = token();
    // 15s 硬上限：rclone 自己卡住时（远端不可达、DNS 不响应）不会主动报错，
    // 只能由这一层给结论。正常链路（握手 + 一次目录请求）在 1~4s 内完成，
    // 15s 留了足够余量，又不会让用户干等。
    let response = rpc_with_timeout(
        "serve/start",
        &json!({
            "type": "restic",
            "fs": fs,
            "addr": ["127.0.0.1:0"],
            "user": user,
            "pass": pass,
            // 等价 --no-check-certificate：只在本次 RC 调用内覆盖全局配置，
            // backend 创建时生成的 HTTP transport 会带上它
            "no_check_certificate": insecure,
        }),
        Duration::from_secs(15),
    )?;
    let addr = response
        .get("addr")
        .and_then(Value::as_str)
        .ok_or_else(|| "serve/start 未返回监听地址".to_string())?;
    let id = response
        .get("id")
        .and_then(Value::as_str)
        .ok_or_else(|| "serve/start 未返回服务 id".to_string())?;
    let url = format!("http://{user}:{pass}@{addr}/");
    *guard = Some(Serve {
        key: key.to_string(),
        url: url.clone(),
        id: id.to_string(),
    });
    Ok(url)
}

// ==================== 远程目录浏览 ====================

fn join_remote(path: &str, name: &str) -> Result<String, String> {
    if name.is_empty() || name.contains('/') || name.contains('\\') || name == ".." {
        return Err("文件夹名称不合法".to_string());
    }
    let base = path.trim_matches('/');
    Ok(if base.is_empty() {
        name.to_string()
    } else {
        format!("{base}/{name}")
    })
}

/// 列一层子目录；`create` 不为空时先在该层建一个文件夹再重新列。
fn list_dirs(
    fs: &Value,
    path: &str,
    create: Option<&str>,
    insecure: bool,
) -> Result<Vec<String>, String> {
    if let Some(name) = create {
        let target = join_remote(path, name)?;
        rpc(
            "operations/mkdir",
            &json!({
                "fs": fs,
                "remote": target,
                "no_check_certificate": insecure,
                "contimeout": "5s",
                "timeout": "10s",
                "low_level_retries": 1,
            }),
        )?;
    }
    let remote = path.trim_matches('/');
    let response = rpc(
        "operations/list",
        &json!({
            "fs": fs,
            "remote": remote,
            "no_check_certificate": insecure,
            "contimeout": "5s",
            "timeout": "10s",
            "low_level_retries": 1,
        }),
    )?;
    let mut dirs: Vec<String> = response
        .get("list")
        .and_then(Value::as_array)
        .ok_or_else(|| "列目录返回格式异常".to_string())?
        .iter()
        .filter(|entry| {
            entry
                .get("IsDir")
                .and_then(Value::as_bool)
                .unwrap_or(false)
        })
        .filter_map(|entry| {
            entry
                .get("Name")
                .and_then(Value::as_str)
                .map(str::to_string)
        })
        .collect();
    dirs.sort();
    Ok(dirs)
}
