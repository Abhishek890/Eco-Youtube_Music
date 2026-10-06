@file:Suppress("unused", "LocalVariableName")

package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.toAlbum
import dev.brahmkshatriya.echo.extension.toArtist
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.endpoint.SongRadioEndpoint
import dev.toastbits.ytmkt.model.external.Thumbnail
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmMediaItem
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import dev.toastbits.ytmkt.model.internal.BrowseEndpoint
import dev.toastbits.ytmkt.model.internal.MusicResponsiveListItemRenderer
import dev.toastbits.ytmkt.model.internal.MusicThumbnailRenderer
import dev.toastbits.ytmkt.model.internal.NavigationEndpoint
import dev.toastbits.ytmkt.model.internal.TextRun
import dev.toastbits.ytmkt.model.internal.TextRuns
import dev.toastbits.ytmkt.model.internal.WatchEndpoint
import dev.toastbits.ytmkt.uistrings.parseYoutubeDurationString
import dev.toastbits.ytmkt.radio.YoutubeiNextResponse as YtmNextResponse
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

open class EchoSongEndPoint(override val api: YoutubeiApi) : ApiEndpoint() {
    suspend fun loadSong(
        @Suppress("LocalVariableName") song_id: String
    ): Result<Track> = runCatching {
        val nextResponse: HttpResponse = api.client.request {
            endpointPath("next")
            addApiHeadersWithAuthenticated()
            postWithBody {
                put("enablePersistentPlaylistPanel", true)
                put("isAudioOnly", true)
                put("videoId", song_id)
            }
        }
        return@runCatching parseSongResponse(song_id, nextResponse, api).getOrThrow()
    }

    suspend fun loadSongRadio(
        songId: String,
        continuation: String?
    ): Result<SongRadioEndpoint.RadioData> = runCatching {
        val response = api.client.request {
            endpointPath("next")
            addApiHeadersWithAuthenticated()
            postWithBody {
                put("enablePersistentPlaylistPanel", true)
                put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
                put("playlistId", "RDAMVM$songId")
                put("isAudioOnly", true)
                putJsonObject("watchEndpointMusicSupportedConfigs") {
                    putJsonObject("watchEndpointMusicConfig") {
                        put("hasPersistentPlaylistPanel", true)
                        put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")
                    }
                }
                continuation?.let { put("continuation", it) }
            }
        }

        val panel = if (continuation == null) {
            val data: YoutubeiNextResponse = response.body()
            data.contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer
                .watchNextTabbedResultsRenderer.tabs.firstOrNull()?.tabRenderer?.content
                ?.musicQueueRenderer?.content?.playlistPanelRenderer
        } else {
            response.body<YoutubeiNextResponse.ContinuationResponse>()
                .continuationContents.playlistPanelContinuation
        }

        SongRadioEndpoint.RadioData(
            items = panel?.contents.orEmpty().map { it.getRenderer().toSongRadioItem(api) },
            continuation = panel?.continuations?.firstOrNull()?.data?.continuation,
            filters = null
        )
    }

    private suspend fun YoutubeiNextResponse.PlaylistPanelVideoRenderer.toSongRadioItem(
        api: YoutubeiApi
    ): YtmSong = YtmSong(
        id = YtmSong.cleanId(videoId),
        name = title.first_text,
        thumbnail_provider = ThumbnailProvider.fromThumbnails(thumbnail.thumbnails),
        artists = getArtists().getOrThrow(),
        album = getAlbum(),
        duration = parseYoutubeDurationString(lengthText.first_text, api.dataLocale)?.inWholeMilliseconds,
        is_explicit = badges?.any { it.isExplicit() } == true
    )

    private suspend fun YtmNextResponse.PlaylistPanelVideoRenderer.toSongRadioItem(
        api: YoutubeiApi
    ): YtmSong = YtmSong(
        id = YtmSong.cleanId(videoId),
        name = title.first_text,
        artists = getArtists(api).getOrThrow(),
        album = getAlbum().getOrThrow(),
        duration = getDuration(api.dataLocale)?.inWholeMilliseconds
    )

