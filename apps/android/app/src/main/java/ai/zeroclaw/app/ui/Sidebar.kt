package ai.zeroclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.zeroclaw.app.data.SessionEntry
import ai.zeroclaw.app.data.UiState
import ai.zeroclaw.app.ui.theme.Zc
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SidebarContent(
    state: UiState,
    onSelectAgent: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSession: (SessionEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshSessions: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(Zc.Surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark(30)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "ZeroClaw",
                    style = MaterialTheme.typography.titleMedium,
                    color = Zc.Text,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(state.socket)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = socketLabel(state.socket),
                        style = MaterialTheme.typography.labelSmall,
                        color = Zc.TextSubtle,
                    )
                }
            }
        }

        if (state.agents.isNotEmpty()) {
            SectionLabel("Agents", Modifier.padding(horizontal = 12.dp))
            state.agents.forEach { agent ->
                val selected = agent.alias == state.agent
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Zc.AccentSoft else Zc.Surface)
                        .clickable { onSelectAgent(agent.alias) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(
                                if (agent.enabled) Zc.Success else Zc.TextSubtle,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = agent.alias,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) Zc.Accent else Zc.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onNewChat() }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Zc.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "New chat",
                style = MaterialTheme.typography.bodyMedium,
                color = Zc.Accent,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "CONVERSATIONS",
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextSubtle,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefreshSessions) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh conversations",
                    tint = Zc.TextSubtle,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (state.sessions.isEmpty()) {
            Text(
                text = "No saved conversations yet.",
                style = MaterialTheme.typography.bodySmall,
                color = Zc.TextSubtle,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(state.sessions, key = { it.sessionKey }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.sessionId == state.sessionId,
                        onClick = { onOpenSession(session) },
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Zc.Border),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpenSettings() }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = Zc.TextMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Zc.Text,
                )
                Text(
                    text = state.gatewayUrl.removePrefix("http://").removePrefix("https://"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Zc.TextSubtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntry, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Zc.SurfaceRaised else Zc.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Zc.Text else Zc.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = relativeTime(session.lastActivity),
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextSubtle,
            )
        }
        val meta = buildString {
            session.agentAlias?.let { append(it) }
            if (session.messageCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("${session.messageCount} msg")
                if (session.messageCount != 1) append("s")
            }
        }
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun relativeTime(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        val instant = Instant.parse(raw)
        val minutes = java.time.Duration.between(instant, Instant.now()).toMinutes()
        when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
            else -> timeFormatter.format(instant.atZone(ZoneId.systemDefault()))
        }
    }.getOrDefault("")
}
