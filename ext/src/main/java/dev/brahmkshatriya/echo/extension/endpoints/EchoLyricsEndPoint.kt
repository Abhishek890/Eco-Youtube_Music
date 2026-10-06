package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiPostBody
import dev.toastbits.ytmkt.model.ApiEndpoint
import io.ktor.client.call.body
import io.ktor.client.request.request
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class EchoLyricsEndPoint(override val api: YoutubeiApi) : ApiEndpoint() {
    internal suspend fun getLyrics(id: String): ParsedLyrics? =
        getLyricsWithFallback(
            requestDefaultLyrics = { requestLyrics(id, base = null) },
            requestAndroidMusicLyrics = { requestLyrics(id, androidMusicLyricsContext()) }
        )

    private fun androidMusicLyricsContext(): JsonObject = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("hl", api.dataLocale.language)
                put("platform", "MOBILE")
                put("clientName", "ANDROID_MUSIC")
                put("clientVersion", ANDROID_MUSIC_CLIENT_VERSION)
                put(
                    "userAgent",
                    "com.google.android.apps.youtube.music/$ANDROID_MUSIC_CLIENT_VERSION (Linux; U; Android 11) gzip"
                )
                put(
                    "acceptHeader",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
            }
            putJsonObject("user") {}
        }
    }

    private suspend fun requestLyrics(id: String, base: JsonObject?): LyricsResponse {
        val response = api.client.request {
            endpointPath("browse")
            addApiHeadersWithoutAuthentication()
            postWithBody(base) {
                put("browseId", id)
            }
        }
        return response.body()
    }
}

private const val ANDROID_MUSIC_CLIENT_VERSION = "7.21.50"

internal suspend fun getLyricsWithFallback(
    requestDefaultLyrics: suspend () -> LyricsResponse,
    requestAndroidMusicLyrics: suspend () -> LyricsResponse
): ParsedLyrics? {
    val defaultLyrics = try {
        requestDefaultLyrics().toEchoLyrics()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    if (defaultLyrics != null) return defaultLyrics
    return requestAndroidMusicLyrics().toEchoLyrics()
}

internal data class ParsedLyrics(
    val lyrics: Lyrics.Lyric,
    val subtitle: String? = null
)

internal fun LyricsResponse.toEchoLyrics(): ParsedLyrics? {
    val lyricsModel =
        contents?.elementRenderer?.newElement?.type?.componentType?.model

    lyricsModel?.timedLyricsModel?.lyricsData?.timedLyricsData
        ?.takeIf { it.isNotEmpty() }
        ?.let { timedLines ->
            return ParsedLyrics(
                lyrics = Lyrics.Timed(
                    timedLines.map { line ->
                        Lyrics.Item(
                            text = line.lyricLine,
                            startTime = line.cueRange.startTimeMilliseconds.toLong(),
                            endTime = line.cueRange.endTimeMilliseconds.toLong()
                        )
                    }
                ),
                subtitle = lyricsModel.musicMessageModel?.text
            )
        }

    contents?.sectionListRenderer?.contents
        ?.firstNotNullOfOrNull { it.musicDescriptionShelfRenderer?.description?.firstTextOrNull() }
        ?.takeIf { it.isNotBlank() }
        ?.let { return ParsedLyrics(Lyrics.Simple(it)) }

    val responseMessage = lyricsModel?.musicMessageModel?.text
        ?: contents?.messageRenderer?.text?.firstTextOrNull()

    if (!responseMessage.isNullOrBlank()) return null
    return null
}

@Serializable
data class LyricsResponse(
    val contents: Contents? = null
)

@Serializable
data class Contents(
    val elementRenderer: ElementRenderer? = null,
    val sectionListRenderer: LyricsSectionListRenderer? = null,
    val messageRenderer: LyricsMessageRenderer? = null
)

@Serializable
data class LyricsSectionListRenderer(val contents: List<LyricsShelf>?)

@Serializable
data class LyricsShelf(val musicDescriptionShelfRenderer: LyricsDescriptionShelfRenderer? = null)

@Serializable
data class LyricsDescriptionShelfRenderer(val description: LyricsText?)

@Serializable
data class LyricsMessageRenderer(val text: LyricsText?)

@Serializable
data class LyricsText(val runs: List<LyricsTextRun>?) {
    fun firstTextOrNull(): String? = runs?.firstOrNull()?.text
}

@Serializable
data class LyricsTextRun(val text: String?)

@Serializable
data class ElementRenderer(
    val trackingParams: String? = null,
    val newElement: NewElement? = null
)

@Serializable
data class NewElement(
    val type: Type? = null
)

@Serializable
data class Type(
    val componentType: ComponentType? = null
)

@Serializable
data class ComponentType(
    val model: Model? = null
)

@Serializable
data class Model(
    val timedLyricsModel: TimedLyricsModel? = null,
    val musicMessageModel: MusicMessageModel? = null
)

@Serializable
data class MusicMessageModel(val text: String? = null)

@Serializable
data class TimedLyricsModel(
    val lyricsData: LyricsData? = null
)

@Serializable
data class LyricsData(
    val timedLyricsData: List<TimedLyricsDatum>? = null,
    val sourceMessage: String? = null,
    val trackingParams: String? = null,
    val disableTapToSeek: Boolean? = null,
    val loggingCommand: Command? = null,
    val colorSamplePaletteEntityKey: String? = null,
    val backgroundImage: BackgroundImage? = null,
    val enableDirectUpdateProperties: Boolean? = null,
    val timedLyricsCommand: Command? = null,
    val collectionKey: String? = null
)

@Serializable
data class BackgroundImage(
    val sources: List<Source>? = null
)

@Serializable
data class Source(
    val url: String? = null
)

@Serializable
data class Command(
    val clickTrackingParams: String? = null,
    val logLyricEventCommand: LogLyricEventCommand? = null
)

@Serializable
data class LogLyricEventCommand(
    val serializedLyricInfo: String? = null
)

@Serializable
data class TimedLyricsDatum(
    val lyricLine: String,
    val cueRange: CueRange
)

@Serializable
data class CueRange(
    val startTimeMilliseconds: String,
    val endTimeMilliseconds: String,
    val metadata: Metadata? = null
)

@Serializable
data class Metadata(
    val id: String? = null
)
