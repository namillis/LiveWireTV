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

    // Archive (catch-up / time shift) state. Null seek controller ⇒ LIVE mode.
    private var seekController: ArchiveSeekController? = null
    private var reopenProvider: ((Long) -> String?)? = null
    private var fallbackProvider: ((Long) -> String?)? = null

    /** True once an archive 404 switched this archive to the fallback URL form; reopens follow. */
    private var onFallback = false

    private fun activeReopen(): ((Long) -> String?)? = if (onFallback) fallbackProvider else reopenProvider
    private var lastSeekAtMs: Long = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSeekRunnable: Runnable? = null

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _mediaInfo = MutableStateFlow(MediaInfo())
    override val mediaInfo: StateFlow<MediaInfo> = _mediaInfo.asStateFlow()

    private val _position = MutableStateFlow(C.TIME_UNSET)
    override val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(C.TIME_UNSET)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    override val canSeek: Boolean get() = seekController != null

    /** Lightweight position poll (~500 ms) so the archive scrub bar advances without per-frame churn. */
    private val positionPoll = object : Runnable {
        override fun run() {
            val ctrl = seekController
            if (ctrl != null) {
                _position.value = ctrl.absolutePosition(player.currentPosition.coerceAtLeast(0))
            }
            mainHandler.postDelayed(this, POSITION_POLL_MS)
        }
    }

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
            // An archive URL that 404s may be served by the provider's other archive path form
            // (Xtream `timeshift.php`): switch to it once, at the same start.
            val ctrl = seekController
            if (ctrl != null && !onFallback && responseCode(error) == 404) {
                val alt = fallbackProvider?.invoke(ctrl.openStartMs)
                if (alt != null) {
                    onFallback = true
                    openInternal(alt, true, lastHeaders, _status.value.mode)
                    return
                }
            }
            // Single-connection providers refuse a new stream for a moment after the old one
            // closes (a channel switch or a reopen-to-seek), answering 401/403/429, and a login
            // at its connection limit answers 456 (RefusedCodes). Retry quietly a couple of
            // times before showing an error.
            if (isConnectionRefusal(error) && connectionRetries < MAX_CONNECTION_RETRIES) {
                connectionRetries++
                val r = Runnable { if (seekController == null) retryLive(refused = true) else retry() }
                pendingRetry = r
                mainHandler.postDelayed(r, CONNECTION_RETRY_DELAY_MS * connectionRetries)
                return
            }
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
        if (state == PlaybackState.PLAYING) connectionRetries = 0
        watchForStall(state)
        val archive = seekController != null
        // Live streams report an unset/dynamic duration; treat unknown as live — but an
        // ARCHIVE stream is never "live" regardless of what ExoPlayer reports for its duration.
        val live = !archive &&
            (player.duration == C.TIME_UNSET || player.isCurrentMediaItemDynamic)
        _status.value = _status.value.copy(state = state, isLive = live, errorMessage = null)
    }

    override fun attach(surface: Surface) {
        player.setVideoSurface(surface)
    }

    override fun open(
        url: String,
        play: Boolean,
        headers: Map<String, String>,
        retryUrl: ((attempt: Int, refused: Boolean) -> String)?,
        onStop: (() -> Unit)?,
    ) {
        // Leaving archive mode: drop the seek controller so status reverts to LIVE.
        clearArchive()
        connectionRetries = 0
        endStream(onStop)
        liveRetryUrl = retryUrl
        liveAttempt = 0
        livePlayed = false
        stallPolicy.reset()
        openInternal(url, play, headers, PlaybackMode.Live)
    }

    override fun openArchive(
        url: String,
        startMs: Long,
        endMs: Long,
        play: Boolean,
        headers: Map<String, String>,
        reopen: (targetStartMs: Long) -> String?,
        fallback: (targetStartMs: Long) -> String?,
        onStop: (() -> Unit)?,
    ) {
        endStream(onStop)
        liveRetryUrl = null
        livePlayed = false
        mainHandler.removeCallbacks(stallCheck)
        fallbackProvider = fallback
        onFallback = false
        seekController = ArchiveSeekController(
            windowStartMs = startMs,
            windowEndMs = endMs,
            openStartMs = startMs,
        )
        reopenProvider = reopen
        _duration.value = (endMs - startMs).coerceAtLeast(0)
        _position.value = startMs
        connectionRetries = 0
        openInternal(url, play, headers, PlaybackMode.Archive(startMs, endMs))
        mainHandler.removeCallbacks(positionPoll)
        mainHandler.postDelayed(positionPoll, POSITION_POLL_MS)
    }

    private fun clearArchive() {
        seekController = null
        reopenProvider = null
        fallbackProvider = null
        onFallback = false
        pendingSeekRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingSeekRunnable = null
        mainHandler.removeCallbacks(positionPoll)
        _position.value = C.TIME_UNSET
        _duration.value = C.TIME_UNSET
    }

    private var connectionRetries = 0
    private var pendingRetry: Runnable? = null

    /** Builds the live URL for retry N, when the provider wants retries reported. */
    private var liveRetryUrl: ((attempt: Int, refused: Boolean) -> String)? = null
    private var liveAttempt = 0

    /** The current stream's stop hook, run once when it is replaced, stopped or released. */
    private var onStopCurrent: (() -> Unit)? = null

    private fun endStream(next: (() -> Unit)? = null) {
        val previous = onStopCurrent
        onStopCurrent = next
        previous?.let { runCatching { it() } }
    }

    /**
     * Re-open the live stream. [refused] marks the automatic retry after a refusal
     * ([RefusedCodes]), which keeps counting toward [MAX_CONNECTION_RETRIES]; a user retry
     * starts that count again.
     */
    private fun retryLive(refused: Boolean) {
        val url = lastUrl ?: return
        if (!refused) connectionRetries = 0
        val next = liveRetryUrl?.let { build ->
            liveAttempt++
            build(liveAttempt, refused)
        } ?: url
        openInternal(next, play = true, headers = lastHeaders, mode = PlaybackMode.Live)
    }

    /** Whether the current live stream has played at all, so a later freeze counts as a stall. */
    private var livePlayed = false
    private val stallPolicy = StallPolicy()
    private val stallCheck = Runnable { onStall() }

    /**
     * A live stream that played and then sits buffering for [STALL_MS] has frozen. When the
     * provider takes reported retries (the IPTV provider pool), the next attempt gets a different
     * source, so switch on our own; elsewhere a reopen would land on the same frozen feed.
     */
    private fun watchForStall(state: PlaybackState) {
        mainHandler.removeCallbacks(stallCheck)
        if (seekController != null) return
        if (state == PlaybackState.PLAYING) livePlayed = true
        if (state == PlaybackState.BUFFERING && livePlayed && liveRetryUrl != null) {
            mainHandler.postDelayed(stallCheck, STALL_MS)
        }
    }

    private fun onStall() {
        if (seekController != null || liveRetryUrl == null) return
        if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return
        if (stallPolicy.allowSwitch(android.os.SystemClock.elapsedRealtime())) {
            retryLive(refused = false)
        } else {
            player.stop()
            _status.value = _status.value.copy(
                state = PlaybackState.ERROR,
                errorMessage = "This channel keeps freezing. Try again or pick another channel.",
            )
        }
    }

    private fun responseCode(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    private fun isConnectionRefusal(error: PlaybackException): Boolean =
        RefusedCodes.isRefusal(responseCode(error))

    private fun openInternal(url: String, play: Boolean, headers: Map<String, String>, mode: PlaybackMode) {
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
        lastUrl = url
        lastHeaders = headers
        videoUnsupported = false
        _mediaInfo.value = MediaInfo()
        _status.value = PlaybackStatus(state = PlaybackState.BUFFERING, isLive = mode is PlaybackMode.Live, mode = mode)
        // Stop first so a channel switch releases the old decoder AND the old provider
        // connection before the new one opens — many providers allow only one connection
        // (same rule as PR #30). Reopen-to-seek relies on this too.
        player.stop()
        player.setMediaSource(mediaSourceFor(url, headers))
        player.playWhenReady = play
        player.prepare()
    }

    override fun seekTo(positionMs: Long) {
        val ctrl = seekController ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val actNow = ctrl.requestSeek(positionMs, now)
        if (actNow != null) {
            performSeek(actNow)
        } else {
            // Debounced: schedule the pending (latest) target after the debounce window so a
            // held ◀◀ collapses to a single reopen.
            pendingSeekRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                ctrl.takePending()?.let { performSeek(it) }
            }
            pendingSeekRunnable = r
            mainHandler.postDelayed(r, SEEK_DEBOUNCE_MS)
        }
    }

    override fun seekBy(deltaMs: Long) {
        val ctrl = seekController ?: return
        val current = ctrl.absolutePosition(player.currentPosition.coerceAtLeast(0))
        seekTo(current + deltaMs)
    }

    /** Apply a resolved seek: native when buffered+seekable, else reopen the stream at the target. */
    private fun performSeek(absoluteTargetMs: Long) {
        val ctrl = seekController ?: return
        val current = ctrl.absolutePosition(player.currentPosition.coerceAtLeast(0))
        val bufferedAhead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
        val decision = ctrl.resolveSeek(
            targetMs = absoluteTargetMs,
            currentAbsoluteMs = current,
            seekable = player.isCurrentMediaItemSeekable,
            bufferedAheadMs = bufferedAhead,
            bufferedBehindMs = player.currentPosition.coerceAtLeast(0),
        )
        when (decision) {
            is ArchiveSeekController.SeekDecision.Native -> {
                player.seekTo(decision.relativePositionMs.coerceAtLeast(0))
                _position.value = decision.absoluteTargetMs
            }
            is ArchiveSeekController.SeekDecision.Reopen -> {
                val url = activeReopen()?.invoke(decision.absoluteTargetMs) ?: return
                ctrl.onReopenedAt(decision.absoluteTargetMs)
                _position.value = decision.absoluteTargetMs
                // One connection: openInternal stops the current stream before opening the new one.
                openInternal(url, play = true, headers = lastHeaders, mode = PlaybackMode.Archive(ctrl.windowStartMs, ctrl.windowEndMs))
            }
        }
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
        val ctrl = seekController
        if (ctrl != null) {
            // Re-open the archive at the current position, preserving mode + window. lastUrl
            // starts at the stream's open point, so reopening it would jump back after a
            // native seek; ask for a URL starting where playback actually is.
            val here = ctrl.absolutePosition(player.currentPosition.coerceAtLeast(0))
            val resumeUrl = activeReopen()?.invoke(here)
            if (resumeUrl != null) ctrl.onReopenedAt(here)
            openInternal(resumeUrl ?: url, play = true, headers = lastHeaders, mode = PlaybackMode.Archive(ctrl.windowStartMs, ctrl.windowEndMs))
        } else {
            stallPolicy.reset()
            retryLive(refused = false)
        }
    }

    override fun stop() {
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
        mainHandler.removeCallbacks(stallCheck)
        player.stop()
        player.clearMediaItems()
        endStream()
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
        endStream()
        mainHandler.removeCallbacks(stallCheck)
        mainHandler.removeCallbacks(positionPoll)
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingSeekRunnable?.let { mainHandler.removeCallbacks(it) }
        player.removeListener(listener)
        player.release()
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
        const val SEEK_DEBOUNCE_MS = 600L
        /** A refused connection is retried this many times, 1.5 s then 3 s apart. */
        const val MAX_CONNECTION_RETRIES = 2
        /** A live stream that played, then buffers this long, has frozen. */
        const val STALL_MS = 10_000L
        const val CONNECTION_RETRY_DELAY_MS = 1_500L
    }
}
