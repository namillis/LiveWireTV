package com.livewire.tv.feature.epg.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How screens show the time of a Live Now event ([EpgProgramme.endIsEstimate]). The pool often
 * only estimates an event's end, so this names the start and never the end.
 */
object EventTimes {
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayAndTime = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

    /** True for an event that hasn't started at [now]; screens label it "UP NEXT" rather than "ON NOW". */
    fun isUpcoming(programme: EpgProgramme, now: Long): Boolean =
        programme.endIsEstimate && now < programme.startMs

    /** "Starts 8:15 PM" before the start, "Started 8:15 PM" after, with the day when it isn't today. */
    fun label(programme: EpgProgramme, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val start = Instant.ofEpochMilli(programme.startMs).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = (if (start.toLocalDate() == today) time else dayAndTime).format(start)
        return if (now < programme.startMs) "Starts $clock" else "Started $clock"
    }
}
