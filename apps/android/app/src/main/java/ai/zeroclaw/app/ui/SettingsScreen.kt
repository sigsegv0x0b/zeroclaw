package ai.zeroclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.text.KeyboardOptions
import ai.zeroclaw.app.data.NodeStatus
import ai.zeroclaw.app.data.UiState
import ai.zeroclaw.app.ui.theme.Zc

@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onReconnect: (url: String, token: String) -> Unit,
    onForget: () -> Unit,
    onNodeEnabled: (Boolean) -> Unit,
) {
    var url by remember(state.gatewayUrl) { mutableStateOf(state.gatewayUrl) }
    var token by remember(state.token) { mutableStateOf(state.token) }
    var reveal by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
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
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Zc.TextMuted)
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = Zc.Text,
            )
        }

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
                text = "Let the agent use this phone: alarms and timers are set on the device's clock app. " +
                    "The link runs as a foreground service while it is enabled.",
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
    }
}

private fun nodeStatusLabel(state: UiState): String = when (val status = state.nodeStatus) {
    is NodeStatus.Connected -> "Linked as ${status.nodeId} · ${status.capabilityCount} capabilities"
    is NodeStatus.Connecting -> "Connecting…"
    is NodeStatus.Error -> "Link lost: ${status.message}"
    NodeStatus.Stopped -> if (state.nodeEnabled) "Starting…" else "Off"
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
