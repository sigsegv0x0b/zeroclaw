package ai.zeroclaw.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

data class ToolEntry(
    val name: String,
    val description: String = "",
)

enum class ToolPolicy {
    /** No explicit entry; the risk profile's autonomy level decides. */
    Default,
    /** Always ask before the tool runs. */
    Ask,
    /** Preapprove the tool. */
    Always,
    /** Make the tool unavailable to the agent. */
    Blocked,
}

data class ToolPolicyUi(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val entries: List<ToolEntry> = emptyList(),
    val riskProfile: String = "",
    val level: String = "",
    val autoApprove: Set<String> = emptySet(),
    val alwaysAsk: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
    val updating: String? = null,
    val error: String? = null,
) {
    fun policyFor(name: String): ToolPolicy = when {
        excluded.contains(name) -> ToolPolicy.Blocked
        alwaysAsk.contains("*") || alwaysAsk.contains(name) -> ToolPolicy.Ask
        autoApprove.contains("*") || autoApprove.contains(name) -> ToolPolicy.Always
        else -> ToolPolicy.Default
    }
}

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
data class UploadResponse(
    val path: String = "",
    val marker: String = "",
    val kind: String = "file",
    val name: String = "attachment",
    val size: Long = 0,
)

enum class AttachmentKind { Image, File }

/** One attachment on a chat message. */
data class MessageAttachment(
    val name: String,
    val path: String,
    val kind: AttachmentKind,
    /** Local content URI while this process still has read access. */
    val localUri: String? = null,
)

/** One attachment being uploaded from the composer. */
data class PendingAttachment(
    val id: String,
    val name: String,
    val kind: AttachmentKind,
    val localUri: String?,
    val marker: String = "",
    val path: String = "",
    val uploading: Boolean = false,
    val size: Long = 0,
)

private val AttachmentMarker = Regex(
    """\[(IMAGE|PHOTO|DOCUMENT|FILE|VIDEO|VOICE|AUDIO):([^\]]+)]""",
    RegexOption.IGNORE_CASE,
)

/** Split attachment markers out of a persisted user message. */
fun parseAttachmentMarkers(content: String): Pair<String, List<MessageAttachment>> {
    val attachments = mutableListOf<MessageAttachment>()
    val text = AttachmentMarker.replace(content) { match ->
        val kindName = match.groupValues[1].uppercase()
        val path = match.groupValues[2]
        val file = path.substringAfterLast('/').substringAfterLast('\\')
        val name = file
            .replace(Regex("^upload_[0-9a-fA-F]{32}_"), "")
            .ifBlank { "attachment" }
        attachments += MessageAttachment(
            name = name,
            path = path,
            kind = if (kindName == "IMAGE" || kindName == "PHOTO") {
                AttachmentKind.Image
            } else {
                AttachmentKind.File
            },
        )
        ""
    }.trim()
    return text to attachments
}

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
    data class ToolResult(val id: String?, val name: String?, val output: String) : ChatEvent
    data class Done(val fullResponse: String?) : ChatEvent
    data class Failure(val message: String, val code: String?) : ChatEvent
    data object Aborted : ChatEvent
    data class ApprovalRequest(
        val requestId: String,
        val tool: String,
        val argumentsSummary: String,
        val timeoutSecs: Int?,
    ) : ChatEvent
    data class ElicitationRequest(val prompt: ElicitationPrompt) : ChatEvent
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
    /** Optional qualifier shown after the tool name, e.g. `[maps.navigate]`. */
    val detail: String? = null,
)

/**
 * A short qualifier for a tool card title.
 *
 * `intent.launch` is one tool for every recipe, so the transcript would
 * otherwise show an anonymous `intent_launch` card. Naming the recipe makes
 * the call legible without changing the tool name that policy keys on.
 */
