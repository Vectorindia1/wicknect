package com.hostelautologin.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hostelautologin.core.AppLog
import com.hostelautologin.core.Masker
import com.hostelautologin.core.PortalConfig
import com.hostelautologin.core.Settings
import java.io.File

/** Credentials and settings, encrypted with a key held in the Android Keystore. */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secure_store", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun settings() = Settings(
        ssid = prefs.getString("ssid", null) ?: "University_Hostel",
        username = prefs.getString("username", "") ?: "",
        autoLogin = prefs.getBoolean("auto_login", true),
        loginIfSsidUnknown = prefs.getBoolean("login_if_ssid_unknown", false),
    )

    fun saveSettings(s: Settings) {
        prefs.edit().putString("ssid", s.ssid).putString("username", s.username)
            .putBoolean("auto_login", s.autoLogin).putBoolean("login_if_ssid_unknown", s.loginIfSsidUnknown).apply()
    }

    fun password(): String? = prefs.getString("password", null)?.also { Masker.global.register(it) }

    fun savePassword(pw: String) {
        Masker.global.register(pw)
        prefs.edit().putString("password", pw).apply()
    }

    fun credentials(): Pair<String, String>? {
        val u = settings().username
        val p = password()
        return if (u.isNotEmpty() && !p.isNullOrEmpty()) u to p else null
    }

    fun hasCredentials() = credentials() != null
}

/** portal.json lives in app-private storage; it is seeded from the bundled placeholder and editable in-app. */
class PortalStore(private val context: Context) {
    private val file get() = File(context.filesDir, "portal.json")

    fun text(): String {
        if (!file.exists()) reset()
        return file.readText()
    }

    fun save(text: String) {
        PortalConfig.parse(text)                        // validate before writing; throws ConfigException
        file.writeText(text)
        AppLog.i("portal.json saved")
    }

    fun reset() {
        file.writeText(context.assets.open("portal.json").bufferedReader().use { it.readText() })
    }

    fun config(): PortalConfig = PortalConfig.parse(text())
}
