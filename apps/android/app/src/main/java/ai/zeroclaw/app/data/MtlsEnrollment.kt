package ai.zeroclaw.app.data

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Pairing-code enrollment for the gateway mTLS listener.
 *
 * 1. Fetch the gateway CA over provisional TLS. Nothing secret is sent: the
 *    request carries no code and no token.
 * 2. Authenticate that CA before the code leaves the phone. A scanned QR
 *    carries the full CA fingerprint, which must match exactly; a typed code
 *    is checked by the operator comparing the SAS (code + CA fingerprint)
 *    with the gateway console.
 * 3. After the operator confirms, generate a non-exportable P-256 key in the
 *    Android Keystore and POST the code + CSR over a CA-pinned connection.
 * 4. Receive the signed client certificate, CA, gateway identity, and the
 *    bearer token bound to that certificate.
 */
object MtlsEnrollment {

    data class Probe(
        val host: String,
        val enrollPort: Int,
        val code: String,
        val caPem: String,
        val caFingerprint: String,
        val gatewayId: String,
        val sas: String,
    )

    /** The client identity a successful enrollment or renewal stores. */
    data class Identity(
        val caPem: String,
        val certPem: String,
        val keyAlias: String,
        val gatewayId: String,
        val deviceId: String,
    )

    data class Enrolled(val identity: Identity, val token: String)

    /** What a scanned QR promises about the gateway; blank fields are absent. */
    data class Expected(
        val caFingerprint: String? = null,
        val gatewayId: String? = null,
        val sas: String? = null,
    ) {
        /** A QR that pins the CA authenticates the gateway on its own. */
        val pinsCa: Boolean get() = !caFingerprint.isNullOrBlank()
        val verifiesSas: Boolean get() = pinsCa || !sas.isNullOrBlank()
    }

    suspend fun probe(
        host: String,
        enrollPort: Int,
        code: String,
    ): Probe = withContext(Dispatchers.IO) {
        val url = "https://$host:$enrollPort/enroll/ca"
        val response = trustAllClient().newCall(Request.Builder().url(url).get().build())
            .execute().use { it.body?.string().orEmpty() }
        val json = JSONObject(response)
        val caPem = json.getString("ca_chain_pem")
        val gatewayId = json.getString("gateway_id")
        val fingerprint = caFingerprint(caPem)
        Probe(
            host = host,
            enrollPort = enrollPort,
            code = code,
            caPem = caPem,
            caFingerprint = fingerprint,
            gatewayId = gatewayId,
            sas = sasFromFingerprint(code, fingerprint),
        )
    }

    /** Why `probe` does not match what the QR promised, or null when it does. */
    internal fun mismatch(probe: Probe, expected: Expected): String? {
        expected.caFingerprint?.takeIf { it.isNotBlank() }?.let { pinned ->
            if (!pinned.replace(":", "").equals(probe.caFingerprint, ignoreCase = true)) {
                return "the gateway presented a different CA than the QR code names"
            }
        }
        expected.gatewayId?.takeIf { it.isNotBlank() }?.let { id ->
            if (id != probe.gatewayId) {
                return "the gateway identity ${probe.gatewayId} is not the QR code's $id"
            }
        }
        expected.sas?.takeIf { it.isNotBlank() }?.let { sas ->
            if (!sas.equals(probe.sas, ignoreCase = true)) {
                return "the QR expects SAS $sas but the gateway presented ${probe.sas}"
            }
        }
        return null
    }