fun toolDetail(name: String, args: JsonElement?): String? {
    val capability = name.substringAfter("__", name)
    if (capability != "intent_launch" && capability != "intent.launch") return null
    val recipe = ((args as? JsonObject)?.get("recipe") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        .orEmpty()
    return recipe.takeIf { it.isNotEmpty() }?.let { "[$it]" }
}

/** One ordered piece of an assistant turn. */
sealed interface MessageBlock {
    data class Text(val text: String) : MessageBlock
    data class Thinking(val text: String) : MessageBlock
    data class Tool(val card: ToolCallCard) : MessageBlock
}

enum class MessageRole { User, Assistant, System }

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String = "",
    val streaming: Boolean = false,
    /**
     * Ordered assistant content. Text, reasoning, and tool cards share one
     * list so a tool call cannot jump below a later text block.
     */
    val blocks: List<MessageBlock> = emptyList(),
    val attachments: List<MessageAttachment> = emptyList(),
    val isError: Boolean = false,
) {
    /** Ordered blocks, with legacy plain text treated as one text block. */
    val contentBlocks: List<MessageBlock>
        get() = when {
            blocks.isNotEmpty() -> blocks
            text.isNotBlank() -> listOf(MessageBlock.Text(text))
            else -> emptyList()
        }

    val contentLength: Int
        get() = contentBlocks.sumOf { block ->
            when (block) {
                is MessageBlock.Text -> block.text.length
                is MessageBlock.Thinking -> block.text.length
                is MessageBlock.Tool -> 1
            }
        }

    val toolCards: List<ToolCallCard>
        get() = contentBlocks.filterIsInstance<MessageBlock.Tool>().map { it.card }
}

fun ChatMessage.appendText(chunk: String): ChatMessage {
    val next = contentBlocks.toMutableList()
    val last = next.lastOrNull()
    if (last is MessageBlock.Text) {
        next[next.lastIndex] = last.copy(text = last.text + chunk)
    } else if (chunk.isNotEmpty()) {
        next += MessageBlock.Text(chunk)
    }
    return copy(blocks = next, text = "", streaming = true)
}

fun ChatMessage.appendThinking(chunk: String): ChatMessage {
    if (chunk.isEmpty()) return this
    val next = contentBlocks.toMutableList()
    val last = next.lastOrNull()
    if (last is MessageBlock.Thinking) {
        next[next.lastIndex] = last.copy(text = last.text + chunk)
    } else {
        next += MessageBlock.Thinking(chunk)
    }
    return copy(blocks = next, text = "", streaming = true)
}

fun ChatMessage.appendTool(card: ToolCallCard): ChatMessage =
    copy(blocks = contentBlocks + MessageBlock.Tool(card), text = "", streaming = true)

fun ChatMessage.completeTool(id: String?, name: String?, output: String): ChatMessage {
    val next = contentBlocks.toMutableList()
    val byId = id?.let { wanted ->
        next.indexOfFirst { it is MessageBlock.Tool && it.card.id == wanted }
    }?.takeIf { it >= 0 }
    val byName = next.indexOfFirst {
        it is MessageBlock.Tool && !it.card.finished && (name == null || it.card.name == name)
    }.takeIf { it >= 0 }
    val target = byId ?: byName
        ?: next.indexOfFirst { it is MessageBlock.Tool && !it.card.finished }
    if (target < 0) return this
    val tool = next[target] as MessageBlock.Tool
    next[target] = MessageBlock.Tool(tool.card.copy(output = output, finished = true))
    return copy(blocks = next, text = "")
}

fun ChatMessage.finishStreaming(fullResponse: String?): ChatMessage {
    val next = contentBlocks.toMutableList()
    if (next.none { it is MessageBlock.Text } && !fullResponse.isNullOrBlank()) {
        next += MessageBlock.Text(fullResponse)
    }
    return copy(blocks = next, text = "", streaming = false)
}

fun ChatMessage.finishWith(reason: String, isError: Boolean): ChatMessage {
    val next = contentBlocks.toMutableList()
    if (reason.isNotBlank() && (next.lastOrNull() as? MessageBlock.Text)?.text != reason) {
        next += MessageBlock.Text(reason)
    }
    return copy(blocks = next, text = "", streaming = false, isError = isError)
}

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

/** True when the transport carries the token and chat frames in clear text. */
fun isInsecureHttp(baseUrl: String): Boolean =
    baseUrl.startsWith("http://", ignoreCase = true)

/**
 * Stable key for remembering one accepted cleartext gateway. The port is part
 * of the origin, so accepting HTTP for a development port does not silently
 * allow a different listener or host.
 */
fun httpOrigin(baseUrl: String): String {
    val uri = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull()
        ?: return baseUrl.trimEnd('/').lowercase()
    val host = uri.host ?: return baseUrl.trimEnd('/').lowercase()
    val port = if (uri.port > 0) uri.port else 80
    val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
    return "http://${hostPart.lowercase()}:$port"
}
