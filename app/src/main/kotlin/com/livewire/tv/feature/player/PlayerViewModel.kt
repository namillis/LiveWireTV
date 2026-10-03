package com.livewire.tv.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.catchup.domain.ArchiveUrlBuilder
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import com.livewire.tv.feature.search.data.RecentHistoryStore
import com.livewire.tv.feature.search.data.WatchedChannel
import com.livewire.tv.feature.settings.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class PlayerSourceState(
    val loading: Boolean = true,
    val source: PlaybackSource? = null,
    val error: String? = null,
    /** The channel currently playing (may differ from the launch target after an in-place switch). */
    val currentStreamId: String? = null,
    val title: String = "",
)

/**
 * Resolved catch-up (archive) request from [PlayerViewModel.playArchive]. [source] is
 * security-sensitive (embeds credentials); [startMs]/[endMs] are the UTC window bounds the
 * player engine uses for `openArchive`.
 *
 * [reopen] is the synchronous URL builder the engine calls when a seek lands outside the
 * buffered range: given an absolute UTC target it returns a fresh archive URL starting there,
 * or null when the target is out of the window. It is pre-resolved (server timezone / M3U
 * entry fetched up front) so it needs no suspension at seek time. [token] changes on every
 * new [playArchive] so the screen can tell a fresh request from a stale recomposition.
 * [timeShift] marks a request that came from pausing live TV (PAUSED_LIVE), not catch-up.
 */
data class ArchiveState(
    val loading: Boolean = false,
    val source: PlaybackSource? = null,
    val error: String? = null,
    val streamId: String? = null,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val title: String = "",
    val programmeTitle: String? = null,
    val timeShift: Boolean = false,
    /** Wall-clock ms when live was paused; stays fixed across time-shift reopens. */
    val pausedAtMs: Long = 0,
    val token: Int = 0,
    val reopen: (targetStartMs: Long) -> String? = { null },
    /** The provider's alternate archive URL for a start (Xtream `timeshift.php`), tried on a 404. */
    val fallback: (targetStartMs: Long) -> String? = { null },
)

/** One row of the channel-list panel: a channel plus its now-playing programme (may be null). */
data class ChannelListItem(
    val channel: LiveChannel,
    val number: String,
    val nowPlaying: EpgProgramme?,
)

/** The channel-list panel's data: the playing channel's category and its channels. */
data class ChannelListState(
    val categoryName: String = "",
    val items: List<ChannelListItem> = emptyList(),
    /** Index of the channel currently playing, or -1 when not in the list. */
    val playingIndex: Int = -1,
)

