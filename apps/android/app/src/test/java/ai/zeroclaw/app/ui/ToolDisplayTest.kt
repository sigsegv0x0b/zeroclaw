package ai.zeroclaw.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDisplayTest {

    @Test
    fun `node tool shows the capability and phone origin`() {
        val display = toolDisplay("node_android-0c81d94a__timer_set")

        assertEquals("timer_set", display.name)
        assertEquals("phone", display.label)
        assertTrue(display.isNode)
    }

    @Test
    fun `server tool keeps its name and server origin`() {
        val display = toolDisplay("cron_add")

        assertEquals("cron_add", display.name)
        assertEquals("server", display.label)
        assertFalse(display.isNode)
    }

    @Test
    fun `malformed node prefix stays a server tool`() {
        val display = toolDisplay("node_without_separator")

        assertEquals("node_without_separator", display.name)
        assertFalse(display.isNode)
    }
}
