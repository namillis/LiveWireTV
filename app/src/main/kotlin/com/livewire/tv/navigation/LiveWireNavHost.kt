package com.livewire.tv.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.livewire.tv.feature.epg.GuideScreen
import com.livewire.tv.feature.home.HomeScreen
import com.livewire.tv.feature.onboarding.OnboardingScreen
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
    const val PLAYER = "player"

    /** Build a player route without embedding credentials or a complete stream URL. */
    fun player(target: PlaybackTarget, title: String): String =
        "$PLAYER?providerId=${Uri.encode(target.providerId)}&streamId=${Uri.encode(target.streamId)}&title=${Uri.encode(title)}"
}

/** Root navigation. Provider configuration chooses onboarding versus Home at startup. */
@Composable
fun LiveWireNavHost(
    startAtHome: Boolean,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = if (startAtHome) Routes.HOME else Routes.ONBOARDING,
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
                onPlayChannel = { target, title -> navController.navigate(Routes.player(target, title)) },
                onOpenGuide = { navController.navigate(Routes.GUIDE) },
                onOpenSports = { navController.navigate(Routes.SPORTS) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onPlayChannel = { target, title -> navController.navigate(Routes.player(target, title)) },
                onOpenGuide = { navController.navigate(Routes.GUIDE) },
                onOpenSports = { navController.navigate(Routes.SPORTS) },
            )
        }
        composable(Routes.PROVIDERS) { ProvidersScreen() }
        composable(Routes.SETTINGS) {
            SettingsScreen(onOpenProviders = { navController.navigate(Routes.PROVIDERS) })
        }
        composable(Routes.GUIDE) {
            GuideScreen(
                onPlayChannel = { target, title -> navController.navigate(Routes.player(target, title)) },
            )
        }
        composable(Routes.SPORTS) {
            SportsScreen(
                onPlayChannel = { target, title -> navController.navigate(Routes.player(target, title)) },
            )
        }
        composable(
            route = "${Routes.PLAYER}?providerId={providerId}&streamId={streamId}&title={title}",
            arguments = listOf(
                navArgument("providerId") { type = NavType.StringType; defaultValue = "" },
                navArgument("streamId") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            PlayerScreen(
                target = PlaybackTarget(
                    providerId = entry.arguments?.getString("providerId").orEmpty(),
                    streamId = entry.arguments?.getString("streamId").orEmpty(),
                ),
                title = entry.arguments?.getString("title").orEmpty(),
                onExit = { navController.popBackStack() },
            )
        }
    }
}
