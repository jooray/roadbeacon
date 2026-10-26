package app.roadbeacon.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.roadbeacon.util.UnitSystem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class Settings(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    var nsec: String?
        get() = prefs.getString(KEY_NSEC, null)
        set(value) = prefs.edit().putString(KEY_NSEC, value).apply()

    var relays: List<String>
        get() {
            val json = prefs.getString(KEY_RELAYS, null) ?: return DEFAULT_RELAYS
            return try {
                Json.decodeFromString(json)
            } catch (e: Exception) {
                DEFAULT_RELAYS
            }
        }
        set(value) = prefs.edit().putString(KEY_RELAYS, Json.encodeToString(value)).apply()

    var useEphemeralKeys: Boolean
        get() = prefs.getBoolean(KEY_USE_EPHEMERAL, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_EPHEMERAL, value).apply()

    var alertEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERT_ENABLED, value).apply()

    var alertDistance: Int
        get() = prefs.getInt(KEY_ALERT_DISTANCE, 500)
        set(value) = prefs.edit().putInt(KEY_ALERT_DISTANCE, value).apply()

    var alertSound: Boolean
        get() = prefs.getBoolean(KEY_ALERT_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERT_SOUND, value).apply()

    var alertVibration: Boolean
        get() = prefs.getBoolean(KEY_ALERT_VIBRATION, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERT_VIBRATION, value).apply()

    var querySpeedThreshold: Int
        get() = prefs.getInt(KEY_SPEED_THRESHOLD, 80)
        set(value) = prefs.edit().putInt(KEY_SPEED_THRESHOLD, value).apply()

    var visibleTypes: Set<Int>
        get() {
            val stored = prefs.getStringSet(KEY_VISIBLE_TYPES, null)
            return stored?.map { it.toInt() }?.toSet() ?: ALL_TYPES
        }
        set(value) = prefs.edit().putStringSet(KEY_VISIBLE_TYPES, value.map { it.toString() }.toSet()).apply()

    var osmandPackage: String
        get() = prefs.getString(KEY_OSMAND_PACKAGE, "net.osmand.plus") ?: "net.osmand.plus"
        set(value) = prefs.edit().putString(KEY_OSMAND_PACKAGE, value).apply()

    var unitSystem: UnitSystem
        get() = UnitSystem.fromStored(prefs.getString(KEY_UNIT_SYSTEM, null))
        set(value) = prefs.edit().putString(KEY_UNIT_SYSTEM, value.name.lowercase()).apply()

    companion object {
        private const val TAG = "Settings"
        private const val PREFS_NAME = "roadbeacon_prefs"
        private const val KEY_NSEC = "nsec"
        private const val KEY_RELAYS = "relays"
        private const val KEY_USE_EPHEMERAL = "use_ephemeral_keys"
        private const val KEY_ALERT_ENABLED = "alert_enabled"
        private const val KEY_ALERT_DISTANCE = "alert_distance"
        private const val KEY_ALERT_SOUND = "alert_sound"
        private const val KEY_ALERT_VIBRATION = "alert_vibration"
        private const val KEY_SPEED_THRESHOLD = "query_speed_threshold"
        private const val KEY_VISIBLE_TYPES = "visible_types"
        private const val KEY_OSMAND_PACKAGE = "osmand_package"
        private const val KEY_UNIT_SYSTEM = "unit_system"

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://offchain.pub"
        )

        private val ALL_TYPES = setOf(0, 1, 2, 3, 4, 5, 6, 7, 255)

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return try {
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt preferences, clearing corrupted data", e)
                // Delete corrupted preferences file
                File(context.filesDir.parent, "shared_prefs/${PREFS_NAME}.xml").delete()
                // Retry creating fresh encrypted preferences
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        }
    }
}
