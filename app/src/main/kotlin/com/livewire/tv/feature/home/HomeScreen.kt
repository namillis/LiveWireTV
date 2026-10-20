package com.livewire.tv.feature.home

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.livewire.tv.R
import com.livewire.tv.core.player.PlaybackState
import com.livewire.tv.core.player.PlayerFormats
import com.livewire.tv.feature.epg.GuidePreviewState
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EventTimes
import com.livewire.tv.feature.epg.rememberGuidePreview
import com.livewire.tv.feature.favorites.ui.ChannelMenuOverlay
import com.livewire.tv.feature.favorites.ui.FavoriteStar
import com.livewire.tv.feature.favorites.ui.FavoriteToastHost
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.ui.theme.LiveBadge
import com.livewire.tv.ui.theme.LiveWireColors
import com.livewire.tv.ui.theme.LiveWireDimens
import com.livewire.tv.ui.theme.LiveWireProgress
import com.livewire.tv.ui.theme.LiveWireSurface
import com.livewire.tv.ui.theme.LiveWireTheme
import com.livewire.tv.ui.theme.LiveWireType
import com.livewire.tv.ui.theme.Overline
import com.livewire.tv.ui.theme.PerformanceTier
import com.livewire.tv.ui.theme.brandEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** Focus must settle this long before the preview and details follow it, so D-pad scrolling stays smooth. */
private const val DETAILS_SETTLE_MS = 150L

/**
 * Home in B · Grid: a large muted preview of the focused channel with its details below on the
 * left, and the On now list on the right. A filter dropdown above the list switches between
 * Live Now (the pool's), Favourites, Recently watched, the pinned categories and All channels.
 * OK plays, hold OK opens the channel menu.
 */
