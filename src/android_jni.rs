//! JNI lifecycle bridge for the Android host.
//!
//! The VPN service owns the TUN descriptor until nativeStart is called. After
//! that call, ownership is transferred to tun-rs through run_android().
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong};
use jni::{JNIEnv, JavaVM};
use std::collections::HashMap;
use std::os::fd::FromRawFd;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Mutex, OnceLock};
use tokio::runtime::{Builder, Runtime};
use tokio::task::JoinHandle;

static TASKS: OnceLock<Mutex<HashMap<i64, AndroidTask>>> = OnceLock::new();
static NEXT_ID: AtomicI64 = AtomicI64::new(1);
static JAVA_VM: OnceLock<JavaVM> = OnceLock::new();
static VPN_SERVICE: OnceLock<Mutex<Option<GlobalRef>>> = OnceLock::new();

struct AndroidTask {
    // Keep a dedicated runtime per VPN session so nativeStop shuts down every
    // child task spawned by the engine, not only the top-level future.
    runtime: Runtime,
    handle: JoinHandle<()>,
}

fn tasks() -> &'static Mutex<HashMap<i64, AndroidTask>> {
    TASKS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn fail(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

fn vpn_service() -> &'static Mutex<Option<GlobalRef>> {
    VPN_SERVICE.get_or_init(|| Mutex::new(None))
}

/// Ask Android VpnService to exempt an already-created socket from the VPN.
/// The caller MUST invoke this before connect(), bind(), or the first send.
pub(crate) fn protect_socket(fd: std::os::fd::RawFd) -> anyhow::Result<()> {
    let vm = JAVA_VM
        .get()
        .ok_or_else(|| anyhow::anyhow!("Android VPN protector is not registered"))?;
    let service = vpn_service()
        .lock()
        .map_err(|_| anyhow::anyhow!("Android VPN protector lock poisoned"))?;
    let service = service
        .as_ref()
        .ok_or_else(|| anyhow::anyhow!("Android VPN service is not registered"))?;
    let mut env = vm
        .attach_current_thread()
        .map_err(|e| anyhow::anyhow!("attach JNI thread: {e}"))?;
    let allowed = env
        .call_method(service.as_obj(), "protect", "(I)Z", &[JValue::Int(fd)])?
        .z()?;
    if !allowed {
        anyhow::bail!("Android VpnService.protect({fd}) returned false");
    }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_rakhsh_android_NativeBridge_nativeSetProtector(
    mut env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(error) => {
            fail(&mut env, &format!("get Android JavaVM: {error}"));
            return;
        }
    };
    let global = match env.new_global_ref(service) {
        Ok(global) => global,
        Err(error) => {
            fail(&mut env, &format!("retain Android VPN service: {error}"));
            return;
        }
    };
    if JAVA_VM.set(vm).is_err() {
        // A JavaVM is process-wide. A previous registration is expected when
        // the VPN service is restarted; the global service reference is replaced.
    }
    match vpn_service().lock() {
        Ok(mut slot) => *slot = Some(global),
        Err(_) => fail(&mut env, "Android VPN protector lock poisoned"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rakhsh_android_NativeBridge_nativeClearProtector(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(mut slot) = vpn_service().lock() {
        *slot = None;
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rakhsh_android_NativeBridge_nativeValidateConfig(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
    token: JString,
    static_secret: JString,
) -> jni::sys::jstring {
    let result = (|| -> Result<(), String> {
        let config: String = env
            .get_string(&config)
            .map_err(|e| format!("read config string: {e}"))?
            .into();
        let token: String = env
            .get_string(&token)
            .map_err(|e| format!("read token string: {e}"))?
            .into();
        let static_secret: String = env
            .get_string(&static_secret)
            .map_err(|e| format!("read static secret string: {e}"))?
            .into();
        crate::prepare_android_config(&config, &token, &static_secret)
            .map_err(|e| format!("{e:#}"))?;
        Ok(())
    })();

    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(message) => env
            .new_string(message)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rakhsh_android_NativeBridge_nativeVpnParameters(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
    token: JString,
    static_secret: JString,
) -> jni::sys::jstring {
    let result = (|| -> Result<String, String> {
        let config: String = env
            .get_string(&config)
            .map_err(|e| format!("read config string: {e}"))?
            .into();
        let token: String = env
            .get_string(&token)
            .map_err(|e| format!("read token string: {e}"))?
            .into();
        let static_secret: String = env
            .get_string(&static_secret)
            .map_err(|e| format!("read static secret string: {e}"))?
            .into();
        let cfg = crate::prepare_android_config(&config, &token, &static_secret)
            .map_err(|e| format!("invalid Android Rakhsh config: {e:#}"))?;
        let addresses = cfg.l3_addr.as_deref()
            .ok_or_else(|| "Android VPN requires l3_addr".to_string())?;
        let dns = cfg.dns.as_ref()
            .map(|dns| dns.remote.join(","))
            .unwrap_or_default();
        Ok(format!("{}\n{}\n{}", addresses, crate::l3::planned_mtu(&cfg), dns))
    })();

    match result {
        Ok(value) => env
            .new_string(value)
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(message) => {
            fail(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rakhsh_android_NativeBridge_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
    token: JString,
    static_secret: JString,
    tun_fd: jint,
) -> jlong {
    let result = (|| -> Result<i64, String> {
        if tun_fd < 0 {
            return Err("invalid Android TUN file descriptor".into());
        }
        let config: String = env
            .get_string(&config)
            .map_err(|e| format!("read config string: {e}"))?
            .into();
        let token: String = env
            .get_string(&token)
            .map_err(|e| format!("read token string: {e}"))?
            .into();
        let static_secret: String = env
            .get_string(&static_secret)
            .map_err(|e| format!("read static secret string: {e}"))?
            .into();

        // Parse and validate all configuration before taking ownership of the
        // descriptor. On any error above, Kotlin still owns the PFD.
        let cfg = crate::prepare_android_config(&config, &token, &static_secret)
            .map_err(|e| format!("invalid Android Rakhsh config: {e:#}"))?;
        let rt = Builder::new_multi_thread()
            .enable_all()
            .thread_name("rakhsh-android")
            .build()
            .map_err(|e| format!("create Android Tokio runtime: {e}"))?;
        let mut registry = tasks()
            .lock()
            .map_err(|_| "native task registry poisoned".to_string())?;

        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        if id <= 0 {
            return Err("native task id exhausted".into());
        }

        // SAFETY: Kotlin passes ParcelFileDescriptor.detachFd(); after this
        // conversion the OwnedFd is the sole owner and closes it on drop.
        let tun_fd = unsafe { std::os::fd::OwnedFd::from_raw_fd(tun_fd) };
        let handle = rt.spawn(async move {
            if let Err(error) = crate::run_android_config(cfg, tun_fd).await {
                // Do not include configuration or credentials in logs.
                tracing::error!("Android Rakhsh engine stopped: {error:#}");
            }
        });
        registry.insert(id, AndroidTask { runtime: rt, handle });
        Ok(id)
    })();

    match result {
        Ok(id) => id as jlong,
        Err(message) => {
            fail(&mut env, &message);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_rakhsh_android_NativeBridge_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    task_id: jlong,
) {
    if task_id <= 0 {
        return;
    }
    if let Ok(mut tasks) = tasks().lock() {
        if let Some(task) = tasks.remove(&(task_id as i64)) {
            task.handle.abort();
            // This also cancels detached child tasks spawned by entry::run_inner.
            task.runtime.shutdown_background();
        }
    }
}
