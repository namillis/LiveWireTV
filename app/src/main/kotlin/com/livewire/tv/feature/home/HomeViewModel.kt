package com.livewire.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.favorites.data.FavoritesLogic
import com.livewire.tv.feature.favorites.data.FavoritesStore
import com.livewire.tv.feature.favorites.ui.FavoritesUi
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.providers.domain.ProviderConfig
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

/** One rail: a category name + its channels. The favourites rail uses [isFavorites]. */
data class ChannelRail(
    val categoryId: String,
    val title: String,
    val channels: List<LiveChannel>,
    val isFavorites: Boolean = false,
)

/** A brief add/remove confirmation for the top-right toast; [token] increases on each toggle. */
data class FavoriteConfirmation(val token: Int = 0, val message: String = "")

data class HomeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val rails: List<ChannelRail> = emptyList(),
    val guideLoaded: Boolean = false,
    /** Favourited stream ids for the active provider, for the card star marker. */
    val favoriteIds: Set<String> = emptySet(),
    val confirmation: FavoriteConfirmation = FavoriteConfirmation(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val client: ProviderRepository,
    private val storage: ProviderStorage,
    private val epg: EpgRepository,
    private val settings: SettingsStore,
    private val favorites: FavoritesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var guide: EpgGuide? = null
    private var showNowPlaying: Boolean = true
    private var streamFormat: com.livewire.tv.feature.settings.data.StreamFormat =
        com.livewire.tv.feature.settings.data.StreamFormat.TS

    /** Every loaded channel across the category rails, for resolving favourites by id/name. */
    private var allChannels: List<LiveChannel> = emptyList()
    private var confirmToken = 0

    fun load() {
        _state.update { it.copy(loading = true, error = null, guideLoaded = false) }
        viewModelScope.launch {
            val appSettings = settings.settings.first()
            showNowPlaying = appSettings.showNowPlayingOnCards
            streamFormat = appSettings.streamFormat

            val providers = storage.load()
            if (providers.isEmpty()) {
                _state.update { it.copy(loading = false, error = "No provider configured") }
                return@launch
            }
            provider = providers.first()
            try {
                val categories = client.liveCategories(provider!!)
                val categoryRails = categories.take(6).mapNotNull { category ->
                    val channels = client.liveChannels(provider!!, categoryId = category.id)
                    if (channels.isEmpty()) null else ChannelRail(category.id, category.name, channels)
                }
                allChannels = categoryRails.flatMap { it.channels }.distinctBy { it.streamId }
                publishRails(categoryRails)
                observeFavorites()

                if (showNowPlaying) {
                    val now = System.currentTimeMillis()
                    val window = EpgWindow(
                        startMs = now - TimeUnit.HOURS.toMillis(2),
                        endMs = now + TimeUnit.HOURS.toMillis(12),
                    )
                    val ids = allChannels.mapNotNullTo(HashSet()) { it.epgChannelId }
                    guide = runCatching { epg.fetch(provider!!, window, ids) }.getOrNull()
                    if (guide != null) _state.update { it.copy(guideLoaded = true) }
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(loading = false, error = "Could not load channels. Check the provider and network.")
                }
            }
        }
    }

    /** Keep the favourites rail, the id set and the stored M3U rematches in sync with the store. */
    private fun observeFavorites() {
        val cfg = provider ?: return
        viewModelScope.launch {
            favorites.favorites(cfg.id).collect { entries ->
                val resolved = FavoritesLogic.resolve(entries, allChannels)
                // Persist any name-fallback id rewrites so the fast id path hits next time.
                favorites.applyRematches(cfg.id, resolved)
                val favChannels = resolved.mapNotNull { it.channel }
                _state.update { st ->
                    val withoutFav = st.rails.filterNot { it.isFavorites }
                    val rails = if (favChannels.isEmpty()) {
                        withoutFav
                    } else {
                        listOf(ChannelRail("\u0000favorites", "Favourites", favChannels, isFavorites = true)) + withoutFav
                    }
                    st.copy(rails = rails, favoriteIds = entries.map { it.streamId }.toSet())
                }
            }
        }
    }

    private fun publishRails(categoryRails: List<ChannelRail>) {
        _state.update { st ->
            // Preserve a favourites rail already placed by the collector on a reload.
            val fav = st.rails.filter { it.isFavorites }
            st.copy(loading = false, rails = fav + categoryRails)
        }
    }

    /** Toggle [channel] in the active provider's favourites and raise a confirmation toast. */
    fun toggleFavorite(channel: LiveChannel) {
        val cfg = provider ?: return
        viewModelScope.launch {
            val wasFavourite = favorites.isFavorite(cfg.id, channel.streamId)
            favorites.toggle(cfg.id, channel)
            confirmToken += 1
            _state.update {
                it.copy(
                    confirmation = FavoriteConfirmation(
                        token = confirmToken,
                        message = FavoritesUi.confirmationLabel(nowFavourite = !wasFavourite),
                    ),
                )
            }
        }
    }

    fun nowPlaying(channel: LiveChannel): EpgProgramme? {
        if (!showNowPlaying) return null
        return channel.epgChannelId?.let { guide?.nowPlaying(it) }
    }

    /** The programme after the one on now, for the hero band's "UP NEXT" line. */
    fun upNext(channel: LiveChannel): EpgProgramme? {
        if (!showNowPlaying) return null
        return channel.epgChannelId?.let { guide?.upNext(it) }
    }

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }

    /** Assemble the Channel info panel model for [channel] from cheap, already-loaded data. */
    fun channelInfoFor(channel: LiveChannel): com.livewire.tv.feature.favorites.ui.ChannelInfoModel {
        val now = System.currentTimeMillis()
        val programmes = channel.epgChannelId?.let { guide?.programmesFor(it) }.orEmpty()
        return com.livewire.tv.feature.favorites.ui.ChannelInfoModel(
            channel = channel,
            providerName = provider?.name.orEmpty(),
            categoryLabel = state.value.rails.firstOrNull { it.channels.any { c -> c.streamId == channel.streamId } && !it.isFavorites }?.title,
            formatLabel = com.livewire.tv.feature.favorites.ui.ChannelInfo.formatLabel(streamFormat),
            qualityLabel = com.livewire.tv.feature.favorites.ui.ChannelInfo.qualityLabel(channel.name),
            nowPlaying = programmes.firstOrNull { it.airsAt(now) },
            upNext = programmes.filter { it.startMs > now },
            isFavourite = channel.streamId in state.value.favoriteIds,
        )
    }
}
