package ai.zeroclaw.app.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.zeroclaw.app.service.NodeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A press shorter than this is a tap: let silence detection finish the turn. */
private const val VOICE_QUICK_TAP_MS = 400L

data class UiState(
    val booting: Boolean = true,
    val gatewayUrl: String = "",
    val token: String = "",
    val deviceName: String = "Android",
    val deviceId: String = "",
    val nodeEnabled: Boolean = true,
    val nodeFolderName: String = "",
    val voiceBackend: String = "android",
    val voiceListening: Boolean = false,
    val voiceError: String? = null,
    val mtlsEnabled: Boolean = false,
    val mtlsGatewayId: String = "",
    val mtlsLoaded: Boolean = false,
    val mtlsStatus: String = "",
    val mtlsNotAfter: Long = 0,
    val secureEnrolling: Boolean = false,
    val secureSas: String? = null,
    val nodeStatus: NodeStatus = NodeStatus.Stopped,
    val agents: List<AgentEntry> = emptyList(),
    val agent: String = "",
    val sessions: List<SessionEntry> = emptyList(),
    val sessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val socket: SocketState = SocketState.Idle,
    val busy: Boolean = false,
    val approval: ApprovalPrompt? = null,
    val choice: ChoicePrompt? = null,
    val error: String? = null,
    val notice: String? = null,
    val insecurePrompt: InsecureConnectionPrompt? = null,
    val settingsOpen: Boolean = false,
    val toolPolicy: ToolPolicyUi = ToolPolicyUi(),
    val drawerOpen: Boolean = false,
) {
    val paired: Boolean get() = gatewayUrl.isNotBlank() && token.isNotBlank()
    val connected: Boolean get() = socket is SocketState.Open
}

/** Shown once per cleartext origin before the app sends a token over HTTP. */
data class InsecureConnectionPrompt(val origin: String)

private sealed interface PendingConnection {
    val base: String

    data class Pair(
        override val base: String,
        val code: String,
        val deviceName: String,
    ) : PendingConnection

    data class Reconnect(
        override val base: String,
        val token: String,
    ) : PendingConnection

