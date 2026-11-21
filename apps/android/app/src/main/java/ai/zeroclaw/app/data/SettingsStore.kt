package ai.zeroclaw.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "zeroclaw_settings")

data class StoredSettings(
    val gatewayUrl: String = "",
    val token: String = "",
    val agent: String = "",
    val deviceName: String = "Android",
    val lastSessionId: String = "",
    val deviceId: String = "",
    val nodeEnabled: Boolean = true,
)

/**
 * Connection state lives in app-private DataStore. The bearer token is the
 * same one the web dashboard keeps in localStorage; on Android that is
 * app-private storage, not world-readable.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val gatewayUrl = stringPreferencesKey("gateway_url")
        val token = stringPreferencesKey("token")
        val agent = stringPreferencesKey("agent")
        val deviceName = stringPreferencesKey("device_name")
        val lastSession = stringPreferencesKey("last_session_id")
        val deviceId = stringPreferencesKey("device_id")
        val nodeEnabled = booleanPreferencesKey("node_enabled")
    }

    val settings: Flow<StoredSettings> = context.settingsDataStore.data.map { prefs ->
        StoredSettings(
            gatewayUrl = prefs[Keys.gatewayUrl].orEmpty(),
            token = prefs[Keys.token].orEmpty(),
            agent = prefs[Keys.agent].orEmpty(),
            deviceName = prefs[Keys.deviceName] ?: "Android",
            lastSessionId = prefs[Keys.lastSession].orEmpty(),
            deviceId = prefs[Keys.deviceId].orEmpty(),
            nodeEnabled = prefs[Keys.nodeEnabled] ?: true,
        )
    }

    suspend fun current(): StoredSettings = settings.first()

    suspend fun saveConnection(gatewayUrl: String, token: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.gatewayUrl] = gatewayUrl
            prefs[Keys.token] = token
        }
    }

    suspend fun saveAgent(agent: String) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.agent] = agent }
    }

    suspend fun saveDeviceName(name: String) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.deviceName] = name }
    }

    suspend fun saveLastSession(sessionId: String) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.lastSession] = sessionId }
    }

    /** Stable, provider-safe node id (`^[a-z0-9-]+$`, short enough for a tool name). */
    suspend fun ensureDeviceId(): String {
        val existing = settings.first().deviceId
        if (existing.isNotBlank()) return existing
        val generated = "android-" + java.util.UUID.randomUUID().toString().take(8)
        context.settingsDataStore.edit { prefs -> prefs[Keys.deviceId] = generated }
        return generated
    }

    suspend fun saveNodeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.nodeEnabled] = enabled }
    }

    suspend fun clear() {
        context.settingsDataStore.edit { prefs -> prefs.clear() }
    }
}
