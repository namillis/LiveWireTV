package com.livewire.tv.feature.epg.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The HTTP side of the guide refresh: If-None-Match goes out, a 304 comes back as NotModified. */
class GuideDownloadTest {

    private val xml = "<?xml version=\"1.0\"?><tv><channel id=\"a\"><display-name>A</display-name></channel>" +
        "<programme start=\"20261109010000 +0000\" stop=\"20261109020000 +0000\" channel=\"a\"><title>News</title></programme></tv>"
    private val url = "https://iptv.example/xmltv.php?username=u&password=p"
    private val requests = mutableListOf<Request>()

    /** A server whose guide has ETag [etag] and that answers a matching If-None-Match with 304. */
    private fun server(etag: String?, code: Int = 200) = OkHttpClient.Builder().addInterceptor { chain ->
        val req = chain.request().also { requests += it }
        val matched = etag != null && req.header("If-None-Match") == etag
        val builder = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
        when {
            matched -> builder.code(304).message("Not Modified").body("".toResponseBody(null))
            else -> builder.code(code).message("x").body(xml.toResponseBody("application/xml".toMediaType()))
        }.apply { if (etag != null) header("ETag", etag) }.build()
    }.build()

    @Test fun `a full download returns the guide and its ETag, without If-None-Match`() {
        val result = EpgRepository.downloadGuide(server("\"v1\""), url, null, null, ifNoneMatch = null)
        result as GuideDownload.Loaded
        assertEquals("\"v1\"", result.etag)
        assertEquals("News", result.guide.programmesFor("a").single().title)
        assertNull(requests.single().header("If-None-Match"))
    }

    @Test fun `a matching ETag comes back as NotModified`() {
        val result = EpgRepository.downloadGuide(server("\"v1\""), url, null, null, ifNoneMatch = "\"v1\"")
        assertSame(GuideDownload.NotModified, result)
        assertEquals("\"v1\"", requests.single().header("If-None-Match"))
    }

    @Test fun `a changed guide downloads in full with the new ETag`() {
        val result = EpgRepository.downloadGuide(server("\"v2\""), url, null, null, ifNoneMatch = "\"v1\"")
        result as GuideDownload.Loaded
        assertEquals("\"v2\"", result.etag)
    }

    @Test fun `a server without ETags gives a null ETag`() {
        val result = EpgRepository.downloadGuide(server(null), url, null, null, ifNoneMatch = null)
        assertNull((result as GuideDownload.Loaded).etag)
    }

    @Test fun `an error response still throws`() {
        try {
            EpgRepository.downloadGuide(server(null, code = 503), url, null, null, ifNoneMatch = "\"v1\"")
            fail("expected a failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("failed"))
        }
    }
}
