package com.rakhsh.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.IBinder
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Owns Android's TUN interface and the native Rakhsh engine task.
 * Credentials are passed only in the start Intent and remain in memory.
 */
class RakhshVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var nativeTaskId: Long = 0L

    override fun onCreate() {
        super.onCreate()
        NativeBridge.nativeSetProtector(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (nativeTaskId != 0L || tun != null) {
                    stopTunnel()
                }
                startForeground(NOTIFICATION_ID, notification("در حال آماده‌سازی تونل"))
                try {
                    startTunnel(
                        intent.getStringExtra(EXTRA_CONFIG) ?: error("پروفایل موجود نیست"),
                        intent.getStringExtra(EXTRA_TOKEN) ?: error("Token موجود نیست"),
                        intent.getStringExtra(EXTRA_SECRET) ?: error("Static secret موجود نیست")
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Could not start VPN", error)
                    stopTunnel()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            else -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(config: String, token: String, secret: String) {
        // Rust validates the same profile used by nativeStart and supplies the
        // exact L3 addresses/MTU/DNS values needed by Android's TUN builder.
        val parameters = NativeBridge.nativeVpnParameters(config, token, secret)
            ?: error("پارامترهای رابط VPN از هسته دریافت نشد")
        val fields = parameters.split('\n')
        require(fields.size >= 2) { "پاسخ پارامترهای VPN ناقص است" }
        val mtu = fields[1].toIntOrNull() ?: error("MTU نامعتبر است")
        require(mtu in 576..9000) { "MTU خارج از محدودهٔ مجاز است" }

        val builder = Builder()
            .setSession("Rakhsh")
            .setMtu(mtu)
            .setBlocking(true)

        val configuredFamilies = mutableSetOf<Int>()
        fields[0].split(',').map(String::trim).filter(String::isNotEmpty).forEach { cidr ->
            val parts = cidr.split('/', limit = 2)
            val address = InetAddress.getByName(parts[0])
            val prefix = parts.getOrNull(1)?.toIntOrNull()
                ?: if (address is Inet6Address) 64 else 24
            val maxPrefix = if (address is Inet6Address) 128 else 32
            require(prefix in 0..maxPrefix) { "Prefix نامعتبر در $cidr" }
            builder.addAddress(address, prefix)
            configuredFamilies += if (address is Inet6Address) 6 else 4
        }
        require(configuredFamilies.isNotEmpty()) { "هیچ آدرس TUN معتبری در پروفایل نیست" }

        // Route both IP families into the tunnel to avoid silently leaking IPv6
        // when the profile contains only an IPv4 interface address.
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)

        if (fields.size >= 3) {
            fields[2].split(',').map(String::trim).filter(String::isNotEmpty).forEach { value ->
                val resolver = runCatching { InetAddress.getByName(value) }.getOrNull()
                if (resolver is Inet4Address || resolver is Inet6Address) {
                    builder.addDnsServer(resolver)
                }
            }
        }

        val established = builder.establish() ?: error("Android اجازهٔ ساخت رابط VPN را نداد")
        tun = established
        val fd = established.detachFd()
        try {
            nativeTaskId = NativeBridge.nativeStart(config, token, secret, fd)
            check(nativeTaskId > 0L) { "هستهٔ Rust نتوانست اجرا شود" }
        } catch (error: Exception) {
            // nativeStart owns the detached fd only after accepting the call.
            // If JNI throws before ownership transfer, close the raw descriptor.
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            throw error
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification("تونل Rakhsh فعال است"))
    }

    private fun stopTunnel() {
        if (nativeTaskId > 0L) {
            NativeBridge.nativeStop(nativeTaskId)
            nativeTaskId = 0L
        }
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopTunnel()
        NativeBridge.nativeClearProtector()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rakhsh VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Rakhsh")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(nativeTaskId != 0L || message.contains("آماده‌سازی"))
            .build()
    }

    companion object {
        const val ACTION_START = "com.rakhsh.android.action.START"
        const val ACTION_STOP = "com.rakhsh.android.action.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_SECRET = "secret"
        private const val TAG = "RakhshVpnService"
        private const val CHANNEL_ID = "rakhsh_vpn"
        private const val NOTIFICATION_ID = 1101
    }
}
