package com.livewire.tv.core.player

/**
 * The player's track and format state, exposed by [PlaybackEngine] so the UI can build the
 * Options panel WITHOUT reaching into ExoPlayer's own types (Media3 `@UnstableApi`). The
 * engine translates ExoPlayer's `Tracks`/`Format` into these plain data classes; the UI
 * selects a track by its stable [id] and never sees a `TrackGroup`.
 */
data class MediaInfo(
    val audioTracks: List<AudioTrack> = emptyList(),
    val textTracks: List<TextTrack> = emptyList(),
    /** id of the selected audio track, or null when none is selected/only one exists unnamed. */
    val selectedAudioId: String? = null,
    /** id of the selected text track, or null when subtitles are OFF. */
    val selectedTextId: String? = null,
    val video: VideoFormat? = null,
) {
    /** True when the stream offers at least one subtitle/caption track the user can turn on. */
    val hasSubtitles: Boolean get() = textTracks.isNotEmpty()
    val hasMultipleAudio: Boolean get() = audioTracks.size > 1
}

/**
 * One selectable audio track. [id] is a stable handle the UI passes back to
 * [PlaybackEngine.selectAudioTrack]; it encodes the track-group + track index so the engine
 * can map it back without the UI holding ExoPlayer objects.
 */
data class AudioTrack(
    val id: String,
    /** Human label: language display name, falling back to "Audio 1" style. */
    val label: String,
    val language: String? = null,
    val channelCount: Int = 0,
    val codec: String? = null,
) {
    /** "English · Stereo", "Audio 1 · 5.1" — the value shown on the Audio row. */
    val summary: String get() = PlayerFormats.audioSummary(label, channelCount)
}

/** One selectable subtitle/caption track. [id] round-trips to [PlaybackEngine.selectTextTrack]. */
data class TextTrack(
    val id: String,
    val label: String,
    val language: String? = null,
)

/** Decoded video characteristics for the Stream info sub-list. Any field may be unknown (null/0). */
data class VideoFormat(
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannelCount: Int = 0,
    /** Peak/average bitrate in bits per second, or 0 when the container does not declare it. */
    val bitrateBps: Int = 0,
)

/**
 * Encodes an ExoPlayer (trackGroupIndex, trackIndex) pair as the stable string [MediaInfo]
 * exposes and [PlaybackEngine.selectAudioTrack]/[selectTextTrack] round-trip. Kept pure and
 * separate so the encoding is unit-testable without an engine.
 */
object TrackIds {
    fun of(groupIndex: Int, trackIndex: Int): String = "$groupIndex:$trackIndex"

    /** (groupIndex, trackIndex) or null when [id] is malformed. */
    fun parse(id: String): Pair<Int, Int>? {
        val parts = id.split(":")
        if (parts.size != 2) return null
        val g = parts[0].toIntOrNull() ?: return null
        val t = parts[1].toIntOrNull() ?: return null
        if (g < 0 || t < 0) return null
        return g to t
    }
}
