package ai.zeroclaw.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MtlsEnrollmentTest {

    @Test
    fun wireSecondsBecomeEpochMillis() {
        assertEquals(1_798_042_111_000L, MtlsEnrollment.notAfterMillis(1_798_042_111L))
        assertEquals(0L, MtlsEnrollment.notAfterMillis(0L))
        assertEquals(0L, MtlsEnrollment.notAfterMillis(-1L))
    }
}
