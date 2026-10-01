package com.livewire.tv.feature.update.data

import com.livewire.tv.BuildConfig
import com.livewire.tv.feature.update.domain.ReleaseAsset
import com.livewire.tv.feature.update.domain.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the app's latest GitHub release. The repo is public, so the request is
 * unauthenticated (limit 60/hr/IP). We send `Accept: application/vnd.github+json` and
 * cache the `ETag`, replaying it as `If-None-Match` so a "no change" answer is a cheap
 * 304 that doesn't count against a tight budget.
 *
 * The API base, owner and name come from [BuildConfig] so the emulator e2e test can point
 * this at a local server (`-PupdateApiBase=http://127.0.0.1:8765`). When the base is not
 * GitHub the path is the same shape (`/repos/{owner}/{name}/releases/latest`).
 */
@Singleton
class GitHubReleaseSource @Inject constructor(
    private val http: OkHttpClient,
) {
    // In-memory ETag cache. A process-lifetime cache is enough: checks are at most daily.
    @Volatile private var cachedEtag: String? = null
    @Volatile private var cachedRelease: ReleaseInfo? = null

    private val url: String = buildString {
        append(BuildConfig.UPDATE_API_BASE.trimEnd('/'))
        append("/repos/")
        append(BuildConfig.UPDATE_REPO_OWNER)
        append("/")
        append(BuildConfig.UPDATE_REPO_NAME)
        append("/releases/latest")
    }

    sealed interface Result {
        data class Success(val release: ReleaseInfo) : Result
        /** 304 Not Modified — [release] is the cached value (null if nothing cached yet). */
        data class NotModified(val release: ReleaseInfo?) : Result
        /** The latest release is a draft or prerelease and must be ignored. */
        data object Ignored : Result
        data class Failed(val cause: Throwable?) : Result
    }

    /**
     * Fetch `releases/latest`. Returns [Result.NotModified] on a 304 (using the cached
     * release), [Result.Ignored] when the release is a draft/prerelease, [Result.Success]
     * with a parsed release otherwise, or [Result.Failed] on network/parse errors.
     */
    suspend fun fetchLatest(): Result = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        cachedEtag?.let { builder.header("If-None-Match", it) }

        try {
            http.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 304) return@withContext Result.NotModified(cachedRelease)
                if (!resp.isSuccessful) return@withContext Result.Failed(
                    IOException("HTTP ${resp.code}"),
                )
                val body = resp.body?.string()
                    ?: return@withContext Result.Failed(IOException("Empty body"))
                val release = GitHubReleaseParser.parse(body)
                    ?: return@withContext Result.Failed(IOException("Unparseable release JSON"))
                // Cache the ETag only for a value we actually parsed and will keep.
                resp.header("ETag")?.let { cachedEtag = it }
                cachedRelease = release
                if (release.draft || release.prerelease) Result.Ignored
                else Result.Success(release)
            }
        } catch (e: Exception) {
            Result.Failed(e)
        }
    }
}

/**
 * Pure JSON → [ReleaseInfo] parser for the GitHub `releases/latest` shape. Split out of
 * [GitHubReleaseSource] so it can be unit-tested against a saved fixture without OkHttp.
 */
object GitHubReleaseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse a release JSON document, or null if it cannot be decoded. */
    fun parse(body: String): ReleaseInfo? =
        runCatching { json.decodeFromString(GhRelease.serializer(), body).toReleaseInfo() }.getOrNull()

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<GhAsset> = emptyList(),
    ) {
        fun toReleaseInfo() = ReleaseInfo(
            tagName = tagName,
            name = name,
            notes = body,
            draft = draft,
            prerelease = prerelease,
            publishedAtEpochMs = parseIso8601(publishedAt),
            assets = assets.map {
                ReleaseAsset(it.name, it.size, it.browserDownloadUrl)
            },
        )
    }

    @Serializable
    private data class GhAsset(
        val name: String = "",
        val size: Long = 0,
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    )
}

/**
 * Parse an ISO-8601 instant (`2026-09-30T12:00:00Z`) to epoch ms, or null.
 *
 * Safe below API 26: core library desugaring rewrites `java.time` to the bundled
 * `j$.time` copy (see NetworkModule/parseEspnDate for the same pattern in this repo).
 */
@Suppress("NewApi")
internal fun parseIso8601(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(s.trim()).toEpochMilli() }.getOrNull()
}
