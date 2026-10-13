package com.livewire.tv.core.player

import androidx.media3.common.PlaybackException

/**
 * The message the player shows when a stream fails for good (after any quiet retries), picked
 * from the HTTP status the provider answered, if any, and Media3's error code. Only a stream
 * the player got but couldn't read gets the Stream format advice: a refusal, a missing channel
 * or a dropped connection has nothing to do with the format.
 */
object PlaybackErrors {

    /** 401/403/429/456 that kept coming after the quiet retries ([RefusedCodes]). */
    const val REFUSED = "The provider refused the stream. Its connections may be busy. Try again in a moment."

    /** 404/410 on a live channel: the provider (or the pool) has no stream for it right now. */
    const val NOT_FOUND = "This channel isn't available from the provider right now. Try again or pick another channel."

    /** 404/410 on catch-up, after the other archive URL form was tried too. */
    const val ARCHIVE_NOT_FOUND = "This programme isn't in the archive."

    /** The connection failed or timed out, before or during the stream. */
    const val NETWORK = "Couldn't reach the stream. The provider may be down. Try again in a moment."

    /** The provider sent something the player couldn't read as video. */
    const val UNREADABLE = "This stream couldn't be read. Try the other Stream format in Settings."

    /** The device's decoder couldn't start or gave up on the video. */
    const val DECODER = "This device couldn't decode this channel. Try again or pick another channel."

    const val GENERIC = "Playback failed. Try again or pick another channel."

    /** Any other HTTP error, with its status so a report can say what the provider answered. */
    fun httpError(status: Int): String = "The provider answered with an error (HTTP $status). Try again in a moment."

    /**
     * [httpStatus] is the provider's answer when the failure was an HTTP error, else null.
     * [errorCode] is [PlaybackException.errorCode]. [archive] is true for catch-up and time shift.
     */
    fun messageFor(httpStatus: Int?, errorCode: Int, archive: Boolean): String = when {
        RefusedCodes.isRefusal(httpStatus) -> REFUSED
        httpStatus == 404 || httpStatus == 410 -> if (archive) ARCHIVE_NOT_FOUND else NOT_FOUND
        httpStatus != null -> httpError(httpStatus)
        errorCode in NETWORK_CODES -> NETWORK
        errorCode in UNREADABLE_CODES -> UNREADABLE
        errorCode in DECODER_CODES -> DECODER
        else -> GENERIC
    }

    private val NETWORK_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
    )

    private val UNREADABLE_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    )

    private val DECODER_CODES = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    )
}
