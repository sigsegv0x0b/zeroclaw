package ai.zeroclaw.app.data

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.zeroclaw.app.service.NodeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A press shorter than this is a tap: let silence detection finish the turn. */
private const val VOICE_QUICK_TAP_MS = 400L
private const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024

data class UiState(
    val booting: Boolean = true,
    val gatewayUrl: String = "",
    val token: String = "",
    val deviceName: String = "Android",
    val deviceId: String = "",
    val nodeEnabled: Boolean = true,
    val preferNodeTools: Boolean = true,
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
    val secureConfirm: SecureEnrollConfirm? = null,
    val nodeStatus: NodeStatus = NodeStatus.Stopped,
    val agents: List<AgentEntry> = emptyList(),
    val agent: String = "",
    val sessions: List<SessionEntry> = emptyList(),
    val sessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val socket: SocketState = SocketState.Idle,
    val busy: Boolean = false,
    val approval: ApprovalPrompt? = null,
    val elicitation: ElicitationPrompt? = null,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
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

/**
 * The gateway a secure setup is about to pair with, shown for confirmation
 * before the pairing code is sent. `verified` is true when a scanned QR code
 * already matched the gateway's CA (and SAS); a typed code needs the operator
 * to compare the SAS with the gateway console.
 */
data class SecureEnrollConfirm(
    val host: String,
    val gatewayId: String,
    val sas: String,
    val verified: Boolean,
)

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
    private val gatewayVoice = GatewayVoiceInput(app) { currentTransport }
    @Volatile private var currentTransport: okhttp3.OkHttpClient = MtlsTransport.plainClient()
    private var pendingProbe: MtlsEnrollment.Probe? = null
    private var pendingMtlsPort: Int = 42618
    private var pendingInsecure: PendingConnection? = null
    private var appliedClientCertPem: String = ""
    private var recoveringInterruptedTurn = false

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        androidVoice.listener = voiceListener()
        gatewayVoice.listener = voiceListener()
        viewModelScope.launch {
            socket.events.collect(::handleEvent)
        }
        viewModelScope.launch {
            var previous: SocketState = SocketState.Idle
            socket.state.collect { state ->
                val reconnected = previous !is SocketState.Open && state is SocketState.Open
                _ui.update { it.copy(socket = state) }
                if (reconnected && _ui.value.busy && !recoveringInterruptedTurn) {
                    recoveringInterruptedTurn = true
                    viewModelScope.launch {
                        try {
                            recoverInterruptedTurn()
                        } finally {
                            recoveringInterruptedTurn = false
                        }
                    }
                }
                previous = state
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
                                preferNodeTools = snapshot.preferNodeTools,
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
            runCatching { store.migrateSecrets() }
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
                    preferNodeTools = saved.preferNodeTools,
                    nodeFolderName = saved.nodeFolderName,
                    voiceBackend = saved.voiceBackend,
                    mtlsEnabled = saved.mtlsEnabled,
                    mtlsGatewayId = saved.mtlsGatewayId,
                    mtlsLoaded = saved.mtlsCaPem.isNotBlank() &&
                        saved.mtlsClientCertPem.isNotBlank() &&
                        saved.hasClientKey,
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
            ClientKeys.deleteAllExcept("")
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
     * When on, matching requests default to this phone's tools. When off, the
     * agent asks which device to use when the host offers the same action.
     * The preference travels in the chat socket handshake, so reopen it for
     * the next turn once the user is not mid-turn.
     */
    fun setPreferNodeTools(enabled: Boolean) {
        _ui.update { it.copy(preferNodeTools = enabled) }
        viewModelScope.launch {
            store.savePreferNodeTools(enabled)
            if (!_ui.value.busy && _ui.value.connected) reconnectSocket()
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
        val client = MtlsTransport.clientFor(settings)
        currentTransport = client
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
        expected: MtlsEnrollment.Expected,
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(secureEnrolling = true, error = null, secureConfirm = null) }
            runCatching { MtlsEnrollment.probe(host.trim(), enrollPort, code.trim()) }
                .onSuccess { probe ->
                    val mismatch = MtlsEnrollment.mismatch(probe, expected)
                    if (mismatch != null) {
                        pendingProbe = null
                        _ui.update {
                            it.copy(
                                secureEnrolling = false,
                                error = "Secure setup stopped: $mismatch. Do not continue.",
                            )
                        }
                        return@onSuccess
                    }
                    pendingProbe = probe
                    pendingMtlsPort = mtlsPort
                    // Even a verified QR asks once: the operator sees which
                    // gateway will be able to use this phone before pairing.
                    _ui.update {
                        it.copy(
                            secureEnrolling = false,
                            secureConfirm = SecureEnrollConfirm(
                                host = probe.host,
                                gatewayId = probe.gatewayId,
                                sas = probe.sas,
                                verified = expected.verifiesSas,
                            ),
                        )
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
        _ui.update { it.copy(secureEnrolling = false, secureConfirm = null) }
    }

    private suspend fun completeSecureEnroll(probe: MtlsEnrollment.Probe, mtlsPort: Int) {
        _ui.update { it.copy(secureEnrolling = true, error = null, secureConfirm = null) }
        pendingProbe = null
        val baseUrl = "https://${probe.host}:$mtlsPort"
        val enrolled = runCatching {
            // Serialized with renewal, which prunes Keystore keys nothing
            // refers to; the new key is referenced once this write lands.
            CertificateRenewal.serialized {
                val enrolled = MtlsEnrollment.enroll(
                    probe,
                    _ui.value.deviceName.ifBlank { "Android" },
                )
                try {
                    store.saveEnrolledIdentity(enrolled.identity, baseUrl, enrolled.token)
                } catch (error: Exception) {
                    ClientKeys.delete(enrolled.identity.keyAlias)
                    throw error
                }
                ClientKeys.deleteAllExcept(enrolled.identity.keyAlias)
                enrolled
            }
        }.getOrElse { error ->
            _ui.update {
                it.copy(
                    secureEnrolling = false,
                    error = "Enrollment failed: ${error.userMessage()}",
                )
            }
            return
        }
        val saved = store.current()
        applyMtlsTransport(saved)
        CertificateRenewalScheduler.schedule(getApplication())
        _ui.update {
            it.copy(
                gatewayUrl = baseUrl,
                token = enrolled.token,
                mtlsEnabled = true,
                mtlsGatewayId = enrolled.identity.gatewayId,
                mtlsLoaded = true,
                mtlsStatus = MtlsTransport.describe(saved),
                mtlsNotAfter = saved.mtlsNotAfter,
                secureEnrolling = false,
                secureConfirm = null,
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
        renewInternal(manual = false)
    }

    fun renewCertificate() {
        viewModelScope.launch { renewInternal(manual = true) }
    }

    private suspend fun renewInternal(manual: Boolean) {
        runCatching { CertificateRenewal.renewIfDue(store, force = manual) }
            .onSuccess { saved ->
                if (saved == null) {
                    if (manual) {
                        _ui.update { it.copy(error = "No enrolled client certificate to renew.") }
                    }
                    return@onSuccess
                }
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
            }
            .onFailure { error ->
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
                        saved.hasClientKey,
                    mtlsStatus = MtlsTransport.describe(saved),
                    mtlsNotAfter = saved.mtlsNotAfter,
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
                elicitation = null,
                pendingAttachments = emptyList(),
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
                    preferNodeTools = _ui.value.preferNodeTools,
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
                preferNodeTools = snapshot.preferNodeTools,
            ),
        )
    }

    /**
     * A turn belongs to the socket that started it. When that socket drops,
     * the gateway keeps the turn running unattended, but the reconnected
     * socket cannot receive its events — including approval or elicitation
     * prompts. Cancel the orphaned turn and reload the transcript so the UI
     * does not sit on "working" forever.
     */
    private suspend fun recoverInterruptedTurn() {
        val snapshot = _ui.value
        if (snapshot.sessionId.isBlank()) return
        runCatching { gateway.abort(snapshot.gatewayUrl, snapshot.token, snapshot.sessionId) }
        // Let the abort settle before reading the transcript it may append to.
        kotlinx.coroutines.delay(750)
        val history = runCatching {
            gateway.messages(snapshot.gatewayUrl, snapshot.token, snapshot.sessionId)
        }.getOrNull()?.messages.orEmpty().toChatMessages()
        _ui.update { state ->
            state.copy(
                messages = history.ifEmpty { state.messages }
                    .map { it.copy(streaming = false) },
                busy = false,
                approval = null,
                elicitation = null,
                pendingAttachments = emptyList(),
                error = null,
                notice = "Connection dropped. The interrupted turn was stopped; you can send again.",
            )
        }
    }

    // ── Turns ──────────────────────────────────────────────────────────────

    fun attach(uri: Uri) {
        val snapshot = _ui.value
        if (snapshot.agent.isBlank() || !snapshot.connected) {
            _ui.update { it.copy(error = "Connect to the gateway before attaching files.") }
            return
        }
        val resolver = getApplication<Application>().contentResolver
        val name = queryDisplayName(resolver, uri) ?: "attachment"
        val size = querySize(resolver, uri)
        if (size != null && size > MAX_ATTACHMENT_BYTES) {
            _ui.update { it.copy(error = "Attachment exceeds the 20 MB upload limit.") }
            return
        }
        val mime = resolver.getType(uri).orEmpty()
        val id = newId()
        _ui.update {
            it.copy(
                pendingAttachments = it.pendingAttachments + PendingAttachment(
                    id = id,
                    name = name,
                    kind = if (mime.startsWith("image/")) AttachmentKind.Image else AttachmentKind.File,
                    localUri = uri.toString(),
                    uploading = true,
                    size = size ?: 0,
                ),
                error = null,
            )
        }
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)?.use { input -> readCapped(input, MAX_ATTACHMENT_BYTES) }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                failAttachment(id, "Could not read the selected attachment.")
                return@launch
            }
            runCatching {
                gateway.uploadAttachment(
                    baseUrl = snapshot.gatewayUrl,
                    token = snapshot.token,
                    agent = snapshot.agent,
                    filename = name,
                    bytes = bytes,
                )
            }.onSuccess { uploaded ->
                _ui.update { state ->
                    state.copy(
                        pendingAttachments = state.pendingAttachments.map { attachment ->
                            if (attachment.id != id) {
                                attachment
                            } else {
                                attachment.copy(
                                    name = uploaded.name.ifBlank { name },
                                    marker = uploaded.marker,
                                    path = uploaded.path,
                                    kind = if (uploaded.kind == "image") {
                                        AttachmentKind.Image
                                    } else {
                                        AttachmentKind.File
                                    },
                                    uploading = false,
                                    size = uploaded.size.takeIf { it > 0 } ?: bytes.size.toLong(),
                                )
                            }
                        },
                    )
                }
            }.onFailure { error ->
                failAttachment(id, error.message ?: "Attachment upload failed.")
            }
        }
    }

    fun removeAttachment(id: String) {
        _ui.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == id })
        }
    }

    fun send(text: String) {
        val content = text.trim()
        val snapshot = _ui.value
        val attachments = snapshot.pendingAttachments
        if (attachments.any { it.uploading }) {
            _ui.update { it.copy(error = "Wait for attachments to finish uploading.") }
            return
        }
        if (attachments.any { it.marker.isBlank() }) {
            _ui.update { it.copy(error = "Attachment upload is incomplete.") }
            return
        }
        if (content.isEmpty() && attachments.isEmpty()) return
        if (snapshot.agent.isBlank()) {
            _ui.update { it.copy(error = "Pick an agent first.") }
            return
        }
        if (!socket.isOpen()) {
            _ui.update { it.copy(error = "Not connected to the gateway yet.") }
            return
        }
        val markers = attachments.joinToString("\n") { it.marker }.trim()
        val outgoing = listOf(content, markers)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val userMessage = ChatMessage(
            id = newId(),
            role = MessageRole.User,
            text = content,
            attachments = attachments.map { attachment ->
                MessageAttachment(
                    name = attachment.name,
                    path = attachment.path,
                    kind = attachment.kind,
                    localUri = attachment.localUri,
                )
            },
        )
        val assistant = ChatMessage(id = newId(), role = MessageRole.Assistant, streaming = true)
        _ui.update {
            it.copy(
                messages = it.messages + userMessage + assistant,
                busy = true,
                error = null,
                pendingAttachments = emptyList(),
            )
        }
        if (!socket.sendMessage(outgoing)) {
            finishAssistant("The message could not be sent.")
        }
    }

    private fun failAttachment(id: String, message: String) {
        _ui.update { state ->
            state.copy(
                pendingAttachments = state.pendingAttachments.filterNot { it.id == id },
                error = message,
            )
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun readCapped(input: InputStream, maxBytes: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
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

    fun answerElicitation(selected: List<String>, freeForm: String?) {
        val prompt = _ui.value.elicitation ?: return
        val typed = freeForm?.trim()?.takeIf { it.isNotEmpty() }
        val content = buildJsonObject {
            if (prompt.choiceProperty != null && selected.isNotEmpty()) {
                if (prompt.multiSelect) {
                    put("choices", buildJsonArray { selected.forEach { add(JsonPrimitive(it)) } })
                } else if (typed == null) {
                    // For a single choice the typed answer overrides the
                    // selection, so send only one of them.
                    put("choice", JsonPrimitive(selected.first()))
                }
            }
            if (prompt.freeFormProperty != null && typed != null) {
                put(prompt.freeFormProperty, JsonPrimitive(typed))
            }
        }
        socket.sendElicitation(prompt.requestId, "accept", content)
        _ui.update { it.copy(elicitation = null) }
    }

    fun cancelElicitation() {
        val prompt = _ui.value.elicitation ?: return
        socket.sendElicitation(prompt.requestId, "cancel")
        _ui.update { it.copy(elicitation = null) }
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
                        if (message.streaming) {
                            message.finishStreaming(event.fullResponse)
                        } else {
                            message
                        }
                    }
                    state.copy(messages = messages, busy = false, elicitation = null)
                }
                viewModelScope.launch { reloadSessions() }
            }
            is ChatEvent.Failure -> {
                finishAssistant(event.message, isError = true)
                _ui.update { it.copy(elicitation = null) }
            }
            ChatEvent.Aborted -> {
                updateAssistant { it.copy(streaming = false) }
                _ui.update { it.copy(busy = false, notice = "Turn stopped.", elicitation = null) }
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
            is ChatEvent.ElicitationRequest -> _ui.update {
                it.copy(elicitation = event.prompt)
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
                messages[index] = messages[index].appendText(chunk)
            } else {
                messages += ChatMessage(
                    id = newId(),
                    role = MessageRole.Assistant,
                    streaming = true,
                ).appendText(chunk)
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
                messages[index] = messages[index].appendThinking(chunk)
            } else {
                messages += ChatMessage(
                    id = newId(),
                    role = MessageRole.Assistant,
                    streaming = true,
                ).appendThinking(chunk)
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
            val card = ToolCallCard(
                id = event.id ?: newId(),
                name = event.name,
                args = event.args?.shortJson(),
            )
            messages[index] = messages[index].appendTool(card)
            state.copy(messages = messages, busy = true)
        }
    }

    private fun completeToolCall(event: ChatEvent.ToolResult) {
        _ui.update { state ->
            val messages = state.messages.toMutableList()
            for (index in messages.indices.reversed()) {
                val updated = messages[index].completeTool(event.id, event.name, event.output)
                if (updated != messages[index]) {
                    messages[index] = updated
                    break
                }
            }
            state.copy(messages = messages)
        }
    }

    private fun finishAssistant(reason: String, isError: Boolean = false) {
        _ui.update { state ->
            val messages = state.messages.toMutableList()
            val index = messages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                messages[index] = messages[index].finishWith(reason, isError)
            } else {
                messages += ChatMessage(
                    id = newId(),
                    role = MessageRole.Assistant,
                    streaming = false,
                ).finishWith(reason, isError)
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
