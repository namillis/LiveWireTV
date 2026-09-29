package com.livewire.tv.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rail-section mapping added so the Providers sub-screen shows the nav rail with
 * Settings lit, without becoming a drawer-selectable destination.
 */
class TopLevelRailSectionTest {

    @Test
    fun topLevelRoutesMapToThemselves() {
        assertEquals(TopLevel.HOME, TopLevel.railSectionOf(Routes.HOME))
        assertEquals(TopLevel.GUIDE, TopLevel.railSectionOf(Routes.GUIDE))
        assertEquals(TopLevel.SPORTS, TopLevel.railSectionOf(Routes.SPORTS))
        assertEquals(TopLevel.SEARCH, TopLevel.railSectionOf(Routes.SEARCH))
        assertEquals(TopLevel.SETTINGS, TopLevel.railSectionOf(Routes.SETTINGS))
    }

    @Test
    fun providersBorrowsTheSettingsSectionForTheRail() {
        // Providers is not a top-level section...
        assertNull(TopLevel.of(Routes.PROVIDERS))
        // ...but its rail lights Settings, so the rail shows and Settings stays highlighted.
        assertEquals(TopLevel.SETTINGS, TopLevel.railSectionOf(Routes.PROVIDERS))
    }

    @Test
    fun screensWithNoRailReturnNull() {
        assertNull(TopLevel.railSectionOf(Routes.ONBOARDING))
        assertNull(TopLevel.railSectionOf(Routes.PLAYER))
        assertNull(TopLevel.railSectionOf(null))
    }
}
