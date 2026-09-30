package com.livewire.tv.feature.update

import kotlin.math.roundToInt

/**
 * Pure formatting and parsing for the update UI (no Android / Compose), so every string the
 * dialog and the Settings About rows show is unit-testable. The composables call these and
 * render the result; nothing here reaches for a clock, resources or the framework.
 *
 * Colour/behaviour discipline lives in the composables; this file only turns raw model data
 * (bytes, epoch millis, a markdown release body) into the exact text the mockup shows:
 * "3.6 MB", "Released Sep 30", "Today · 8:02 PM", and the What's-new bullet lines.
 */
object UpdateFormat {

    // ── Download size ────────────────────────────────────────────────────────────

    /**
     * A release/download size as the mockup shows it: "3.6 MB", "850 KB", "1.2 GB". Uses
     * decimal (1000-based) units to match how GitHub reports asset sizes, one decimal place
     * for MB/GB and none for KB/bytes. Zero or negative is rendered as "—" (unknown).
     */
    fun size(bytes: Long): String {
        if (bytes <= 0) return "—"
        val kb = bytes / 1000.0
        if (kb < 1.0) return "$bytes B"
        val mb = kb / 1000.0
        if (mb < 1.0) return "${kb.roundToInt()} KB"
        val gb = mb / 1000.0
        if (gb < 1.0) return "${oneDecimal(mb)} MB"
        return "${oneDecimal(gb)} GB"
    }

    /** "2.3 MB of 3.6 MB" for the download progress meta line. */
    fun progressBytes(downloaded: Long, total: Long): String =
        "${size(downloaded)} of ${size(total)}"

    private fun oneDecimal(v: Double): String {
        val rounded = (v * 10).roundToInt() / 10.0
        // Trim a trailing ".0" so "3.0 MB" reads "3 MB", matching the mockup's clean numbers.
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    // ── Release date ─────────────────────────────────────────────────────────────

    /**
     * The "Released <date>" fragment for the dialog meta line. [publishedAtEpochMs] is the
     * release's publish time; [monthDay] renders it as "Sep 30". Null (no date) yields null so
     * the caller can drop the fragment.
     */
    fun releasedFragment(dateLabel: String?): String? =
        dateLabel?.let { "Released $it" }

    // ── Notes (What's new) ─────────────────────────────────────────────────────────

    /**
     * One What's-new line: [text] plus whether it is a real markdown list item ([bullet]).
     * Only `-`/`*`/`+` list items get a bullet dot in the dialog; every other line (a lead-in
     * like "What's new in this build:", a heading, a plain paragraph) renders as plain text.
     */
    data class NoteLine(val text: String, val bullet: Boolean)

    /**
     * Turn a GitHub release body (markdown, plain text, or null) into the What's-new lines the
     * dialog shows. Rules, matching the brief:
     *  - a markdown bullet (`- `, `* `, `+ `) becomes a [NoteLine] with `bullet = true` and the
     *    marker stripped;
     *  - any other non-empty line becomes a [NoteLine] with `bullet = false` (plain text — no
     *    dot), so a lead-in line like "What's new in this build:" is NOT bulleted;
     *  - blank lines, markdown headings (`#`, `##`, …) reduce to their text as a plain line, and
     *    horizontal rules (`---`, `***`) are dropped;
     *  - inline markdown emphasis (`**bold**`, `` `code` ``) markers are stripped.
     *
     * Returns an empty list when there is nothing to show (the caller then hides the box).
     */
    fun notesLines(body: String?): List<NoteLine> {
        if (body.isNullOrBlank()) return emptyList()
        val out = ArrayList<NoteLine>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.all { it == '-' || it == '=' || it == '*' } && line.length >= 3) continue // hr
            if (line.startsWith("#")) {
                val text = line.trimStart('#', ' ')
                if (text.isNotEmpty()) out += NoteLine(stripInline(text), bullet = false)
                continue
            }
            if (isBullet(line)) {
                out += NoteLine(stripInline(line.substring(2).trim()), bullet = true)
            } else {
                out += NoteLine(stripInline(line), bullet = false)
            }
        }
        return out
    }

    /** True when the line begins with a markdown bullet marker (`-`, `*`, `+`) then a space. */
    private fun isBullet(line: String): Boolean =
        line.length >= 2 && line[0] in "-*+" && line[1] == ' '

    /** Strip common inline markdown emphasis markers, leaving readable plain text. */
    private fun stripInline(s: String): String =
        s.replace("**", "").replace("__", "").replace("`", "").trim()

    // ── About: version + build ───────────────────────────────────────────────────

    /** The About version row title, e.g. "LiveWire · 0.1.3 (build 47)". */
    fun versionLine(versionName: String, versionCode: Long): String =
        "LiveWire · ${cleanVersion(versionName)} (build $versionCode)"

    /** Strip the app's own `-debug` build suffix for display, so the About row reads cleanly. */
    fun cleanVersion(versionName: String): String = versionName.removeSuffix("-debug")

    // ── About: "Check for updates" subtitle ──────────────────────────────────────

    /** Subtitle when the installed build is current. */
    fun upToDateSubtitle(currentVersion: String): String =
        "You're on the latest version ($currentVersion)"

    /** Subtitle while a check is in flight. */
    const val CHECKING_SUBTITLE = "Checking…"

    /** Subtitle when a newer version was found. */
    fun availableSubtitle(version: String): String = "Version $version is available"

    /** Subtitle shown in a debug build where updates are compiled out. */
    const val DEBUG_DISABLED_SUBTITLE = "Updates are off in debug builds"

    // ── About: "Last checked" relative time ──────────────────────────────────────

    /**
     * A relative "last checked" label from two epoch-millis timestamps. Never checked
     * ([lastCheckMs] <= 0) → "Never". Otherwise: "Just now" (< 1 min), "N minutes ago"
     * (< 1 h), "N hours ago" (< 1 day), "Yesterday" (1 day), "N days ago" (< 7 days),
     * else the absolute [absoluteDateLabel] the caller passes (formatted with the device
     * locale). A future timestamp (clock skew) reads "Just now".
     */
    fun lastCheckedLabel(lastCheckMs: Long, nowMs: Long, absoluteDateLabel: String?): String {
        if (lastCheckMs <= 0L) return "Never"
        val delta = nowMs - lastCheckMs
        if (delta < 60_000L) return "Just now"
        val minutes = delta / 60_000L
        if (minutes < 60L) return "$minutes ${plural(minutes, "minute")} ago"
        val hours = delta / 3_600_000L
        if (hours < 24L) return "$hours ${plural(hours, "hour")} ago"
        val days = delta / 86_400_000L
        if (days == 1L) return "Yesterday"
        if (days < 7L) return "$days days ago"
        return absoluteDateLabel ?: "$days days ago"
    }

    private fun plural(n: Long, unit: String): String = if (n == 1L) unit else "${unit}s"

    // ── "Updated to X" note ──────────────────────────────────────────────────────

    /** The transient Home note shown once after a successful self-update. */
    fun updatedNote(version: String): String = "Updated to $version"
}
