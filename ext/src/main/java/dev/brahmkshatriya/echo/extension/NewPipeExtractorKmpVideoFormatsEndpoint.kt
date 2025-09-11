package dev.brahmkshatriya.echo.extension

import com.yushosei.newpipe.extractor.NewPipe
import com.yushosei.newpipe.extractor.stream.StreamInfo
import com.yushosei.newpipe.extractor.stream.AudioStream
import com.yushosei.newpipe.util.DefaultDownloaderImpl
import com.yushosei.newpipe.util.ExtractorHelper
import dev.toastbits.ytmkt.model.YtmApi
import dev.toastbits.ytmkt.model.external.YoutubeVideoFormat

class NewPipeExtractorKmpVideoFormatsEndpoint(private val api: YtmApi) {
    private var initialised: Boolean = false

    suspend fun getVideoFormats(
        id: String,
        include_non_default: Boolean = true,
        filter: ((YoutubeVideoFormat) -> Boolean)? = null
    ): Result<List<YoutubeVideoFormat>> {
        return try {
            init()

            val videoId = if (id.startsWith("https://")) {
                extractVideoIdFromUrl(id)
            } else {
                id
            }
            
            val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
            println("NewPipeExtractorKmpVideoFormatsEndpoint: Converting ID '$id' to YouTube URL '$youtubeUrl'")

            val streamInfo: StreamInfo = ExtractorHelper.getStreamInfo(0, youtubeUrl)

            val audioStreams: List<YoutubeVideoFormat> = streamInfo.audioStreams
                .map { audioStream: AudioStream -> audioStream.toYoutubeVideoFormat() }
                .filter { format: YoutubeVideoFormat -> filter?.invoke(format) ?: true }

            Result.success(audioStreams)
        } catch (e: Exception) {
            println("NewPipeExtractorKmpVideoFormatsEndpoint: Error getting video formats: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun init() {
        if (initialised) {
            return
        }

        NewPipe.init(DefaultDownloaderImpl())

        initialised = true
    }
}

private fun AudioStream.toYoutubeVideoFormat(): YoutubeVideoFormat {
    return YoutubeVideoFormat(itag, format?.mimeType ?: "audio/unknown", bitrate, url = content)
}

private fun extractVideoIdFromUrl(url: String): String {
    val patterns = listOf(
        "v=([^&/#?]+)",  // Standard watch URL: https://www.youtube.com/watch?v=VIDEO_ID
        "youtu\\.be/([^&/#?]+)",  // Short URL: https://youtu.be/VIDEO_ID
        "embed/([^&/#?]+)"  // Embed URL: https://www.youtube.com/embed/VIDEO_ID
    )
    
    for (pattern in patterns) {
        val regex = Regex(pattern)
        val matchResult = regex.find(url)
        if (matchResult != null) {
            return matchResult.groupValues[1]
        }
    }
    
    return url
}