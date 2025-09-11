package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.external.ThumbnailProvider

class YoutubeConverter {
    
    fun convertSongToTrack(song: YtmSong): Track {
        val streamables = listOf(
            Streamable.server(
                id = "youtube_music_${song.id}", 
                quality = 128, 
                title = "YouTube Music",
                extras = mapOf("videoId" to song.id)
            )
        )        
        return Track(
            id = song.id,
            title = song.name ?: "Unknown Title",
            subtitle = song.artists?.joinToString(", ") { it.name ?: "Unknown Artist" } ?: "",
            cover = song.thumbnail_provider?.let { provider ->
                var url: String? = null
                for (quality in arrayOf(ThumbnailProvider.Quality.HIGH, ThumbnailProvider.Quality.LOW)) {
                    url = provider.getThumbnailUrl(quality)
                    if (url != null) break
                }
                url?.toImageHolder()
            },
            duration = song.duration ?: 0L,
            isExplicit = song.is_explicit,
            streamables = streamables,
            album = song.album?.let { playlist ->
                Album(
                    id = playlist.id,
                    title = playlist.name ?: "Unknown Album",
                    cover = playlist.thumbnail_provider?.let { provider ->
                        var url: String? = null
                        for (quality in arrayOf(ThumbnailProvider.Quality.HIGH, ThumbnailProvider.Quality.LOW)) {
                            url = provider.getThumbnailUrl(quality)
                            if (url != null) break
                        }
                        url?.toImageHolder()
                    }
                )
            },
            artists = song.artists?.map { artist ->
                Artist(
                    id = artist.id,
                    name = artist.name ?: "Unknown Artist",
                    cover = artist.thumbnail_provider?.let { provider ->
                        var url: String? = null
                        for (quality in arrayOf(ThumbnailProvider.Quality.HIGH, ThumbnailProvider.Quality.LOW)) {
                            url = provider.getThumbnailUrl(quality)
                            if (url != null) break
                        }
                        url?.toImageHolder()
                    }
                )
            } ?: emptyList(),
            extras = mapOf(
                "videoId" to song.id,
                "type" to "youtube_song",
                "conversionTimestamp" to System.currentTimeMillis().toString() 
            )
        )
    }
    fun convertArtistToEchoArtist(artist: YtmArtist): Artist {
        return Artist(
            id = artist.id,
            name = artist.name ?: "Unknown Artist",
            cover = artist.thumbnail_provider?.let { provider ->
                var url: String? = null
                for (quality in arrayOf(ThumbnailProvider.Quality.HIGH, ThumbnailProvider.Quality.LOW)) {
                    url = provider.getThumbnailUrl(quality)
                    if (url != null) break
                }
                url?.toImageHolder()
            },
            subtitle = "",
            isFollowable = true, 
            extras = mapOf(
                "type" to "youtube_artist",
                "conversionTimestamp" to System.currentTimeMillis().toString() 
            )
        )
    }

    fun convertPlaylistToEchoAlbum(playlist: YtmPlaylist): Album {
        return Album(
            id = playlist.id,
            title = playlist.name ?: "Unknown Album",
            cover = playlist.thumbnail_provider?.let { provider ->
                var url: String? = null
                for (quality in arrayOf(ThumbnailProvider.Quality.HIGH, ThumbnailProvider.Quality.LOW)) {
                    url = provider.getThumbnailUrl(quality)
                    if (url != null) break
                }
                url?.toImageHolder()
            },
            subtitle = playlist.artists?.joinToString(", ") { it.name ?: "Unknown Artist" } ?: "",
            extras = mapOf(
                "type" to "youtube_album",
                "conversionTimestamp" to System.currentTimeMillis().toString() 
            )
        )
    }
    fun convertPlaylistToEchoPlaylist(playlist: YtmPlaylist): Playlist {
        return Playlist(
            id = playlist.id,
            title = playlist.name ?: "Unknown Playlist",
            cover = playlist.thumbnail_provider?.let { provider ->
                var url: String? = null
                for (quality in arrayOf(ThumbnailProvider.Quality.HIGH, ThumbnailProvider.Quality.LOW)) {
                    url = provider.getThumbnailUrl(quality)
                    if (url != null) break
                }
                url?.toImageHolder()
            },
            subtitle = "",
            isEditable = false,
            extras = mapOf(
                "type" to "youtube_playlist",
                "conversionTimestamp" to System.currentTimeMillis().toString() 
            )
        )
    }
}