package ai.zeroclaw.app.data

import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Identity-based mTLS for the gateway transport.
 *
 * The gateway's server certificate carries only a URI SAN
 * (`spiffe://zeroclaw/gateway/<id>`), never a DNS name or IP: the address may be
 * a rotating public IP, a DDNS name, or a LAN address. So this transport pins
 * the gateway's CA, checks that URI identity, and disables OkHttp's hostname
 * check - which is safe only because the trust manager below verifies both the
 * chain and the identity. The client certificate proves this device.
 */
object MtlsTransport {

    data class Material(
        val caPem: String,
        val clientCertPem: String,
        val clientKeyPem: String,
        val gatewayId: String,
    )

    data class Built(
        val client: OkHttpClient,
        val identity: String,
    )

    sealed interface Result {
        data class Ready(val built: Built) : Result
        data class Unavailable(val reason: String) : Result
    }

    /** Build the transport from stored settings, or explain what is missing. */
    fun build(settings: StoredSettings): Result {
        if (!settings.mtlsEnabled) {
            return Result.Unavailable("mTLS transport is disabled")
        }
        if (settings.mtlsGatewayId.isBlank()) {
            return Result.Unavailable("gateway id is missing")
        }
        if (settings.mtlsCaPem.isBlank() ||
            settings.mtlsClientCertPem.isBlank() ||
            settings.mtlsClientKeyPem.isBlank()
        ) {
            return Result.Unavailable("import the CA, client certificate and client key")
        }
        return runCatching {
            val ca = certificates(settings.mtlsCaPem).firstOrNull()
                ?: error("CA PEM contains no certificate")
            val chain = certificates(settings.mtlsClientCertPem)
            if (chain.isEmpty()) error("client certificate PEM contains no certificate")
            val key = privateKey(settings.mtlsClientKeyPem)
            val identity = "spiffe://zeroclaw/gateway/${settings.mtlsGatewayId}"

            val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            keyStore.setKeyEntry("client", key, charArrayOf(), chain.toTypedArray())
            val keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm(),
            ).apply { init(keyStore, charArrayOf()) }.keyManagers

            val trustStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("gateway-ca", ca)
            }
            val base = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm(),
            ).apply { init(trustStore) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
                ?: error("no X509 trust manager")
            val trust = IdentityTrustManager(base, identity)

            val context = SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf(trust), null)
            }
            val client = OkHttpClient.Builder()
                .sslSocketFactory(context.socketFactory, trust)
                // Address is not identity: the trust manager already checked the
                // pinned CA and the URI SAN above, so hostname matching against a
                // rotating IP/DDNS name would only produce false negatives.
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
            Result.Ready(Built(client, identity))
        }.getOrElse { error ->
            Result.Unavailable(error.message ?: "could not build the mTLS transport")
        }
    }

    /** The plain transport, used when mTLS is off or its material is incomplete. */
    fun plainClient(): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /** Human-readable status for Settings. */
    fun describe(settings: StoredSettings): String = when (val result = build(settings)) {
        is Result.Ready -> "mTLS ready · ${result.built.identity}"
        is Result.Unavailable -> result.reason
    }

    private fun certificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return pem.byteInputStream().use { stream ->
            factory.generateCertificates(stream).map { it as X509Certificate }
        }
    }

    private fun privateKey(pem: String): PrivateKey {
        val body = pem
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s"), "")
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private class IdentityTrustManager(
        private val delegate: X509TrustManager,
        private val expectedIdentity: String,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkServerTrusted(chain, authType)
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("gateway presented no certificate")
            val uris = leaf.subjectAlternativeNames
                ?.filter { it.size >= 2 && it[0] == 6 }
                ?.map { it[1]?.toString().orEmpty() }
                .orEmpty()
            if (uris.none { it == expectedIdentity }) {
                throw CertificateException(
                    "gateway identity mismatch: expected $expectedIdentity, certificate URIs are " +
                        uris.joinToString(","),
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}
