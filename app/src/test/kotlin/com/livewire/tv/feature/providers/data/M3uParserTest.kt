package com.livewire.tv.feature.providers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class M3uParserTest {

    private fun parse(text: String, max: Int = M3uParser.MAX_ENTRIES) =
        M3uParser.parse(StringReader(text), max)

    @Test
    fun parsesAttributesNameGroupAndGuideUrl() {
        val playlist = parse(
            """
            #EXTM3U url-tvg="http://guide.example/epg.xml.gz,http://backup/epg.xml"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://l/bbc.png" group-title="UK",BBC One HD
            http://stream.example/live/1.ts
            """.trimIndent(),
        )
        assertEquals("http://guide.example/epg.xml.gz", playlist.epgUrl)
        val entry = playlist.entries.single()
        assertEquals("BBC One HD", entry.name)
        assertEquals("bbc1.uk", entry.tvgId)
        assertEquals("http://l/bbc.png", entry.logoUrl)
        assertEquals("UK", entry.group)
        assertEquals("http://stream.example/live/1.ts", entry.url)
    }

    @Test
    fun readsTheChannelNumberFromTvgChno() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-chno="103" group-title="Film",Kino Classics
            http://s/103.ts
            #EXTINF:-1 tvg-chno="x" group-title="Film",No Number
            http://s/x.ts
            """.trimIndent(),
        ).entries
        assertEquals(103, entries[0].number)
        assertNull(entries[1].number)
    }

    @Test
    fun commaInsideQuotedAttributeDoesNotSplitName() {
        val entry = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News, World",CNN International
            http://s/cnn.m3u8
            """.trimIndent(),
        ).entries.single()
        assertEquals("News, World", entry.group)
        assertEquals("CNN International", entry.name)
    }

    @Test
    fun readsExtGrpAndVlcHeaders() {
        val entry = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel
            #EXTGRP:Sports
            #EXTVLCOPT:http-user-agent=MyPlayer/1.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://s/ch.ts
            """.trimIndent(),
        ).entries.single()
        assertEquals("Sports", entry.group)
        assertEquals("MyPlayer/1.0", entry.headers["User-Agent"])
        assertEquals("http://ref.example/", entry.headers["Referer"])
    }

    @Test
    fun skipsXtreamVodEntriesAndUrlsWithoutExtinf() {
        val playlist = parse(
            """
            #EXTM3U
            http://s/orphan.ts
            #EXTINF:-1,Live
            http://h/live/u/p/1.ts
            #EXTINF:-1,Movie
            http://h/movie/u/p/2.mp4
            #EXTINF:-1,Show
            http://h/series/u/p/3.mkv
            """.trimIndent(),
        )
        assertEquals(listOf("Live"), playlist.entries.map { it.name })
    }

    @Test
    fun fallsBackToTvgNameAndHandlesBomAndBlankLines() {
        val playlist = parse(
            "\uFEFF#EXTM3U\r\n\r\n#EXTINF:-1 tvg-name=\"Fallback\",\r\nhttp://s/a.ts\r\n",
        )
        assertNull(playlist.epgUrl)
        assertEquals("Fallback", playlist.entries.single().name)
    }

    @Test
    fun multiGroupValuesUseTheFirstGroup() {
        val entry = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Animation;Kids",Cartoons
            http://s/c.m3u8
            """.trimIndent(),
        ).entries.single()
        assertEquals("Animation", entry.group)
    }

    @Test
    fun capsEntryCount() {
        val text = buildString {
            appendLine("#EXTM3U")
            repeat(5) { appendLine("#EXTINF:-1,C$it"); appendLine("http://s/$it.ts") }
        }
        val playlist = parse(text, max = 3)
        assertEquals(3, playlist.entries.size)
        assertTrue(playlist.truncated)
    }

    @Test
    fun parsesPerEntryCatchupAttributes() {
        val entry = parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup="shift" catchup-days="5",Ch
            http://s/live/1.ts
            """.trimIndent(),
        ).entries.single()
        assertEquals("shift", entry.catchupType)
        assertEquals(5, entry.catchupDays)
        assertNull(entry.catchupSource)
    }

    @Test
    fun catchupTypeAliasAndSource() {
        val entry = parse(
            """
            #EXTM3U
            #EXTINF:-1 catchup-type="default" catchup-source="http://s/ts/{utc}.m3u8" catchup-days="7",Ch
            http://s/live/1.ts
            """.trimIndent(),
        ).entries.single()
        assertEquals("default", entry.catchupType)
        assertEquals("http://s/ts/{utc}.m3u8", entry.catchupSource)
        assertEquals(7, entry.catchupDays)
    }

    @Test
    fun headerCatchupDefaultsApplyWhenEntryOmitsThem() {
        val playlist = parse(
            """
            #EXTM3U catchup="append" catchup-days="3"
            #EXTINF:-1,WithDefault
            http://s/live/1.ts
            #EXTINF:-1 catchup="shift" catchup-days="9",Overrides
            http://s/live/2.ts
            """.trimIndent(),
        )
        val (a, b) = playlist.entries
        assertEquals("append", a.catchupType)
        assertEquals(3, a.catchupDays)
        assertEquals("shift", b.catchupType)
        assertEquals(9, b.catchupDays)
    }

    @Test
    fun noCatchupLeavesFieldsEmpty() {
        val entry = parse(
            """
            #EXTM3U
            #EXTINF:-1,Plain
            http://s/live/1.ts
            """.trimIndent(),
        ).entries.single()
        assertNull(entry.catchupType)
        assertEquals(0, entry.catchupDays)
    }
}
