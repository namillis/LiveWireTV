package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class XmltvParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="cnn.us"><display-name>CNN</display-name><icon src="http://logos/cnn.png"/></channel>
          <channel id="espn.us"><display-name>ESPN</display-name></channel>
          <programme start="20240115180000 +0000" stop="20240115190000 +0000" channel="cnn.us">
            <title>The Situation Room</title><desc>News and analysis.</desc><category>News</category>
          </programme>
          <programme start="20240115190000 +0000" stop="20240115200000 +0000" channel="cnn.us">
            <title>Anderson Cooper 360</title>
          </programme>
          <programme start="20240115130000 -0500" stop="20240115140000 -0500" channel="espn.us">
            <title>SportsCenter</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun utcMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.clear()
        c.set(y, mo - 1, d, h, mi, 0); return c.timeInMillis
    }

    @Test fun `parses channels with name and icon`() {
        val g = XmltvParser.parse(sample)
        assertEquals(2, g.channels.size)
        val cnn = g.channels.first { it.id == "cnn.us" }
        assertEquals("CNN", cnn.displayName)
        assertEquals("http://logos/cnn.png", cnn.iconUrl)
        assertNull(g.channels.first { it.id == "espn.us" }.iconUrl)
    }

    @Test fun `parses programmes with title desc category`() {
        val g = XmltvParser.parse(sample)
        val cnn = g.programmesFor("cnn.us")
        assertEquals(2, cnn.size)
        assertEquals("The Situation Room", cnn[0].title)
        assertEquals("News and analysis.", cnn[0].description)
        assertEquals("News", cnn[0].category)
        assertEquals(3, g.programmeCount)
    }

    @Test fun `normalizes timezone offsets to UTC`() {
        val g = XmltvParser.parse(sample)
        assertEquals(utcMillis(2024, 1, 15, 18, 0), g.programmesFor("cnn.us")[0].startMs)   // +0000
        assertEquals(utcMillis(2024, 1, 15, 18, 0), g.programmesFor("espn.us")[0].startMs)  // 13:00 -0500 -> 18:00Z
    }

    @Test fun `nowPlaying and upNext resolve at a fixed instant`() {
        val g = XmltvParser.parse(sample)
        val at = utcMillis(2024, 1, 15, 18, 30)
        assertEquals("The Situation Room", g.nowPlaying("cnn.us", at)?.title)
        assertEquals(0.5f, g.nowPlaying("cnn.us", at)!!.progressAt(at), 0.01f)
        assertEquals("Anderson Cooper 360", g.upNext("cnn.us", at)?.title)
    }

    @Test fun `retains only programmes overlapping requested window`() {
        val window = EpgWindow(
            startMs = utcMillis(2024, 1, 15, 19, 15),
            endMs = utcMillis(2024, 1, 15, 19, 45),
        )
        val g = XmltvParser.parse(sample, window)
        assertEquals(listOf("Anderson Cooper 360"),
            g.programmesFor("cnn.us").map { it.title })
        assertTrue(g.programmesFor("espn.us").isEmpty())
    }

    @Test fun `keeps only requested channel ids`() {
        val guide = XmltvParser.parse(
            "<tv><channel id=\"a\"><display-name>A</display-name></channel>" +
                "<channel id=\"b\"><display-name>B</display-name></channel>" +
                "<programme channel=\"a\" start=\"20260101000000 +0000\" stop=\"20260101010000 +0000\"><title>x</title></programme>" +
                "<programme channel=\"b\" start=\"20260101000000 +0000\" stop=\"20260101010000 +0000\"><title>y</title></programme></tv>",
            channelIds = setOf("b"),
        )
        assertEquals(listOf("b"), guide.channels.map { it.id })
        assertTrue(guide.programmesFor("a").isEmpty())
        assertEquals(listOf("y"), guide.programmesFor("b").map { it.title })
    }

    @Test fun `skips a leading UTF-8 byte-order mark`() {
        val guide = XmltvParser.parse(
            "\uFEFF<?xml version=\"1.0\" encoding=\"utf-8\" ?><tv>" +
                "<channel id=\"a\"><display-name>A</display-name></channel></tv>",
        )
        assertEquals(listOf("a"), guide.channels.map { it.id })
    }

    @Test fun `handles empty and malformed input`() {
        assertTrue(XmltvParser.parse("<tv></tv>").channels.isEmpty())
        assertEquals(0, XmltvParser.parse("<tv></tv>").programmeCount)
    }
}
