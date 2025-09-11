package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.endpoint.SearchResults
import dev.toastbits.ytmkt.model.external.YoutubeVideoFormat
import dev.toastbits.ytmkt.endpoint.SearchType
import dev.toastbits.ytmkt.formats.MultipleVideoFormatsEndpoint
import dev.toastbits.ytmkt.formats.NewPipeVideoFormatsEndpoint
import dev.toastbits.ytmkt.formats.PipedVideoFormatsEndpoint
import dev.toastbits.ytmkt.formats.YoutubeiVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.NewPipeExtractorKmpVideoFormatsEndpoint
import dev.toastbits.ytmkt.endpoint.SongLikedEndpoint
import dev.toastbits.ytmkt.model.external.SongLikedStatus
import dev.toastbits.ytmkt.endpoint.RadioBuilderModifier
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.headers
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay

class YoutubeExtension : ExtensionClient, SearchFeedClient, TrackClient, LoginClient.WebView, LikeClient, LibraryFeedClient, AlbumClient, ArtistClient, PlaylistClient, PlaylistEditClient, HomeFeedClient, dev.brahmkshatriya.echo.common.clients.RadioClient, FollowClient {

    private lateinit var setting: Settings
    private val youtubeApi by lazy { 
        val api = YoutubeiApi()
        try {
            val visitorIdResult = runBlocking { api.GetVisitorId.getVisitorId() }
            if (visitorIdResult.isSuccess) {
                api.visitor_id = visitorIdResult.getOrThrow()
                println("Successfully set visitor ID: ${api.visitor_id}")
            } else {
                println("Failed to get visitor ID: ${visitorIdResult.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            println("Exception getting visitor ID: ${e.message}")
        }
        api
    }
    private val converter by lazy { YoutubeConverter() }

    override fun setSettings(settings: Settings) {
        this.setting = settings
    }

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingSwitch(
            "Show Videos [Not Working For Now]",
            "show_videos",
            "Allows videos to be available when playing stuff. Instead of disabling videos, change the streaming quality as Medium in the app settings to select audio only by default.",
            false
        ),
        SettingSwitch(
            "Prefer Videos [Not Working For Now]",
            "prefer_videos",
            "Prefer videos over audio when available",
            false
        )
    )

    private val showVideos
        get() = setting.getBoolean("show_videos") != false

    private val preferVideos
        get() = setting.getBoolean("prefer_videos") == false

    override suspend fun onInitialize() {
        println("YouTube Music Extension initialized")
    }

    //Authentication

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
            return youtubeApi.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
                headers {
                    append("referer", "https://music.youtube.com/")
                    appendAll(headers)
                }
            }.parseAccountSwitcherResponse(cookie, auth)
        }
    }

    override fun setLoginUser(user: User?) {
        if (user == null) {
            youtubeApi.user_auth_state = null
        } else {
            val cookie = user.extras["cookie"] ?: throw Exception("No cookie")
            val auth = user.extras["auth"] ?: throw Exception("No auth")

            val headers = headers {
                append("cookie", cookie)
                append("authorization", auth)
            }
            val authenticationState =
                YoutubeiAuthenticationState(youtubeApi, headers, user.id.ifEmpty { null })
            youtubeApi.user_auth_state = authenticationState
        }
        try {
            val visitorIdResult = runBlocking { youtubeApi.GetVisitorId.getVisitorId() }
            if (visitorIdResult.isSuccess) {
                youtubeApi.visitor_id = visitorIdResult.getOrThrow()
                println("Successfully refreshed visitor ID: ${youtubeApi.visitor_id}")
            } else {
                println("Failed to refresh visitor ID: ${visitorIdResult.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            println("Exception refreshing visitor ID: ${e.message}")
        }
    }

    override suspend fun getCurrentUser(): User? {
        val authState = youtubeApi.user_auth_state ?: return null

        return try {
            val userResponse = youtubeApi.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
                headers {
                    append("referer", "https://music.youtube.com/")
                    appendAll(authState.headers)
                }
            }.parseAccountSwitcherResponse("", "").firstOrNull() ?: return null
            
            userResponse.copy(
                subtitle = userResponse.extras["channelHandle"]?.takeIf { it.isNotEmpty() } 
                    ?: userResponse.extras["email"]?.takeIf { it.isNotEmpty() }
                    ?: "YouTube Music User",
                extras = userResponse.extras.toMutableMap().apply {
                    put("isLoggedIn", "true")
                    put("userService", "youtube_music")
                    put("accountType", "google")
                    put("lastUpdated", System.currentTimeMillis().toString())
                }
            )
        } catch (e: Exception) {
            println("Failed to get current user: ${e.message}")
            null
        }
    }

    //Like Implement

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        val track = item as? Track ?: throw Exception("Only tracks can be liked")
        likeTrack(track, shouldLike)
    }

    private suspend fun likeTrack(track: Track, isLiked: Boolean) {
        val likeStatus = if (isLiked) SongLikedStatus.LIKED else SongLikedStatus.NEUTRAL
        
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
        authState.SetSongLiked.setSongLiked(track.id, likeStatus).getOrThrow()
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        val track = item as? Track ?: return false
        
        val authState = youtubeApi.user_auth_state
            ?: return false
            
        return try {
            val result = authState.SongLiked.getSongLiked(track.id).getOrThrow()
            result == SongLikedStatus.LIKED
        } catch (e: Exception) {
            println("Failed to check if track is liked: ${e.message}")
            false
        }
    }

    //Search Implement

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return emptyList<Shelf>().toFeed()
        }

        val tabs = listOf(
            Tab("all", "All"),
            Tab("songs", "Songs"),
            Tab("videos", "Videos"),
            Tab("albums", "Albums"),
            Tab("artists", "Artists"),
            Tab("playlists", "Playlists")
        )

        return Feed(tabs) { tab ->
            when (tab?.id) {
                "songs" -> createSongsTab(query)
                "videos" -> createVideosTab(query)
                "albums" -> createAlbumsTab(query)
                "artists" -> createArtistsTab(query)
                "playlists" -> createPlaylistsTab(query)
                else -> createAllTab(query) 
            }
        }
    }

    private suspend fun createAllTab(query: String): Feed.Data<Shelf> {
        return try {
            val searchResult = youtubeApi.Search.search(query).getOrThrow()
            val shelves = convertSearchResultsToShelves(searchResult)
            shelves.toFeedData()
        } catch (e: Exception) {
            println("Search failed: ${e.message}")
            emptyList<Shelf>().toFeedData()
        }
    }

    private suspend fun createSongsTab(query: String): Feed.Data<Shelf> {
        return try {
            val searchResult = youtubeApi.Search.search(
                query,
                params = SearchType.SONG.getDefaultParams()
            ).getOrThrow()
            
            val shelves = convertSearchResultsToShelves(searchResult)
            shelves.toFeedData()
        } catch (e: Exception) {
            println("Songs search failed: ${e.message}")
            emptyList<Shelf>().toFeedData()
        }
    }

    private suspend fun createVideosTab(query: String): Feed.Data<Shelf> {
        return try {
            val searchResult = youtubeApi.Search.search(
                query,
                params = SearchType.VIDEO.getDefaultParams()
            ).getOrThrow()
            
            val shelves = convertSearchResultsToShelves(searchResult)
            shelves.toFeedData()
        } catch (e: Exception) {
            println("Videos search failed: ${e.message}")
            emptyList<Shelf>().toFeedData()
        }
    }

    private suspend fun createAlbumsTab(query: String): Feed.Data<Shelf> {
        return try {
            val searchResult = youtubeApi.Search.search(
                query,
                params = SearchType.ALBUM.getDefaultParams()
            ).getOrThrow()
            
            val shelves = convertSearchResultsToShelves(searchResult)
            shelves.toFeedData()
        } catch (e: Exception) {
            println("Albums search failed: ${e.message}")
            emptyList<Shelf>().toFeedData()
        }
    }

    private suspend fun createArtistsTab(query: String): Feed.Data<Shelf> {
        return try {
            val searchResult = youtubeApi.Search.search(
                query,
                params = SearchType.ARTIST.getDefaultParams()
            ).getOrThrow()
            
            val shelves = convertSearchResultsToShelves(searchResult)
            shelves.toFeedData()
        } catch (e: Exception) {
            println("Artists search failed: ${e.message}")
            emptyList<Shelf>().toFeedData()
        }
    }

    private suspend fun createPlaylistsTab(query: String): Feed.Data<Shelf> {
        return try {
            val searchResult = youtubeApi.Search.search(
                query,
                params = SearchType.PLAYLIST.getDefaultParams()
            ).getOrThrow()
            
            val shelves = convertSearchResultsToShelves(searchResult)
            shelves.toFeedData()
        } catch (e: Exception) {
            println("Playlists search failed: ${e.message}")
            emptyList<Shelf>().toFeedData()
        }
    }

    private suspend fun convertSearchResultsToShelves(searchResults: SearchResults): List<Shelf> {
        val shelves = mutableListOf<Shelf>()
        
        for ((layout, filter) in searchResults.categories) {
            val title = layout.title?.getString("en") ?: "Results"
            val items = layout.items
            
            if (items.isNotEmpty()) {
                val echoItems = items.mapNotNull { item ->
                    when (item) {
                        is YtmSong -> converter.convertSongToTrack(item)
                        is YtmArtist -> converter.convertArtistToEchoArtist(item)
                        is YtmPlaylist -> {
                            if (item.type == YtmPlaylist.Type.ALBUM) {
                                converter.convertPlaylistToEchoAlbum(item)
                            } else {
                                converter.convertPlaylistToEchoPlaylist(item)
                            }
                        }
                        else -> null
                    }
                }
                
                if (echoItems.isNotEmpty()) {
                    val shelf = if (echoItems.all { it is Track }) {
                        Shelf.Lists.Tracks(
                            id = "search_${title.lowercase().replace(" ", "_")}",
                            title = title,
                            list = echoItems.filterIsInstance<Track>()
                        )
                    } else {
                        Shelf.Lists.Items(
                            id = "search_${title.lowercase().replace(" ", "_")}",
                            title = title,
                            list = echoItems
                        )
                    }
                    shelves.add(shelf)
                }
            }
        }
        
        return shelves
    }

    //Track Implement

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return try {
            val ytmSong = youtubeApi.LoadSong.loadSong(track.id).getOrThrow()
            val updatedTrack = converter.convertSongToTrack(ytmSong)
            
            val finalTrack = updatedTrack.copy(
                cover = updatedTrack.cover ?: track.cover,
                background = updatedTrack.background ?: track.background
            )
            
            finalTrack
        } catch (e: Exception) {
            println("Failed to load track details for ${track.id}: ${e.message}")
            track
        }
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, 
        isDownload: Boolean
    ): Streamable.Media {
        return when (streamable.type) {
            Streamable.MediaType.Server -> {
                val videoId = streamable.extras["videoId"] 
                    ?: throw Exception("No video ID found in streamable extras. This track may not be playable.")
                
                markSongAsPlayed(videoId)
                
                println("Loading streamable media for video ID: $videoId")
                
                val newPipeExtractor = NewPipeExtractorKmpVideoFormatsEndpoint(youtubeApi)
                
                try {
                    if (youtubeApi.visitor_id == null) {
                        println("Visitor ID is null, trying to get a new one...")
                        val visitorIdResult = youtubeApi.GetVisitorId.getVisitorId()
                        if (visitorIdResult.isSuccess) {
                            youtubeApi.visitor_id = visitorIdResult.getOrThrow()
                            println("Successfully set visitor ID: ${youtubeApi.visitor_id}")
                        } else {
                            println("Failed to get visitor ID: ${visitorIdResult.exceptionOrNull()?.message}")
                        }
                    } else {
                        println("Using existing visitor ID: ${youtubeApi.visitor_id}")
                    }
                } catch (e: Exception) {
                    println("Exception ensuring visitor ID: ${e.message}")
                }
                
                val formatsResult = newPipeExtractor.getVideoFormats(videoId, include_non_default = true, filter = null)
                
                if (formatsResult.isFailure) {
                    val exception = formatsResult.exceptionOrNull()
                    throw Exception("Failed to get video formats: ${exception?.message ?: "Unknown error"}. The track may not be available.")
                }
                
                val formats = formatsResult.getOrThrow()
                
                println("Found ${formats.size} total formats")
                formats.forEach { format ->
                    println("Format: ${format.mimeType}, bitrate: ${format.bitrate}, hasUrl: ${format.url != null}")
                }
                
                val validFormats = formats.filter { it.url != null }
                
                if (validFormats.isEmpty()) {
                    throw Exception("No valid formats with URLs available for this track. The track may not be available in your region.")
                }
                
                val audioFormats = validFormats.filter { 
                    it.mimeType.startsWith("audio/") 
                }
                
                val videoFormats = if (showVideos) {
                    validFormats.filter { 
                        it.mimeType.startsWith("video/") 
                    }
                } else {
                    emptyList()
                }
                
                if (audioFormats.isEmpty() && videoFormats.isEmpty()) {
                    throw Exception("No playable formats found for this track. The track may be region-restricted or unavailable.")
                }
                
                val audioSources = audioFormats.map { format ->
                    val bitrateKbps = if (format.bitrate != null) format.bitrate / 1000 else 0
                    Streamable.Source.Http(
                        request = format.url!!.toGetRequest(),
                        type = Streamable.SourceType.Progressive,
                        quality = bitrateKbps,
                        title = "Audio - ${format.mimeType}${if (bitrateKbps > 0) " - ${bitrateKbps}kbps" else ""}"
                    )
                }
                
                val videoSources = videoFormats.map { format ->
                    val bitrateKbps = if (format.bitrate != null) format.bitrate / 1000 else 0
                    Streamable.Source.Http(
                        request = format.url!!.toGetRequest(),
                        type = Streamable.SourceType.Progressive,
                        quality = bitrateKbps,
                        title = "Video - ${format.mimeType}${if (bitrateKbps > 0) " - ${bitrateKbps}kbps" else ""}"
                    )
                }
                
                return when {
                    preferVideos && videoSources.isNotEmpty() && audioSources.isNotEmpty() -> {
                        Streamable.Media.Server(
                            sources = listOf(audioSources.first(), videoSources.first()),
                            merged = true
                        )
                    }
                    videoSources.isNotEmpty() && !preferVideos -> {
                        Streamable.Media.Server(videoSources, false)
                    }
                    audioSources.isNotEmpty() -> {
                        Streamable.Media.Server(audioSources, false)
                    }
                    else -> {
                        throw Exception("No valid formats with URLs available after trying all endpoints. The track may not be available.")
                    }
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

    // Marks a song as played in the user's history

    private suspend fun markSongAsPlayed(songId: String) {
        try {
            val authState = youtubeApi.user_auth_state ?: return
            
            val result = authState.MarkSongAsWatched.markSongAsWatched(songId)
            
            if (result.isSuccess) {
                println("Successfully marked song $songId as played in user's history")
            } else {
                println("Failed to mark song $songId as played: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            println("Exception while marking song as played: ${e.message}")
        }
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf> {
        return emptyList<Shelf>().toFeed()
    }
    
    //Home Feed Implement

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        return try {
            val songFeedResult = youtubeApi.SongFeed.getSongFeed()
            if (songFeedResult.isFailure) {
                println("Failed to load home feed: ${songFeedResult.exceptionOrNull()?.message}")
                return emptyList<Shelf>().toFeed()
            }
            
            val songFeed = songFeedResult.getOrThrow()
            val shelves = songFeed.layouts.mapNotNull { layout ->
                val title = layout.title?.getString("en") ?: "Unknown"
                val layoutItems = layout.items
                val items = if (layoutItems != null) {
                    layoutItems.mapNotNull { item ->
                        when (item) {
                            is YtmSong -> converter.convertSongToTrack(item)
                            is YtmArtist -> converter.convertArtistToEchoArtist(item)
                            is YtmPlaylist -> {
                                if (item.type == YtmPlaylist.Type.ALBUM) {
                                    converter.convertPlaylistToEchoAlbum(item)
                                } else {
                                    converter.convertPlaylistToEchoPlaylist(item)
                                }
                            }
                            else -> null
                        }
                    }
                } else {
                    emptyList()
                }
                
                if (items.isNotEmpty()) {
                    if (items.all { it is Track }) {
                        Shelf.Lists.Tracks(
                            id = "home_tracks_${title.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}", 
                            title = title,
                            list = items.filterIsInstance<Track>()
                        )
                    } else {
                        Shelf.Lists.Items(
                            id = "home_${title.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}", 
                            title = title,
                            list = items
                        )
                    }
                } else null
            }.toMutableList<Shelf>()
            
            val authState = youtubeApi.user_auth_state
            if (authState != null) {
                addPersonalizedSectionsWithDelay(shelves, authState)
            }
            
            shelves.toFeed()
        } catch (e: Exception) {
            println("Exception loading home feed: ${e.message}")
            emptyList<Shelf>().toFeed()
        }
    }
    
    //delays to prevent server overload

    private suspend fun addPersonalizedSectionsWithDelay(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState) {
        try {
            addRecentlyPlayed(shelves, authState)
        } catch (e: Exception) {
            println("Failed to load recently played: ${e.message}")
        }     
        kotlinx.coroutines.delay(200)     
        try {
            addRecommendations(shelves, authState)
        } catch (e: Exception) {
            println("Failed to load recommendations: ${e.message}")
        }      
        kotlinx.coroutines.delay(300)
        try {
            addMixes(shelves, authState)
        } catch (e: Exception) {
            println("Failed to load mixes: ${e.message}")
        }
        kotlinx.coroutines.delay(250)
        try {
            addLofiContent(shelves)
        } catch (e: Exception) {
            println("Failed to load lofi content: ${e.message}")
        }
        
        kotlinx.coroutines.delay(350)
        try {
            addMoodBasedRecommendations(shelves)
        } catch (e: Exception) {
            println("Failed to load mood-based recommendations: ${e.message}")
        }    
        kotlinx.coroutines.delay(300)
        try {
            addTrendingContent(shelves)
        } catch (e: Exception) {
            println("Failed to load trending content: ${e.message}")
        }      
        kotlinx.coroutines.delay(250)
        try {
            addNewReleases(shelves)
        } catch (e: Exception) {
            println("Failed to load new releases: ${e.message}")
        }        

        kotlinx.coroutines.delay(300)
        try {
            addMoodsAndGenres(shelves)
        } catch (e: Exception) {
            println("Failed to load moods & genres: ${e.message}")
        }

        kotlinx.coroutines.delay(250)
        try {
            addSimilarToRecentlyPlayed(shelves, authState)
        } catch (e: Exception) {
            println("Failed to load similar to recently played: ${e.message}")
        }
        
        kotlinx.coroutines.delay(350)
        try {
            addDiscoverWeeklyPlaylists(shelves, authState)
        } catch (e: Exception) {
            println("Failed to load discover weekly playlists: ${e.message}")
        }
        
        kotlinx.coroutines.delay(300)
        try {
            addReleaseRadar(shelves, authState)
        } catch (e: Exception) {
            println("Failed to load release radar: ${e.message}")
        }
        
        kotlinx.coroutines.delay(250)
        try {
            addContinueWatchingListening(shelves, authState)
        } catch (e: Exception) {
            println("Failed to load continue watching/listening: ${e.message}")
        }
        
        kotlinx.coroutines.delay(300)
        try {
            addYourTopSongs(shelves, authState)
        } catch (e: Exception) {
            println("Failed to load your top songs: ${e.message}")
        }
        
        kotlinx.coroutines.delay(250)
        try {
            addRecentlyUploadedPlaylists(shelves, authState)
        } catch (e: Exception) {
            println("Failed to load recently uploaded playlists: ${e.message}")
        }
    }
    
    //Add recently played content with a delay
 
    private suspend fun addRecentlyPlayed(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState) {
        try {
            val historyResult = youtubeApi.GenericFeedViewMorePage.getGenericFeedViewMorePage("FEmusic_history")
            if (historyResult.isSuccess) {
                val historyItems = historyResult.getOrThrow().mapNotNull { item ->
                    when (item) {
                        is YtmSong -> converter.convertSongToTrack(item)
                        else -> null
                    }
                }.filterIsInstance<Track>()
                
                if (historyItems.isNotEmpty()) {
                    shelves.add(0, Shelf.Lists.Tracks(
                        id = "recently_played_24h_${System.currentTimeMillis()}", 
                        title = "Recently Played (Last 24 Hours)",
                        list = historyItems.take(20) 
                    ))
                }
            }
        } catch (e: Exception) {
            println("Failed to load 24h history: ${e.message}")
        }
    }
    
    //Add recommendations with a delay

    private suspend fun addRecommendations(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState) {
        try {
            val recentTracks = shelves.find { it is Shelf.Lists.Tracks }?.let { it as Shelf.Lists.Tracks }?.list?.take(3)
            if (recentTracks != null && recentTracks.isNotEmpty()) {
                val recommendations = mutableListOf<Track>()
                for (track in recentTracks) {
                    try {
                        val radioResult = youtubeApi.SongRadio.getSongRadio(track.id, null)
                        if (radioResult.isSuccess) {
                            val radioData = radioResult.getOrThrow()
                            val radioTracks = radioData.items.map { song -> 
                                converter.convertSongToTrack(song)
                            }
                            recommendations.addAll(radioTracks.take(5)) 
                        }
                    } catch (e: Exception) {
                        println("Failed to get recommendations for track ${track.id}: ${e.message}")
                    }
                }
                
                if (recommendations.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Tracks(
                        id = "recommended_for_you_${System.currentTimeMillis()}", 
                        title = "Recommended For You",
                        list = recommendations.distinctBy { it.id }.take(20) 
                    ))
                }
            }
        } catch (e: Exception) {
            println("Failed to load recommendations: ${e.message}")
        }
    }
    
    //Add mixes with a delay

    private suspend fun addMixes(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState) {
        try {
            val likedArtistsResult = authState.LikedArtists.getLikedArtists()
            if (likedArtistsResult.isSuccess) {
                val likedArtists = likedArtistsResult.getOrThrow()
                val mixes = mutableListOf<Playlist>()
                
                for (artist in likedArtists.take(3)) {
                    try {
                        val radioResult = youtubeApi.ArtistRadio.getArtistRadio(artist.id, null)
                        if (radioResult.isSuccess) {
                            val radioData = radioResult.getOrThrow()
                            if (radioData.items.isNotEmpty()) {
                                val mixPlaylist = Playlist(
                                    id = "artist_mix_${artist.id}_${System.currentTimeMillis()}", 
                                    title = "${artist.name} Mix",
                                    cover = artist.thumbnail_provider?.let { provider ->
                                        val url = provider.getThumbnailUrl(dev.toastbits.ytmkt.model.external.ThumbnailProvider.Quality.HIGH)
                                        if (url != null) {
                                            url.toImageHolder()
                                        } else {
                                            null
                                        }
                                    },
                                    isEditable = false,
                                    isPrivate = false,
                                    description = "Mix based on ${artist.name}",
                                    extras = mapOf("source" to "artist_radio", "artistId" to artist.id)
                                )
                                mixes.add(mixPlaylist)
                            }
                        }
                    } catch (e: Exception) {
                        println("Failed to create mix for artist ${artist.id}: ${e.message}")
                    }
                }
                
                if (mixes.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Items(
                        id = "your_mixes_${System.currentTimeMillis()}", 
                        title = "Your Mixes",
                        list = mixes
                    ))
                }
            }
        } catch (e: Exception) {
            println("Failed to load mixes: ${e.message}")
        }
    }
    
    //Add lofi content with a delay

    private suspend fun addLofiContent(shelves: MutableList<Shelf>) {
        try {
            val lofiSearchResult = youtubeApi.Search.search("lofi", params = "Eg-KAQwIARAAGAAgACgAMABqChADEAQQCRAFEAo%3D")
            if (lofiSearchResult.isSuccess) {
                val lofiResults = lofiSearchResult.getOrThrow()
                val lofiTracks = mutableListOf<Track>()
                
                for ((layout, _) in lofiResults.categories) {
                    for (item in layout.items) {
                        if (item is YtmSong) {
                            lofiTracks.add(converter.convertSongToTrack(item))
                        }
                    }
                }
                
                if (lofiTracks.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Tracks(
                        id = "lofi_music_${System.currentTimeMillis()}", 
                        title = "Lofi Music",
                        list = lofiTracks.take(20)
                    ))
                }
            }
        } catch (e: Exception) {
            println("Failed to load lofi content: ${e.message}")
        }
    }
    
    //mood-based recommendations on home feed

    private suspend fun addMoodBasedRecommendations(shelves: MutableList<Shelf>) {
        val moods = listOf(
            "Chill" to "EgWKAQIoAUICCAFqDBAOEAoQAxAEEAkQBQ%3D%3D",
            "Focus" to "EgWKAQIgAUICCAFqDBAOEAoQAxAEEAkQBQ%3D%3D",
            "Workout" to "EgWKAQIQAUICCAFqDBAOEAoQAxAEEAkQBQ%3D%3D",
            "Party" to "EgWKAQIYAUICCAFqDBAOEAoQAxAEEAkQBQ%3D%3D",
            "Sleep" to "EgWKAQIoAUICCAFqDBAOEAoQAxAEEAkQBQ%3D%3D"
        )
        
        val moodPlaylists = mutableListOf<Playlist>()
        
        for ((mood, params) in moods) {
            try {
                val searchResult = youtubeApi.Search.search(mood, params = params)
                if (searchResult.isSuccess) {
                    val results = searchResult.getOrThrow()
                    for ((layout, _) in results.categories) {
                        for (item in layout.items) {
                            if (item is YtmPlaylist && item.type != YtmPlaylist.Type.ALBUM) {
                                val playlist = converter.convertPlaylistToEchoPlaylist(item)
                                moodPlaylists.add(playlist.copy(
                                    title = "$mood: ${playlist.title}",
                                    extras = playlist.extras + mapOf("mood" to mood)
                                ))
                                break
                            }
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                println("Failed to load $mood playlists: ${e.message}")
            }
        }
        
        if (moodPlaylists.isNotEmpty()) {
            shelves.add(Shelf.Lists.Items(
                id = "mood_playlists_${System.currentTimeMillis()}", 
                title = "Mood Playlists",
                list = moodPlaylists.take(10) 
            ))
        }
    }
    
    //trending content to the home feed

    private suspend fun addTrendingContent(shelves: MutableList<Shelf>) {
        try {
            val trendingSearch = youtubeApi.Search.search("trending music", params = "Eg-KAQwIARAAGAAgACgAMABqChADEAQQCRAFEAo%3D")
            if (trendingSearch.isSuccess) {
                val trendingResults = trendingSearch.getOrThrow()
                val trendingTracks = mutableListOf<Track>()
                
                for ((layout, _) in trendingResults.categories) {
                    for (item in layout.items) {
                        if (item is YtmSong) {
                            trendingTracks.add(converter.convertSongToTrack(item))
                        }
                    }
                }
                
                if (trendingTracks.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Tracks(
                        id = "trending_music_${System.currentTimeMillis()}", 
                        title = "Trending Now",
                        list = trendingTracks.take(20)
                    ))
                }
            }
        } catch (e: Exception) {
            println("Failed to load trending content: ${e.message}")
        }
    }
    
    //new releases to the home feed

    private suspend fun addNewReleases(shelves: MutableList<Shelf>) {
        try {
            val newReleasesSearch = youtubeApi.Search.search("new releases", params = "Eg-KAQwIABABGAAgACgAMABqChADEAQQCRAFEAo%3D")
            if (newReleasesSearch.isSuccess) {
                val newReleasesResults = newReleasesSearch.getOrThrow()
                val newReleasesItems = mutableListOf<EchoMediaItem>()
                
                for ((layout, _) in newReleasesResults.categories) {
                    for (item in layout.items) {
                        when (item) {
                            is YtmSong -> newReleasesItems.add(converter.convertSongToTrack(item))
                            is YtmPlaylist -> {
                                if (item.type == YtmPlaylist.Type.ALBUM) {
                                    newReleasesItems.add(converter.convertPlaylistToEchoAlbum(item))
                                } else {
                                    newReleasesItems.add(converter.convertPlaylistToEchoPlaylist(item))
                                }
                            }
                        }
                    }
                }
                
                if (newReleasesItems.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Items(
                        id = "new_releases_${System.currentTimeMillis()}", 
                        title = "New Releases",
                        list = newReleasesItems.take(20)
                    ))
                }
            }
        } catch (e: Exception) {
            println("Failed to load new releases: ${e.message}")
        }
    }
    
    //moods and genres to the home feed

    private suspend fun addMoodsAndGenres(shelves: MutableList<Shelf>) {
        try {
            val moodsAndGenresSearch = youtubeApi.Search.search("moods and genres", params = "Eg-KAQwIARAAGAAgACgAMABqChADEAQQCRAFEAo%3D")
            if (moodsAndGenresSearch.isSuccess) {
                val moodsAndGenresResults = moodsAndGenresSearch.getOrThrow()
                val moodsAndGenresItems = mutableListOf<EchoMediaItem>()
                
                for ((layout, _) in moodsAndGenresResults.categories) {
                    for (item in layout.items) {
                        when (item) {
                            is YtmPlaylist -> {
                                if (item.type != YtmPlaylist.Type.ALBUM) {
                                    moodsAndGenresItems.add(converter.convertPlaylistToEchoPlaylist(item))
                                }
                            }
                            is YtmArtist -> moodsAndGenresItems.add(converter.convertArtistToEchoArtist(item))
                        }
                    }
                }
                
                if (moodsAndGenresItems.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Items(
                        id = "moods_and_genres_${System.currentTimeMillis()}", 
                        title = "Moods & Genres",
                        list = moodsAndGenresItems.take(20)
                    ))
                }
            }
        } catch (e: Exception) {
            println("Failed to load moods and genres: ${e.message}")
        }
    }
    

    
    //similar to recently played recommendations

    private suspend fun addSimilarToRecentlyPlayed(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState?) {
        if (authState == null) return
        
        try {
            val historyResult = youtubeApi.GenericFeedViewMorePage.getGenericFeedViewMorePage("FEmusic_history")
            if (historyResult.isSuccess) {
                val historyItems = historyResult.getOrThrow().mapNotNull { item ->
                    when (item) {
                        is YtmSong -> converter.convertSongToTrack(item)
                        else -> null
                    }
                }.filterIsInstance<Track>()
                
                if (historyItems.isNotEmpty()) {
                    val recentTracks = historyItems.take(3)
                    val similarTracks = mutableListOf<Track>()
                    
                    for (track in recentTracks) {
                        try {
                            val radioResult = youtubeApi.SongRadio.getSongRadio(track.id, null)
                            if (radioResult.isSuccess) {
                                val radioData = radioResult.getOrThrow()
                                val radioTracks = radioData.items.map { song -> 
                                    converter.convertSongToTrack(song)
                                }
                                similarTracks.addAll(radioTracks.take(5)) 
                            }
                        } catch (e: Exception) {
                            println("Failed to get similar tracks for ${track.id}: ${e.message}")
                        }
                    }
                    
                    if (similarTracks.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Tracks(
                            id = "similar_to_recently_played_${System.currentTimeMillis()}", 
                            title = "Similar to Recently Played",
                            list = similarTracks.distinctBy { it.id }.take(20) 
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to load similar to recently played: ${e.message}")
        }
    }
    
    //Discover Weekly style playlists

    private suspend fun addDiscoverWeeklyPlaylists(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState?) {
        if (authState == null) return
        
        try {
            val likedArtistsResult = authState.LikedArtists.getLikedArtists()
            if (likedArtistsResult.isSuccess) {
                val likedArtists = likedArtistsResult.getOrThrow()
                if (likedArtists.isNotEmpty()) {
                    try {
                        val radioBuilderArtists = youtubeApi.RadioBuilder.getRadioBuilderArtists { thumbnails ->
                            thumbnails.maxByOrNull { it.width * it.height } ?: thumbnails.firstOrNull() ?: throw Exception("No thumbnails available")
                        }.getOrThrow()
                        
                        val selectedArtists = radioBuilderArtists.filter { radioArtist ->
                            likedArtists.any { likedArtist ->
                                likedArtist.name?.contains(radioArtist.name, ignoreCase = true) == true ||
                                radioArtist.name.contains(likedArtist.name ?: "", ignoreCase = true)
                            }
                        }.take(5).toSet() 
                        
                        if (selectedArtists.isNotEmpty()) {
                            val modifiers = setOf(
                                RadioBuilderModifier.Variety.HIGH,
                                RadioBuilderModifier.SelectionType.DISCOVER
                            )
                            
                            val radioToken = youtubeApi.RadioBuilder.buildRadioToken(selectedArtists, modifiers)
                            val builtRadio = youtubeApi.RadioBuilder.getBuiltRadio(radioToken).getOrThrow()
                            
                            if (builtRadio != null) {
                                val discoveryPlaylist = converter.convertPlaylistToEchoPlaylist(builtRadio)
                                val discoveryItems = mutableListOf<EchoMediaItem>()
                                
                                discoveryItems.add(discoveryPlaylist.copy(
                                    title = "Discover Weekly",
                                    description = "Fresh music picked for you based on your taste",
                                    extras = discoveryPlaylist.extras + mapOf("discoveryType" to "weekly")
                                ))
                                
                                builtRadio.items?.take(10)?.forEach { song ->
                                    discoveryItems.add(converter.convertSongToTrack(song))
                                }
                                
                                if (discoveryItems.isNotEmpty()) {
                                    shelves.add(Shelf.Lists.Items(
                                        id = "discover_weekly_${System.currentTimeMillis()}", 
                                        title = "Discover Weekly",
                                        list = discoveryItems
                                    ))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("Failed to create discover weekly playlist: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to load discover weekly playlists: ${e.message}")
        }
    }
    
    //Release Radar for new music from followed artists

    private suspend fun addReleaseRadar(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState?) {
        if (authState == null) return
        
        try {
            val likedArtistsResult = authState.LikedArtists.getLikedArtists()
            if (likedArtistsResult.isSuccess) {
                val likedArtists = likedArtistsResult.getOrThrow()
                if (likedArtists.isNotEmpty()) {
                    val newReleasesFromArtists = mutableListOf<EchoMediaItem>()
                    
                    for (artist in likedArtists.take(10)) { 
                        try {
                            val artistName = artist.name ?: continue
                            val searchResult = youtubeApi.Search.search("$artistName new music", params = "EgWKAQIoAUICCAFqDBAOEAoQAxAEEAkQBQ%3D%3D")
                            if (searchResult.isSuccess) {
                                val results = searchResult.getOrThrow()
                                for ((layout, _) in results.categories) {
                                    for (item in layout.items) {
                                        when (item) {
                                            is YtmSong -> {
                                                if (item.artists?.any { it.name?.contains(artistName, ignoreCase = true) == true } == true) {
                                                    newReleasesFromArtists.add(converter.convertSongToTrack(item))
                                                }
                                            }
                                            is YtmPlaylist -> {
                                                if (item.type == YtmPlaylist.Type.ALBUM) {
                                                    if (item.artists?.any { it.name?.contains(artistName, ignoreCase = true) == true } == true) {
                                                        newReleasesFromArtists.add(converter.convertPlaylistToEchoAlbum(item))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            println("Failed to search for new releases from artist ${artist.id}: ${e.message}")
                        }
                    }
                    
                    if (newReleasesFromArtists.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Items(
                            id = "release_radar_${System.currentTimeMillis()}", 
                            title = "Release Radar",
                            list = newReleasesFromArtists.distinctBy { 
                                when (it) {
                                    is Track -> it.id
                                    is Album -> it.id
                                    else -> it.hashCode().toString()
                                }
                            }.take(20) 
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to load release radar: ${e.message}")
        }
    }
    
    //Continue Watching/Listening

    private suspend fun addContinueWatchingListening(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState?) {
        if (authState == null) return
        
        try {
            val historyResult = youtubeApi.GenericFeedViewMorePage.getGenericFeedViewMorePage("FEmusic_history")
            if (historyResult.isSuccess) {
                val historyItems = historyResult.getOrThrow().mapNotNull { item ->
                    when (item) {
                        is YtmSong -> {
                            val track = converter.convertSongToTrack(item)
                            val playedDuration = track.playedDuration
                            if (playedDuration != null && playedDuration > 0L) {
                                track
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                }.filterIsInstance<Track>()
                
                if (historyItems.isNotEmpty()) {
                    val continueListeningTracks = historyItems.filter { track ->
                        val playedDuration = track.playedDuration ?: 0L
                        val totalDuration = track.duration ?: 0L
                        totalDuration > 0L && playedDuration > 0L && 
                        (playedDuration.toDouble() / totalDuration.toDouble()) < 0.8
                    }.take(10) 
                    
                    if (continueListeningTracks.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Tracks(
                            id = "continue_listening_${System.currentTimeMillis()}", 
                            title = "Continue Listening",
                            list = continueListeningTracks
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to load continue watching/listening: ${e.message}")
        }
    }
    
    //Top Songs based on play count

    private suspend fun addYourTopSongs(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState?) {
        if (authState == null) return
        
        try {
            val historyResult = youtubeApi.GenericFeedViewMorePage.getGenericFeedViewMorePage("FEmusic_history")
            if (historyResult.isSuccess) {
                val historyItems = historyResult.getOrThrow().mapNotNull { item ->
                    when (item) {
                        is YtmSong -> converter.convertSongToTrack(item)
                        else -> null
                    }
                }.filterIsInstance<Track>()
                
                if (historyItems.isNotEmpty()) {
                    val trackPlayCounts = mutableMapOf<String, Int>()
                    historyItems.forEach { track ->
                        trackPlayCounts[track.id] = trackPlayCounts.getOrDefault(track.id, 0) + 1
                    }
                    
                    val topTracks = trackPlayCounts.entries.sortedByDescending { it.value }
                        .take(20) 
                        .mapNotNull { entry ->
                            historyItems.find { it.id == entry.key }
                        }
                    
                    if (topTracks.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Tracks(
                            id = "your_top_songs_${System.currentTimeMillis()}", 
                            title = "Your Top Songs",
                            list = topTracks
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to load your top songs: ${e.message}")
        }
    }
    
    //Recently Uploaded playlists

    private suspend fun addRecentlyUploadedPlaylists(shelves: MutableList<Shelf>, authState: dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState?) {
        if (authState == null) return
        
        try {
            val accountPlaylistsResult = authState.AccountPlaylists.getAccountPlaylists()
            if (accountPlaylistsResult.isSuccess) {
                val accountPlaylists = accountPlaylistsResult.getOrThrow()
                if (accountPlaylists.isNotEmpty()) {
                    val recentPlaylists = accountPlaylists.sortedByDescending { playlist ->
                        playlist.id.hashCode() 
                    }.take(10) 
                    
                    val echoPlaylists = recentPlaylists.map { converter.convertPlaylistToEchoPlaylist(it) }
                    
                    if (echoPlaylists.isNotEmpty()) {
                        shelves.add(Shelf.Lists.Items(
                            id = "recently_uploaded_playlists_${System.currentTimeMillis()}", 
                            title = "Recently Uploaded Playlists",
                            list = echoPlaylists
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to load recently uploaded playlists: ${e.message}")
        }
    }
    
    //LibraryFeed Implement

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
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
                try {
                    val browseId = tab?.extras?.get("browseId") ?: "FEmusic_library_landing"
                    
                    if (browseId == "FEmusic_library_landing") {
                        val shelves = mutableListOf<Shelf>()
                        
                        try {
                            val playlistsResult = authState.LikedPlaylists.getLikedPlaylists()
                            if (playlistsResult.isSuccess) {
                                val playlists = playlistsResult.getOrThrow()
                                val shelfItems = playlists.map { converter.convertPlaylistToEchoPlaylist(it) }
                                if (shelfItems.isNotEmpty()) {
                                    shelves.add(
                                        Shelf.Lists.Items(
                                            id = "liked_playlists",
                                            title = "Liked Playlists",
                                            list = shelfItems
                                        )
                                    )
                                }
                            } else {
                                println("Failed to load liked playlists: ${playlistsResult.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            println("Exception loading liked playlists: ${e.message}")
                        }
                        
                        try {
                            val artistsResult = authState.LikedArtists.getLikedArtists()
                            if (artistsResult.isSuccess) {
                                val artists = artistsResult.getOrThrow()
                                val shelfItems = artists.map { converter.convertArtistToEchoArtist(it) }
                                if (shelfItems.isNotEmpty()) {
                                    shelves.add(
                                        Shelf.Lists.Items(
                                            id = "liked_artists",
                                            title = "Liked Artists",
                                            list = shelfItems
                                        )
                                    )
                                }
                            } else {
                                println("Failed to load liked artists: ${artistsResult.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            println("Exception loading liked artists: ${e.message}")
                        }
                        
                        try {
                            val albumsResult = authState.LikedAlbums.getLikedAlbums()
                            if (albumsResult.isSuccess) {
                                val albums = albumsResult.getOrThrow()
                                val shelfItems = albums.map { converter.convertPlaylistToEchoAlbum(it) }
                                if (shelfItems.isNotEmpty()) {
                                    shelves.add(
                                        Shelf.Lists.Items(
                                            id = "liked_albums",
                                            title = "Liked Albums",
                                            list = shelfItems
                                        )
                                    )
                                }
                            } else {
                                println("Failed to load liked albums: ${albumsResult.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            println("Exception loading liked albums: ${e.message}")
                        }
                        
                        try {
                            val accountPlaylistsResult = authState.AccountPlaylists.getAccountPlaylists()
                            if (accountPlaylistsResult.isSuccess) {
                                val playlists = accountPlaylistsResult.getOrThrow()
                                val shelfItems = playlists.map { converter.convertPlaylistToEchoPlaylist(it) }
                                if (shelfItems.isNotEmpty()) {
                                    shelves.add(
                                        Shelf.Lists.Items(
                                            id = "account_playlists",
                                            title = "Your Playlists",
                                            list = shelfItems
                                        )
                                    )
                                }
                            } else {
                                println("Failed to load account playlists: ${accountPlaylistsResult.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            println("Exception loading account playlists: ${e.message}")
                        }
                        
                        Page(shelves, null)
                    } else {
                        when (browseId) {
                            "FEmusic_liked_playlists" -> {
                                try {
                                    val playlistsResult = authState.LikedPlaylists.getLikedPlaylists()
                                    if (playlistsResult.isSuccess) {
                                        val playlists = playlistsResult.getOrThrow()
                                        val shelfItems = playlists.map { converter.convertPlaylistToEchoPlaylist(it) }
                                        if (shelfItems.isNotEmpty()) {
                                            val shelf = Shelf.Lists.Items(
                                                id = "liked_playlists",
                                                title = "Liked Playlists",
                                                list = shelfItems
                                            )
                                            Page(listOf(shelf), null)
                                        } else {
                                            Page(emptyList(), null)
                                        }
                                    } else {
                                        println("Failed to load liked playlists: ${playlistsResult.exceptionOrNull()?.message}")
                                        Page(emptyList(), null)
                                    }
                                } catch (e: Exception) {
                                    println("Exception loading liked playlists: ${e.message}")
                                    Page(emptyList(), null)
                                }
                            }
                            "FEmusic_library_corpus_track_artists" -> {
                                try {
                                    val artistsResult = authState.LikedArtists.getLikedArtists()
                                    if (artistsResult.isSuccess) {
                                        val artists = artistsResult.getOrThrow()
                                        val shelfItems = artists.map { converter.convertArtistToEchoArtist(it) }
                                        if (shelfItems.isNotEmpty()) {
                                            val shelf = Shelf.Lists.Items(
                                                id = "liked_artists",
                                                title = "Liked Artists",
                                                list = shelfItems
                                            )
                                            Page(listOf(shelf), null)
                                        } else {
                                            Page(emptyList(), null)
                                        }
                                    } else {
                                        println("Failed to load liked artists: ${artistsResult.exceptionOrNull()?.message}")
                                        Page(emptyList(), null)
                                    }
                                } catch (e: Exception) {
                                    println("Exception loading liked artists: ${e.message}")
                                    Page(emptyList(), null)
                                }
                            }
                            "FEmusic_liked_videos" -> {
                                try {
                                    val genericFeedResult = youtubeApi.GenericFeedViewMorePage.getGenericFeedViewMorePage(browseId)
                                    if (genericFeedResult.isSuccess) {
                                        val items = genericFeedResult.getOrThrow().mapNotNull { item ->
                                            when (item) {
                                                is YtmSong -> converter.convertSongToTrack(item)
                                                else -> null
                                            }
                                        }
                                        
                                        if (items.isNotEmpty()) {
                                            val shelf = Shelf.Lists.Tracks(
                                                id = "library_liked_songs",
                                                title = tab?.title ?: "Liked Songs",
                                                list = items.filterIsInstance<Track>()
                                            )
                                            Page(listOf(shelf), null)
                                        } else {
                                            Page(emptyList(), null)
                                        }
                                    } else {
                                        println("Failed to load liked songs: ${genericFeedResult.exceptionOrNull()?.message}")
                                        Page(emptyList(), null)
                                    }
                                } catch (e: Exception) {
                                    println("Exception loading liked songs: ${e.message}")
                                    Page(emptyList(), null)
                                }
                            }
                            "FEmusic_history" -> {
                                try {
                                    val historyResult = youtubeApi.GenericFeedViewMorePage.getGenericFeedViewMorePage(browseId)
                                    if (historyResult.isSuccess) {
                                        val items = historyResult.getOrThrow().mapNotNull { item ->
                                            when (item) {
                                                is YtmSong -> converter.convertSongToTrack(item)
                                                else -> null
                                            }
                                        }
                                        
                                        if (items.isNotEmpty()) {
                                            val shelf = Shelf.Lists.Tracks(
                                                id = "library_history",
                                                title = tab?.title ?: "History",
                                                list = items.filterIsInstance<Track>()
                                            )
                                            Page(listOf(shelf), null)
                                        } else {
                                            Page(emptyList(), null)
                                        }
                                    } else {
                                        println("Failed to load history: ${historyResult.exceptionOrNull()?.message}")
                                        Page(emptyList(), null)
                                    }
                                } catch (e: Exception) {
                                    println("Exception loading history: ${e.message}")
                                    Page(emptyList(), null)
                                }
                            }
                            else -> {
                                try {
                                    val genericFeedResult = youtubeApi.GenericFeedViewMorePage.getGenericFeedViewMorePage(browseId)
                                    if (genericFeedResult.isSuccess) {
                                        val items = genericFeedResult.getOrThrow().mapNotNull { item ->
                                            when (item) {
                                                is YtmSong -> converter.convertSongToTrack(item)
                                                is YtmArtist -> converter.convertArtistToEchoArtist(item)
                                                is YtmPlaylist -> {
                                                    if (item.type == YtmPlaylist.Type.ALBUM) {
                                                        converter.convertPlaylistToEchoAlbum(item)
                                                    } else {
                                                        converter.convertPlaylistToEchoPlaylist(item)
                                                    }
                                                }
                                                else -> null
                                            }
                                        }
                                        
                                        if (items.isNotEmpty()) {
                                            val shelf = if (items.all { it is Track }) {
                                                Shelf.Lists.Tracks(
                                                    id = "library_${browseId.lowercase().replace("femusic_", "")}",
                                                    title = tab?.title ?: "Library",
                                                    list = items.filterIsInstance<Track>()
                                                )
                                            } else {
                                                Shelf.Lists.Items(
                                                    id = "library_${browseId.lowercase().replace("femusic_", "")}",
                                                    title = tab?.title ?: "Library",
                                                    list = items
                                                )
                                            }
                                            Page(listOf(shelf), null)
                                        } else {
                                            Page(emptyList(), null)
                                        }
                                    } else {
                                        println("Failed to load library feed for $browseId: ${genericFeedResult.exceptionOrNull()?.message}")
                                        Page(emptyList(), null)
                                    }
                                } catch (e: Exception) {
                                    println("Exception loading library feed for $browseId: ${e.message}")
                                    Page(emptyList(), null)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Failed to load library feed: ${e.message}")
                    Page(emptyList(), null)
                }
            }
            Feed.Data(pagedData)
        }
    }

    //Album Implement

    override suspend fun loadAlbum(album: Album): Album {
        val ytmPlaylist = youtubeApi.LoadPlaylist.loadPlaylist(album.id).getOrThrow()
        val updatedAlbum = converter.convertPlaylistToEchoAlbum(ytmPlaylist)
        
        return updatedAlbum.copy(
            cover = updatedAlbum.cover ?: album.cover
        )
    }
    
    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val ytmPlaylist = youtubeApi.LoadPlaylist.loadPlaylist(album.id).getOrThrow()
        val playlistItems = ytmPlaylist.items
        val tracks = if (playlistItems != null) {
            playlistItems.map { song ->
                converter.convertSongToTrack(song)
            }
        } else {
            emptyList()
        }
        
        return tracks.toFeed()
    }

    //AlbumFeed Implement

    override suspend fun loadFeed(album: Album): Feed<Shelf> {
        val ytmPlaylist = youtubeApi.LoadPlaylist.loadPlaylist(album.id).getOrThrow()
        
        val shelves = mutableListOf<Shelf>()
        
        val albumItems = ytmPlaylist.items
        if (albumItems != null && albumItems.isNotEmpty()) {
            val tracks = albumItems.map { song ->
                converter.convertSongToTrack(song)
            }
            shelves.add(
                Shelf.Lists.Tracks(
                    id = "album_tracks_${album.id}",
                    title = "Tracks",
                    list = tracks
                )
            )
        }
        
        return shelves.toFeed()
    }

    //Artist Implement

    override suspend fun loadArtist(artist: Artist): Artist {
        val ytmArtist = youtubeApi.LoadArtist.loadArtist(artist.id).getOrThrow()
        val updatedArtist = converter.convertArtistToEchoArtist(ytmArtist)
        
        return updatedArtist.copy(
            cover = updatedArtist.cover ?: artist.cover
        )
    }
    
    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val ytmArtist = youtubeApi.LoadArtist.loadArtist(artist.id).getOrThrow()
        
        val shelves = mutableListOf<Shelf>()
        
        if (ytmArtist.description != null) {
            shelves.add(
                Shelf.Category(
                    id = "artist_description_${artist.id}",
                    title = "About",
                    subtitle = ytmArtist.description,
                    feed = null
                )
            )
        }
        
        val artistLayouts = ytmArtist.layouts
        if (artistLayouts != null && artistLayouts.isNotEmpty()) {
            artistLayouts.forEach { layout ->
                val title = layout.title?.getString("en") ?: "Content"
                val layoutItems = layout.items
                val items = if (layoutItems != null) {
                    layoutItems.mapNotNull { item ->
                        when (item) {
                            is YtmSong -> converter.convertSongToTrack(item)
                            is YtmPlaylist -> {
                                if (item.type == YtmPlaylist.Type.ALBUM) {
                                    converter.convertPlaylistToEchoAlbum(item)
                                } else {
                                    converter.convertPlaylistToEchoPlaylist(item)
                                }
                            }
                            is YtmArtist -> converter.convertArtistToEchoArtist(item)
                            else -> null
                        }
                    }
                } else {
                    emptyList()
                }
                
                if (items.isNotEmpty()) {
                    shelves.add(
                        Shelf.Lists.Items(
                            id = "artist_layout_${layout.hashCode()}",
                            title = title,
                            list = items
                        )
                    )
                }
            }
        }
        
        return shelves.toFeed()
    }

    //Playlist Implement

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val ytmPlaylist = youtubeApi.LoadPlaylist.loadPlaylist(playlist.id).getOrThrow()
        val updatedPlaylist = converter.convertPlaylistToEchoPlaylist(ytmPlaylist)
        
        return updatedPlaylist.copy(
            cover = updatedPlaylist.cover ?: playlist.cover
        )
    }
    
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val ytmPlaylist = youtubeApi.LoadPlaylist.loadPlaylist(playlist.id).getOrThrow()
        val tracks = ytmPlaylist.items?.map { song ->
            converter.convertSongToTrack(song)
        } ?: emptyList()
        
        return tracks.toFeed()
    }

    //PlaylistFeed Implement

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf> {
        val ytmPlaylist = youtubeApi.LoadPlaylist.loadPlaylist(playlist.id).getOrThrow()
        
        val shelves = mutableListOf<Shelf>()
        
        val playlistItems = ytmPlaylist.items
        if (playlistItems != null && playlistItems.isNotEmpty()) {
            val tracks = playlistItems.map { song ->
                converter.convertSongToTrack(song)
            }
            shelves.add(
                Shelf.Lists.Tracks(
                    id = "playlist_tracks_${playlist.id}",
                    title = "Tracks",
                    list = tracks
                )
            )
        }
        
        return shelves.toFeed()
    }

    //PlaylistEditClient Implement

    override suspend fun createPlaylist(
        title: String,
        description: String?
    ): Playlist {
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
        val createResult = authState.CreateAccountPlaylist.createAccountPlaylist(
            title = title,
            description = description ?: ""
        )
        
        if (createResult.isFailure) {
            throw createResult.exceptionOrNull() ?: Exception("Failed to create playlist")
        }
        
        val playlistId = createResult.getOrThrow()
        
        val loadResult = youtubeApi.LoadPlaylist.loadPlaylist(playlistId)
        if (loadResult.isFailure) {
            return Playlist(
                id = playlistId,
                title = title,
                isEditable = true,
                isPrivate = false,
                description = description,
                extras = mapOf(
                    "created" to "true",
                    "creationTime" to System.currentTimeMillis().toString()
                )
            )
        }
        
        val ytmPlaylist = loadResult.getOrThrow()
        return converter.convertPlaylistToEchoPlaylist(ytmPlaylist)
    }
    
    override suspend fun addTracksToPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        index: Int,
        new: List<Track>
    ) {
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
        val trackIds = new.map { it.id }
        
        val addResult = authState.AccountPlaylistAddSongs.addSongs(
            playlist_id = playlist.id,
            song_ids = trackIds
        )
        
        if (addResult.isFailure) {
            throw addResult.exceptionOrNull() ?: Exception("Failed to add tracks to playlist")
        }
    }
    
    override suspend fun deletePlaylist(playlist: Playlist) {
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
        val deleteResult = authState.DeleteAccountPlaylist.deleteAccountPlaylist(playlist.id)
        
        if (deleteResult.isFailure) {
            throw deleteResult.exceptionOrNull() ?: Exception("Failed to delete playlist")
        }
    }
    
    override suspend fun editPlaylistMetadata(
        playlist: Playlist,
        title: String,
        description: String?
    ) {
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
        val editor = authState.AccountPlaylistEditor.getEditor(
            playlist_id = playlist.id,
            item_ids = emptyList(),
            item_set_ids = emptyList()
        )
        
        val actions = mutableListOf<dev.toastbits.ytmkt.model.external.PlaylistEditor.Action>()
        actions.add(dev.toastbits.ytmkt.model.external.PlaylistEditor.Action.SetTitle(title))
        
        if (description != null) {
            actions.add(dev.toastbits.ytmkt.model.external.PlaylistEditor.Action.SetDescription(description))
        }
        
        val updateResult = editor.performAndCommitActions(actions)
        
        if (updateResult.isFailure) {
            throw updateResult.exceptionOrNull() ?: Exception("Failed to update playlist metadata")
        }
    }
    
    override suspend fun listEditablePlaylists(
        track: Track?
    ): List<Pair<Playlist, Boolean>> {
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
        val playlistsResult = authState.AccountPlaylists.getAccountPlaylists()
        
        if (playlistsResult.isFailure) {
            throw playlistsResult.exceptionOrNull() ?: Exception("Failed to load editable playlists")
        }
        
        val ytmPlaylists = playlistsResult.getOrThrow()
        val echoPlaylists = ytmPlaylists.map { ytmPlaylist ->
            val echoPlaylist = converter.convertPlaylistToEchoPlaylist(ytmPlaylist)
            Pair(echoPlaylist, false)
        }
        
        return echoPlaylists
    }
    
    override suspend fun moveTrackInPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        fromIndex: Int,
        toIndex: Int
    ) {
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
        val feed = loadTracks(playlist)
        val feedData = feed.getPagedData.invoke(null)
        val page = feedData.pagedData.loadPage(null)
        val playlistTracks = page.data
        val itemIds = playlistTracks.map { track: Track -> track.id }
        val setIds = playlistTracks.map { track: Track -> 
            val setId = track.extras["setVideoId"] ?: ""
            setId
        }.filter { setId: String -> setId.isNotEmpty() }
        
        val editor = authState.AccountPlaylistEditor.getEditor(
            playlist_id = playlist.id,
            item_ids = itemIds,
            item_set_ids = setIds
        )
        
        val actions = listOf(
            dev.toastbits.ytmkt.model.external.PlaylistEditor.Action.Move(
                from = fromIndex,
                to = toIndex
            )
        )
        
        val moveResult = editor.performAndCommitActions(actions)
        
        if (moveResult.isFailure) {
            throw moveResult.exceptionOrNull() ?: Exception("Failed to move track in playlist")
        }
    }
    
    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        indexes: List<Int>
    ) {
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
        val feed = loadTracks(playlist)
        val feedData = feed.getPagedData.invoke(null)
        val page = feedData.pagedData.loadPage(null)
        val playlistTracks = page.data
        val itemIds = playlistTracks.map { track: Track -> track.id }
        val setIds = playlistTracks.map { track: Track -> 
            val setId = track.extras["setVideoId"] ?: ""
            setId
        }.filter { setId: String -> setId.isNotEmpty() }
        
        val editor = authState.AccountPlaylistEditor.getEditor(
            playlist_id = playlist.id,
            item_ids = itemIds,
            item_set_ids = setIds
        )
        
        val actions = indexes.map { index: Int ->
            dev.toastbits.ytmkt.model.external.PlaylistEditor.Action.Remove(index)
        }
        
        val removeResult = editor.performAndCommitActions(actions)
        
        if (removeResult.isFailure) {
            throw removeResult.exceptionOrNull() ?: Exception("Failed to remove tracks from playlist")
        }
    }

    //RadioClient Implement

    override suspend fun radio(
        item: EchoMediaItem,
        context: EchoMediaItem?
    ): Radio {
        return when (item) {
            is Track -> {
                Radio(
                    id = "radio_track_${item.id}",
                    title = "Radio based on ${item.title}",
                    cover = item.cover ?: context?.cover, 
                    authors = item.artists,
                    description = "Radio playlist based on ${item.title}",
                    subtitle = "Similar to ${item.title}",
                    extras = mapOf(
                        "sourceType" to "track",
                        "sourceId" to item.id
                    )
                )
            }
            is Album -> {
                Radio(
                    id = "radio_album_${item.id}",
                    title = "Radio based on ${item.title}",
                    cover = item.cover ?: context?.cover, 
                    authors = item.artists,
                    description = "Radio playlist based on ${item.title}",
                    subtitle = "Similar to ${item.title}",
                    extras = mapOf(
                        "sourceType" to "album",
                        "sourceId" to item.id
                    )
                )
            }
            is Artist -> {
                Radio(
                    id = "radio_artist_${item.id}",
                    title = "${item.name} Radio",
                    cover = item.cover ?: context?.cover, 
                    authors = listOf(item),
                    description = "Radio playlist based on ${item.name}",
                    subtitle = "Similar to ${item.name}",
                    extras = mapOf(
                        "sourceType" to "artist",
                        "sourceId" to item.id
                    )
                )
            }
            is Playlist -> {
                Radio(
                    id = "radio_playlist_${item.id}",
                    title = "Radio based on ${item.title}",
                    cover = item.cover ?: context?.cover,
                    authors = item.authors,
                    description = "Radio playlist based on ${item.title}",
                    subtitle = "Similar to ${item.title}",
                    extras = mapOf(
                        "sourceType" to "playlist",
                        "sourceId" to item.id
                    )
                )
            }
            else -> {
                throw Exception("Radio not supported for this item type")
            }
        }
    }

    suspend fun createCustomRadio(
        artists: Set<String> = emptySet(),
        variety: RadioBuilderModifier.Variety = RadioBuilderModifier.Variety.MEDIUM,
        selectionType: RadioBuilderModifier.SelectionType = RadioBuilderModifier.SelectionType.BLEND,
        filterA: RadioBuilderModifier.FilterA? = null,
        filterB: RadioBuilderModifier.FilterB? = null
    ): Radio {
        try {
            val radioBuilderArtists = youtubeApi.RadioBuilder.getRadioBuilderArtists { thumbnails ->
                thumbnails.maxByOrNull { it.width * it.height } ?: thumbnails.firstOrNull() ?: throw Exception("No thumbnails available")
            }.getOrThrow()
            
            val selectedArtists = if (artists.isNotEmpty()) {
                radioBuilderArtists.filter { artist -> 
                    artists.any { name -> 
                        artist.name.contains(name, ignoreCase = true) 
                    } 
                }.toSet()
            } else {
                radioBuilderArtists.toSet()
            }
            
            if (selectedArtists.isEmpty()) {
                throw Exception("No matching artists found for radio creation")
            }
            
            val modifiers = mutableSetOf<RadioBuilderModifier?>()
            modifiers.add(variety)
            modifiers.add(selectionType)
            filterA?.let { modifiers.add(it) }
            filterB?.let { modifiers.add(it) }
            
            val radioToken = youtubeApi.RadioBuilder.buildRadioToken(selectedArtists, modifiers)
            
            val builtRadio = youtubeApi.RadioBuilder.getBuiltRadio(radioToken).getOrThrow()
                ?: throw Exception("Failed to create custom radio")
            
            val convertedPlaylist = converter.convertPlaylistToEchoPlaylist(builtRadio)
            
            return Radio(
                id = convertedPlaylist.id,
                title = convertedPlaylist.title,
                cover = convertedPlaylist.cover,
                authors = convertedPlaylist.authors,
                trackCount = convertedPlaylist.trackCount,
                description = convertedPlaylist.description,
                subtitle = convertedPlaylist.subtitle,
                extras = convertedPlaylist.extras + mapOf(
                    "sourceType" to "custom_radio",
                    "radioToken" to radioToken
                )
            )
        } catch (e: Exception) {
            println("Failed to create custom radio: ${e.message}")
            throw e
        }
    }

    override suspend fun loadRadio(radio: Radio): Radio {
        val sourceType = radio.extras["sourceType"]
        val sourceId = radio.extras["sourceId"]
        val radioToken = radio.extras["radioToken"]
        
        if (sourceType == null) {
            return radio 
        }
        
        try {
            when (sourceType) {
                "track" -> {
                    if (sourceId != null) {
                        val radioResult = youtubeApi.SongRadio.getSongRadio(sourceId, null)
                        if (radioResult.isSuccess) {
                            return radio
                        }
                    }
                }
                "artist" -> {
                    if (sourceId != null) {
                        val radioResult = youtubeApi.ArtistRadio.getArtistRadio(sourceId, null)
                        if (radioResult.isSuccess) {
                            return radio
                        }
                    }
                }
                "playlist", "album" -> {
                    if (sourceId != null) {
                        val playlistResult = youtubeApi.LoadPlaylist.loadPlaylist(sourceId)
                        if (playlistResult.isSuccess) {
                            val playlist = playlistResult.getOrThrow()
                            val convertedPlaylist = converter.convertPlaylistToEchoPlaylist(playlist)
                            return radio.copy(
                                title = convertedPlaylist.title,
                                cover = convertedPlaylist.cover ?: radio.cover,
                                authors = convertedPlaylist.authors,
                                description = convertedPlaylist.description ?: radio.description,
                                subtitle = convertedPlaylist.subtitle ?: radio.subtitle
                            )
                        }
                    }
                }
                "custom_radio" -> {
                    if (radioToken != null) {
                        val playlistResult = youtubeApi.LoadPlaylist.loadPlaylist(radioToken)
                        if (playlistResult.isSuccess) {
                            val playlist = playlistResult.getOrThrow()
                            val convertedPlaylist = converter.convertPlaylistToEchoPlaylist(playlist)
                            return radio.copy(
                                title = convertedPlaylist.title,
                                cover = convertedPlaylist.cover ?: radio.cover,
                                authors = convertedPlaylist.authors,
                                description = convertedPlaylist.description ?: radio.description,
                                subtitle = convertedPlaylist.subtitle ?: radio.subtitle
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to load enhanced radio details: ${e.message}")
        }
        
        return radio
    }

    override suspend fun loadTracks(radio: Radio): Feed<Track> {
        val sourceType = radio.extras["sourceType"]
        val sourceId = radio.extras["sourceId"]
        val radioToken = radio.extras["radioToken"]
        
        if (sourceType == null || (sourceId == null && radioToken == null)) {
            throw Exception("Invalid radio source")
        }
        
        return when (sourceType) {
            "track" -> {
                try {
                    val radioResult = youtubeApi.SongRadio.getSongRadio(sourceId!!, null)
                    if (radioResult.isSuccess) {
                        val radioData = radioResult.getOrThrow()
                        val tracks = radioData.items.map { song ->
                            converter.convertSongToTrack(song)
                        }
                        
                        val validatedTracks = tracks.map { track ->
                            if (track.streamables.isEmpty()) {
                                try {
                                    loadTrack(track, false)
                                } catch (e: Exception) {
                                    println("Failed to load track details for ${track.id}: ${e.message}")
                                    track
                                }
                            } else {
                                track
                            }
                        }
                        
                        validatedTracks.toFeed()
                    } else {
                        throw radioResult.exceptionOrNull() ?: Exception("Failed to load radio")
                    }
                } catch (e: Exception) {
                    println("Failed to load track radio: ${e.message}")
                    emptyList<Track>().toFeed()
                }
            }
            "artist" -> {
                try {
                    val radioResult = youtubeApi.ArtistRadio.getArtistRadio(sourceId!!, null)
                    if (radioResult.isSuccess) {
                        val radioData = radioResult.getOrThrow()
                        val tracks = radioData.items.map { song ->
                            converter.convertSongToTrack(song)
                        }
                        
                        val validatedTracks = tracks.map { track ->
                            if (track.streamables.isEmpty()) {
                                try {
                                    loadTrack(track, false)
                                } catch (e: Exception) {
                                    println("Failed to load track details for ${track.id}: ${e.message}")
                                    track
                                }
                            } else {
                                track
                            }
                        }
                        
                        validatedTracks.toFeed()
                    } else {
                        throw radioResult.exceptionOrNull() ?: Exception("Failed to load radio")
                    }
                } catch (e: Exception) {
                    println("Failed to load artist radio: ${e.message}")
                    emptyList<Track>().toFeed()
                }
            }
            "playlist", "album" -> {
                try {
                    val playlistResult = youtubeApi.LoadPlaylist.loadPlaylist(sourceId!!)
                    if (playlistResult.isSuccess) {
                        val playlist = playlistResult.getOrThrow()
                        val playlistTracks = playlist.items?.take(5)
                        
                        if (playlistTracks != null && playlistTracks.isNotEmpty()) {
                            val firstTrack = playlistTracks.first()
                            val radioResult = youtubeApi.SongRadio.getSongRadio(firstTrack.id, null)
                            if (radioResult.isSuccess) {
                                val radioData = radioResult.getOrThrow()
                                val tracks = radioData.items.map { song ->
                                    converter.convertSongToTrack(song)
                                }
                                
                                val validatedTracks = tracks.map { track ->
                                    if (track.streamables.isEmpty()) {
                                        try {
                                            loadTrack(track, false)
                                        } catch (e: Exception) {
                                            println("Failed to load track details for ${track.id}: ${e.message}")
                                            track
                                        }
                                    } else {
                                        track
                                    }
                                }
                                
                                validatedTracks.toFeed()
                            } else {
                                throw radioResult.exceptionOrNull() ?: Exception("Failed to load radio")
                            }
                        } else {
                            emptyList<Track>().toFeed()
                        }
                    } else {
                        throw playlistResult.exceptionOrNull() ?: Exception("Failed to load playlist")
                    }
                } catch (e: Exception) {
                    println("Failed to load playlist/album radio: ${e.message}")
                    emptyList<Track>().toFeed()
                }
            }
            "custom_radio" -> {
                try {
                    if (radioToken != null) {
                        val playlistResult = youtubeApi.LoadPlaylist.loadPlaylist(radioToken)
                        if (playlistResult.isSuccess) {
                            val playlist = playlistResult.getOrThrow()
                            val tracks = playlist.items?.map { song ->
                                converter.convertSongToTrack(song)
                            } ?: emptyList()
                            
                            val validatedTracks = tracks.map { track ->
                                if (track.streamables.isEmpty()) {
                                    try {
                                        loadTrack(track, false)
                                    } catch (e: Exception) {
                                        println("Failed to load track details for ${track.id}: ${e.message}")
                                        track
                                    }
                                } else {
                                    track
                                }
                            }
                            
                            validatedTracks.toFeed()
                        } else {
                            throw playlistResult.exceptionOrNull() ?: Exception("Failed to load custom radio")
                        }
                    } else {
                        emptyList<Track>().toFeed()
                    }
                } catch (e: Exception) {
                    println("Failed to load custom radio: ${e.message}")
                    emptyList<Track>().toFeed()
                }
            }
            else -> {
                emptyList<Track>().toFeed()
            }
        }
    }

    //Follow Implement

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        val authState = youtubeApi.user_auth_state
            ?: throw ClientException.LoginRequired()
        
        val artist = item as? Artist ?: throw Exception("Only artists can be followed")
        
        val result = authState.SetSubscribedToArtist.setSubscribedToArtist(
            artist_id = artist.id,
            subscribed = shouldFollow
        )
        
        if (result.isFailure) {
            throw result.exceptionOrNull() ?: Exception("Failed to ${if (shouldFollow) "follow" else "unfollow"} artist")
        }
    }

    override suspend fun isFollowing(item: EchoMediaItem): Boolean {
        val authState = youtubeApi.user_auth_state
            ?: return false
        
        val artist = item as? Artist ?: return false
        
        return try {
            val result = authState.SubscribedToArtist.isSubscribedToArtist(artist.id)
            result.getOrNull() ?: false
        } catch (e: Exception) {
            println("Failed to check if artist is followed: ${e.message}")
            false
        }
    }

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? {
        return null
    }

}

private suspend fun io.ktor.client.statement.HttpResponse.parseAccountSwitcherResponse(cookie: String, auth: String): List<User> {
    try {
        val responseText = this.bodyAsText()
        val json = Json.decodeFromString(JsonObject.serializer(), responseText)
        
        val contents = json["contents"]?.jsonObject?.get("accountSectionListRenderer")?.jsonObject?.get("contents")?.jsonArray
        val activeAccount = contents?.get(0)?.jsonObject?.get("accountItem")?.jsonObject
        
        if (activeAccount != null) {
            val accountName = activeAccount["accountName"]?.jsonObject?.get("simpleText")?.jsonPrimitive?.content ?: "YouTube Music User"
            val accountId = activeAccount["accountId"]?.jsonPrimitive?.content ?: ""
            val channelHandle = activeAccount["channelHandle"]?.jsonPrimitive?.content ?: ""
            
            val email = if (accountName.contains("@")) accountName else ""
            
            var cover: ImageHolder? = null
            try {
                val thumbnails = activeAccount["accountPhoto"]?.jsonObject?.get("thumbnails")?.jsonArray
                val bestThumbnail = thumbnails?.maxByOrNull { 
                    val width = it.jsonObject["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val height = it.jsonObject["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    width * height
                }
                val thumbnailUrl = bestThumbnail?.jsonObject?.get("url")?.jsonPrimitive?.content
                if (thumbnailUrl != null) {
                    cover = thumbnailUrl.toImageHolder()
                }
            } catch (e: Exception) {
                println("Failed to extract profile image: ${e.message}")
            }
            
            return listOf(
                User(
                    id = accountId,
                    name = accountName,
                    cover = cover,
                    subtitle = if (channelHandle.isNotEmpty()) channelHandle else if (email.isNotEmpty()) email else "YouTube Music Account",
                    extras = mapOf(
                        "cookie" to cookie,
                        "auth" to auth,
                        "accountId" to accountId,
                        "channelHandle" to channelHandle,
                        "email" to email,
                        "isLoggedIn" to "true",
                        "userService" to "youtube_music",
                        "accountType" to "google"
                    )
                )
            )
        }
        
        return listOf(
            User(
                id = "",
                name = "YouTube Music User",
                subtitle = "Logged in via Google",
                extras = mapOf(
                    "cookie" to cookie,
                    "auth" to auth,
                    "isLoggedIn" to "true",
                    "userService" to "youtube_music",
                    "accountType" to "google"
                )
            )
        )
    } catch (e: Exception) {
        println("Failed to parse account switcher response: ${e.message}")
        return listOf(
            User(
                id = "",
                name = "YouTube Music User",
                subtitle = "Logged in via Google",
                extras = mapOf(
                    "cookie" to cookie,
                    "auth" to auth,
                    "isLoggedIn" to "true",
                    "userService" to "youtube_music",
                    "accountType" to "google"
                )
            )
        )
    }
}
