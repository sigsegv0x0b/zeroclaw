package ai.zeroclaw.app.data

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MtlsEnrollmentTest {

    private fun selfSigned(name: String, notBefore: Long = 1_000_000L, notAfter: Long = 9_000_000_000_000L): String {
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val holder = JcaX509v3CertificateBuilder(
            X500Name("CN=$name"),
            BigInteger.ONE,
            Date(notBefore),
            Date(notAfter),
            X500Name("CN=$name"),
            keys.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private))
        return Pem.encode("CERTIFICATE", holder.encoded)
    }

    private fun probe(caPem: String, code: String = "270391") = MtlsEnrollment.Probe(
        host = "gw.example",
        enrollPort = 42619,
        code = code,
        caPem = caPem,
        caFingerprint = MtlsEnrollment.caFingerprint(caPem),
        gatewayId = "gw_0011223344556677",
        sas = MtlsEnrollment.sas(code, caPem),
    )

    /** Same vector as `zeroclaw_tls` `enrollment_sas_is_deterministic_and_ca_sensitive`. */
    @Test
    fun sasMatchesTheGatewayVector() {
        assertEquals("6791-21FA", MtlsEnrollment.sasFromFingerprint("270391", "aa11bb22"))
        assertEquals("6791-21FA", MtlsEnrollment.sasFromFingerprint(" 270391 ", "AA11BB22"))
    }

    @Test
    fun aCaChainWithMoreThanOneCertificateIsRefused() {
        val chain = selfSigned("one") + selfSigned("two")
        try {
            MtlsEnrollment.caFingerprint(chain)
            fail("a second certificate must not ride along")
        } catch (expected: CertificateException) {
            assertTrue(expected.message.orEmpty().contains("exactly one"))
        }
    }

    @Test
    fun aQrPinnedCaMustMatchExactly() {
        val ca = selfSigned("gateway")
        val probe = probe(ca)
        val pinned = MtlsEnrollment.Expected(caFingerprint = probe.caFingerprint)
        assertTrue(pinned.pinsCa && pinned.verifiesSas)
        assertNull(MtlsEnrollment.mismatch(probe, pinned))

        val impostor = probe(selfSigned("impostor"))
        assertNotNull(MtlsEnrollment.mismatch(impostor, pinned))
    }

    @Test
    fun aQrGatewayIdAndSasAreChecked() {
        val probe = probe(selfSigned("gateway"))
        assertNotNull(
            MtlsEnrollment.mismatch(probe, MtlsEnrollment.Expected(gatewayId = "gw_other")),
        )
        assertNotNull(MtlsEnrollment.mismatch(probe, MtlsEnrollment.Expected(sas = "0000-0000")))
        assertNull(
            MtlsEnrollment.mismatch(
                probe,
                MtlsEnrollment.Expected(gatewayId = probe.gatewayId, sas = probe.sas.lowercase()),
            ),
        )
    }

    @Test
    fun aTypedCodeIsNotPreVerified() {
        val typed = MtlsEnrollment.Expected()
        assertTrue(!typed.pinsCa && !typed.verifiesSas)
        assertNull(MtlsEnrollment.mismatch(probe(selfSigned("gateway")), typed))
    }

    @Test
    fun validityComesFromTheCertificate() {
        val pem = selfSigned("leaf", notBefore = 1_700_000_000_000L, notAfter = 1_702_592_000_000L)
        assertEquals(1_700_000_000_000L to 1_702_592_000_000L, certificateValidity(pem))
        assertNull(certificateValidity(""))
        assertNull(certificateValidity("not a certificate"))
    }
}