    private suspend fun parseSongResponse(
        songId: String,
        response: HttpResponse,
        api: YoutubeiApi
    ) = runCatching {
        val responseData: YoutubeiNextResponse = response.body()
        val tabs: List<YoutubeiNextResponse.Tab> =
            responseData
                .contents
                .singleColumnMusicWatchNextResultsRenderer
                .tabbedRenderer
                .watchNextTabbedResultsRenderer
                .tabs

        val lyricsBrowseId: String? =
            tabs.getOrNull(1)?.tabRenderer?.endpoint?.browseEndpoint?.browseId
        val relatedBrowseId: String? =
            tabs.getOrNull(2)?.tabRenderer?.endpoint?.browseEndpoint?.browseId

        val video: YoutubeiNextResponse.PlaylistPanelVideoRenderer =
            tabs[0].tabRenderer.content!!.musicQueueRenderer!!.content!!.playlistPanelRenderer.contents.first().playlistPanelVideoRenderer!!

        val title: String = video.title.first_text
        val isLiked =
            responseData.playerOverlays?.playerOverlayRenderer?.actions?.firstOrNull()?.likeButtonRenderer?.likeStatus == "LIKE"

        val artists: List<YtmArtist> = video.getArtists().getOrThrow() ?: emptyList()
        val album = video.getAlbum()
        val duration = parseYoutubeDurationString(video.lengthText.first_text, api.dataLocale)?.inWholeMilliseconds

        val cover = ThumbnailProvider.fromThumbnails(video.thumbnail.thumbnails)
            ?.getThumbnailUrl(ThumbnailProvider.Quality.HIGH)?.toImageHolder()
        return@runCatching Track(
            id = songId,
            title = title,
            cover = cover,
            artists = artists.map { it.toArtist(ThumbnailProvider.Quality.HIGH) },
            album = album?.toAlbum(false, ThumbnailProvider.Quality.HIGH),
            duration = duration,
            extras = mutableMapOf<String, String>().apply {
                relatedBrowseId?.let { put("relatedId", it) }
                lyricsBrowseId?.let { put("lyricsId", it) }
                put("isLiked", isLiked.toString())
            },

        )
    }
}

@Serializable
private data class PlayerData(
    val videoDetails: VideoDetails?,
) {
    @Serializable
    class VideoDetails(
        val title: String,
        val channelId: String,
    )
}


