package com.rakhsh.android

import android.net.VpnService
import android.content.Intent
import android.os.IBinder

/**
 * VPN service foundation.
 *
 * Registering this service lets native TCP carrier sockets call protect(fd)
 * before connecting. TUN establishment and nativeStart remain disabled until
 * the app has a validated imported config and complete route/lifecycle handling.
 */
class RakhshVpnService : VpnService() {
    override fun onCreate() {
        super.onCreate()
        NativeBridge.nativeSetProtector(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Do not establish a TUN yet: there is no end-to-end config/import UI,
        // notification lifecycle, or verified stop/reconnect handling.
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NativeBridge.nativeClearProtector()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
