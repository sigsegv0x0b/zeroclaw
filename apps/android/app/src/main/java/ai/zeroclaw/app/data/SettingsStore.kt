package ai.zeroclaw.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "zeroclaw_settings")

/**
 * Serializes device-id generation across the app process. Bootstrap and the
 * node service both ask for the id at startup; without this lock they can read
 * a blank preference and persist two different ids, so the chat scopes its
 * phone tools to an id the node link never registered.
 */
private val deviceIdLock = Mutex()

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
    /** An imported PEM key. Enrollment never sets this; it uses [mtlsClientKeyAlias]. */
    val mtlsClientKeyPem: String = "",
    /** Android Keystore alias of the enrolled, non-exportable client key. */
    val mtlsClientKeyAlias: String = "",
    /** Validity of [mtlsClientCertPem], epoch millis, read from the certificate itself. */
    val mtlsIssuedAt: Long = 0,
    val mtlsNotAfter: Long = 0,
    /** Cleartext origins the operator explicitly accepted after a warning. */
    val insecureHttpOrigins: Set<String> = emptySet(),
) {
    val hasClientKey: Boolean
        get() = mtlsClientKeyAlias.isNotBlank() || mtlsClientKeyPem.isNotBlank()
}

/** `notBefore` and `notAfter` of the first certificate in `pem`, epoch millis. */
internal fun certificateValidity(pem: String): Pair<Long, Long>? {
    if (pem.isBlank()) return null
    return runCatching {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as X509Certificate
        certificate.notBefore.time to certificate.notAfter.time
    }.getOrNull()
}

