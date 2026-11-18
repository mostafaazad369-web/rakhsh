package com.rakhsh.android

import android.net.VpnService
import android.content.Intent
import android.os.IBinder

/**
 * Android VPN service declaration/foundation.
 *
 * This service deliberately does not call Builder.establish() yet. The Rust
 * engine must first accept the Android-provided TUN file descriptor and all
 * outbound carrier sockets must be protected with VpnService.protect().
 * Starting a half-integrated VPN could otherwise black-hole device traffic
 * or create a routing loop.
 */
class RakhshVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
