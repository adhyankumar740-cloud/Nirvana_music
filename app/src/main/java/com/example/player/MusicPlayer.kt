package com.example.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
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

/**
 * Bridge to the small, always-visible embedded YouTube player (see
 * YouTubePlayerHost.kt). Kept visible per YouTube's Terms of Service - full
 * songs are NOT extracted/streamed directly, they play through YouTube's own
 * official IFrame player, just shown small. This is also why YouTube tracks
 * pause when the app is backgrounded (see PlaybackService.kt doc comment) -
 * that constraint is a platform/ToS wall, not something either engine choice
 * can route around.
 */
interface YouTubePlayerBridge {
    fun loadVideo(videoId: String)
    fun play()
    fun pause()
    fun seekTo(seconds: Float)
}

/**
 * Central playback engine. Two underlying engines, selected per-track by
 * [Track.source]:
 *  - ITUNES  -> Media3 ExoPlayer, connected via MediaController to
 *               [PlaybackService] (a real foreground service) - gives real
 *               background playback + lock-screen/notification controls.
 *  - YOUTUBE -> the small visible embedded WebView player (YouTubePlayerHost)
 *               - foreground-only, per YouTube's Terms of Service.
 *
 * Queue/shuffle/repeat/autoplay are implemented at THIS level (not handed off
 * to ExoPlayer's native playlist) because a single queue can mix both source
 * types, and ExoPlayer can't play a YouTube video directly.
 */
class MusicPlayer(private val context: Context) {

    // ---- Media3 controller (iTunes-sourced tracks; real background playback) ----
    private var mediaController: MediaController? = null
    private val pendingActions = mutableListOf<() -> Unit>()

    // Fired only when THIS device caused the change (button press), not when
    // applyRemoteX() is mirroring an update that came from a Jam partner.
    // JamViewModel wires these to JamManager.pushX so the change gets broadcast.
    var onLocalSongChange: ((Track) -> Unit)? = null
    var onLocalPlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onLocalSeek: ((positionMs: Long) -> Unit)? = null

    private var isApplyingRemote = false
    // Consumed exactly once by whichever engine's async "state changed" callback
    // fires next - avoids the race where isApplyingRemote would already have
    // been reset (by applyRemoteX's synchronous `finally`) before the engine's
    // callback arrives.
    private var suppressNextPlayPauseBroadcast = false

    // Set by YouTubePlayerHost once its WebView is composed. Stays set for the
    // app's lifetime (the host is mounted once, persistently, at the app root).
    var youtubeBridge: YouTubePlayerBridge? = null

    /** Called when the queue naturally runs out (last track ends, repeat=OFF). */
    var autoplayProvider: (suspend (currentTrack: Track, excludeIds: Set<Long>) -> Track?)? = null

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

    // Play order (list of indices into _queue.value) - separate from the
    // user-visible queue order so shuffling doesn't reshuffle what's shown.
    private var playOrder: List<Int> = emptyList()
    private var playOrderPos = 0

