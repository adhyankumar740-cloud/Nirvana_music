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

enum class RepeatMode { OFF, ONE, ALL }

interface YouTubePlayerBridge {
    fun loadVideo(videoId: String)
    fun play()
    fun pause()
    fun seekTo(seconds: Float)
}

class MusicPlayer(private val context: Context) {

    private var mediaController: MediaController? = null
    private val pendingActions = mutableListOf<() -> Unit>()

    var onLocalSongChange: ((Track) -> Unit)? = null
    var onLocalPlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onLocalSeek: ((positionMs: Long) -> Unit)? = null

    private var isApplyingRemote = false
    private var suppressNextPlayPauseBroadcast = false

    var youtubeBridge: YouTubePlayerBridge? = null

    var autoplayProvider: (suspend (currentTrack: Track, excludeIds: Set<Long>, recentTracks: List<Track>) -> Track?)? = null

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(-1)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isResolvingAutoplay = MutableStateFlow(false)
    val isResolvingAutoplay: StateFlow<Boolean> = _isResolvingAutoplay.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private var hasReachedPlayingState = false
    private var consecutivePlaybackFailures = 0
    private val maxConsecutivePlaybackFailures = 3

    private var playOrder: List<Int> = emptyList()
    private var playOrderPos = 0

    private val recentlyPlayedIds = ArrayDeque<Long>()
    private val recentlyPlayedTracks = ArrayDeque<Track>()
    private val recentlyPlayedCap = 40

    private var loadedYoutubeVideoId: String? = null

    private var bufferingWatchdogJob: Job? = null
    private val bufferingTimeoutMs = 12_000L

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    init {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
                mediaController?.addListener(playerListener)
                pendingActions.forEach { it() }
                pendingActions.clear()
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Failed to connect MediaController", e)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun runOnController(action: (MediaController) -> Unit) {
        val controller = mediaController
        if (controller != null) action(controller) else pendingActions.add { mediaController?.let(action) }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            _isPlaying.value = isPlayingNow
            if (isPlayingNow) startProgressTracker() else stopProgressTracker()

            if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
                if (isPlayingNow) youtubeBridge?.play() else youtubeBridge?.pause()
            }

