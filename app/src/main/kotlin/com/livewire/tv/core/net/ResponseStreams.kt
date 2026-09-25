package com.livewire.tv.core.net

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Wraps a response body stream, transparently un-gzipping it. Providers serve
 * `.gz` guides and playlists both with and without a `Content-Encoding` header, so
 * the gzip magic bytes are checked too.
 */
fun decodedStream(raw: InputStream, contentEncoding: String?): InputStream {
    val buffered = BufferedInputStream(raw)
    return if (contentEncoding.equals("gzip", ignoreCase = true) || isGzip(buffered)) {
        GZIPInputStream(buffered)
    } else {
        buffered
    }
}

private fun isGzip(stream: BufferedInputStream): Boolean {
    stream.mark(2)
    val first = stream.read()
    val second = stream.read()
    stream.reset()
    return first == 0x1f && second == 0x8b
}
