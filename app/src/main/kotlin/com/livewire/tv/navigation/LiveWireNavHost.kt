package com.livewire.tv.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.livewire.tv.feature.epg.GuideScreen
import com.livewire.tv.feature.home.HomeScreen
import com.livewire.tv.feature.onboarding.OnboardingScreen
import com.livewire.tv.feature.player.PlayerScreen
import com.livewire.tv.feature.providers.ProvidersScreen
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

    /** Build a /player route with URL-encoded stream url + title. */
    fun player(streamUrl: String, title: String): String =
        "$PLAYER?url=${Uri.encode(streamUrl)}&title=${Uri.encode(title)}"
}

/**
 * Root navigation. [startAtHome] is decided once at startup from whether a provider
 * is already configured (first run → onboarding, otherwise → home). This replaces
 * the old auth-gate redirect.
 */
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
                onPlayChannel = { url, title ->
                    navController.navigate(Routes.player(url, title))
                },
                onOpenGuide = { navController.navigate(Routes.GUIDE) },
                onOpenSports = { navController.navigate(Routes.SPORTS) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onPlayChannel = { url, title -> navController.navigate(Routes.player(url, title)) },
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
                onPlayChannel = { url, title ->
                    navController.navigate(Routes.player(url, title))
                },
            )
        }
        composable(Routes.SPORTS) {
            SportsScreen(
                onPlayChannel = { url, title ->
                    navController.navigate(Routes.player(url, title))
                },
            )
        }
        composable(
            route = "${Routes.PLAYER}?url={url}&title={title}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            PlayerScreen(
                streamUrl = entry.arguments?.getString("url").orEmpty(),
                title = entry.arguments?.getString("title").orEmpty(),
                onExit = { navController.popBackStack() },
            )
        }
    }
}
