package dev.brahmkshatriya.echo.extension.providers.feeds

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.brahmkshatriya.echo.extension.ArtistNameResolver
import dev.brahmkshatriya.echo.extension.endpoints.EchoLibraryEndPoint
import dev.brahmkshatriya.echo.extension.toEchoMediaItem
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider


class LibraryFeedProvider(
    private val api: YoutubeiApi,
    private val authManager: YouTubeAuthManager,
    private val libraryEndpoint: EchoLibraryEndPoint,
    private val artistNameResolver: ArtistNameResolver
) {
    suspend fun loadLibraryFeed(thumbnailQuality: ThumbnailProvider.Quality): Feed<Shelf> {
        val tabs = listOf(
            Tab(
                id = "FEmusic_library_landing",
                title = "All",
                isSort = false,
                extras = mapOf(
                    "browseId" to "FEmusic_library_landing",
                    "category" to "All",
                    "isLibraryTab" to "true",
                    "isDefaultLibraryTab" to "true"
                )
            ),
            Tab(
                id = "FEmusic_history",
                title = "History",
                isSort = false,
                extras = mapOf(
                    "browseId" to "FEmusic_history",
                    "category" to "History",
                    "isLibraryTab" to "true",
                    "contentType" to "history"
                )
            ),
            Tab(
                id = "FEmusic_liked_playlists",
                title = "Playlists",
                isSort = false,
                extras = mapOf(
                    "browseId" to "FEmusic_liked_playlists",
                    "category" to "Playlists",
                    "isLibraryTab" to "true",
                    "contentType" to "playlists"
                )
            ),
            Tab(
                id = "FEmusic_liked_videos",
                title = "Songs",
                isSort = false,
                extras = mapOf(
                    "browseId" to "FEmusic_liked_videos",
                    "category" to "Songs",
                    "isLibraryTab" to "true",
                    "contentType" to "liked_songs"
                )
            ),
            Tab(
                id = "FEmusic_library_corpus_track_artists",
                title = "Artists",
                isSort = false,
                extras = mapOf(
                    "browseId" to "FEmusic_library_corpus_track_artists",
                    "category" to "Artists",
                    "isLibraryTab" to "true",
                    "contentType" to "artists"
                )
            )
        )

        return Feed(tabs) { tab ->
            val pagedData = PagedData.Continuous<Shelf> { continuation ->
                val browseId = tab?.id ?: "FEmusic_library_landing"

                if (browseId == "FEmusic_library_corpus_track_artists") {
                    loadLikedArtists(thumbnailQuality)
                } else {
                    loadLibraryContent(browseId, continuation, thumbnailQuality)
                }
            }
            Feed.Data(pagedData)
        }
    }

    private suspend fun loadLikedArtists(
        thumbnailQuality: ThumbnailProvider.Quality
    ): Page<Shelf> {
        return try {
            val auth = authManager.requireAuth()
            val artists = auth.LikedArtists.getLikedArtists().getOrThrow()
            val shelves = artists.mapNotNull { artist ->
                artist.toEchoMediaItem(false, thumbnailQuality, artistNameResolver)?.toShelf()
            }
            Page(shelves, null)
        } catch (e: Exception) {
            println("Failed to load liked artists: ${e.message}")
            Page(emptyList(), null)
        }
    }

    private suspend fun loadLibraryContent(
        browseId: String,
        continuation: String?,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Page<Shelf> {
        val auth = authManager.requireAuth()
        val (result, ctoken) = libraryEndpoint.loadLibraryFeed(browseId, continuation)
        val shelves = result.mapNotNull { playlist ->
            playlist.toEchoMediaItem(false, thumbnailQuality, artistNameResolver)?.let { Shelf.Item(it) }
        }
        return Page(shelves, ctoken)
    }
}
