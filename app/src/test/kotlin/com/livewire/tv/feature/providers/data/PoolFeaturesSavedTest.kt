package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.PoolSignals
import com.livewire.tv.feature.providers.domain.ProviderConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** [ProviderRepository.poolFeatures] with the feature list saved between app runs. */
class PoolFeaturesSavedTest {

    @get:Rule val tmp = TemporaryFolder()

    private val cfg = ProviderConfig(id = "pool", name = "Pool", baseUrl = "https://iptv.example", username = "tv3", password = "secret")
    private val full = setOf("retry", "stop", "sources", "event_info", "guide_window")
    private fun pool(vararg features: String) =
        """{"pool":1,"version":1,"features":[${features.joinToString(",") { "\"$it\"" }}]}"""

    private var now = 1_000_000L
    private val calls = AtomicInteger()

    /** What the panel does with one `pool_info` call. */
    private sealed interface Reply {
        data class Body(val code: Int, val body: String, val retryAfter: String? = null) : Reply
        data object Timeout : Reply
        data object NetworkError : Reply
        /** Waits for [release] before answering [then]. */
        data class Slow(val release: CountDownLatch, val then: Reply) : Reply
    }

    private fun answer(vararg features: String) = Reply.Body(200, pool(*features))

    /** A repository whose panel gives [replies] in turn (the last one repeats), saving to [dir]. */
    private fun repository(dir: File?, vararg replies: Reply): ProviderRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val n = calls.incrementAndGet()
            var reply = replies[minOf(n, replies.size) - 1]
            while (reply is Reply.Slow) {
                reply.release.await(10, TimeUnit.SECONDS)
                reply = reply.then
            }
            when (reply) {
                Reply.Timeout -> throw SocketTimeoutException("slow panel")
                Reply.NetworkError -> throw IOException("no route")
                is Reply.Body -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(reply.code).message("x")
                    .apply { reply.retryAfter?.let { header("Retry-After", it) } }
                    .body(reply.body.toResponseBody("application/json".toMediaType())).build()
                is Reply.Slow -> error("unreachable")
            }
        }.build()
        return ProviderRepository(XtreamClient(http), M3uClient(http), LiveChannelCache(dir = null), ProviderFileStore(dir))
            .also { it.clock = { now } }
    }

    /** A folder holding [features] as [cfg]'s saved list, written by an earlier run. */
    private fun savedWith(vararg features: String): File = tmp.newFolder().also { dir ->
        runBlocking {
            val repo = repository(dir, answer(*features))
            repo.poolFeatures(cfg)
            repo.awaitPoolCheck(cfg)
        }
        calls.set(0)
    }

    @Test fun `a later launch uses the saved list at once and checks in the background`() = runBlocking {
        val dir = savedWith(*full.toTypedArray())
        val release = CountDownLatch(1)
        val repo = repository(dir, Reply.Slow(release, answer(*full.toTypedArray())))
        // The panel hasn't answered, and won't until released: the saved list comes back anyway.
        val features = withTimeout(2_000) { repo.poolFeatures(cfg) }
        assertEquals(full, features)
        release.countDown()
        repo.awaitPoolCheck(cfg)
        assertEquals(1, calls.get())
        // Answered now: kept for the run, not asked again.
        now += 24 * 60 * 60_000L
        assertEquals(full, repo.poolFeatures(cfg))
        assertEquals(1, calls.get())
    }

    @Test fun `the background answer replaces the saved list, here and for the next launch`() = runBlocking {
        val dir = savedWith("retry", "stop")
        val repo = repository(dir, answer(*full.toTypedArray()))
        assertEquals(setOf("retry", "stop"), repo.poolFeatures(cfg))
        repo.awaitPoolCheck(cfg)
        assertEquals(full, repo.poolFeatures(cfg))

        val next = repository(dir, Reply.Slow(CountDownLatch(1), Reply.Timeout))
        assertEquals(full, withTimeout(2_000) { next.poolFeatures(cfg) })
    }

    @Test fun `anything that isn't an answer keeps the saved list and asks again later`() = runBlocking {
        val failures = listOf(
            Reply.Timeout,
            Reply.NetworkError,
            Reply.Body(503, """{"error":"Pool unavailable"}"""),
            Reply.Body(429, """{"error":"Too many requests"}""", retryAfter = "60"),
            Reply.Body(200, """{"user_info":{"auth":0}}"""),
            Reply.Body(401, """{"error":"Invalid login"}"""),
            Reply.Body(403, "Forbidden"),
        )
        for (failure in failures) {
            val dir = savedWith(*full.toTypedArray())
            val repo = repository(dir, failure, answer("retry"))
            assertEquals("$failure", full, repo.poolFeatures(cfg))
            repo.awaitPoolCheck(cfg)
            assertEquals("$failure: still the saved list", full, repo.poolFeatures(cfg))
            assertEquals("$failure: one check so far", 1, calls.get())
            // Within the wait: no new check. After it: checked again, and the answer replaces it.
            now += 59_000
            repo.poolFeatures(cfg)
            assertEquals("$failure: no check within the minute", 1, calls.get())
            now += 2_000
            repo.poolFeatures(cfg)
            repo.awaitPoolCheck(cfg)
            assertEquals("$failure: checked again after the minute", 2, calls.get())
            assertEquals(setOf("retry"), repo.poolFeatures(cfg))
            calls.set(0)
        }
    }

    @Test fun `a plain panel's answer replaces the saved list with an empty one`() = runBlocking {
        val plainAnswers = listOf(
            Reply.Body(404, "<html>Not found</html>"),
            Reply.Body(200, """{"user_info":{"auth":1,"status":"Active"},"server_info":{}}"""),
            Reply.Body(200, "[]"),
        )
        for (plain in plainAnswers) {
            val dir = savedWith(*full.toTypedArray())
            val repo = repository(dir, plain)
            repo.poolFeatures(cfg)
            repo.awaitPoolCheck(cfg)
            assertTrue("$plain", repo.poolFeatures(cfg).isEmpty())
            val next = repository(dir, Reply.Slow(CountDownLatch(1), Reply.Timeout))
            assertTrue("$plain: saved as plain", withTimeout(2_000) { next.poolFeatures(cfg) }.isEmpty())
            calls.set(0)
        }
    }

    @Test fun `with nothing saved, a refused login counts as plain for a minute, not for the run`() = runBlocking {
        val repo = repository(tmp.newFolder(), Reply.Body(200, """{"user_info":{"auth":0}}"""), answer(*full.toTypedArray()))
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        now += 30_000
        assertTrue(repo.poolFeatures(cfg).isEmpty())
        assertEquals(1, calls.get())
        now += 31_000
        assertEquals(full, repo.poolFeatures(cfg))
        assertEquals(2, calls.get())
    }

    @Test fun `callers at the same moment share one check, saved list or not`() = runBlocking {
        for (dir in listOf(tmp.newFolder(), savedWith(*full.toTypedArray()))) {
            val release = CountDownLatch(1)
            val repo = repository(dir, Reply.Slow(release, answer(*full.toTypedArray())))
            val callers = (1..6).map { async(kotlinx.coroutines.Dispatchers.IO) { repo.poolFeatures(cfg) } }
            Thread.sleep(200)
            release.countDown()
            callers.awaitAll().forEach { assertEquals(full, it) }
            repo.awaitPoolCheck(cfg)
            assertEquals(1, calls.get())
            calls.set(0)
        }
    }

    @Test fun `forgetProvider deletes the saved list`() = runBlocking {
        val dir = savedWith(*full.toTypedArray())
        assertEquals(1, dir.listFiles()!!.size)
        val repo = repository(dir, answer(*full.toTypedArray()))
        repo.forgetProvider(cfg.id)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun `a check that ends after forgetProvider saves nothing`() = runBlocking {
        val dir = tmp.newFolder()
        val release = CountDownLatch(1)
        val repo = repository(dir, Reply.Slow(release, answer(*full.toTypedArray())))
        val first = async(kotlinx.coroutines.Dispatchers.IO) { repo.poolFeatures(cfg) }
        Thread.sleep(200)
        repo.forgetProvider(cfg.id)
        release.countDown()
        first.await()
        repo.awaitPoolCheck(cfg)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun `the saved file names no URL or login and holds only the feature names`() {
        val dir = savedWith(*full.toTypedArray())
        val file = dir.listFiles()!!.single()
        assertTrue(file.name, file.name.matches(Regex("[0-9a-f]{16}_pool_features_[0-9a-f]{32}\\.json")))
        val text = file.readText()
        for (secret in listOf("secret", "tv3", "iptv.example")) assertFalse(text, secret in text)
        assertTrue(text, PoolSignals.GUIDE_WINDOW in text)
    }

    @Test fun `a new login for the provider replaces its old file`() = runBlocking {
        val dir = savedWith(*full.toTypedArray())
        val repo = repository(dir, answer("retry"))
        repo.poolFeatures(cfg.copy(password = "rotated"))
        repo.awaitPoolCheck(cfg.copy(password = "rotated"))
        assertEquals(1, dir.listFiles()!!.size)
    }
}
