package com.livewire.tv.feature.providers.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Local checks run before any network request, so an obviously incomplete form
 * fails immediately instead of after a connection timeout.
 */
object ProviderInputValidator {

    enum class Field { URL, USERNAME, PASSWORD }

    data class Problem(val field: Field, val message: String)

    fun validate(url: String, username: String, password: String): Problem? {
        urlProblem(url)?.let { return Problem(Field.URL, it) }
        if (username.isBlank()) return Problem(Field.USERNAME, "Enter your username.")
        if (password.isEmpty()) return Problem(Field.PASSWORD, "Enter your password.")
        return null
    }

    /** Null when [url] is a usable http(s) server address. */
    fun urlProblem(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "Enter your server URL."
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Start the server URL with http:// or https://."
        }
        val parsed = trimmed.toHttpUrlOrNull() ?: return "Enter a valid server URL."
        if (parsed.host.isBlank()) return "Enter a valid server URL."
        return null
    }
}
