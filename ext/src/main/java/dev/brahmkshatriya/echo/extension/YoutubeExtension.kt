package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.clients.TrackerMarkClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.Track.Type
import dev.brahmkshatriya.echo.common.models.Track.Playable
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.endpoints.EchoArtistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoArtistMoreEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoEditPlaylistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoLibraryEndPoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoLyricsEndPoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoPlaylistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSearchEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSearchSuggestionsEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongEndPoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongFeedEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongRelatedEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVideoEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.GoogleAccountResponse
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState
import dev.toastbits.ytmkt.model.external.PlaylistEditor
import dev.toastbits.ytmkt.model.external.SongLikedStatus
import dev.toastbits.ytmkt.model.external.ThumbnailProvider.Quality.HIGH
import dev.toastbits.ytmkt.model.external.ThumbnailProvider.Quality.LOW
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.YoutubeVideoFormat
import dev.brahmkshatriya.echo.extension.NewPipeExtractorKmpVideoFormatsEndpoint
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.http.headers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import java.security.MessageDigest


private fun createShelfPagedDataFromMediaItems(mediaItems: PagedData<EchoMediaItem>): PagedData<Shelf> {
    return PagedData.Continuous { continuation ->
        val page = mediaItems.loadPage(continuation)
        val shelves = page.data.map { item -> Shelf.Item(item) }
        Page(shelves, page.continuation)
    }
}
class YoutubeExtension : ExtensionClient, HomeFeedClient, TrackClient, SearchFeedClient,
    RadioClient, AlbumClient, ArtistClient, PlaylistClient, LoginClient.WebView,
    TrackerClient, TrackerMarkClient, LibraryFeedClient, ShareClient, LyricsClient, FollowClient,
    LikeClient, PlaylistEditClient, LyricsSearchClient, QuickSearchClient {

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingSwitch(
            "High Thumbnail Quality",
            "high_quality",
            "Use high quality thumbnails, will cause more data usage.",
            false
        ),
        SettingSwitch(
            "Prefer Videos [Not Working For Now]",
            "prefer_videos",
            "Prefer videos over audio when available",
            false
        )
    )

    private lateinit var settings: Settings
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    val api = YoutubeiApi(
        data_language = ENGLISH
    )

    private suspend fun ensureVisitorId() {
        if (api.visitor_id == null) {
            api.visitor_id = visitorEndpoint.getVisitorId()
        }
    }
    private val thumbnailQuality
        get() = if (settings.getBoolean("high_quality") == true) HIGH else LOW

    private val preferVideos
        get() = settings.getBoolean("prefer_videos") == false

    private val language = ENGLISH
    private val visitorEndpoint = EchoVisitorEndpoint(api)
    private val songFeedEndPoint = EchoSongFeedEndpoint(api)
    private val artistEndPoint = EchoArtistEndpoint(api)
    private val artistMoreEndpoint = EchoArtistMoreEndpoint(api)
    private val libraryEndPoint = EchoLibraryEndPoint(api)
    private val songEndPoint = EchoSongEndPoint(api)
    private val songRelatedEndpoint = EchoSongRelatedEndpoint(api)
    private val videoEndpoint = EchoVideoEndpoint(api)
    private val playlistEndPoint = EchoPlaylistEndpoint(api)
    private val lyricsEndPoint = EchoLyricsEndPoint(api)
    private val searchSuggestionsEndpoint = EchoSearchSuggestionsEndpoint(api)
    private val searchEndpoint = EchoSearchEndpoint(api)
    private val editorEndpoint = EchoEditPlaylistEndpoint(api)
    private val newPipeExtractor by lazy { NewPipeExtractorKmpVideoFormatsEndpoint(api) }
    companion object {
        const val ENGLISH = "en-GB"
        const val SINGLES = "Singles"
        const val SONGS = "songs"
    }
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf<Tab>() 
        return Feed(tabs) { tab ->
            val pagedData = PagedData.Continuous { continuation ->
                val result = songFeedEndPoint.getSongFeed(
                    params = tab?.id, continuation = continuation
                ).getOrThrow()
                val data = result.layouts.map { itemLayout ->
                    itemLayout.toShelf(api, SINGLES, thumbnailQuality)
                }
                Page(data, result.ctoken)
            }
            Feed.Data(pagedData)
        }
    }
    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean
    ): Streamable.Media {
        return when (streamable.type) {
            Streamable.MediaType.Server -> {
                val videoId = streamable.extras["videoId"] 
                    ?: throw Exception("No video ID found in streamable extras. This track may not be playable.")
                
                println("Loading streamable media for video ID: $videoId")
                
                // Try NewPipe first
                try {
                    val newPipeExtractor = NewPipeExtractorKmpVideoFormatsEndpoint(api)
                    
                    val formatsResult = newPipeExtractor.getVideoFormats(videoId, include_non_default = true, filter = null)
                    
                    if (formatsResult.isSuccess) {
                        val formats = formatsResult.getOrThrow()
                        
                        val validFormats = formats.filter { it.url != null }
                        
                        if (validFormats.isNotEmpty()) {
                            val audioFormats = validFormats.filter { 
                                it.mimeType?.startsWith("audio/") == true
                            }
                            
                            if (audioFormats.isNotEmpty()) {
                                val audioSources = audioFormats.map { format ->
                                    val bitrateKbps = if (format.bitrate != null) format.bitrate / 1000 else 0
                                    Streamable.Source.Http(
                                        request = NetworkRequest(url = format.url!!),
                                        type = Streamable.SourceType.Progressive,
                                        quality = bitrateKbps.toInt(),
                                        title = "Audio - ${format.mimeType}${if (bitrateKbps > 0) " - ${bitrateKbps}kbps" else ""}"
                                    )
                                }
                                
                                println("Successfully loaded streamable media using NewPipe extractor with ${audioSources.size} quality options")
                                return Streamable.Media.Server(audioSources, false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("NewPipe extractor failed: ${e.message}")
                    e.printStackTrace()
                }
                
                // Fallback to current YouTube Music implementation
                println("Falling back to YouTube Music API for video ID: $videoId")
                try {
                    ensureVisitorId()
                    val (video, _) = videoEndpoint.getVideo(true, videoId)
                    
                    val audioSources = video.streamingData.adaptiveFormats
                        .filter { it.mimeType?.lowercase()?.contains("audio/") == true && it.url != null }
                        .map { format ->
                            val bitrateKbps = if (format.bitrate != null) format.bitrate / 1000 else 128
                            Streamable.Source.Http(
                                request = NetworkRequest(url = format.url!!),
                                type = Streamable.SourceType.Progressive,
                                quality = bitrateKbps.toInt(),
                                title = "Audio - ${format.mimeType ?: "audio/unknown"}${if (format.bitrate != null) " - ${bitrateKbps}kbps" else ""}"
                            )
                        }
                    
                    if (audioSources.isNotEmpty()) {
                        println("Successfully loaded streamable media using YouTube Music API with ${audioSources.size} quality options")
                        return Streamable.Media.Server(audioSources, false)
                    } else {
                        throw Exception("No audio sources found in YouTube Music API")
                    }
                } catch (e: Exception) {
                    println("YouTube Music API also failed: ${e.message}")
                    e.printStackTrace()
                    throw Exception("Failed to load streamable media using both NewPipe and YouTube Music API: ${e.message}")
                }
            }
            Streamable.MediaType.Background -> {
                throw Exception("Background streamables not supported")
            }
            Streamable.MediaType.Subtitle -> {
                throw Exception("Subtitles not supported")
            }
        }
    }
   override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = coroutineScope {
        ensureVisitorId()
        
        val deferred = async { songEndPoint.loadSong(track.id).getOrThrow() }
        val (video, _) = videoEndpoint.getVideo(true, track.id)

        val hasAudio = video.streamingData.adaptiveFormats.any { 
            it.mimeType.contains("audio") && it.url != null 
        }
        
        val newTrack = deferred.await()
        newTrack.copy(
            description = video.videoDetails.shortDescription,
            artists = newTrack.artists.ifEmpty {
                video.videoDetails.run { listOf(Artist(channelId, author)) }
            },
            streamables = if (hasAudio) {
                listOf(Streamable.server("AUDIO_MP3", 0, "Audio", mapOf("videoId" to track.id)))
            } else {
                emptyList()
            },
            plays = video.videoDetails.viewCount?.toLongOrNull(),
            extras = newTrack.extras.toMutableMap().apply {
                put("videoId", track.id)
                put("channelId", video.videoDetails.channelId ?: "")
                put("author", video.videoDetails.author ?: "")
                put("viewCount", video.videoDetails.viewCount ?: "0")
                put("lengthSeconds", video.videoDetails.lengthSeconds ?: "0")
            }
        )
    }

    private suspend fun loadRelated(track: Track): List<Shelf> {
        val relatedId = track.extras["relatedId"]
        return if (relatedId != null) {
            try {
                songFeedEndPoint.getSongFeed(browseId = relatedId).getOrThrow().layouts.map {
                    it.toShelf(api, SINGLES, thumbnailQuality)
                }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        val shelves = loadRelated(track)
        return if (shelves.isNotEmpty()) {
            Feed(emptyList()) { _ -> PagedData.Single { shelves }.toFeedData() }
        } else {
            null
        }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {
        searchSuggestionsEndpoint.delete(item as QuickSearchItem.Query)
    }

    override suspend fun quickSearch(query: String) = query.takeIf { it.isNotBlank() }?.run {
        try {
            api.SearchSuggestions.getSearchSuggestions(this).getOrThrow()
                .map { QuickSearchItem.Query(it.text, it.is_from_history) }
        } catch (e: NullPointerException) {
            null
        } catch (e: ConnectTimeoutException) {
            null
        }
    } ?: listOf()


    private var oldSearch: Pair<String, List<Shelf>>? = null
    
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val tabs = if (query.isNotBlank()) {
            val search = api.Search.search(query, null).getOrThrow()
            oldSearch = query to search.categories.map { (itemLayout, _) ->
                try {
                    SearchResultsFixer.fixSearchResultShelf(itemLayout.toShelf(api, SINGLES, thumbnailQuality))
                } catch (e: Exception) {
                    itemLayout.toShelf(api, SINGLES, thumbnailQuality)
                }
            }
            val searchTabs = search.categories.mapNotNull { (item, filter) ->
                filter?.let {
                    Tab(
                        id = it.params,
                        title = item.title?.getString(language) ?: "???",
                        isSort = false,
                        extras = mapOf(
                            "browseId" to it.params,
                            "category" to (item.title?.getString(language) ?: "unknown"),
                            "filterType" to it.type.name,
                            "isSearchTab" to "true"
                        )
                    )
                }
            }
            listOf(
                Tab(
                    id = "All", 
                    title = "All",
                    isSort = false,
                    extras = mapOf(
                        "browseId" to "All",
                        "category" to "All",
                        "isSearchTab" to "true",
                        "isDefaultTab" to "true"
                    )
                )
            ) + searchTabs
        } else {
            val result = songFeedEndPoint.getSongFeed().getOrThrow()
            result.filter_chips?.map {
                Tab(
                    id = it.params,
                    title = it.text.getString(language),
                    isSort = false,
                    extras = mapOf(
                        "browseId" to it.params,
                        "category" to it.text.getString(language),
                        "isFilterChip" to "true",
                        "isHomeFeedTab" to "true"
                    )
                )
            } ?: emptyList()
        }

        return Feed(tabs) { tab ->
            val pagedData = if (query.isNotBlank()) {
                val params = if (tab == null || tab.id == "All") {
                    null
                } else {
                    tab.id
                }
                
                PagedData.Single {
                    try {
                        if (params == null && oldSearch?.first == query) {
                            return@Single oldSearch?.second ?: emptyList()
                        }
                        val search = try {
                            api.Search.search(query, params).getOrThrow()
                        } catch (e: Exception) {
                            return@Single emptyList()
                        }
                        
                        try {
                            search.categories.map { (itemLayout, _) ->
                                try {
                                    itemLayout.items.mapNotNull { item ->
                                        try {
                                            val shelf = item.toEchoMediaItem(false, thumbnailQuality)?.toShelf()
                                            if (shelf != null) {
                                                try {
                                                    SearchResultsFixer.fixSearchResultShelf(shelf)
                                                } catch (e: Exception) {
                                                    shelf
                                                }
                                            } else null
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            }.flatten()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            } else if (tab != null) {
                PagedData.Continuous {
                    try {
                        val params = tab.id
                        val continuation = it
                        val result = try {
                            songFeedEndPoint.getSongFeed(
                                params = params, continuation = continuation
                            ).getOrThrow()
                        } catch (e: Exception) {
                            return@Continuous Page(emptyList(), null)
                        }
                        
                        val data = try {
                            result.layouts.mapNotNull { itemLayout ->
                                try {
                                    val shelf = try {
                                        itemLayout.toShelf(api, SINGLES, thumbnailQuality)
                                    } catch (e: Exception) {
                                        return@mapNotNull null
                                    }
                                    
                                    try {
                                        SearchResultsFixer.fixSearchResultShelf(shelf)
                                    } catch (e: Exception) {
                                        shelf
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        } catch (e: Exception) {
                            emptyList()
                        }
                        
                        Page(data, result.ctoken)
                    } catch (e: Exception) {
                        Page(emptyList(), null)
                    }
                }
            } else {
                PagedData.Single { listOf() }
            }
            
            Feed.Data(pagedData as PagedData<Shelf>)
        }
    }

    override suspend fun loadTracks(radio: Radio): Feed<Track> =
        PagedData.Single { json.decodeFromString<List<Track>>(radio.extras["tracks"]!!) }.toFeed()

    suspend fun radio(album: Album): Radio {
        val track = api.LoadPlaylist.loadPlaylist(album.id).getOrThrow().items
            ?.lastOrNull()?.toTrack(HIGH)
            ?: throw Exception("No tracks found")
        return radio(track, null)
    }

    suspend fun radio(artist: Artist): Radio {
        val id = "radio_${artist.id}"
        val result = api.ArtistRadio.getArtistRadio(artist.id, null).getOrThrow()
        val tracks = result.items.map { song -> song.toTrack(thumbnailQuality) }
        return Radio(
            id = id,
            title = "${artist.name} Radio",
            extras = mutableMapOf<String, String>().apply {
                put("tracks", json.encodeToString(tracks))
            }
        )
    }


    suspend fun radio(track: Track, context: EchoMediaItem? = null): Radio {
        val id = "radio_${track.id}"
        val cont = context?.extras?.get("cont")
        val result = api.SongRadio.getSongRadio(track.id, cont).getOrThrow()
        val tracks = result.items.map { song -> song.toTrack(thumbnailQuality) }
        return Radio(
            id = id,
            title = "${track.title} Radio",
            extras = mutableMapOf<String, String>().apply {
                put("tracks", json.encodeToString(tracks))
                result.continuation?.let { put("cont", it) }
            }
        )
    }

    suspend fun radio(user: User): Radio {
        val artist = ModelTypeHelper.userToArtist(user)
        return radio(artist)
    }

    suspend fun radio(playlist: Playlist): Radio {
        val track = loadTracks(playlist)?.loadAll()?.lastOrNull()
            ?: throw Exception("No tracks found")
        return radio(track, null)
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val tracks = loadTracks(album)?.loadAll() ?: emptyList()
        val lastTrack = tracks.lastOrNull() ?: return null
        val loadedTrack = loadTrack(lastTrack, false)
        val shelves = loadRelated(loadedTrack)
        return Feed(emptyList()) { _ -> PagedData.Single { shelves }.toFeedData() }
    }


    private val trackMap = mutableMapOf<String, PagedData<Track>>()
    override suspend fun loadAlbum(album: Album): Album {
        val (ytmPlaylist, _, data) = playlistEndPoint.loadFromPlaylist(
            album.id, null, thumbnailQuality
        )
        trackMap[ytmPlaylist.id] = data
        return ytmPlaylist.toAlbum(false, HIGH)
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? = trackMap[album.id]?.toFeed()

    private suspend fun getArtistMediaItems(artist: Artist): List<Shelf> {
        val result =
            loadedArtist.takeIf { artist.id == it?.id } ?: api.LoadArtist.loadArtist(artist.id)
                .getOrThrow()

        return result.layouts?.map {
            val title = it.title?.getString(ENGLISH)
            val single = title == SINGLES
            Shelf.Lists.Items(
                id = it.title?.getString(language)?.hashCode()?.toString() ?: "Unknown",
                title = it.title?.getString(language) ?: "Unknown",
                subtitle = it.subtitle?.getString(language),
                list = it.items?.mapNotNull { item ->
                    item.toEchoMediaItem(single, thumbnailQuality)
                } ?: emptyList(),
                more = it.view_more?.getBrowseParamsData()?.let { param ->
                    PagedData.Single {
                        val data = artistMoreEndpoint.load(param)
                        data.map { row ->
                            row.items.mapNotNull { item ->
                                item.toEchoMediaItem(single, thumbnailQuality)
                            }
                        }.flatten()
                    }.let { mediaItems ->
                        Feed(listOf()) { _ -> 
                            Feed.Data(createShelfPagedDataFromMediaItems(mediaItems))
                        }
                    }
                })
        } ?: emptyList()
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val shelves = getArtistMediaItems(artist)
        return Feed(emptyList()) { _ -> PagedData.Single { shelves }.toFeedData() }
    }

    private var loadedArtist: YtmArtist? = null
    override suspend fun loadArtist(artist: Artist): Artist {
        val result = artistEndPoint.loadArtist(artist.id)
        loadedArtist = result
        return result.toArtist(HIGH)
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        val cont = playlist.extras["relatedId"] ?: return null
        
        val shelves = try {
            if (cont.startsWith("id://")) {
                val id = cont.substring(5)
                val track = Track(id, "")
                val loadedTrack = loadTrack(track, false)
                val feed = loadFeed(loadedTrack)
                coroutineScope { 
                    if (feed != null) {
                        val items = feed.loadAll()
                        items.filterIsInstance<Shelf.Category>()
                    } else emptyList()
                }
            } else {
                songRelatedEndpoint.loadFromPlaylist(cont).getOrNull()?.map { 
                    it.toShelf(api, language, thumbnailQuality) 
                } ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        
        return if (shelves.isNotEmpty()) {
            Feed(emptyList()) { _ -> Feed.Data(PagedData.Single { shelves }) }
        } else {
            null
        }
    }


    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val (ytmPlaylist, related, data) = playlistEndPoint.loadFromPlaylist(
            playlist.id,
            null,
            thumbnailQuality
        )
        trackMap[ytmPlaylist.id] = data
        return ytmPlaylist.toPlaylist(HIGH, related)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = trackMap[playlist.id]?.toFeed() ?: listOf<Track>().toFeed()


    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val initialUrl =
            "https://accounts.google.com/v3/signin/identifier?dsh=S1527412391%3A1678373417598386&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den-GB%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F%253Fcbrd%253D1%26feature%3D__FEATURE__&hl=en-GB&ifkv=AWnogHfK4OXI8X1zVlVjzzjybvICXS4ojnbvzpE4Gn_Pfddw7fs3ERdfk-q3tRimJuoXjfofz6wuzg&ltmpl=music&passive=true&service=youtube&uilel=3&flowName=GlifWebSignIn&flowEntry=ServiceLogin".toGetRequest()
        override val stopUrlRegex = "https://music\\.youtube\\.com/.*".toRegex()
        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User> {
            if (!cookie.contains("SAPISID")) throw Exception("Login Failed, could not load SAPISID")
            val auth = run {
                val currentTime = System.currentTimeMillis() / 1000
                val id = cookie.split("SAPISID=")[1].split(";")[0]
                val str = "$currentTime $id https://music.youtube.com"
                val idHash = MessageDigest.getInstance("SHA-1").digest(str.toByteArray())
                    .joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
                "SAPISIDHASH ${currentTime}_${idHash}"
            }
            val headersMap = mutableMapOf("cookie" to cookie, "authorization" to auth)
            val headers = headers { headersMap.forEach { (t, u) -> append(t, u) } }
            return api.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
                headers {
                    append("referer", "https://music.youtube.com/")
                    appendAll(headers)
                }
            }.getUsers(cookie, auth)
        }
    }

    override fun setLoginUser(user: User?) {
        if (user == null) {
            api.user_auth_state = null
        } else {
            val cookie = user.extras["cookie"] ?: throw Exception("No cookie")
            val auth = user.extras["auth"] ?: throw Exception("No auth")

            val headers = headers {
                append("cookie", cookie)
                append("authorization", auth)
            }
            val authenticationState =
                YoutubeiAuthenticationState(api, headers, user.id.ifEmpty { null })
            api.user_auth_state = authenticationState
        }
        api.visitor_id = runCatching { kotlinx.coroutines.runBlocking { visitorEndpoint.getVisitorId() } }.getOrNull()
    }

    override suspend fun getCurrentUser(): User? {
        val headers = api.user_auth_state?.headers ?: return null
        val userResponse = api.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
            headers {
                append("referer", "https://music.youtube.com/")
                appendAll(headers)
            }
        }.getUsers("", "").firstOrNull() ?: return null
        
        return userResponse.copy(
            subtitle = userResponse.extras["email"] ?: "YouTube Music User",
            extras = userResponse.extras.toMutableMap().apply {
                put("isLoggedIn", "true")
                put("userService", "youtube_music")
                put("accountType", "google")
                put("lastUpdated", System.currentTimeMillis().toString())
                putAll(userResponse.extras)
            }
        )
    }


    override suspend fun getMarkAsPlayedDuration(details: TrackDetails): Long? = 30000L

    override suspend fun onMarkAsPlayed(details: TrackDetails) {
        api.user_auth_state?.MarkSongAsWatched?.markSongAsWatched(details.track.id)?.getOrThrow()
    }

        private suspend fun <T> withUserAuth(
        block: suspend (auth: YoutubeiAuthenticationState) -> T
    ): T {
        val state = api.user_auth_state
            ?: throw ClientException.LoginRequired()
        return runCatching { block(state) }.getOrElse {
            if (it is ClientRequestException) {
                if (it.response.status.value == 401) {
                    val user = state.own_channel_id
                        ?: throw ClientException.LoginRequired()
                    throw ClientException.Unauthorized(user)
                }
            }
            throw it
        }
    }

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
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
//            Tab("FEmusic_listening_review", "Review"),
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
            val pagedData = PagedData.Continuous<Shelf> { cont ->
                val browseId = tab?.id ?: "FEmusic_library_landing"               
                if (browseId == "FEmusic_library_corpus_track_artists") {
                    try {
                        val artists = withUserAuth { auth ->
                            auth.LikedArtists.getLikedArtists().getOrThrow()
                        }
                        val shelves = artists.mapNotNull { artist ->
                            artist.toEchoMediaItem(false, thumbnailQuality)?.toShelf()
                        }
                        Page(shelves, null)
                    } catch (e: Exception) {
                        println("Failed to load liked artists: ${e.message}")
                        Page(emptyList(), null)
                    }
                } else {
                    val (result, ctoken) = withUserAuth { libraryEndPoint.loadLibraryFeed(browseId, cont) }
                    val data = result.mapNotNull { playlist ->
                        playlist.toEchoMediaItem(false, thumbnailQuality)?.toShelf()
                    }
                    Page(data, ctoken)
                }
            }
            Feed.Data(pagedData)
        }
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        val playlistId = withUserAuth {
            it.CreateAccountPlaylist
                .createAccountPlaylist(title, description ?: "")
                .getOrThrow()
        }
        return loadPlaylist(Playlist(playlistId, "", true))
    }

    override suspend fun deletePlaylist(playlist: Playlist) = withUserAuth {
        it.DeleteAccountPlaylist.deleteAccountPlaylist(playlist.id).getOrThrow()
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        val track = item as? Track ?: throw Exception("Only tracks can be liked")
        likeTrack(track, shouldLike)
    }

    private suspend fun likeTrack(track: Track, isLiked: Boolean) {
        val likeStatus = if (isLiked) SongLikedStatus.LIKED else SongLikedStatus.NEUTRAL
        withUserAuth { it.SetSongLiked.setSongLiked(track.id, likeStatus).getOrThrow() }
    }

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> =
        withUserAuth { auth ->
            auth.AccountPlaylists.getAccountPlaylists().getOrThrow().mapNotNull {
                if (it.id != "VLSE") it.toPlaylist(thumbnailQuality) to false
                else null
            }

        }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist, title: String, description: String?
    ) {
        withUserAuth { auth ->
            val editor = auth.AccountPlaylistEditor.getEditor(playlist.id, listOf(), listOf())
            editor.performAndCommitActions(
                listOfNotNull(
                    PlaylistEditor.Action.SetTitle(title),
                    description?.let { PlaylistEditor.Action.SetDescription(it) }
                )
            )
        }
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist, tracks: List<Track>, indexes: List<Int>
    ) {
        val actions = indexes.map {
            val track = tracks[it]
            EchoEditPlaylistEndpoint.Action.Remove(track.id, track.extras["setId"]!!)
        }
        editorEndpoint.editPlaylist(playlist.id, actions)
    }

    override suspend fun addTracksToPlaylist(
        playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>
    ) {
        val actions = new.map { EchoEditPlaylistEndpoint.Action.Add(it.id) }
        val setIds = editorEndpoint.editPlaylist(playlist.id, actions).playlistEditResults!!.map {
            it.playlistEditVideoAddedResultData.setVideoId
        }
        val addBeforeTrack = tracks.getOrNull(index)?.extras?.get("setId") ?: return
        val moveActions = setIds.map { setId ->
            EchoEditPlaylistEndpoint.Action.Move(setId, addBeforeTrack)
        }
        editorEndpoint.editPlaylist(playlist.id, moveActions)
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int
    ) {
        val setId = tracks[fromIndex].extras["setId"]!!
        val before = if (fromIndex - toIndex > 0) 0 else 1
        val addBeforeTrack = tracks.getOrNull(toIndex + before)?.extras?.get("setId")
            ?: return
        editorEndpoint.editPlaylist(
            playlist.id, listOf(
                EchoEditPlaylistEndpoint.Action.Move(setId, addBeforeTrack)
            )
        )
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val pagedData = PagedData.Single {
            val lyricsId = track.extras["lyricsId"] ?: return@Single listOf()
            val data = lyricsEndPoint.getLyrics(lyricsId) ?: return@Single listOf()
            val lyrics = data.first.map {
                it.cueRange.run {
                    Lyrics.Item(
                        it.lyricLine,
                        startTimeMilliseconds.toLong(),
                        endTimeMilliseconds.toLong()
                    )
                }
            }
            listOf(Lyrics(lyricsId, track.title, data.second, Lyrics.Timed(lyrics)))
        }
        return pagedData.toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics) = lyrics

    override suspend fun onShare(item: EchoMediaItem) = when (item) {
        is Album -> "https://music.youtube.com/browse/${item.id}"
        is Playlist -> "https://music.youtube.com/playlist?list=${item.id}"
        is Radio -> "https://music.youtube.com/playlist?list=${item.id}"
        is Artist -> "https://music.youtube.com/channel/${item.id}"
        is Track -> "https://music.youtube.com/watch?v=${item.id}"
        else -> throw ClientException.NotSupported("Unsupported media item type for sharing")
    }
    
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        val fixedItem = when(item) {
            is User -> ModelTypeHelper.userToArtist(item)
            else -> item
        }
        
        return when(fixedItem) {
            is Track -> radio(fixedItem)
            is Album -> radio(fixedItem)
            is Artist -> radio(fixedItem)
            is Playlist -> radio(fixedItem)
            else -> throw ClientException.NotSupported("Radio not supported for this media item type")
        }
    }
    
    override suspend fun loadRadio(radio: Radio): Radio = radio
    
    private fun String.toGetRequest(): NetworkRequest {
        return NetworkRequest(url = this)
    }
    
    override suspend fun onTrackChanged(details: TrackDetails?) {}
    
    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {}
    
    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        return item.extras["isLiked"]?.toBoolean() ?: false
    }
    
    override suspend fun isFollowing(item: EchoMediaItem): Boolean {
        return when (item) {
            is Artist -> {
                try {
                    withUserAuth { auth ->
                        val result = auth.SubscribedToArtist.isSubscribedToArtist(item.id)
                        result.getOrNull() ?: false
                    }
                } catch (e: Exception) {
                    println("Failed to check if artist is followed: ${e.message}")
                    false
                }
            }
            else -> false
        }
    }
    
    override suspend fun getFollowersCount(item: EchoMediaItem): Long? {
        return item.extras["followerCount"]?.toLong()
    }
    
    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        when(item) {
            is Artist -> {
                val subId = item.extras["subId"]
                withUserAuth { it.SetSubscribedToArtist.setSubscribedToArtist(item.id, shouldFollow, subId) }
            }
            else -> throw ClientException.NotSupported("Follow not supported for this media item type")
        }
    }
    
    override suspend fun searchLyrics(query: String): Feed<Lyrics> {
        return listOf<Lyrics>().toFeed()
    }
}