package ai.zeroclaw.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide owner of the `/ws/chat` socket.
 *
 * The chat socket used to live in `AppViewModel`, so destroying the activity
 * (swipe-away, OS reclamation) also dropped the live turn, its approvals and
 * its questions. `NodeService` already keeps the process alive as a
 * `connectedDevice` foreground service; this object lets that service own the
 * chat connection too, while the UI attaches and detaches as a subscriber.
 *
 * It is the single owner of the socket and of the pending approval/question
 * used for notifications. The transcript itself stays in the UI and is
 * reloaded from the gateway when the activity returns.
 */
object ChatRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val socket = ChatSocket(scope)
    private val visible = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var lastConfig: ChatSocket.Config? = null
    private var pendingApproval: ApprovalPrompt? = null
    private var pendingElicitation: ElicitationPrompt? = null

    val events get() = socket.events
    val state get() = socket.state

    init {
        scope.launch { socket.events.collect(::onEvent) }
    }

    /** Idempotent: both the activity and the service call this. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setVisible(visible: Boolean) {
        this.visible.set(visible)
        if (visible) {
            cancelNotifications()
            return
        }
        // A prompt may have arrived while the app was still visible (the
        // operator sent a message, then switched away before the model asked).
        // Surface it now instead of waiting for another event.
        appContext?.let { context ->
            pendingApproval?.let { ApprovalNotifications.show(context, it) }
            pendingElicitation?.let { ApprovalNotifications.showQuestion(context, it) }
        }
    }

    fun updateHttpClient(client: okhttp3.OkHttpClient) {
        socket.updateHttpClient(client)
    }

    fun connect(config: ChatSocket.Config) {
        // The service and the UI both ask for a connection; only restart the
        // socket when something actually changed or it is not up.
        if (config == lastConfig && socket.isOpen()) return
        lastConfig = config
        socket.connect(config)
    }

    /**
     * Connect from persisted settings, used by `NodeService` on boot or after a
     * restart. Does nothing until the operator has paired and opened a chat.
     */
    suspend fun startFromSettings(context: Context) {
        init(context)
        val settings = SettingsStore(context).current()
        if (settings.gatewayUrl.isBlank() || settings.token.isBlank()) return
        if (settings.agent.isBlank() || settings.lastSessionId.isBlank()) return
        val client = runCatching { MtlsTransport.clientFor(settings) }.getOrNull() ?: return
        updateHttpClient(client)
        connect(
            ChatSocket.Config(
                baseUrl = settings.gatewayUrl,
                token = settings.token,
                sessionId = settings.lastSessionId,
                agent = settings.agent,
                nodeId = settings.deviceId,
                preferNodeTools = settings.preferNodeTools,
            ),
        )
    }

    fun close() {
        lastConfig = null
        socket.close()
        clearPending()
    }

    fun isOpen(): Boolean = socket.isOpen()

    fun sendMessage(text: String): Boolean = socket.sendMessage(text)

    fun sendApproval(requestId: String, decision: String): Boolean =
        socket.sendApproval(requestId, decision)

    fun sendElicitation(
        requestId: String,
        action: String,
        content: kotlinx.serialization.json.JsonObject? = null,
    ): Boolean = socket.sendElicitation(requestId, action, content)

    fun cancelApproval(requestId: String) {
        if (pendingApproval?.requestId == requestId) pendingApproval = null
        appContext?.let { ApprovalNotifications.cancel(it, requestId) }
    }

    fun cancelElicitation(requestId: String) {
        if (pendingElicitation?.requestId == requestId) pendingElicitation = null
        appContext?.let { ApprovalNotifications.cancel(it, requestId) }
    }

    private fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.ApprovalRequest -> {
                val prompt = ApprovalPrompt(
                    requestId = event.requestId,
                    tool = event.tool,
                    argumentsSummary = event.argumentsSummary,
                    timeoutSecs = event.timeoutSecs,
                )
                pendingApproval = prompt
                if (!visible.get()) appContext?.let { ApprovalNotifications.show(it, prompt) }
            }

            is ChatEvent.ElicitationRequest -> {
                pendingElicitation = event.prompt
                if (!visible.get()) {
                    appContext?.let { ApprovalNotifications.showQuestion(it, event.prompt) }
                }
            }

            is ChatEvent.ToolResult -> {
                val denied = toolDenialFromOutput(event.output) != null
                if (denied && (event.name == null || event.name == pendingApproval?.tool)) {
                    pendingApproval?.let { cancelApproval(it.requestId) }
                }
            }

            is ChatEvent.Done,
            is ChatEvent.Failure -> clearPending()

            ChatEvent.Aborted -> clearPending()

            else -> Unit
        }
    }

    private fun clearPending() {
        pendingApproval?.let { cancelApproval(it.requestId) }
        pendingElicitation?.let { cancelElicitation(it.requestId) }
        pendingApproval = null
        pendingElicitation = null
    }

    private fun cancelNotifications() {
        appContext?.let { context ->
            pendingApproval?.let { ApprovalNotifications.cancel(context, it.requestId) }
            pendingElicitation?.let { ApprovalNotifications.cancel(context, it.requestId) }
        }
    }
}
