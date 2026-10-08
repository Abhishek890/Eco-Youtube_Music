package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.ImageHolder
import org.junit.Assert.assertSame
import org.junit.Test

class TrackCoverMergeTest {
    @Test
    fun `keeps cover already shown before playback reload`() {
        val original = ImageHolder.HexColorImageHolder("#112233")
        val refreshed = ImageHolder.HexColorImageHolder("#445566")

        assertSame(original, EchoEnhancedSongEndpoint.mergeCover(original, refreshed))
    }

    @Test
    fun `uses refreshed cover when original is missing`() {
        val refreshed = ImageHolder.HexColorImageHolder("#445566")

        assertSame(refreshed, EchoEnhancedSongEndpoint.mergeCover(null, refreshed))
    }

    @Test
    fun `uses legacy cover when original and refreshed covers are missing`() {
        val legacy = ImageHolder.HexColorImageHolder("#778899")

        assertSame(legacy, EchoEnhancedSongEndpoint.mergeCover(null, null, legacy))
    }
}
