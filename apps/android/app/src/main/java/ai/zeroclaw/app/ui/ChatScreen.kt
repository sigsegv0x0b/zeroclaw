package ai.zeroclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.zeroclaw.app.data.AppViewModel
import ai.zeroclaw.app.data.ApprovalPrompt
import ai.zeroclaw.app.data.ChatMessage
import ai.zeroclaw.app.data.UiState
import ai.zeroclaw.app.ui.theme.Zc

@Composable
fun ChatScreen(
    state: UiState,
    viewModel: AppViewModel,
    onOpenDrawer: () -> Unit,
) {
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

        Box(Modifier.weight(1f)) {
            if (state.messages.isEmpty()) {
                EmptyChat(agent = state.agent)
            } else {
                MessageList(state.messages)
            }
        }

        Composer(
            busy = state.busy,
            connected = state.connected,
            onSend = viewModel::send,
            onStop = viewModel::stop,
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
private fun MessageList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()
    val last = messages.lastOrNull()

    LaunchedEffect(messages.size, last?.text?.length, last?.tools?.size) {
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
            MessageItem(message)
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
private fun ApprovalDialog(
    prompt: ApprovalPrompt,
    onApprove: () -> Unit,
    onAlways: () -> Unit,
    onDeny: () -> Unit,
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
            Column {
                Text(
                    text = "ZeroClaw wants to run ${prompt.tool}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Zc.Text,
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
                prompt.timeoutSecs?.let { seconds ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Auto-denies in ${seconds}s.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Zc.TextSubtle,
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