@kotlin.OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun HomeScreen(
    onPlayChannel: (target: PlaybackTarget, title: String, fromFavorites: Boolean) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The self-update surface, scoped to the Activity so Home and Settings share one state.
    val updateViewModel = com.livewire.tv.feature.update.rememberActivityUpdateViewModel()
    LaunchedEffect(Unit) { updateViewModel.autoCheck(playerOpen = false) }
    val pendingUpdatedVersion by updateViewModel.pendingUpdatedVersion.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val updateDialogShowing = com.livewire.tv.feature.update.isDialogState(updateState)

    // The ViewModel lives as long as Home's back-stack entry, so coming back keeps the lists.
    LaunchedEffect(Unit) { if (viewModel.state.value.rails.isEmpty()) viewModel.load() else viewModel.refreshIfStale() }

    val list = state.rails.firstOrNull { it.categoryId == state.selectedListId }
    val channels = list?.channels.orEmpty()

    // The row the user last had focused, by stream id, saved with this back-stack entry so
    // coming back from the player lands on it.
    var lastFocusedId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var listHasFocus by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }
    var menuChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var infoChannel by remember { mutableStateOf<LiveChannel?>(null) }
    var restoreToken by remember { mutableIntStateOf(0) }
    val rowFocus = remember { FocusRequester() }
    val pillFocus = remember { FocusRequester() }

    val targetIndex = remember(channels, lastFocusedId) {
        channels.indexOfFirst { it.streamId == lastFocusedId }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()

    // The preview and details follow focus after a short settle.
    var settledId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { focusedId }.distinctUntilChanged().debounce(DETAILS_SETTLE_MS).collect { settledId = it }
    }
    val shownId = settledId ?: lastFocusedId
    val shown = channels.firstOrNull { it.streamId == shownId } ?: channels.firstOrNull()
    val shownIndex = channels.indexOf(shown)
    val now = System.currentTimeMillis()
    val nowPlaying = remember(shown, state.guideVersion) { shown?.let(viewModel::nowPlaying) }
    val upNext = remember(shown, state.guideVersion) { shown?.let(viewModel::upNext) }

    val previewOn = state.previewEnabled && LiveWireTheme.tokens.tier == PerformanceTier.STANDARD
    val preview = rememberGuidePreview(
        streamId = shown?.streamId?.takeIf { listHasFocus && menuChannel == null && infoChannel == null },
        enabled = previewOn,
        resolve = viewModel::previewSource,
    )

    // Put focus on a row whenever Home appears or the list changes: the remembered row, else
    // the first. Kept until it holds for a few frames, since coming back from the player the
    // player's focused node goes away in the same frame Home appears.
    suspend fun holdRowFocus() {
        listState.scrollToItem(targetIndex)
        var held = 0
        repeat(40) {
            if (updateDialogShowing || filterOpen) return
            if (listHasFocus) held++ else {
                held = 0
                runCatching { rowFocus.requestFocus() }
            }
            if (held >= 3) return
            delay(50)
        }
    }
    LaunchedEffect(state.selectedListId, channels.isNotEmpty(), updateDialogShowing) {
        if (channels.isNotEmpty() && !updateDialogShowing) holdRowFocus()
    }
    LaunchedEffect(restoreToken) { if (restoreToken > 0) holdRowFocus() }

    Box(Modifier.fillMaxSize().background(LiveWireColors.Canvas)) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> HomeMessage(state.error!!)
            state.rails.isEmpty() -> HomeMessage("No channels found for this provider.")
            else -> Row(
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = LiveWireDimens.SafeHorizontal,
                        end = LiveWireDimens.SafeHorizontal,
                        top = 16.dp,
                        bottom = LiveWireDimens.SafeVertical,
                    ),
            ) {
                Column(Modifier.weight(652f)) {
                    ChannelPreview(shown, preview, previewOn)
                    Spacer(Modifier.height(14.dp))
                    if (shown != null) Details(shown, shownIndex, nowPlaying, upNext, now)
                }
                Spacer(Modifier.width(21.dp))
                Column(Modifier.weight(504f)) {
                    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                        Overline("On now", brandRule = false, liveDot = true)
                        Spacer(Modifier.weight(1f))
                        if (list != null) {
                            FilterPill(list, onClick = { filterOpen = true }, modifier = Modifier.focusRequester(pillFocus))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().onFocusChanged { listHasFocus = it.hasFocus },
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        itemsIndexed(channels, key = { _, c -> c.streamId }) { index, channel ->
                            OnNowRow(
                                number = HomeLists.rowNumber(channel.number, index),
                                channel = channel,
                                nowPlaying = viewModel.nowPlaying(channel),
                                now = now,
                                showStar = channel.streamId in state.favoriteIds && list?.isFavorites != true,
                                onClick = {
                                    viewModel.playbackTarget(channel)?.let { onPlayChannel(it, channel.name, list?.isFavorites == true) }
                                },
                                onLongClick = { menuChannel = channel },
                                modifier = Modifier
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            focusedId = channel.streamId
                                            lastFocusedId = channel.streamId
                                        }
                                    }
                                    .then(if (index == targetIndex) Modifier.focusRequester(rowFocus) else Modifier),
                            )
                        }
                    }
                }
            }
        }

        if (filterOpen) {
            FilterDropdown(
                lists = state.rails,
                selectedId = state.selectedListId,
                onSelect = { id ->
                    filterOpen = false
                    if (id != state.selectedListId) {
                        lastFocusedId = null
                        viewModel.selectList(id)
                    } else {
                        runCatching { pillFocus.requestFocus() }
                    }
                },
                onDismiss = {
                    filterOpen = false
                    runCatching { pillFocus.requestFocus() }
                },
            )
        }

        menuChannel?.let { channel ->
            ChannelMenuOverlay(
                channel = channel,
                isFavourite = channel.streamId in state.favoriteIds,
                onToggleFavourite = { viewModel.toggleFavorite(channel) },
                onPlay = {
                    viewModel.playbackTarget(channel)?.let { onPlayChannel(it, channel.name, channel.streamId in state.favoriteIds) }
                },
                onChannelInfo = { infoChannel = channel; menuChannel = null },
                onDismiss = {
                    menuChannel = null
                    lastFocusedId = channel.streamId
                    restoreToken++
                },
            )
        }

        infoChannel?.let { channel ->
            com.livewire.tv.feature.favorites.ui.ChannelInfoPanel(
                model = viewModel.channelInfoFor(channel),
                now = System.currentTimeMillis(),
                onPlay = {
                    viewModel.playbackTarget(channel)?.let { onPlayChannel(it, channel.name, channel.streamId in state.favoriteIds) }
                    infoChannel = null
                },
                onToggleFavourite = { viewModel.toggleFavorite(channel) },
                onDismiss = {
                    infoChannel = null
                    lastFocusedId = channel.streamId
                    restoreToken++
                },
            )
        }

        FavoriteToastHost(
            token = state.confirmation.token,
            message = state.confirmation.message,
            suppressed = infoChannel != null,
            holdMs = state.confirmation.holdMs,
            showStar = state.confirmation.showStar,
        )
        UpdatedNote(version = pendingUpdatedVersion, onDone = updateViewModel::clearPendingUpdatedVersion)
        com.livewire.tv.feature.update.UpdateOverlay(viewModel = updateViewModel)
    }
}

