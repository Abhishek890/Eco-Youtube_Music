package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.ArtistNameResolver
import dev.brahmkshatriya.echo.extension.toTrack
import dev.brahmkshatriya.echo.extension.usableArtistName
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider

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
        val ytmTrack = runCatching {
            api.LoadSong.loadSong(trackId).getOrThrow().toTrack(
                thumbnailQuality,
                artistNameResolver,
                knownArtistNames = nameHints
            )
        }.getOrNull()
        
        if (ytmTrack != null) {
            // Check if we need legacy data for missing extras (lyricsId, relatedId, isLiked)
            val needsLegacyExtras = ytmTrack.extras["lyricsId"] == null || 
                                     ytmTrack.extras["relatedId"] == null ||
                                     ytmTrack.extras["isLiked"] == null
            
            if (needsLegacyExtras) {
                println("ytm-kt track missing extras, fetching from legacy endpoint")
                val legacyTrack = runCatching {
                    echoSongEndpoint.loadSong(trackId).getOrThrow()
                }.getOrNull()
                
                val mergedExtras = buildMergedExtras(ytmTrack, legacyTrack, trackId, fallbackTrack)
                return mergeWithYtmPriority(ytmTrack, legacyTrack, fallbackTrack, mergedExtras)
            } else {
                println("ytm-kt track has all required extras, skipping legacy fetch")
                val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
                return mergeWithYtmPriority(ytmTrack, null, fallbackTrack, mergedExtras)
            }
        }
        
        // Fallback to legacy if ytm-kt failed
        println("ytm-kt failed, trying legacy endpoint")
        val legacyTrack = runCatching {
            echoSongEndpoint.loadSong(trackId).getOrThrow()
        }.getOrNull()
        
        val mergedExtras = buildMergedExtras(null, legacyTrack, trackId, fallbackTrack)
        
        return when {
            legacyTrack != null -> mergeWithLegacyPriority(legacyTrack, fallbackTrack, mergedExtras)
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
        mergedExtras: Map<String, String>
    ): Track {
        // ytmTrack from ytm-kt NEVER has streamables, so we must create them
        val streamables = if (ytmTrack.streamables.isNotEmpty()) {
            ytmTrack.streamables
        } else {
            createDefaultStreamable(mergedExtras["videoId"]!!)
        }
        
        return ytmTrack.copy(
            // Prefer ytm cover, fallback to original then legacy
            cover = ytmTrack.cover ?: fallbackTrack.cover ?: legacyTrack?.cover,
            
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
        mergedExtras: Map<String, String>
    ): Track {
        return legacyTrack.copy(
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
}
