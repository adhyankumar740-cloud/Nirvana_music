package com.example.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.model.Song
import com.example.data.model.Track
import com.example.data.model.TrackSongBridge
import com.example.data.model.TrackSource
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RepeatMode { OFF, ONE, ALL } [cite: 1]

interface YouTubePlayerBridge { [cite: 4]
    fun loadVideo(videoId: String) [cite: 4]
    fun play() [cite: 4]
    fun pause() [cite: 4]
    fun seekTo(seconds: Float) [cite: 4]
}

class MusicPlayer(private val context: Context) { [cite: 8]

    private var mediaController: MediaController? = null [cite: 8, 9]
    private val pendingActions = mutableListOf<() -> Unit>() [cite: 9]

    var onLocalSongChange: ((Track) -> Unit)? = null [cite: 10, 11]
    var onLocalPlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null [cite: 11, 12]
    var onLocalSeek: ((positionMs: Long) -> Unit)? = null [cite: 12, 13]

    private var isApplyingRemote = false [cite: 13]
    private var suppressNextPlayPauseBroadcast = false [cite: 14]

    var youtubeBridge: YouTubePlayerBridge? = null [cite: 16]

    var autoplayProvider: (suspend (currentTrack: Track, excludeIds: Set<Long>, recentTracks: List<Track>) -> Track?)? = null [cite: 18, 19]

    private val _currentTrack = MutableStateFlow<Track?>(null) [cite: 19]
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow() [cite: 19]

    private val _isPlaying = MutableStateFlow(false) [cite: 19]
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow() [cite: 19]

    private val _playbackPosition = MutableStateFlow(0L) [cite: 19]
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow() [cite: 19]

    private val _duration = MutableStateFlow(0L) [cite: 19]
    val duration: StateFlow<Long> = _duration.asStateFlow() [cite: 19]

    private val _isBuffering = MutableStateFlow(false) [cite: 19]
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow() [cite: 19]

    private val _queue = MutableStateFlow<List<Track>>(emptyList()) [cite: 19]
    val queue: StateFlow<List<Track>> = _queue.asStateFlow() [cite: 19]

    private val _queueIndex = MutableStateFlow(-1) [cite: 20]
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow() [cite: 20]

