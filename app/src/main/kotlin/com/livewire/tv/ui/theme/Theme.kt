package com.livewire.tv.ui.theme

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.livewire.tv.R

/**
 * B · Grid colour tokens. This file is the source of truth for the design system.
 *
 * Rules:
 * - Focus is the light inverted fill ([Focus] with [OnFocus] text). It is the only focus
 *   cue: no amber, no ring, no glow. Text fields are the one exception and show focus as a
 *   2dp [Focus] ring so typed text stays readable.
 * - [Live] red marks what is live or now (LIVE badge, progress dots, the Guide now-line,
 *   live game clocks, the channel you are watching, the current tab's on-air dot) and is
 *   the brand accent (the short rule before section overlines, the top edge of panels and
 *   dialogs, buttons that are not focused). It is never used for focus or for errors.
 * - "Selected but not focused" (the current tab, the row behind an open menu) uses
 *   [SurfaceRaised].
 */
object LiveWireColors {
    val Canvas = Color(0xFF0A0F1A)
    val Surface = Color(0xFF111A2B)
    val SurfaceRaised = Color(0xFF18233A)
    /** Past programmes with no recording in the Guide. */
    val SurfaceSunken = Color(0xFF0D1422)
    val OnSurface = Color(0xFFEEF2FA)
    val OnSurfaceSoft = Color(0xFFB9C3D6)
    val OnSurfaceMuted = Color(0xFF8A97B0)
    val OnSurfaceFaint = Color(0xFF5B6884)
    val Focus = Color(0xFFF4F7FF)
    val OnFocus = Color(0xFF0A0F1A)
    val OnFocusMuted = Color(0xFF4A5670)
    val Live = Color(0xFFFF3B30)
    val OnLive = Color(0xFFFFFFFF)
    /** The main progress line; mini bars in lists use [ProgressFillSoft]. */
    val ProgressFill = Color(0xFFEEF2FA)
    val ProgressFillSoft = Color(0xFFC9D2E3)
    val ProgressTrack = Color(0xFFEEF2FA).copy(alpha = 0.16f)
    val ProgressTrackOnFocus = Color(0xFF0A0F1A).copy(alpha = 0.18f)
    /** Grid lines, dividers and borders. */
    val Border = Color(0xFF1E2A42)
    /** The Guide's half-hour verticals. */
    val LineFaint = Color(0xFF1A2439)
    val BorderStrong = Color(0xFF2A3756)
    val Scrim = Color(0xFF05080E).copy(alpha = 0.72f)
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

    /** Text-field focus ring, the one place focus is a ring instead of the fill. */
    val FocusBorder = 2.dp
    val RestBorder = 1.dp
    /** B · Grid does not scale focused items; the fill is the cue. Kept at 1 so callers stay simple. */
    const val FocusScale = 1f
    const val FocusScaleWide = 1f

    /** The top bar: 78px in the mockups = 58.5dp. */
    val TopBarHeight = 58.dp
    val TabHeight = 33.dp
    /** The red top edge on panels and dialogs. */
    val BrandEdge = 2.dp
}

/** Render tier. LOW skips the muted channel preview and any animation on weak sticks. */
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
 * read this scheme. Primary is the neutral progress colour so they never turn red.
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
    primary = LiveWireColors.Focus,
    onPrimary = LiveWireColors.OnFocus,
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
    // Errors are never red; red is live and brand only.
    error = LiveWireColors.OnSurface,
    onError = LiveWireColors.Canvas,
)

// Bundled families, subset to Latin in res/font/ (no downloadable fonts:
// AOSP TV images have no Play Services). Display = Space Grotesk (hero titles, big
// numbers), Text = Inter (everything else), Mono = JetBrains Mono (times, channel
// numbers, "N min left", section labels, tags — always tabular figures).
private val Display = FontFamily(
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)
private val Text = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
private val Mono = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    // tag (labelSmall) asks for weight 700; JetBrains Mono ships 500/600, so the
    // heaviest bundled weight (600) stands in for Bold.
    Font(R.font.jetbrains_mono_semibold, FontWeight.Bold),
)

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 33.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, lineHeight = 9.sp, letterSpacing = 0.12.em, fontFeatureSettings = "tnum"),
)

private val overline = TextStyle(
    fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
    lineHeight = 12.sp, letterSpacing = 0.12.em, fontFeatureSettings = "tnum",
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

/**
 * True inside a focused [LiveWireSurface], or inside any caller that draws the focus fill
 * itself and wraps its content in [ProvideOnFocus]. Text and icons read
 * [LiveWireTheme.onSurface] / [LiveWireTheme.onSurfaceMuted], which flip to dark ink on the fill.
 */
val LocalOnFocus = compositionLocalOf { false }

@Composable
fun ProvideOnFocus(focused: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOnFocus provides focused, content = content)
}

object LiveWireTheme {
    val tokens: LiveWireTokens
        @Composable get() = LocalLiveWireTokens.current

    /** [LiveWireColors.OnSurface], or [LiveWireColors.OnFocus] on the focus fill. */
    val onSurface: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalOnFocus.current) LiveWireColors.OnFocus else LiveWireColors.OnSurface

    /** [LiveWireColors.OnSurfaceMuted], or [LiveWireColors.OnFocusMuted] on the focus fill. */
    val onSurfaceMuted: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalOnFocus.current) LiveWireColors.OnFocusMuted else LiveWireColors.OnSurfaceMuted

    /** [LiveWireColors.OnSurfaceSoft], or [LiveWireColors.OnFocus] on the focus fill. */
    val onSurfaceSoft: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalOnFocus.current) LiveWireColors.OnFocus else LiveWireColors.OnSurfaceSoft

    val progressFill: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalOnFocus.current) LiveWireColors.OnFocus else LiveWireColors.ProgressFill

    val progressTrack: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalOnFocus.current) LiveWireColors.ProgressTrackOnFocus else LiveWireColors.ProgressTrack
}
