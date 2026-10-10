package com.kidtoy.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Grown-up settings, stored with EncryptedSharedPreferences (AES-256-GCM values, key kept in the Android Keystore).
 * The Claude API key never leaves the device except in the x-api-key header to api.anthropic.com
 * (or it is not used at all when a proxy URL is set: then the proxy holds the key).
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            "toyforge_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Keystore broken (rare, some emulators): fall back to private prefs WITHOUT storing the key.
        keystoreBroken = true
        context.getSharedPreferences("toyforge_plain", Context.MODE_PRIVATE)
    }
    var keystoreBroken = false; private set

    var apiKey: String
        get() = prefs.getString(K_KEY, "") ?: ""
        set(v) { if (!keystoreBroken) prefs.edit().putString(K_KEY, v.trim()).apply() }
    var model: String
        get() = prefs.getString(K_MODEL, MODELS.first()) ?: MODELS.first()
        set(v) = prefs.edit().putString(K_MODEL, v).apply()
    var proxyUrl: String
        get() = prefs.getString(K_PROXY, "") ?: ""
        set(v) = prefs.edit().putString(K_PROXY, v.trim()).apply()
    /** Square bed size in mm used to centre the toy in Bambu/Orca projects (256 = X1/P1/A1, 180 = A1 mini). */
    var bedSize: Int
        get() = prefs.getInt(K_BED, 256)
        set(v) = prefs.edit().putInt(K_BED, v).apply()

    companion object {
        private const val K_KEY = "anthropic_api_key"
        private const val K_MODEL = "anthropic_model"
        private const val K_PROXY = "ai_proxy_url"
        private const val K_BED = "bed_size"
        val MODELS = listOf("claude-sonnet-5-5", "claude-haiku-5-5")
        val BEDS = listOf(256 to "X1 / P1 / A1", 180 to "A1 mini")

        fun mask(key: String) = if (key.length <= 10) "••••" else key.take(7) + "…" + key.takeLast(4)
    }
}
