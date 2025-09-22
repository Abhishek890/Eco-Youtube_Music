package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User


object SearchResultsFixer {
    @JvmStatic
    fun fixSearchResults(results: List<EchoMediaItem>): List<EchoMediaItem> {
        return results.map { fixSearchResultItem(it) }
    }
    @JvmStatic
    fun fixSearchResultShelf(shelf: Shelf): Shelf {
        try {
            return when (shelf) {
                is Shelf.Item -> try {
                    Shelf.Item(fixSearchResultItem(shelf.media))
                } catch (e: Exception) {
                    shelf
                }
                
                is Shelf.Lists.Items -> try {
                    Shelf.Lists.Items(
                        id = shelf.id,
                        title = shelf.title,
                        subtitle = shelf.subtitle,
                        list = shelf.list.mapNotNull { 
                            try {
                                fixSearchResultItem(it)
                            } catch (e: Exception) {
                                it 
                            }
                        },
                        more = shelf.more
                    )
                } catch (e: Exception) {
                    shelf
                }
                
                is Shelf.Lists.Tracks -> try {
                    Shelf.Lists.Tracks(
                        id = shelf.id,
                        title = shelf.title,
                        subtitle = shelf.subtitle,
                        list = shelf.list.mapNotNull { 
                            try {
                                fixTrack(it)
                            } catch (e: Exception) {
                                it 
                            }
                        },
                        more = shelf.more
                    )
                } catch (e: Exception) {
                    shelf
                }
                
                is Shelf.Lists.Categories -> try {
                    Shelf.Lists.Categories(
                        id = shelf.id,
                        title = shelf.title,
                        list = shelf.list.map { 
                            try {
                                fixSearchResultCategory(it)
                            } catch (e: Exception) {
                                it 
                            }
                        },
                        more = shelf.more
                    )
                } catch (e: Exception) {
                    shelf
                }
                
                is Shelf.Category -> shelf 
            }
        } catch (e: Exception) {
            return shelf
        }
    }
    @JvmStatic
    private fun fixSearchResultCategory(category: Shelf.Category): Shelf.Category {
        return category 
    }
    @JvmStatic
    fun fixSearchResultItem(item: EchoMediaItem): EchoMediaItem {
        try {
            return when (item) {
                is Track -> fixTrack(item)
                is Album -> fixAlbum(item)
                is Playlist -> fixPlaylist(item)
                is Artist -> item
                is User -> ModelTypeHelper.userToArtist(item)
                else -> item
            }
        } catch (e: Exception) {
            return item
        }
    }
    @JvmStatic
    fun fixTrack(track: Track): Track {
        val fixedArtists = track.artists.mapNotNull { artist ->
            try {
                when (artist) {
                    is Artist -> artist
                    is User -> ModelTypeHelper.userToArtist(artist)
                    else -> {
                        Artist(
                            id = (artist as? EchoMediaItem)?.id ?: "unknown-id",
                            name = try {
                                when (artist) {
                                    is User -> artist.name ?: "Unknown Artist"
                                    is EchoMediaItem -> artist.title ?: artist.id
                                    else -> "Unknown Artist"
                                }
                            } catch (e: Exception) {
                                "Unknown Artist"
                            },
                            cover = (artist as? EchoMediaItem)?.cover,
                            subtitle = (artist as? EchoMediaItem)?.subtitle,
                            extras = (artist as? EchoMediaItem)?.extras ?: emptyMap()
                        )
                    }
                }
            } catch (e: Exception) {
                Artist(
                    id = "fallback-${track.id}-artist",
                    name = "Unknown Artist",
                    cover = null,
                    subtitle = null,
                    extras = mapOf("error" to "Conversion failed")
                )
            }
        }
        
        val fixedAlbum = try {
            track.album?.let { fixAlbum(it) }
        } catch (e: Exception) {
            track.album
        }
        
        return track.copy(
            artists = fixedArtists,
            album = fixedAlbum
        )
    }
    @JvmStatic
    fun fixAlbum(album: Album): Album {
        val fixedArtists = album.artists.mapNotNull { artist ->
            try {
                when (artist) {
                    is Artist -> artist
                    is User -> ModelTypeHelper.userToArtist(artist)
                    else -> {
                        Artist(
                            id = (artist as? EchoMediaItem)?.id ?: "unknown-id",
                            name = try {
                                when (artist) {
                                    is User -> artist.name ?: "Unknown Artist"
                                    is EchoMediaItem -> artist.title ?: artist.id
                                    else -> "Unknown Artist"
                                }
                            } catch (e: Exception) {
                                "Unknown Artist"
                            },
                            cover = (artist as? EchoMediaItem)?.cover,
                            subtitle = (artist as? EchoMediaItem)?.subtitle,
                            extras = (artist as? EchoMediaItem)?.extras ?: emptyMap()
                        )
                    }
                }
            } catch (e: Exception) {
                Artist(
                    id = "fallback-${album.id}-artist",
                    name = "Unknown Artist",
                    cover = null,
                    subtitle = null,
                    extras = mapOf("error" to "Conversion failed")
                )
            }
        }
        
        return album.copy(
            artists = fixedArtists
        )
    }
    @JvmStatic
    fun fixPlaylist(playlist: Playlist): Playlist {
        val fixedAuthors = playlist.authors.mapNotNull { author ->
            try {
                when (author) {
                    is Artist -> author
                    is User -> ModelTypeHelper.userToArtist(author)
                    else -> {
                        Artist(
                            id = (author as? EchoMediaItem)?.id ?: "unknown-id",
                            name = try {
                                if (author is EchoMediaItem) author.title ?: author.id
                                else "Unknown Artist"
                            } catch (e: Exception) {
                                "Unknown Artist"
                            },
                            cover = (author as? EchoMediaItem)?.cover,
                            subtitle = (author as? EchoMediaItem)?.subtitle,
                            extras = (author as? EchoMediaItem)?.extras ?: emptyMap()
                        )
                    }
                }
            } catch (e: Exception) {
                Artist(
                    id = "fallback-${playlist.id}-author",
                    name = "Unknown Artist",
                    cover = null,
                    subtitle = null,
                    extras = mapOf("error" to "Conversion failed")
                )
            }
        }
        
        return playlist.copy(
            authors = fixedAuthors
        )
    }
}