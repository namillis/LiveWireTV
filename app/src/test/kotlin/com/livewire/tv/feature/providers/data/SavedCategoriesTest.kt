package com.livewire.tv.feature.providers.data

import com.livewire.tv.feature.providers.domain.ProviderCategory
import com.livewire.tv.feature.providers.domain.ProviderConfig
import com.livewire.tv.feature.providers.domain.ProviderType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [ProviderRepository.savedLiveCategories]: the last categories saved, for Home before the provider answers. */
class SavedCategoriesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val cfg = ProviderConfig(id = "pool", name = "Pool", baseUrl = "https://iptv.example", username = "tv3", password = "secret")
    private val two = """[{"category_id":"565","category_name":"Popular on TV"},{"category_id":"187","category_name":"Live Sports"}]"""

    /** A panel answering `get_live_categories` with [body] and HTTP [code]. */
    private fun repository(dir: File, body: String, code: Int = 200): ProviderRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return ProviderRepository(XtreamClient(http), M3uClient(http), LiveChannelCache(dir = null), ProviderFileStore(dir))
    }

    @Test fun `categories are saved and read back by a later run`() = runBlocking {
        val dir = tmp.newFolder()
        repository(dir, two).liveCategories(cfg)
        val later = repository(dir, "[]", code = 503)
        assertEquals(listOf(ProviderCategory("565", "Popular on TV"), ProviderCategory("187", "Live Sports")), later.savedLiveCategories(cfg))
    }

    @Test fun `an empty or failed answer never replaces the saved categories`() = runBlocking {
        val dir = tmp.newFolder()
        repository(dir, two).liveCategories(cfg)
        repository(dir, "[]").liveCategories(cfg)
        repository(dir, "{}", code = 403).liveCategories(cfg)
        assertEquals(2, repository(dir, "[]").savedLiveCategories(cfg)?.size)
    }

    @Test fun `nothing saved, an M3U provider, or a deleted provider has none`() = runBlocking {
        val dir = tmp.newFolder()
        assertNull(repository(dir, two).savedLiveCategories(cfg))
        val m3u = cfg.copy(id = "m3u", type = ProviderType.M3U, baseUrl = "https://iptv.example/list.m3u")
        assertNull(repository(dir, two).savedLiveCategories(m3u))

        val repo = repository(dir, two)
        repo.liveCategories(cfg)
        repo.forgetProvider(cfg.id)
        assertNull(repo.savedLiveCategories(cfg))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun `the file name holds no URL or login`() = runBlocking {
        val dir = tmp.newFolder()
        repository(dir, two).liveCategories(cfg)
        val name = dir.listFiles()!!.single().name
        assertTrue(name, name.matches(Regex("[0-9a-f]{16}_live_categories_[0-9a-f]{32}\\.json")))
    }
}