@Composable
private fun HomeMessage(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(text, style = LiveWireType.Body, color = LiveWireTheme.onSurfaceMuted, modifier = Modifier.padding(24.dp))
    }
}

// ---------------------------------------------------------------- preview

/**
 * The focused channel's preview: its logo while waiting (a thin grey line fills over the 2 s
 * wait), then the muted video. LIVE badge top-left, "PREVIEW · MUTED" top-right while it plays.
 * With the preview off (setting, or the LOW tier) it shows the logo only, with no line.
 */
@Composable
private fun ChannelPreview(channel: LiveChannel?, preview: GuidePreviewState, previewOn: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(LiveWireColors.Surface)
            .border(1.dp, LiveWireColors.Border, shape),
    ) {
        if (channel != null) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth(0.34f).aspectRatio(16f / 10f)) {
                ChannelLogo(channel, Modifier.fillMaxSize(), textSize = 22)
            }
        }
        val playing = previewOn && channel != null && preview.player.playingStreamId == channel.streamId
        if (previewOn) PreviewVideo(preview, playing)
        LiveBadge(Modifier.align(Alignment.TopStart).padding(start = 13.dp, top = 12.dp), color = Color.White)
        if (playing) {
            Text(
                "PREVIEW · MUTED",
                style = LiveWireType.Tag,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
        if (previewOn) {
            val progress by animateFloatAsState(preview.countdown, tween(100, easing = LinearEasing), label = "homePreviewWait")
            if (preview.countdown > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(2.dp).background(LiveWireColors.OnSurfaceMuted))
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun BoxScope.PreviewVideo(preview: GuidePreviewState, wanted: Boolean) {
    val status by preview.player.engine.status.collectAsStateWithLifecycle()
    val showing = wanted && status.state == PlaybackState.PLAYING
    val alpha by animateFloatAsState(if (showing) 1f else 0f, tween(300), label = "homePreviewAlpha")
    if (alpha > 0f) {
        AndroidView(
            modifier = Modifier.matchParentSize().alpha(alpha),
            factory = { ctx ->
                (android.view.LayoutInflater.from(ctx).inflate(R.layout.livewire_player_view, android.widget.FrameLayout(ctx), false) as PlayerView)
                    .apply {
                        player = preview.player.engine.exoPlayer
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
            },
            onRelease = { it.player = null },
        )
    }
}

// ---------------------------------------------------------------- details

@Composable
private fun Details(channel: LiveChannel, index: Int, programme: EpgProgramme?, next: EpgProgramme?, now: Long) {
    Column {
        Overline(
            "${HomeLists.rowNumber(channel.number, index)} · ${channel.name}",
            style = LiveWireType.Overline.copy(fontSize = 9.sp),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            programme?.title ?: channel.name,
            style = LiveWireType.Display,
            color = LiveWireTheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        val meta = when {
            programme == null -> "No guide data for this channel"
            programme.endIsEstimate -> EventTimes.label(programme, now)
            else -> listOfNotNull(PlayerFormats.timeRange(programme.startMs, programme.stopMs), programme.category).joinToString(" · ")
        }
        Text(meta, style = LiveWireType.Body, color = LiveWireTheme.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (programme != null && !programme.endIsEstimate) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveWireProgress(programme.progressAt(now), Modifier.weight(1f), height = 3.dp, dot = 9.dp)
                Spacer(Modifier.width(10.dp))
                Text(minutesLeft(programme, now), style = LiveWireType.Mono.copy(fontSize = 9.75.sp), color = LiveWireTheme.onSurfaceMuted)
            }
        }
        if (next != null) {
            Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("UP NEXT", style = LiveWireType.Overline.copy(fontSize = 9.sp), color = LiveWireTheme.onSurfaceMuted)
                Spacer(Modifier.width(7.dp))
                Text(PlayerFormats.clock(next.startMs), style = LiveWireType.Mono.copy(fontWeight = FontWeight.SemiBold), color = LiveWireTheme.onSurface)
                Spacer(Modifier.width(9.dp))
                Text(next.title, style = LiveWireType.Mono, color = LiveWireTheme.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- list

/** "36m" for the On now rows, "ends soon" in the last minute. */
internal fun shortMinutesLeft(p: EpgProgramme, now: Long): String {
    val mins = ((p.stopMs - now) / 60_000L).coerceAtLeast(0)
    return if (mins < 1) "ends soon" else "${mins}m"
}

@Composable
internal fun OnNowRow(
    number: String,
    channel: LiveChannel,
    nowPlaying: EpgProgramme?,
    now: Long,
    showStar: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveWireSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(9.dp),
        restingColor = Color.Transparent,
        restingBorder = false,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(number, style = LiveWireType.Number, color = LiveWireTheme.onSurfaceMuted, modifier = Modifier.width(28.dp), maxLines = 1)
            ChannelLogo(channel, Modifier.size(width = 48.dp, height = 30.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showStar) {
                        FavoriteStar(size = 10.dp, tint = LiveWireTheme.onSurface)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        nowPlaying?.title ?: channel.name,
                        style = LiveWireType.Title,
                        color = LiveWireTheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (nowPlaying != null) channel.name else " ",
                    style = LiveWireType.Sub,
                    color = LiveWireTheme.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nowPlaying != null) {
                Column(Modifier.width(60.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // A Live Now event's end is a guess, so it shows its start time instead.
                    val label = if (nowPlaying.endIsEstimate) PlayerFormats.clockMeridiem(nowPlaying.startMs) else shortMinutesLeft(nowPlaying, now)
                    Text(label, style = LiveWireType.Mono, color = LiveWireTheme.onSurfaceMuted, maxLines = 1)
                    if (!nowPlaying.endIsEstimate) {
                        LiveWireProgress(nowPlaying.progressAt(now), Modifier.width(42.dp), height = 2.dp, dot = 6.dp, soft = true)
                    }
                }
            }
        }
    }
}

/** A channel's logo on a raised tile, or its name as a wordmark when it has none. */
@Composable
internal fun ChannelLogo(channel: LiveChannel, modifier: Modifier = Modifier, textSize: Int = 9) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(LiveWireColors.SurfaceRaised).padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(model = channel.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                wordmark(channel.name),
                style = LiveWireType.Title.copy(fontWeight = FontWeight.Bold, fontSize = textSize.sp, lineHeight = (textSize + 2).sp),
                color = LiveWireColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- filter

@Composable
private fun listIcon(list: ChannelRail): Int = when {
    list.isFavorites -> R.drawable.ic_fav_star_filled
    list.isRecent -> R.drawable.ic_clock
    list.isLiveNow -> R.drawable.ic_live_pulse
    list.categoryId == HomeLists.ALL_ID -> R.drawable.ic_fav_listings
    else -> R.drawable.ic_pin
}

@Composable
private fun FilterPill(list: ChannelRail, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(onClick = onClick, modifier = modifier.height(24.dp), shape = RoundedCornerShape(50), restingColor = Color.Transparent) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(painterResource(listIcon(list)), contentDescription = null, tint = LiveWireTheme.onSurfaceMuted, modifier = Modifier.size(11.dp))
            Text(list.title, style = LiveWireType.Title.copy(fontSize = 9.75.sp), color = LiveWireTheme.onSurfaceMuted, maxLines = 1)
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = LiveWireTheme.onSurfaceMuted, modifier = Modifier.size(10.dp))
        }
    }
}

/**
 * The filter dropdown under the pill. Focus starts on the current list and stays inside the
 * panel; OK shows a list, Back closes. Each list shows its channel count.
 */
@kotlin.OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FilterDropdown(lists: List<ChannelRail>, selectedId: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    var panelFocused by remember { mutableStateOf(false) }
    // The panel is laid out a frame after it opens; keep asking until it holds focus.
    LaunchedEffect(Unit) {
        repeat(20) {
            if (panelFocused) return@LaunchedEffect
            runCatching { first.requestFocus() }
            delay(30)
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxSize().background(LiveWireColors.Scrim.copy(alpha = 0.55f))) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 44.dp, end = LiveWireDimens.SafeHorizontal)
                .width(225.dp)
                .clip(shape)
                .background(LiveWireColors.Surface)
                .border(1.dp, LiveWireColors.Border, shape)
                .brandEdge()
                .padding(5.dp)
                .verticalScroll(rememberScrollState())
                .onFocusChanged { panelFocused = it.hasFocus }
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
        ) {
            var labelled = false
            lists.forEach { list ->
                val special = list.isLiveNow || list.isFavorites || list.isRecent || list.categoryId == HomeLists.ALL_ID
                if (!special && !labelled) {
                    labelled = true
                    Overline("Categories", brandRule = false, style = LiveWireType.Overline.copy(fontSize = 7.5.sp), modifier = Modifier.padding(start = 10.dp, top = 9.dp, bottom = 4.dp))
                }
                if (list.categoryId == HomeLists.ALL_ID) {
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(LiveWireColors.Border))
                }
                FilterItem(
                    list = list,
                    selected = list.categoryId == selectedId,
                    onClick = { onSelect(list.categoryId) },
                    modifier = if (list.categoryId == (selectedId ?: lists.first().categoryId)) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun FilterItem(list: ChannelRail, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LiveWireSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(30.dp),
        shape = RoundedCornerShape(7.dp),
        restingColor = Color.Transparent,
        restingBorder = false,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (list.isLiveNow) {
                Box(Modifier.size(13.dp), Alignment.Center) { Box(Modifier.size(6.dp).background(LiveWireColors.Live, CircleShape)) }
            } else {
                Icon(painterResource(listIcon(list)), contentDescription = null, tint = LiveWireTheme.onSurfaceMuted, modifier = Modifier.size(13.dp))
            }
            Text(list.title, style = LiveWireType.Title.copy(fontSize = 10.5.sp), color = LiveWireTheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(HomeLists.count(list.channels.size), style = LiveWireType.Mono, color = LiveWireTheme.onSurfaceMuted)
            Box(Modifier.size(12.dp)) {
                if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = "Showing", tint = LiveWireTheme.onSurface)
            }
        }
    }
}

// ---------------------------------------------------------------- misc

/** The "Updated to X" note after a self-update restarts the app, shown once for a few seconds. */
@Composable
private fun UpdatedNote(version: String?, onDone: () -> Unit) {
    if (version == null) return
    LaunchedEffect(version) {
        delay(4000)
        onDone()
    }
    Box(Modifier.fillMaxSize().padding(top = 12.dp, end = LiveWireDimens.SafeHorizontal), contentAlignment = Alignment.TopEnd) {
        Text(
            com.livewire.tv.feature.update.UpdateFormat.updatedNote(version),
            style = LiveWireType.Tag,
            color = LiveWireColors.OnSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(LiveWireColors.SurfaceRaised)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