/**
 * Resolves a player-only source after navigation, keeping credentials out of route state,
 * and drives in-place channel switching plus the channel-list / EPG data the overlay needs.
 *
 * The channel list and now/next come from the SAME repositories Home and Guide use
 * ([ProviderRepository.liveChannels], [EpgRepository]); the EPG cache is shared per provider,
 * so opening the player after Home/Guide reuses the already-downloaded guide rather than
 * fetching a second copy.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val providerStorage: ProviderStorage,
    private val providers: ProviderRepository,
    private val settingsStore: SettingsStore,
    private val history: RecentHistoryStore,
    private val epg: EpgRepository,
    private val favorites: com.livewire.tv.feature.favorites.data.FavoritesStore,
) : ViewModel() {

    private val _source = MutableStateFlow(PlayerSourceState())
    val source: StateFlow<PlayerSourceState> = _source.asStateFlow()

    private val _channels = MutableStateFlow(ChannelListState())
    val channels: StateFlow<ChannelListState> = _channels.asStateFlow()

    /** Favourited stream ids for the active provider, for the Options row + channel-list group. */
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    private val _archive = MutableStateFlow(ArchiveState())
    /**
     * Resolved archive (catch-up) request, populated by [playArchive]. This is the non-UI
     * entry point the Phase C player UI will consume ([ArchiveState.source] +
     * window bounds feed `PlaybackEngine.openArchive`). No UI reads it yet, so wiring it up
     * has no visible effect today.
     */
    val archive: StateFlow<ArchiveState> = _archive.asStateFlow()

    private var provider: ProviderConfig? = null
    private var guide: EpgGuide? = null
    private var categoryChannels: List<LiveChannel> = emptyList()
    private var archiveToken: Int = 1

    /** True when the player was opened from a favourite, so ▲/▼ zap within favourites (task §4). */
    private var fromFavorites: Boolean = false
    private var favoritesJob: kotlinx.coroutines.Job? = null

    /** Resolve and start the launch target, then load its category list + EPG in the background. */
    fun resolve(target: PlaybackTarget, title: String = "", fromFavorites: Boolean = false) {
        this.fromFavorites = fromFavorites
        _source.update { PlayerSourceState(loading = true, currentStreamId = target.streamId, title = title) }
        viewModelScope.launch {
            val cfg = providerStorage.load().firstOrNull { it.id == target.providerId }
            if (cfg == null) {
                _source.update {
                    it.copy(loading = false, error = "This provider is no longer configured.")
                }
                return@launch
            }
            provider = cfg
            observeFavorites(cfg.id)
            openStream(cfg, target.streamId, title)
            loadCategory(cfg, target.streamId)
        }
    }

    /** Keep [favoriteIds] in step with the store for the active provider (Options row + group). */
    private fun observeFavorites(providerId: String) {
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            favorites.favoriteIds(providerId).collect { ids -> _favoriteIds.value = ids }
        }
    }

    /** Toggle the currently-playing channel's favourite state (Options panel row). */
    fun toggleCurrentFavorite() {
        val cfg = provider ?: return
        val channel = currentChannel() ?: return
        viewModelScope.launch { favorites.toggle(cfg.id, channel) }
    }

    /** Switch to [item] in place (requirement 3): re-resolve its source, no navigation. */
    fun switchTo(item: ChannelListItem) = switchToStream(item.channel.streamId, item.channel.name)

    /**
     * Catch-up entry point: resolve the archive source for [channel] between [startMs] and
     * [endMs] (UTC) and publish it on [archive] for the player UI to open via
     * `PlaybackEngine.openArchive`. Also pre-resolves a SYNCHRONOUS [ArchiveState.reopen] so a
     * seek that reopens the stream needs no suspension. Fails soft — an unavailable window
     * sets [ArchiveState.error] ("This programme isn't in the archive") rather than throwing.
     *
     * [timeShift] distinguishes a pause-live request (PAUSED_LIVE) from catch-up playback.
     * [programmeTitle] is the past programme's title for the overlay ("CATCH-UP <title>").
     */
    fun playArchive(
        channel: LiveChannel,
        startMs: Long,
        endMs: Long,
        timeShift: Boolean = false,
        programmeTitle: String? = null,
        pausedAtMs: Long = 0,
    ) {
        val token = archiveToken++
        _archive.update {
            ArchiveState(
                loading = true,
                streamId = channel.streamId,
                startMs = startMs,
                endMs = endMs,
                title = channel.name,
                programmeTitle = programmeTitle,
                timeShift = timeShift,
                pausedAtMs = pausedAtMs,
                token = token,
            )
        }
        viewModelScope.launch {
            val cfg = provider
            if (cfg == null) {
                _archive.update { it.copy(loading = false, error = "This provider is no longer configured.") }
                return@launch
            }
            // Pre-resolve the pieces a synchronous reopen needs: the Xtream server timezone (so
            // archive start times are in the provider's local time) and the M3U entry's live
            // URL + headers. Doing it once here keeps seek-time reopen off the IO dispatcher.
            val serverInfo = if (cfg.type == ProviderType.XTREAM) {
                runCatching { providers.serverInfo(cfg) }.getOrNull()
            } else {
                null
            }
            val m3u = if (cfg.type == ProviderType.M3U) {
                runCatching { providers.m3uEntry(cfg, channel.streamId) }.getOrNull()
            } else {
                null
            }
            val fullWindowMin = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(1)

            // Synchronous builder for both the first open and every reopen-to-seek: an absolute
            // target start, played to the end of the window.
            fun buildAt(target: Long): PlaybackSource? {
                val durMin = ((endMs - target) / 60_000L).toInt().coerceAtLeast(1)
                return ArchiveUrlBuilder.archiveUrl(
                    cfg = cfg,
                    channel = channel,
                    startMs = target,
                    durationMin = durMin,
                    serverInfo = serverInfo,
                    m3uLiveUrl = m3u?.first,
                    m3uHeaders = m3u?.second ?: emptyMap(),
                )
            }
            val reopen: (Long) -> String? = { target -> buildAt(target)?.url }
            val fallback: (Long) -> String? = { target ->
                val durMin = ((endMs - target) / 60_000L).toInt().coerceAtLeast(1)
                ArchiveUrlBuilder.xtreamFallbackUrl(cfg, channel, target, durMin, serverInfo)
            }
            val firstUrl = buildAt(startMs)
            _archive.update {
                // Ignore a result from an older request the user has already moved past.
                if (it.token != token) return@update it
                if (firstUrl == null) {
                    it.copy(loading = false, source = null, error = "This programme isn't in the archive.")
                } else {
                    it.copy(loading = false, source = firstUrl, error = null, reopen = reopen, fallback = fallback)
                }
            }
        }
    }

    /** Live source for [streamId], for "Go live" from archive/time-shift. */
    suspend fun liveSource(streamId: String): PlaybackSource? {
        val cfg = provider ?: return null
        val streamFormat = settingsStore.settings.first().streamFormat
        return runCatching { providers.playbackSource(cfg, streamId, streamFormat.ext) }.getOrNull()
    }

    /** True when [streamId] offers any catch-up window — drives the "can't rewind" card. */
    fun channelHasArchive(streamId: String?): Boolean =
        streamId?.let { id -> categoryChannels.firstOrNull { it.streamId == id }?.hasArchive } ?: false

    /**
     * A Guide catch-up launch: load the provider and the channel for [target] (independently of
     * [resolve]'s own background load), then play the archive window [startMs, endMs] from its
     * start. Fails soft onto [archive]'s error. Credentials never enter the route — only
     * (providerId, streamId) and the window times do.
     */
    fun resolveAndPlayArchive(target: PlaybackTarget, startMs: Long, endMs: Long, programme: String) {
        viewModelScope.launch {
            val cfg = provider ?: providerStorage.load().firstOrNull { it.id == target.providerId }
            if (cfg == null) {
                _archive.update { it.copy(loading = false, error = "This provider is no longer configured.") }
                return@launch
            }
            provider = cfg
            val channel = categoryChannels.firstOrNull { it.streamId == target.streamId }
                ?: runCatching { locateCategory(cfg, target.streamId) }.getOrNull()
                    ?.also { categoryChannels = it.channels }
                    ?.channels?.firstOrNull { it.streamId == target.streamId }
            if (channel == null) {
                _archive.update { it.copy(loading = false, error = "This programme isn't in the archive.") }
                return@launch
            }
            playArchive(channel, startMs, endMs, programmeTitle = programme.ifBlank { null })
        }
    }

    /** ▲/▼ with no panel: play the previous channel in the current category (requirement 4). */
    fun previousChannel() = stepChannel(ChannelNeighbours::previous)

    /** ▲/▼ with no panel: play the next channel in the current category. */
    fun nextChannel() = stepChannel(ChannelNeighbours::next)

    private fun stepChannel(pick: (Int, Int) -> Int) {
        viewModelScope.launch {
            val list = neighbourList()
            if (list.isEmpty()) return@launch
            val current = _source.value.currentStreamId
            val currentIndex = list.indexOfFirst { it.streamId == current }
            val target = pick(currentIndex, list.size)
            list.getOrNull(target)?.let { switchToStream(it.streamId, it.name) }
        }
    }

    /**
     * The ▲/▼ neighbour list: the favourites (in saved order) when the player was opened from
     * a favourite and the current channel is still a favourite, otherwise the playing channel's
     * category. Falling back to the category when the current channel is no longer a favourite
     * keeps zapping working after the user removes the one they started from.
     */
    private suspend fun neighbourList(): List<LiveChannel> {
        val cfg = provider ?: return categoryChannels
        if (!fromFavorites) return categoryChannels
        val entries = favorites.favorites(cfg.id).first()
        // Favourites can span categories, so resolve against ALL the provider's channels
        // (cached per provider, so this reuses the channel list Home/Guide already fetched).
        val all = runCatching { providers.liveChannels(cfg) }.getOrDefault(categoryChannels)
        val favChannels = com.livewire.tv.feature.favorites.data.FavoritesLogic
            .resolve(entries, all)
            .mapNotNull { it.channel }
        val current = _source.value.currentStreamId
        return if (favChannels.any { it.streamId == current }) favChannels else categoryChannels
    }

    private fun switchToStream(streamId: String, title: String) {
        val cfg = provider ?: return
        if (streamId == _source.value.currentStreamId && _source.value.source != null) return
        _source.update { it.copy(loading = true, error = null, source = null, currentStreamId = streamId, title = title) }
        viewModelScope.launch { openStream(cfg, streamId, title) }
    }

    private suspend fun openStream(cfg: ProviderConfig, streamId: String, title: String) {
        val streamFormat = settingsStore.settings.first().streamFormat
        val resolved = runCatching { providers.playbackSource(cfg, streamId, streamFormat.ext) }
        _source.update { prev ->
            resolved.fold(
                onSuccess = { playable ->
                    if (playable == null) {
                        prev.copy(loading = false, source = null, error = "This channel is no longer in the playlist.")
                    } else {
                        prev.copy(loading = false, source = playable, error = null, currentStreamId = streamId, title = title)
                    }
                },
                onFailure = {
                    prev.copy(loading = false, source = null, error = "Could not load this channel. Check the provider and network.")
                },
            )
        }
        if (resolved.getOrNull() != null) {
            // Record history exactly as before: reference by (provider, stream) + a display name.
            history.recordWatched(
                WatchedChannel(
                    providerId = cfg.id,
                    streamId = streamId,
                    name = title,
                    ts = System.currentTimeMillis(),
                ),
            )
            _channels.update { it.copy(playingIndex = categoryChannels.indexOfFirst { c -> c.streamId == streamId }) }
        }
    }

    /**
     * Load the channels in the playing channel's category and the shared EPG, so the
     * channel-list panel and ▲/▼ have data. Best-effort: a missing guide leaves now/next null
     * (the panel then shows "No programme information"), and a channel not found in any
     * category simply leaves the list empty rather than failing playback.
     */
    private suspend fun loadCategory(cfg: ProviderConfig, streamId: String) {
        val located = runCatching { locateCategory(cfg, streamId) }.getOrNull() ?: return
        categoryChannels = located.channels
        _channels.update {
            ChannelListState(
                categoryName = located.categoryName,
                items = located.channels.map { ch ->
                    ChannelListItem(channel = ch, number = channelNumber(ch), nowPlaying = null)
                },
                playingIndex = located.channels.indexOfFirst { it.streamId == streamId },
            )
        }
        // Fetch the guide for just these channels; the per-provider cache means this reuses
        // Home/Guide's download when one already ran.
        val now = System.currentTimeMillis()
        val window = EpgWindow(now - TimeUnit.HOURS.toMillis(1), now + TimeUnit.HOURS.toMillis(6))
        val ids = located.channels.mapNotNullTo(HashSet()) { it.epgChannelId }
        guide = runCatching { epg.fetch(cfg, window, ids) }.getOrNull()
        if (guide != null) {
            _channels.update { state ->
                state.copy(items = state.items.map { it.copy(nowPlaying = nowPlaying(it.channel)) })
            }
        }
    }

    private data class LocatedCategory(val categoryName: String, val channels: List<LiveChannel>)

    /** Find [streamId]'s category and load that category's channels. */
    private suspend fun locateCategory(cfg: ProviderConfig, streamId: String): LocatedCategory? {
        val categories = providers.liveCategories(cfg)
        for (category in categories) {
            val channels = providers.liveChannels(cfg, categoryId = category.id)
            if (channels.any { it.streamId == streamId }) {
                return LocatedCategory(category.name, channels)
            }
        }
        return null
    }

    fun nowPlaying(channel: LiveChannel): EpgProgramme? =
        channel.epgChannelId?.let { guide?.nowPlaying(it) }

    /** The programme airing on [channel] at [atMs] — the title for a time-shifted stream. */
    fun programmeAt(channel: LiveChannel, atMs: Long): EpgProgramme? =
        channel.epgChannelId?.let { guide?.nowPlaying(it, atMs) }

    fun upNext(channel: LiveChannel): EpgProgramme? =
        channel.epgChannelId?.let { guide?.upNext(it) }

    /** The channel currently playing, for the resting overlay's on-now block. */
    fun currentChannel(): LiveChannel? =
        _source.value.currentStreamId?.let { id -> categoryChannels.firstOrNull { it.streamId == id } }

    /** Channel number for display: providers don't expose one, so use the 1-based list position. */
    private fun channelNumber(channel: LiveChannel): String {
        val idx = categoryChannels.indexOfFirst { it.streamId == channel.streamId }
        return if (idx >= 0) (idx + 1).toString() else ""
    }
}
