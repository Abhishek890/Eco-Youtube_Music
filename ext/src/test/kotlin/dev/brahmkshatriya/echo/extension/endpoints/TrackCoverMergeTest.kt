package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.ImageHolder
import org.junit.Assert.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TrackCoverMergeTest {
    @Test
    fun `uses refreshed cover when it is available`() = runBlocking {
        val original = ImageHolder.HexColorImageHolder("#112233")
        val refreshed = ImageHolder.HexColorImageHolder("#445566")

        assertSame(refreshed, EchoEnhancedSongEndpoint.choosePlaybackCover(
            listOf(refreshed), original
        ) { true })
    }

    @Test
    fun `tries lower quality refreshed cover before original when preferred image is unavailable`() = runBlocking {
        val high = ImageHolder.HexColorImageHolder("#112233")
        val refreshed = ImageHolder.HexColorImageHolder("#445566")
        val original = ImageHolder.HexColorImageHolder("#778899")

        assertSame(refreshed, EchoEnhancedSongEndpoint.choosePlaybackCover(
            listOf(high, refreshed), original
        ) { it === refreshed })
    }

    @Test
    fun `falls back to original when all refreshed images are unavailable`() = runBlocking {
        val original = ImageHolder.HexColorImageHolder("#778899")

        assertSame(original, EchoEnhancedSongEndpoint.choosePlaybackCover(
            listOf(ImageHolder.HexColorImageHolder("#112233")), original
        ) { false })
    }
}
