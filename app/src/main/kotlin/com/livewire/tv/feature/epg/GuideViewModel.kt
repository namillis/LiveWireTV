package com.livewire.tv.feature.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.catchup.data.ArchiveRepository
import com.livewire.tv.feature.epg.domain.CatchupGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackSource
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.settings.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** One guide row: a channel + programmes overlapping the visible window. */
data class GuideRow(
    val channel: LiveChannel,
    val programmes: List<EpgProgramme>,
    /**
     * Start-ms of the past programmes the provider flags as archived on this channel, from the
     * on-demand past-listing fetch (not the shared EPG cache). A past cell whose start is in this
     * set shows the ⟲ badge and plays from its start; past cells not in it are shown but not
     * playable. Empty until the past listings load (or for a no-archive channel).
     */
    val pastArchivedStarts: Set<Long> = emptySet(),
) {
    /** True when [programme] is an archived past programme on this channel (playable catch-up). */
    fun isArchived(programme: EpgProgramme): Boolean = programme.startMs in pastArchivedStarts
}

data class GuideUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Every loaded row, before filtering. */
    val allRows: List<GuideRow> = emptyList(),
    val categories: List<GuideCategory> = emptyList(),
    /** Selected category id, or null for all channels. */
    val categoryId: String? = null,
    val query: String = "",
    /** True once the guide download finished (or failed), so rows are final. */
    val programmesLoaded: Boolean = false,
    val windowStartMs: Long = 0,
    val windowSpanMs: Long = TimeUnit.HOURS.toMillis(4),
    /** True when the window extends into the past because a channel offers catch-up. */
    val pastEnabled: Boolean = false,
    /** The Settings switch for the muted channel preview. */
    val previewEnabled: Boolean = false,
    /** Favourited stream ids for the active provider, for the row star + ★ Favourites filter. */
    val favoriteIds: Set<String> = emptySet(),
    /** Add/remove confirmation for the top-right toast; token increases on each toggle. */
    val confirmation: com.livewire.tv.feature.home.FavoriteConfirmation = com.livewire.tv.feature.home.FavoriteConfirmation(),
) {
    /**
     * The category column entries: ★ Favourites first (count = rows it will show), then the
     * provider categories. "All channels" is added by the column itself via [totalChannels].
     */
    val columnCategories: List<GuideCategory> by lazy {
        listOf(favoritesCategory(allRows, favoriteIds)) + categories
    }

    /** The rows the grid shows: [allRows] narrowed by the category and keyword filters. */
    val rows: List<GuideRow> by lazy { filterGuideRows(allRows, categoryId, query, favoriteIds) }
    val totalChannels: Int get() = allRows.size
    val categoryName: String get() =
        if (categoryId == FAVORITES_CATEGORY_ID) "Favourites"
        else categories.firstOrNull { it.id == categoryId }?.name ?: "All channels"
    /** True when the ★ Favourites filter is on but no favourite is available to show. */
    val favoritesEmpty: Boolean get() = categoryId == FAVORITES_CATEGORY_ID && rows.isEmpty()
}

/**
 * Where the user was in the grid, kept in the ViewModel so it survives the trip to the
 * player and back (the screen's own remembered state is dropped when it leaves composition).
 */
data class GuidePosition(
    val focusedStreamId: String? = null,
    /** The time the user is browsing; Up/Down keep it, Left/Right move it. */
    val anchorMs: Long? = null,
    val listIndex: Int = 0,
    val listOffset: Int = 0,
    val horizontalScroll: Int = 0,
)

