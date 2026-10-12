package com.livewire.tv.feature.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.livewire.tv.core.player.ExoPlayerEngine
import kotlinx.coroutines.launch
import com.livewire.tv.core.player.PlaybackMode
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.core.player.PlayerFormats
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireTheme

/**
 * A catch-up launch from the Guide: open the player straight into archive mode for the window
 * [startMs, endMs] (UTC), labelled with the past [programme] title. Null for a normal live open.
 */
data class ArchiveLaunch(val startMs: Long, val endMs: Long, val programme: String)

/**
 * Full-screen player with the "Option 3" overlay (design system §9.6). The nav layer passes
 * only [PlaybackTarget]; the credential-bearing stream URL is resolved in [PlayerViewModel]
 * and never placed in route state.
 *
 * D-pad routing (requirement 4) is decided by the pure [PlayerInput] reducer, so the key
 * handler here only maps Android key codes to [PlayerKey] and performs the resulting
 * [PlayerAction]. Overlay auto-hides after ~5s; any key reveals it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    target: PlaybackTarget,
    title: String,
    onExit: () -> Unit,
    fromFavorites: Boolean = false,
    archiveLaunch: ArchiveLaunch? = null,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { ExoPlayerEngine(context) }
    val status by engine.status.collectAsStateWithLifecycle()
    val mediaInfo by engine.mediaInfo.collectAsStateWithLifecycle()
    val position by engine.position.collectAsStateWithLifecycle()
    val duration by engine.duration.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val archive by viewModel.archive.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val optionsState = rememberOptionsPanelState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var panel by remember { mutableStateOf(PlayerPanel.NONE) }
    var pictureMode by remember { mutableStateOf(PictureMode.FIT) }
    var controlsVisible by remember { mutableStateOf(true) }
    var revealTick by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // --- Catch-up / time-shift UI state --------------------------------------------------
    // The scrub bar is "focused" (▼ grabbed it); Left/Right then seek instead of opening panels.
    var barFocused by remember { mutableStateOf(false) }
    // The one-time "can't rewind" card, shown when the user tries to time-shift a no-archive channel.
    var noRewind by remember { mutableStateOf(false) }
    var noRewindSeen by remember { mutableStateOf(false) }
    // Time-shift: when the user pauses a live archive channel we record the live-edge instant so
    // resume reopens the archive at the pause point. 0 = not time-shifting.
    var pausedLiveAtMs by remember { mutableLongStateOf(0L) }
    // The archive request token last handed to the engine, so we open each new one exactly once.
    var openedArchiveToken by remember { mutableIntStateOf(0) }

    val mode: PlayerMode = when {
        pausedLiveAtMs > 0L -> PlayerMode.PAUSED_LIVE
        status.mode is PlaybackMode.Archive -> PlayerMode.ARCHIVE
        else -> PlayerMode.LIVE
    }

    fun reveal() {
        controlsVisible = true
        revealTick++
    }

    LaunchedEffect(target) { viewModel.resolve(target, title, fromFavorites) }
    // A Guide catch-up launch: ask the view model to resolve the archive window once.
    LaunchedEffect(archiveLaunch) {
        archiveLaunch?.let { al ->
            viewModel.resolveAndPlayArchive(target, al.startMs, al.endMs, al.programme)
        }
    }
    // Open the LIVE source whenever it changes — but not while an archive request is in flight
    // (archiveLaunch), where the archive open below owns the engine.
    LaunchedEffect(source.source) {
        if (archiveLaunch == null && pausedLiveAtMs == 0L && archive.source == null) {
            source.source?.let { engine.open(it.url, headers = it.headers, retryUrl = it.retryUrl, onStop = it.onStop) }
        }
    }
    // Open an ARCHIVE source (catch-up launch, Start over, or resume from time-shift) exactly
    // once per request token, wiring the engine's reopen-to-seek to the view model's builder.
    LaunchedEffect(archive.token, archive.source) {
        val src = archive.source
        if (src != null && archive.token != openedArchiveToken) {
            openedArchiveToken = archive.token
            val reopen = archive.reopen
            val fallback = archive.fallback
            engine.openArchive(
                url = src.url,
                startMs = archive.startMs,
                endMs = archive.endMs,
                headers = src.headers,
                reopen = { reopen(it) },
                fallback = { fallback(it) },
                onStop = src.onStop,
            )
            barFocused = false
            reveal()
        }
    }
    DisposableEffect(Unit) { onDispose { engine.release() } }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // A ticking clock so the wall clock and progress bars advance while the overlay is up.
    LaunchedEffect(controlsVisible, panel) {
        while (controlsVisible || panel != PlayerPanel.NONE) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    // Auto-hide the resting overlay after 5s; a panel, PAUSED, a focused scrub bar, archive /
    // time-shift mode, or the no-rewind card all keep it up.
    LaunchedEffect(revealTick, status.state, panel, barFocused, mode, noRewind) {
        if (panel != PlayerPanel.NONE) return@LaunchedEffect
        if (status.state == PlaybackState.PAUSED) return@LaunchedEffect
        if (barFocused || noRewind) return@LaunchedEffect
        if (mode != PlayerMode.LIVE) return@LaunchedEffect
        kotlinx.coroutines.delay(5_000)
        controlsVisible = false
    }

    // Go live: leave archive / time-shift and reopen the live stream.
    fun goLive() {
        pausedLiveAtMs = 0L
        barFocused = false
        scope.launch {
            val live = viewModel.liveSource(source.currentStreamId ?: target.streamId)
            if (live != null) engine.open(live.url, headers = live.headers, retryUrl = live.retryUrl, onStop = live.onStop)
        }
        reveal()
    }

    // OK on a live channel: pause. On an archive channel this becomes a time-shift (resume will
    // reopen the archive at the pause point); on a no-archive channel it is a plain pause and we
    // show the one-time "can't rewind" card the first time.
    fun centerOnLive() {
        if (source.error != null) { viewModel.resolve(target, title); return }
        if (status.state == PlaybackState.ERROR) { engine.retry(); return }
        val streamId = source.currentStreamId
        if (status.state == PlaybackState.PAUSED) {
            // Resuming a plain live pause.
            engine.play()
            return
        }
        if (viewModel.channelHasArchive(streamId)) {
            // Time-shift: record the live edge and stop the live stream; resume reopens archive.
            pausedLiveAtMs = System.currentTimeMillis()
            engine.pause()
        } else {
            engine.pause()
            if (!noRewindSeen) { noRewind = true; noRewindSeen = true }
        }
        reveal()
    }

    // Resume from a time-shift pause: reopen the archive at the pause point.
    fun resumeTimeShift() {
        val pausedAt = pausedLiveAtMs
        val streamId = source.currentStreamId
        val channel = viewModel.currentChannel()
        if (pausedAt > 0L && channel != null) {
            // Archive window: from the pause point to now, so the user can also seek forward to
            // live. The provider serves only what it has recorded at request time, so the stream
            // ENDS when it catches up; the ENDED effect below reopens it to keep the delay.
            viewModel.playArchive(
                channel,
                pausedAt,
                System.currentTimeMillis(),
                timeShift = true,
                programmeTitle = viewModel.programmeAt(channel, pausedAt)?.title,
                pausedAtMs = pausedAt,
            )
            pausedLiveAtMs = 0L
        } else if (streamId != null) {
            engine.play()
            pausedLiveAtMs = 0L
        }
        reveal()
    }

    // A time-shifted stream only holds what the provider had recorded when it was requested, and
    // the provider records ~15s behind real time, so near the end of the window the stream runs
    // dry and the engine reports ERROR (or ENDED). Keep the delay by reopening from the current
    // position up to the new "now" shortly BEFORE that point, and again if it fails anyway.
    // Within TIME_SHIFT_LIVE_SNAP_MS of live there is too little to reopen, so go live instead.
    fun continueTimeShift(allowLive: Boolean) {
        val channel = viewModel.currentChannel()
        if (channel == null) {
            goLive()
            return
        }
        val wallNow = System.currentTimeMillis()
        val pos = position.takeIf { it > 0 } ?: archive.endMs
        if (wallNow - pos <= TIME_SHIFT_LIVE_SNAP_MS) {
            if (allowLive) goLive()
            return
        }
        viewModel.playArchive(
            channel,
            pos,
            wallNow,
            timeShift = true,
            programmeTitle = viewModel.programmeAt(channel, pos)?.title ?: archive.programmeTitle,
            pausedAtMs = archive.pausedAtMs,
        )
    }
    val timeShifting = mode == PlayerMode.ARCHIVE && archive.timeShift
    val nearWindowEnd = timeShifting && status.state == PlaybackState.PLAYING &&
        position > 0 && position >= archive.endMs - TIME_SHIFT_REOPEN_LEAD_MS
    LaunchedEffect(nearWindowEnd, archive.token) {
        if (nearWindowEnd) continueTimeShift(allowLive = false)
    }
    LaunchedEffect(status.state, archive.token) {
        if (timeShifting && (status.state == PlaybackState.ENDED || status.state == PlaybackState.ERROR)) {
            continueTimeShift(allowLive = true)
        }
    }

    // Back inside an open panel: close a sub-list, else the panel, then hand focus back to
    // the player root so the next D-pad key is not lost.
    fun backInPanel() {
        if (panel == PlayerPanel.OPTIONS && optionsState.onBack()) {
            reveal()
            return
        }
        panel = PlayerPanel.NONE
        reveal()
        focusRequester.requestFocus()
    }

    // Back with no panel exits (keeps the key-up BackHandler fix). Back inside a panel is
    // taken by onPreviewKeyEvent below instead: Compose moves focus out of the panel's list
    // on Back before this handler runs, which cost the user a second press to close it.
    BackHandler {
        when {
            panel != PlayerPanel.NONE -> backInPanel()
            noRewind -> { noRewind = false; reveal() }
            mode != PlayerMode.LIVE && barFocused -> { barFocused = false; reveal() }
            else -> onExit()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // Only while a panel is open; with no panel Back reaches BackHandler.
                    if (panel == PlayerPanel.NONE || event.key.nativeKeyCode != KeyEvent.KEYCODE_BACK) {
                        return@onPreviewKeyEvent false
                    }
                    // Act on key-up (consuming key-down too) so the key-up never reaches
                    // whatever takes focus after the panel closes.
                    if (event.type == KeyEventType.KeyUp) backInPanel()
                    true
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val playerKey = event.toPlayerKey() ?: return@onKeyEvent false
                    // Back flows through the BackHandler above, not here — unless the scrub bar
                    // is focused, where Back releases the bar instead of exiting.
                    if (playerKey == PlayerKey.BACK && !(mode != PlayerMode.LIVE && barFocused)) {
                        return@onKeyEvent false
                    }
                    // The one-time no-rewind card: any key dismisses it and is consumed.
                    if (noRewind) { noRewind = false; reveal(); return@onKeyEvent true }
                    reveal()
                    when (val action = PlayerInput.onKey(panel, playerKey, mode, barFocused)) {
                        is PlayerAction.OpenPanel -> { panel = action.panel; if (action.panel == PlayerPanel.OPTIONS) optionsState.start(); true }
                        PlayerAction.TogglePlayPause -> {
                            when {
                                source.error != null -> viewModel.resolve(target, title, fromFavorites)
                                status.state == PlaybackState.ERROR -> engine.retry()
                                mode == PlayerMode.LIVE -> centerOnLive()
                                else -> engine.togglePlayPause()
                            }
                            true
                        }
                        PlayerAction.ChannelUp -> { viewModel.previousChannel(); true }
                        PlayerAction.ChannelDown -> { viewModel.nextChannel(); true }
                        PlayerAction.ClosePanel -> { panel = PlayerPanel.NONE; focusRequester.requestFocus(); true }
                        PlayerAction.GoLive -> { goLive(); true }
                        PlayerAction.FocusScrubBar -> { barFocused = true; true }
                        PlayerAction.ReleaseScrubBar -> { barFocused = false; focusRequester.requestFocus(); true }
                        PlayerAction.SeekBack -> { engine.seekBy(-10_000L); true }
                        PlayerAction.SeekForward -> { engine.seekBy(30_000L); true }
                        PlayerAction.Resume -> { resumeTimeShift(); true }
                        PlayerAction.Handled -> true
                        PlayerAction.Ignored -> false
                        PlayerAction.Exit -> { onExit(); true }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    (android.view.LayoutInflater.from(viewContext)
                        .inflate(com.livewire.tv.R.layout.livewire_player_view, android.widget.FrameLayout(viewContext), false) as PlayerView)
                        .apply {
                            keepScreenOn = true
                            player = engine.exoPlayer
                        }
                },
                update = { it.resizeMode = pictureMode.toResizeMode() },
                onRelease = { it.player = null },
            )

            when {
                source.loading -> CircularProgressIndicator()
                source.error != null -> SafeErrorOverlay(source.error!!, onRetry = { viewModel.resolve(target, title, fromFavorites) })
                status.state == PlaybackState.BUFFERING -> CircularProgressIndicator()
                status.state == PlaybackState.ERROR -> SafeErrorOverlay(
                    message = status.errorMessage ?: "Playback failed. Try again.",
                    onRetry = engine::retry,
                )
            }

            // Resting / archive / paused overlay: only when no panel is open.
            AnimatedVisibility(
                visible = controlsVisible && panel == PlayerPanel.NONE,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                val channel = viewModel.currentChannel()
                when (mode) {
                    PlayerMode.PAUSED_LIVE -> PausedLiveOverlay(
                        model = archiveOverlayModel(
                            channel = channel,
                            fallbackName = source.title.ifBlank { title },
                            number = channels.items.getOrNull(channels.playingIndex)?.number ?: "",
                            programmeTitle = null,
                            windowStartMs = pausedLiveAtMs,
                            positionMs = pausedLiveAtMs,
                            durationMs = 0,
                            mediaInfo = mediaInfo,
                        ),
                        behindMs = (now - pausedLiveAtMs).coerceAtLeast(0L),
                        now = now,
                    )
                    PlayerMode.ARCHIVE -> ArchivePlayerOverlay(
                        model = archiveOverlayModel(
                            channel = channel,
                            fallbackName = source.title.ifBlank { title },
                            number = channels.items.getOrNull(channels.playingIndex)?.number ?: "",
                            programmeTitle = archive.programmeTitle,
                            windowStartMs = archive.startMs,
                            positionMs = position.takeIf { it > 0 } ?: archive.startMs,
                            durationMs = (archive.endMs - archive.startMs).coerceAtLeast(0L),
                            mediaInfo = mediaInfo,
                        ),
                        state = status.state,
                        barFocused = barFocused,
                        now = now,
                        timeShift = archive.timeShift,
                        pausedAtMs = archive.pausedAtMs,
                    )
                    PlayerMode.LIVE -> RestingOverlay(
                        model = RestingOverlayModel(
                            channelName = channel?.name ?: source.title.ifBlank { title },
                            channelNumber = channels.items.getOrNull(channels.playingIndex)?.number ?: "",
                            nowPlaying = channel?.let(viewModel::nowPlaying),
                            upNext = channel?.let(viewModel::upNext),
                            video = mediaInfo.video,
                            audioChannelCount = mediaInfo.video?.audioChannelCount ?: 0,
                            hasSubtitles = mediaInfo.hasSubtitles,
                        ),
                        status = status,
                        now = now,
                    )
                }
            }

            // One-time "This channel can't rewind" card, over live video on a no-archive channel.
            AnimatedVisibility(
                visible = noRewind,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                NoRewindCard(channelName = viewModel.currentChannel()?.name ?: source.title.ifBlank { title })
            }

            // A scrim dims the video whenever a side panel is open (design system §9.5:
            // "content dimmed behind an open drawer or dialog"), so panel and preview text
            // stay readable over any broadcast. Mockups option3-options / option3-channels.
            AnimatedVisibility(
                visible = panel != PlayerPanel.NONE,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().background(LiveWireColors.Scrim))
            }

            // Options panel (right) + its bottom-left mini now-playing block (mockup).
            AnimatedVisibility(
                visible = panel == PlayerPanel.OPTIONS,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                val channel = viewModel.currentChannel()
                OptionsMiniInfo(
                    channelName = channel?.name ?: source.title.ifBlank { title },
                    nowPlaying = channel?.let(viewModel::nowPlaying),
                    now = now,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
            AnimatedVisibility(
                visible = panel == PlayerPanel.OPTIONS,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                OptionsPanel(
                    state = optionsState,
                    mediaInfo = mediaInfo,
                    pictureMode = pictureMode,
                    onSelectAudio = engine::selectAudioTrack,
                    onSelectText = engine::selectTextTrack,
                    onSelectPicture = { pictureMode = it },
                    onReload = engine::retry,
                    isFavorite = source.currentStreamId?.let { it in favoriteIds } ?: false,
                    onToggleFavorite = viewModel::toggleCurrentFavorite,
                )
            }

            // Channels panel (left).
            AnimatedVisibility(
                visible = panel == PlayerPanel.CHANNELS,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
                modifier = Modifier.fillMaxSize(),
            ) {
                ChannelListPanel(
                    state = channels,
                    now = now,
                    favoriteIds = favoriteIds,
                    onPlay = { item ->
                        viewModel.switchTo(item)
                        panel = PlayerPanel.NONE
                        reveal()
                        focusRequester.requestFocus()
                    },
                )
            }
        }
    }
}

/** Map an Android key event to the reducer's [PlayerKey], or null for keys we ignore. */
private fun androidx.compose.ui.input.key.KeyEvent.toPlayerKey(): PlayerKey? = when (key.nativeKeyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> PlayerKey.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> PlayerKey.DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> PlayerKey.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerKey.RIGHT
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_SPACE -> PlayerKey.CENTER
    KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerKey.MEDIA_REW
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerKey.MEDIA_FF
    KeyEvent.KEYCODE_BACK -> PlayerKey.BACK
    else -> null
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PictureMode.toResizeMode(): Int = when (this) {
    PictureMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    PictureMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    PictureMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

/** Build the archive/paused overlay model from the current channel + decoded media info. */
private fun archiveOverlayModel(
    channel: com.livewire.tv.feature.providers.domain.LiveChannel?,
    fallbackName: String,
    number: String,
    programmeTitle: String?,
    windowStartMs: Long,
    positionMs: Long,
    durationMs: Long,
    mediaInfo: com.livewire.tv.core.player.MediaInfo,
): ArchiveOverlayModel = ArchiveOverlayModel(
    channelName = channel?.name ?: fallbackName,
    channelNumber = number,
    programmeTitle = programmeTitle,
    programmeCategory = null,
    windowStartMs = windowStartMs,
    positionMs = positionMs,
    durationMs = durationMs,
    video = mediaInfo.video,
    audioChannelCount = mediaInfo.video?.audioChannelCount ?: 0,
    hasSubtitles = mediaInfo.hasSubtitles,
)

/**
 * The bottom-left mini now-playing block shown while the Options panel is open (mockup
 * option3-options.png): channel name, programme title, and a progress bar with "N min left".
 */
@Composable
private fun OptionsMiniInfo(
    channelName: String,
    nowPlaying: EpgProgramme?,
    now: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = 620.dp)
            .padding(horizontal = LiveWireDimens.SafeHorizontal, vertical = LiveWireDimens.SafeVertical),
    ) {
        Text(
            channelName,
            style = MaterialTheme.typography.titleMedium,
            color = LiveWireColors.OnSurfaceMuted,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            nowPlaying?.title ?: channelName,
            style = MaterialTheme.typography.headlineSmall,
            color = LiveWireColors.OnSurface,
            maxLines = 1,
        )
        if (nowPlaying != null) {
            Spacer(Modifier.height(LiveWireDimens.SpaceM))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveWireProgress(nowPlaying.progressAt(now), Modifier.width(360.dp), height = 4.dp)
                Spacer(Modifier.width(LiveWireDimens.SpaceM))
                Text(
                    PlayerFormats.minutesLeft(nowPlaying.stopMs, now),
                    style = MaterialTheme.typography.labelMedium,
                    color = LiveWireColors.OnSurfaceMuted,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SafeErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = Color(0xFFFF6E6E))
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Retry")
            }
        }
    }
}

/**
 * A time-shifted stream within this much of live goes live instead of reopening: the provider
 * records ~15s behind real time, so a tiny reopen would run dry again at once.
 */
private const val TIME_SHIFT_LIVE_SNAP_MS = 60_000L

/** Reopen a time-shifted stream this long before its window ends, ahead of the provider's lag. */
private const val TIME_SHIFT_REOPEN_LEAD_MS = 30_000L
