package ai.zeroclaw.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Chat transport for `GET /ws/chat`.
 *
 * The gateway keys one conversation per socket. The client owns the session
 * id (the same id the REST history endpoints use) and reconnects with backoff;
 * a turn interrupted by a drop is not replayed, which matches the dashboard.
 */
class ChatSocket(
    private val scope: CoroutineScope,
    @Volatile private var http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build(),
) {
    /** Swap the transport (e.g. plain ws -> identity-based mTLS) on reconnect. */
    fun updateHttpClient(client: OkHttpClient) {
        http = client
    }

    data class Config(
        val baseUrl: String,
        val token: String,
        val sessionId: String,
        val agent: String,
        /** Local node id; scopes the gateway's phone tools to this device. */
        val nodeId: String = "",
    )

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow<SocketState>(SocketState.Idle)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var config: Config? = null
    private var closedByUser = true
    private var attempt = 0
    private var reconnectJob: Job? = null

    fun connect(config: Config) {
        this.config = config
        closedByUser = false
        attempt = 0
        reconnectJob?.cancel()
        socket?.cancel()
        open()
    }

    fun isOpen(): Boolean = _state.value is SocketState.Open

    fun sendMessage(text: String): Boolean {
        val ws = socket ?: return false
        val frame = zcJson.encodeToString(MessageFrame.serializer(), MessageFrame(content = text))
        return ws.send(frame)
    }

    fun sendApproval(requestId: String, decision: String): Boolean {
        val ws = socket ?: return false
        val frame = zcJson.encodeToString(
            ApprovalFrame.serializer(),
            ApprovalFrame(requestId = requestId, decision = decision),
        )
        return ws.send(frame)
    }

    fun close() {
        closedByUser = true
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "client closed")
        socket = null
        _state.value = SocketState.Idle
    }

    private fun open() {
        val cfg = config ?: return
        val url = buildString {
            append(webSocketUrl(cfg.baseUrl))
            append("/ws/chat?session_id=")
            append(encode(cfg.sessionId))
            append("&agent=")
            append(encode(cfg.agent))
        }
        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", "zeroclaw.v1, bearer.${cfg.token}")
            .apply {
                if (cfg.nodeId.isNotBlank()) {
                    header("X-ZeroClaw-Node-Id", cfg.nodeId)
                }
            }
            .build()
        _state.value = if (attempt == 0) SocketState.Connecting else SocketState.Reconnecting(attempt)
        socket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            attempt = 0
            _state.value = SocketState.Open
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val event = runCatching { parseFrame(text) }.getOrNull() ?: return
            _events.tryEmit(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // A superseded socket can report its close after a new connect();
            // only the current socket may drive reconnection.
            if (webSocket !== socket) return
            socket = null
            if (closedByUser) return
            _state.value = SocketState.Reconnecting(attempt + 1)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            socket = null
            if (closedByUser) return
            _state.value = SocketState.Failed(t.message ?: "Connection failed")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (closedByUser) return
        reconnectJob?.cancel()
        attempt += 1
        val capped = attempt.coerceAtMost(6)
        val waitMs = (1000L shl (capped - 1)).coerceAtMost(30_000L)
        reconnectJob = scope.launch {
            delay(waitMs)
            if (!closedByUser) open()
        }
    }

    private fun parseFrame(text: String): ChatEvent? {
        val root = zcJson.parseToJsonElement(text) as? JsonObject ?: return null
        val type = root.str("type") ?: return null
        return when (type) {
            "chunk", "message" -> root.str("content")?.let { ChatEvent.Chunk(it) }
            "thinking" -> ChatEvent.Thinking(root.str("content").orEmpty())
            "tool_call" -> ChatEvent.ToolCall(
                id = root.str("id"),
                name = root.str("name") ?: "tool",
                args = root["args"],
            )
            "tool_result" -> ChatEvent.ToolResult(
                name = root.str("name"),
                output = root.str("output").orEmpty(),
            )
            "done" -> ChatEvent.Done(root.str("full_response"))
            "error" -> ChatEvent.Failure(
                message = root.str("message") ?: "The gateway reported an error",
                code = root.str("code"),
            )
            "aborted" -> ChatEvent.Aborted
            "approval_request" -> ChatEvent.ApprovalRequest(
                requestId = root.str("request_id").orEmpty(),
                tool = root.str("tool") ?: "tool",
                argumentsSummary = root.str("arguments_summary").orEmpty(),
                timeoutSecs = root.str("timeout_secs")?.toIntOrNull(),
            )
            "session_start" -> ChatEvent.SessionStart(
                sessionId = root.str("session_id"),
                resumed = root.str("resumed") == "true",
                messageCount = root.str("message_count")?.toIntOrNull(),
            )
            "history_trimmed" -> ChatEvent.HistoryTrimmed(
                droppedMessages = root.str("dropped_messages")?.toIntOrNull(),
            )
            "safeguard_fallback" -> ChatEvent.SafeguardFallback(
                requested = root.str("requested_model"),
                served = root.str("served_model"),
            )
            else -> ChatEvent.Other(type)
        }
    }

    private fun JsonObject.str(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value.isString && value.content.isEmpty()) return null
        return value.content
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    @Serializable
    private data class MessageFrame(
        val type: String = "message",
        val content: String,
    )

    @Serializable
    private data class ApprovalFrame(
        val type: String = "approval_response",
        @SerialName("request_id") val requestId: String,
        val decision: String,
    )
}
