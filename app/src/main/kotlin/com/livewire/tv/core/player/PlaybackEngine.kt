package com.livewire.tv.core.player

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/** Coarse playback state for the UI to render buffering/error overlays. */
enum class PlaybackState { IDLE, BUFFERING, PLAYING, PAUSED, ERROR, ENDED }

/**
 * Whether the current stream is live or an archive (catch-up / time shift) window.
 * ARCHIVE carries the absolute UTC bounds of the requested window so the UI can render
 * elapsed/remaining and clamp seeks.
 */
sealed interface PlaybackMode {
    data object Live : PlaybackMode
    data class Archive(val startMs: Long, val endMs: Long) : PlaybackMode
}

/** Snapshot the UI observes. */
data class PlaybackStatus(
    val state: PlaybackState = PlaybackState.IDLE,
    val errorMessage: String? = null,
    val isLive: Boolean = true,
    val mode: PlaybackMode = PlaybackMode.Live,
)

/**
 * The swappable playback seam (see ADR-0001). We ship [ExoPlayerEngine] (hardware
 * decode by default). A future MpvEngine implements this same interface and is
 * dropped in as a fallback for streams ExoPlayer can't handle — no UI changes.
 */
interface PlaybackEngine {
    val status: StateFlow<PlaybackStatus>

    /**
     * Audio/subtitle tracks and decoded video format, kept in sync as ExoPlayer resolves the
     * stream. The Options panel reads this instead of touching ExoPlayer's own types.
     */
    val mediaInfo: StateFlow<MediaInfo>

    /**
     * Current playback position within the archive window, in UTC epoch millis, or
     * [androidx.media3.common.C.TIME_UNSET] when live/unknown. For an ARCHIVE stream this is
     * `requestedStart + player.currentPosition`, so the UI can render an absolute scrub bar.
     * Polled on a light cadence, not per frame, to avoid spamming recomposition.
     */
    val position: StateFlow<Long>

    /** Length of the current archive window in millis, or [androidx.media3.common.C.TIME_UNSET]. */
    val duration: StateFlow<Long>

    /** True when the current stream can be seeked (archive mode with a resolved window). */
    val canSeek: Boolean

    /** Attach the render surface (from the Compose PlayerView / SurfaceView). */
    fun attach(surface: Surface)

    /**
     * Open and (optionally) start a stream. Safe to call again to switch channels.
     * [headers] are sent with every request for this stream (M3U `#EXTVLCOPT`).
     */
    fun open(url: String, play: Boolean = true, headers: Map<String, String> = emptyMap())

    /**
     * Open an ARCHIVE window. [url] plays the archive from [startMs]; [startMs]/[endMs] are the
     * absolute UTC bounds used for position reporting and seek clamping. [reopen] is called when
     * a seek lands outside the buffered range on a non-seekable stream: it returns a fresh URL
     * whose archive start is the seek target (see [PlaybackEngine.seekTo]).
     */
    fun openArchive(
        url: String,
        startMs: Long,
        endMs: Long,
        play: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        reopen: (targetStartMs: Long) -> String?,
        /** The provider's alternate archive URL form for a start, tried once when [url] 404s. */
        fallback: (targetStartMs: Long) -> String? = { null },
    )

    fun play()
    fun pause()
    fun togglePlayPause()

    /** Seek to an absolute UTC position within the archive window. No-op when not seekable. */
    fun seekTo(positionMs: Long)

    /** Seek by a relative delta (negative rewinds). No-op when not seekable. */
    fun seekBy(deltaMs: Long)

    /** Select the audio track with this [MediaInfo] id. No-op if the id is unknown. */
    fun selectAudioTrack(id: String)

    /** Turn subtitles on to the [MediaInfo] text-track id, or OFF when [id] is null. */
    fun selectTextTrack(id: String?)

    /** Re-open the current media — the primary resilience path for a dropped live stream. */
    fun retry()

    /** Release all resources. The engine is unusable afterward. */
    fun release()
}
