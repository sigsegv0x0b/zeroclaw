package ai.zeroclaw.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun `accepts host and port input`() {
        assertEquals("http://192.168.1.20:42617", normalizeBaseUrl("192.168.1.20:42617"))
        assertEquals("http://192.168.1.20:42617", normalizeBaseUrl(" 192.168.1.20:42617/ "))
    }

    @Test
    fun `preserves explicit schemes`() {
        assertEquals("https://gw.example.com", normalizeBaseUrl("https://gw.example.com/"))
        assertEquals("http://10.0.2.2:42617", normalizeBaseUrl("ws://10.0.2.2:42617"))
        assertEquals("https://gw.example.com", normalizeBaseUrl("wss://gw.example.com"))
    }

    @Test
    fun `derives websocket url from rest base`() {
        assertEquals("ws://127.0.0.1:42617", webSocketUrl("http://127.0.0.1:42617"))
        assertEquals("wss://gw.example.com", webSocketUrl("https://gw.example.com"))
    }

    @Test
    fun `parses a bare session array`() {
        val body = """
            [{"session_id":"abc","session_key":"gw_abc","message_count":3,"agent_alias":"main"}]
        """.trimIndent()
        val sessions = parseSessions(body)
        assertEquals(1, sessions.size)
        assertEquals("abc", sessions[0].sessionId)
        assertEquals(3, sessions[0].messageCount)
        assertEquals("main", sessions[0].agentAlias)
    }

    @Test
    fun `parses a wrapped session object and ignores unknown fields`() {
        val body = """
            {"sessions":[{"session_id":"x","session_key":"gw_x","future_field":true}],"total":1}
        """.trimIndent()
        val sessions = parseSessions(body)
        assertEquals(1, sessions.size)
        assertEquals("gw_x", sessions[0].sessionKey)
    }

    @Test
    fun `skips malformed session rows instead of failing the list`() {
        val body = """[{"message_count":2},{"session_id":"ok"}]"""
        val sessions = parseSessions(body)
        assertEquals(1, sessions.size)
        assertEquals("ok", sessions[0].sessionId)
    }

    @Test
    fun `labels fall back to a short id`() {
        val entry = SessionEntry(sessionId = "1234567890")
        assertEquals("12345678", entry.label)
        assertEquals("1234567890", entry.sessionKey)
    }

    @Test
    fun `history rows map roles and drop empty content`() {
        val messages = listOf(
            MessageRow(role = "user", content = "hi"),
            MessageRow(role = "assistant", content = "hello"),
            MessageRow(role = "system", content = ""),
            MessageRow(role = "tool", content = "raw"),
        ).toChatMessages()

        assertEquals(3, messages.size)
        assertEquals(MessageRole.User, messages[0].role)
        assertEquals(MessageRole.Assistant, messages[1].role)
        assertEquals(MessageRole.System, messages.last().role)
        assertTrue(messages.none { it.text.isBlank() })
    }
}
