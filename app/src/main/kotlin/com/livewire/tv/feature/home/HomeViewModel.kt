package com.livewire.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livewire.tv.feature.epg.data.EpgRepository
import com.livewire.tv.feature.epg.domain.ChannelProgrammes
import com.livewire.tv.feature.epg.domain.EpgGuide
import com.livewire.tv.feature.epg.domain.EpgProgramme
import com.livewire.tv.feature.epg.domain.EpgWindow
import com.livewire.tv.feature.favorites.data.FavoriteEntry
import com.livewire.tv.feature.favorites.data.FavoritesLogic
import com.livewire.tv.feature.favorites.data.FavoritesStore
import com.livewire.tv.feature.favorites.ui.FavoritesUi
import com.livewire.tv.feature.home.data.HomeRails
import com.livewire.tv.feature.home.data.PinnedCategoriesStore
import com.livewire.tv.feature.providers.data.LiveChannelCache
import com.livewire.tv.feature.providers.data.ProviderRepository
import com.livewire.tv.feature.providers.domain.FailoverWatch
import com.livewire.tv.feature.providers.domain.LiveChannel
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.providers.domain.ProviderCategory
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.search.data.RecentHistoryStore
import com.livewire.tv.feature.search.data.WatchedChannel
import com.livewire.tv.feature.settings.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * One list Home can show: a category name + its channels. The favourites, recently watched and
 * pool Live Now lists are flagged. See [HomeLists] for the order and the default.
 */
data class ChannelRail(
    val categoryId: String,
    val title: String,
    val channels: List<LiveChannel>,
    val isFavorites: Boolean = false,
    val isRecent: Boolean = false,
    val isLiveNow: Boolean = false,
)

/**
 * A brief confirmation for the top-right toast; [token] increases on each one. It stays up for
 * [holdMs]: 2 s for a favourite add/remove, longer for a provider switch the user didn't trigger.
 * A provider switch shows no favourites star ([showStar]).
 */
data class FavoriteConfirmation(
    val token: Int = 0,
    val message: String = "",
    val holdMs: Long = 2_000L,
    val showStar: Boolean = true,
)

