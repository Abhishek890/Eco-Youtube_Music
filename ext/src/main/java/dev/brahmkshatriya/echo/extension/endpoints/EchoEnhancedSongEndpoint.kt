package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.ArtistNameResolver
import dev.brahmkshatriya.echo.extension.toTrack
import dev.brahmkshatriya.echo.extension.usableArtistName
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Enhanced song endpoint that intelligently combines data from multiple sources.
 * Optimized to try ytm-kt first, then conditionally fetch legacy if needed.
 */
class EchoEnhancedSongEndpoint(
    private val api: YoutubeiApi,
    private val echoSongEndpoint: EchoSongEndPoint,
    private val artistNameResolver: ArtistNameResolver
) {
    /**
     * Load track data by combining ytm-kt LoadSong and custom EchoSongEndpoint.
     * Optimized to try ytm-kt first, then conditionally fetch legacy only if needed.
     * 
     * @param trackId YouTube video/song ID
     * @param fallbackTrack Original track for fallback data
     * @param thumbnailQuality Quality for thumbnail images
     * @return Enhanced Track with merged data from both sources
     */
    suspend fun loadEnhancedTrack(
        trackId: String, 
        fallbackTrack: Track,
        thumbnailQuality: ThumbnailProvider.Quality
    ): Track {
        println("EchoEnhancedSongEndpoint: Loading track $trackId, fallback isVideo=${fallbackTrack.extras["isVideo"]}")
        
        // Try ytm-kt first (faster, better quality data)
        val nameHints = ArtistNameResolver.namesFrom(fallbackTrack.artists)
        val ytmLoadedTrack = runCatching {
            val song = api.LoadSong.loadSong(trackId).getOrThrow()
            val track = song.toTrack(
                thumbnailQuality,
                artistNameResolver,
                knownArtistNames = nameHints
            )
            val covers = ThumbnailProvider.Quality.byQuality(thumbnailQuality)
                .mapNotNull { quality ->
                    song.thumbnail_provider?.getThumbnailUrl(quality)?.toImageHolder(crop = true)
                } + listOfNotNull(track.cover)
            YtmLoadedTrack(track, covers.distinctBy(::coverUrl))
        }.getOrNull()
        val ytmTrack = ytmLoadedTrack?.track
        
        if (ytmTrack != null) {
            // Check if we need legacy data for missing extras (lyricsId, relatedId, isLiked)
            val needsLegacyExtras = ytmTrack.extras["lyricsId"] == null || 
                                     ytmTrack.extras["relatedId"] == null ||
                                     ytmTrack.extras["isLiked"] == null
            
            if (needsLegacyExtras) {
                println("ytm-kt track missing extras, fetching from legacy endpoint")
                val legacyTrack = runCatching {
                    echoSongEndpoint.loadSong(trackId, thumbnailQuality).getOrThrow()
                }.getOrNull()
                
                val mergedExtras = buildMergedExtras(ytmTrack, legacyTrack, trackId, fallbackTrack)
                val cover = choosePlaybackCover(
                    ytmLoadedTrack.covers + listOfNotNull(legacyTrack?.cover),
                    fallbackTrack.cover
                )
                return mergeWithYtmPriority(ytmTrack, legacyTrack, fallbackTrack, mergedExtras, cover)
            } else {
                println("ytm-kt track has all required extras, skipping legacy fetch")
                val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
                val cover = choosePlaybackCover(ytmLoadedTrack.covers, fallbackTrack.cover)
                return mergeWithYtmPriority(ytmTrack, null, fallbackTrack, mergedExtras, cover)
            }
        }
        
        // Fallback to legacy if ytm-kt failed
        println("ytm-kt failed, trying legacy endpoint")
        val legacyTrack = runCatching {
            echoSongEndpoint.loadSong(trackId, thumbnailQuality).getOrThrow()
        }.getOrNull()
        
        val mergedExtras = buildMergedExtras(null, legacyTrack, trackId, fallbackTrack)
        
        return when {
            legacyTrack != null -> {
                val cover = choosePlaybackCover(listOfNotNull(legacyTrack.cover), fallbackTrack.cover)
                mergeWithLegacyPriority(legacyTrack, fallbackTrack, mergedExtras, cover)
            }
            else -> createFallbackTrack(fallbackTrack, mergedExtras, trackId)
        }
    }
    
    /**
     * Build merged extras map from all available sources.
     */
    private fun buildMergedExtras(ytmTrack: Track?, legacyTrack: Track?, trackId: String, fallbackTrack: Track? = null): MutableMap<String, String> {
        return mutableMapOf<String, String>().apply {
            // Add ytm extras first (lowest priority)
            ytmTrack?.extras?.let { 
                println("  ytm extras: $it")
                putAll(it) 
            }
            
            // Add legacy extras (medium priority - contains lyricsId, relatedId, isLiked)
            legacyTrack?.extras?.let { 
                println("  legacy extras: $it")
                putAll(it) 
            }
            
            // Add fallback extras last (HIGHEST priority - preserves isVideo!)
            fallbackTrack?.extras?.let { 
                println("  fallback extras: $it")
                putAll(it) 
            }
            
            // Ensure videoId is always present
            if (!containsKey("videoId")) {
                put("videoId", trackId)
            }
            
            println("  final merged isVideo=${get("isVideo")}")
        }
    }
    
    /**
     * Merge strategy when ytm-kt track is available (preferred source).
     * Falls back to legacy/original track for missing fields.
     */
    private fun mergeWithYtmPriority(
        ytmTrack: Track,
        legacyTrack: Track?,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        cover: ImageHolder?
    ): Track {
        // ytmTrack from ytm-kt NEVER has streamables, so we must create them
        val streamables = if (ytmTrack.streamables.isNotEmpty()) {
            ytmTrack.streamables
        } else {
            createDefaultStreamable(mergedExtras["videoId"]!!)
        }
        
        return ytmTrack.copy(
            cover = cover,
            
            // Prefer ytm album, fallback to legacy
            album = ytmTrack.album ?: legacyTrack?.album,
            
            artists = mergeArtistsById(ytmTrack.artists, legacyTrack?.artists, fallbackTrack.artists),
            
            // Add streamables - THIS WAS MISSING!
            streamables = streamables,
            
            // Use merged extras with all available metadata
            extras = mergedExtras
        )
    }
    
    /**
     * Merge strategy when only legacy track is available.
     * Ensures streamables are always present.
     */
    private fun mergeWithLegacyPriority(
        legacyTrack: Track,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        cover: ImageHolder?
    ): Track {
        return legacyTrack.copy(
            cover = cover,
            artists = mergeArtistsById(legacyTrack.artists, fallbackTrack.artists),
            extras = mergedExtras,
            streamables = legacyTrack.streamables.takeIf { it.isNotEmpty() } 
                ?: createDefaultStreamable(mergedExtras["videoId"]!!)
        )
    }
    
    /**
     * Create fallback track when both API calls fail.
     * Uses original track data with enhanced streamables.
     */
    private fun createFallbackTrack(
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        trackId: String
    ): Track {
        return fallbackTrack.copy(
            extras = mergedExtras,
            streamables = fallbackTrack.streamables.takeIf { it.isNotEmpty() }
                ?: createDefaultStreamable(trackId)
        )
    }
    
    /**
     * Create default streamable configuration.
     */
    private fun createDefaultStreamable(videoId: String): List<Streamable> {
        return listOf(
            Streamable.server(
                id = "youtube_music_$videoId",
                quality = 128,
                title = "YouTube Music",
                extras = mapOf("videoId" to videoId)
            )
        )
    }

    companion object {
        internal suspend fun choosePlaybackCover(
            refreshedCovers: List<ImageHolder>,
            originalCover: ImageHolder?,
            isAvailable: suspend (ImageHolder) -> Boolean
        ): ImageHolder? {
            for (cover in refreshedCovers) {
                if (isAvailable(cover)) return cover
            }
            return originalCover
        }

        internal fun mergeArtistsById(
            primary: List<Artist>,
            vararg fallbacks: List<Artist>?
        ): List<Artist> {
            val candidates = listOfNotNull(primary.takeIf { it.isNotEmpty() }) +
                fallbacks.filterNotNull().filter { it.isNotEmpty() }
            if (candidates.isEmpty()) return emptyList()

            // During playback, the original feed/search item is the user's known credit.
            // Prefer the latest named candidate so a later uploader ID cannot replace it.
            val selected = candidates.lastOrNull { candidate ->
                candidate.any { it.name.usableArtistName() != null }
            } ?: candidates.first()
            val namedById = buildMap {
                candidates.asReversed().flatten().forEach { artist ->
                    if (artist.id.isNotBlank()) {
                        artist.name.usableArtistName()?.let { putIfAbsent(artist.id, it) }
                    }
                }
            }
            return selected.map { artist ->
                if (artist.name.usableArtistName() != null) artist
                    else namedById[artist.id]?.let { artist.copy(name = it) } ?: artist
            }
        }
    }

    private suspend fun choosePlaybackCover(
        refreshedCovers: List<ImageHolder>,
        originalCover: ImageHolder?
    ): ImageHolder? {
        val originalUrl = originalCover?.let(::coverUrl)
        return withTimeoutOrNull(1_500) {
            choosePlaybackCover(refreshedCovers, originalCover) { cover ->
                coverUrl(cover) == originalUrl || isImageAvailable(cover)
            }
        } ?: originalCover
    }

    private suspend fun isImageAvailable(cover: ImageHolder): Boolean {
        val image = cover as? ImageHolder.NetworkRequestImageHolder ?: return true
        return try {
            val response = api.client.head(image.request.url)
            response.status.value in 200..299 && response.headers[HttpHeaders.ContentType]
                ?.substringBefore(';')?.startsWith("image/") == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private data class YtmLoadedTrack(val track: Track, val covers: List<ImageHolder>)

    private fun coverUrl(cover: ImageHolder): String? =
        (cover as? ImageHolder.NetworkRequestImageHolder)?.request?.url
}
