package com.livewire.tv.navigation

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.livewire.tv.feature.epg.GuideScreen
import com.livewire.tv.feature.home.HomeScreen
import com.livewire.tv.feature.onboarding.OnboardingScreen
import com.livewire.tv.feature.player.ArchiveLaunch
import com.livewire.tv.feature.player.PlayerScreen
import com.livewire.tv.feature.providers.ProvidersScreen
import com.livewire.tv.feature.providers.domain.PlaybackTarget
import com.livewire.tv.feature.search.SearchScreen
import com.livewire.tv.feature.settings.SettingsScreen
import com.livewire.tv.feature.sports.SportsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val GUIDE = "guide"
    const val SPORTS = "sports"
    const val SEARCH = "search"
    const val PROVIDERS = "providers"
    const val SETTINGS = "settings"
    const val FAVORITES = "favorites"
    const val PLAYER = "player"

    /** Build a player route without embedding credentials or a complete stream URL. */
    fun player(target: PlaybackTarget, title: String, fromFavorites: Boolean = false): String =
        "$PLAYER?providerId=${Uri.encode(target.providerId)}&streamId=${Uri.encode(target.streamId)}" +
            "&title=${Uri.encode(title)}&fromFavorites=$fromFavorites"

    /**
     * Player route that opens straight into catch-up for a past programme: the archive window
     * [startMs, endMs] (UTC) and the programme title. No credentials or stream URL in the route
     * — the player's view model resolves the archive source from (provider, stream) on open.
     */
    fun playerArchive(target: PlaybackTarget, title: String, startMs: Long, endMs: Long, programme: String): String =
        player(target, title) +
            "&archiveStart=$startMs&archiveEnd=$endMs&programme=${Uri.encode(programme)}"
}

/** Root navigation. Provider configuration chooses onboarding versus Home at startup. */
@Composable
fun LiveWireNavHost(
    startAtHome: Boolean,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    LiveWireNavShell(
        currentRoute = backStackEntry?.destination?.route,
        onSelect = { section -> navController.navigateToSection(section) },
    ) {
        LiveWireRoutes(startAtHome, navController)
    }
}

/**
 * Switch top-level section. Sections share one back stack rooted at Home, and each
 * keeps its state (scroll position, focus) when you leave and come back.
 */
private fun NavHostController.navigateToSection(section: TopLevel) {
    navigate(section.route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun LiveWireRoutes(startAtHome: Boolean, navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = if (startAtHome) Routes.HOME else Routes.ONBOARDING,
        // No crossfade: during one, the outgoing screen is still focusable, so focus handed
        // over from the drawer lands on it and is lost when it leaves. Instant switches are
        // also cheaper on low-end sticks (design system section 10).
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onConnected = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onPlayChannel = { target, title, fromFavorites ->
                    navController.navigate(Routes.player(target, title, fromFavorites))
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onPlayChannel = { target, title -> navController.navigate(Routes.player(target, title)) },
                onOpenGuide = { navController.navigateToSection(TopLevel.GUIDE) },
            )
        }
        composable(Routes.PROVIDERS) { ProvidersScreen() }
        composable(Routes.FAVORITES) { com.livewire.tv.feature.favorites.ui.FavoritesManageScreen() }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                onOpenFavorites = { navController.navigate(Routes.FAVORITES) },
            )
        }
        composable(Routes.GUIDE) {
            GuideScreen(
                onPlayChannel = { target, title, fromFavorites ->
                    navController.navigate(Routes.player(target, title, fromFavorites))
                },
                onPlayArchive = { target, title, startMs, endMs, programme ->
                    navController.navigate(Routes.playerArchive(target, title, startMs, endMs, programme))
                },
            )
        }
        composable(Routes.SPORTS) {
            SportsScreen(
                onPlayChannel = { target, title -> navController.navigate(Routes.player(target, title)) },
            )
        }
        composable(
            route = "${Routes.PLAYER}?providerId={providerId}&streamId={streamId}&title={title}" +
                "&fromFavorites={fromFavorites}" +
                "&archiveStart={archiveStart}&archiveEnd={archiveEnd}&programme={programme}",
            arguments = listOf(
                navArgument("providerId") { type = NavType.StringType; defaultValue = "" },
                navArgument("streamId") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("fromFavorites") { type = NavType.BoolType; defaultValue = false },
                navArgument("archiveStart") { type = NavType.LongType; defaultValue = 0L },
                navArgument("archiveEnd") { type = NavType.LongType; defaultValue = 0L },
                navArgument("programme") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val start = entry.arguments?.getLong("archiveStart") ?: 0L
            val end = entry.arguments?.getLong("archiveEnd") ?: 0L
            PlayerScreen(
                target = PlaybackTarget(
                    providerId = entry.arguments?.getString("providerId").orEmpty(),
                    streamId = entry.arguments?.getString("streamId").orEmpty(),
                ),
                title = entry.arguments?.getString("title").orEmpty(),
                fromFavorites = entry.arguments?.getBoolean("fromFavorites") ?: false,
                archiveLaunch = if (start > 0L && end > start) {
                    ArchiveLaunch(startMs = start, endMs = end, programme = entry.arguments?.getString("programme").orEmpty())
                } else {
                    null
                },
                onExit = { navController.popBackStack() },
            )
        }
    }
}
