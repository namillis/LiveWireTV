package com.livewire.tv.feature.settings

import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import com.livewire.tv.core.player.CaptionStyling
import com.livewire.tv.feature.settings.data.CaptionBackground
import com.livewire.tv.feature.settings.data.CaptionColor
import com.livewire.tv.feature.settings.data.CaptionEdge
import com.livewire.tv.feature.settings.data.CaptionFont
import com.livewire.tv.feature.settings.data.CaptionPlacement
import com.livewire.tv.feature.settings.data.CaptionSize
import com.livewire.tv.feature.settings.data.SubtitleStyle
import com.livewire.tv.feature.settings.data.canStep
import com.livewire.tv.feature.settings.data.enumOr
import com.livewire.tv.feature.settings.data.step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {

    @Test
    fun defaultsMatchMedia3sOwnCaptionLook() {
        val s = SubtitleStyle()
        assertTrue(s.isDefault)
        assertEquals(0.0533f, s.size.fraction)
        val p = CaptionStyling.paintOf(s)
        assertEquals(0xFFFFFFFF.toInt(), p.foreground)
        assertEquals(0xFF000000.toInt(), p.background)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, p.edgeType)
        assertEquals(CaptionPlacement.AS_BROADCAST, s.placement)
    }

    @Test
    fun summaryDescribesTheLook() {
        assertEquals("Medium · white on black · as broadcast", SubtitleStyle().summary)
        assertEquals(
            "Large · yellow outlined · mono · always bottom",
            SubtitleStyle(
                size = CaptionSize.LARGE, color = CaptionColor.YELLOW, background = CaptionBackground.NONE,
                edge = CaptionEdge.OUTLINE, font = CaptionFont.MONO, placement = CaptionPlacement.BOTTOM,
            ).summary,
        )
        assertEquals(
            "Small · white on see-through · as broadcast",
            SubtitleStyle(size = CaptionSize.SMALL, background = CaptionBackground.SEE_THROUGH).summary,
        )
        assertEquals(
            "Medium · cyan, no background · as broadcast",
            SubtitleStyle(color = CaptionColor.CYAN, background = CaptionBackground.NONE).summary,
        )
    }

    @Test
    fun paintMapsEdgesAndBackgrounds() {
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, CaptionStyling.paintOf(SubtitleStyle(edge = CaptionEdge.OUTLINE)).edgeType)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, CaptionStyling.paintOf(SubtitleStyle(edge = CaptionEdge.SHADOW)).edgeType)
        assertEquals(0, CaptionStyling.paintOf(SubtitleStyle(background = CaptionBackground.NONE)).background)
        // See-through keeps black but drops the alpha.
        val seeThrough = CaptionStyling.paintOf(SubtitleStyle(background = CaptionBackground.SEE_THROUGH)).background
        assertEquals(0, seeThrough and 0x00FFFFFF)
        assertTrue((seeThrough ushr 24) in 1..254)
    }

    @Test
    fun stepStopsAtEitherEnd() {
        assertEquals(CaptionSize.LARGE, CaptionSize.MEDIUM.step(forward = true))
        assertEquals(CaptionSize.SMALL, CaptionSize.MEDIUM.step(forward = false))
        assertEquals(CaptionSize.SMALL, CaptionSize.SMALL.step(forward = false))
        assertEquals(CaptionSize.EXTRA_LARGE, CaptionSize.EXTRA_LARGE.step(forward = true))
        assertFalse(CaptionSize.SMALL.canStep(forward = false))
        assertFalse(CaptionSize.EXTRA_LARGE.canStep(forward = true))
        assertTrue(CaptionSize.MEDIUM.canStep(forward = true))
    }

    @Test
    fun unknownOrMissingStoredValuesFallBack() {
        assertEquals(CaptionColor.YELLOW, enumOr("YELLOW", CaptionColor.WHITE))
        assertEquals(CaptionColor.WHITE, enumOr("MAGENTA", CaptionColor.WHITE))
        assertEquals(CaptionColor.WHITE, enumOr(null, CaptionColor.WHITE))
    }

    @Test
    fun rowKeyOrdersCaptionRowsTopToBottom() {
        val n = Cue.LINE_TYPE_NUMBER
        val f = Cue.LINE_TYPE_FRACTION
        // A top row (line 1), a row counted from the bottom (-2), the last row (-1), then an
        // unpositioned cue, which Media3 draws at the very bottom.
        val keys = listOf(
            CaptionStyling.rowKey(1f, n),
            CaptionStyling.rowKey(-2f, n),
            CaptionStyling.rowKey(-1f, n),
            CaptionStyling.rowKey(Cue.DIMEN_UNSET, Cue.TYPE_UNSET),
        )
        assertEquals(keys.sorted(), keys)
        // A fraction near the top sorts above a number row near the bottom, and vice versa.
        assertTrue(CaptionStyling.rowKey(0.1f, f) < CaptionStyling.rowKey(-3f, n))
        assertTrue(CaptionStyling.rowKey(0.9f, f) > CaptionStyling.rowKey(2f, n))
    }
}
