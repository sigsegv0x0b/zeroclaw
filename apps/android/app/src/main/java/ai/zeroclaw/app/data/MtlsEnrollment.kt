package ai.zeroclaw.app.data

import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
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
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.json.JSONObject

/**
 * Pairing-code enrollment for the gateway mTLS listener.
 *
 * 1. Fetch the gateway CA over provisional TLS (no identity yet).
 * 2. Compute the SAS from the pairing code and CA fingerprint; the operator
 *    compares it with the gateway console or the QR payload.
 * 3. Generate a P-256 key and CSR locally, then POST the code + CSR over a
 *    CA-pinned connection.
 * 4. Receive the signed client certificate, CA chain, gateway identity, and a
 *    bearer token.
 */
object MtlsEnrollment {

    data class Probe(
        val host: String,
        val mtlsPort: Int,
        val enrollPort: Int,
        val code: String,
        val caPem: String,
        val gatewayId: String,
        val sas: String,
    )

    data class Result(
        val caPem: String,
        val certPem: String,
        val keyPem: String,
        val gatewayId: String,
        val deviceId: String,
        val token: String,
        val notAfter: Long,
    )

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
        Probe(
            host = host,
            mtlsPort = 42618,
            enrollPort = enrollPort,
            code = code,
            caPem = caPem,
            gatewayId = gatewayId,
            sas = sas(code, caPem),
        )
    }

    suspend fun enroll(
        probe: Probe,
        deviceName: String,
        mtlsPort: Int,
    ): Result = withContext(Dispatchers.IO) {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val csr = JcaPKCS10CertificationRequestBuilder(
            X500Name("CN=${deviceName.replace(",", "")}"),
            keyPair.public,
        ).build(signer)
        val csrPem = pem("CERTIFICATE REQUEST", csr.encoded)
        val keyPem = pem("PRIVATE KEY", keyPair.private.encoded)

        val payload = JSONObject()
            .put("pairing_code", probe.code)
            .put("csr_pem", csrPem)
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
        Result(
            caPem = json.getString("ca_chain_pem"),
            certPem = json.getString("cert_pem"),
            keyPem = keyPem,
            gatewayId = json.getString("gateway_id"),
            deviceId = json.getString("device_id"),
            token = json.getString("token"),
            notAfter = notAfterMillis(json.optLong("not_after", 0L)),
        )
    }

    /**
     * Authenticated renewal: prove the bearer token and the current client
     * certificate, submit a fresh CSR, and replace the stored material. The
     * server rotates its own leaf key and revokes the old client certificate.
     */
    suspend fun renew(
        settings: StoredSettings,
        deviceName: String,
    ): Result = withContext(Dispatchers.IO) {
        val transport = when (val built = MtlsTransport.build(settings)) {
            is MtlsTransport.Result.Ready -> built.built.client
            is MtlsTransport.Result.Unavailable -> error(built.reason)
        }
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val csr = JcaPKCS10CertificationRequestBuilder(
            X500Name("CN=${deviceName.replace(",", "")}"),
            keyPair.public,
        ).build(signer)
        val payload = JSONObject()
            .put("csr_pem", pem("CERTIFICATE REQUEST", csr.encoded))
            .put("device_name", deviceName)
        val request = Request.Builder()
            .url("${settings.gatewayUrl.trimEnd('/')}/api/mtls/renew")
            .header("Authorization", "Bearer ${settings.token}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body = transport.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
                error("Renewal failed (HTTP ${response.code}): ${error ?: text}")
            }
            text
        }
        val json = JSONObject(body)
        Result(
            caPem = json.getString("ca_chain_pem"),
            certPem = json.getString("cert_pem"),
            keyPem = pem("PRIVATE KEY", keyPair.private.encoded),
            gatewayId = json.getString("gateway_id"),
            deviceId = json.getString("device_id"),
            token = settings.token,
            notAfter = notAfterMillis(json.optLong("not_after", 0L)),
        )
    }

    /** The wire format and the certificate use epoch seconds; app state uses
     *  epoch milliseconds so it can be compared with `currentTimeMillis`. */
    internal fun notAfterMillis(epochSeconds: Long): Long =
        if (epochSeconds > 0) epochSeconds * 1000L else 0L

    /** Must match `zeroclaw_tls::enrollment_sas` exactly. */
    internal fun sas(code: String, caPem: String): String {
        val fingerprint = caFingerprint(caPem)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("zeroclaw-enroll-sas-v1\u0000".toByteArray())
        digest.update(code.trim().toByteArray())
        digest.update(0)
        digest.update(fingerprint.lowercase().toByteArray())
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        val value = hex.take(8).uppercase()
        return "${value.take(4)}-${value.drop(4)}"
    }

    internal fun caFingerprint(caPem: String): String {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(caPem.byteInputStream()) as X509Certificate
        return MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    private fun pem(label: String, der: ByteArray): String {
        val body = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

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
        val identity = "spiffe://zeroclaw/gateway/$gatewayId"
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                base.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                base.checkServerTrusted(chain, authType)
                val leaf = chain?.firstOrNull() ?: error("no server certificate")
                val uris = leaf.subjectAlternativeNames
                    ?.filter { it.size >= 2 && it[0] == 6 }
                    ?.map { it[1]?.toString().orEmpty() }
                    .orEmpty()
                if (uris.none { it == identity }) {
                    error("gateway identity mismatch: expected $identity")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
        }
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