            if (suppressNextPlayPauseBroadcast) {
                suppressNextPlayPauseBroadcast = false
            } else {
                onLocalPlayPause?.invoke(isPlayingNow, _playbackPosition.value)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (_currentTrack.value?.source != TrackSource.ITUNES) return
            when (state) {
                Player.STATE_BUFFERING -> _isBuffering.value = true
                Player.STATE_READY -> {
                    _isBuffering.value = false
                    _duration.value = (mediaController?.duration ?: 0L).coerceAtLeast(0L)
                }
                Player.STATE_ENDED -> handleTrackEnded()
                else -> {}
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // Control centre seek bar drag handling for YouTube tracks
            if (reason == Player.DISCONTINUITY_REASON_SEEK && _currentTrack.value?.source == TrackSource.YOUTUBE) {
                val targetSeekMs = newPosition.positionMs
                _playbackPosition.value = targetSeekMs
                youtubeBridge?.seekTo(targetSeekMs / 1000f)
                if (!isApplyingRemote) onLocalSeek?.invoke(targetSeekMs)
            }
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        consecutivePlaybackFailures = 0
        _playbackError.value = null
        _queue.value = tracks
        rebuildPlayOrder(anchorIndex = startIndex.coerceIn(0, tracks.size - 1))
        _queueIndex.value = playOrder[playOrderPos]
        play(tracks[playOrder[playOrderPos]])
    }

    fun addToQueue(track: Track) {
        _queue.value = _queue.value + track
        playOrder = playOrder + (_queue.value.size - 1)
    }

    fun removeFromQueue(index: Int) {
        if (index !in _queue.value.indices) return
        val removedTrackWasCurrent = index == _queueIndex.value
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
        rebuildPlayOrder(anchorIndex = (_queueIndex.value - if (index < _queueIndex.value) 1 else 0).coerceIn(0, (_queue.value.size - 1).coerceAtLeast(0)))
        if (removedTrackWasCurrent && _queue.value.isNotEmpty()) {
            _queueIndex.value = playOrder[playOrderPos]
            play(_queue.value[playOrder[playOrderPos]])
        }
    }

    fun playQueueItem(index: Int) {
        val tracks = _queue.value
        if (index !in tracks.indices) return
        consecutivePlaybackFailures = 0
        _playbackError.value = null
        playOrderPos = playOrder.indexOf(index).coerceAtLeast(0)
        _queueIndex.value = index
        play(tracks[index])
    }

    fun toggleShuffle() {
        _isShuffleEnabled.value = !_isShuffleEnabled.value
        rebuildPlayOrder(anchorIndex = _queueIndex.value.coerceAtLeast(0))
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    private fun rebuildPlayOrder(anchorIndex: Int) {
        val tracks = _queue.value
        if (tracks.isEmpty()) {
            playOrder = emptyList()
            playOrderPos = 0
            return
        }
        val safeAnchor = anchorIndex.coerceIn(0, tracks.size - 1)
        playOrder = if (_isShuffleEnabled.value) {
            val rest = tracks.indices.filter { it != safeAnchor }.shuffled()
            listOf(safeAnchor) + rest
        } else {
            tracks.indices.toList()
        }
        playOrderPos = playOrder.indexOf(safeAnchor).coerceAtLeast(0)
    }

    fun skipNext() = advance(1)
    fun skipPrevious() = advance(-1)

    private fun advance(direction: Int) {
        val tracks = _queue.value
        if (tracks.isEmpty()) return

        if (_repeatMode.value == RepeatMode.ONE) {
            _currentTrack.value?.let { play(it) }
            return
        }

        val nextPos = playOrderPos + direction
        when {
            nextPos in playOrder.indices -> {
                playOrderPos = nextPos
                val idx = playOrder[playOrderPos]
                _queueIndex.value = idx
                play(tracks[idx])
            }
            direction > 0 && _repeatMode.value == RepeatMode.ALL -> {
                playOrderPos = 0
                val idx = playOrder[0]
                _queueIndex.value = idx
                play(tracks[idx])
            }
            direction > 0 -> triggerAutoplay()
            else -> {
                _currentTrack.value?.let { seekTo(0L); if (!_isPlaying.value) resume() }
            }
        }
    }

    private fun triggerAutoplay() {
        val current = _currentTrack.value ?: return
        val provider = autoplayProvider ?: return
        scope.launch {
            _isResolvingAutoplay.value = true
            try {
                val excludeIds = (_queue.value.map { it.id } + recentlyPlayedIds).toSet()
                val recentTracks = _queue.value + recentlyPlayedTracks
                val next = provider(current, excludeIds, recentTracks)
                if (next != null) {
                    _queue.value = _queue.value + next
                    val newIndex = _queue.value.size - 1
                    playOrder = playOrder + newIndex
                    playOrderPos = playOrder.size - 1
                    _queueIndex.value = newIndex
                    play(next)
                }
            } finally {
                _isResolvingAutoplay.value = false
            }
        }
    }

    private fun handleTrackEnded() {
        if (!hasReachedPlayingState) {
            registerPlaybackFailureAndMaybeStop()
            return
        }
        advance(1)
    }

    private fun registerPlaybackFailureAndMaybeStop() {
        consecutivePlaybackFailures++
        if (consecutivePlaybackFailures >= maxConsecutivePlaybackFailures) {
            consecutivePlaybackFailures = 0
            _isBuffering.value = false
            _isPlaying.value = false
            _playbackError.value = "Kai gaane play nahi ho paaye, ruk gaya - kuch aur try karein."
            Log.e("MusicPlayer", "Stopping auto-skip after $maxConsecutivePlaybackFailures playback failures in a row")
            return
        }
        advance(1)
    }

    private fun trackRecentlyPlayed(track: Track) {
        if (recentlyPlayedIds.lastOrNull() != track.id) {
            recentlyPlayedIds.addLast(track.id)
            recentlyPlayedTracks.addLast(track)
            while (recentlyPlayedIds.size > recentlyPlayedCap) recentlyPlayedIds.removeFirst()
            while (recentlyPlayedTracks.size > recentlyPlayedCap) recentlyPlayedTracks.removeFirst()
        }
    }

    fun play(track: Track) {
        trackRecentlyPlayed(track)
        if (track.source == TrackSource.YOUTUBE) {
            playYoutubeTrack(track)
        } else {
            playItunesTrack(track)
        }
    }

    private fun playItunesTrack(track: Track) {
        cancelBufferingWatchdog()
        youtubeBridge?.pause()
        _currentTrack.value = track
        if (!isApplyingRemote) onLocalSongChange?.invoke(track)
        _isBuffering.value = true
        _isPlaying.value = false
        _playbackPosition.value = 0L
        
        runOnController { controller ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist ?: "Unknown Artist")
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(track.previewUrl)
                .setMediaId(track.id.toString())
                .setMediaMetadata(metadata)
                .build()

            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }
        startProgressTracker()
    }

    private fun playYoutubeTrack(track: Track) {
        cancelBufferingWatchdog()
        _currentTrack.value = track
        if (!isApplyingRemote) onLocalSongChange?.invoke(track)
        
        _isBuffering.value = true
        _isPlaying.value = false
        _playbackPosition.value = 0L
        _duration.value = track.durationMs
        hasReachedPlayingState = false
        _playbackError.value = null
        loadedYoutubeVideoId = track.youtubeVideoId
        
        // INSTANT LOAD FOR SYSTEM TRAY: Koi local asset URI nahi daalenge jo delay kare
        runOnController { controller ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist ?: "YouTube Stream")
                .build()

            // Bina kisi playback resource ke sirf stream tracking define karenge
            val fastMediaItem = MediaItem.Builder()
                .setMediaId("yt_${track.id}")
                .setMediaMetadata(metadata)
                .build()

            controller.setMediaItem(fastMediaItem)
            // Prepare ko block nahi karenge taaki background queue instant execute ho ske
        }

        track.youtubeVideoId?.let { videoId ->
            youtubeBridge?.loadVideo(videoId)
            startBufferingWatchdog(videoId)
        }
    }

