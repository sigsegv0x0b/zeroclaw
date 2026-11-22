package ai.zeroclaw.app.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.app.data.ChatMessage
import ai.zeroclaw.app.data.MessageRole
import ai.zeroclaw.app.data.SocketState
import ai.zeroclaw.app.data.ToolCallCard
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
fun MessageItem(message: ChatMessage) {
    when (message.role) {
        MessageRole.User -> Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = Zc.UserBubble,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Zc.Accent.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth(0.88f),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Zc.Text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
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
            val thinking = message.thinking
            if (!thinking.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThinkingDots()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = Zc.TextSubtle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            if (message.text.isNotBlank()) {
                if (message.isError) {
                    Surface(
                        color = Zc.DangerSoft,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Zc.Danger,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                } else {
                    MarkdownBlocks(parseMarkdown(message.text))
                }
            }

            if (message.streaming && message.text.isBlank() && message.tools.isEmpty()) {
                ThinkingDots()
            }

            message.tools.forEach { card ->
                Spacer(Modifier.height(8.dp))
                ToolCallRow(card)
            }
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

@Composable
fun ToolCallRow(card: ToolCallCard) {
    var expanded by remember(card.id) { mutableStateOf(false) }
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
                    text = card.name,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                    color = Zc.Accent,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        card.finished -> "done"
                        else -> "running"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (card.finished) Zc.TextSubtle else Zc.Warning,
                )
            }
            val detail = when {
                expanded -> card.output ?: card.args
                card.args != null -> card.args
                else -> null
            }
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                    color = Zc.TextMuted,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (expanded) {
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                )
            }
        }
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
