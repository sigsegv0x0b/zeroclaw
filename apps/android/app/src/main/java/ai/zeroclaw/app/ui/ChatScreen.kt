package ai.zeroclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.zeroclaw.app.data.AppViewModel
import ai.zeroclaw.app.data.ApprovalPrompt
import ai.zeroclaw.app.data.ChatMessage
import ai.zeroclaw.app.data.ElicitationPrompt
import ai.zeroclaw.app.data.NodeStatus
import ai.zeroclaw.app.data.TtsController
import ai.zeroclaw.app.data.UiState
import ai.zeroclaw.app.ui.theme.Zc

@Composable
fun ChatScreen(
    state: UiState,
    viewModel: AppViewModel,
    onOpenDrawer: () -> Unit,
) {
    val context = LocalContext.current
    val tts = remember { TtsController(context.applicationContext) }
    DisposableEffect(tts) {
        onDispose { tts.shutdown() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Zc.Canvas),
    ) {
        ChatTopBar(
            agent = state.agent,
            state = state,
            onOpenDrawer = onOpenDrawer,
            onNewChat = viewModel::newChat,
        )

        state.error?.let { message ->
            InlineError(text = message, onDismiss = viewModel::dismissError)
        }
        state.notice?.let { message ->
            NoticeBar(text = message, onDismiss = viewModel::dismissNotice)
        }
        if (state.nodeEnabled && state.nodeStatus is NodeStatus.Disabled) {
            NodeEndpointWarning()
        }

        Box(Modifier.weight(1f)) {
            if (state.messages.isEmpty()) {
                EmptyChat(agent = state.agent)
            } else {
                MessageList(state.messages, state.deviceId, tts)
            }
        }

        Composer(
            busy = state.busy,
            connected = state.connected,
            voiceListening = state.voiceListening,
            voiceError = state.voiceError,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onVoiceStart = viewModel::startVoice,
            onVoiceStop = viewModel::stopVoice,
            onVoiceCancel = viewModel::cancelVoice,
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding(),
        )
    }

    state.approval?.let { prompt ->
        ApprovalDialog(
            prompt = prompt,
            onApprove = { viewModel.answerApproval(true) },
            onAlways = { viewModel.answerApproval(true, always = true) },
            onDeny = { viewModel.answerApproval(false) },
            localNodeId = state.deviceId,
            tts = tts,
        )
    }

    state.elicitation?.let { prompt ->
        ElicitationDialog(
            prompt = prompt,
            onSend = viewModel::answerElicitation,
            onCancel = viewModel::cancelElicitation,
            tts = tts,
        )
    }
}

@Composable
private fun NodeEndpointWarning() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Zc.Warning.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "Phone tools are unavailable",
            style = MaterialTheme.typography.labelLarge,
            color = Zc.Warning,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "The gateway's node endpoint is disabled, so the agent cannot use " +
                "this phone's alarms, timers, location, files, or other capabilities.",
            style = MaterialTheme.typography.bodySmall,
            color = Zc.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "zeroclaw config set nodes.enabled true",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Zc.Text,
        )
        Text(
            text = "Run that on the gateway, restart it, and this link will retry automatically.",
            style = MaterialTheme.typography.labelSmall,
            color = Zc.TextSubtle,
        )
    }
}

@Composable
private fun ChatTopBar(
    agent: String,
    state: UiState,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "Open conversations", tint = Zc.TextMuted)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = agent.ifBlank { "ZeroClaw" },
                style = MaterialTheme.typography.titleMedium,
                color = Zc.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.socket)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = socketLabel(state.socket),
                    style = MaterialTheme.typography.labelSmall,
                    color = Zc.TextSubtle,
                )
                if (state.busy) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "working",
                        style = MaterialTheme.typography.labelSmall,
                        color = Zc.Accent,
                    )
                }
            }
        }
        IconButton(onClick = onNewChat) {
            Icon(Icons.Default.Add, contentDescription = "New chat", tint = Zc.TextMuted)
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    localNodeId: String,
    tts: TtsController,
) {
    val listState = rememberLazyListState()
    val last = messages.lastOrNull()

    LaunchedEffect(
        messages.size,
        last?.contentLength,
        last?.contentBlocks?.size,
        last?.toolCards?.count { it.finished },
    ) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageItem(message, localNodeId, tts)
        }
    }
}

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Zc.AccentSoft, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Zc.Accent,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Text("×", style = MaterialTheme.typography.titleMedium, color = Zc.Accent)
        }
    }
}

