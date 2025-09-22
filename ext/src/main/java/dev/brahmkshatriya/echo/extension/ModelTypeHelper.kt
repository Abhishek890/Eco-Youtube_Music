package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User


object ModelTypeHelper {
    const val CONVERTED_FROM_USER_KEY = "convertedFromUser"
    const val CONVERTED_FROM_USER_VALUE = "true"
    @JvmStatic
    fun userToArtist(user: User): Artist {
        try {
            val newExtras = user.extras.toMutableMap().apply {
                put(CONVERTED_FROM_USER_KEY, CONVERTED_FROM_USER_VALUE)
            }
            return Artist(
                id = user.id,
                name = user.name,
                cover = user.cover,
                subtitle = user.subtitle,
                extras = newExtras
            )
        } catch (e: Exception) {
            return Artist(
                id = user.id,
                name = user.name ?: "Unknown Artist",
                cover = null,
                subtitle = null,
                extras = mapOf(
                    CONVERTED_FROM_USER_KEY to CONVERTED_FROM_USER_VALUE,
                    "error" to "Conversion failed"
                )
            )
        }
    }
    @JvmStatic
    fun artistToUser(artist: Artist): User = User(
        id = artist.id,
        name = artist.name,
        cover = artist.cover,
        subtitle = artist.subtitle,
        extras = artist.extras
    )
    @JvmStatic
    fun isConvertedArtist(artist: Artist): Boolean {
        return artist.extras[CONVERTED_FROM_USER_KEY] == CONVERTED_FROM_USER_VALUE
    }
    @JvmStatic
    fun safeArtistConversion(obj: Any?): Artist? {
        try {
            return when (obj) {
                is Artist -> obj
                is User -> userToArtist(obj)
                is EchoMediaItem -> Artist(
                    id = obj.id,
                    name = obj.id, 
                    cover = obj.cover,
                    subtitle = obj.subtitle,
                    extras = obj.extras + mapOf(CONVERTED_FROM_USER_KEY to CONVERTED_FROM_USER_VALUE)
                )
                else -> null
            }
        } catch (e: Exception) {
            return obj?.let {
                Artist(
                    id = "fallback-${it.hashCode()}",
                    name = "Unknown Artist",
                    cover = null,
                    subtitle = null,
                    extras = mapOf("error" to "Conversion failed")
                )
            }
        }
    }
    @JvmStatic
    fun safeArtistListConversion(list: List<Any>): List<Artist> {
        return list.mapNotNull { item -> 
            when (item) {
                is Artist -> item
                is User -> userToArtist(item)
                else -> null
            }
        }
    }
    @JvmStatic
    fun ensureProperArtistsInAlbum(album: Album): Album {
        val artists = album.artists.mapNotNull { artist ->
            when (artist) {
                is Artist -> artist
                is User -> userToArtist(artist)
                
            }
        }
        
        return album.copy(artists = artists)
    }   
    @JvmStatic
    fun ensureProperArtistsInTrack(track: Track): Track {
        val artists = track.artists.mapNotNull { artist ->
            when (artist) {
                is Artist -> artist
                is User -> userToArtist(artist)
            }
        }
        val fixedAlbum = track.album?.let { ensureProperArtistsInAlbum(it) }       
        return track.copy(
            artists = artists,
            album = fixedAlbum
        )
    }
    @JvmStatic
    fun ensureProperAuthorsInPlaylist(playlist: Playlist): Playlist {
        val authors = playlist.authors.mapNotNull { author ->
            when (author) {
                is Artist -> author
                is User -> userToArtist(author)             
            }
        }
        
        return playlist.copy(authors = authors)
    }
    @JvmStatic
    fun ensureProperTypesInMediaItem(item: EchoMediaItem): EchoMediaItem {
        try {
            return when (item) {
                is Track -> ensureProperArtistsInTrack(item)
                is Album -> ensureProperArtistsInAlbum(item)
                is Playlist -> ensureProperAuthorsInPlaylist(item)
                is User -> userToArtist(item) 
                else -> item
            }
        } catch (e: Exception) {
            return item
        }
    }
    @JvmStatic
    fun fixSearchResultShelf(shelf: Shelf): Shelf {
        try {
            return when (shelf) {
                is Shelf.Item -> try {
                    Shelf.Item(ensureProperTypesInMediaItem(shelf.media))
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
                                ensureProperTypesInMediaItem(it)
                            } catch (e: Exception) {
                                null
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
                                ensureProperArtistsInTrack(it)
                            } catch (e: Exception) {
                                null
                            }
                        },
                        more = shelf.more
                    )
                } catch (e: Exception) {
                    shelf
                }
                
                else -> shelf 
            }
        } catch (e: Exception) {
            return shelf
        }
    }
}