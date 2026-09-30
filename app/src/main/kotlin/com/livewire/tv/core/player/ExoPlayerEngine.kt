package com.livewire.tv.core.player

import android.content.Context
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
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

    private val _mediaInfo = MutableStateFlow(MediaInfo())
    override val mediaInfo: StateFlow<MediaInfo> = _mediaInfo.asStateFlow()

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
            _mediaInfo.value = readMediaInfo(tracks)
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
        _mediaInfo.value = MediaInfo()
        _status.value = PlaybackStatus(state = PlaybackState.BUFFERING)
        // Stop first so a channel switch releases the old decoder and stream connection. A
        // decoder reused across a resolution change (1080p -> 720p) drew the new video into
        // part of the TextureView beside a stale frame.
        player.stop()
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
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http), extractorsFactory)
            .createMediaSource(MediaItem.fromUri(url))
    }

    /**
     * Extractors factory that DECLARES an in-band CEA-608 caption track for MPEG-TS streams.
     *
     * IPTV live channels are usually MPEG-TS, and US broadcasters carry closed captions as
     * CEA-608/708 in the H.264 user-data — not as a separate PID. Media3's TS extractor
     * ignores those caption descriptors unless the expected subtitle formats are declared up
     * front (see DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS), so with the
     * default factory such a stream reports NO text tracks even when captions are present.
     * Declaring a CEA-608 format here makes the captions selectable as an "English CC" text
     * track; on a stream that carries none, this adds an empty track that never produces
     * cues, which the UI simply never shows selected — safe either way.
     */
    private val extractorsFactory by lazy {
        androidx.media3.extractor.DefaultExtractorsFactory().setTsSubtitleFormats(
            listOf(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                    .setLanguage("en")
                    .setAccessibilityChannel(1)
                    .build(),
            ),
        )
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

    override fun selectAudioTrack(id: String) = selectTrack(C.TRACK_TYPE_AUDIO, id)

    override fun selectTextTrack(id: String?) {
        if (id == null) {
            // Turn subtitles off: disable the text renderer via TrackSelectionParameters, the
            // supported public path (the UI must not reach into ExoPlayer itself).
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            _mediaInfo.value = _mediaInfo.value.copy(selectedTextId = null)
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            selectTrack(C.TRACK_TYPE_TEXT, id)
        }
    }

    /**
     * Apply a [TrackSelectionOverride] for the group+track encoded in [id]. Ids come from
     * [readMediaInfo], so the group index and track index are recovered by walking the
     * current [Tracks] in the same order — the UI never holds an ExoPlayer object.
     */
    private fun selectTrack(trackType: Int, id: String) {
        val groups = player.currentTracks.groups.filter { it.type == trackType }
        val (groupIndex, trackIndex) = TrackIds.parse(id) ?: return
        val group = groups.getOrNull(groupIndex) ?: return
        if (trackIndex !in 0 until group.length) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        _mediaInfo.value = readMediaInfo(player.currentTracks)
    }

    /** Translate ExoPlayer's [Tracks] into the UI-facing [MediaInfo]. */
    private fun readMediaInfo(tracks: Tracks): MediaInfo {
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        var selectedAudioId: String? = null
        val audio = buildList {
            audioGroups.forEachIndexed { gi, g ->
                for (ti in 0 until g.length) {
                    if (!g.isTrackSupported(ti)) continue
                    val fmt = g.getTrackFormat(ti)
                    val id = TrackIds.of(gi, ti)
                    add(
                        AudioTrack(
                            id = id,
                            label = languageLabel(fmt, fallback = "Audio ${size + 1}"),
                            language = fmt.language,
                            channelCount = fmt.channelCount.coerceAtLeast(0),
                            codec = codecLabel(fmt),
                        ),
                    )
                    if (g.isTrackSelected(ti)) selectedAudioId = id
                }
            }
        }

        var selectedTextId: String? = null
        val text = buildList {
            textGroups.forEachIndexed { gi, g ->
                for (ti in 0 until g.length) {
                    if (!g.isTrackSupported(ti)) continue
                    val fmt = g.getTrackFormat(ti)
                    // Forced/auto caption tracks with no language read as "Subtitle N".
                    if (fmt.selectionFlags and C.SELECTION_FLAG_FORCED != 0 && fmt.language == null) continue
                    val id = TrackIds.of(gi, ti)
                    add(TextTrack(id = id, label = languageLabel(fmt, fallback = "Subtitle ${size + 1}"), language = fmt.language))
                    if (g.isTrackSelected(ti)) selectedTextId = id
                }
            }
        }

        val videoFmt = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
        val selectedAudioFmt = audioGroups.firstOrNull { it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }

        return MediaInfo(
            audioTracks = audio,
            textTracks = text,
            selectedAudioId = selectedAudioId,
            selectedTextId = selectedTextId,
            video = videoFmt?.let { v ->
                VideoFormat(
                    width = v.width.coerceAtLeast(0),
                    height = v.height.coerceAtLeast(0),
                    frameRate = v.frameRate.takeIf { it != Format.NO_VALUE.toFloat() && it > 0 } ?: 0f,
                    videoCodec = codecLabel(v),
                    audioCodec = selectedAudioFmt?.let(::codecLabel),
                    audioChannelCount = selectedAudioFmt?.channelCount?.coerceAtLeast(0) ?: 0,
                    bitrateBps = v.bitrate.takeIf { it != Format.NO_VALUE } ?: v.peakBitrate.takeIf { it != Format.NO_VALUE } ?: 0,
                )
            },
        )
    }

    /** A track's language display name, or [fallback] when the format carries no language. */
    private fun languageLabel(fmt: Format, fallback: String): String {
        val lang = fmt.language ?: return fallback
        return runCatching {
            java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.getDefault())
        }.getOrNull()?.takeIf { it.isNotBlank() && it != lang } ?: lang.uppercase(java.util.Locale.ROOT)
    }

    /** "H.264", "HEVC", "AAC", "EAC3" from a MIME type, or null when unknown. */
    private fun codecLabel(fmt: Format): String? {
        val mime = fmt.sampleMimeType ?: fmt.codecs ?: return null
        return when {
            MimeTypes.VIDEO_H264 == mime || mime.contains("avc", ignoreCase = true) -> "H.264"
            MimeTypes.VIDEO_H265 == mime || mime.contains("hevc", ignoreCase = true) || mime.contains("hvc", ignoreCase = true) -> "HEVC"
            MimeTypes.VIDEO_MPEG2 == mime -> "MPEG-2"
            MimeTypes.VIDEO_VP9 == mime -> "VP9"
            MimeTypes.VIDEO_AV1 == mime -> "AV1"
            MimeTypes.AUDIO_AAC == mime -> "AAC"
            MimeTypes.AUDIO_E_AC3 == mime -> "E-AC3"
            MimeTypes.AUDIO_AC3 == mime -> "AC3"
            MimeTypes.AUDIO_MPEG == mime || MimeTypes.AUDIO_MPEG_L2 == mime -> "MP2/3"
            MimeTypes.AUDIO_OPUS == mime -> "Opus"
            else -> mime.substringAfter('/').uppercase(java.util.Locale.ROOT)
        }
    }

    override fun release() {
        player.removeListener(listener)
        player.release()
    }
}
