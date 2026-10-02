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
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.core.player.PlayerFormats
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireTheme

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
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { ExoPlayerEngine(context) }
    val status by engine.status.collectAsStateWithLifecycle()
    val mediaInfo by engine.mediaInfo.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val optionsState = rememberOptionsPanelState()

    var panel by remember { mutableStateOf(PlayerPanel.NONE) }
    var pictureMode by remember { mutableStateOf(PictureMode.FIT) }
    var controlsVisible by remember { mutableStateOf(true) }
    var revealTick by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun reveal() {
        controlsVisible = true
        revealTick++
    }

    LaunchedEffect(target) { viewModel.resolve(target, title, fromFavorites) }
    // Re-open the engine whenever the resolved source changes (initial load AND channel switch).
    LaunchedEffect(source.source) { source.source?.let { engine.open(it.url, headers = it.headers) } }
    DisposableEffect(Unit) { onDispose { engine.release() } }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // A ticking clock so the wall clock and progress bars advance while the overlay is up.
    LaunchedEffect(controlsVisible, panel) {
        while (controlsVisible || panel != PlayerPanel.NONE) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    // Auto-hide the resting overlay after 5s; a panel or PAUSED keeps it up.
    LaunchedEffect(revealTick, status.state, panel) {
        if (panel != PlayerPanel.NONE) return@LaunchedEffect
        if (status.state == PlaybackState.PAUSED) return@LaunchedEffect
        kotlinx.coroutines.delay(5_000)
        controlsVisible = false
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
        if (panel != PlayerPanel.NONE) backInPanel() else onExit()
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
                    // Back flows through the BackHandler above, not here.
                    if (playerKey == PlayerKey.BACK) return@onKeyEvent false
                    reveal()
                    when (val action = PlayerInput.onKey(panel, playerKey)) {
                        is PlayerAction.OpenPanel -> { panel = action.panel; if (action.panel == PlayerPanel.OPTIONS) optionsState.start(); true }
                        PlayerAction.TogglePlayPause -> {
                            // The Retry button can't take focus while the root owns the D-pad, so OK retries.
                            when {
                                source.error != null -> viewModel.resolve(target, title, fromFavorites)
                                status.state == PlaybackState.ERROR -> engine.retry()
                                else -> engine.togglePlayPause()
                            }
                            true
                        }
                        PlayerAction.ChannelUp -> { viewModel.previousChannel(); true }
                        PlayerAction.ChannelDown -> { viewModel.nextChannel(); true }
                        PlayerAction.ClosePanel -> { panel = PlayerPanel.NONE; focusRequester.requestFocus(); true }
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

            // Resting overlay: only when no panel is open.
            AnimatedVisibility(
                visible = controlsVisible && panel == PlayerPanel.NONE,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                val channel = viewModel.currentChannel()
                RestingOverlay(
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
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> PlayerKey.CENTER
    KeyEvent.KEYCODE_BACK -> PlayerKey.BACK
    else -> null
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PictureMode.toResizeMode(): Int = when (this) {
    PictureMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    PictureMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    PictureMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

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