    private val _isShuffleEnabled = MutableStateFlow(false) [cite: 20]
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow() [cite: 20]

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF) [cite: 20]
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow() [cite: 20]

    private val _isResolvingAutoplay = MutableStateFlow(false) [cite: 20]
    val isResolvingAutoplay: StateFlow<Boolean> = _isResolvingAutoplay.asStateFlow() [cite: 20]

    private val _playbackError = MutableStateFlow<String?>(null) [cite: 21]
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow() [cite: 21]

    private var hasReachedPlayingState = false [cite: 23]
    private var consecutivePlaybackFailures = 0 [cite: 23]
    private val maxConsecutivePlaybackFailures = 3 [cite: 23]

    private var playOrder: List<Int> = emptyList() [cite: 24]
    private var playOrderPos = 0 [cite: 24]

    private val recentlyPlayedIds = ArrayDeque<Long>() [cite: 24]
    private val recentlyPlayedTracks = ArrayDeque<Track>() [cite: 24]
    private val recentlyPlayedCap = 40 [cite: 24]

    private var loadedYoutubeVideoId: String? = null [cite: 26]

    private var bufferingWatchdogJob: Job? = null [cite: 27]
    private val bufferingTimeoutMs = 12_000L [cite: 27]

    private var progressJob: Job? = null [cite: 27, 28]
    private val scope = CoroutineScope(Dispatchers.Main + Job()) [cite: 28]

    init {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java)) [cite: 28]
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync() [cite: 28]
        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get() [cite: 28]
                mediaController?.addListener(playerListener) [cite: 28]
                pendingActions.forEach { it() } [cite: 29]
                pendingActions.clear() [cite: 29]
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Failed to connect MediaController", e) [cite: 29]
            }
        }, MoreExecutors.directExecutor()) [cite: 29]
    }

    private fun runOnController(action: (MediaController) -> Unit) { [cite: 29, 30]
        val controller = mediaController [cite: 30]
        if (controller != null) action(controller) else pendingActions.add { mediaController?.let(action) } [cite: 30]
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            if (_currentTrack.value?.source != TrackSource.ITUNES) return [cite: 30, 31]
            _isPlaying.value = isPlayingNow [cite: 31]
            
            if (isPlayingNow) startProgressTracker() else stopProgressTracker() [cite: 31]
            if (suppressNextPlayPauseBroadcast) {
                suppressNextPlayPauseBroadcast = false [cite: 31]
            } else {
                onLocalPlayPause?.invoke(isPlayingNow, _playbackPosition.value) [cite: 31]
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (_currentTrack.value?.source != TrackSource.ITUNES) return [cite: 32]
            when (state) {
                Player.STATE_BUFFERING -> _isBuffering.value = true [cite: 32]
                Player.STATE_READY -> {
                    _isBuffering.value = false [cite: 32]
                    _duration.value = (mediaController?.duration ?: 0L).coerceAtLeast(0L) [cite: 33]
                }
                Player.STATE_ENDED -> handleTrackEnded() [cite: 33]
                else -> {}
            }
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return [cite: 34]
        consecutivePlaybackFailures = 0 [cite: 34]
        _playbackError.value = null [cite: 34]
        _queue.value = tracks [cite: 34]
        rebuildPlayOrder(anchorIndex = startIndex.coerceIn(0, tracks.size - 1)) [cite: 34]
        _queueIndex.value = playOrder[playOrderPos] [cite: 34]
        play(tracks[playOrder[playOrderPos]]) [cite: 34]
    }

    fun addToQueue(track: Track) {
        _queue.value = _queue.value + track [cite: 35]
        playOrder = playOrder + (_queue.value.size - 1) [cite: 35]
    }

    fun removeFromQueue(index: Int) {
        if (index !in _queue.value.indices) return [cite: 35]
        val removedTrackWasCurrent = index == _queueIndex.value [cite: 35]
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index } [cite: 35]
        rebuildPlayOrder(anchorIndex = (_queueIndex.value - if (index < _queueIndex.value) 1 else 0).coerceIn(0, (_queue.value.size - 1).coerceAtLeast(0))) [cite: 35, 36]
        if (removedTrackWasCurrent && _queue.value.isNotEmpty()) {
            _queueIndex.value = playOrder[playOrderPos] [cite: 36]
            play(_queue.value[playOrder[playOrderPos]]) [cite: 36]
        }
    }

    fun playQueueItem(index: Int) {
        val tracks = _queue.value [cite: 36]
        if (index !in tracks.indices) return [cite: 36]
        consecutivePlaybackFailures = 0 [cite: 36]
        _playbackError.value = null [cite: 37]
        playOrderPos = playOrder.indexOf(index).coerceAtLeast(0) [cite: 37]
        _queueIndex.value = index [cite: 37]
        play(tracks[index]) [cite: 37]
    }

    fun toggleShuffle() {
        _isShuffleEnabled.value = !_isShuffleEnabled.value [cite: 37]
        rebuildPlayOrder(anchorIndex = _queueIndex.value.coerceAtLeast(0)) [cite: 37]
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) { [cite: 37]
            RepeatMode.OFF -> RepeatMode.ALL [cite: 37, 38]
            RepeatMode.ALL -> RepeatMode.ONE [cite: 38]
            RepeatMode.ONE -> RepeatMode.OFF [cite: 38]
        }
    }

    private fun rebuildPlayOrder(anchorIndex: Int) {
        val tracks = _queue.value [cite: 38]
        if (tracks.isEmpty()) {
            playOrder = emptyList() [cite: 38]
            playOrderPos = 0 [cite: 38]
            return [cite: 39]
        }
        val safeAnchor = anchorIndex.coerceIn(0, tracks.size - 1) [cite: 39]
        playOrder = if (_isShuffleEnabled.value) {
            val rest = tracks.indices.filter { it != safeAnchor }.shuffled() [cite: 39]
            listOf(safeAnchor) + rest [cite: 39]
        } else {
            tracks.indices.toList() [cite: 40]
        }
        playOrderPos = playOrder.indexOf(safeAnchor).coerceAtLeast(0) [cite: 40]
    }

    fun skipNext() = advance(1) [cite: 40]
    fun skipPrevious() = advance(-1) [cite: 40]

    private fun advance(direction: Int) {
        val tracks = _queue.value [cite: 40]
        if (tracks.isEmpty()) return [cite: 40]

        if (_repeatMode.value == RepeatMode.ONE) {
            _currentTrack.value?.let { play(it) } [cite: 40]
            return [cite: 41]
        }

        val nextPos = playOrderPos + direction [cite: 41]
        when {
            nextPos in playOrder.indices -> {
                playOrderPos = nextPos [cite: 41]
                val idx = playOrder[playOrderPos] [cite: 41]
                _queueIndex.value = idx [cite: 42]
                play(tracks[idx]) [cite: 42]
            }
            direction > 0 && _repeatMode.value == RepeatMode.ALL -> {
                playOrderPos = 0 [cite: 42]
                val idx = playOrder[0] [cite: 42]
                _queueIndex.value = idx [cite: 43]
                play(tracks[idx]) [cite: 43]
            }
            direction > 0 -> triggerAutoplay() [cite: 43]
            else -> {
                _currentTrack.value?.let { seekTo(0L); if (!_isPlaying.value) resume() } [cite: 44]
            }
        }
    }

    private fun triggerAutoplay() {
        val current = _currentTrack.value ?: return [cite: 44]
        val provider = autoplayProvider ?: return [cite: 44]
        scope.launch {
            _isResolvingAutoplay.value = true [cite: 44]
            try {
                val excludeIds = (_queue.value.map { it.id } + recentlyPlayedIds).toSet() [cite: 45]
                val recentTracks = _queue.value + recentlyPlayedTracks [cite: 45]
                val next = provider(current, excludeIds, recentTracks) [cite: 45]
                if (next != null) {
                    _queue.value = _queue.value + next [cite: 46]
                    val newIndex = _queue.value.size - 1 [cite: 46]
                    playOrder = playOrder + newIndex [cite: 46]
                    playOrderPos = playOrder.size - 1 [cite: 46]
                    _queueIndex.value = newIndex [cite: 47]
                    play(next) [cite: 47]
                }
            } finally {
                _isResolvingAutoplay.value = false [cite: 47]
            }
        }
    }

    private fun handleTrackEnded() {
        if (!hasReachedPlayingState) {
            registerPlaybackFailureAndMaybeStop() [cite: 50]
            return
        }
        advance(1) [cite: 50]
    }

    private fun registerPlaybackFailureAndMaybeStop() {
        consecutivePlaybackFailures++ [cite: 52]
        if (consecutivePlaybackFailures >= maxConsecutivePlaybackFailures) {
            consecutivePlaybackFailures = 0 [cite: 52]
            _isBuffering.value = false [cite: 52]
            _isPlaying.value = false [cite: 52]
            _playbackError.value = "Kai gaane play nahi ho paaye, ruk gaya - kuch aur try karein." [cite: 52]
            Log.e("MusicPlayer", "Stopping auto-skip after $maxConsecutivePlaybackFailures playback failures in a row") [cite: 53]
            return
        }
        advance(1) [cite: 53]
    }

    private fun trackRecentlyPlayed(track: Track) {
        if (recentlyPlayedIds.lastOrNull() != track.id) {
            recentlyPlayedIds.addLast(track.id) [cite: 54]
            recentlyPlayedTracks.addLast(track) [cite: 54]
            while (recentlyPlayedIds.size > recentlyPlayedCap) recentlyPlayedIds.removeFirst() [cite: 54]
            while (recentlyPlayedTracks.size > recentlyPlayedCap) recentlyPlayedTracks.removeFirst() [cite: 54]
        }
    }

    fun play(track: Track) {
        trackRecentlyPlayed(track) [cite: 54]
        if (track.source == TrackSource.YOUTUBE) {
            playYoutubeTrack(track) [cite: 54]
        } else {
            playItunesTrack(track) [cite: 54]
        }
    }

    private fun playItunesTrack(track: Track) {
        cancelBufferingWatchdog() [cite: 55]
        youtubeBridge?.pause() [cite: 55]
        _currentTrack.value = track [cite: 55]
        if (!isApplyingRemote) onLocalSongChange?.invoke(track) [cite: 55]
        _isBuffering.value = true [cite: 55]
        _isPlaying.value = false [cite: 55]
        _playbackPosition.value = 0L [cite: 55]
        
        runOnController { controller ->
            // System Tray Metadata Setup jisse control centre timeline draw karega
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist ?: "Unknown Artist")
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(track.previewUrl)
                .setMediaId(track.id.toString())
                .setMediaMetadata(metadata)
                .build()

            controller.setMediaItem(mediaItem) [cite: 56]
            controller.prepare() [cite: 56]
            controller.play() [cite: 56]
        }
        startProgressTracker() [cite: 56]
    }

    private fun playYoutubeTrack(track: Track) {
        val videoId = track.youtubeVideoId [cite: 56]
        if (videoId.isNullOrBlank()) {
            Log.e("MusicPlayer", "YouTube track missing videoId: ${track.title}") [cite: 56]
            return [cite: 57]
        }
        runOnController { it.pause() } [cite: 57]
        _currentTrack.value = track [cite: 57]
        if (!isApplyingRemote) onLocalSongChange?.invoke(track) [cite: 57]
        _isBuffering.value = true [cite: 57]
        _isPlaying.value = false [cite: 57]
        _playbackPosition.value = 0L [cite: 57]
        _duration.value = track.durationMs [cite: 57]
        hasReachedPlayingState = false [cite: 57]
        _playbackError.value = null [cite: 58]
        loadedYoutubeVideoId = videoId [cite: 58]
        youtubeBridge?.loadVideo(videoId) [cite: 58]
        startBufferingWatchdog(videoId) [cite: 58]
    }

    private fun startBufferingWatchdog(videoId: String) {
        bufferingWatchdogJob?.cancel() [cite: 61]
        bufferingWatchdogJob = scope.launch {
            delay(bufferingTimeoutMs) [cite: 61]
            if (loadedYoutubeVideoId == videoId && _isBuffering.value && !hasReachedPlayingState) { [cite: 61]
                Log.e("MusicPlayer", "Buffering timed out for videoId=$videoId - treating as failed playback") [cite: 61]
                _isBuffering.value = false [cite: 62]
                registerPlaybackFailureAndMaybeStop() [cite: 62]
            }
        }
    }

    private fun cancelBufferingWatchdog() {
        bufferingWatchdogJob?.cancel() [cite: 62]
        bufferingWatchdogJob = null [cite: 62]
    }

    fun pause() {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.pause() [cite: 63]
        } else {
            runOnController { it.pause() } [cite: 63]
        }
    }

    fun resume() {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.play() [cite: 63]
        } else {
            runOnController { it.play() } [cite: 64]
        }
    }

    fun clearPlaybackError() {
        _playbackError.value = null [cite: 65]
    }

    fun stop() {
        stopProgressTracker() [cite: 65]
        runOnController { it.stop() } [cite: 65]
        youtubeBridge?.pause() [cite: 65]
        _isPlaying.value = false [cite: 65]
        _playbackPosition.value = 0L [cite: 65]
    }

    fun stopAndDismiss() {
        stop() [cite: 67]
        _currentTrack.value = null [cite: 67]
    }

    fun seekTo(position: Long) {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.seekTo(position / 1000f) [cite: 67]
        } else {
            runOnController { it.seekTo(position) } [cite: 68]
        }
        _playbackPosition.value = position [cite: 68]
        if (!isApplyingRemote) onLocalSeek?.invoke(position) [cite: 68]
    }

    fun onYoutubePlayerStateChanged(state: Int, currentTimeSec: Double, durationSec: Double, videoId: String) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return [cite: 69]
        if (videoId != loadedYoutubeVideoId) return [cite: 70]
        when (state) {
            1 -> { 
                _isPlaying.value = true [cite: 70]
                _isBuffering.value = false [cite: 70]
                hasReachedPlayingState = true [cite: 70]
                consecutivePlaybackFailures = 0 [cite: 71]
                cancelBufferingWatchdog() [cite: 71]
                if (suppressNextPlayPauseBroadcast) suppressNextPlayPauseBroadcast = false [cite: 71]
                else onLocalPlayPause?.invoke(true, (currentTimeSec * 1000).toLong()) [cite: 71]
            }
            2 -> { 
                _isPlaying.value = false [cite: 72]
                _isBuffering.value = false [cite: 72]
                if (suppressNextPlayPauseBroadcast) suppressNextPlayPauseBroadcast = false [cite: 72]
                else onLocalPlayPause?.invoke(false, (currentTimeSec * 1000).toLong()) [cite: 72]
            }
            3 -> _isBuffering.value = true [cite: 72]
            0 -> { cancelBufferingWatchdog(); handleTrackEnded() } [cite: 73, 74]
        }
        if (durationSec > 0) _duration.value = (durationSec * 1000).toLong() [cite: 74]
    }

    fun onYoutubeTimeUpdate(currentTimeSec: Double, durationSec: Double, videoId: String) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return [cite: 75]
        if (videoId != loadedYoutubeVideoId) return [cite: 75]
        _playbackPosition.value = (currentTimeSec * 1000).toLong() [cite: 75]
        if (durationSec > 0) _duration.value = (durationSec * 1000).toLong() [cite: 75]
    }

    fun onYoutubePlayerError(errorCode: Int, videoId: String) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return [cite: 79]
        if (videoId != loadedYoutubeVideoId) return [cite: 79]
        Log.e("MusicPlayer", "YouTube playback error $errorCode for '${_currentTrack.value?.title}' - skipping") [cite: 79]
        cancelBufferingWatchdog() [cite: 79]
        _isBuffering.value = false [cite: 79]
        _isPlaying.value = false [cite: 79]
        registerPlaybackFailureAndMaybeStop() [cite: 79]
    }

    fun applyRemoteSongChange(song: Song) {
        isApplyingRemote = true [cite: 81]
        try {
            play(TrackSongBridge.toTrack(song)) [cite: 81]
        } finally {
            isApplyingRemote = false [cite: 81]
        }
    }

    fun applyRemotePlayPause(isPlaying: Boolean, positionMs: Long) {
        isApplyingRemote = true [cite: 82]
        suppressNextPlayPauseBroadcast = true [cite: 82]
        try {
            seekTo(positionMs) [cite: 82]
            if (isPlaying) resume() else pause() [cite: 82]
        } finally {
            isApplyingRemote = false [cite: 82]
        }
    }

    fun applyRemoteSeek(positionMs: Long) {
        isApplyingRemote = true [cite: 84]
        try {
            seekTo(positionMs) [cite: 84]
        } finally {
            isApplyingRemote = false [cite: 84]
        }
    }

    private fun startProgressTracker() {
        stopProgressTracker() [cite: 84]
        progressJob = scope.launch {
            while (true) {
                if (_currentTrack.value?.source == TrackSource.ITUNES) {
                    mediaController?.let {
                        if (it.isPlaying) _playbackPosition.value = it.currentPosition.coerceAtLeast(0L) [cite: 85]
                    }
                }
                // Delay 1000ms kiya taaki main-thread composition smooth ho aur constant lock refresh drop na kare
                delay(1000)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel() [cite: 86]
        progressJob = null [cite: 86]
    }

    fun release() {
        stopProgressTracker() [cite: 87]
        cancelBufferingWatchdog() [cite: 87]
        mediaController?.release() [cite: 87]
        mediaController = null [cite: 87]
    }
}

class SamplesPlayerManager {
    private val _isPlaying = MutableStateFlow(false) [cite: 91]
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow() [cite: 91]

    private val _playbackPosition = MutableStateFlow(0L) [cite: 91]
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow() [cite: 91]

    private val _duration = MutableStateFlow(0L) [cite: 91]
    val duration: StateFlow<Long> = _duration.asStateFlow() [cite: 91]

    private val _isBuffering = MutableStateFlow(true) [cite: 91]
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow() [cite: 91]

    var activePlayer: androidx.media3.exoplayer.ExoPlayer? = null [cite: 92]

    fun reportActiveState(isPlaying: Boolean, isBuffering: Boolean, positionMs: Long, durationMs: Long) {
        _isPlaying.value = isPlaying [cite: 92]
        _isBuffering.value = isBuffering [cite: 92]
        _playbackPosition.value = positionMs [cite: 92]
        if (durationMs > 0) _duration.value = durationMs [cite: 92]
    }

    fun togglePlayPause() {
        val player = activePlayer ?: return [cite: 92]
        if (player.isPlaying) player.pause() else player.play() [cite: 92, 93]
    }

    fun pause() {
        activePlayer?.pause() [cite: 93]
    }
}
