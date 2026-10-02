package com.livewire.tv.feature.favorites.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.favorites.data.FavoriteEntry
import com.livewire.tv.feature.favorites.data.FavoritesLogic
import com.livewire.tv.feature.favorites.data.FavoritesStore
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** One row of the manage screen: the stored favourite plus the channel it resolves to. */
data class ManageRow(
    val entry: FavoriteEntry,
    /** Position shown to the user, 1-based. */
    val displayNumber: Int,
    /** True when no current channel matches by id or name (the Unavailable row). */
    val unavailable: Boolean,
    /** The now-playing programme title, or null (no guide, or unavailable). */
    val nowPlayingTitle: String?,
)

data class FavoritesManageUiState(
    val loading: Boolean = true,
    /** "Home IPTV · Xtream · 4 channels" for the top line; "" until loaded. */
    val headerSummary: String = "",
    val rows: List<ManageRow> = emptyList(),
)

/**
 * Backs the Settings → Favourites management screen (mockup option1-manage). Reads the active
 * provider's favourites, resolves each to a current [LiveChannel] (flagging the Unavailable
 * ones), and exposes reorder (move up/down) and remove. All ordering goes through
 * [FavoritesStore]/[FavoritesLogic], so the Home rail and Guide filter stay in the same order.
 */
@HiltViewModel
class FavoritesManageViewModel @Inject constructor(
    private val storage: ProviderStorage,
    private val repository: ProviderRepository,
    private val favorites: FavoritesStore,
    private val epg: EpgRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesManageUiState())
    val state: StateFlow<FavoritesManageUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var channels: List<LiveChannel> = emptyList()
    private var guide: EpgGuide? = null

    fun load() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val cfg = storage.load().firstOrNull()
            provider = cfg
            if (cfg == null) {
                _state.update { FavoritesManageUiState(loading = false, headerSummary = "No provider") }
                return@launch
            }
            channels = runCatching { repository.liveChannels(cfg) }.getOrDefault(emptyList())
            // Guide is best-effort: it only fills the now-playing line, never blocks the list.
            val now = System.currentTimeMillis()
            val window = EpgWindow(now - TimeUnit.HOURS.toMillis(1), now + TimeUnit.HOURS.toMillis(2))
            val ids = channels.mapNotNullTo(HashSet()) { it.epgChannelId }
            guide = runCatching { epg.fetch(cfg, window, ids) }.getOrNull()
            observe(cfg)
        }
    }

    private fun observe(cfg: ProviderConfig) {
        viewModelScope.launch {
            favorites.favorites(cfg.id).collect { entries ->
                val resolved = FavoritesLogic.resolve(entries, channels)
                // Keep the fast id path hot by persisting name-fallback rewrites.
                favorites.applyRematches(cfg.id, resolved)
                val rows = resolved.mapIndexed { index, r ->
                    ManageRow(
                        entry = r.entry,
                        displayNumber = index + 1,
                        unavailable = r.unavailable,
                        nowPlayingTitle = r.channel?.epgChannelId?.let { guide?.nowPlaying(it)?.title },
                    )
                }
                _state.update {
                    it.copy(loading = false, headerSummary = headerSummary(cfg, rows.size), rows = rows)
                }
            }
        }
    }

    fun moveUp(streamId: String) = provider?.let { viewModelScope.launch { favorites.moveUp(it.id, streamId) } }
    fun moveDown(streamId: String) = provider?.let { viewModelScope.launch { favorites.moveDown(it.id, streamId) } }
    fun remove(streamId: String) = provider?.let { viewModelScope.launch { favorites.remove(it.id, streamId) } }

    private fun headerSummary(cfg: ProviderConfig, count: Int): String {
        val type = if (cfg.type == ProviderType.XTREAM) "Xtream" else "M3U"
        val channelsLabel = if (count == 1) "1 channel" else "$count channels"
        return "${cfg.name} · $type · $channelsLabel"
    }
}
