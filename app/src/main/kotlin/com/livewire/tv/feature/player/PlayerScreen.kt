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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.livewire.tv.core.player.ExoPlayerEngine
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.feature.providers.domain.PlaybackTarget

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
@Composable
fun PlayerScreen(
    target: PlaybackTarget,
    title: String,
    onExit: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { ExoPlayerEngine(context) }
    val status by engine.status.collectAsStateWithLifecycle()
    val mediaInfo by engine.mediaInfo.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
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

    LaunchedEffect(target) { viewModel.resolve(target, title) }
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

    // Back: close a sub-list, then the panel, then exit (keeps the key-up BackHandler fix).
    BackHandler {
        when {
            panel == PlayerPanel.OPTIONS && optionsState.onBack() -> reveal()
            panel != PlayerPanel.NONE -> { panel = PlayerPanel.NONE; reveal() }
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
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val playerKey = event.toPlayerKey() ?: return@onKeyEvent false
                    // Back flows through the BackHandler above, not here.
                    if (playerKey == PlayerKey.BACK) return@onKeyEvent false
                    reveal()
                    when (val action = PlayerInput.onKey(panel, playerKey)) {
                        is PlayerAction.OpenPanel -> { panel = action.panel; if (action.panel == PlayerPanel.OPTIONS) optionsState.reset(); true }
                        PlayerAction.TogglePlayPause -> { engine.togglePlayPause(); true }
                        PlayerAction.ChannelUp -> { viewModel.previousChannel(); true }
                        PlayerAction.ChannelDown -> { viewModel.nextChannel(); true }
                        PlayerAction.ClosePanel -> { panel = PlayerPanel.NONE; true }
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
                source.error != null -> SafeErrorOverlay(source.error!!, onRetry = { viewModel.resolve(target, title) })
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

            // Options panel (right).
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
                    onPlay = { item ->
                        viewModel.switchTo(item)
                        panel = PlayerPanel.NONE
                        reveal()
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

private fun PictureMode.toResizeMode(): Int = when (this) {
    PictureMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    PictureMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    PictureMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
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
