package com.nanobeaconnetwork.internal.prefs

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

internal class SdkPrefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "nbn_sdk_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SERVER_URL, v).apply()

    var anonymousToken: String
        get() = prefs.getString(KEY_ANONYMOUS_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ANONYMOUS_TOKEN, v).apply()

    var scanEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCAN_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_SCAN_ENABLED, v).apply()

    var dedupWindowSeconds: Int
        get() = prefs.getInt(KEY_DEDUP_WINDOW, 300)
        set(v) = prefs.edit().putInt(KEY_DEDUP_WINDOW, v).apply()

    var reportMinIntervalSeconds: Int
        get() = prefs.getInt(KEY_REPORT_MIN_INTERVAL, 10)
        set(v) = prefs.edit().putInt(KEY_REPORT_MIN_INTERVAL, v).apply()

    var reportBatchThreshold: Int
        get() = prefs.getInt(KEY_BATCH_THRESHOLD, 50)
        set(v) = prefs.edit().putInt(KEY_BATCH_THRESHOLD, v).apply()

    /**
     * Per-install SQLCipher passphrase. Generated once with [SecureRandom] and persisted in the
     * Keystore-backed encrypted prefs, so the local database is never protected by a value baked
     * into the shipped SDK. Stored base64-encoded; returned as the raw 32-byte key.
     */
    val databasePassphrase: ByteArray
        get() {
            prefs.getString(KEY_DB_PASSPHRASE, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(KEY_DB_PASSPHRASE, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
            return key
        }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ANONYMOUS_TOKEN = "anonymous_token"
        private const val KEY_SCAN_ENABLED = "scan_enabled"
        private const val KEY_DEDUP_WINDOW = "dedup_window_seconds"
        private const val KEY_REPORT_MIN_INTERVAL = "report_min_interval_seconds"
        private const val KEY_BATCH_THRESHOLD = "report_batch_threshold"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
    }
}
