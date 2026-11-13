package org.cygnusx1.openbu.notify

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.cygnusx1.openbu.data.ProxyConfig
import org.cygnusx1.openbu.network.SavedPrinter
import org.cygnusx1.openbu.network.Socks5TlsSocket
import java.net.Socket
import java.util.UUID

/**
 * Read/write access to the same encrypted "bambu_prefs" file the ViewModel uses, so the
 * background service can see saved printers, relay settings and the notification toggles
 * without needing the ViewModel (which dies with the activity).
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
     * The Bambuddy server to watch, or null when the app is in direct-connection fallback mode
     * or no server has been saved (then the service watches the saved printers over MQTT).
     */
    fun bambuddyConfig(prefs: SharedPreferences): BambuddyConfig? {
        if (prefs.getBoolean(KEY_BB_FALLBACK, false)) return null
        val url = prefs.getString(KEY_BB_URL, "") ?: ""
        if (url.isBlank()) return null
        return BambuddyConfig(url, prefs.getString(KEY_BB_API, "") ?: "")
    }

    /** Returns null if the encrypted prefs can't be opened (the ViewModel handles resetting them). */
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

    /** Mirrors BambuStreamViewModel.loadSavedPrinters(). */
    fun savedPrinters(prefs: SharedPreferences): List<SavedPrinter> {
        val serialsCsv = prefs.getString("saved_printer_serials", "") ?: ""
        return serialsCsv.split(",").filter { it.isNotBlank() }.mapNotNull { serial ->
            val ip = prefs.getString("saved_ip_$serial", "") ?: ""
            val accessCode = prefs.getString("access_code_$serial", "") ?: ""
            if (ip.isBlank()) return@mapNotNull null
            SavedPrinter(
                ip = ip,
                serialNumber = serial,
                accessCode = accessCode,
                deviceName = (prefs.getString("custom_name_$serial", "") ?: "").ifBlank {
                    prefs.getString("saved_name_$serial", "") ?: ""
                },
            )
        }
    }

    /**
     * MQTT client id for the background connection. It must differ from the UI's id, otherwise
     * the broker would treat the two as the same client and keep kicking one of them off.
     * MQTT 3.1.1 brokers must accept ids up to 23 characters, so stay within that.
     */
    fun backgroundClientId(prefs: SharedPreferences): String =
        prefs.getString("mqtt_client_id_bg", null) ?: run {
            val id = "bammate_bg_" + UUID.randomUUID().toString().replace("-", "").take(13)
            prefs.edit().putString("mqtt_client_id_bg", id).apply()
            id
        }

    /** Mirrors BambuStreamViewModel.effectiveProxyConfig() so relay users get alerts too. */
    private fun proxyConfig(prefs: SharedPreferences, serial: String): ProxyConfig? {
        if (!prefs.getBoolean("relay_enabled", false)) return null
        if (prefs.getBoolean("relay_override_$serial", false)) return null
        val host = prefs.getString("relay_host", "") ?: ""
        val port = (prefs.getString("relay_port", "1080") ?: "1080").toIntOrNull() ?: return null
        val user = prefs.getString("relay_username", "") ?: ""
        val pass = prefs.getString("relay_password", "") ?: ""
        if (host.isBlank() || user.isBlank() || pass.isBlank()) return null
        return ProxyConfig(host, port, user, pass)
    }

    fun proxySocketFactory(prefs: SharedPreferences, serial: String): ((String, Int) -> Socket)? {
        val config = proxyConfig(prefs, serial) ?: return null
        return { host, port -> Socks5TlsSocket.connect(config, host, port) }
    }
}
