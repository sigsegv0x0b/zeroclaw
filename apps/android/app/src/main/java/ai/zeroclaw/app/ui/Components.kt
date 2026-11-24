package ai.zeroclaw.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.app.data.ChatMessage
import ai.zeroclaw.app.data.MessageBlock
import ai.zeroclaw.app.data.MessageRole
import ai.zeroclaw.app.data.SocketState
import ai.zeroclaw.app.data.ToolCallCard
import ai.zeroclaw.app.data.TtsController
import ai.zeroclaw.app.data.TtsStatus
import ai.zeroclaw.app.ui.theme.MonoFamily
import ai.zeroclaw.app.ui.theme.Zc

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Zc.TextSubtle,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
fun StatusDot(state: SocketState, modifier: Modifier = Modifier) {
    val color = when (state) {
        is SocketState.Open -> Zc.Success
        is SocketState.Connecting, is SocketState.Reconnecting -> Zc.Warning
        is SocketState.Failed -> Zc.Danger
        SocketState.Idle -> Zc.TextSubtle
    }
    Box(
        modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

fun socketLabel(state: SocketState): String = when (state) {
    is SocketState.Open -> "Connected"
    is SocketState.Connecting -> "Connecting…"
    is SocketState.Reconnecting -> "Reconnecting…"
    is SocketState.Failed -> "Offline"
    SocketState.Idle -> "Not connected"
}

@Composable
fun BrandMark(size: Int = 32) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(Zc.AccentSoft)
            .border(1.dp, Zc.AccentBorder.copy(alpha = 0.6f), RoundedCornerShape((size / 4).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Z",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = (size * 0.5f).sp,
            ),
            color = Zc.Accent,
        )
    }
}


@Composable
fun MessageItem(message: ChatMessage, localNodeId: String, tts: TtsController) {
    when (message.role) {
        MessageRole.User -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                color = Zc.UserBubble,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Zc.Accent.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth(0.88f),
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Zc.Text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            MessageActions(
                text = message.text,
                tts = tts,
                ttsKey = message.id,
                speechText = message.text,
            )
        }

        MessageRole.System -> Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall,
            color = Zc.TextSubtle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )

        MessageRole.Assistant -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            val blocks = message.contentBlocks
            SelectionContainer {
                Column {
                    blocks.forEachIndexed { index, block ->
                        when (block) {
                            is MessageBlock.Thinking -> {
                                ThinkingBlock(
                                    text = block.text,
                                    streaming = message.streaming && index == blocks.lastIndex,
                                )
                            }

                            is MessageBlock.Text -> {
                                if (message.isError) {
                                    Surface(
                                        color = Zc.DangerSoft,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = block.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Zc.Danger,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        )
                                    }
                                } else {
                                    MarkdownBlocks(parseMarkdown(block.text))
                                }
                            }

                            is MessageBlock.Tool -> ToolCallRow(block.card, localNodeId)
                        }
                        if (index != blocks.lastIndex) {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (message.streaming && blocks.isEmpty()) {
                        ThinkingDots()
                    }
                }
            }
            if (!message.streaming) {
                val copyText = blocks
                    .filterIsInstance<MessageBlock.Text>()
                    .joinToString("") { it.text }
                MessageActions(
                    text = copyText,
                    clipboardText = plainTextForClipboard(copyText),
                    speechText = plainTextForSpeech(copyText),
                    tts = tts,
                    ttsKey = message.id,
                )
            }
        }
    }
}

/** Copy/share actions beneath a chat block. The whole block is copied as text. */
@Composable
fun MessageActions(
    text: String,
    tts: TtsController,
    ttsKey: String,
    clipboardText: String = text,
    speechText: String = text,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank() && clipboardText.isBlank()) return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val playback by tts.state.collectAsState()
    val active = playback.key == ttsKey && playback.status != TtsStatus.Idle
    val speaking = active && playback.status == TtsStatus.Playing
    val speakLabel = when {
        speaking -> "Pause"
        active -> "Resume"
        else -> "Speak"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (speechText.isNotBlank()) {
            TextButton(
                onClick = { tts.toggle(ttsKey, speechText) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = speakLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Zc.Accent,
                )
            }
            if (active) {
                TextButton(
                    onClick = { tts.reset() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelSmall,
                        color = Zc.TextSubtle,
                    )
                }
            }
        }
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(clipboardText))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = "Copy",
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextSubtle,
            )
        }
        TextButton(
            onClick = { shareText(context, clipboardText) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = "Share",
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextSubtle,
            )
        }
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, "Share reply").apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

/** Collapsible reasoning text: two lines by default, full scrollable text when open. */
@Composable
private fun ThinkingBlock(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = Zc.Surface,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Zc.Border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (streaming) {
                    ThinkingDots()
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = Zc.TextSubtle,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelSmall,
                    color = Zc.Accent,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = Zc.TextMuted,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (expanded) {
                    Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .background(Zc.Accent, CircleShape),
            )
        }
    }
}