@Composable
private fun ElicitationDialog(
    prompt: ElicitationPrompt,
    onSend: (List<String>, String?) -> Unit,
    onCancel: () -> Unit,
    tts: TtsController,
) {
    var selected by remember(prompt.requestId) { mutableStateOf(emptySet<String>()) }
    var freeForm by remember(prompt.requestId) { mutableStateOf("") }
    val canSend = selected.isNotEmpty() || freeForm.isNotBlank()

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Zc.SurfaceRaised,
        titleContentColor = Zc.Text,
        textContentColor = Zc.TextMuted,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                text = when {
                    prompt.options.isEmpty() -> "Your answer"
                    prompt.multiSelect -> "Choose one or more"
                    else -> "Choose an option"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text = prompt.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Zc.Text,
                    )
                }
                MessageActions(
                    text = buildString {
                        append(prompt.message)
                        if (prompt.options.isNotEmpty()) {
                            append("\n\n")
                            prompt.options.forEach { append("• ${it.label}\n") }
                        }
                    },
                    tts = tts,
                    ttsKey = prompt.requestId,
                )
                if (prompt.options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    prompt.options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (prompt.multiSelect) {
                                        selected = if (option.value in selected) {
                                            selected - option.value
                                        } else {
                                            selected + option.value
                                        }
                                    } else {
                                        selected = setOf(option.value)
                                        freeForm = ""
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (prompt.multiSelect) {
                                Checkbox(
                                    checked = option.value in selected,
                                    onCheckedChange = null,
                                )
                            } else {
                                RadioButton(
                                    selected = option.value in selected,
                                    onClick = null,
                                )
                            }
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Zc.Text,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                if (prompt.freeFormProperty != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = freeForm,
                        onValueChange = { value ->
                            freeForm = value
                            if (!prompt.multiSelect && value.isNotBlank()) {
                                selected = emptySet()
                            }
                        },
                        label = { Text(prompt.freeFormLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                }
                prompt.timeoutSecs?.let { seconds ->
                    var remaining by remember(prompt.requestId) { mutableIntStateOf(seconds) }
                    LaunchedEffect(prompt.requestId) {
                        while (remaining > 0) {
                            kotlinx.coroutines.delay(1_000)
                            remaining -= 1
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (remaining > 0) {
                            "Auto-cancels in ${remaining}s."
                        } else {
                            "Response window elapsed — waiting for the gateway."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (remaining <= 10 && remaining > 0) Zc.Warning else Zc.TextSubtle,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSend(
                        selected.toList(),
                        freeForm.trim().takeIf { it.isNotEmpty() },
                    )
                },
                enabled = canSend,
            ) {
                Text("Send", color = if (canSend) Zc.Accent else Zc.TextSubtle)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Zc.Danger)
            }
        },
    )
}

@Composable
private fun ApprovalDialog(
    prompt: ApprovalPrompt,
    onApprove: () -> Unit,
    onAlways: () -> Unit,
    onDeny: () -> Unit,
    localNodeId: String,
    tts: TtsController,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        containerColor = Zc.SurfaceRaised,
        titleContentColor = Zc.Text,
        textContentColor = Zc.TextMuted,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                text = "Tool approval",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            val display = remember(prompt.tool) { toolDisplay(prompt.tool) }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ZeroClaw wants to run ${display.name}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Zc.Text,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    ToolOriginBadge(display)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (display.isNode) {
                        if (display.nodeId == localNodeId) {
                            "This action runs on this phone."
                        } else {
                            "This action runs on another phone (${display.nodeId ?: "unknown"})."
                        }
                    } else {
                        "This action runs on the gateway."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Zc.TextSubtle,
                )
                if (prompt.argumentsSummary.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(color = Zc.CodeBg, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = prompt.argumentsSummary,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = ai.zeroclaw.app.ui.theme.MonoFamily,
                            ),
                            color = Zc.TextMuted,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                MessageActions(
                    text = buildString {
                        append("ZeroClaw wants to run ${display.name}.")
                        if (prompt.argumentsSummary.isNotBlank()) {
                            append("\n\n")
                            append(prompt.argumentsSummary)
                        }
                    },
                    tts = tts,
                    ttsKey = prompt.requestId,
                )
                prompt.timeoutSecs?.let { seconds ->
                    // Live countdown: the gateway's auto-deny window starts when
                    // the request is sent, so tick once a second from the value
                    // the frame carried rather than showing a frozen number.
                    var remaining by remember(prompt.requestId) { mutableIntStateOf(seconds) }
                    LaunchedEffect(prompt.requestId) {
                        while (remaining > 0) {
                            kotlinx.coroutines.delay(1_000)
                            remaining -= 1
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (remaining > 0) {
                            "Auto-denies in ${remaining}s."
                        } else {
                            "Auto-deny window elapsed — waiting for the gateway."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (remaining <= 10 && remaining > 0) Zc.Warning else Zc.TextSubtle,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) {
                Text("Approve", color = Zc.Accent)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onAlways) {
                    Text("Always", color = Zc.TextMuted)
                }
                TextButton(onClick = onDeny) {
                    Text("Deny", color = Zc.Danger)
                }
            }
        },
    )
}