    data class Startup(val settings: StoredSettings) : PendingConnection {
        override val base: String get() = settings.gatewayUrl
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val gateway = GatewayClient()
    private val socket = ChatSocket(viewModelScope)
    private val androidVoice = AndroidVoiceInput(app)
    private val gatewayVoice = GatewayVoiceInput(app)
    private var pendingProbe: MtlsEnrollment.Probe? = null
    private var pendingMtlsPort: Int = 42618
    private var pendingInsecure: PendingConnection? = null
    private var appliedClientCertPem: String = ""

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        androidVoice.listener = voiceListener()
        gatewayVoice.listener = voiceListener()
        viewModelScope.launch {
            socket.events.collect(::handleEvent)
        }
        viewModelScope.launch {
            socket.state.collect { state ->
                _ui.update { it.copy(socket = state) }
            }
        }
        // A background worker may rotate the certificate while the process is
        // alive. Adopt the new material immediately so any later reconnect
        // does not present the revoked leaf.
        viewModelScope.launch {
            store.settings.collect { saved ->
                if (saved.mtlsClientCertPem.isBlank() ||
                    saved.mtlsClientCertPem == appliedClientCertPem
                ) {
                    return@collect
                }
                applyMtlsTransport(saved)
                _ui.update {
                    it.copy(
                        mtlsEnabled = saved.mtlsEnabled,
                        mtlsGatewayId = saved.mtlsGatewayId,
                        mtlsLoaded = true,
                        mtlsStatus = MtlsTransport.describe(saved),
                        mtlsNotAfter = saved.mtlsNotAfter,
                    )
                }
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
                                nodeId = snapshot.deviceId,
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
                    nodeFolderName = saved.nodeFolderName,
                    voiceBackend = saved.voiceBackend,
                    mtlsEnabled = saved.mtlsEnabled,
                    mtlsGatewayId = saved.mtlsGatewayId,
                    mtlsLoaded = saved.mtlsCaPem.isNotBlank() &&
                        saved.mtlsClientCertPem.isNotBlank() &&
                        saved.mtlsClientKeyPem.isNotBlank(),
                    mtlsStatus = MtlsTransport.describe(saved),
                    mtlsNotAfter = saved.mtlsNotAfter,
                    booting = false,
                )
            }
            val startup = PendingConnection.Startup(saved)
            if (!confirmCleartext(startup)) return@launch
            performStartup(saved)
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
                    // Do not erase the pairing on one 401. A gateway that is
                    // still starting, or a token that was temporarily
                    // unavailable, would otherwise force a re-pair. Keep the
                    // material and point the operator at the recovery path.
                    _ui.update {
                        it.copy(
                            error = "The gateway rejected this device's token. It may have " +
                                "been revoked or replaced. Open Settings and forget the " +
                                "gateway, then pair again if this persists.",
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
        requestConnection(PendingConnection.Pair(base, code, deviceName))
    }

    /** Settings can replace the token or address without re-pairing. */
    fun reconnect(rawUrl: String, token: String) {
        val base = normalizeBaseUrl(rawUrl)
        val trimmed = token.trim()
        if (base.isBlank() || trimmed.isBlank()) {
            _ui.update { it.copy(error = "Address and token are both required.") }
            return
        }
        requestConnection(PendingConnection.Reconnect(base, trimmed))
    }

    /**
     * Cleartext carries the bearer token and chat frames without encryption.
     * Ask once per origin, then remember the decision; HTTPS never prompts.
     */
    private suspend fun confirmCleartext(request: PendingConnection): Boolean {
        if (!isInsecureHttp(request.base)) return true
        val origin = httpOrigin(request.base)
        if (store.isInsecureOriginAllowed(origin)) return true
        pendingInsecure = request
        _ui.update { it.copy(insecurePrompt = InsecureConnectionPrompt(origin)) }
        return false
    }

    fun acceptInsecureConnection() {
        val request = pendingInsecure ?: return
        viewModelScope.launch {
            store.allowInsecureOrigin(httpOrigin(request.base))
            pendingInsecure = null
            _ui.update { it.copy(insecurePrompt = null) }
            perform(request)
        }
    }

    fun rejectInsecureConnection() {
        pendingInsecure = null
        _ui.update {
            it.copy(
                insecurePrompt = null,
                error = "Connection cancelled. Use https:// or Secure setup (mTLS) " +
                    "to protect this phone.",
            )
        }
    }

    private fun requestConnection(request: PendingConnection) {
        viewModelScope.launch {
            if (!confirmCleartext(request)) return@launch
            perform(request)
        }
    }

    private suspend fun perform(request: PendingConnection) {
        when (request) {
            is PendingConnection.Pair -> performPair(request)
            is PendingConnection.Reconnect -> performReconnect(request)
            is PendingConnection.Startup -> performStartup(request.settings)
        }
    }

    private suspend fun performPair(request: PendingConnection.Pair) {
        _ui.update { it.copy(booting = true, error = null) }
        val token = runCatching {
            if (!gateway.health(request.base)) {
                throw GatewayException(0, "No gateway answered at ${request.base}.")
            }
            gateway.pair(request.base, request.code, request.deviceName)
        }.getOrElse { error ->
            _ui.update { it.copy(booting = false, error = error.userMessage()) }
            return
        }
        store.saveConnection(request.base, token)
        store.saveDeviceName(request.deviceName)
        val nodeId = runCatching { store.ensureDeviceId() }.getOrDefault("")
        if (_ui.value.nodeEnabled && nodeId.isNotBlank()) {
            NodeService.start(getApplication())
        }
        _ui.update {
            it.copy(
                booting = false,
                gatewayUrl = request.base,
                token = token,
                deviceName = request.deviceName,
                deviceId = nodeId,
                error = null,
            )
        }
        loadWorkspace(null)
    }

    private suspend fun performReconnect(request: PendingConnection.Reconnect) {
        maybeRenewBeforeConnect()
        store.saveConnection(request.base, request.token)
        socket.close()
        val nodeId = runCatching { store.ensureDeviceId() }.getOrDefault("")
        if (_ui.value.nodeEnabled && nodeId.isNotBlank()) {
            NodeService.start(getApplication())
        }
        _ui.update {
            it.copy(
                gatewayUrl = request.base,
                token = request.token,
                deviceId = nodeId,
                settingsOpen = false,
                messages = emptyList(),
                error = null,
            )
        }
        loadWorkspace(null)
    }

    private suspend fun performStartup(saved: StoredSettings) {
        // Apply first so a renewal below replaces the client that was just
        // installed instead of the stale pre-renewal material.
        applyMtlsTransport(saved)
        maybeRenewBeforeConnect()
        CertificateRenewalScheduler.schedule(getApplication())
        if (saved.nodeEnabled) {
            NodeService.start(getApplication())
        }
        loadWorkspace(saved.lastSessionId.ifBlank { null })
    }

    fun forgetGateway() {
        viewModelScope.launch {
            socket.close()
            NodeService.stop(getApplication())
            CertificateRenewalScheduler.cancel(getApplication())
            store.clear()
            appliedClientCertPem = ""
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

    /**
     * Persist the SAF tree the operator picked in Settings. The URI grant must
     * be taken while the activity result is still valid, and the display name
     * is captured here so the tool output can name the folder without a query.
     */
    fun saveNodeFolder(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val name = runCatching { DocumentFile.fromTreeUri(app, uri)?.name }
                .getOrNull()
                .orEmpty()
                .ifBlank { "Selected folder" }
            runCatching { store.saveNodeFolder(uri.toString(), name) }
            _ui.update { it.copy(nodeFolderName = name) }
        }
    }

    // ── Voice input ────────────────────────────────────────────────────────

    private fun voiceListener() = object : AndroidVoiceInput.Listener,
        GatewayVoiceInput.Listener {
        override fun onStarted() {
            _ui.update { it.copy(voiceListening = true, voiceError = null) }
        }

        override fun onTranscript(text: String) {
            _ui.update { it.copy(voiceListening = false) }
            send(text)
        }

        override fun onFailed(message: String) {
            _ui.update { it.copy(voiceListening = false, voiceError = message) }
        }

        override fun onStopped() {
            _ui.update { it.copy(voiceListening = false) }
        }
    }

    // ── Identity-based mTLS transport ──────────────────────────────────────

    private fun applyMtlsTransport(settings: StoredSettings) {
        val client = when (val result = MtlsTransport.build(settings)) {
            is MtlsTransport.Result.Ready -> result.built.client
            is MtlsTransport.Result.Unavailable -> MtlsTransport.plainClient()
        }
        gateway.updateHttpClient(client)
        socket.updateHttpClient(client)
        appliedClientCertPem = settings.mtlsClientCertPem
    }

    // ── Pairing-code mTLS enrollment ───────────────────────────────────────

    fun beginSecureEnroll(
        host: String,
        mtlsPort: Int,
        enrollPort: Int,
        code: String,
        expectedSas: String?,
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(secureEnrolling = true, error = null, secureSas = null) }
            runCatching { MtlsEnrollment.probe(host.trim(), enrollPort, code.trim()) }
                .onSuccess { probe ->
                    pendingProbe = probe
                    pendingMtlsPort = mtlsPort
                    if (!expectedSas.isNullOrBlank() &&
                        !expectedSas.equals(probe.sas, ignoreCase = true)
                    ) {
                        pendingProbe = null
                        _ui.update {
                            it.copy(
                                secureEnrolling = false,
                                error = "SAS mismatch: the QR expects $expectedSas but the gateway " +
                                    "presented ${probe.sas}. Do not continue.",
                            )
                        }
                    } else if (!expectedSas.isNullOrBlank()) {
                        completeSecureEnroll(probe, mtlsPort)
                    } else {
                        _ui.update { it.copy(secureEnrolling = false, secureSas = probe.sas) }
                    }
                }
                .onFailure { error ->
                    _ui.update {
                        it.copy(
                            secureEnrolling = false,
                            error = "Secure setup failed: ${error.userMessage()}",
                        )
                    }
                }
        }
    }

