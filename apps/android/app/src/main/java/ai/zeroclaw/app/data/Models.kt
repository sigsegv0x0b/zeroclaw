package ai.zeroclaw.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Shared lenient JSON: the gateway adds fields as it evolves. */
val zcJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class AgentEntry(
    val alias: String,
    val enabled: Boolean = true,
)

@Serializable
data class AgentsResponse(
    val agents: List<AgentEntry> = emptyList(),
)

@Serializable
data class SessionEntry(
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_key") val sessionKey: String = sessionId,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_activity") val lastActivity: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    val name: String? = null,
    @SerialName("agent_alias") val agentAlias: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
) {
    /** Label shown in the sidebar: operator name, else a short id. */
    val label: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: sessionId.take(8)
}

@Serializable
data class MessageRow(
    val role: String,
    val content: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class MessagesResponse(
    @SerialName("session_id") val sessionId: String = "",
    val messages: List<MessageRow> = emptyList(),
    @SerialName("session_persistence") val persistence: Boolean = true,
)

@Serializable
data class PairRequest(
    val code: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_type") val deviceType: String = "android",
)

@Serializable
data class PairResponse(
    val token: String,
)

@Serializable
data class HealthResponse(
    val status: String = "",
    val version: String = "",
)

/** Connection lifecycle of the chat socket. */
sealed interface SocketState {
    data object Idle : SocketState
    data object Connecting : SocketState
    data object Open : SocketState
    data class Reconnecting(val attempt: Int) : SocketState
    data class Failed(val message: String) : SocketState
}

/** One frame from the gateway's chat WebSocket. */
sealed interface ChatEvent {
    data class SessionStart(val sessionId: String?, val resumed: Boolean, val messageCount: Int?) : ChatEvent
    data class Chunk(val content: String) : ChatEvent
    data class Thinking(val content: String) : ChatEvent
    data class ToolCall(val id: String?, val name: String, val args: JsonElement?) : ChatEvent
    data class ToolResult(val name: String?, val output: String) : ChatEvent
    data class Done(val fullResponse: String?) : ChatEvent
    data class Failure(val message: String, val code: String?) : ChatEvent
    data object Aborted : ChatEvent
    data class ApprovalRequest(
        val requestId: String,
        val tool: String,
        val argumentsSummary: String,
        val timeoutSecs: Int?,
    ) : ChatEvent
    data class HistoryTrimmed(val droppedMessages: Int?) : ChatEvent
    data class SafeguardFallback(val requested: String?, val served: String?) : ChatEvent
    data class Other(val type: String) : ChatEvent
}

data class ToolCallCard(
    val id: String,
    val name: String,
    val args: String?,
    val output: String? = null,
    val finished: Boolean = false,
)

enum class MessageRole { User, Assistant, System }

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String = "",
    val streaming: Boolean = false,
    val tools: List<ToolCallCard> = emptyList(),
    val thinking: String? = null,
    val isError: Boolean = false,
)

data class ApprovalPrompt(
    val requestId: String,
    val tool: String,
    val argumentsSummary: String,
    val timeoutSecs: Int?,
)

/** Stable id for a brand-new conversation; the gateway keys history by it. */
fun newSessionId(): String = java.util.UUID.randomUUID().toString()

/** Accept `host:port`, `http(s)://…` and `ws(s)://…` and return a REST base URL. */
fun normalizeBaseUrl(input: String): String {
    var value = input.trim().trimEnd('/')
    if (value.isEmpty()) return ""
    value = when {
        value.startsWith("wss://", ignoreCase = true) -> "https://" + value.substring(6)
        value.startsWith("ws://", ignoreCase = true) -> "http://" + value.substring(5)
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
        else -> "http://$value"
    }
    return value
}

fun webSocketUrl(baseUrl: String): String = when {
    baseUrl.startsWith("https://", ignoreCase = true) -> "wss://" + baseUrl.substring(8)
    baseUrl.startsWith("http://", ignoreCase = true) -> "ws://" + baseUrl.substring(7)
    else -> "ws://$baseUrl"
}