    private val recentlyPlayedIds = ArrayDeque<Long>()
    private val recentlyPlayedCap = 40

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
            if (_currentTrack.value?.source != TrackSource.ITUNES) return
            _isPlaying.value = isPlayingNow
            if (isPlayingNow) startProgressTracker() else stopProgressTracker()
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
    }

    // ---------------- Queue / shuffle / repeat ----------------

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
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
        // Rebuild indices/order after the shift
        rebuildPlayOrder(anchorIndex = (_queueIndex.value - if (index < _queueIndex.value) 1 else 0).coerceIn(0, (_queue.value.size - 1).coerceAtLeast(0)))
        if (removedTrackWasCurrent && _queue.value.isNotEmpty()) {
            _queueIndex.value = playOrder[playOrderPos]
            play(_queue.value[playOrder[playOrderPos]])
        }
    }

    fun playQueueItem(index: Int) {
        val tracks = _queue.value
        if (index !in tracks.indices) return
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
                // Already at the start; restart current track instead of wrapping.
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
                val next = provider(current, excludeIds)
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
        advance(1)
    }

    // ---------------- Playback ----------------

    private fun trackRecentlyPlayed(track: Track) {
        if (recentlyPlayedIds.lastOrNull() != track.id) {
            recentlyPlayedIds.addLast(track.id)
            while (recentlyPlayedIds.size > recentlyPlayedCap) recentlyPlayedIds.removeFirst()
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
        youtubeBridge?.pause()
        _currentTrack.value = track
        if (!isApplyingRemote) onLocalSongChange?.invoke(track)
        _isBuffering.value = true
        _isPlaying.value = false
        _playbackPosition.value = 0L
        runOnController { controller ->
            controller.setMediaItem(MediaItem.fromUri(track.previewUrl))
            controller.prepare()
            controller.play()
        }
        startProgressTracker()
    }

    private fun playYoutubeTrack(track: Track) {
        val videoId = track.youtubeVideoId
        if (videoId.isNullOrBlank()) {
            Log.e("MusicPlayer", "YouTube track missing videoId: ${track.title}")
            return
        }
        runOnController { it.pause() }
        _currentTrack.value = track
        if (!isApplyingRemote) onLocalSongChange?.invoke(track)
        _isBuffering.value = true
        _isPlaying.value = false
        _playbackPosition.value = 0L
        _duration.value = track.durationMs
        youtubeBridge?.loadVideo(videoId)
    }

    fun pause() {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.pause()
        } else {
            runOnController { it.pause() }
        }
    }

    fun resume() {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.play()
        } else {
            runOnController { it.play() }
        }
    }

    fun stop() {
        stopProgressTracker()
        runOnController { it.stop() }
        youtubeBridge?.pause()
        _isPlaying.value = false
        _playbackPosition.value = 0L
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

    /** Called by YouTubePlayerHost's JS bridge when the IFrame player's state changes. */
    fun onYoutubePlayerStateChanged(state: Int, currentTimeSec: Double, durationSec: Double) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return
        when (state) {
            1 -> { // playing
                _isPlaying.value = true
                _isBuffering.value = false
                if (suppressNextPlayPauseBroadcast) suppressNextPlayPauseBroadcast = false
                else onLocalPlayPause?.invoke(true, (currentTimeSec * 1000).toLong())
            }
            2 -> { // paused
                _isPlaying.value = false
                _isBuffering.value = false
                if (suppressNextPlayPauseBroadcast) suppressNextPlayPauseBroadcast = false
                else onLocalPlayPause?.invoke(false, (currentTimeSec * 1000).toLong())
            }
            3 -> _isBuffering.value = true // buffering
            0 -> handleTrackEnded() // ended
        }
        if (durationSec > 0) _duration.value = (durationSec * 1000).toLong()
    }

    /** Called periodically by YouTubePlayerHost's JS bridge while a video is loaded. */
    fun onYoutubeTimeUpdate(currentTimeSec: Double, durationSec: Double) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return
        _playbackPosition.value = (currentTimeSec * 1000).toLong()
        if (durationSec > 0) _duration.value = (durationSec * 1000).toLong()
    }

    // ---------------- Jam (remote) sync ----------------

    /** Mirrors a song change that arrived from a Jam partner (does not re-broadcast). */
    fun applyRemoteSongChange(song: Song) {
        isApplyingRemote = true
        try {
            play(TrackSongBridge.toTrack(song))
        } finally {
            isApplyingRemote = false
        }
    }

    /** Mirrors a play/pause that arrived from a Jam partner (does not re-broadcast). */
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

    /** Mirrors a seek that arrived from a Jam partner (does not re-broadcast). */
    fun applyRemoteSeek(positionMs: Long) {
        isApplyingRemote = true
        try {
            seekTo(positionMs)
        } finally {
            isApplyingRemote = false
        }
    }

    // ---------------- Progress polling ----------------

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (true) {
                if (_currentTrack.value?.source == TrackSource.ITUNES) {
                    mediaController?.let {
                        if (it.isPlaying) _playbackPosition.value = it.currentPosition.coerceAtLeast(0L)
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopProgressTracker()
        mediaController?.release()
        mediaController = null
    }
}

/**
 * Shared UI-facing state for the Samples Vertical Swipe Feed. Each visible/adjacent
 * feed page owns its OWN Media3 ExoPlayer instance (see SampleVideoPage in
 * SamplesScreen.kt) so 1 page ahead is always pre-buffered - swiping to it is
 * instant, no reload/lag. Only the currently active page's player reports its
 * state in here (and receives play/pause taps via [activePlayer]).
 *
 * Using Media3's PlayerView (useController = false) instead of the old VideoView
 * also fixes the "can't scroll" bug: VideoView intercepted touch/drag gestures
 * before Compose's VerticalPager could see them; PlayerView without its own
 * controls does not.
 */
class SamplesPlayerManager {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(true)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    /** The currently active page's ExoPlayer - tap-to-pause controls this directly. */
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