    fun confirmSecureEnroll() {
        val probe = pendingProbe ?: return
        viewModelScope.launch { completeSecureEnroll(probe, pendingMtlsPort) }
    }

    fun cancelSecureEnroll() {
        pendingProbe = null
        _ui.update { it.copy(secureEnrolling = false, secureSas = null) }
    }

    private suspend fun completeSecureEnroll(probe: MtlsEnrollment.Probe, mtlsPort: Int) {
        _ui.update { it.copy(secureEnrolling = true, error = null, secureSas = null) }
        val result = runCatching {
            MtlsEnrollment.enroll(probe, _ui.value.deviceName.ifBlank { "Android" }, mtlsPort)
        }.getOrElse { error ->
            _ui.update {
                it.copy(
                    secureEnrolling = false,
                    error = "Enrollment failed: ${error.userMessage()}",
                )
            }
            pendingProbe = null
            return
        }
        pendingProbe = null
        store.saveMtlsMaterial(
            ca = result.caPem,
            cert = result.certPem,
            key = result.keyPem,
        )
        store.saveMtlsValidity(System.currentTimeMillis(), result.notAfter)
        store.saveMtlsGatewayId(result.gatewayId)
        store.saveMtlsEnabled(true)
        val baseUrl = "https://${probe.host}:$mtlsPort"
        store.saveConnection(baseUrl, result.token)
        val saved = store.current()
        applyMtlsTransport(saved)
        CertificateRenewalScheduler.schedule(getApplication())
        _ui.update {
            it.copy(
                gatewayUrl = baseUrl,
                token = result.token,
                mtlsEnabled = true,
                mtlsGatewayId = result.gatewayId,
                mtlsLoaded = true,
                mtlsStatus = MtlsTransport.describe(saved),
                secureEnrolling = false,
                secureSas = null,
                settingsOpen = false,
                error = null,
                messages = emptyList(),
            )
        }
        NodeService.start(getApplication())
        loadWorkspace(null)
    }

