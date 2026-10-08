package dev.brahmkshatriya.echo.extension.providers.playlists

import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.brahmkshatriya.echo.extension.ArtistNameResolver
import dev.brahmkshatriya.echo.extension.endpoints.EchoEditPlaylistEndpoint
import dev.brahmkshatriya.echo.extension.toPlaylist
import dev.toastbits.ytmkt.model.external.PlaylistEditor
import dev.toastbits.ytmkt.model.external.ThumbnailProvider


class PlaylistManager(
    private val authManager: YouTubeAuthManager,
    private val editorEndpoint: EchoEditPlaylistEndpoint,
    private val thumbnailQuality: ThumbnailProvider.Quality,
    private val artistNameResolver: ArtistNameResolver
) {
    suspend fun createPlaylist(title: String, description: String?): Playlist {
        val auth = authManager.requireAuth()
        val playlistId = auth.CreateAccountPlaylist
            .createAccountPlaylist(title, description ?: "")
            .getOrThrow()

        return Playlist(
            id = playlistId,
            title = title,
            isEditable = true
        )
    }

    suspend fun deletePlaylist(playlist: Playlist) {
        val auth = authManager.requireAuth()
        auth.DeleteAccountPlaylist.deleteAccountPlaylist(playlist.id).getOrThrow()
    }

    suspend fun getEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        val auth = authManager.requireAuth()
        return auth.AccountPlaylists.getAccountPlaylists().getOrThrow().mapNotNull {
            if (it.id != "VLSE") {
                it.toPlaylist(thumbnailQuality, artistNameResolver) to false
            } else {
                null
            }
        }
    }

    suspend fun updatePlaylistMetadata(
        playlist: Playlist,
        title: String,
        description: String?
    ) {
        val auth = authManager.requireAuth()
        val editor = auth.AccountPlaylistEditor.getEditor(playlist.id, listOf(), listOf())
        
        editor.performAndCommitActions(
            listOfNotNull(
                PlaylistEditor.Action.SetTitle(title),
                description?.let { PlaylistEditor.Action.SetDescription(it) }
            )
        )
    }

    suspend fun removeTracks(
        playlist: Playlist,
        tracks: List<Track>,
        indexes: List<Int>
    ) {
        val actions = indexes.map { index ->
            val track = tracks[index]
            val setId = track.extras["setId"]
                ?: throw Exception("Track missing setId for removal")
            EchoEditPlaylistEndpoint.Action.Remove(track.id, setId)
        }
        editorEndpoint.editPlaylist(playlist.id, actions)
    }

    suspend fun addTracks(
        playlist: Playlist,
        tracks: List<Track>,
        index: Int,
        newTracks: List<Track>
    ) {
        val addActions = newTracks.map { track ->
            EchoEditPlaylistEndpoint.Action.Add(track.id)
        }
        
        val addResult = editorEndpoint.editPlaylist(playlist.id, addActions)
        
        val setIds = addResult.playlistEditResults
            ?.map { it.playlistEditVideoAddedResultData.setVideoId }
            ?: return

        val addBeforeTrack = tracks.getOrNull(index)?.extras?.get("setId") ?: return
        val moveActions = setIds.map { setId ->
            EchoEditPlaylistEndpoint.Action.Move(setId, addBeforeTrack)
        }
        
        editorEndpoint.editPlaylist(playlist.id, moveActions)
    }

    suspend fun moveTrack(
        playlist: Playlist,
        tracks: List<Track>,
        fromIndex: Int,
        toIndex: Int
    ) {
        val setId = tracks[fromIndex].extras["setId"]
            ?: throw Exception("Track missing setId for move")
        
        val before = if (fromIndex - toIndex > 0) 0 else 1
        val addBeforeTrack = tracks.getOrNull(toIndex + before)?.extras?.get("setId")
            ?: return

        editorEndpoint.editPlaylist(
            playlist.id,
            listOf(EchoEditPlaylistEndpoint.Action.Move(setId, addBeforeTrack))
        )
    }
}
