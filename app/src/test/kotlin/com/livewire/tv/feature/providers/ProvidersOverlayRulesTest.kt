package com.livewire.tv.feature.providers

import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Providers overlay stack rules: which overlay opens on OK, what Back/Cancel does,
 * where focus returns. These are the new behaviours the Option 2 restyle introduces, so
 * they are pinned here independently of the Compose UI.
 */
class ProvidersOverlayRulesTest {

    private val xtream = ProviderConfig(
        id = "p1", name = "My Provider", baseUrl = "http://panel.example.com",
        username = "user123", password = "secret", type = ProviderType.XTREAM,
    )
    private val m3u = ProviderConfig(
        id = "p2", name = "iptv-org US", baseUrl = "https://iptv-org.github.io/iptv/countries/us.m3u",
        type = ProviderType.M3U,
    )

    @Test
    fun okOnRowOpensMenuAndRemembersThatRow() {
        val (overlay, origin) = ProvidersOverlayRules.openMenu(xtream)
        assertEquals(ProvidersOverlay.Menu(xtream), overlay)
        assertEquals(FocusOrigin.Row("p1"), origin)
    }

    @Test
    fun okOnAddOpensEmptyFormReturningToAdd() {
        val (overlay, origin) = ProvidersOverlayRules.openAdd()
        assertEquals(ProvidersOverlay.Form(editing = null), overlay)
        assertEquals(FocusOrigin.Add, origin)
    }

    @Test
    fun menuEditOpensFormForThatProvider() {
        assertEquals(ProvidersOverlay.Form(editing = m3u), ProvidersOverlayRules.editFromMenu(m3u))
    }

    @Test
    fun menuDeleteOpensConfirmationForThatProvider() {
        assertEquals(ProvidersOverlay.ConfirmDelete(m3u), ProvidersOverlayRules.confirmDeleteFromMenu(m3u))
    }

    @Test
    fun backFromConfirmationReturnsToItsMenuNotAllTheWayOut() {
        val confirm = ProvidersOverlay.ConfirmDelete(xtream)
        assertEquals(ProvidersOverlay.Menu(xtream), ProvidersOverlayRules.back(confirm))
    }

    @Test
    fun backFromMenuClosesToTheList() {
        assertEquals(ProvidersOverlay.None, ProvidersOverlayRules.back(ProvidersOverlay.Menu(xtream)))
    }

    @Test
    fun backFromFormClosesToTheList() {
        assertEquals(ProvidersOverlay.None, ProvidersOverlayRules.back(ProvidersOverlay.Form(editing = xtream)))
        assertEquals(ProvidersOverlay.None, ProvidersOverlayRules.back(ProvidersOverlay.Form(editing = null)))
    }

    @Test
    fun closeAlwaysReturnsToTheList() {
        assertEquals(ProvidersOverlay.None, ProvidersOverlayRules.close())
    }

    @Test
    fun openMenuThenDeleteThenBackTwiceUnwindsMenuThenList() {
        // OK on the row → menu
        val (afterOk, origin) = ProvidersOverlayRules.openMenu(xtream)
        assertTrue(afterOk is ProvidersOverlay.Menu)
        // Delete → confirmation
        val confirm = ProvidersOverlayRules.confirmDeleteFromMenu((afterOk as ProvidersOverlay.Menu).provider)
        // Back → back to the menu
        val backToMenu = ProvidersOverlayRules.back(confirm)
        assertEquals(ProvidersOverlay.Menu(xtream), backToMenu)
        // Back again → list, and the remembered origin is still the original row
        assertEquals(ProvidersOverlay.None, ProvidersOverlayRules.back(backToMenu))
        assertEquals(FocusOrigin.Row("p1"), origin)
    }
}
