package com.livewire.tv.core.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackErrorsTest {

    private val badStatus = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

    @Test
    fun `a refusal that outlasts the retries says the provider turned it down`() {
        listOf(401, 403, 429, 456, 460).forEach {
            assertEquals("$it", PlaybackErrors.REFUSED, PlaybackErrors.messageFor(it, badStatus, archive = false))
        }
    }

    @Test
    fun `a missing live stream says the channel is unavailable`() {
        assertEquals(PlaybackErrors.NOT_FOUND, PlaybackErrors.messageFor(404, badStatus, archive = false))
        assertEquals(PlaybackErrors.NOT_FOUND, PlaybackErrors.messageFor(410, badStatus, archive = false))
    }

    @Test
    fun `a missing archive stream says the programme isn't in the archive`() {
        assertEquals(PlaybackErrors.ARCHIVE_NOT_FOUND, PlaybackErrors.messageFor(404, badStatus, archive = true))
    }

    @Test
    fun `any other HTTP error names its status`() {
        assertEquals(PlaybackErrors.httpError(503), PlaybackErrors.messageFor(503, badStatus, archive = false))
        assertEquals(PlaybackErrors.httpError(500), PlaybackErrors.messageFor(500, badStatus, archive = true))
    }

    @Test
    fun `a dropped or timed-out connection points at the network`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
        ).forEach { assertEquals("$it", PlaybackErrors.NETWORK, PlaybackErrors.messageFor(null, it, archive = false)) }
    }

    @Test
    fun `only an unreadable stream suggests the other stream format`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ).forEach { assertEquals("$it", PlaybackErrors.UNREADABLE, PlaybackErrors.messageFor(null, it, archive = false)) }
    }

    @Test
    fun `no message but the unreadable one mentions the stream format`() {
        val others = listOf(
            PlaybackErrors.messageFor(403, badStatus, archive = false),
            PlaybackErrors.messageFor(404, badStatus, archive = false),
            PlaybackErrors.messageFor(404, badStatus, archive = true),
            PlaybackErrors.messageFor(502, badStatus, archive = false),
            PlaybackErrors.messageFor(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, archive = false),
            PlaybackErrors.messageFor(null, PlaybackException.ERROR_CODE_DECODING_FAILED, archive = false),
            PlaybackErrors.messageFor(null, PlaybackException.ERROR_CODE_UNSPECIFIED, archive = false),
        )
        others.forEach { assertFalse(it, it.contains("Stream format", ignoreCase = true)) }
    }

    @Test
    fun `a decoder failure says the device couldn't decode it`() {
        assertEquals(
            PlaybackErrors.DECODER,
            PlaybackErrors.messageFor(null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, archive = false),
        )
    }

    @Test
    fun `anything else gets the generic message`() {
        assertEquals(
            PlaybackErrors.GENERIC,
            PlaybackErrors.messageFor(null, PlaybackException.ERROR_CODE_UNSPECIFIED, archive = false),
        )
        assertEquals(
            PlaybackErrors.GENERIC,
            PlaybackErrors.messageFor(null, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, archive = false),
        )
    }
}
