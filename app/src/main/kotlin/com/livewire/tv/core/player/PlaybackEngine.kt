package com.livewire.tv.core.player

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/** Coarse playback state for the UI to render buffering/error overlays. */
enum class PlaybackState { IDLE, BUFFERING, PLAYING, PAUSED, ERROR, ENDED }

/** Snapshot the UI observes. */
data class PlaybackStatus(
    val state: PlaybackState = PlaybackState.IDLE,
    val errorMessage: String? = null,
    val isLive: Boolean = true,
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

    /** Attach the render surface (from the Compose PlayerView / SurfaceView). */
    fun attach(surface: Surface)

    /**
     * Open and (optionally) start a stream. Safe to call again to switch channels.
     * [headers] are sent with every request for this stream (M3U `#EXTVLCOPT`).
     */
    fun open(url: String, play: Boolean = true, headers: Map<String, String> = emptyMap())

    fun play()
    fun pause()
    fun togglePlayPause()

    /** Select the audio track with this [MediaInfo] id. No-op if the id is unknown. */
    fun selectAudioTrack(id: String)

    /** Turn subtitles on to the [MediaInfo] text-track id, or OFF when [id] is null. */
    fun selectTextTrack(id: String?)

    /** Re-open the current media — the primary resilience path for a dropped live stream. */
    fun retry()

    /** Release all resources. The engine is unusable afterward. */
    fun release()
}
