package ai.zeroclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.zeroclaw.app.data.DevicePermissions
import ai.zeroclaw.app.data.NodeStatus
import ai.zeroclaw.app.data.ToolEntry
import ai.zeroclaw.app.data.ToolPolicy
import ai.zeroclaw.app.data.ToolPolicyUi
import ai.zeroclaw.app.data.UiState
import ai.zeroclaw.app.ui.theme.MonoFamily
import ai.zeroclaw.app.ui.theme.Zc

@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onReconnect: (url: String, token: String) -> Unit,
    onForget: () -> Unit,
    onNodeEnabled: (Boolean) -> Unit,
    onPreferNodeTools: (Boolean) -> Unit,
    onFolderPicked: (android.net.Uri?) -> Unit,
    onVoiceBackend: (String) -> Unit,
    onMtlsEnabled: (Boolean) -> Unit,
    onMtlsGatewayId: (String) -> Unit,
    onMtlsImport: (kind: String, uri: android.net.Uri?) -> Unit,
    onRenewCertificate: () -> Unit,
    onRefreshTools: () -> Unit,
    onToolPolicy: (String, ToolPolicy) -> Unit,
) {
    var url by remember(state.gatewayUrl) { mutableStateOf(state.gatewayUrl) }
    var gwId by remember(state.mtlsGatewayId) { mutableStateOf(state.mtlsGatewayId) }
    val context = LocalContext.current
    var permissionTick by remember { mutableIntStateOf(0) }
    val locationGranted = remember(permissionTick) { DevicePermissions.hasLocation(context) }
    val mediaGranted = remember(permissionTick) { DevicePermissions.hasMedia(context) }
    val contactsGranted = remember(permissionTick) {
        DevicePermissions.has(context, DevicePermissions.contacts())
    }
    val calendarGranted = remember(permissionTick) {
        DevicePermissions.has(context, DevicePermissions.calendar())
    }
    val activityGranted = remember(permissionTick) {
        DevicePermissions.has(context, DevicePermissions.activity())
    }
    val smsGranted = remember(permissionTick) {
        DevicePermissions.has(context, DevicePermissions.sms())
    }
    val callLogGranted = remember(permissionTick) {
        DevicePermissions.has(context, DevicePermissions.callLog())
    }
    val notificationAccess = remember(permissionTick) {
        DevicePermissions.hasNotificationAccess(context)
    }
    val cameraGranted = remember(permissionTick) {
        DevicePermissions.has(context, DevicePermissions.camera())
    }
    val microphoneGranted = remember(permissionTick) {
        DevicePermissions.has(context, DevicePermissions.microphone())
    }
    val accessibilityGranted = remember(permissionTick) {
        DevicePermissions.hasAccessibility(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    val callLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> onFolderPicked(uri) }
    val caLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> onMtlsImport("ca", uri) }
    val certLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> onMtlsImport("cert", uri) }
    val keyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> onMtlsImport("key", uri) }
    var token by remember(state.token) { mutableStateOf(state.token) }
    var reveal by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(SettingsTab.General) }
    val colors = zcFieldColors()

    Column(
        Modifier
            .fillMaxSize()
            .background(Zc.Canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Zc.TextMuted,
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = Zc.Text,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettingsTab.entries.forEach { entry ->
                TextButton(
                    onClick = {
                        tab = entry
                        if (entry == SettingsTab.Tools && !state.toolPolicy.loading) {
                            onRefreshTools()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (tab == entry) Zc.Accent else Zc.TextMuted,
                    )
                }
            }
        }

        if (tab == SettingsTab.General) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
            SectionLabel("Connection")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Gateway URL") },
                singleLine = true,
                colors = colors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Bearer token") },
                singleLine = true,
                colors = colors,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { reveal = !reveal }) {
                        Text(
                            text = if (reveal) "Hide" else "Show",
                            style = MaterialTheme.typography.labelSmall,
                            color = Zc.Accent,
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onReconnect(url, token) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Zc.Accent,
                    contentColor = Color(0xFF00242B),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Save and reconnect", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(26.dp))
            SectionLabel("Transport")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Identity-based mTLS",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Zc.Text,
                    )
                    Text(
                        text = "Use the additional gateway listener. Set the Gateway URL to " +
                            "https://host:42618. Identity is a URI, so IP/DDNS changes are fine.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Zc.TextSubtle,
                    )
                }
                Switch(
                    checked = state.mtlsEnabled,
                    onCheckedChange = onMtlsEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00242B),
                        checkedTrackColor = Zc.Accent,
                        uncheckedThumbColor = Zc.TextSubtle,
                        uncheckedTrackColor = Zc.SurfaceRaised,
                        uncheckedBorderColor = Zc.BorderStrong,
                    ),
                )
            }
            OutlinedTextField(
                value = gwId,
                onValueChange = {
                    gwId = it
                    onMtlsGatewayId(it)
                },
                label = { Text("Gateway ID") },
                placeholder = { Text("gw_…") },
                singleLine = true,
                colors = colors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { caLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Import CA", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(
                    onClick = { certLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Import cert", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(
                    onClick = { keyLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Import key", style = MaterialTheme.typography.labelSmall) }
            }
            Text(
                text = state.mtlsStatus.ifBlank { "mTLS transport is disabled" },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.mtlsStatus.startsWith("mTLS ready")) Zc.Success else Zc.TextSubtle,
            )
            if (state.mtlsEnabled) {
                val expiry = if (state.mtlsNotAfter > 0) {
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                        java.time.Instant.ofEpochMilli(state.mtlsNotAfter)
                            .atZone(java.time.ZoneId.systemDefault()),
                    )
                } else {
                    "unknown"
                }
                Text(
                    text = "Certificate expires: $expiry. Renewal is attempted at half " +
                        "the lifetime; the old certificate stays valid until the new one connects.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Zc.TextSubtle,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onRenewCertificate,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Renew certificate") }
            }

            Spacer(Modifier.height(26.dp))
            SectionLabel("Status")
            InfoRow("Connection", socketLabel(state.socket))
            InfoRow("Agent", state.agent.ifBlank { "—" })
            InfoRow(
                "Conversation",
                state.sessionId.take(8).ifBlank { "—" },
            )
            InfoRow("Conversations saved", state.sessions.size.toString())
            InfoRow("App version", "0.1.0")

            Spacer(Modifier.height(26.dp))
            SectionLabel("Device capabilities")
            Text(
                text = "Let the agent use this phone: clock alarms and timers, location, files in " +
                    "a folder you pick, and photos. The link runs as a foreground service while " +
                    "it is enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = Zc.TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Device link",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Zc.Text,
                    )
                    Text(
                        text = nodeStatusLabel(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (state.nodeStatus) {
                            is NodeStatus.Connected -> Zc.Success
                            is NodeStatus.Disabled -> Zc.Danger
                            is NodeStatus.Error -> Zc.Danger
                            is NodeStatus.Connecting -> Zc.Warning
                            NodeStatus.Stopped -> Zc.TextSubtle
                        },
                    )
                }
                Switch(
                    checked = state.nodeEnabled,
                    onCheckedChange = onNodeEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00242B),
                        checkedTrackColor = Zc.Accent,
                        uncheckedThumbColor = Zc.TextSubtle,
                        uncheckedTrackColor = Zc.SurfaceRaised,
                        uncheckedBorderColor = Zc.BorderStrong,
                    ),
                )
            }
            if (state.deviceId.isNotBlank()) {
                InfoRow("Node id", state.deviceId)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Prefer this phone's tools",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Zc.Text,
                    )
                    Text(
                        text = if (state.preferNodeTools) {
                            "Matching requests use this phone unless you say server, host or cron. " +
                                "Off: the agent asks which device when both offer the action."
                        } else {
                            "The agent asks which device to use when this phone and the host both " +
                                "offer the same action."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Zc.TextSubtle,
                    )
                }
                Switch(
                    checked = state.preferNodeTools,
                    onCheckedChange = onPreferNodeTools,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00242B),
                        checkedTrackColor = Zc.Accent,
                        uncheckedThumbColor = Zc.TextSubtle,
                        uncheckedTrackColor = Zc.SurfaceRaised,
                        uncheckedBorderColor = Zc.BorderStrong,
                    ),
                )
            }

            Spacer(Modifier.height(6.dp))
            PermissionRow(
                title = "Location",
                detail = if (locationGranted) {
                    "Granted. The agent can read the current position."
                } else {
                    "Needed for location.get. Android asks while the app is open."
                },
                granted = locationGranted,
                actionLabel = "Grant",
                onAction = { locationLauncher.launch(DevicePermissions.location()) },
            )
            PermissionRow(
                title = "Photos",
                detail = if (mediaGranted) {
                    "Granted. The agent can list the gallery and attach an image."
                } else {
                    "Needed for gallery.list / gallery.read."
                },
                granted = mediaGranted,
                actionLabel = "Grant",
                onAction = { mediaLauncher.launch(DevicePermissions.media()) },
            )
            PermissionRow(
                title = "Contacts",
                detail = if (contactsGranted) {
                    "Granted. contacts.search / contacts.add."
                } else {
                    "Needed for contacts.search and contacts.add."
                },
                granted = contactsGranted,
                actionLabel = "Grant",
                onAction = { contactsLauncher.launch(DevicePermissions.contacts()) },
            )
            PermissionRow(
                title = "Calendar",
                detail = if (calendarGranted) {
                    "Granted. calendar.events / calendar.add."
                } else {
                    "Needed for calendar.events and calendar.add."
                },
                granted = calendarGranted,
                actionLabel = "Grant",
                onAction = { calendarLauncher.launch(DevicePermissions.calendar()) },
            )
            PermissionRow(
                title = "Activity",
                detail = if (activityGranted) {
                    "Granted. Step counter and motion classification."
                } else {
                    "Needed for motion.activity and motion.pedometer."
                },
                granted = activityGranted,
                actionLabel = "Grant",
                onAction = { activityLauncher.launch(DevicePermissions.activity()) },
            )
            PermissionRow(
                title = "SMS",
                detail = if (smsGranted) {
                    "Granted. sms.send / sms.search."
                } else {
                    "Needed for sms.send and sms.search. Play-restricted; this build is sideloaded."
                },
                granted = smsGranted,
                actionLabel = "Grant",
                onAction = { smsLauncher.launch(DevicePermissions.sms()) },
            )
            PermissionRow(
                title = "Call log",
                detail = if (callLogGranted) {
                    "Granted. callLog.search."
                } else {
                    "Needed for callLog.search. Play-restricted; this build is sideloaded."
                },
                granted = callLogGranted,
                actionLabel = "Grant",
                onAction = { callLogLauncher.launch(DevicePermissions.callLog()) },
            )
            PermissionRow(
                title = "Camera",
                detail = if (cameraGranted) {
                    "Granted. camera.list / camera.snap / camera.clip."
                } else {
                    "Needed for camera.list, camera.snap and camera.clip."
                },
                granted = cameraGranted,
                actionLabel = "Grant",
                onAction = { cameraLauncher.launch(DevicePermissions.camera()) },
            )
            PermissionRow(
                title = "Microphone",
                detail = if (microphoneGranted) {
                    "Granted. talk.ptt.* and voice capture."
                } else {
                    "Needed for talk.ptt.once / start / stop."
                },
                granted = microphoneGranted,
                actionLabel = "Grant",
                onAction = { microphoneLauncher.launch(DevicePermissions.microphone()) },
            )
            PermissionRow(
                title = "Accessibility",
                detail = if (accessibilityGranted) {
                    "Granted. mobile.ui.observe / mobile.ui.act. System settings, permission " +
                        "prompts and ZeroClaw's own screens are excluded."
                } else {
                    "Special access. Enable the ZeroClaw service in system accessibility settings."
                },
                granted = accessibilityGranted,
                actionLabel = "Enable",
                onAction = { context.startActivity(DevicePermissions.accessibilitySettingsIntent()) },
            )
            PermissionRow(
                title = "Notification access",
                detail = if (notificationAccess) {
                    "Granted. notifications.list / notifications.actions."
                } else {
                    "Special access. Enable ZeroClaw in the system notification-access list."
                },
                granted = notificationAccess,
                actionLabel = "Enable",
                onAction = { context.startActivity(DevicePermissions.notificationAccessSettingsIntent()) },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Folder",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Zc.Text,
                    )
                    Text(
                        text = state.nodeFolderName.ifBlank {
                            "No folder chosen. File tools stay unavailable until you pick one."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.nodeFolderName.isBlank()) Zc.TextSubtle else Zc.Success,
                    )
                }
                TextButton(onClick = { folderLauncher.launch(null) }) {
                    Text("Choose", color = Zc.Accent)
                }
            }

            Spacer(Modifier.height(26.dp))
            SectionLabel("Voice input")
            Text(
                text = "Where spoken messages are transcribed. The mic button in the chat " +
                    "composer uses this choice.",
                style = MaterialTheme.typography.bodySmall,
                color = Zc.TextMuted,
            )
            VoiceOptionRow(
                title = "Android speech-to-text",
                detail = "Transcribe on this phone with Android's recognizer. Audio stays on " +
                    "the device.",
                selected = state.voiceBackend != "zeroclaw",
                onSelect = { onVoiceBackend("android") },
            )
            VoiceOptionRow(
                title = "ZeroClaw gateway",
                detail = "Record on the phone and send the audio to the gateway. Requires " +
                    "[transcription] enabled with a provider on the gateway.",
                selected = state.voiceBackend == "zeroclaw",
                onSelect = { onVoiceBackend("zeroclaw") },
            )

            Spacer(Modifier.height(26.dp))
            SectionLabel("This device")
            if (!confirmForget) {
                OutlinedButton(
                    onClick = { confirmForget = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Forget this gateway", color = Zc.Danger)
                }
            } else {
                Text(
                    text = "Removes the saved address and token from this phone. The gateway keeps its pairing record until you revoke the device there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Zc.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinedButton(
                        onClick = { confirmForget = false },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel", color = Zc.TextMuted)
                    }
                    Spacer(Modifier.size(10.dp))
                    Button(
                        onClick = onForget,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Zc.Danger,
                            contentColor = Color(0xFF2A0B0B),
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Forget")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text = "ZeroClaw Android speaks the gateway WebSocket chat protocol. History, agents and approvals stay on your gateway.",
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextSubtle,
            )
            }
        } else {
            ToolPolicyTab(
                state = state,
                onRefresh = onRefreshTools,
                onPolicy = onToolPolicy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private enum class SettingsTab(val label: String) {
    General("General"),
    Tools("Tools"),
}

@Composable
private fun ToolPolicyTab(
    state: UiState,
    onRefresh: () -> Unit,
    onPolicy: (String, ToolPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val policy = state.toolPolicy
    var search by remember { mutableStateOf("") }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search tools") },
                singleLine = true,
                colors = zcFieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRefresh, enabled = !policy.loading) {
                Text("Refresh", color = Zc.Accent)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Agent ${state.agent.ifBlank { "—" }} · risk profile " +
                policy.riskProfile.ifBlank { "—" },
            style = MaterialTheme.typography.labelSmall,
            color = Zc.TextSubtle,
        )
        Text(
            text = "Ask confirms every time · Always preapproves · Block hides the tool · " +
                "Default uses the profile.",
            style = MaterialTheme.typography.labelSmall,
            color = Zc.TextSubtle,
        )
        if (policy.level.isNotBlank() && policy.level != "supervised") {
            Text(
                text = "Approval prompts are bypassed at autonomy level ${policy.level}; " +
                    "stored policies still apply on delegated paths.",
                style = MaterialTheme.typography.labelSmall,
                color = Zc.Warning,
            )
        }
        if (policy.autoApprove.contains("*") || policy.alwaysAsk.contains("*")) {
            Text(
                text = "A wildcard approval entry is active; individual overrides may not win.",
                style = MaterialTheme.typography.labelSmall,
                color = Zc.Warning,
            )
        }
        policy.error?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Zc.Danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Zc.DangerSoft, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
        }

        if (policy.loading && !policy.loaded) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Zc.Accent, strokeWidth = 2.dp)
            }
        } else {
            val query = search.trim()
            val filtered = policy.entries.filter { entry ->
                query.isBlank() ||
                    entry.name.contains(query, ignoreCase = true) ||
                    entry.description.contains(query, ignoreCase = true)
            }
            val phone = filtered.filter { toolDisplay(it.name).isNode }
            val server = filtered.filterNot { toolDisplay(it.name).isNode }
            LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                if (phone.isNotEmpty()) {
                    item { SectionLabel("Phone tools") }
                    items(phone, key = { it.name }) { entry ->
                        ToolPolicyRow(entry, policy, onPolicy)
                    }
                }
                if (server.isNotEmpty()) {
                    item { SectionLabel("Server tools") }
                    items(server, key = { it.name }) { entry ->
                        ToolPolicyRow(entry, policy, onPolicy)
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = if (policy.loaded) "No tools match." else "Loading tools…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Zc.TextSubtle,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolPolicyRow(
    entry: ToolEntry,
    policy: ToolPolicyUi,
    onPolicy: (String, ToolPolicy) -> Unit,
) {
    val display = remember(entry.name) { toolDisplay(entry.name) }
    val current = policy.policyFor(entry.name)
    val updating = policy.updating == entry.name
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = display.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                color = Zc.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            ToolOriginBadge(display)
            if (updating) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Zc.Accent,
                )
            }
        }
        if (entry.description.isNotBlank()) {
            Text(
                text = entry.description,
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextSubtle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolPolicyChip("Default", current == ToolPolicy.Default, !updating) {
                onPolicy(entry.name, ToolPolicy.Default)
            }
            ToolPolicyChip("Ask", current == ToolPolicy.Ask, !updating) {
                onPolicy(entry.name, ToolPolicy.Ask)
            }
            ToolPolicyChip("Always", current == ToolPolicy.Always, !updating) {
                onPolicy(entry.name, ToolPolicy.Always)
            }
            ToolPolicyChip("Block", current == ToolPolicy.Blocked, !updating) {
                onPolicy(entry.name, ToolPolicy.Blocked)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Zc.Border))
}

@Composable
private fun ToolPolicyChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(label, style = MaterialTheme.typography.labelSmall)
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Zc.AccentSoft,
            selectedLabelColor = Zc.Accent,
        ),
    )
}

private fun nodeStatusLabel(state: UiState): String = when (val status = state.nodeStatus) {
    is NodeStatus.Connected -> "Linked as ${status.nodeId} · ${status.capabilityCount} capabilities"
    is NodeStatus.Connecting -> "Connecting…"
    is NodeStatus.Disabled -> status.message
    is NodeStatus.Error -> "Link lost: ${status.message}"
    NodeStatus.Stopped -> if (state.nodeEnabled) "Starting…" else "Off"
}

@Composable
private fun VoiceOptionRow(
    title: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Zc.Text,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) Zc.Success else Zc.TextSubtle,
            )
        }
        RadioButton(selected = selected, onClick = onSelect)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Zc.Text,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) Zc.Success else Zc.TextSubtle,
            )
        }
        if (!granted) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = Zc.Accent)
            }
        } else {
            Text("Granted", style = MaterialTheme.typography.labelSmall, color = Zc.Success)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Zc.TextMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Zc.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
