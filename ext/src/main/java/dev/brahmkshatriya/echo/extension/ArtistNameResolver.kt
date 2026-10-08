package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal fun String?.usableArtistName(): String? = this
    ?.trim()
    ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) && !it.equals("unknown artist", ignoreCase = true) }

class ArtistNameResolver(
    private val loadArtist: suspend (String) -> YtmArtist
) {
    constructor(api: YoutubeiApi) : this({ id -> api.LoadArtist.loadArtist(id).getOrThrow() })

    private val names = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lookupSemaphore = Semaphore(4)

    suspend fun resolve(
        artists: List<YtmArtist>?,
        knownArtists: List<YtmArtist>? = null,
        knownNames: Map<String, String> = emptyMap()
    ): List<YtmArtist> {
        if (artists.isNullOrEmpty()) return emptyList()

        val hints = buildMap {
            knownArtists.orEmpty().forEach { artist ->
                if (artist.id.isNotBlank()) artist.name.usableArtistName()?.let { putIfAbsent(artist.id, it) }
            }
            knownNames.forEach { (id, name) ->
                if (id.isNotBlank()) name.usableArtistName()?.let { putIfAbsent(id, it) }
            }
        }

        return artists.map { artist ->
            val name = artist.name.usableArtistName()?.also { cacheName(artist.id, it) }
                ?: hints[artist.id]?.also { cacheName(artist.id, it) }
                ?: artist.id.takeIf(::isLookupId)?.let { lookupName(it) }
            artist.copy(name = name ?: artist.name)
        }
    }

    private fun cacheName(id: String, name: String) {
        if (isLookupId(id)) names.putIfAbsent(id, name)
    }

    private suspend fun lookupName(id: String): String? {
        names[id]?.let { return it }
        return locks.getOrPut(id) { Mutex() }.withLock {
            names[id] ?: try {
                lookupSemaphore.withPermit { loadArtist(id).name.usableArtistName() }
                    ?.also { names.putIfAbsent(id, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        fun namesFrom(vararg artists: List<Artist>?): Map<String, String> = buildMap {
            artists.forEach { list ->
                list.orEmpty().forEach { artist ->
                    if (artist.id.isNotBlank()) artist.name.usableArtistName()?.let { putIfAbsent(artist.id, it) }
                }
            }
        }

        private fun isLookupId(id: String): Boolean =
            id.isNotBlank() && !id.startsWith("FORSONG", ignoreCase = true) && !id.startsWith("FORPLAYLIST", ignoreCase = true)
    }
}