@Serializable
data class YoutubeiNextResponse(
    val contents: Contents,
    val playerOverlays: PlayerOverlays? = null
) {

    @Serializable
    data class PlayerOverlays(
        val playerOverlayRenderer: PlayerOverlayRenderer? = null
    )

    @Serializable
    data class PlayerOverlayRenderer(
        val actions: List<PlayerOverlayRendererAction>? = null,
        val browserMediaSession: BrowserMediaSession? = null
    )

    @Serializable
    data class PlayerOverlayRendererAction(
        val likeButtonRenderer: LikeButtonRenderer? = null
    )

    @Serializable
    data class LikeButtonRenderer(
        val target: Target? = null,
        val likeStatus: String? = null,
        val trackingParams: String? = null,
        val likesAllowed: Boolean? = null,
        val serviceEndpoints: List<ServiceEndpoint>? = null
    )

    @Serializable
    data class ServiceEndpoint(
        val clickTrackingParams: String? = null,
        val likeEndpoint: LikeEndpoint? = null
    )

    @Serializable
    data class LikeEndpoint(
        val status: String? = null,
        val target: Target? = null,
        val actions: List<LikeEndpointAction>? = null,
        val likeParams: String? = null,
        val dislikeParams: String? = null,
        val removeLikeParams: String? = null
    )

    @Serializable
    data class LikeEndpointAction(
        val clickTrackingParams: String? = null,
        val musicLibraryStatusUpdateCommand: MusicLibraryStatusUpdateCommand? = null
    )

    @Serializable
    data class MusicLibraryStatusUpdateCommand(
        val libraryStatus: String? = null,
        val addToLibraryFeedbackToken: String? = null
    )

    @Serializable
    data class Target(
        val videoId: String? = null
    )

    @Serializable
    data class BrowserMediaSession(
        val browserMediaSessionRenderer: BrowserMediaSessionRenderer? = null
    )

    @Serializable
    data class BrowserMediaSessionRenderer(
        val album: Album? = null,
        val thumbnailDetails: ThumbnailDetails? = null
    )

    @Serializable
    data class Album(
        val runs: List<Run>? = null
    )

    @Serializable
    data class Run(
        val text: String? = null
    )

    @Serializable
    data class ThumbnailDetails(
        val thumbnails: List<Thumbnail>? = null
    )

    @Serializable
    class Contents(val singleColumnMusicWatchNextResultsRenderer: SingleColumnMusicWatchNextResultsRenderer)

    @Serializable
    class SingleColumnMusicWatchNextResultsRenderer(val tabbedRenderer: TabbedRenderer)

    @Serializable
    class TabbedRenderer(val watchNextTabbedResultsRenderer: WatchNextTabbedResultsRenderer)

    @Serializable
    class WatchNextTabbedResultsRenderer(val tabs: List<Tab>)

    @Serializable
    class Tab(val tabRenderer: TabRenderer)

    @Serializable
    class TabRenderer(val content: Content?, val endpoint: TabRendererEndpoint?)

    @Serializable
    class TabRendererEndpoint(val browseEndpoint: BrowseEndpoint)

    @Serializable
    class Content(val musicQueueRenderer: MusicQueueRenderer? = null)

    @Serializable
    class MusicQueueRenderer(
        val content: MusicQueueRendererContent?,
        val subHeaderChipCloud: SubHeaderChipCloud?
    )

    @Serializable
    class SubHeaderChipCloud(val chipCloudRenderer: ChipCloudRenderer)

    @Serializable
    class ChipCloudRenderer(val chips: List<Chip>)

    @Serializable
    class Chip(private val chipCloudChipRenderer: ChipCloudChipRenderer) {
        fun getPlaylistId(): String? =
            chipCloudChipRenderer.navigationEndpoint.queueUpdateCommand.fetchContentsCommand.watchEndpoint.playlistId
    }

    @Serializable
    class ChipCloudChipRenderer(val navigationEndpoint: ChipNavigationEndpoint)

    @Serializable
    class ChipNavigationEndpoint(val queueUpdateCommand: QueueUpdateCommand)

    @Serializable
    class QueueUpdateCommand(val fetchContentsCommand: FetchContentsCommand)

    @Serializable
    class FetchContentsCommand(val watchEndpoint: WatchEndpoint)

    @Serializable
    class MusicQueueRendererContent(val playlistPanelRenderer: PlaylistPanelRenderer)

    @Serializable
    class PlaylistPanelRenderer(
        val contents: List<ResponseRadioItem>,
        val continuations: List<Continuation>? = null
    )

    @Serializable
    data class ResponseRadioItem(
        val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer?,
        val playlistPanelVideoWrapperRenderer: PlaylistPanelVideoWrapperRenderer?
    ) {
        fun getRenderer(): PlaylistPanelVideoRenderer {
            if (playlistPanelVideoRenderer != null) {
                return playlistPanelVideoRenderer
            }

            if (playlistPanelVideoWrapperRenderer == null) {
                throw NotImplementedError("Unimplemented renderer object in ResponseRadioItem")
            }

            return playlistPanelVideoWrapperRenderer.primaryRenderer.getRenderer()
        }
    }

    @Serializable
    class PlaylistPanelVideoWrapperRenderer(
        val primaryRenderer: ResponseRadioItem
    )

    @Serializable
    class PlaylistPanelVideoRenderer(
        val videoId: String,
        val title: TextRuns,
        private val longBylineText: TextRuns,
        val lengthText: TextRuns,
        val menu: Menu,
        val thumbnail: MusicThumbnailRenderer.RendererThumbnail,
        val badges: List<MusicResponsiveListItemRenderer.Badge>?
    ) {
        fun getArtists(): Result<List<YtmArtist>?> = runCatching {
            // Gets artists IDs direct
            val artists: List<YtmArtist> = (longBylineText.runs.orEmpty() + title.runs.orEmpty())
                .mapNotNull { run ->
                    val browse_id: String = run.navigationEndpoint?.browseEndpoint?.browseId
                        ?: return@mapNotNull null

                    val page_type = run.browse_endpoint_type?.let { type ->
                        YtmMediaItem.Type.fromBrowseEndpointType(type)
                    }
                    if (page_type != YtmMediaItem.Type.ARTIST) {
                        return@mapNotNull null
                    }

                    return@mapNotNull YtmArtist(
                        id = browse_id,
                        name = run.text
                    )
                }

            if (artists.isNotEmpty()) {
                return@runCatching artists
            }

            val menu_artist: String? =
                menu.menuRenderer.getArtist()?.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.browseId
            if (menu_artist != null) {
                val artist_title: TextRun? =
                    longBylineText.runs?.firstOrNull { it.navigationEndpoint == null }
                if (artist_title != null) {
                    return@runCatching listOf(
                        YtmArtist(
                            id = menu_artist,
                            name = artist_title.text
                        )
                    )
                }
            }

            return@runCatching null
        }

        fun getAlbum(): YtmPlaylist? {
            for (run in longBylineText.runs.orEmpty()) {
                if (run.navigationEndpoint?.browseEndpoint?.getPageType() != "MUSIC_PAGE_TYPE_ALBUM") {
                    continue
                }

                val playlist_id: String =
                    run.navigationEndpoint?.browseEndpoint?.browseId ?: continue
                return YtmPlaylist(
                    id = playlist_id,
                    name = run.text,
                    year = longBylineText.runs?.find { it.text.length == 4 }?.text?.toIntOrNull(),
                )
            }

            return null
        }
    }

    @Serializable
    data class Continuation(val nextContinuationData: ContinuationData?, val nextRadioContinuationData: ContinuationData?) {
        val data: ContinuationData? get() = nextContinuationData ?: nextRadioContinuationData
    }

    @Serializable
    data class ContinuationData(val continuation: String)

    @Serializable
    data class ContinuationResponse(val continuationContents: ContinuationContents)

    @Serializable
    data class ContinuationContents(val playlistPanelContinuation: PlaylistPanelRenderer)

    @Serializable
    data class Menu(val menuRenderer: MenuRenderer)

    @Serializable
    data class MenuRenderer(val items: List<MenuItem>) {
        fun getArtist(): MenuItem? =
            items.firstOrNull {
                it.menuNavigationItemRenderer?.icon?.iconType == "ARTIST"
            }
    }

    @Serializable
    data class MenuItem(val menuNavigationItemRenderer: MenuNavigationItemRenderer?)

    @Serializable
    data class MenuNavigationItemRenderer(
        val icon: MenuIcon,
        val navigationEndpoint: NavigationEndpoint
    )

    @Serializable
    data class MenuIcon(val iconType: String)
}

