package com.livewire.tv.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.livewire.tv.R

/**
 * B · Grid text styles at the mockups' scale (mockup px x 0.75 = dp/sp). Space Grotesk for
 * display titles, Inter for UI text, JetBrains Mono with tabular figures for numbers, times,
 * the clock and overline labels.
 */
object LiveWireType {
    /** The big programme title on Home and in the player (32px). */
    val Display = TextStyle(
        fontFamily = LiveWireFonts.Display, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.0125).em,
    )
    /** Dialog and section headings (26px). */
    val Heading = TextStyle(fontFamily = LiveWireFonts.Display, fontWeight = FontWeight.Bold, fontSize = 19.5.sp, lineHeight = 23.sp)
    /** Row titles and tab labels (15px, 600). */
    val Title = TextStyle(fontFamily = LiveWireFonts.Text, fontWeight = FontWeight.SemiBold, fontSize = 11.25.sp, lineHeight = 14.sp)
    /** The band title in the Guide (19px). */
    val TitleLarge = TextStyle(fontFamily = LiveWireFonts.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.25.sp, lineHeight = 18.sp)
    /** Meta lines (15px). */
    val Body = TextStyle(fontFamily = LiveWireFonts.Text, fontWeight = FontWeight.Normal, fontSize = 11.25.sp, lineHeight = 16.sp)
    /** Second lines under row titles (13px). */
    val Sub = TextStyle(fontFamily = LiveWireFonts.Text, fontWeight = FontWeight.Normal, fontSize = 9.75.sp, lineHeight = 13.sp)
    /** Channel numbers (15px mono). */
    val Number = TextStyle(
        fontFamily = LiveWireFonts.Mono, fontWeight = FontWeight.SemiBold,
        fontSize = 11.25.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum",
    )
    /** Times, "36m", "66 min left" (12-13px mono). */
    val Mono = TextStyle(
        fontFamily = LiveWireFonts.Mono, fontWeight = FontWeight.Medium,
        fontSize = 9.sp, lineHeight = 12.sp, fontFeatureSettings = "tnum",
    )
    /** Section overlines: "ON NOW", "103 · KINO CLASSICS" (11-12px, 0.16em). */
    val Overline = TextStyle(
        fontFamily = LiveWireFonts.Mono, fontWeight = FontWeight.SemiBold,
        fontSize = 8.25.sp, lineHeight = 11.sp, letterSpacing = 0.16.em, fontFeatureSettings = "tnum",
    )
    /** The LIVE badge label (12px, 600, 0.16em). */
    val Live = TextStyle(
        fontFamily = LiveWireFonts.Mono, fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.16.em,
    )
    /** Small tags: "PREVIEW · MUTED", "WATCHING" (10px). */
    val Tag = TextStyle(
        fontFamily = LiveWireFonts.Mono, fontWeight = FontWeight.SemiBold,
        fontSize = 7.5.sp, lineHeight = 9.sp, letterSpacing = 0.14.em,
    )
    /** Key hints: "OK Watch" (12px mono). */
    val Hint = TextStyle(fontFamily = LiveWireFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 9.sp, lineHeight = 11.sp)
}

/**
 * The LIVE badge: the pulse line running into the red dot, then "LIVE". [color] tints the
 * line and the label; the dot is always red.
 */
@Composable
fun LiveBadge(
    modifier: Modifier = Modifier,
    label: String = "LIVE",
    color: Color = LiveWireTheme.onSurface,
    width: Dp = 22.dp,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(width = width, height = width * 16f / 30f)) {
            Icon(painterResource(R.drawable.ic_live_pulse), contentDescription = null, tint = color)
            Image(painterResource(R.drawable.ic_live_dot), contentDescription = null)
        }
        Text(label, style = LiveWireType.Live, color = color)
    }
}

/**
 * A section overline. [brandRule] draws the short red rule before it (the brand accent on
 * section labels); [liveDot] draws a red on-air dot instead, for labels about what is live.
 */
@Composable
fun Overline(
    text: String,
    modifier: Modifier = Modifier,
    brandRule: Boolean = true,
    liveDot: Boolean = false,
    color: Color = LiveWireTheme.onSurfaceMuted,
    style: TextStyle = LiveWireType.Overline,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when {
            liveDot -> {
                Box(Modifier.size(5.dp).background(LiveWireColors.Live, CircleShape))
                Spacer(Modifier.width(7.dp))
            }
            brandRule -> {
                Box(Modifier.width(9.dp).height(1.5.dp).background(LiveWireColors.Live, RoundedCornerShape(1.dp)))
                Spacer(Modifier.width(6.dp))
            }
        }
        Text(text.uppercase(), style = style, color = color, maxLines = 1)
    }
}

/** The red top edge on panels and dialogs (the brand accent). Draw after the background. */
fun Modifier.brandEdge(width: Dp = LiveWireDimens.BrandEdge): Modifier = drawWithContent {
    drawContent()
    drawRect(LiveWireColors.Live, topLeft = Offset.Zero, size = Size(size.width, width.toPx()))
}

/** "OK  Watch" key hints. */
@Composable
fun KeyHints(vararg hints: Pair<String, String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    key,
                    style = LiveWireType.Hint.copy(fontWeight = FontWeight.SemiBold, fontSize = 8.25.sp),
                    color = LiveWireTheme.onSurface,
                    modifier = Modifier
                        .border(1.dp, LiveWireColors.Border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Text(label, style = LiveWireType.Hint, color = LiveWireTheme.onSurfaceMuted)
            }
        }
    }
}
