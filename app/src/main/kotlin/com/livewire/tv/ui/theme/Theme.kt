package com.livewire.tv.ui.theme

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Colour tokens from the LiveWire design system (design/LIVEWIRE_DESIGN_SYSTEM.md, section 3; kept outside the repo).
 *
 * Two rules matter more than any single value:
 * - [Accent] (amber) means focus / "you are here" and nothing else.
 * - [Live] (red) means live / now and nothing else. Progress bars use [ProgressFill].
 */
object LiveWireColors {
    val Canvas = Color(0xFF0B0C10)
    val Surface = Color(0xFF15171D)
    val SurfaceRaised = Color(0xFF1F222A)
    val SurfaceFocused = Color(0xFF262A34)
    val OnSurface = Color(0xFFFAFAFA)
    val OnSurfaceMuted = Color(0xFFA8ACB6)
    val Accent = Color(0xFFF5A524)
    val OnAccent = Color(0xFF0B0C10)
    val Live = Color(0xFFFF5A5F)
    val ProgressFill = Color(0xFFD4D6DC)
    val ProgressTrack = Color.White.copy(alpha = 0.14f)
    val Border = Color.White.copy(alpha = 0.12f)
    val BorderStrong = Color.White.copy(alpha = 0.18f)
    val Scrim = Color.Black.copy(alpha = 0.55f)
}

/** Spacing and shape tokens (section 5). */
object LiveWireDimens {
    val SpaceXs = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 12.dp
    val SpaceL = 16.dp
    val SpaceXl = 24.dp

    /** Horizontal / vertical TV safe area (section 2). */
    val SafeHorizontal = 48.dp
    val SafeVertical = 27.dp

    val RadiusCard = 7.dp
    val RadiusCell = 5.dp
    val RadiusDialog = 10.dp

    val RailGap = 10.dp
    val RailSpacing = 16.dp

    val FocusBorder = 2.dp
    val RestBorder = 0.5.dp
    const val FocusScale = 1.04f
    const val FocusScaleWide = 1.02f
    val FocusGlow = 18.dp
}

/** Render tier (section 10). LOW drops glows and animated focus on weak sticks. */
enum class PerformanceTier { LOW, STANDARD }

/** Tokens TV Material has no slot for. Read with `LiveWireTheme.tokens`. */
@Immutable
data class LiveWireTokens(
    val tier: PerformanceTier,
    val overline: TextStyle,
    val tag: TextStyle,
)

private val LocalLiveWireTokens = staticCompositionLocalOf {
    LiveWireTokens(PerformanceTier.STANDARD, TextStyle.Default, TextStyle.Default)
}

/**
 * Phone Material3 components still used on TV screens (spinners, LinearProgressIndicator)
 * read this scheme. Primary is the neutral progress colour so they never turn amber
 * (focus) or red (live).
 */
private val m3Scheme = androidx.compose.material3.darkColorScheme(
    primary = LiveWireColors.ProgressFill,
    onPrimary = LiveWireColors.Canvas,
    secondary = LiveWireColors.OnSurfaceMuted,
    background = LiveWireColors.Canvas,
    onBackground = LiveWireColors.OnSurface,
    surface = LiveWireColors.Surface,
    onSurface = LiveWireColors.OnSurface,
    surfaceVariant = LiveWireColors.ProgressTrack,
    onSurfaceVariant = LiveWireColors.OnSurfaceMuted,
    outline = LiveWireColors.BorderStrong,
    error = LiveWireColors.OnSurface,
)

private val scheme = darkColorScheme(
    primary = LiveWireColors.Accent,
    onPrimary = LiveWireColors.OnAccent,
    secondary = LiveWireColors.OnSurfaceMuted,
    onSecondary = LiveWireColors.Canvas,
    background = LiveWireColors.Canvas,
    onBackground = LiveWireColors.OnSurface,
    // Full-screen Surfaces are the canvas; cards and bands set LiveWireColors.Surface explicitly.
    surface = LiveWireColors.Canvas,
    onSurface = LiveWireColors.OnSurface,
    surfaceVariant = LiveWireColors.SurfaceRaised,
    onSurfaceVariant = LiveWireColors.OnSurfaceMuted,
    border = LiveWireColors.Border,
    borderVariant = LiveWireColors.BorderStrong,
    // Errors are not red in browse screens; red is reserved for live (section 3.3).
    error = LiveWireColors.OnSurface,
    onError = LiveWireColors.Canvas,
)

// Section 4. Bundled Inter / Space Grotesk / JetBrains Mono land in a follow-up;
// until then the system sans and monospace families stand in at the same sizes.
private val Display = FontFamily.SansSerif
private val Text = FontFamily.SansSerif
private val Mono = FontFamily.Monospace

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 33.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, lineHeight = 9.sp, letterSpacing = 0.12.em),
)

private val overline = TextStyle(
    fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
    lineHeight = 12.sp, letterSpacing = 0.12.em,
)

private fun detectTier(context: Context): PerformanceTier {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return PerformanceTier.STANDARD
    if (am.isLowRamDevice) return PerformanceTier.LOW
    val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
    return if (info.totalMem < 2L * 1024 * 1024 * 1024) PerformanceTier.LOW else PerformanceTier.STANDARD
}

/** App theme. Dark, TV-first, built on Compose-for-TV MaterialTheme. */
@Composable
fun LiveWireTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val tokens = remember(context) {
        LiveWireTokens(tier = detectTier(context), overline = overline, tag = typography.labelSmall)
    }
    CompositionLocalProvider(LocalLiveWireTokens provides tokens) {
        androidx.compose.material3.MaterialTheme(colorScheme = m3Scheme) {
            MaterialTheme(colorScheme = scheme, typography = typography, content = content)
        }
    }
}

object LiveWireTheme {
    val tokens: LiveWireTokens
        @Composable get() = LocalLiveWireTokens.current
}