@HiltViewModel
class GuideViewModel @Inject constructor(
    private val client: ProviderRepository,
    private val storage: ProviderStorage,
    private val epg: EpgRepository,
    private val archive: ArchiveRepository,
    private val settings: SettingsStore,
    private val favorites: com.livewire.tv.feature.favorites.data.FavoritesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = _state.asStateFlow()

    /** Written by the screen as the user moves; read back when the screen returns. */
    var position: GuidePosition = GuidePosition()

    private var provider: ProviderConfig? = null
    private var loadedAtMs = 0L
    private var favoritesJob: Job? = null
    private var confirmToken = 0
    private var streamFormat: com.livewire.tv.feature.settings.data.StreamFormat =
        com.livewire.tv.feature.settings.data.StreamFormat.TS

    private var loadJob: Job? = null

    fun setCategory(categoryId: String?) {
        _state.update { it.copy(categoryId = categoryId) }
        position = GuidePosition(anchorMs = position.anchorMs)
    }

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        position = GuidePosition(anchorMs = position.anchorMs)
    }

    fun load(forceRefresh: Boolean = false) {
        // Guard against the double-load that a re-composed LaunchedEffect(Unit) triggers:
        // a load already in flight (and not a forced refresh) is left to complete.
        if (!forceRefresh && loadJob?.isActive == true) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val appSettings = settings.settings.first()
            streamFormat = appSettings.streamFormat
            val spanMs = TimeUnit.HOURS.toMillis(appSettings.guideWindowHours.toLong())
            val now = System.currentTimeMillis()
            val providers = storage.load()
            val configured = providers.firstOrNull()

            // Coming back from the player (or another section) re-runs load(). Keep the grid
            // as it is when nothing that shapes it has changed, so the user's place survives.
            if (!forceRefresh && isFresh(configured, spanMs, now)) return@launch

            _state.update { it.copy(loading = it.allRows.isEmpty(), error = null) }

            if (configured == null) {
                _state.update { it.copy(loading = false, error = "No provider configured") }
                return@launch
            }
            val switchedProvider = provider != null && provider != configured
            provider = configured
            if (switchedProvider) position = GuidePosition()
            try {
                // One request for every channel, so any category can be shown; the category
                // list only supplies names and order.
                val categories = runCatching { client.liveCategories(configured) }.getOrDefault(emptyList())
                val channels = client.liveChannels(configured).distinctBy { it.streamId }
                val guideCategories = guideCategories(categories, channels)

                // Catch-up: when any channel offers an archive, open the window further into the
                // past so archived programmes are visible and the user can scroll left into them.
                // Otherwise keep the shipped near-now start (design system §9.4).
                val hasAnyArchive = channels.any { it.hasArchive }
                val windowStart = if (hasAnyArchive) {
                    val maxDays = channels.maxOf { it.archiveDays }
                    CatchupGuide.pastWindowStart(now, CatchupGuide.pastLeadMinutes(maxDays))
                } else {
                    guideWindowStart(now)
                }
                // The forward EPG window is unchanged (near-now start → +span): past programmes
                // come from the on-demand archive fetch, NOT the shared EPG cache, so the cache
                // never grows with past data (the plan's decision). The grid's visible span is
                // widened to cover from [windowStart] through the forward window's end.
                val forwardStart = guideWindowStart(now)
                val forwardWindow = EpgWindow(forwardStart, forwardStart + spanMs)
                val visibleSpan = (forwardStart + spanMs) - windowStart

                // Progressive display: show the channel rows immediately (no programmes yet)
                // and clear the spinner, so the grid is usable while the large guide loads.
                _state.update {
                    it.copy(
                        loading = false,
                        allRows = channels.map { channel -> GuideRow(channel, emptyList()) },
                        categories = guideCategories,
                        // A filter from a previous provider (or a vanished category) no longer
                        // applies — but the ★ Favourites sentinel is always valid.
                        categoryId = it.categoryId?.takeIf { id ->
                            id == FAVORITES_CATEGORY_ID || guideCategories.any { c -> c.id == id }
                        },
                        query = if (switchedProvider) "" else it.query,
                        programmesLoaded = false,
                        windowStartMs = windowStart,
                        windowSpanMs = visibleSpan,
                        pastEnabled = hasAnyArchive,
                    )
                }
                observeFavorites(configured.id)
                // A missing or broken guide should not hide the channel list (M3U
                // playlists often ship without one); rows then show no programmes.
                val ids = channels.mapNotNullTo(HashSet()) { it.epgChannelId }
                val guide = runCatching { epg.fetch(configured, forwardWindow, ids, forceRefresh) }.getOrNull()
                _state.update {
                    if (guide == null) it.copy(programmesLoaded = true) else it.copy(
                        programmesLoaded = true,
                        allRows = channels.map { channel ->
                            GuideRow(
                                channel = channel,
                                programmes = channel.epgChannelId?.let(guide::programmesFor) ?: emptyList(),
                            )
                        },
                    )
                }
                loadedAtMs = now

                // Past listings are fetched per row as rows come on screen (requestPastListings),
                // not for every archive channel up front: one provider request per visible
                // archive channel, cached ~15 min in ArchiveRepository, never in the EPG cache.
                pastRequested.clear()
            } catch (_: Exception) {
                // Only surface an error if we never got as far as showing rows.
                _state.update {
                    if (it.allRows.isEmpty()) {
                        it.copy(loading = false, error = "Could not load the guide. Check the provider and network.")
                    } else it
                }
            }
        }
    }

    /** Archive channels whose past listings were already requested since the last load. */
    private val pastRequested = HashSet<String>()

    /**
     * Fetch [streamId]'s past listings (on demand, memory-only) when its row comes on screen, and
     * merge them into the row: past programmes join the programme list (deduped by start against
     * the forward EPG), and the archived ones' starts go into [GuideRow.pastArchivedStarts] for
     * the ⟲ badge and catch-up playability. Each channel is requested once per load; a failure
     * leaves the row unchanged.
     */
    fun requestPastListings(streamId: String) {
        val s = _state.value
        val cfg = provider ?: return
        if (!s.pastEnabled || !pastRequested.add(streamId)) return
        val channel = s.allRows.firstOrNull { it.channel.streamId == streamId }?.channel ?: return
        if (!channel.hasArchive) return
        val fromMs = s.windowStartMs
        viewModelScope.launch {
            val nowMs = System.currentTimeMillis()
            val past = runCatching { archive.pastListings(cfg, channel, fromMs, nowMs, nowMs) }.getOrDefault(emptyList())
            if (past.isEmpty()) return@launch
            val archivedStarts = past.filter { it.hasArchive }.mapTo(HashSet()) { it.startMs }
            val pastProgrammes = past.map { p ->
                EpgProgramme(
                    channelId = channel.epgChannelId ?: channel.streamId,
                    startMs = p.startMs,
                    stopMs = p.stopMs,
                    title = p.title,
                    description = p.description,
                )
            }
            _state.update { st ->
                // A reload or provider switch since the request: drop the stale result.
                if (provider != cfg || st.windowStartMs != fromMs) return@update st
                st.copy(
                    allRows = st.allRows.map { row ->
                        if (row.channel.streamId != streamId) {
                            row
                        } else {
                            val existingStarts = row.programmes.mapTo(HashSet()) { it.startMs }
                            val merged = (row.programmes + pastProgrammes.filter { it.startMs !in existingStarts })
                                .sortedBy { it.startMs }
                            row.copy(programmes = merged, pastArchivedStarts = archivedStarts)
                        }
                    },
                )
            }
        }
    }

    private fun isFresh(configured: ProviderConfig?, spanMs: Long, now: Long): Boolean {
        val s = _state.value
        // The expected start is the past-scroll start when catch-up is on, else the near-now start.
        val expectedStart = if (s.pastEnabled) CatchupGuide.pastWindowStart(now) else guideWindowStart(now)
        // The visible span runs from the window start to the end of the forward window, so a
        // changed guide-window setting changes it and forces a reload.
        val expectedSpan = guideWindowStart(now) + spanMs - expectedStart
        return s.allRows.isNotEmpty() && s.error == null && s.programmesLoaded &&
            configured != null && configured == provider &&
            s.windowSpanMs == expectedSpan &&
            now - loadedAtMs < FRESH_FOR_MS &&
            // Window start past the previous half-hour means the now-line drifted; reload.
            expectedStart == s.windowStartMs
    }

    init {
        // Follow the Settings switch live, so turning it off stops a preview on return.
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(previewEnabled = s.guidePreview) } }
        }
    }

    /**
     * Keep [GuideUiState.favoriteIds] in step with the store for the active provider, and
     * persist any name-fallback id rewrites the resolver makes so the star tracks the current
     * channel id. Re-subscribes when the provider changes.
     */
    private fun observeFavorites(providerId: String) {
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            favorites.favorites(providerId).collect { entries ->
                val resolved = com.livewire.tv.feature.favorites.data.FavoritesLogic
                    .resolve(entries, _state.value.allRows.map { it.channel })
                favorites.applyRematches(providerId, resolved)
                _state.update { it.copy(favoriteIds = entries.map { e -> e.streamId }.toSet()) }
            }
        }
    }

    /** Toggle [channel] in the active provider's favourites (hold-OK menu) and raise a toast. */
    fun toggleFavorite(channel: LiveChannel) {
        val cfg = provider ?: return
        viewModelScope.launch {
            val wasFavourite = favorites.isFavorite(cfg.id, channel.streamId)
            favorites.toggle(cfg.id, channel)
            confirmToken += 1
            _state.update {
                it.copy(
                    confirmation = com.livewire.tv.feature.home.FavoriteConfirmation(
                        token = confirmToken,
                        message = com.livewire.tv.feature.favorites.ui.FavoritesUi
                            .confirmationLabel(nowFavourite = !wasFavourite),
                    ),
                )
            }
        }
    }

    /** The stream to preview for [streamId], resolved the same way the player resolves it. */
    suspend fun previewSource(streamId: String): PlaybackSource? {        val cfg = provider ?: return null
        val format = settings.settings.first().streamFormat
        return client.playbackSource(cfg, streamId, format.ext)
    }

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }

    /** Assemble the Channel info panel model for [channel] from the loaded guide rows. */
    fun channelInfoFor(channel: LiveChannel): com.livewire.tv.feature.favorites.ui.ChannelInfoModel {
        val now = System.currentTimeMillis()
        val programmes = _state.value.allRows.firstOrNull { it.channel.streamId == channel.streamId }?.programmes.orEmpty()
        val category = _state.value.categories.firstOrNull { it.id == channel.categoryId }?.name
        return com.livewire.tv.feature.favorites.ui.ChannelInfoModel(
            channel = channel,
            providerName = provider?.name.orEmpty(),
            categoryLabel = category,
            formatLabel = com.livewire.tv.feature.favorites.ui.ChannelInfo.formatLabel(streamFormat),
            qualityLabel = com.livewire.tv.feature.favorites.ui.ChannelInfo.qualityLabel(channel.name),
            nowPlaying = programmes.firstOrNull { it.airsAt(now) },
            upNext = programmes.filter { it.startMs > now },
            isFavourite = channel.streamId in _state.value.favoriteIds,
        )
    }

    private companion object {
        val FRESH_FOR_MS = TimeUnit.MINUTES.toMillis(30)
    }
}
