package com.livewire.tv.core.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Media3/ExoPlayer implementation of [PlaybackEngine]. ExoPlayer selects the device's
 * HARDWARE decoders by default (MediaCodec) — the leanness win from ADR-0001. HLS and
 * progressive/TS are handled via the bundled default + HLS source factories.
 *
 * Not injected as a singleton: one engine instance per player screen, released on exit.
 */
class ExoPlayerEngine(context: Context) : PlaybackEngine {

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

        override fun onPlayerError(error: PlaybackException) {
            _status.value = _status.value.copy(
                state = PlaybackState.ERROR,
                errorMessage = error.errorCodeName + (error.message?.let { ": $it" } ?: ""),
            )
        }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(listener)
        playWhenReady = true
    }

    /** Expose the player so a Compose PlayerView can bind to it. */
    val exoPlayer: ExoPlayer get() = player

    private fun update(state: PlaybackState) {
        // Live streams report an unset/dynamic duration; treat unknown as live.
        val live = player.duration == androidx.media3.common.C.TIME_UNSET || player.isCurrentMediaItemDynamic
        _status.value = _status.value.copy(state = state, isLive = live, errorMessage = null)
    }

    override fun attach(surface: Surface) {
        player.setVideoSurface(surface)
    }

    override fun open(url: String, play: Boolean) {
        _status.value = PlaybackStatus(state = PlaybackState.BUFFERING)
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = play
        player.prepare()
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }

    override fun togglePlayPause() {
        if (_status.value.state == PlaybackState.ERROR) return
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun retry() {
        val current = player.currentMediaItem ?: return
        _status.value = _status.value.copy(state = PlaybackState.BUFFERING, errorMessage = null)
        player.setMediaItem(current)
        player.prepare()
        player.play()
    }

    override fun release() {
        player.removeListener(listener)
        player.release()
    }
}
