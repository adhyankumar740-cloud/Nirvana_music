package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Track
import com.example.data.repository.MusicRepository
import com.example.player.MusicPlayer
import com.example.player.SamplesPlayerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SamplesViewModel(
    private val repository: MusicRepository,
    val playerManager: SamplesPlayerManager,
    private val musicPlayer: MusicPlayer
) : ViewModel() {

    private val _samples = MutableStateFlow<List<Track>>(emptyList())
    val samples = _samples.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    // True while we're looking up the matching YouTube video for "Play Full Song".
    private val _isResolvingFullSong = MutableStateFlow(false)
    val isResolvingFullSong = _isResolvingFullSong.asStateFlow()

    init {
        loadSamples()
    }

    fun loadSamples() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getSamplesFeed("trending hit").collectLatest { tracks ->
                _samples.value = tracks
                _isLoading.value = false

                // Play first sample automatically
                if (tracks.isNotEmpty()) {
                    playSampleAtIndex(0)
                }
            }
        }
    }

    fun onSwipe(newIndex: Int) {
        if (newIndex in _samples.value.indices) {
            _currentIndex.value = newIndex
            playSampleAtIndex(newIndex)
        }
    }

    private fun playSampleAtIndex(index: Int) {
        val tracksList = _samples.value
        if (tracksList.isEmpty() || index !in tracksList.indices) return

        val currentTrack = tracksList[index]
        val nextTrack = if (index + 1 in tracksList.indices) tracksList[index + 1] else null
        playerManager.playTrack(currentTrack, nextTrack)
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            repository.toggleFavorite(track)
            _samples.value = _samples.value.map {
                if (it.id == track.id) it.copy(isFavorite = !it.isFavorite) else it
            }
        }
    }

    fun toggleDownload(track: Track) {
        viewModelScope.launch {
            repository.toggleDownload(track)
            _samples.value = _samples.value.map {
                if (it.id == track.id) it.copy(isDownloaded = !it.isDownloaded) else it
            }
        }
    }

    /**
     * "Play Full Song" button - finds the best-matching YouTube video for this
     * sample's title/artist and starts it in the app's own player (small,
     * always-visible embedded YouTube view - see YouTubePlayerHost). No longer
     * launches the external YouTube app.
     */
    fun playFullSong(track: Track) {
        viewModelScope.launch {
            _isResolvingFullSong.value = true
            playerManager.pause()
            val match = repository.findBestYouTubeMatch(track.title, track.artist)
            _isResolvingFullSong.value = false
            if (match != null) {
                musicPlayer.play(match)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.stop()
    }

    class Factory(
        private val repository: MusicRepository,
        private val playerManager: SamplesPlayerManager,
        private val musicPlayer: MusicPlayer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SamplesViewModel(repository, playerManager, musicPlayer) as T
        }
    }
}
