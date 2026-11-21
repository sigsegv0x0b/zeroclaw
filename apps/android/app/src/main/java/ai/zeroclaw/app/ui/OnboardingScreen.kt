package ai.zeroclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import ai.zeroclaw.app.ui.theme.Zc

@Composable
fun OnboardingScreen(
    booting: Boolean,
    error: String?,
    deviceName: String,
    onPair: (url: String, code: String, deviceName: String) -> Unit,
    onTokenConnect: (url: String, token: String) -> Unit,
    onDismissError: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember(deviceName) { mutableStateOf(deviceName) }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    val colors = zcFieldColors()

    Column(
        Modifier
            .fillMaxSize()
            .background(Zc.Canvas)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(64)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Connect to ZeroClaw",
            style = MaterialTheme.typography.headlineMedium,
            color = Zc.Text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Pair this phone with the gateway running on your machine.",
            style = MaterialTheme.typography.bodyMedium,
            color = Zc.TextSubtle,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Gateway URL") },
            placeholder = { Text("192.168.1.20:42617") },
            singleLine = true,
            colors = colors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        if (!showToken) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Pairing code") },
                placeholder = { Text("Code printed by the gateway") },
                singleLine = true,
                colors = colors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Device name") },
                singleLine = true,
                colors = colors,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Bearer token") },
                singleLine = true,
                colors = colors,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            InlineError(text = error, onDismiss = onDismissError)
        }

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = {
                if (showToken) {
                    onTokenConnect(url, token)
                } else {
                    onPair(url, code, name)
                }
            },
            enabled = !booting,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Zc.Accent,
                contentColor = Color(0xFF00242B),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            if (booting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF00242B),
                )
            } else {
                Text(
                    text = if (showToken) "Connect" else "Pair device",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        TextButton(onClick = { showToken = !showToken }) {
            Text(
                text = if (showToken) "Use a pairing code" else "Use an existing token",
                style = MaterialTheme.typography.labelMedium,
                color = Zc.TextMuted,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            Text(
                text = "Get the code with ",
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextSubtle,
            )
            Text(
                text = "zeroclaw gateway get-paircode",
                style = MaterialTheme.typography.labelSmall,
                color = Zc.TextMuted,
            )
        }
    }
}
