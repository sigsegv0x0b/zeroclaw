package ai.zeroclaw.app.ui

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import ai.zeroclaw.app.ui.theme.Zc

/** Shared field styling so forms match the shell instead of Material defaults. */
@Composable
fun zcFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Zc.Accent,
    unfocusedBorderColor = Zc.BorderStrong,
    focusedLabelColor = Zc.Accent,
    unfocusedLabelColor = Zc.TextSubtle,
    cursorColor = Zc.Accent,
    focusedTextColor = Zc.Text,
    unfocusedTextColor = Zc.Text,
    focusedContainerColor = Zc.SurfaceRaised,
    unfocusedContainerColor = Zc.SurfaceRaised,
    focusedPlaceholderColor = Zc.TextSubtle,
    unfocusedPlaceholderColor = Zc.TextSubtle,
)