    suspend fun enroll(
        probe: Probe,
        deviceName: String,
    ): Enrolled = withContext(Dispatchers.IO) {
        val key = ClientKeys.generate(deviceName)
        try {
            val payload = JSONObject()
                .put("pairing_code", probe.code)
                .put("csr_pem", key.csrPem)
                .put("device_name", deviceName)
            val request = Request.Builder()
                .url("https://${probe.host}:${probe.enrollPort}/enroll")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val body = caPinnedClient(probe.caPem, probe.gatewayId)
                .newCall(request)
                .execute()
                .use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        error("Enrollment failed (HTTP ${response.code}): ${error ?: text}")
                    }
                    text
                }
            val json = JSONObject(body)
            val identity = verifiedIdentity(
                json = json,
                keyAlias = key.alias,
                publicKey = key.publicKey.encoded,
                expectedCaFingerprint = probe.caFingerprint,
                expectedGatewayId = probe.gatewayId,
            )
            Enrolled(identity, json.getString("token"))
        } catch (error: Exception) {
            ClientKeys.delete(key.alias)
            throw error
        }
    }

    /**
     * Authenticated renewal over the current client certificate and its bound
     * bearer token. A fresh Keystore key signs the CSR; the gateway keeps the
     * old certificate valid until the new one is first used, so a response
     * lost here is retried with the old certificate.
     */
    suspend fun renew(
        settings: StoredSettings,
        deviceName: String,
    ): Identity = withContext(Dispatchers.IO) {
        if (!settings.gatewayUrl.startsWith("https://", ignoreCase = true)) {
            error("renewal needs the https:// mTLS gateway address")
        }
        val transport = when (val built = MtlsTransport.build(settings)) {
            is MtlsTransport.Result.Ready -> built.built.client
            is MtlsTransport.Result.Unavailable -> error(built.reason)
        }
        val key = ClientKeys.generate(deviceName)
        try {
            val payload = JSONObject().put("csr_pem", key.csrPem)
            val request = Request.Builder()
                .url("${settings.gatewayUrl.trimEnd('/')}/api/mtls/renew")
                .header("Authorization", "Bearer ${settings.token}")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val body = transport.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw GatewayException(
                        response.code,
                        "Renewal failed (HTTP ${response.code}): ${error ?: text}",
                    )
                }
                text
            }
            verifiedIdentity(
                json = JSONObject(body),
                keyAlias = key.alias,
                publicKey = key.publicKey.encoded,
                expectedCaFingerprint = caFingerprint(settings.mtlsCaPem),
                expectedGatewayId = settings.mtlsGatewayId,
            )
        } catch (error: Exception) {
            ClientKeys.delete(key.alias)
            throw error
        }
    }

    /**
     * The gateway's answer must name the CA and identity already pinned, and
     * the certificate must carry this phone's new public key.
     */
    private fun verifiedIdentity(
        json: JSONObject,
        keyAlias: String,
        publicKey: ByteArray,
        expectedCaFingerprint: String,
        expectedGatewayId: String,
    ): Identity {
        val caPem = json.getString("ca_chain_pem")
        val certPem = json.getString("cert_pem")
        val gatewayId = json.getString("gateway_id")
        if (!caFingerprint(caPem).equals(expectedCaFingerprint, ignoreCase = true)) {
            error("the gateway returned a different CA than the one it was pinned to")
        }
        if (gatewayId != expectedGatewayId) {
            error("the gateway returned identity $gatewayId instead of $expectedGatewayId")
        }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certPem.byteInputStream()) as X509Certificate
        if (!certificate.publicKey.encoded.contentEquals(publicKey)) {
            error("the issued certificate does not carry this device's key")
        }
        return Identity(
            caPem = caPem,
            certPem = certPem,
            keyAlias = keyAlias,
            gatewayId = gatewayId,
            deviceId = json.getString("device_id"),
        )
    }

    /** Must match `zeroclaw_tls::enrollment_sas` exactly. */
    internal fun sas(code: String, caPem: String): String =
        sasFromFingerprint(code, caFingerprint(caPem))

    internal fun sasFromFingerprint(code: String, fingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("zeroclaw-enroll-sas-v1\u0000".toByteArray())
        digest.update(code.trim().toByteArray())
        digest.update(0)
        digest.update(fingerprint.trim().lowercase().toByteArray())
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        val value = hex.take(8).uppercase()
        return "${value.take(4)}-${value.drop(4)}"
    }

    /**
     * SHA-256 of the gateway CA. The PEM must hold exactly one certificate,
     * as the gateway requires, so a second certificate cannot ride along
     * unchecked.
     */
    internal fun caFingerprint(caPem: String): String {
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(caPem.byteInputStream())
        if (certificates.size != 1) {
            throw CertificateException("expected exactly one CA certificate, got ${certificates.size}")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(certificates.first().encoded)
            .joinToString("") { "%02x".format(it) }
    }

    /** Only for `GET /enroll/ca`, which carries no secret in either direction. */
    private fun trustAllClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun caPinnedClient(caPem: String, gatewayId: String): OkHttpClient {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(caPem.byteInputStream()) as X509Certificate
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("gateway-ca", certificate)
        }
        val base = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
        val trust = IdentityTrustManager(base, "spiffe://zeroclaw/gateway/$gatewayId")
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
