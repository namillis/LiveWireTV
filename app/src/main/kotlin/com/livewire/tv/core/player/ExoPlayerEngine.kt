package com.livewire.tv.core.player

import android.content.Context
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Media3/ExoPlayer implementation of [PlaybackEngine]. ExoPlayer selects the device's
 * HARDWARE decoders by default (MediaCodec) — the leanness win from ADR-0001. HLS and
 * progressive/TS are handled via the bundled default + HLS source factories.
 *
 * Not injected as a singleton: one engine instance per player screen, released on exit.
 *
 * Opts in to Media3's `@UnstableApi` media-source classes: they are the only way to
 * attach per-stream HTTP headers (M3U `#EXTVLCOPT`). Re-check on Media3 upgrades.
 */
@OptIn(UnstableApi::class)
class ExoPlayerEngine(context: Context) : PlaybackEngine {

    private val appContext: Context = context.applicationContext
    private var lastUrl: String? = null
    private var lastHeaders: Map<String, String> = emptyMap()
    private var videoUnsupported = false

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> update(PlaybackState.BUFFERING)
                Player.STATE_READY -> update(if (player.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED)
                Player.STATE_ENDED -> update(PlaybackState.ENDED)
                Player.STATE_IDLE -> { /* transient; leave as-is unless an error set it */ }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_status.value.state == PlaybackState.ERROR) return
            if (isPlaying) update(PlaybackState.PLAYING)
            else if (player.playbackState == Player.STATE_READY) update(PlaybackState.PAUSED)
        }

        override fun onTracksChanged(tracks: Tracks) {
            // ExoPlayer silently drops a video track the device cannot decode and keeps
            // playing the audio, which looks like a blank screen with sound. Surface it.
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (video.isNotEmpty() && video.none { it.isSupported }) {
                videoUnsupported = true
                player.pause()
                _status.value = _status.value.copy(
                    state = PlaybackState.ERROR,
                    errorMessage = "This channel's video format isn't supported on this device.",
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _status.value = _status.value.copy(
                state = PlaybackState.ERROR,
                errorMessage = "Playback failed. Check the stream format and try again.",
            )
        }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        // If the preferred hardware decoder fails to initialise, try the next one
        // instead of dropping video.
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    ).build().apply {
        addListener(listener)
        playWhenReady = true
    }

    /** Expose the player so a Compose PlayerView can bind to it. */
    val exoPlayer: ExoPlayer get() = player

    private fun update(state: PlaybackState) {
        if (videoUnsupported) return
        // Live streams report an unset/dynamic duration; treat unknown as live.
        val live = player.duration == androidx.media3.common.C.TIME_UNSET || player.isCurrentMediaItemDynamic
        _status.value = _status.value.copy(state = state, isLive = live, errorMessage = null)
    }

    override fun attach(surface: Surface) {
        player.setVideoSurface(surface)
    }

    override fun open(url: String, play: Boolean, headers: Map<String, String>) {
        lastUrl = url
        lastHeaders = headers
        videoUnsupported = false
        _status.value = PlaybackStatus(state = PlaybackState.BUFFERING)
        player.setMediaSource(mediaSourceFor(url, headers))
        player.playWhenReady = play
        player.prepare()
    }

    /** Builds a source whose every request (manifest, segments) carries [headers]. */
    private fun mediaSourceFor(url: String, headers: Map<String, String>): MediaSource {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.let { http.setUserAgent(it.value) }
        val others = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        if (others.isNotEmpty()) http.setDefaultRequestProperties(others)
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http))
            .createMediaSource(MediaItem.fromUri(url))
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }

    override fun togglePlayPause() {
        if (_status.value.state == PlaybackState.ERROR) return
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun retry() {
        val url = lastUrl ?: return
        open(url, play = true, headers = lastHeaders)
    }

    override fun release() {
        player.removeListener(listener)
        player.release()
    }
}
