package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong

class YtmSongConverter {
    fun toTrack(song: YtmSong): Track {
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
                getThumbnailUrl(provider)?.toImageHolder()
            },
            duration = song.duration,
            isExplicit = song.is_explicit,
            streamables = streamables,
            album = song.album?.let { playlist ->
                Album(
                    id = playlist.id,
                    title = playlist.name ?: "Unknown Album",
                    cover = playlist.thumbnail_provider?.let { provider ->
                        getThumbnailUrl(provider)?.toImageHolder()
                    }
                )
            },
            artists = song.artists?.map { artist ->
                Artist(
                    id = artist.id,
                    name = artist.name ?: "Unknown Artist",
                    cover = artist.thumbnail_provider?.let { provider ->
                        getThumbnailUrl(provider)?.toImageHolder()
                    }
                )
            } ?: emptyList(),
            extras = mapOf("videoId" to song.id)
        )
    }

    private fun getThumbnailUrl(provider: ThumbnailProvider): String? {
        return provider.getThumbnailUrl(ThumbnailProvider.Quality.HIGH)
    }
}
