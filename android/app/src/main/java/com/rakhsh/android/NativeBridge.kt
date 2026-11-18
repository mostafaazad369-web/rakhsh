package com.rakhsh.android

/**
 * JNI entry points for the Rust engine.
 *
 * nativeStart takes ownership of a detached VpnService TUN fd only after the
 * Rust side has accepted the call. Call nativeStop with the returned handle
 * to abort the engine task.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("rakhsh")
    }

    @JvmStatic
    external fun nativeStart(
        configToml: String,
        token: String,
        staticSecret: String,
        tunFd: Int
    ): Long

    @JvmStatic
    external fun nativeStop(taskId: Long)
}
