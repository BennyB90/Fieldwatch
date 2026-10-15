package app.fieldwatch.sweep

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val SweepInk = Color(0xFF183B39)
internal val SweepTeal = Color(0xFF087E73)
internal val SweepCream = Color(0xFFF6F5EE)
internal val SweepMuted = Color(0xFF596865)
internal val SweepLine = Color(0xFFE1E6DE)
internal val SweepPale = Color(0xFFE3EFE7)
internal val SweepAmber = Color(0xFF976520)

private val SweepColors = lightColorScheme(
    primary = SweepTeal,
    onPrimary = Color.White,
    primaryContainer = SweepPale,
    onPrimaryContainer = SweepInk,
    secondary = SweepInk,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBECE2),
    onSecondaryContainer = SweepInk,
    tertiary = SweepAmber,
    background = SweepCream,
    onBackground = SweepInk,
    surface = Color(0xFFFFFEF9),
    onSurface = SweepInk,
    surfaceVariant = Color(0xFFEEF0E8),
    onSurfaceVariant = SweepMuted,
    outline = Color(0xFF7D8B83),
    outlineVariant = SweepLine,
    error = Color(0xFFAA4136),
    errorContainer = Color(0xFFFCEAE3),
    onErrorContainer = Color(0xFF78281F),
)

private val SweepType = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

@Composable
fun SweepTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SweepColors, typography = SweepType, content = content)
}