    /**
     * Called when the activity comes to the foreground. A background worker
     * may have rotated the certificate while the process was alive, so adopt
     * the new material before the chat or node clients use the old one.
     */
    fun onForeground() {
        viewModelScope.launch {
            if (_ui.value.booting || !_ui.value.paired) return@launch
            // The chat socket and the device-node link are separate. A
            // foregrounded app re-arms a link the OS stopped while the chat
            // stayed connected; otherwise this phone silently disappears from
            // the agent's tool list while another node answers in its place.
            val saved = runCatching { store.current() }.getOrNull()
            if (saved != null && saved.nodeEnabled && _ui.value.nodeStatus is NodeStatus.Stopped) {
                NodeService.start(getApplication())
            }
            maybeRenewBeforeConnect()
        }
    }

    /**
     * Renew only when the app is already awake and the certificate is past its
     * midpoint. No timer, no wakeup: the next foreground connection pays for it.
     */
    private suspend fun maybeRenewBeforeConnect() {
        val saved = runCatching { store.current() }.getOrNull() ?: return
        if (!saved.mtlsEnabled || saved.mtlsIssuedAt <= 0 || saved.mtlsNotAfter <= 0) return
        val midpoint = saved.mtlsIssuedAt + (saved.mtlsNotAfter - saved.mtlsIssuedAt) / 2
        if (System.currentTimeMillis() >= midpoint) {
            renewInternal(manual = false)
        }
    }

    fun renewCertificate() {
        viewModelScope.launch { renewInternal(manual = true) }
    }

