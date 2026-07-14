package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Track
import com.example.data.repository.MusicRepository
import com.example.data.repository.Playlist
import com.example.data.repository.PlaylistImportResult
import com.example.player.MusicPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Library "Playlists" tab: create/rename/delete a playlist, add or
 * remove tracks, play a whole playlist through the app's shared [MusicPlayer]
 * queue, and import a playlist from a pasted song list (or M3U-style text).
 */
class PlaylistViewModel(
    private val repository: MusicRepository,
    private val musicPlayer: MusicPlayer
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Which playlist's detail view is open, if any (null = showing the list).
    private val _openPlaylistId = MutableStateFlow<Long?>(null)
    val openPlaylistId = _openPlaylistId.asStateFlow()

    // Tracks of whichever playlist is currently open - switches automatically
    // when openPlaylistId changes, and is empty while nothing is open.
    private val _openPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    val openPlaylistTracks = _openPlaylistTracks.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _importResult = MutableStateFlow<PlaylistImportResult?>(null)
    val importResult = _importResult.asStateFlow()

    init {
        viewModelScope.launch {
            _openPlaylistId.collectLatest { id ->
                if (id == null) {
                    _openPlaylistTracks.value = emptyList()
                } else {
                    repository.getPlaylistTracks(id).collectLatest { tracks ->
                        _openPlaylistTracks.value = tracks
                    }
                }
            }
        }
    }

    fun openPlaylist(playlistId: Long) {
        _openPlaylistId.value = playlistId
    }

    fun closePlaylist() {
        _openPlaylistId.value = null
    }

    fun createPlaylist(name: String, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.createPlaylist(name)
            onCreated(id)
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch { repository.renamePlaylist(playlistId, newName) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if (_openPlaylistId.value == playlistId) _openPlaylistId.value = null
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { repository.addTrackToPlaylist(playlistId, track) }
    }

    fun removeTrackFromPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { repository.removeTrackFromPlaylist(playlistId, track.id) }
    }

    /** Starts playback of the whole playlist, from [startTrack] if given, else from the top. */
    fun playPlaylist(tracks: List<Track>, startTrack: Track? = null) {
        if (tracks.isEmpty()) return
        val startIndex = startTrack?.let { t -> tracks.indexOfFirst { it.id == t.id } }?.coerceAtLeast(0) ?: 0
        musicPlayer.setQueue(tracks, startIndex)
    }

    /**
     * Starts playback of the whole playlist in shuffled order: turns the
     * player's global shuffle mode on (so Next/Previous keep shuffling
     * afterwards too, same as everywhere else in the app) and picks a random
     * starting track rather than always track 0.
     */
    fun shufflePlayPlaylist(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (!musicPlayer.isShuffleEnabled.value) {
            musicPlayer.toggleShuffle()
        }
        musicPlayer.setQueue(tracks, tracks.indices.random())
    }

    /**
     * Imports a playlist from pasted text (one song per line: "Title - Artist"
     * works best). Each line is resolved against the iTunes catalog; lines
     * that don't match anything are reported in the result rather than
     * silently dropped.
     */
    fun importPlaylist(name: String, rawText: String) {
        viewModelScope.launch {
            _isImporting.value = true
            _importResult.value = null
            val result = repository.importPlaylist(name, rawText)
            _importResult.value = result
            _isImporting.value = false
        }
    }

    fun dismissImportResult() {
        _importResult.value = null
    }

    class Factory(
        private val repository: MusicRepository,
        private val musicPlayer: MusicPlayer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlaylistViewModel(repository, musicPlayer) as T
        }
    }
}
