package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgChannel
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Streaming XMLTV parser (bounded memory for multi-MB guides). Kotlin port of the
 * Flutter xmltv_parser.dart, using XmlPullParser instead of a DOM.
 *
 * XMLTV:
 *   <tv>
 *     <channel id="cnn.us"><display-name>CNN</display-name><icon src="..."/></channel>
 *     <programme start="20240115183000 +0000" stop="..." channel="cnn.us">
 *       <title>…</title><desc>…</desc><category>…</category>
 *     </programme>
 *   </tv>
 */
object XmltvParser {

    fun parse(xml: String): EpgGuide {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        return parse(parser)
    }

    private fun parse(parser: XmlPullParser): EpgGuide {
        val channels = mutableListOf<EpgChannel>()
        val programmes = mutableMapOf<String, MutableList<EpgProgramme>>()

        // Accumulators for the element currently open.
        var chId: String? = null
        var chName: String? = null
        var chIcon: String? = null

        var pChannel: String? = null
        var pStart: Long? = null
        var pStop: Long? = null
        var pTitle: String? = null
        var pDesc: String? = null
        var pCategory: String? = null

        var textTarget: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> { chId = parser.getAttributeValue(null, "id"); chName = null; chIcon = null }
                    "programme" -> {
                        pChannel = parser.getAttributeValue(null, "channel")
                        pStart = parseXmltvTime(parser.getAttributeValue(null, "start"))
                        pStop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                        pTitle = null; pDesc = null; pCategory = null
                    }
                    "display-name", "title", "desc", "category" -> textTarget = parser.name
                    "icon" -> parser.getAttributeValue(null, "src")?.let { chIcon = it }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text?.trim().orEmpty()
                    if (t.isNotEmpty()) when (textTarget) {
                        "display-name" -> if (chName == null) chName = t
                        "title" -> if (pTitle == null) pTitle = t
                        "desc" -> if (pDesc == null) pDesc = t
                        "category" -> if (pCategory == null) pCategory = t
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> {
                        val id = chId
                        if (!id.isNullOrEmpty()) {
                            channels.add(EpgChannel(id = id, displayName = chName ?: id, iconUrl = chIcon))
                        }
                        chId = null
                    }
                    "programme" -> {
                        val ch = pChannel; val start = pStart; val stop = pStop
                        if (!ch.isNullOrEmpty() && start != null && stop != null) {
                            programmes.getOrPut(ch) { mutableListOf() }.add(
                                EpgProgramme(
                                    channelId = ch,
                                    startMs = start,
                                    stopMs = stop,
                                    title = pTitle ?: "No title",
                                    description = pDesc,
                                    category = pCategory,
                                ),
                            )
                        }
                        pChannel = null
                    }
                    "display-name", "title", "desc", "category" -> textTarget = null
                }
            }
            event = parser.next()
        }
        return EpgGuide(channels = channels, programmesByChannel = programmes)
    }

    /**
     * XMLTV time: "YYYYMMDDHHMMSS ZZZ" (offset like "+0000" optional). Returns epoch
     * millis (UTC), or null if unparseable.
     */
    fun parseXmltvTime(raw: String?): Long? {
        val s = raw?.trim() ?: return null
        if (s.length < 14) return null
        val d = s.substring(0, 14)
        val year = d.substring(0, 4).toIntOrNull() ?: return null
        val month = d.substring(4, 6).toIntOrNull() ?: return null
        val day = d.substring(6, 8).toIntOrNull() ?: return null
        val hour = d.substring(8, 10).toIntOrNull() ?: return null
        val minute = d.substring(10, 12).toIntOrNull() ?: return null
        val second = d.substring(12, 14).toIntOrNull() ?: return null

        // Optional timezone offset, e.g. " +0100" / "-0500".
        var offsetMs = 0L
        val tz = if (s.length > 14) s.substring(14).trim() else ""
        if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
            val sign = if (tz[0] == '-') -1 else 1
            val oh = tz.substring(1, 3).toIntOrNull() ?: 0
            val om = tz.substring(3, 5).toIntOrNull() ?: 0
            offsetMs = sign * (oh * 3600_000L + om * 60_000L)
        }

        // Build the wall-clock instant as UTC, then subtract the offset to get true UTC.
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis - offsetMs
    }
}
