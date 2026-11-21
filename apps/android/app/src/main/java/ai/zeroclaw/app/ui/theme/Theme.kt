package ai.zeroclaw.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zeroclaw.app.R

/**
 * Design tokens mirror the OpenClaw Android shell (same geometry, type scale
 * and surface hierarchy) with ZeroClaw's cyan accent, so the app reads as the
 * familiar client without impersonating another product.
 */
object Zc {
    val Canvas = Color(0xFF0E1015)
    val Surface = Color(0xFF161920)
    val SurfaceRaised = Color(0xFF191C24)
    val SurfacePressed = Color(0xFF1F2330)

    val Accent = Color(0xFF22D3EE)
    val AccentSoft = Color(0x1F22D3EE)
    val AccentBorder = Color(0xFF0EA5C4)
    val UserBubble = Color(0xFF22D3EE).copy(alpha = 0.12f).compositeOver(Color(0xFF0E1015))

    val Border = Color(0xFF1E2028)
    val BorderStrong = Color(0xFF2E3040)

    val Text = Color(0xFFF4F4F5)
    val TextMuted = Color(0xFFBCBCC0)
    val TextSubtle = Color(0xFF8B8B94)

    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFF87171)
    val DangerSoft = Color(0x26F87171)

    val CodeBg = Color(0xFF0A0C10)

    val Xxs = 8.dp
    val Xs = 12.dp
    val Sm = 16.dp
    val Lg = 24.dp
    val Touch = 48.dp
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    resId = R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(
    manrope(400),
    manrope(500),
    manrope(600),
    manrope(700),
)

val MonoFamily = FontFamily.Monospace

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
)

private val DarkScheme = darkColorScheme(
    primary = Zc.Accent,
    onPrimary = Color(0xFF00242B),
    secondary = Zc.Accent,
    onSecondary = Color(0xFF00242B),
    background = Zc.Canvas,
    onBackground = Zc.Text,
    surface = Zc.Surface,
    onSurface = Zc.Text,
    surfaceVariant = Zc.SurfaceRaised,
    onSurfaceVariant = Zc.TextMuted,
    outline = Zc.BorderStrong,
    outlineVariant = Zc.Border,
    error = Zc.Danger,
    onError = Color(0xFF2A0B0B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B7F93),
    onPrimary = Color.White,
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF101014),
    surface = Color.White,
    onSurface = Color(0xFF101014),
    surfaceVariant = Color(0xFFF1F1F4),
    onSurfaceVariant = Color(0xFF52525B),
    outline = Color(0xFFCFCFD8),
    outlineVariant = Color(0xFFE4E4EA),
    error = Color(0xFFB91C1C),
)

@Composable
fun ZeroClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
