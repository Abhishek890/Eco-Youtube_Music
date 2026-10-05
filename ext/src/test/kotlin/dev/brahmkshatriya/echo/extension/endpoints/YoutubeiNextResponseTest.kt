package dev.brahmkshatriya.echo.extension.endpoints

import kotlinx.serialization.json.Json
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubeiNextResponseTest {
    @Test
    fun tabContentWithoutMusicQueueRendererDeserializes() {
        val content = Json.decodeFromString<YoutubeiNextResponse.Content>("{}")

        assertNull(content.musicQueueRenderer)
    }
}