data class HomeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Every list the filter dropdown offers, in [HomeLists.lists] order. */
    val rails: List<ChannelRail> = emptyList(),
    /** The list the On now column shows; see [HomeLists.defaultSelection]. */
    val selectedListId: String? = null,
    /** Play the focused channel muted in the preview (Settings > Channel preview). */
    val previewEnabled: Boolean = true,
    val guideLoaded: Boolean = false,
    /** Bumps each time a guide load finishes, so cards re-read now-playing after the rails change. */
    val guideVersion: Int = 0,
    /** Favourited stream ids for the active provider, for the card star marker. */
    val favoriteIds: Set<String> = emptySet(),
    val confirmation: FavoriteConfirmation = FavoriteConfirmation(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val client: ProviderRepository,
    private val active: com.livewire.tv.feature.providers.data.ActiveProvider,
    private val epg: EpgRepository,
    private val settings: SettingsStore,
    private val favorites: FavoritesStore,
    private val pins: PinnedCategoriesStore,
    private val history: RecentHistoryStore,
    private val listChoice: com.livewire.tv.feature.home.data.HomeListStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var provider: ProviderConfig? = null
    private var guide: EpgGuide? = null
    private var showNowPlaying: Boolean = true
    private var streamFormat: com.livewire.tv.feature.settings.data.StreamFormat =
        com.livewire.tv.feature.settings.data.StreamFormat.TS

    /**
     * The provider's full channel list, for resolving favourites and recents by id/name. Falls
     * back to the rails' channels when the full list can't be loaded.
     */
    private var allChannels: List<LiveChannel> = emptyList()
    private var confirmToken = 0

    private var categories: List<ProviderCategory> = emptyList()
    private var favoriteRail: ChannelRail? = null
    private var recentRail: ChannelRail? = null
    private var categoryRails: List<ChannelRail> = emptyList()
    private var liveNowRail: ChannelRail? = null
    private var allRail: ChannelRail? = null
    /** The list picked last time on this provider, read once per load. */
    private var rememberedList: String? = null

    /** Loaded category rails by category id, so a pin change only fetches the new ones. */
    private val railCache = HashMap<String, ChannelRail?>()

    /**
     * The current load and everything it started (rail observers, the backup watch). Cancelled
     * when Home moves to another provider, so the old provider's flows stop writing the rails.
     */
    private var loadJob: Job? = null

    /** When the rails and [allChannels] were last downloaded, for [refreshIfStale]. */
    private var railsLoadedAtMs = 0L
    private var refreshJob: Job? = null

    /** The store's latest favourites and watch history, so a refresh can re-resolve those rails. */
    private var favoriteEntries: List<FavoriteEntry> = emptyList()
    private var watched: List<WatchedChannel> = emptyList()

    fun load() = startLoad(notice = null)

    /** Load Home from the provider [ActiveProvider] picks; [notice] is shown once the rails are up. */
    private fun startLoad(notice: String?) {
        loadJob?.cancel()
        // A refresh still running for the old load would write its provider's rails back.
        refreshJob?.cancel()
        _state.update { it.copy(loading = true, error = null, guideLoaded = false) }
        loadJob = viewModelScope.launch {
            val appSettings = settings.settings.first()
            showNowPlaying = appSettings.showNowPlayingOnCards
            streamFormat = appSettings.streamFormat
            _state.update { it.copy(previewEnabled = appSettings.guidePreview) }

            val cfg = active.current()
            if (cfg == null) {
                _state.update { it.copy(loading = false, error = "No provider configured") }
                return@launch
            }
            val failover = active.activeFailover
            provider = cfg
            try {
                // Favourites and recents can sit in any category, so resolve them against every
                // channel, downloaded alongside the rails rather than after them.
                val fullList = async { runCatching { client.liveChannels(cfg) }.getOrNull().orEmpty() }
                categories = client.liveCategories(cfg)
                if (categories.isEmpty()) active.recheck()
                val pinned = pins.pinned(cfg.id).first()
                rememberedList = listChoice.selected(cfg.id)
                liveNowRail = loadLiveNowRail(cfg)
                categoryRails = loadCategoryRails(cfg, pinned)
                publishRails()
                // Raised only now: while the spinner shows, the toast would fade before Home appears.
                val message = failover?.let { "${it.primary.name} isn't responding. Using ${it.backup.name}." } ?: notice
                if (message != null) showProviderNotice(message)
                allChannels = fullList.await().ifEmpty { categoryRails.flatMap { it.channels }.distinctBy { it.streamId } }
                rebuildAllRail()
                publishRails()
                railsLoadedAtMs = System.currentTimeMillis()
                observeFavorites()
                observeRecent()
                observePins(cfg, pinned)
                if (failover != null) watchForRecovery(cfg, failover.primary)
                loadGuide()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                active.recheck()
                _state.update {
                    it.copy(loading = false, error = "Could not load channels. Check the provider and network.")
                }
            }
        }
    }

    /**
     * While Home shows the backup, ask every [FailoverWatch.POLL_MS] whether the main provider is
     * back, and reload Home on it when it is. Without this Home stays on the backup until the app
     * restarts, because it loads its provider once.
     */
    private fun CoroutineScope.watchForRecovery(backup: ProviderConfig, primary: ProviderConfig) {
        launch {
            val next = FailoverWatch.awaitChange(backup) { active.current() }
            // Something else can also end the failover (the user changed providers in Settings).
            switchProvider(notice = if (next?.id == primary.id) "${primary.name} is back." else null)
        }
    }

    /** Drop everything loaded from the old provider and load Home again. */
    private fun switchProvider(notice: String?) {
        railCache.clear()
        categories = emptyList()
        categoryRails = emptyList()
        liveNowRail = null
        allRail = null
        rememberedList = null
        favoriteRail = null
        recentRail = null
        allChannels = emptyList()
        favoriteEntries = emptyList()
        railsLoadedAtMs = 0L
        guide = null
        _state.update { it.copy(rails = emptyList(), selectedListId = null, favoriteIds = emptySet()) }
        startLoad(notice)
    }

    private fun showProviderNotice(message: String) {
        confirmToken += 1
        _state.update { it.copy(confirmation = FavoriteConfirmation(confirmToken, message, PROVIDER_NOTICE_MS, showStar = false)) }
    }

    private suspend fun loadCategoryRails(cfg: ProviderConfig, pinned: List<String>): List<ChannelRail> =
        HomeRails.categoryRails(categories, pinned).mapNotNull { category ->
            railCache.getOrPut(category.id) {
                val channels = client.liveChannels(cfg, categoryId = category.id)
                if (channels.isEmpty()) null else ChannelRail(category.id, category.name, channels)
            }
        }

    /** The pool's Live Now category as a list, or null for providers that don't send one. */
    private suspend fun loadLiveNowRail(cfg: ProviderConfig): ChannelRail? {
        val category = categories.firstOrNull { HomeLists.isLiveNow(it.name) } ?: return null
        val rail = railCache.getOrPut(category.id) {
            val channels = client.liveChannels(cfg, categoryId = category.id)
            if (channels.isEmpty()) null else ChannelRail(category.id, category.name, channels)
        }
        return rail?.copy(isLiveNow = true)
    }

    private fun rebuildAllRail() {
        allRail = allChannels.takeIf { it.isNotEmpty() }?.let { ChannelRail(HomeLists.ALL_ID, "All channels", it) }
    }

    /** Show [listId] in the On now column and remember it for this provider. */
    fun selectList(listId: String) {
        val cfg = provider ?: return
        if (_state.value.rails.none { it.categoryId == listId }) return
        rememberedList = listId
        _state.update { it.copy(selectedListId = listId) }
        viewModelScope.launch {
            listChoice.select(cfg.id, listId)
            loadGuide()
        }
    }

    /** The stream to preview for [streamId], resolved the same way the player resolves it. */
    suspend fun previewSource(streamId: String): com.livewire.tv.feature.providers.domain.PlaybackSource? {
        val cfg = provider ?: return null
        return client.playbackSource(cfg, streamId, settings.settings.first().streamFormat.ext)
    }

    /** Rebuild the category rails when the pins change (from the Guide's category column). */
    private fun CoroutineScope.observePins(cfg: ProviderConfig, initial: List<String>) {
        launch {
            var last = initial
            pins.pinned(cfg.id).collect { pinned ->
                if (pinned == last) return@collect
                last = pinned
                categoryRails = runCatching { loadCategoryRails(cfg, pinned) }.getOrDefault(categoryRails)
                publishRails()
                loadGuide()
            }
        }
    }

    /** Keep the favourites rail, the id set and the stored M3U rematches in sync with the store. */
    private fun CoroutineScope.observeFavorites() {
        val cfg = provider ?: return
        launch {
            favorites.favorites(cfg.id).collect { entries ->
                favoriteEntries = entries
                rebuildFavoriteRail(cfg)
                _state.update { it.copy(favoriteIds = entries.map { e -> e.streamId }.toSet()) }
                publishRails()
            }
        }
    }

    private suspend fun rebuildFavoriteRail(cfg: ProviderConfig) {
        val resolved = FavoritesLogic.resolve(favoriteEntries, allChannels)
        // Persist any name-fallback id rewrites so the fast id path hits next time.
        favorites.applyRematches(cfg.id, resolved)
        val favChannels = resolved.mapNotNull { it.channel }
        favoriteRail = favChannels.takeIf { it.isNotEmpty() }?.let {
            ChannelRail(HomeLists.FAVORITES_ID, "Favourites", it, isFavorites = true)
        }
    }

    /** Keep the Recently watched rail in step with what the player records. */
    private fun CoroutineScope.observeRecent() {
        val cfg = provider ?: return
        launch {
            history.history.map { it.watched }.distinctUntilChanged().collect { list ->
                watched = list
                rebuildRecentRail(cfg)
                publishRails()
            }
        }
    }

    private fun rebuildRecentRail(cfg: ProviderConfig) {
        val channels = HomeRails.recentlyWatched(watched, cfg.id, allChannels)
        recentRail = channels.takeIf { it.isNotEmpty() }?.let {
            ChannelRail(HomeLists.RECENT_ID, "Recently watched", it, isRecent = true)
        }
    }

    /**
     * Download the rails and the full channel list again when they're older than the shared
     * channel cache ([LiveChannelCache.DEFAULT_TTL_MS]). Home otherwise keeps its first download
     * for the whole app run, so a pinned Live Now rail would never pick up new games, drop finished
     * ones or get later event times. The screen keeps showing the old rails while this runs, and
     * nothing is republished when nothing changed.
     */
    fun refreshIfStale(now: Long = System.currentTimeMillis()) {
        val cfg = provider ?: return
        if (_state.value.rails.isEmpty() || refreshJob?.isActive == true) return
        if (now - railsLoadedAtMs < LiveChannelCache.DEFAULT_TTL_MS) return
        refreshJob = viewModelScope.launch {
            // A provider switch is load()'s job, not this one's.
            if (active.current() != cfg) return@launch
            val fullList = async { runCatching { client.liveChannels(cfg) }.getOrNull().orEmpty() }
            val pinned = pins.pinned(cfg.id).first()
            railCache.clear()
            val live = runCatching { loadLiveNowRail(cfg) }.getOrNull()
            val rails = runCatching { loadCategoryRails(cfg, pinned) }.getOrNull()
            val channels = fullList.await()
            if (rails.isNullOrEmpty() || channels.isEmpty()) {
                // Leave the old rails up and try again on the next visit.
                categoryRails.forEach { railCache[it.categoryId] = it }
                return@launch
            }
            railsLoadedAtMs = now
            val changed = rails != categoryRails || channels != allChannels || live != liveNowRail
            categoryRails = rails
            liveNowRail = live
            allChannels = channels
            if (!changed) return@launch
            rebuildAllRail()
            rebuildFavoriteRail(cfg)
            rebuildRecentRail(cfg)
            publishRails()
            loadGuide()
        }
    }

    /** Publish the dropdown's lists, keeping the current choice while it still exists. */
    private fun publishRails() {
        val rails = HomeLists.lists(liveNowRail, favoriteRail, recentRail, categoryRails, allRail)
        _state.update { st ->
            val keep = st.selectedListId?.takeIf { id -> rails.any { it.categoryId == id } }
            st.copy(loading = false, rails = rails, selectedListId = keep ?: HomeLists.defaultSelection(rails, rememberedList))
        }
    }

    /** The guide for every channel on Home's rails. Served from the guide cache after the first load. */
    private suspend fun loadGuide() {
        val cfg = provider ?: return
        if (!showNowPlaying) return
        val now = System.currentTimeMillis()
        val window = EpgWindow(
            startMs = now - TimeUnit.HOURS.toMillis(2),
            endMs = now + TimeUnit.HOURS.toMillis(12),
        )
        val favChannels = FavoritesLogic.resolve(favorites.favorites(cfg.id).first(), allChannels).mapNotNull { it.channel }
        val shown = _state.value.rails.firstOrNull { it.categoryId == _state.value.selectedListId }?.channels.orEmpty()
        val onHome = categoryRails.flatMap { it.channels } + favChannels + recentRail?.channels.orEmpty() +
            liveNowRail?.channels.orEmpty() + shown.take(GUIDE_ROWS_FOR_ALL)
        val ids = onHome.mapNotNullTo(HashSet()) { it.epgChannelId }
        runCatching { epg.fetch(cfg, window, ids) }.getOrNull()?.let { loaded ->
            guide = loaded
            _state.update { it.copy(guideLoaded = true, guideVersion = it.guideVersion + 1) }
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
        return ChannelProgrammes.now(channel, System.currentTimeMillis(), ChannelProgrammes.from(guide))
    }

    /** The programme after the one on now, for the hero band's "UP NEXT" line. */
    fun upNext(channel: LiveChannel): EpgProgramme? {
        if (!showNowPlaying) return null
        return ChannelProgrammes.next(channel, System.currentTimeMillis(), ChannelProgrammes.from(guide))
    }

    fun playbackTarget(channel: LiveChannel): PlaybackTarget? =
        provider?.let { PlaybackTarget(providerId = it.id, streamId = channel.streamId) }

    /** Assemble the Channel info panel model for [channel] from cheap, already-loaded data. */
    fun channelInfoFor(channel: LiveChannel): com.livewire.tv.feature.favorites.ui.ChannelInfoModel {
        val now = System.currentTimeMillis()
        val lookup = ChannelProgrammes.from(guide)
        return com.livewire.tv.feature.favorites.ui.ChannelInfoModel(
            channel = channel,
            providerName = provider?.name.orEmpty(),
            categoryLabel = categories.firstOrNull { it.id == channel.categoryId }?.name
                ?: categoryRails.firstOrNull { r -> r.channels.any { it.streamId == channel.streamId } }?.title,
            formatLabel = com.livewire.tv.feature.favorites.ui.ChannelInfo.formatLabel(streamFormat),
            qualityLabel = com.livewire.tv.feature.favorites.ui.ChannelInfo.qualityLabel(channel.name),
            nowPlaying = ChannelProgrammes.now(channel, now, lookup),
            upNext = ChannelProgrammes.later(channel, now, lookup),
            isFavourite = channel.streamId in state.value.favoriteIds,
        )
    }

    private companion object {
        /** How many rows of a long list (All channels) get guide data. */
        const val GUIDE_ROWS_FOR_ALL = 300

        /** How long the "isn't responding" and "is back" notices stay on screen. */
        const val PROVIDER_NOTICE_MS = 6_000L
    }
}
