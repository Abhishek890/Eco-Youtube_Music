package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistNameResolverTest {
    @Test
    fun `uses only a matching artist ID hint before loading`() = runBlocking {
        var calls = 0
        val resolver = ArtistNameResolver {
            calls++
            YtmArtist(it, name = "Loaded name")
        }

        val sameId = resolver.resolve(
            listOf(YtmArtist("UC1")),
            knownArtists = listOf(YtmArtist("UC1", name = "Album name"))
        )
        val differentId = resolver.resolve(
            listOf(YtmArtist("UC2")),
            knownArtists = listOf(YtmArtist("UC3", name = "Wrong artist"))
        )

        assertEquals("Album name", sameId.single().name)
        assertEquals("Loaded name", differentId.single().name)
        assertEquals(1, calls)
    }

    @Test
    fun `loads and caches a missing channel name`() = runBlocking {
        var calls = 0
        val resolver = ArtistNameResolver {
            calls++
            YtmArtist(it, name = "  Artist name  ")
        }

        val first = resolver.resolve(listOf(YtmArtist("UC1")))
        val second = resolver.resolve(listOf(YtmArtist("UC1", name = "Unknown")))

        assertEquals("Artist name", first.single().name)
        assertEquals("Artist name", second.single().name)
        assertEquals(1, calls)
    }

    @Test
    fun `does not look up synthetic IDs and leaves meaningful labels intact`() = runBlocking {
        var calls = 0
        val resolver = ArtistNameResolver {
            calls++
            YtmArtist(it)
        }

        val result = resolver.resolve(
            listOf(
                YtmArtist("FORSONGtrack", name = "Various artists"),
                YtmArtist("", name = "Unknown")
            )
        )

        assertEquals("Various artists", result.first().name)
        assertEquals("Unknown", result.last().name)
        assertEquals(0, calls)
    }

    @Test
    fun `matches fallback track artist names by ID`() {
        fun artist(id: String, name: String) = Artist(id = id, name = name)

        val merged = EchoEnhancedSongEndpoint.mergeArtistsById(
            listOf(artist("UC1", "Unknown"), artist("UC2", "Artist B")),
            listOf(artist("UC1", "Artist A"), artist("UC3", "Wrong artist"))
        )

        assertEquals(listOf("Artist A", "Artist B"), merged.map { it.name })
        assertTrue(merged.none { it.name == "Wrong artist" })
    }

    @Test
    fun `falls back to first available list only when primary is empty`() {
        fun artist(id: String, name: String) = Artist(id = id, name = name)

        val fallback = listOf(artist("UC1", "Artist A"))
        assertEquals(fallback, EchoEnhancedSongEndpoint.mergeArtistsById(emptyList(), null, fallback))
    }
}
