package ai.zeroclaw.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder

/**
 * The mTLS client key, generated inside the Android Keystore.
 *
 * The private key never exists outside the Keystore (TEE-backed where the
 * device has one): it cannot be read by the app, copied by a backup, or moved
 * to another phone. Only an opaque alias is stored in settings. Each renewal
 * generates a key under a fresh alias, so the key in use is only deleted after
 * its replacement certificate is saved.
 */
object ClientKeys {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "zeroclaw-mtls-client-"
    private const val SIGNATURE = "SHA256withECDSA"

    /** A new non-exportable P-256 signing key, its public half, and a CSR for it. */
    class Generated(val alias: String, val publicKey: PublicKey, val csrPem: String)

    fun generate(deviceName: String): Generated {
        val alias = ALIAS_PREFIX + System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // TLS signs with the digest the handshake negotiates; NONE lets
                // the platform's TLS stack pass a precomputed digest.
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                )
                .build(),
        )
        val keyPair = generator.generateKeyPair()
        return try {
            Generated(alias, keyPair.public, csrPem(deviceName, keyPair.public, keyPair.private))
        } catch (error: Exception) {
            delete(alias)
            throw error
        }
    }

    fun privateKey(alias: String): PrivateKey? = runCatching {
        keyStore().getKey(alias, null) as? PrivateKey
    }.getOrNull()

    /**
     * Import an existing PEM key into the Keystore and return its alias, or
     * `null` when the platform refuses the key. Imported keys are software
     * protected unless the device supports hardware import, but they are no
     * longer a plaintext PEM the app can read back; the caller keeps the sealed
     * PEM as a fallback when this returns `null`.
     */
    fun importPem(certPem: String, keyPem: String): String? = runCatching {
        val chain = Pem.certificates(certPem)
        require(chain.isNotEmpty()) { "the client certificate PEM contains no certificate" }
        val key = Pem.privateKey(keyPem)
        val alias = ALIAS_PREFIX + "import-" + System.currentTimeMillis()
        val store = keyStore()
        val entry = KeyStore.PrivateKeyEntry(key, chain.toTypedArray())
        val protections = listOf(
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                )
                .build(),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        var imported = false
        for (protection in protections) {
            if (runCatching { store.setEntry(alias, entry, protection) }.isSuccess) {
                imported = true
                break
            }
        }
        if (!imported) {
            // Older platform implementations accept the legacy entry form.
            store.setKeyEntry(alias, key, null, chain.toTypedArray())
        }
        alias
    }.getOrNull()

    fun delete(alias: String) {
        if (alias.isBlank()) return
        runCatching { keyStore().deleteEntry(alias) }
    }

    /** Remove every client key except `keep` (blank removes them all). */
    fun deleteAllExcept(keep: String) {
        runCatching {
            val store = keyStore()
            store.aliases().toList()
                .filter { it.startsWith(ALIAS_PREFIX) && it != keep }
                .forEach { store.deleteEntry(it) }
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** The CSR subject is only a hint; the gateway assigns the identity. */
    internal fun csrPem(deviceName: String, publicKey: PublicKey, privateKey: PrivateKey): String {
        val subject = deviceName.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .trim()
            .take(64)
            .ifBlank { "Android" }
        val csr = JcaPKCS10CertificationRequestBuilder(X500Name("CN=$subject"), publicKey)
            .build(PlatformSigner(privateKey))
        return Pem.encode("CERTIFICATE REQUEST", csr.encoded)
    }

    /**
     * Signs through `java.security.Signature`, so the platform routes a
     * Keystore key to the Keystore provider instead of BouncyCastle trying to
     * read key material it cannot see.
     */
    private class PlatformSigner(key: PrivateKey) : ContentSigner {
        private val signature = Signature.getInstance(SIGNATURE).apply { initSign(key) }
        private val buffer = ByteArrayOutputStream()

        override fun getAlgorithmIdentifier(): AlgorithmIdentifier =
            DefaultSignatureAlgorithmIdentifierFinder().find(SIGNATURE)

        override fun getOutputStream(): OutputStream = buffer

        override fun getSignature(): ByteArray {
            signature.update(buffer.toByteArray())
            return signature.sign()
        }
    }
}

/**
 * Presents exactly one client identity. Works for a Keystore key, whose
 * material is not extractable and so cannot be placed in a PKCS#12 store.
 */
internal class SingleIdentityKeyManager(
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = ALIAS

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = ALIAS

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
        arrayOf(ALIAS)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) chain else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    private companion object {
        const val ALIAS = "client"
    }
}

internal object Pem {
    fun encode(label: String, der: ByteArray): String {
        val body = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    fun certificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return pem.byteInputStream().use { stream ->
            factory.generateCertificates(stream).map { it as X509Certificate }
        }
    }

    fun privateKey(pem: String): PrivateKey {
        val body = pem
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s"), "")
        val der = Base64.getDecoder().decode(body)
        val spec = PKCS8EncodedKeySpec(der)
        return runCatching { KeyFactory.getInstance("EC").generatePrivate(spec) }
            .recoverCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
            .getOrThrow()
    }
}
