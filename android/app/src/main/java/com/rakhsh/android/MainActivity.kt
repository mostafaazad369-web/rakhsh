package com.rakhsh.android

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.net.VpnService
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rakhsh.android.security.SecretStore

/**
 * Secure config-import screen.
 *
 * This screen only imports and encrypts the profile and credentials. It does
 * not start the VPN service: TUN establishment and routing remain disabled
 * until the native/service lifecycle is validated end to end.
 */
class MainActivity : Activity() {
    private lateinit var store: SecretStore
    private lateinit var configInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var status: TextView

    @Suppress("DEPRECATION")
    private fun chooseConfigFile() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_CONFIG)
    }

    @Deprecated("Handled for Android document picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) startVpnService()
            else status.text = "مجوز VPN داده نشد؛ اتصال شروع نشد."
            return
        }
        if (requestCode != REQUEST_CONFIG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                val value = it.readText()
                require(value.length <= MAX_CONFIG_CHARS) { "حجم فایل تنظیمات بیش از حد مجاز است." }
                value
            } ?: error("خواندن فایل تنظیمات ممکن نشد.")
            configInput.setText(text)
            status.text = "فایل تنظیمات بارگذاری شد؛ برای ذخیره‌سازی امن، دکمه ذخیره را بزنید."
        } catch (error: Exception) {
            status.text = "خطا در خواندن فایل: ${error.message ?: "نامشخص"}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecretStore(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply {
            text = "رخش | تنظیمات اتصال"
            textSize = 23f
        }, matchWidth())

        root.addView(TextView(this).apply {
            text = "فایل TOML سرور و اطلاعات احراز هویت را وارد کنید. اطلاعات پس از ذخیره با Android Keystore رمزگذاری می‌شوند."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        }, matchWidth())

        val importButton = Button(this).apply {
            text = "انتخاب فایل تنظیمات TOML"
            setOnClickListener { chooseConfigFile() }
        }
        root.addView(importButton, matchWidth())

        configInput = EditText(this).apply {
            hint = "محتوای فایل TOML"
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            maxLines = 16
        }
        root.addView(configInput, matchWidth())

        tokenInput = EditText(this).apply {
            hint = "Token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(tokenInput, matchWidth())

        secretInput = EditText(this).apply {
            hint = "Static secret"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(secretInput, matchWidth())

        val saveButton = Button(this).apply {
            text = "ذخیره امن اطلاعات"
            setOnClickListener { saveProfile() }
        }
        root.addView(saveButton, matchWidth())

        root.addView(Button(this).apply {
            text = "اتصال VPN"
            setOnClickListener { requestVpnConnection() }
        }, matchWidth())

        root.addView(Button(this).apply {
            text = "قطع اتصال"
            setOnClickListener { stopVpnService() }
        }, matchWidth())

        status = TextView(this).apply {
            text = "هنوز تنظیماتی ذخیره نشده است. سرویس VPN در این نسخه فعال نمی‌شود."
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(status, matchWidth())
        setContentView(scroll)

        restoreProfile()
    }

    private fun saveProfile() {
        val config = configInput.text.toString().trim()
        val token = tokenInput.text.toString()
        val secret = secretInput.text.toString()
        if (config.isEmpty() || token.isEmpty() || secret.isEmpty()) {
            status.text = "لطفاً فایل TOML، Token و Static secret را کامل وارد کنید."
            return
        }
        if (config.length > MAX_CONFIG_CHARS) {
            status.text = "حجم تنظیمات بیش از حد مجاز است."
            return
        }
        try {
            val validationError = NativeBridge.nativeValidateConfig(config, token, secret)
            if (validationError != null) {
                status.text = "تنظیمات معتبر نیست: $validationError"
                return
            }
            // Commit all encrypted profile values together to avoid partial saves.
            store.saveAll(mapOf(
                "config" to config,
                "token" to token,
                "static_secret" to secret
            ))
            tokenInput.text.clear()
            secretInput.text.clear()
            status.text = "پروفایل معتبر و به‌صورت رمزگذاری‌شده ذخیره شد. اکنون می‌توانید اتصال را درخواست کنید."
        } catch (error: Exception) {
            status.text = "ذخیره امن ناموفق بود: ${error.message ?: "خطای نامشخص"}"
        }
    }

    private fun restoreProfile() {
        try {
            configInput.setText(store.read("config").orEmpty())
            // Credentials are deliberately not prefilled into visible text fields.
            val hasToken = store.read("token") != null
            val hasSecret = store.read("static_secret") != null
            if (configInput.text.isNotEmpty() && hasToken && hasSecret) {
                status.text = "یک پروفایل رمزگذاری‌شده روی دستگاه موجود است."
            }
        } catch (error: Exception) {
            status.text = "بازیابی اطلاعات ممکن نشد؛ احتمالاً کلید امن دستگاه در دسترس نیست. لطفاً اطلاعات را دوباره وارد کنید."
        }
    }

    private fun requestVpnConnection() {
        try {
            // Verify the encrypted values exist before showing Android's consent prompt.
            store.read("config") ?: error("ابتدا پروفایل را ذخیره کنید")
            store.read("token") ?: error("Token ذخیره نشده است")
            store.read("static_secret") ?: error("Static secret ذخیره نشده است")
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent != null) {
                startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
            } else {
                startVpnService()
            }
        } catch (error: Exception) {
            status.text = "آماده‌سازی اتصال ناموفق بود: " + (error.message ?: "خطای نامشخص")
        }
    }

    private fun startVpnService() {
        try {
            val profile = store.read("config") ?: error("پروفایل موجود نیست")
            val authToken = store.read("token") ?: error("Token ذخیره نشده است")
            val authSecret = store.read("static_secret") ?: error("Static secret ذخیره نشده است")
            val intent = android.content.Intent(this, RakhshVpnService::class.java).apply {
                action = RakhshVpnService.ACTION_START
                putExtra(RakhshVpnService.EXTRA_CONFIG, profile)
                putExtra(RakhshVpnService.EXTRA_TOKEN, authToken)
                putExtra(RakhshVpnService.EXTRA_SECRET, authSecret)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            status.text = "درخواست اتصال ارسال شد؛ وضعیت سرویس در اعلان Android نمایش داده می‌شود."
        } catch (error: Exception) {
            status.text = "شروع سرویس ناموفق بود: " + (error.message ?: "خطای نامشخص")
        }
    }

    private fun stopVpnService() {
        val intent = android.content.Intent(this, RakhshVpnService::class.java).apply {
            action = RakhshVpnService.ACTION_STOP
        }
        startService(intent)
        status.text = "درخواست قطع اتصال ارسال شد."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private companion object {
        const val MAX_CONFIG_CHARS = 1_000_000
        const val REQUEST_CONFIG = 4107
        const val REQUEST_VPN_PERMISSION = 4108
    }
}