    private fun startBufferingWatchdog(videoId: String) {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = scope.launch {
            delay(bufferingTimeoutMs)
            if (loadedYoutubeVideoId == videoId && _isBuffering.value && !hasReachedPlayingState) {
                _isBuffering.value = false
                registerPlaybackFailureAndMaybeStop()
            }
        }
    }

    private fun cancelBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
    }

    fun pause() {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.pause()
            _isPlaying.value = false
        } else {
            runOnController { it.pause() }
        }
    }

    fun resume() {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.play()
            _isPlaying.value = true
        } else {
            runOnController { it.play() }
        }
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    fun stop() {
        stopProgressTracker()
        runOnController { it.stop() }
        youtubeBridge?.pause()
        _isPlaying.value = false
        _playbackPosition.value = 0L
    }

    fun stopAndDismiss() {
        stop()
        _currentTrack.value = null
    }

    fun seekTo(position: Long) {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.seekTo(position / 1000f)
        } else {
            runOnController { it.seekTo(position) }
        }
        _playbackPosition.value = position
        if (!isApplyingRemote) onLocalSeek?.invoke(position)
    }

    fun onYoutubePlayerStateChanged(state: Int, currentTimeSec: Double, durationSec: Double, videoId: String) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return
        if (videoId != loadedYoutubeVideoId) return
        when (state) {
            1 -> { // PLAYING STATE
                _isPlaying.value = true
                _isBuffering.value = false
                hasReachedPlayingState = true
                consecutivePlaybackFailures = 0
                cancelBufferingWatchdog()
                startProgressTracker()
                if (suppressNextPlayPauseBroadcast) suppressNextPlayPauseBroadcast = false
                else onLocalPlayPause?.invoke(true, (currentTimeSec * 1000).toLong())
            }
            2 -> { // PAUSED STATE
                _isPlaying.value = false
                _isBuffering.value = false
                stopProgressTracker()
                if (suppressNextPlayPauseBroadcast) suppressNextPlayPauseBroadcast = false
                else onLocalPlayPause?.invoke(false, (currentTimeSec * 1000).toLong())
            }
            3 -> _isBuffering.value = true // BUFFERING
            0 -> { cancelBufferingWatchdog(); handleTrackEnded() } // ENDED
        }
        if (durationSec > 0) _duration.value = (durationSec * 1000).toLong()
    }

    fun onYoutubeTimeUpdate(currentTimeSec: Double, durationSec: Double, videoId: String) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return
        if (videoId != loadedYoutubeVideoId) return
        val currentMs = (currentTimeSec * 1000).toLong()
        _playbackPosition.value = currentMs
        if (durationSec > 0) _duration.value = (durationSec * 1000).toLong()
    }

    fun onYoutubePlayerError(errorCode: Int, videoId: String) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return
        if (videoId != loadedYoutubeVideoId) return
        cancelBufferingWatchdog()
        _isBuffering.value = false
        _isPlaying.value = false
        registerPlaybackFailureAndMaybeStop()
    }

    fun applyRemoteSongChange(song: Song) {
        isApplyingRemote = true
        try {
            play(TrackSongBridge.toTrack(song))
        } finally {
            isApplyingRemote = false
        }
    }

    fun applyRemotePlayPause(isPlaying: Boolean, positionMs: Long) {
        isApplyingRemote = true
        suppressNextPlayPauseBroadcast = true
        try {
            seekTo(positionMs)
            if (isPlaying) resume() else pause()
        } finally {
            isApplyingRemote = false
        }
    }

    fun applyRemoteSeek(positionMs: Long) {
        isApplyingRemote = true
        try {
            seekTo(positionMs)
        } finally {
            isApplyingRemote = false
        }
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (true) {
                if (_currentTrack.value?.source == TrackSource.ITUNES) {
                    mediaController?.let {
                        if (it.isPlaying) _playbackPosition.value = it.currentPosition.coerceAtLeast(0L)
                    }
                }
                // Fast updates keep seek bar moving flawlessly without locking up the CPU
                delay(1000)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopProgressTracker()
        cancelBufferingWatchdog()
        mediaController?.release()
        mediaController = null
    }
}

class SamplesPlayerManager {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(true)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    var activePlayer: androidx.media3.exoplayer.ExoPlayer? = null

    fun reportActiveState(isPlaying: Boolean, isBuffering: Boolean, positionMs: Long, durationMs: Long) {
        _isPlaying.value = isPlaying
        _isBuffering.value = isBuffering
        _playbackPosition.value = positionMs
        if (durationMs > 0) _duration.value = durationMs
    }

    fun togglePlayPause() {
        val player = activePlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() {
        activePlayer?.pause()
    }
}