internal data class ToolDisplay(
    val name: String,
    val label: String,
    val isNode: Boolean,
    val nodeId: String? = null,
)

/** Gateway node tools are `node_<node_id>__<capability>`; everything else runs on the gateway. */
internal fun toolDisplay(name: String): ToolDisplay {
    if (name.startsWith("node_")) {
        val separator = name.indexOf("__")
        if (separator > "node_".length) {
            return ToolDisplay(
                name = name.substring(separator + 2).ifBlank { name },
                label = "phone",
                isNode = true,
                nodeId = name.substring("node_".length, separator),
            )
        }
    }
    return ToolDisplay(name = name, label = "server", isNode = false)
}

@Composable
internal fun ToolOriginBadge(display: ToolDisplay) {
    Surface(
        color = if (display.isNode) Zc.AccentSoft else Zc.SurfacePressed,
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text = display.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (display.isNode) Zc.Accent else Zc.TextSubtle,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun ToolCallRow(card: ToolCallCard, localNodeId: String) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    val display = remember(card.name) { toolDisplay(card.name) }
    val nodeNote = display.nodeId?.let { node ->
        if (node == localNodeId) "this phone" else "phone $node"
    }
    Surface(
        color = Zc.SurfaceRaised,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Zc.Border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (card.finished) Zc.Success else Zc.Warning, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = display.name,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                    color = Zc.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                ToolOriginBadge(display)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        card.finished -> "done"
                        else -> "running"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (card.finished) Zc.TextSubtle else Zc.Warning,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelSmall,
                    color = Zc.Accent,
                )
            }
            if (nodeNote != null) {
                Text(
                    text = nodeNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (display.nodeId == localNodeId) Zc.Success else Zc.TextSubtle,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                ToolDetailSection(
                    label = "Parameters",
                    text = card.args?.ifBlank { "{}" } ?: "{}",
                    maxHeight = 160,
                )
                Spacer(Modifier.height(8.dp))
                ToolDetailSection(
                    label = "Result",
                    text = when {
                        !card.output.isNullOrBlank() -> card.output
                        card.finished -> "(no output)"
                        else -> "Running…"
                    },
                    maxHeight = 240,
                )
            }
        }
    }
}

@Composable
private fun ToolDetailSection(label: String, text: String, maxHeight: Int) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Zc.TextSubtle,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
    Spacer(Modifier.height(4.dp))
    Surface(
        color = Zc.CodeBg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
            color = Zc.TextMuted,
            modifier = Modifier
                .heightIn(max = maxHeight.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        )
    }
}

@Composable
fun Composer(
    busy: Boolean,
    connected: Boolean,
    voiceListening: Boolean,
    voiceError: String?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: (heldMs: Long) -> Unit,
    onVoiceCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        onSend(text)
    }

    Column(modifier = modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Surface(
            color = Zc.Surface,
            shape = RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Zc.BorderStrong),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (draft.isEmpty()) {
                        Text(
                            text = when {
                                voiceListening -> "Listening…"
                                connected -> "Message ZeroClaw"
                                else -> "Reconnecting…"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Zc.TextSubtle,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Zc.Text),
                        cursorBrush = SolidColor(Zc.Accent),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (busy) {
                    IconButton(onClick = onStop) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(Zc.Danger, RoundedCornerShape(2.dp)),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (voiceListening) Zc.Danger.copy(alpha = 0.16f) else Color.Transparent,
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onVoiceStart()
                                    val started = System.currentTimeMillis()
                                    val released = tryAwaitRelease()
                                    val heldMs = System.currentTimeMillis() - started
                                    if (released) onVoiceStop(heldMs) else onVoiceCancel()
                                },
                            )
                        }
                        .semantics { contentDescription = "Hold to talk" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "🎤",
                        fontSize = 20.sp,
                        color = if (voiceListening) Zc.Danger else Zc.TextMuted,
                    )
                }
                IconButton(
                    onClick = { send() },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (draft.isNotBlank()) Zc.Accent else Zc.TextSubtle,
                    )
                }
            }
        }
    }
        if (voiceError != null) {
            Text(
                text = voiceError,
                style = MaterialTheme.typography.labelSmall,
                color = Zc.Danger,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun EmptyChat(agent: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(56)
        Spacer(Modifier.height(18.dp))
        Text(
            text = "How can I help?",
            style = MaterialTheme.typography.headlineMedium,
            color = Zc.Text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Talking to ${agent.ifBlank { "ZeroClaw" }}",
            style = MaterialTheme.typography.bodyMedium,
            color = Zc.TextSubtle,
        )
    }
}

@Composable
fun InlineError(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Zc.DangerSoft, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Zc.Danger,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Zc.Danger, modifier = Modifier.size(18.dp))
        }
    }
}
