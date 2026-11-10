package com.livewire.tv.feature.providers.data

import android.content.Context
import com.livewire.tv.feature.providers.domain.ProviderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small per-provider files that let the app start without asking the provider first, such as the
 * pool's feature list. One file per provider and [kind][write], named by one-way hashes of the
 * provider's id and of its connection details (the same scheme as [LiveChannelCache]), so a name
 * never reveals the URL or login, and editing either makes the old file unreachable. Saving a
 * file deletes the one the provider had under an earlier URL or login.
 *
 * Read and write errors are ignored: a missing or damaged file only means the app asks the
 * provider, as it would without this store.
 */
@Singleton
class ProviderFileStore internal constructor(
    /** Where the files live; null keeps nothing (tests). */
    private val dir: File?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "provider_files"))

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** [cfg]'s saved [kind], or null when there is none or it can't be read. */
    suspend fun <T> read(cfg: ProviderConfig, kind: String, serializer: KSerializer<T>): T? {
        val file = fileFor(cfg, kind) ?: return null
        return withContext(io) {
            runCatching { if (file.isFile) json.decodeFromString(serializer, file.readText()) else null }.getOrNull()
        }
    }

    /** Saves [value] as [cfg]'s [kind], replacing what it saved under any earlier URL or login. */
    suspend fun <T> write(cfg: ProviderConfig, kind: String, serializer: KSerializer<T>, value: T) {
        val dir = dir ?: return
        val target = fileFor(cfg, kind) ?: return
        lock.withLock {
            withContext(io) {
                runCatching {
                    dir.mkdirs()
                    // Written beside the target and renamed, so a crash never leaves half a file.
                    val tmp = File(dir, "${target.name}.tmp")
                    tmp.writeText(json.encodeToString(serializer, value))
                    val prefix = kindPrefixFor(cfg.id, kind)
                    dir.listFiles { f -> f.name.startsWith(prefix) && f != tmp && f != target }?.forEach { it.delete() }
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        tmp.renameTo(target)
                    }
                }
            }
        }
    }

    /** Deletes every file [providerId] saved, after the provider is edited or deleted. */
    suspend fun clearProvider(providerId: String) {
        val dir = dir ?: return
        lock.withLock {
            withContext(io) {
                runCatching { dir.listFiles { f -> f.name.startsWith(providerPrefixFor(providerId)) }?.forEach { it.delete() } }
            }
        }
    }

    private fun fileFor(cfg: ProviderConfig, kind: String): File? {
        require(KIND.matches(kind)) { "File kind must be lower-case letters and underscores" }
        return dir?.let { File(it, "${kindPrefixFor(cfg.id, kind)}${sha256Hex(connection(cfg), bytes = 16)}.json") }
    }

    private fun connection(cfg: ProviderConfig): String =
        listOf(cfg.id, cfg.type.name, cfg.baseUrl, cfg.username, cfg.password).joinToString("\u0000")

    private companion object {
        val KIND = Regex("[a-z_]+")

        /** `<hash of the provider id>_`, shared by every file of one provider. */
        fun providerPrefixFor(providerId: String) = "${sha256Hex(providerId, bytes = 8)}_"

        /** `<hash of the provider id>_<kind>_`, shared by one provider's files of one kind. */
        fun kindPrefixFor(providerId: String, kind: String) = "${providerPrefixFor(providerId)}${kind}_"

        fun sha256Hex(material: String, bytes: Int): String =
            MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
                .take(bytes).joinToString("") { "%02x".format(it) }
    }
}
