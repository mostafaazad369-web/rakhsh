//! JNI lifecycle bridge for the Android host.
//!
//! The VPN service owns the TUN descriptor until nativeStart is called. After
//! that call, ownership is transferred to tun-rs through run_android().
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::sync::atomic::{AtomicI64, Ordering};
use tokio::runtime::{Builder, Runtime};
use tokio::task::JoinHandle;

static RUNTIME: OnceLock<Result<Runtime, String>> = OnceLock::new();
static TASKS: OnceLock<Mutex<HashMap<i64, JoinHandle<()>>>> = OnceLock::new();
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

fn runtime() -> Result<&'static Runtime, String> {
    match RUNTIME.get_or_init(|| {
        Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("rakhsh-native")
            .build()
            .map_err(|e| format!("create Tokio runtime: {e}"))
    }) {
        Ok(runtime) => Ok(runtime),
        Err(error) => Err(error.clone()),
    }
}

fn tasks() -> &'static Mutex<HashMap<i64, JoinHandle<()>>> {
    TASKS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn fail(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
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
        let config: String = env.get_string(&config)
            .map_err(|e| format!("read config string: {e}"))?
            .into();
        let token: String = env.get_string(&token)
            .map_err(|e| format!("read token string: {e}"))?
            .into();
        let static_secret: String = env.get_string(&static_secret)
            .map_err(|e| format!("read static secret string: {e}"))?
            .into();

        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        if id <= 0 {
            return Err("native task id exhausted".into());
        }
        let handle = runtime()?.spawn(async move {
            if let Err(error) = crate::run_android(&config, &token, &static_secret, tun_fd).await {
                // Do not include configuration or credentials in logs.
                tracing::error!("Android Rakhsh engine stopped: {error:#}");
            }
        });
        tasks().lock()
            .map_err(|_| "native task registry poisoned".to_string())?
            .insert(id, handle);
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
        if let Some(handle) = tasks.remove(&(task_id as i64)) {
            handle.abort();
        }
    }
}
