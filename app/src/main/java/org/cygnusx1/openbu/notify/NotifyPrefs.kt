package org.cygnusx1.openbu.notify

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.cygnusx1.openbu.direct.DirectPrinter
import org.cygnusx1.openbu.direct.DirectStore

/**
 * Read/write access to the app's encrypted settings file, so the background service can see
 * the server, the saved printers and the notification toggles without the ViewModel (which
 * dies with the activity).
 */
object NotifyPrefs {
    private const val TAG = "NotifyPrefs"

    const val KEY_ENABLED = "notify_enabled"
    const val KEY_FINISHED = "notify_finished"
    const val KEY_FAILED = "notify_failed"
    const val KEY_PAUSED = "notify_paused"
    const val KEY_HMS = "notify_hms"
    const val KEY_ALMOST = "notify_almost_done"
    const val KEY_PLATE = "notify_plate_clear"
    const val KEY_IDLE_POWER = "notify_idle_power"
    const val KEY_MAINT = "notify_maintenance"

    // Written by BambuddyViewModel.
    private const val KEY_BB_URL = "bb_base_url"
    private const val KEY_BB_API = "bb_api_key"
    private const val KEY_BB_FALLBACK = "bb_fallback_mode"

    data class BambuddyConfig(val url: String, val apiKey: String)

    /**
     * The Bambuddy server to watch, or null when the app is in direct-connection mode or no
     * server has been saved.
     */
    fun bambuddyConfig(prefs: SharedPreferences): BambuddyConfig? {
        if (prefs.getBoolean(KEY_BB_FALLBACK, false)) return null
        val url = prefs.getString(KEY_BB_URL, "") ?: ""
        if (url.isBlank()) return null
        return BambuddyConfig(url, prefs.getString(KEY_BB_API, "") ?: "")
    }

    /** Returns null if the encrypted prefs can't be opened. */
    fun open(context: Context): SharedPreferences? = try {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "bambu_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Could not open encrypted prefs", e)
        null
    }

    /** True when the app talks to the printers directly instead of through Bambuddy. */
    fun directMode(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_BB_FALLBACK, false)

    /** The printers saved for the direct connection. */
    fun directPrinters(prefs: SharedPreferences): List<DirectPrinter> = DirectStore.load(prefs)
}
