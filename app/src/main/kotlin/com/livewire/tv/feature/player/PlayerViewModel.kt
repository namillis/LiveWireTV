package com.livewire.tv.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.providers.domain.ProviderConfig
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

    private var provider: ProviderConfig? = null
    private var guide: EpgGuide? = null
    private var categoryChannels: List<LiveChannel> = emptyList()

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
