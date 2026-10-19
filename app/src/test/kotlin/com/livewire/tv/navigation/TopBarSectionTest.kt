package com.livewire.tv.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which tab the top bar marks as current, and where Back goes from the bar. Sub-screens
 * (Providers, Favourites) keep the bar with Settings marked without becoming tabs.
 */
class TopBarSectionTest {

    @Test
    fun topLevelRoutesMapToThemselves() {
        assertEquals(TopLevel.HOME, TopLevel.barSectionOf(Routes.HOME))
        assertEquals(TopLevel.GUIDE, TopLevel.barSectionOf(Routes.GUIDE))
        assertEquals(TopLevel.SPORTS, TopLevel.barSectionOf(Routes.SPORTS))
        assertEquals(TopLevel.SEARCH, TopLevel.barSectionOf(Routes.SEARCH))
        assertEquals(TopLevel.SETTINGS, TopLevel.barSectionOf(Routes.SETTINGS))
    }

    @Test
    fun subScreensKeepTheBarWithSettingsMarked() {
        assertNull(TopLevel.of(Routes.PROVIDERS))
        assertNull(TopLevel.of(Routes.FAVORITES))
        assertEquals(TopLevel.SETTINGS, TopLevel.barSectionOf(Routes.PROVIDERS))
        assertEquals(TopLevel.SETTINGS, TopLevel.barSectionOf(Routes.FAVORITES))
    }

    @Test
    fun playerAndOnboardingHaveNoBar() {
        assertNull(TopLevel.barSectionOf(Routes.ONBOARDING))
        assertNull(TopLevel.barSectionOf(Routes.PLAYER))
        assertNull(TopLevel.barSectionOf(null))
    }

    @Test
    fun backFromTheBarGoesHomeAndExitsOnHome() {
        assertNull(TopLevel.backFromBar(TopLevel.HOME))
        for (section in TopLevel.entries - TopLevel.HOME) {
            assertEquals(TopLevel.HOME, TopLevel.backFromBar(section))
        }
    }
}
