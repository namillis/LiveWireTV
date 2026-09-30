package com.livewire.tv.feature.onboarding

import com.livewire.tv.feature.providers.domain.ProviderAuthResult
import com.livewire.tv.feature.providers.domain.ProviderInputValidator
import com.livewire.tv.feature.providers.domain.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectErrorTest {

    @Test
    fun unreachableWhenNotReachable() {
        val e = ConnectError.from(
            ProviderType.XTREAM,
            ProviderAuthResult(ok = false, message = "Could not reach the provider. Check the URL…"),
            reachable = false,
        )
        assertEquals(ConnectError.Unreachable, e)
        assertEquals(ProviderInputValidator.Field.URL, e.focusField)
    }

    @Test
    fun badCredentialsWhenReachableAndNoInactiveStatus() {
        val e = ConnectError.from(
            ProviderType.XTREAM,
            ProviderAuthResult(ok = false, status = null),
            reachable = true,
        )
        assertEquals(ConnectError.BadCredentials, e)
        assertEquals(ProviderInputValidator.Field.PASSWORD, e.focusField)
    }

    @Test
    fun accountInactiveWhenStatusIsNotActive() {
        val e = ConnectError.from(
            ProviderType.XTREAM,
            ProviderAuthResult(ok = false, status = "Expired"),
            reachable = true,
        )
        assertTrue(e is ConnectError.AccountInactive)
        assertEquals("Expired", (e as ConnectError.AccountInactive).status)
        assertTrue(e.message.contains("expired"))
    }

    @Test
    fun activeStatusButAuthFailedIsBadCredentials() {
        // Defensive: status "Active" yet auth=0 -> credentials, not inactive.
        val e = ConnectError.from(
            ProviderType.XTREAM,
            ProviderAuthResult(ok = false, status = "Active"),
            reachable = true,
        )
        assertEquals(ConnectError.BadCredentials, e)
    }

    @Test
    fun m3uReachableButNotOkIsNoChannels() {
        val e = ConnectError.from(
            ProviderType.M3U,
            ProviderAuthResult(ok = false, message = "The playlist has no live channels."),
            reachable = true,
        )
        assertEquals(ConnectError.NoChannels, e)
    }

    @Test
    fun okResultMapsToNoChannels() {
        // Reaching from() with ok=true only happens on the "authenticated but empty" path.
        val e = ConnectError.from(
            ProviderType.XTREAM,
            ProviderAuthResult(ok = true, status = "Active"),
            reachable = true,
        )
        assertEquals(ConnectError.NoChannels, e)
    }

    @Test
    fun looksReachableDetectsNetworkPhrases() {
        assertFalse(ConnectError.looksReachable("Could not reach the provider. Check the URL, credentials, and network."))
        assertFalse(ConnectError.looksReachable("Connection timed out"))
        // A real Xtream auth-failure message mentions "URL" but is NOT a reachability failure.
        assertTrue(ConnectError.looksReachable("Authentication failed (check URL / credentials)"))
        assertTrue(ConnectError.looksReachable(null))
    }
}
