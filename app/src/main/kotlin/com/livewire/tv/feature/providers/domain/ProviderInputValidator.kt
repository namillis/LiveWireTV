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

    /**
     * Tidy a server/playlist URL the way a remote user is likely to want:
     * trim surrounding whitespace, add a default `http://` scheme when none was
     * typed, and drop a single trailing `/`. Pure and side-effect free.
     *
     * A blank input is returned unchanged so an empty field is not turned into a
     * bare scheme. The scheme test only looks for `<letters>://` so a value like
     * `provider.example:8080` (a host:port, not a scheme) gets `http://` added.
     */
    fun normalizeUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!SCHEME_PREFIX.containsMatchIn(s)) s = "http://$s"
        // Strip one trailing slash, but never the "//" of the scheme itself.
        if (s.endsWith("/") && !s.endsWith("://")) s = s.dropLast(1)
        return s
    }

    /** A server address plus the username and password pulled out of a pasted link. */
    data class XtreamCredentials(
        val server: String,
        val username: String,
        val password: String,
    )

    /**
     * Split a full Xtream link a provider commonly sends
     * (`http://host:port/get.php?username=U&password=P&type=m3u_plus…` or
     * `.../player_api.php?username=U&password=P`) into the panel server URL plus
     * the username and password, so the user can paste one link instead of typing
     * three fields.
     *
     * Returns null unless the URL is a valid http(s) link whose path ends in
     * `get.php` or `player_api.php` AND carries both a non-blank `username` and
     * `password` query parameter. The returned [server] is the scheme, host and
     * port only (path and query dropped), normalised with [normalizeUrl]. Pure.
     */
    fun parseXtreamLink(raw: String): XtreamCredentials? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val url = normalizeUrl(trimmed).toHttpUrlOrNull() ?: return null
        val lastSegment = url.pathSegments.lastOrNull()?.lowercase()
        if (lastSegment != "get.php" && lastSegment != "player_api.php") return null
        val user = url.queryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
        val pass = url.queryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
        // Rebuild the server root as scheme://host[:port], keeping a non-default port.
        val defaultPort = if (url.scheme == "https") 443 else 80
        val server = buildString {
            append(url.scheme).append("://").append(url.host)
            if (url.port != defaultPort) append(":").append(url.port)
        }
        return XtreamCredentials(server = server, username = user, password = pass)
    }

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
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
