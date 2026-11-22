package ai.zeroclaw.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Device-node transport for `WS /ws/nodes`.
 *
 * The gateway exposes each advertised capability as an agent tool and sends
 * `invoke` frames here. Registration is a single frame on open; the gateway
 * keeps the node in its registry until the socket closes.
 */
class NodeClient(
    private val scope: CoroutineScope,
    private val onStatus: (NodeStatus) -> Unit,
    private val execute: suspend (capability: String, args: JsonObject) -> CapabilityOutcome,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build(),
) {
    data class Config(
        val baseUrl: String,
        val token: String,
        val nodeId: String,
    )

    private var socket: WebSocket? = null
    private var config: Config? = null
    private var closedByUser = true
    private var attempt = 0
    private var reconnectJob: Job? = null

    fun start(config: Config) {
        this.config = config
        closedByUser = false
        attempt = 0
        reconnectJob?.cancel()
        socket?.cancel()
        open()
    }

    fun stop() {
        closedByUser = true
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "client closed")
        socket = null
        onStatus(NodeStatus.Stopped)
    }

    private fun open() {
        val cfg = config ?: return
        val request = Request.Builder()
            .url("${webSocketUrl(cfg.baseUrl)}/ws/nodes")
            .header("Sec-WebSocket-Protocol", "zeroclaw.nodes.v1, bearer.${cfg.token}")
            .build()
        onStatus(NodeStatus.Connecting)
        socket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            val cfg = config ?: return
            attempt = 0
            val frame = RegisterFrame(
                nodeId = cfg.nodeId,
                capabilities = DeviceCapabilities.definitions,
            )
            webSocket.send(zcJson.encodeToString(RegisterFrame.serializer(), frame))
            onStatus(
                NodeStatus.Connected(
                    nodeId = cfg.nodeId,
                    capabilityCount = DeviceCapabilities.definitions.size,
                ),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            handleFrame(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            socket = null
            if (closedByUser) return
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            socket = null
            if (closedByUser) return
            onStatus(NodeStatus.Error(t.message ?: "Device link failed"))
            scheduleReconnect()
        }
    }

    private fun handleFrame(webSocket: WebSocket, text: String) {
        val frame = runCatching { zcJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        if (frame.str("type") != "invoke") return
        val callId = frame.str("call_id") ?: return
        val capability = frame.str("capability") ?: return
        val args = frame["args"] as? JsonObject ?: JsonObject(emptyMap())
        scope.launch {
            val outcome = runCatching { execute(capability, args) }.getOrElse { error ->
                CapabilityOutcome(false, "", error.message ?: "Invocation failed")
            }
            val result = InvokeResultFrame(
                callId = callId,
                success = outcome.success,
                output = outcome.output,
                error = outcome.error,
                attachments = outcome.attachments,
            )
            webSocket.send(zcJson.encodeToString(InvokeResultFrame.serializer(), result))
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
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
