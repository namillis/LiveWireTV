package com.livewire.tv.feature.providers.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Local checks run before any network request, so an obviously incomplete form
 * fails immediately instead of after a connection timeout.
 */
object ProviderInputValidator {

    enum class Field { URL, USERNAME, PASSWORD, EPG_URL }

    data class Problem(val field: Field, val message: String)

    /** Xtream: panel URL plus credentials. */
    fun validate(url: String, username: String, password: String): Problem? {
        urlProblem(url)?.let { return Problem(Field.URL, it) }
        if (username.isBlank()) return Problem(Field.USERNAME, "Enter your username.")
        if (password.isEmpty()) return Problem(Field.PASSWORD, "Enter your password.")
        return null
    }

    /** M3U: playlist URL, plus an optional guide URL. */
    fun validateM3u(playlistUrl: String, epgUrl: String): Problem? {
        urlProblem(playlistUrl, noun = "playlist URL")?.let { return Problem(Field.URL, it) }
        if (epgUrl.isNotBlank()) {
            urlProblem(epgUrl, noun = "guide URL")?.let { return Problem(Field.EPG_URL, it) }
        }
        return null
    }

    fun validate(draft: ProviderDraft): Problem? = when (draft.type) {
        ProviderType.XTREAM -> validate(draft.url, draft.username, draft.password)
        ProviderType.M3U -> validateM3u(draft.url, draft.epgUrl)
    }

    /** Null when [url] is a usable http(s) address. */
    fun urlProblem(url: String, noun: String = "server URL"): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "Enter your $noun."
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Start the $noun with http:// or https://."
        }
        val parsed = trimmed.toHttpUrlOrNull() ?: return "Enter a valid $noun."
        if (parsed.host.isBlank()) return "Enter a valid $noun."
        return null
    }
}

/** Raw form input for either provider type. */
data class ProviderDraft(
    val type: ProviderType,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
) {
    fun toConfig(id: String): ProviderConfig = when (type) {
        ProviderType.XTREAM -> ProviderConfig(
            id = id,
            name = name.trim().ifEmpty { DEFAULT_NAME },
            baseUrl = url.trim().trimEnd('/'),
            username = username.trim(),
            password = password,
            type = ProviderType.XTREAM,
        )
        // Playlist URLs are used verbatim: a trailing slash or query can be significant.
        ProviderType.M3U -> ProviderConfig(
            id = id,
            name = name.trim().ifEmpty { DEFAULT_NAME },
            baseUrl = url.trim(),
            type = ProviderType.M3U,
            epgUrl = epgUrl.trim().ifEmpty { null },
        )
    }

    companion object {
        const val DEFAULT_NAME = "My Provider"

        fun from(cfg: ProviderConfig) = ProviderDraft(
            type = cfg.type,
            name = cfg.name,
            url = cfg.baseUrl,
            username = cfg.username,
            password = cfg.password,
            epgUrl = cfg.epgUrl.orEmpty(),
        )
    }
}
