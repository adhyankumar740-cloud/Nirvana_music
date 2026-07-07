package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.Track
import com.example.data.repository.MusicRepository
import com.example.player.MusicPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MusicViewModel(
    private val repository: MusicRepository,
    val player: MusicPlayer
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _homeTracks = MutableStateFlow<List<Track>>(emptyList())
    val homeTracks = _homeTracks.asStateFlow()

    private val _selectedTab = MutableStateFlow("home")
    val selectedTab = _selectedTab.asStateFlow()

    // Observe Saved/Downloaded/Favorite Tracks from database
    val favoriteTracks: StateFlow<List<Track>> = repository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedTracks: StateFlow<List<Track>> = repository.getDownloadedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val libraryTracks: StateFlow<List<Track>> = repository.getSavedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Load initial home screen content
        fetchHomeRecommendations()
    }

    fun selectTab(tab: String) {
        _selectedTab.value = tab
    }

    fun fetchHomeRecommendations() {
        viewModelScope.launch {
            repository.getHomeFeaturedTracks("chill lofi").collectLatest { tracks ->
                if (tracks.isNotEmpty()) {
                    _homeTracks.value = tracks
                } else {
                    // Fallback mock tracks if network offline
                    _homeTracks.value = getLocalFallbackTracks()
                }
            }
        }
    }

    fun search(query: String) {
        _searchQuery.value = query
        if (query.trim().isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            repository.searchTracks(query).collectLatest { results ->
                _searchResults.value = results
                _isSearching.value = false
            }
        }
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            repository.toggleFavorite(track)
            // Re-enrich search results and home tracks with local updates
            updateTrackStates()
        }
    }

    fun toggleDownload(track: Track) {
        viewModelScope.launch {
            repository.toggleDownload(track)
            updateTrackStates()
        }
    }

    private suspend fun updateTrackStates() {
        _homeTracks.value = _homeTracks.value.map { track ->
            track.copy(
                isFavorite = repository.isTrackFavorite(track.id),
                isDownloaded = repository.isTrackDownloaded(track.id)
            )
        }
        _searchResults.value = _searchResults.value.map { track ->
            track.copy(
                isFavorite = repository.isTrackFavorite(track.id),
                isDownloaded = repository.isTrackDownloaded(track.id)
            )
        }
    }

    fun playTrack(track: Track, tracksList: List<Track>) {
        player.setQueue(tracksList, tracksList.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    private fun getLocalFallbackTracks(): List<Track> {
        return listOf(
            Track(1, "Golden Hour", "JVKE", "Golden Hour", "", "https://picsum.photos/300/300?random=1", 180000, "Pop"),
            Track(2, "Midnight City", "M83", "Hurry Up, We're Dreaming", "", "https://picsum.photos/300/300?random=2", 240000, "Electronic"),
            Track(3, "Sweater Weather", "The Neighbourhood", "I Love You", "", "https://picsum.photos/300/300?random=3", 200000, "Alternative")
        )
    }

    class Factory(
        private val repository: MusicRepository,
        private val player: MusicPlayer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MusicViewModel(repository, player) as T
        }
    }
}
