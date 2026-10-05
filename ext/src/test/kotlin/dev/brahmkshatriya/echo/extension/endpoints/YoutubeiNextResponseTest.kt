package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Lyrics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeiNextResponseTest {
    @Test
    fun tabContentWithoutMusicQueueRendererDeserializes() {
        val content = Json.decodeFromString<YoutubeiNextResponse.Content>("{}")

        assertNull(content.musicQueueRenderer)
    }

    @Test
    fun timedLyricsAreConvertedToEchoLyrics() {
        val response = Json.decodeFromString<LyricsResponse>(
            """
            {"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{"timedLyricsData":[{"lyricLine":"Hello","cueRange":{"startTimeMilliseconds":"0","endTimeMilliseconds":"1500"}}]}}}}}}}}}
            """.trimIndent()
        )

        val lyrics = response.toEchoLyrics()?.lyrics as Lyrics.Timed
        assertEquals("Hello", lyrics.list.single().text)
        assertEquals(0L, lyrics.list.single().startTime)
        assertEquals(1500L, lyrics.list.single().endTime)
    }

    @Test
    fun staticLyricsAreConvertedToEchoLyrics() {
        val response = Json.decodeFromString<LyricsResponse>(
            """
            {"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Plain lyrics"}]}}}]}}}
            """.trimIndent()
        )

        val lyrics = response.toEchoLyrics()?.lyrics as Lyrics.Simple
        assertEquals("Plain lyrics", lyrics.text)
    }

    @Test
    fun lyricsUnavailableMessageReturnsNoLyrics() {
        val response = Json.decodeFromString<LyricsResponse>(
            """
            {"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"musicMessageModel":{"text":"Lyrics unavailable"}}}}}}}}
            """.trimIndent()
        )

        assertNull(response.toEchoLyrics())
    }

    @Test
    fun missingDefaultLyricsFallsBackToAndroidMusicLyrics() = runBlocking {
        var androidMusicRequestMade = false
        val lyrics = getLyricsWithFallback(
            requestDefaultLyrics = {
                Json.decodeFromString(
                    """{"contents":{"messageRenderer":{"text":{"runs":[{"text":"Lyrics unavailable"}]}}}}"""
                )
            },
            requestAndroidMusicLyrics = {
                androidMusicRequestMade = true
                Json.decodeFromString(
                    """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{"timedLyricsData":[{"lyricLine":"Synced line","cueRange":{"startTimeMilliseconds":"0","endTimeMilliseconds":"1000"}}]}}}}}}}}}"""
                )
            }
        )

        assertTrue(androidMusicRequestMade)
        assertEquals("Synced line", (lyrics?.lyrics as Lyrics.Timed).list.single().text)
    }

    @Test
    fun defaultStaticLyricsDoNotMakeAndroidMusicRequest() = runBlocking {
        var androidMusicRequestMade = false
        val lyrics = getLyricsWithFallback(
            requestDefaultLyrics = {
                Json.decodeFromString(
                    """{"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Plain lyrics"}]}}}]}}}"""
                )
            },
            requestAndroidMusicLyrics = {
                androidMusicRequestMade = true
                error("Android Music fallback should not be used when default lyrics exist")
            }
        )

        assertFalse(androidMusicRequestMade)
        assertEquals("Plain lyrics", (lyrics?.lyrics as Lyrics.Simple).text)
    }
}
