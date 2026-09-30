package com.livewire.tv.feature.epg

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.livewire.tv.R
import com.livewire.tv.core.player.ExoPlayerEngine
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireTheme
import kotlinx.coroutines.delay

/** How long focus must rest on a cell before its channel starts previewing. */
internal const val PREVIEW_DELAY_MS = 2_000L

/**
 * One muted player that the Guide owns. It plays the channel under focus behind the details
 * band once focus has rested for [PREVIEW_DELAY_MS], and stops the moment focus moves on,
 * the Guide leaves the screen, or the app goes to the background.
 */
@OptIn(UnstableApi::class)
internal class GuidePreviewPlayer(context: android.content.Context) {
    val engine = ExoPlayerEngine(context).also {
        // Muted, and never takes audio focus: whatever else is playing keeps its sound.
        it.exoPlayer.volume = 0f
    }
    /** Compose state, so the band hides the layer the moment a preview stops. */
    var playingStreamId: String? by mutableStateOf(null)
        private set

    fun play(streamId: String, source: PlaybackSource) {
        playingStreamId = streamId
        engine.open(source.url, play = true, headers = source.headers)
        engine.exoPlayer.volume = 0f
    }

    /** Stops playback and drops the stream, so the provider connection closes now. */
    fun stop() {
        playingStreamId = null
        engine.exoPlayer.stop()
        engine.exoPlayer.clearMediaItems()
    }

    fun release() = engine.release()
}

/**
 * Drives [player] from the focused channel. [streamId] is null whenever nothing in the grid
 * holds focus (the category column or drawer does), which stops the preview.
 */
@Composable
internal fun rememberGuidePreview(
    streamId: String?,
    enabled: Boolean,
    resolve: suspend (String) -> PlaybackSource?,
): GuidePreviewState {
    val context = LocalContext.current
    val player = remember { GuidePreviewPlayer(context) }
    val state = remember { GuidePreviewState(player) }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Backgrounding the app (Home button, screensaver) stops the stream.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val target = streamId.takeIf { enabled && resumed }
    LaunchedEffect(target) {
        state.countdown = 0f
        if (target == null || target != player.playingStreamId) player.stop()
        if (target == null || target == player.playingStreamId) return@LaunchedEffect
        // Count down in small steps so the band can show a thin progress line.
        val steps = 20
        repeat(steps) { i ->
            delay(PREVIEW_DELAY_MS / steps)
            state.countdown = (i + 1f) / steps
        }
        val source = runCatching { resolve(target) }.getOrNull()
        state.countdown = 0f
        if (source != null) player.play(target, source)
    }
    return state
}

internal class GuidePreviewState(val player: GuidePreviewPlayer) {
    /** 0 while idle or playing; rises to 1 during the wait before a preview starts. */
    var countdown by mutableFloatStateOf(0f)
}

/**
 * The preview layer inside the details band: the video filling the band (cropped), a
 * left-to-right fade so the programme text stays readable, and a "PREVIEW · MUTED" tag.
 * Shows nothing until the first frame plays, so there is never a black flash.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun BoxScope.GuidePreviewLayer(state: GuidePreviewState) {
    val status by state.player.engine.status.collectAsStateWithLifecycle()
    val showing = state.player.playingStreamId != null && status.state == PlaybackState.PLAYING
    val alpha by animateFloatAsState(if (showing) 1f else 0f, tween(300), label = "previewAlpha")

    if (alpha > 0f) {
        Box(Modifier.matchParentSize().alpha(alpha)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx).inflate(R.layout.livewire_player_view, android.widget.FrameLayout(ctx), false) as PlayerView)
                        .apply {
                            player = state.player.engine.exoPlayer
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        }
                },
                onRelease = { it.player = null },
            )
            // Solid on the left where the text sits, fading to show the picture on the right.
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        0f to LiveWireColors.Canvas.copy(alpha = 0.96f),
                        0.45f to LiveWireColors.Canvas.copy(alpha = 0.86f),
                        1f to LiveWireColors.Canvas.copy(alpha = 0.25f),
                    ),
                ),
            )
            // Darken the top edge too, so the clock in the band's top-right stays readable.
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to LiveWireColors.Canvas.copy(alpha = 0.7f),
                        0.4f to LiveWireColors.Canvas.copy(alpha = 0f),
                    ),
                ),
            )
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(LiveWireDimens.SpaceS)
                    .clip(RoundedCornerShape(LiveWireDimens.RadiusCell))
                    .background(LiveWireColors.Canvas.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("PREVIEW · MUTED", style = LiveWireTheme.tokens.tag, color = LiveWireColors.OnSurface)
            }
        }
    }

    // A thin neutral line along the bottom while waiting to start (never amber: not focus).
    val progress by animateFloatAsState(state.countdown, tween(100, easing = LinearEasing), label = "previewCountdown")
    if (state.countdown > 0f) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(LiveWireColors.ProgressFill))
        }
    }
}
