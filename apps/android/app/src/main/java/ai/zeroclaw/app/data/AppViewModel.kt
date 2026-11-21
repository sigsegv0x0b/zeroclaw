package ai.zeroclaw.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.zeroclaw.app.service.NodeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val booting: Boolean = true,
    val gatewayUrl: String = "",
    val token: String = "",
    val deviceName: String = "Android",
    val deviceId: String = "",
    val nodeEnabled: Boolean = true,
    val nodeStatus: NodeStatus = NodeStatus.Stopped,
    val agents: List<AgentEntry> = emptyList(),
    val agent: String = "",
    val sessions: List<SessionEntry> = emptyList(),
    val sessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val socket: SocketState = SocketState.Idle,
    val busy: Boolean = false,
    val approval: ApprovalPrompt? = null,
    val error: String? = null,
    val notice: String? = null,
    val settingsOpen: Boolean = false,
    val drawerOpen: Boolean = false,
) {
    val paired: Boolean get() = gatewayUrl.isNotBlank() && token.isNotBlank()
    val connected: Boolean get() = socket is SocketState.Open
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val gateway = GatewayClient()
    private val socket = ChatSocket(viewModelScope)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            socket.events.collect(::handleEvent)
        }
        viewModelScope.launch {
            socket.state.collect { state ->
                _ui.update { it.copy(socket = state) }
            }
        }
        viewModelScope.launch {
            NodeRuntime.status.collect { status ->
                val wasConnected = _ui.value.nodeStatus is NodeStatus.Connected
                _ui.update { it.copy(nodeStatus = status) }
                // The agent's tool list is built when the chat socket opens.
                // Reopen it when the device link comes up so the phone's
                // capabilities become tools for the next turn.
                if (status is NodeStatus.Connected && !wasConnected) {
                    val snapshot = _ui.value
                    if (snapshot.connected && !snapshot.busy && snapshot.agent.isNotBlank()) {
                        socket.connect(
                            ChatSocket.Config(
                                baseUrl = snapshot.gatewayUrl,
                                token = snapshot.token,
                                sessionId = snapshot.sessionId,
                                agent = snapshot.agent,
                            ),
                        )
                    }
                }
            }
        }
        bootstrap()
    }

    // ── Startup ────────────────────────────────────────────────────────────

    private fun bootstrap() {
        viewModelScope.launch {
            val saved = runCatching { store.current() }.getOrNull()
            if (saved == null || saved.gatewayUrl.isBlank() || saved.token.isBlank()) {
                _ui.update { it.copy(booting = false) }
                return@launch
            }
            _ui.update {
                it.copy(
                    gatewayUrl = saved.gatewayUrl,
                    token = saved.token,
                    deviceName = saved.deviceName,
                    deviceId = runCatching { store.ensureDeviceId() }.getOrDefault(""),
                    nodeEnabled = saved.nodeEnabled,
                    booting = false,
                )
            }
            if (saved.nodeEnabled) {
                NodeService.start(getApplication())
            }
            loadWorkspace(saved.lastSessionId.ifBlank { null })
        }
    }

    /** Resolve agents, then open the last (or a fresh) conversation. */
    private fun loadWorkspace(preferredSession: String?) {
        viewModelScope.launch {
            val snapshot = _ui.value
            val agents = runCatching {
                gateway.agents(snapshot.gatewayUrl, snapshot.token)
            }.getOrElse { error ->
                if (error is GatewayException && error.status == 401) {
                    // The stored token was revoked or rotated: drop it so the
                    // shell returns to pairing instead of showing an empty app.
                    runCatching { store.clear() }
                    _ui.update {
                        UiState(
                            booting = false,
                            deviceName = it.deviceName,
                            error = "This device is no longer paired. Enter a pairing code again.",
                        )
                    }
                } else {
                    _ui.update { it.copy(error = error.userMessage()) }
                }
                return@launch
            }
            val enabled = agents.filter { it.enabled }
            val chosen = snapshot.agent.takeIf { alias -> enabled.any { it.alias == alias } }
                ?: enabled.firstOrNull()?.alias
                ?: agents.firstOrNull()?.alias
            if (chosen == null) {
                _ui.update {
                    it.copy(agents = agents, error = "No agents are configured on this gateway.")
                }
                return@launch
            }
            _ui.update { it.copy(agents = agents, agent = chosen) }
            reloadSessions()
            openSession(preferredSession ?: newSessionId(), agentAlias = chosen, loadHistory = preferredSession != null)
        }
    }

    private suspend fun reloadSessions() {
        val snapshot = _ui.value
        val sessions = runCatching {
            gateway.sessions(snapshot.gatewayUrl, snapshot.token)
        }.getOrElse { emptyList() }
        _ui.update { it.copy(sessions = sessions) }
    }

    /** Sidebar refresh: reload the list without touching the live socket. */
    fun refreshSessions() {
        viewModelScope.launch { reloadSessions() }
    }

    // ── Pairing ────────────────────────────────────────────────────────────

    fun pair(rawUrl: String, code: String, deviceName: String) {
        val base = normalizeBaseUrl(rawUrl)
        if (base.isBlank()) {
            _ui.update { it.copy(error = "Enter the gateway address first.") }
            return
        }
        if (code.isBlank()) {
            _ui.update { it.copy(error = "Enter the pairing code printed by the gateway.") }
            return
        }
        _ui.update { it.copy(booting = true, error = null) }
        viewModelScope.launch {
            val token = runCatching {
                if (!gateway.health(base)) throw GatewayException(0, "No gateway answered at $base.")
                gateway.pair(base, code, deviceName)
            }.getOrElse { error ->
                _ui.update { it.copy(booting = false, error = error.userMessage()) }
                return@launch
            }
            store.saveConnection(base, token)
            store.saveDeviceName(deviceName)
            val nodeId = runCatching { store.ensureDeviceId() }.getOrDefault("")
            if (_ui.value.nodeEnabled && nodeId.isNotBlank()) {
                NodeService.start(getApplication())
            }
            _ui.update {
                it.copy(
                    booting = false,
                    gatewayUrl = base,
                    token = token,
                    deviceName = deviceName,
                    deviceId = nodeId,
                    error = null,
                )
            }
            loadWorkspace(null)
        }
    }

    /** Settings can replace the token or address without re-pairing. */
    fun reconnect(rawUrl: String, token: String) {
        val base = normalizeBaseUrl(rawUrl)
        val trimmed = token.trim()
        if (base.isBlank() || trimmed.isBlank()) {
            _ui.update { it.copy(error = "Address and token are both required.") }
            return
        }
        viewModelScope.launch {
            store.saveConnection(base, trimmed)
            socket.close()
            val nodeId = runCatching { store.ensureDeviceId() }.getOrDefault("")
            if (_ui.value.nodeEnabled && nodeId.isNotBlank()) {
                NodeService.start(getApplication())
            }
            _ui.update {
                it.copy(
                    gatewayUrl = base,
                    token = trimmed,
                    deviceId = nodeId,
                    settingsOpen = false,
                    messages = emptyList(),
                    error = null,
                )
            }
            loadWorkspace(null)
        }
    }

    fun forgetGateway() {
        viewModelScope.launch {
            socket.close()
            NodeService.stop(getApplication())
            store.clear()
            _ui.update {
                UiState(booting = false, deviceName = it.deviceName)
            }
        }
    }

    /** Turn the device-node link (alarms, timers) on or off. */
    fun setNodeEnabled(enabled: Boolean) {
        _ui.update { it.copy(nodeEnabled = enabled) }
        viewModelScope.launch { store.saveNodeEnabled(enabled) }
        if (enabled) {
            NodeService.start(getApplication())
        } else {
            NodeService.stop(getApplication())
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    fun openSettings(open: Boolean) = _ui.update { it.copy(settingsOpen = open) }

    fun openDrawer(open: Boolean) = _ui.update { it.copy(drawerOpen = open) }

    // ── Conversations ──────────────────────────────────────────────────────

    fun newChat() {
        val alias = _ui.value.agent
        if (alias.isBlank()) {
            _ui.update { it.copy(error = "Pick an agent first.") }
            return
        }
        openSession(newSessionId(), agentAlias = alias, loadHistory = false)
    }

    fun selectAgent(alias: String) {
        if (alias == _ui.value.agent) return
        viewModelScope.launch { store.saveAgent(alias) }
        val existing = _ui.value.sessions.firstOrNull { it.agentAlias == alias }
        _ui.update { it.copy(agent = alias) }
        if (existing != null) {
            openSession(existing.sessionId, agentAlias = alias, loadHistory = true)
        } else {
            openSession(newSessionId(), agentAlias = alias, loadHistory = false)
        }
    }

    fun openSession(sessionId: String, agentAlias: String = _ui.value.agent, loadHistory: Boolean) {
        if (agentAlias.isBlank()) return
        socket.close()
        _ui.update {
            it.copy(
                sessionId = sessionId,
                agent = agentAlias,
                messages = emptyList(),
                busy = false,
                approval = null,
                error = null,
                drawerOpen = false,
            )
        }
        viewModelScope.launch {
            store.saveLastSession(sessionId)
            if (loadHistory) {
                val snapshot = _ui.value
                val history = runCatching {
                    gateway.messages(snapshot.gatewayUrl, snapshot.token, sessionId)
                }.getOrNull()?.messages.orEmpty().toChatMessages()
                _ui.update { it.copy(messages = history) }
            }
            socket.connect(
                ChatSocket.Config(
                    baseUrl = _ui.value.gatewayUrl,
                    token = _ui.value.token,
                    sessionId = sessionId,
                    agent = agentAlias,
                ),
            )
        }
    }

    fun reconnectSocket() {
        val snapshot = _ui.value
        if (snapshot.agent.isBlank() || snapshot.sessionId.isBlank()) return
        socket.connect(
            ChatSocket.Config(
                baseUrl = snapshot.gatewayUrl,
                token = snapshot.token,
                sessionId = snapshot.sessionId,
                agent = snapshot.agent,
            ),
        )
    }

    // ── Turns ──────────────────────────────────────────────────────────────

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        val snapshot = _ui.value
        if (snapshot.agent.isBlank()) {
            _ui.update { it.copy(error = "Pick an agent first.") }
            return
        }
        if (!socket.isOpen()) {
            _ui.update { it.copy(error = "Not connected to the gateway yet.") }
            return
        }
        val userMessage = ChatMessage(id = newId(), role = MessageRole.User, text = content)
        val assistant = ChatMessage(id = newId(), role = MessageRole.Assistant, streaming = true)
        _ui.update {
            it.copy(messages = it.messages + userMessage + assistant, busy = true, error = null)
        }
        if (!socket.sendMessage(content)) {
            finishAssistant("The message could not be sent.")
        }
    }

    fun stop() {
        val snapshot = _ui.value
        if (snapshot.sessionId.isBlank()) return
        viewModelScope.launch {
            gateway.abort(snapshot.gatewayUrl, snapshot.token, snapshot.sessionId)
        }
        _ui.update { it.copy(busy = false) }
    }

    fun answerApproval(approve: Boolean, always: Boolean = false) {
        val prompt = _ui.value.approval ?: return
        val decision = when {
            !approve -> "deny"
            always -> "always"
            else -> "approve"
        }
        socket.sendApproval(prompt.requestId, decision)
        _ui.update { it.copy(approval = null) }
    }

    // ── Socket events ──────────────────────────────────────────────────────

    private fun handleEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Chunk -> appendToAssistant(event.content)
            is ChatEvent.Thinking -> updateAssistant { it.copy(thinking = event.content) }
            is ChatEvent.ToolCall -> addToolCall(event)
            is ChatEvent.ToolResult -> completeToolCall(event)
            is ChatEvent.Done -> {
                _ui.update { state ->
                    val messages = state.messages.map { message ->
                        if (message.streaming && message.text.isBlank() && !event.fullResponse.isNullOrBlank()) {
                            message.copy(text = event.fullResponse, streaming = false)
                        } else {
                            message.copy(streaming = false)
                        }
                    }
                    state.copy(messages = messages, busy = false)
                }
                viewModelScope.launch { reloadSessions() }
            }
            is ChatEvent.Failure -> finishAssistant(event.message, isError = true)
            ChatEvent.Aborted -> {
                updateAssistant { it.copy(streaming = false) }
                _ui.update { it.copy(busy = false, notice = "Turn stopped.") }
            }
            is ChatEvent.ApprovalRequest -> _ui.update {
                it.copy(
                    approval = ApprovalPrompt(
                        requestId = event.requestId,
                        tool = event.tool,
                        argumentsSummary = event.argumentsSummary,
                        timeoutSecs = event.timeoutSecs,
                    ),
                )
            }
            is ChatEvent.HistoryTrimmed -> _ui.update {
                it.copy(notice = "Older context was trimmed to fit the model window.")
            }
            is ChatEvent.SafeguardFallback -> _ui.update {
                it.copy(
                    notice = buildString {
                        append("Model fallback")
                        if (event.requested != null) append(": ${event.requested}")
                        if (event.served != null) append(" → ${event.served}")
                    },
                )
            }
            is ChatEvent.SessionStart -> Unit
            is ChatEvent.Other -> Unit
        }
    }

    private fun appendToAssistant(chunk: String) {
        _ui.update { state ->
            val messages = state.messages.toMutableList()
            val index = messages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                val message = messages[index]
                messages[index] = message.copy(
                    text = message.text + chunk,
                    streaming = true,
                    thinking = null,
                )
            } else {
                messages += ChatMessage(
                    id = newId(),
                    role = MessageRole.Assistant,
                    text = chunk,
                    streaming = true,
                )
            }
            state.copy(messages = messages, busy = true)
        }
    }

    private fun updateAssistant(transform: (ChatMessage) -> ChatMessage) {
        _ui.update { state ->
            val messages = state.messages.toMutableList()
            val index = messages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                messages[index] = transform(messages[index])
            }
            state.copy(messages = messages)
        }
    }

    private fun addToolCall(event: ChatEvent.ToolCall) {
        _ui.update { state ->
            val messages = state.messages.toMutableList()
            var index = messages.indexOfLast { it.role == MessageRole.Assistant }
            if (index < 0) {
                messages += ChatMessage(id = newId(), role = MessageRole.Assistant, streaming = true)
                index = messages.lastIndex
            }
            val message = messages[index]
            val card = ToolCallCard(
                id = event.id ?: newId(),
                name = event.name,
                args = event.args?.shortJson(),
            )
            messages[index] = message.copy(tools = message.tools + card)
            state.copy(messages = messages, busy = true)
        }
    }

    private fun completeToolCall(event: ChatEvent.ToolResult) {
        _ui.update { state ->
            val messages = state.messages.toMutableList()
            val index = messages.indexOfLast { it.role == MessageRole.Assistant }
            if (index < 0) return@update state
            val message = messages[index]
            val tools = message.tools.toMutableList()
            val target = tools.indexOfLast { !it.finished && (event.name == null || it.name == event.name) }
                .takeIf { it >= 0 }
                ?: tools.indexOfLast { !it.finished }
            if (target >= 0) {
                tools[target] = tools[target].copy(output = event.output, finished = true)
            }
            messages[index] = message.copy(tools = tools)
            state.copy(messages = messages)
        }
    }

    private fun finishAssistant(reason: String, isError: Boolean = false) {
        _ui.update { state ->
            val messages = state.messages.toMutableList()
            val index = messages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                val message = messages[index]
                messages[index] = message.copy(
                    text = if (message.text.isBlank()) reason else message.text,
                    streaming = false,
                    isError = isError,
                )
            } else {
                messages += ChatMessage(
                    id = newId(),
                    role = MessageRole.Assistant,
                    text = reason,
                    isError = isError,
                )
            }
            state.copy(messages = messages, busy = false)
        }
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString()

    override fun onCleared() {
        socket.close()
        super.onCleared()
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is GatewayException -> when (status) {
        401, 403 -> "The gateway rejected the token. Pair the device again."
        404 -> "That route is not on this gateway build."
        else -> message ?: "Gateway error $status"
    }
    else -> message ?: "Could not reach the gateway."
}

private fun kotlinx.serialization.json.JsonElement.shortJson(): String {
    val text = toString()
    return if (text.length > 400) text.take(400) + "…" else text
}
