package com.rakhsh.android

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

    private val openConfig = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
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
            setOnClickListener { openConfig.launch(arrayOf("text/*", "application/octet-stream")) }
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
            // Each value is independently authenticated/encrypted at rest.
            store.save("config", config)
            store.save("token", token)
            store.save("static_secret", secret)
            tokenInput.text.clear()
            secretInput.text.clear()
            status.text = "تنظیمات با موفقیت و به‌صورت رمزگذاری‌شده ذخیره شد. اتصال VPN هنوز غیرفعال است."
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private companion object {
        const val MAX_CONFIG_CHARS = 1_000_000
    }
}
