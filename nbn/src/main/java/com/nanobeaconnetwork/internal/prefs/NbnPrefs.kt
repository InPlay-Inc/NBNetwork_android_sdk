package com.nanobeaconnetwork.internal.prefs

import android.content.Context
import android.util.Base64
import com.nanobeaconnetwork.NbnConfig
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

internal class NbnPrefs(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "nbn_prefs",
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

    // True while LIBRARY_SCAN scanning is active. Set by NbnClient.startScan/stopScan; read by
    // BootReceiver to decide whether to resume scanning after a reboot.
    var scanEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCAN_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_SCAN_ENABLED, v).apply()
    var anonymousTokenInstallationKeyId: String
        get() = prefs.getString(KEY_ANONYMOUS_TOKEN_INSTALLATION_KEY_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ANONYMOUS_TOKEN_INSTALLATION_KEY_ID, v).apply()


    // Persisted NbnConfig.restartOnBoot, so BootReceiver knows the caller's preference.
    var restartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_RESTART_ON_BOOT, NbnConfig.DEFAULT_RESTART_ON_BOOT)
        set(v) = prefs.edit().putBoolean(KEY_RESTART_ON_BOOT, v).apply()

    var scanMode: NbnConfig.ScanMode
        get() = runCatching {
            NbnConfig.ScanMode.valueOf(prefs.getString(KEY_SCAN_MODE, null).orEmpty())
        }.getOrDefault(NbnConfig.ScanMode.LOW_POWER)
        set(v) = prefs.edit().putString(KEY_SCAN_MODE, v.name).apply()

    var dedupWindowSeconds: Int
        get() = prefs.getInt(KEY_DEDUP_WINDOW, 300)
        set(v) = prefs.edit().putInt(KEY_DEDUP_WINDOW, v).apply()

    var reportMinIntervalSeconds: Int
        get() = prefs.getInt(KEY_REPORT_MIN_INTERVAL, 10)
        set(v) = prefs.edit().putInt(KEY_REPORT_MIN_INTERVAL, v).apply()

    var reportBatchThreshold: Int
        get() = prefs.getInt(KEY_BATCH_THRESHOLD, 50)
        set(v) = prefs.edit().putInt(KEY_BATCH_THRESHOLD, v).apply()

    var sourceMinIntervalSeconds: Int
        get() = prefs.getInt(KEY_SOURCE_MIN_INTERVAL, 300)
        set(v) = prefs.edit().putInt(KEY_SOURCE_MIN_INTERVAL, v).apply()

    /**
     * Per-install SQLCipher passphrase. Generated once with [SecureRandom] and persisted in the
     * Keystore-backed encrypted prefs, so the local database is never protected by a value baked
     * into the shipped library. Stored base64-encoded; returned as the raw 32-byte key.
     */
    val databasePassphrase: ByteArray
        get() {
            prefs.getString(KEY_DB_PASSPHRASE, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(KEY_DB_PASSPHRASE, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
            return key
        }

    /** Per-install secret used only to unlinkably hash normalized BLE addresses. */
    val sourceKeyHmacKey: ByteArray
        get() {
            prefs.getString(KEY_SOURCE_HMAC, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(KEY_SOURCE_HMAC, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
            return key
        }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ANONYMOUS_TOKEN = "anonymous_token"
        private const val KEY_SCAN_ENABLED = "scan_enabled"
        private const val KEY_RESTART_ON_BOOT = "restart_on_boot"
        private const val KEY_SCAN_MODE = "scan_mode"
        private const val KEY_DEDUP_WINDOW = "dedup_window_seconds"
        private const val KEY_REPORT_MIN_INTERVAL = "report_min_interval_seconds"
        private const val KEY_BATCH_THRESHOLD = "report_batch_threshold"
        private const val KEY_SOURCE_MIN_INTERVAL = "source_min_interval_seconds"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val KEY_SOURCE_HMAC = "source_key_hmac"
        private const val KEY_ANONYMOUS_TOKEN_INSTALLATION_KEY_ID = "anonymous_token_installation_key_id"
    }
}