    private suspend fun renewInternal(manual: Boolean) {
        val settings = runCatching { store.current() }.getOrNull() ?: return
        if (!settings.mtlsEnabled || settings.mtlsClientCertPem.isBlank()) return
        runCatching {
            CertificateRenewal.serialized {
                val latest = store.current()
                if (!manual && !CertificateRenewal.isDue(latest)) {
                    null
                } else {
                    MtlsEnrollment.renew(latest, latest.deviceName.ifBlank { "Android" })
                }
            }
        }.onSuccess { result ->
            if (result == null) return@onSuccess
            store.saveMtlsMaterial(
                ca = result.caPem,
                cert = result.certPem,
                key = result.keyPem,
            )
            store.saveMtlsGatewayId(result.gatewayId)
            store.saveMtlsValidity(System.currentTimeMillis(), result.notAfter)
            val saved = store.current()
            applyMtlsTransport(saved)
            CertificateRenewalScheduler.schedule(getApplication())
            NodeService.notifyCertificateRenewed(getApplication())
            _ui.update {
                it.copy(
                    mtlsStatus = MtlsTransport.describe(saved),
                    mtlsLoaded = true,
                    mtlsNotAfter = saved.mtlsNotAfter,
                    notice = "Client certificate renewed.",
                )
            }
            if (manual) reconnect(_ui.value.gatewayUrl, _ui.value.token)
        }.onFailure { error ->
            if (manual) {
                _ui.update { it.copy(error = "Certificate renewal failed: ${error.userMessage()}") }
            }
        }
    }