/**
 * Connection state lives in app-private DataStore, which is excluded from
 * backup and device transfer. The bearer token and any imported key are
 * additionally sealed with a Keystore key ([SecretBox]); an enrolled client
 * key lives only in the Keystore ([ClientKeys]).
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
        val mtlsClientKeyAlias = stringPreferencesKey("mtls_client_key_alias")
        val insecureHttpOrigins = stringSetPreferencesKey("insecure_http_origins")

        // Written by earlier builds; the certificate is now the only source.
        val legacyIssuedAt = longPreferencesKey("mtls_issued_at")
        val legacyNotAfter = longPreferencesKey("mtls_not_after")
    }

    val settings: Flow<StoredSettings> = context.settingsDataStore.data.map { prefs ->
        val certPem = prefs[Keys.mtlsClientCertPem].orEmpty()
        val validity = certificateValidity(certPem)
        StoredSettings(
            gatewayUrl = prefs[Keys.gatewayUrl].orEmpty(),
            token = SecretBox.open(prefs[Keys.token].orEmpty()),
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
            mtlsClientCertPem = certPem,
            mtlsClientKeyPem = SecretBox.open(prefs[Keys.mtlsClientKeyPem].orEmpty()),
            mtlsClientKeyAlias = prefs[Keys.mtlsClientKeyAlias].orEmpty(),
            mtlsIssuedAt = validity?.first ?: 0L,
            mtlsNotAfter = validity?.second ?: 0L,
            insecureHttpOrigins = prefs[Keys.insecureHttpOrigins] ?: emptySet(),
        )
    }.flowOn(Dispatchers.IO)

    suspend fun current(): StoredSettings = settings.first()

    /**
     * Seal secrets that earlier builds stored in plain text and drop the
     * duplicated validity fields. Idempotent; run once per start.
     */
    suspend fun migrateSecrets() {
        context.settingsDataStore.edit { prefs ->
            for (key in listOf(Keys.token, Keys.mtlsClientKeyPem)) {
                val stored = prefs[key].orEmpty()
                if (stored.isNotEmpty() && !SecretBox.isSealed(stored)) {
                    prefs[key] = SecretBox.seal(stored)
                }
            }
            prefs.remove(Keys.legacyIssuedAt)
            prefs.remove(Keys.legacyNotAfter)
        }
    }

    suspend fun isInsecureOriginAllowed(origin: String): Boolean =
        settings.first().insecureHttpOrigins.contains(origin.lowercase())

    suspend fun allowInsecureOrigin(origin: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.insecureHttpOrigins] =
                (prefs[Keys.insecureHttpOrigins] ?: emptySet()) + origin.lowercase()
        }
    }

    suspend fun saveConnection(gatewayUrl: String, token: String) {
        val sealed = SecretBox.seal(token)
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.gatewayUrl] = gatewayUrl
            prefs[Keys.token] = sealed
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
    suspend fun ensureDeviceId(): String = deviceIdLock.withLock {
        val existing = settings.first().deviceId
        if (existing.isNotBlank()) return@withLock existing
        val generated = "android-" + java.util.UUID.randomUUID().toString().take(8)
        context.settingsDataStore.edit { prefs -> prefs[Keys.deviceId] = generated }
        generated
    }

    suspend fun saveNodeFolder(uri: String, name: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.nodeFolderUri] = uri
            prefs[Keys.nodeFolderName] = name
        }
    }

    suspend fun saveMtlsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.mtlsEnabled] = enabled }
    }

    suspend fun saveMtlsGatewayId(id: String) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.mtlsGatewayId] = id.trim() }
    }

    /**
     * Manually imported material. A provided key replaces an enrolled one, and
     * once both the certificate and its key are present they are imported into
     * the Keystore so the app no longer keeps an extractable PEM. If the
     * platform refuses the import, the sealed PEM remains the fallback.
     */
    suspend fun saveMtlsMaterial(ca: String?, cert: String?, key: String?) {
        if (ca == null && cert == null && key == null) return
        val before = settings.first()
        val sealedKey = key?.let(SecretBox::seal)
        context.settingsDataStore.edit { prefs ->
            ca?.let { prefs[Keys.mtlsCaPem] = it }
            cert?.let { prefs[Keys.mtlsClientCertPem] = it }
            if (sealedKey != null) {
                prefs[Keys.mtlsClientKeyPem] = sealedKey
                prefs.remove(Keys.mtlsClientKeyAlias)
            } else if (cert != null) {
                // A new certificate cannot use the old alias's key.
                prefs.remove(Keys.mtlsClientKeyAlias)
            }
        }

        // Import in either order: a key imported before its certificate waits
        // here until the certificate arrives.
        val current = settings.first()
        val imported = if (current.mtlsClientKeyAlias.isBlank() &&
            current.mtlsClientCertPem.isNotBlank() &&
            current.mtlsClientKeyPem.isNotBlank()
        ) {
            ClientKeys.importPem(current.mtlsClientCertPem, current.mtlsClientKeyPem)
        } else {
            null
        }
        if (imported != null) {
            context.settingsDataStore.edit { prefs ->
                prefs[Keys.mtlsClientKeyAlias] = imported
                prefs.remove(Keys.mtlsClientKeyPem)
            }
        }
        val keep = imported ?: settings.first().mtlsClientKeyAlias
        if (imported != null || cert != null || key != null) {
            ClientKeys.delete(before.mtlsClientKeyAlias.takeIf { it != keep }.orEmpty())
            ClientKeys.deleteAllExcept(keep)
        }
    }

    /**
     * Everything a successful enrollment produced, in one write, so a crash
     * can never leave a certificate stored without its key or its token.
     */
    suspend fun saveEnrolledIdentity(
        identity: MtlsEnrollment.Identity,
        gatewayUrl: String,
        token: String,
    ) {
        val sealedToken = SecretBox.seal(token)
        var previous = ""
        context.settingsDataStore.edit { prefs ->
            previous = writeIdentity(prefs, identity)
            prefs[Keys.mtlsEnabled] = true
            prefs[Keys.gatewayUrl] = gatewayUrl
            prefs[Keys.token] = sealedToken
        }
        ClientKeys.delete(previous.takeIf { it != identity.keyAlias }.orEmpty())
    }

    /** A renewed certificate and its new key alias, in one write. */
    suspend fun saveRenewedIdentity(identity: MtlsEnrollment.Identity) {
        var previous = ""
        context.settingsDataStore.edit { prefs -> previous = writeIdentity(prefs, identity) }
        ClientKeys.delete(previous.takeIf { it != identity.keyAlias }.orEmpty())
    }

    /** Returns the alias this replaces, so the caller can prune it after the write. */
    private fun writeIdentity(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        identity: MtlsEnrollment.Identity,
    ): String {
        val previous = prefs[Keys.mtlsClientKeyAlias].orEmpty()
        prefs[Keys.mtlsCaPem] = identity.caPem
        prefs[Keys.mtlsClientCertPem] = identity.certPem
        prefs[Keys.mtlsClientKeyAlias] = identity.keyAlias
        prefs.remove(Keys.mtlsClientKeyPem)
        prefs[Keys.mtlsGatewayId] = identity.gatewayId.trim()
        return previous
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
