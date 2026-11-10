package com.livewire.tv.core.player

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.livewire.tv.R
import com.livewire.tv.feature.settings.data.CaptionEdge
import com.livewire.tv.feature.settings.data.CaptionFont
import com.livewire.tv.feature.settings.data.CaptionPlacement
import com.livewire.tv.feature.settings.data.SubtitleStyle

/** The colors and edge a [SubtitleStyle] draws with, as plain ints so tests need no Android. */
data class CaptionPaint(val foreground: Int, val background: Int, val edgeType: Int, val edgeColor: Int)

/**
 * Turns a [SubtitleStyle] into Media3 caption settings, and re-lays cues for
 * [CaptionPlacement.BOTTOM].
 *
 * The broadcaster's own colors, fonts and sizes are always dropped so the user's style wins
 * (SubtitleView.setApplyEmbeddedStyles(false) / setApplyEmbeddedFontSizes(false)). That does
 * NOT drop a cue's position: Media3's removeAllEmbeddedStyling clears window color, style spans
 * and text size only, so with [CaptionPlacement.AS_BROADCAST] captions stay where the
 * broadcaster put them.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object CaptionStyling {

    /** Rows on a CEA-608 caption screen; used to put fraction- and number-positioned cues in one order. */
    private const val CAPTION_ROWS = 15f

    fun paintOf(s: SubtitleStyle) = CaptionPaint(
        foreground = s.color.argb,
        background = s.background.argb,
        edgeType = when (s.edge) {
            CaptionEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            CaptionEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            CaptionEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        },
        edgeColor = 0xFF000000.toInt(),
    )

    fun typeface(context: Context, font: CaptionFont): Typeface? = when (font) {
        CaptionFont.DEFAULT -> null // Media3's default, the system sans
        CaptionFont.MONO -> ResourcesCompat.getFont(context, R.font.jetbrains_mono_semibold)
        CaptionFont.SERIF -> Typeface.SERIF
    }

    /** Style [view] as [s]. Text size ignores view padding, so raising captions doesn't shrink them. */
    fun apply(view: SubtitleView, s: SubtitleStyle) {
        val p = paintOf(s)
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(
            CaptionStyleCompat(p.foreground, p.background, Color.TRANSPARENT, p.edgeType, p.edgeColor, typeface(view.context, s.font)),
        )
        view.setFractionalTextSize(s.size.fraction, /* ignorePadding = */ true)
    }

    /**
     * A cue's row from the top of the screen, for stacking. CEA-608 cues carry a line NUMBER
     * (negative counts up from the bottom); others may carry a FRACTION of the height. Cues
     * with no line go last, the way Media3 draws them at the bottom.
     */
    fun rowKey(line: Float, lineType: Int): Float = when {
        line == Cue.DIMEN_UNSET -> CAPTION_ROWS * 10
        lineType == Cue.LINE_TYPE_FRACTION -> line * CAPTION_ROWS
        line >= 0f -> line
        else -> CAPTION_ROWS + line
    }

    /**
     * Cues as they should be drawn for [placement]. [CaptionPlacement.BOTTOM] joins every text
     * cue, top row first, into one centered cue with no position, which Media3 draws at the
     * bottom. (Unpositioned cues are each drawn at the bottom, so leaving them separate would
     * stack them on top of each other.) Bitmap cues are passed through untouched.
     */
    fun placeCues(cues: List<Cue>, placement: CaptionPlacement): List<Cue> {
        if (placement == CaptionPlacement.AS_BROADCAST) return cues
        val text = cues.filter { it.bitmap == null && !it.text.isNullOrEmpty() }
        if (text.isEmpty()) return cues
        val joined = SpannableStringBuilder()
        text.sortedBy { rowKey(it.line, it.lineType) }.forEachIndexed { i, cue ->
            if (i > 0) joined.append('\n')
            joined.append(cue.text)
        }
        val merged = Cue.Builder().setText(joined).setTextAlignment(Layout.Alignment.ALIGN_CENTER).build()
        return cues.filter { it.bitmap != null } + merged
    }
}

/**
 * Draws the player's captions with the user's [SubtitleStyle] into [view], a [SubtitleView]
 * the screen places ABOVE its overlays (the PlayerView's own caption view sits under them, so
 * the info bar's gradient would dim the captions). The PlayerView's caption view is hidden.
 * [onVideoBounds] reports where the picture is inside the PlayerView (px, relative to it), so
 * the screen can lay [view] over exactly the picture and cue positions line up with it.
 *
 * Cues come from the player through [CaptionStyling.placeCues]. [setRaised] keeps a share of
 * the picture free at the bottom with view padding, so captions clear the info bar. Padding
 * moves positioned cues as well as unpositioned ones (Media3's bottomPaddingFraction moves only
 * the latter).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerCaptions(
    playerView: PlayerView,
    private val view: SubtitleView,
    onVideoBounds: (Rect) -> Unit,
) : Player.Listener {
    private var player: Player? = null
    private var lastCues: List<Cue> = emptyList()
    private var style = SubtitleStyle()
    private var raiseFraction = 0f

    init {
        playerView.subtitleView?.visibility = View.GONE
        val frame: View = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame) ?: playerView
        frame.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            // exo_content_frame is a direct child of the PlayerView, so its frame is already
            // relative to it.
            onVideoBounds(if (v === playerView) Rect(0, 0, r - l, b - t) else Rect(l, t, r, b))
        }
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updatePadding()
        }
        CaptionStyling.apply(view, style)
    }

    private fun updatePadding() {
        val px = (view.height * raiseFraction).toInt()
        if (view.paddingBottom != px) view.post { view.setPadding(0, 0, 0, px) }
    }

    fun bind(p: Player?) {
        if (player === p) return
        player?.removeListener(this)
        player = p
        p?.addListener(this)
        show(p?.currentCues?.cues.orEmpty())
    }

    fun setStyle(s: SubtitleStyle) {
        if (s == style) return
        style = s
        CaptionStyling.apply(view, s)
        show(lastCues)
    }

    /** Keep the bottom [fraction] of the picture clear of captions (0 = not raised). */
    fun setRaised(fraction: Float) {
        if (fraction == raiseFraction) return
        raiseFraction = fraction
        updatePadding()
    }

    override fun onCues(cueGroup: CueGroup) = show(cueGroup.cues)

    private fun show(cues: List<Cue>) {
        lastCues = cues
        view.setCues(CaptionStyling.placeCues(cues, style.placement))
    }
}
