package ai.zeroclaw.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    val nodeFolderUri: String = "",
    val nodeFolderName: String = "",
    /** `android` (on-phone recognizer) or `zeroclaw` (gateway transcription). */
    val voiceBackend: String = "android",
    val mtlsEnabled: Boolean = false,
    val mtlsGatewayId: String = "",
    val mtlsCaPem: String = "",
    val mtlsClientCertPem: String = "",
    val mtlsClientKeyPem: String = "",
    val mtlsIssuedAt: Long = 0,
    val mtlsNotAfter: Long = 0,
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
        val nodeFolderUri = stringPreferencesKey("node_folder_uri")
        val nodeFolderName = stringPreferencesKey("node_folder_name")
        val voiceBackend = stringPreferencesKey("voice_backend")
        val mtlsEnabled = booleanPreferencesKey("mtls_enabled")
        val mtlsGatewayId = stringPreferencesKey("mtls_gateway_id")
        val mtlsCaPem = stringPreferencesKey("mtls_ca_pem")
        val mtlsClientCertPem = stringPreferencesKey("mtls_client_cert_pem")
        val mtlsClientKeyPem = stringPreferencesKey("mtls_client_key_pem")
        val mtlsIssuedAt = longPreferencesKey("mtls_issued_at")
        val mtlsNotAfter = longPreferencesKey("mtls_not_after")
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
            nodeFolderUri = prefs[Keys.nodeFolderUri].orEmpty(),
            nodeFolderName = prefs[Keys.nodeFolderName].orEmpty(),
            voiceBackend = prefs[Keys.voiceBackend] ?: "android",
            mtlsEnabled = prefs[Keys.mtlsEnabled] ?: false,
            mtlsGatewayId = prefs[Keys.mtlsGatewayId].orEmpty(),
            mtlsCaPem = prefs[Keys.mtlsCaPem].orEmpty(),
            mtlsClientCertPem = prefs[Keys.mtlsClientCertPem].orEmpty(),
            mtlsClientKeyPem = prefs[Keys.mtlsClientKeyPem].orEmpty(),
            mtlsIssuedAt = prefs[Keys.mtlsIssuedAt] ?: 0L,
            // Early builds stored the server's epoch seconds here. Normalize
            // on read so renewal deadlines and the expiry label use millis.
            mtlsNotAfter = normalizeEpochMillis(prefs[Keys.mtlsNotAfter] ?: 0L),
        )
    }

    suspend fun current(): StoredSettings = settings.first()

    private fun normalizeEpochMillis(value: Long): Long =
        if (value in 1 until 1_000_000_000_000L) value * 1000L else value

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

    suspend fun saveNodeFolder(uri: String, name: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.nodeFolderUri] = uri
            prefs[Keys.nodeFolderName] = name
        }
    }

    suspend fun saveMtlsValidity(issuedAt: Long, notAfter: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.mtlsIssuedAt] = issuedAt
            prefs[Keys.mtlsNotAfter] = notAfter
        }
    }

    suspend fun saveMtlsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.mtlsEnabled] = enabled }
    }

    suspend fun saveMtlsGatewayId(id: String) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.mtlsGatewayId] = id.trim() }
    }

    suspend fun saveMtlsMaterial(ca: String?, cert: String?, key: String?) {
        context.settingsDataStore.edit { prefs ->
            ca?.let { prefs[Keys.mtlsCaPem] = it }
            cert?.let { prefs[Keys.mtlsClientCertPem] = it }
            key?.let { prefs[Keys.mtlsClientKeyPem] = it }
        }
    }

    suspend fun saveVoiceBackend(backend: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.voiceBackend] = backend
        }
    }

    suspend fun saveNodeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.nodeEnabled] = enabled }
    }

    suspend fun clear() {
        context.settingsDataStore.edit { prefs -> prefs.clear() }
    }
}
