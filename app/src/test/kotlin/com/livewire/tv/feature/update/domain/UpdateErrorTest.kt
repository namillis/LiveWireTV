package com.livewire.tv.feature.update.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UpdateErrorTest {

    @Test fun `unknown host and connect refused map to no connection`() {
        assertEquals(UpdateError.NO_CONNECTION, mapNetworkError(UnknownHostException("api.github.com")))
        assertEquals(UpdateError.NO_CONNECTION, mapNetworkError(ConnectException("refused")))
    }

    @Test fun `timeout and other io map to github unavailable`() {
        assertEquals(UpdateError.GITHUB_UNAVAILABLE, mapNetworkError(SocketTimeoutException("timeout")))
        assertEquals(UpdateError.GITHUB_UNAVAILABLE, mapNetworkError(IOException("HTTP 503")))
        assertEquals(UpdateError.GITHUB_UNAVAILABLE, mapNetworkError(null))
    }

    @Test fun `every error carries a non-blank user-facing message`() {
        UpdateError.entries.forEach { assertFalse("blank message for $it", it.message.isBlank()) }
    }
}
