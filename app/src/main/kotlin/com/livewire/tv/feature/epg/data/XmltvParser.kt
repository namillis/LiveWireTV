package com.livewire.tv.feature.epg.data

import com.livewire.tv.feature.epg.domain.EpgChannel
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.PushbackReader
import java.io.Reader
import java.io.StringReader

/**
 * Streaming XMLTV parser. It consumes a [Reader] so repositories do not need to build a
 * second in-memory XML string. When [window] is supplied, programmes outside it are
 * discarded during parsing instead of being retained by [EpgGuide].
 */
object XmltvParser {

    fun parse(xml: String, window: EpgWindow? = null, channelIds: Set<String>? = null): EpgGuide =
        parse(StringReader(xml), window, channelIds)

    /**
     * [channelIds], when given, keeps only those channels and their programmes. Provider
     * guides often cover thousands of channels a screen never shows, so filtering
     * during the parse is what keeps a large guide within a low-RAM budget.
     */
    fun parse(reader: Reader, window: EpgWindow? = null, channelIds: Set<String>? = null): EpgGuide {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(skipByteOrderMark(reader))
        return parse(parser, window, channelIds)
    }

    /**
     * Many provider guides start with a UTF-8 byte-order mark. A Reader passes it
     * through as U+FEFF, which the pull parser rejects before the XML declaration.
     */
    private fun skipByteOrderMark(reader: Reader): Reader {
        val pushback = PushbackReader(reader, 1)
        val first = pushback.read()
        if (first != -1 && first != BYTE_ORDER_MARK) pushback.unread(first)
        return pushback
    }

    private const val BYTE_ORDER_MARK = 0xFEFF

    private fun parse(parser: XmlPullParser, window: EpgWindow?, channelIds: Set<String>?): EpgGuide {
        fun wanted(id: String) = channelIds == null || id in channelIds
        val channels = mutableListOf<EpgChannel>()
        val programmes = mutableMapOf<String, MutableList<EpgProgramme>>()

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
                    "channel" -> {
                        chId = parser.getAttributeValue(null, "id")
                        chName = null
                        chIcon = null
                    }
                    "programme" -> {
                        pChannel = parser.getAttributeValue(null, "channel")
                        pStart = parseXmltvTime(parser.getAttributeValue(null, "start"))
                        pStop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                        pTitle = null
                        pDesc = null
                        pCategory = null
                    }
                    "display-name", "title", "desc", "category" -> textTarget = parser.name
                    "icon" -> parser.getAttributeValue(null, "src")?.let { chIcon = it }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) when (textTarget) {
                        "display-name" -> if (chName == null) chName = text
                        "title" -> if (pTitle == null) pTitle = text
                        "desc" -> if (pDesc == null) pDesc = text
                        "category" -> if (pCategory == null) pCategory = text
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> {
                        val id = chId
                        if (!id.isNullOrEmpty() && wanted(id)) {
                            channels.add(EpgChannel(id = id, displayName = chName ?: id, iconUrl = chIcon))
                        }
                        chId = null
                    }
                    "programme" -> {
                        val channelId = pChannel
                        val start = pStart
                        val stop = pStop
                        if (!channelId.isNullOrEmpty() && wanted(channelId) && start != null && stop != null) {
                            val programme = EpgProgramme(
                                channelId = channelId,
                                startMs = start,
                                stopMs = stop,
                                title = pTitle ?: "No title",
                                description = pDesc,
                                category = pCategory,
                            )
                            if (window == null || window.overlaps(programme)) {
                                programmes.getOrPut(channelId) { mutableListOf() }.add(programme)
                            }
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
        val value = raw?.trim() ?: return null
        if (value.length < 14) return null
        val date = value.substring(0, 14)
        val year = date.substring(0, 4).toIntOrNull() ?: return null
        val month = date.substring(4, 6).toIntOrNull() ?: return null
        val day = date.substring(6, 8).toIntOrNull() ?: return null
        val hour = date.substring(8, 10).toIntOrNull() ?: return null
        val minute = date.substring(10, 12).toIntOrNull() ?: return null
        val second = date.substring(12, 14).toIntOrNull() ?: return null

        var offsetMs = 0L
        val timezone = if (value.length > 14) value.substring(14).trim() else ""
        if (timezone.length >= 5 && (timezone[0] == '+' || timezone[0] == '-')) {
            val sign = if (timezone[0] == '-') -1 else 1
            val offsetHours = timezone.substring(1, 3).toIntOrNull() ?: 0
            val offsetMinutes = timezone.substring(3, 5).toIntOrNull() ?: 0
            offsetMs = sign * (offsetHours * 3_600_000L + offsetMinutes * 60_000L)
        }

        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar.timeInMillis - offsetMs
    }
}
