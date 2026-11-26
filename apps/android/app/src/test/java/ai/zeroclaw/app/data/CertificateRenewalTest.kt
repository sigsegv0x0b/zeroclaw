package ai.zeroclaw.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CertificateRenewalTest {

    private fun settings(
        issuedAt: Long = 1_000_000L,
        notAfter: Long = issuedAt + 30L * 24 * 60 * 60 * 1000,
        enabled: Boolean = true,
        cert: String = "cert",
        key: String = "key",
    ) = StoredSettings(
        mtlsEnabled = enabled,
        mtlsClientCertPem = cert,
        mtlsClientKeyPem = key,
        mtlsIssuedAt = issuedAt,
        mtlsNotAfter = notAfter,
    )

    @Test
    fun midpointIsHalfwayThroughLifetime() {
        val s = settings(issuedAt = 100L, notAfter = 900L)
        assertEquals(500L, CertificateRenewal.midpoint(s))
    }

    @Test
    fun renewalIsDueAtMidpointButNotBefore() {
        val s = settings(issuedAt = 100L, notAfter = 900L)
        assertFalse(CertificateRenewal.isDue(s, now = 499L))
        assertTrue(CertificateRenewal.isDue(s, now = 500L))
        assertTrue(CertificateRenewal.isDue(s, now = 899L))
    }

    @Test
    fun missingMaterialOrDisabledTransportIsNeverDue() {
        val s = settings(issuedAt = 100L, notAfter = 900L)
        assertFalse(CertificateRenewal.isDue(s.copy(mtlsEnabled = false), now = 800L))
        assertFalse(CertificateRenewal.isDue(s.copy(mtlsClientCertPem = ""), now = 800L))
        assertFalse(CertificateRenewal.isDue(s.copy(mtlsClientKeyPem = ""), now = 800L))
        assertFalse(CertificateRenewal.isDue(s.copy(mtlsIssuedAt = 0L), now = 800L))
        assertFalse(CertificateRenewal.isDue(s.copy(mtlsNotAfter = 0L), now = 800L))
    }

    @Test
    fun aKeystoreKeyCountsAsClientKeyMaterial() {
        val s = settings(issuedAt = 100L, notAfter = 900L, key = "")
            .copy(mtlsClientKeyAlias = "zeroclaw-mtls-client-1")
        assertTrue(CertificateRenewal.isDue(s, now = 800L))
    }

    @Test
    fun mtlsWithBrokenMaterialNeverFallsBackToAPlainClient() {
        val broken = StoredSettings(mtlsEnabled = true, mtlsGatewayId = "gw_1")
        val client = MtlsTransport.clientFor(broken)
        val call = client.newCall(okhttp3.Request.Builder().url("https://gw.example/").build())
        try {
            call.execute()
            fail("the refusing client must not open a connection")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message.orEmpty().startsWith("mTLS transport unavailable"))
        }
    }
}