    fun setMtlsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            store.saveMtlsEnabled(enabled)
            val saved = store.current()
            // Keep Settings open; the next "Save and reconnect" picks the transport up.
            applyMtlsTransport(saved)
            if (saved.mtlsEnabled) {
                CertificateRenewalScheduler.schedule(getApplication())
            } else {
                CertificateRenewalScheduler.cancel(getApplication())
            }
            _ui.update {
                it.copy(
                    mtlsEnabled = saved.mtlsEnabled,
                    mtlsStatus = MtlsTransport.describe(saved),
                )
            }
        }
    }

    fun setMtlsGatewayId(id: String) {
        _ui.update { it.copy(mtlsGatewayId = id) }
        viewModelScope.launch {
            store.saveMtlsGatewayId(id)
            val saved = store.current()
            _ui.update { it.copy(mtlsStatus = MtlsTransport.describe(saved)) }
        }
    }

    /** Import one PEM file (CA, client certificate or client key) via SAF. */
    fun importMtlsMaterial(kind: String, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val text = runCatching {
                getApplication<Application>().contentResolver
                    .openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank() || !text.contains("-----BEGIN")) {
                _ui.update { it.copy(error = "The selected file is not a PEM certificate or key.") }
                return@launch
            }
            when (kind) {
                "ca" -> store.saveMtlsMaterial(ca = text, cert = null, key = null)
                "cert" -> store.saveMtlsMaterial(ca = null, cert = text, key = null)
                "key" -> store.saveMtlsMaterial(ca = null, cert = null, key = text)
                else -> return@launch
            }
            val saved = store.current()
            applyMtlsTransport(saved)
            if (saved.mtlsEnabled) {
                CertificateRenewalScheduler.schedule(getApplication())
            }
            _ui.update {
                it.copy(
                    mtlsLoaded = saved.mtlsCaPem.isNotBlank() &&
                        saved.mtlsClientCertPem.isNotBlank() &&
                        saved.mtlsClientKeyPem.isNotBlank(),
                    mtlsStatus = MtlsTransport.describe(saved),
                )
            }
        }
    }

    fun setVoiceBackend(backend: String) {
        val normalized = if (backend == "zeroclaw") "zeroclaw" else "android"
        if (_ui.value.voiceBackend == normalized) return
        cancelVoice()
        _ui.update { it.copy(voiceBackend = normalized, voiceError = null) }
        viewModelScope.launch { store.saveVoiceBackend(normalized) }
    }

    fun startVoice() {
        val snapshot = _ui.value
        if (snapshot.voiceListening) return
        val app = getApplication<Application>()
        if (!DevicePermissions.has(app, DevicePermissions.microphone())) {
            _ui.update {
                it.copy(
                    voiceError = "Microphone permission is not granted. Open Settings → " +
                        "Device capabilities → Microphone and grant it.",
                )
            }
            return
        }
        _ui.update { it.copy(voiceListening = true, voiceError = null) }
        if (snapshot.voiceBackend == "zeroclaw") {
            gatewayVoice.start(viewModelScope, snapshot.gatewayUrl, snapshot.token)
        } else {
            androidVoice.start()
        }
    }

    /**
     * Release handler: a long hold finalizes now, a quick tap lets the
     * recognizer's end-of-speech (or the recorder's silence detector) finish
     * and auto-send.
     */
    fun stopVoice(heldMs: Long) {
        if (heldMs < VOICE_QUICK_TAP_MS) return
        if (_ui.value.voiceBackend == "zeroclaw") gatewayVoice.stop() else androidVoice.stop()
    }

    fun cancelVoice() {
        androidVoice.cancel()
        gatewayVoice.cancel()
        _ui.update { it.copy(voiceListening = false) }
    }

    fun dismissVoiceError() = _ui.update { it.copy(voiceError = null) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    fun openSettings(open: Boolean) = _ui.update { it.copy(settingsOpen = open) }

    fun openDrawer(open: Boolean) = _ui.update { it.copy(drawerOpen = open) }

    // ── Tool approval policy ───────────────────────────────────────────────

    /**
     * Read the selected agent's tool catalog and the approval lists on its
     * risk profile. The gateway owns both; the app only projects them.
     */
    fun refreshToolPolicies() {
        val snapshot = _ui.value
        if (snapshot.gatewayUrl.isBlank() || snapshot.token.isBlank()) return
        val agent = snapshot.agent
        if (agent.isBlank()) {
            _ui.update {
                it.copy(toolPolicy = it.toolPolicy.copy(error = "Pick an agent first."))
            }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(toolPolicy = it.toolPolicy.copy(loading = true, error = null)) }
            val base = snapshot.gatewayUrl
            val token = snapshot.token
            runCatching {
                val tools = gateway.tools(base, token, agent)
                val profile = configString(
                    gateway.configProp(base, token, "agents.$agent.risk_profile"),
                ).ifBlank { "default" }
                val prefix = "risk_profiles.$profile"
                ToolPolicyUi(
                    loading = false,
                    loaded = true,
                    entries = tools.sortedBy { it.name },
                    riskProfile = profile,
                    level = configString(gateway.configProp(base, token, "$prefix.level")),
                    autoApprove = configArray(
                        gateway.configProp(base, token, "$prefix.auto_approve"),
                    ),
                    alwaysAsk = configArray(
                        gateway.configProp(base, token, "$prefix.always_ask"),
                    ),
                    excluded = configArray(
                        gateway.configProp(base, token, "$prefix.excluded_tools"),
                    ),
                )
            }.onSuccess { policy ->
                _ui.update { it.copy(toolPolicy = policy) }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        toolPolicy = it.toolPolicy.copy(
                            loading = false,
                            error = error.userMessage(),
                        ),
                    )
                }
            }
        }
    }

    fun setToolPolicy(tool: String, policy: ToolPolicy) {
        val snapshot = _ui.value
        val current = snapshot.toolPolicy
        if (current.riskProfile.isBlank()) return
        val auto = current.autoApprove.toMutableSet()
        val ask = current.alwaysAsk.toMutableSet()
        val excluded = current.excluded.toMutableSet()
        when (policy) {
            ToolPolicy.Default -> {
                auto.remove(tool); ask.remove(tool); excluded.remove(tool)
            }
            ToolPolicy.Ask -> {
                auto.remove(tool); excluded.remove(tool); ask.add(tool)
            }
            ToolPolicy.Always -> {
                ask.remove(tool); excluded.remove(tool); auto.add(tool)
            }
            ToolPolicy.Blocked -> {
                auto.remove(tool); ask.remove(tool); excluded.add(tool)
            }
        }
        val prefix = "risk_profiles.${current.riskProfile}"
        val ops = listOf(
            configPatchOp("$prefix.auto_approve", auto),
            configPatchOp("$prefix.always_ask", ask),
            configPatchOp("$prefix.excluded_tools", excluded),
        )
        viewModelScope.launch {
            _ui.update { it.copy(toolPolicy = it.toolPolicy.copy(updating = tool, error = null)) }
            runCatching {
                gateway.patchConfig(snapshot.gatewayUrl, snapshot.token, ops)
            }.onSuccess {
                _ui.update {
                    it.copy(
                        toolPolicy = it.toolPolicy.copy(
                            autoApprove = auto.toSet(),
                            alwaysAsk = ask.toSet(),
                            excluded = excluded.toSet(),
                            updating = null,
                        ),
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        toolPolicy = it.toolPolicy.copy(
                            updating = null,
                            error = error.userMessage(),
                        ),
                    )
                }
            }
        }
    }

    private fun configString(value: JsonElement?): String =
        (value as? JsonPrimitive)?.content
            ?.takeUnless { it == "<unset>" || it == "null" }
            .orEmpty()

    private fun configArray(value: JsonElement?): Set<String> {
        val text = configString(value)
        if (text.isBlank()) return emptySet()
        val parsed = runCatching { zcJson.parseToJsonElement(text) }.getOrNull()
        if (parsed is JsonArray) {
            return parsed.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        }
        return text.removeSurrounding("[", "]")
            .split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun configPatchOp(path: String, values: Set<String>): JsonObject =
        buildJsonObject {
            put("op", JsonPrimitive("replace"))
            put("path", JsonPrimitive(path))
            put(
                "value",
                JsonArray(values.sorted().map { JsonPrimitive(it) }),
            )
        }

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
                choice = null,
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
                    nodeId = _ui.value.deviceId,
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
                nodeId = snapshot.deviceId,
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

    fun answerChoice(selected: List<String>, freeForm: String?) {
        val prompt = _ui.value.choice ?: return
        socket.sendChoice(
            requestId = prompt.requestId,
            selected = selected,
            freeForm = freeForm?.trim()?.takeIf { it.isNotEmpty() },
        )
        _ui.update { it.copy(choice = null) }
    }

    fun cancelChoice() {
        val prompt = _ui.value.choice ?: return
        socket.sendChoice(prompt.requestId, emptyList(), null, cancelled = true)
        _ui.update { it.copy(choice = null) }
    }

    // ── Socket events ──────────────────────────────────────────────────────

    private fun handleEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Chunk -> appendToAssistant(event.content)
            is ChatEvent.Thinking -> appendThinking(event.content)
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
                    state.copy(messages = messages, busy = false, choice = null)
                }
                viewModelScope.launch { reloadSessions() }
            }
            is ChatEvent.Failure -> {
                finishAssistant(event.message, isError = true)
                _ui.update { it.copy(choice = null) }
            }
            ChatEvent.Aborted -> {
                updateAssistant { it.copy(streaming = false) }
                _ui.update { it.copy(busy = false, notice = "Turn stopped.", choice = null) }
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
            is ChatEvent.ChoiceRequest -> _ui.update {
                it.copy(
                    choice = ChoicePrompt(
                        requestId = event.requestId,
                        question = event.question,
                        choices = event.choices,
                        multiSelect = event.multiSelect,
                        allowFreeForm = event.allowFreeForm,
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

    private fun appendThinking(chunk: String) {
        if (chunk.isEmpty()) return
        _ui.update { state ->
            val messages = state.messages.toMutableList()
            val index = messages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                val message = messages[index]
                messages[index] = message.copy(
                    thinking = (message.thinking ?: "") + chunk,
                    streaming = true,
                )
            } else {
                messages += ChatMessage(
                    id = newId(),
                    role = MessageRole.Assistant,
                    thinking = chunk,
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
        androidVoice.destroy()
        gatewayVoice.destroy()
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
