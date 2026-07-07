package com.example.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.example.data.model.Song
import com.example.data.model.Track
import com.example.data.model.TrackSongBridge
import com.example.data.model.TrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bridge to the small, always-visible embedded YouTube player (see
 * YouTubePlayerHost.kt). Kept visible per YouTube's Terms of Service - full
 * songs are NOT extracted/streamed directly, they play through YouTube's own
 * official IFrame player, just shown small.
 */
interface YouTubePlayerBridge {
    fun loadVideo(videoId: String)
    fun play()
    fun pause()
    fun seekTo(seconds: Float)
}

class MusicPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    // Fired only when THIS device caused the change (button press), not when
    // applyRemoteX() is mirroring an update that came from a Jam partner.
    // JamViewModel wires these to JamManager.pushX so the change gets broadcast.
    var onLocalSongChange: ((Track) -> Unit)? = null
    var onLocalPlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onLocalSeek: ((positionMs: Long) -> Unit)? = null

    private var isApplyingRemote = false

    // Set by YouTubePlayerHost once its WebView is composed. Stays set for the
    // app's lifetime (the host is mounted once, persistently, at the app root).
    var youtubeBridge: YouTubePlayerBridge? = null

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

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var queue = listOf<Track>()
    private var currentIndex = -1

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        queue = tracks
        currentIndex = startIndex
        if (queue.isNotEmpty() && currentIndex in queue.indices) {
            play(queue[currentIndex])
        }
    }

    fun play(track: Track) {
        if (track.source == TrackSource.YOUTUBE) {
            playYoutubeTrack(track)
        } else {
            playItunesTrack(track)
        }
    }

    private fun playYoutubeTrack(track: Track) {
        val wasRemote = isApplyingRemote
        val videoId = track.youtubeVideoId
        if (videoId.isNullOrBlank()) {
            Log.e("MusicPlayer", "YouTube track missing videoId: ${track.title}")
            return
        }
        releaseItunesPlayer()
        _currentTrack.value = track
        if (!wasRemote) onLocalSongChange?.invoke(track)
        _isBuffering.value = true
        _isPlaying.value = false
        _playbackPosition.value = 0L
        _duration.value = track.durationMs
        youtubeBridge?.loadVideo(videoId)
    }

    private fun playItunesTrack(track: Track) {
        val wasRemote = isApplyingRemote
        scope.launch {
            try {
                if (_currentTrack.value?.id == track.id && mediaPlayer != null) {
                    resume()
                    return@launch
                }

                _isBuffering.value = true
                _isPlaying.value = false
                releaseItunesPlayer()
                youtubeBridge?.pause()

                _currentTrack.value = track
                if (!wasRemote) onLocalSongChange?.invoke(track)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, Uri.parse(track.previewUrl))
                    setOnPreparedListener { mp ->
                        _isBuffering.value = false
                        _duration.value = mp.duration.toLong()
                        mp.start()
                        _isPlaying.value = true
                        startProgressTracker()
                    }
                    setOnCompletionListener {
                        _isPlaying.value = false
                        skipNext()
                    }
                    setOnErrorListener { _, _, _ ->
                        _isBuffering.value = false
                        false
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                _isBuffering.value = false
                Log.e("MusicPlayer", "Error playing track", e)
            }
        }
    }

    fun pause() {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.pause()
            // isPlaying/onLocalPlayPause fire from onYoutubePlayerStateChanged once YouTube confirms the pause.
            return
        }
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
                stopProgressTracker()
                if (!isApplyingRemote) onLocalPlayPause?.invoke(false, _playbackPosition.value)
            }
        }
    }

    fun resume() {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.play()
            return
        }
        mediaPlayer?.let {
            if (!it.isPlaying) {
                it.start()
                _isPlaying.value = true
                startProgressTracker()
                if (!isApplyingRemote) onLocalPlayPause?.invoke(true, _playbackPosition.value)
            }
        }
    }

    fun stop() {
        stopProgressTracker()
        releaseItunesPlayer()
        youtubeBridge?.pause()
        _isPlaying.value = false
        _playbackPosition.value = 0L
    }

    private fun releaseItunesPlayer() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        mediaPlayer = null
    }

    fun seekTo(position: Long) {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE) {
            youtubeBridge?.seekTo(position / 1000f)
            _playbackPosition.value = position
            if (!isApplyingRemote) onLocalSeek?.invoke(position)
            return
        }
        mediaPlayer?.let {
            it.seekTo(position.toInt())
            _playbackPosition.value = position
            if (!isApplyingRemote) onLocalSeek?.invoke(position)
        }
    }

    /** Called by YouTubePlayerHost's JS bridge when the IFrame player's state changes. */
    fun onYoutubePlayerStateChanged(state: Int, currentTimeSec: Double, durationSec: Double) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return
        when (state) {
            1 -> { // playing
                _isPlaying.value = true
                _isBuffering.value = false
                if (!isApplyingRemote) onLocalPlayPause?.invoke(true, (currentTimeSec * 1000).toLong())
                startProgressTrackerYoutube()
            }
            2 -> { // paused
                _isPlaying.value = false
                _isBuffering.value = false
                stopProgressTracker()
                if (!isApplyingRemote) onLocalPlayPause?.invoke(false, (currentTimeSec * 1000).toLong())
            }
            3 -> _isBuffering.value = true // buffering
            0 -> { // ended
                _isPlaying.value = false
                stopProgressTracker()
                skipNext()
            }
        }
        if (durationSec > 0) _duration.value = (durationSec * 1000).toLong()
    }

    /** Called periodically by YouTubePlayerHost's JS bridge while a video is loaded. */
    fun onYoutubeTimeUpdate(currentTimeSec: Double, durationSec: Double) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return
        _playbackPosition.value = (currentTimeSec * 1000).toLong()
        if (durationSec > 0) _duration.value = (durationSec * 1000).toLong()
    }

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

    fun skipNext() {
        if (queue.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % queue.size
            play(queue[currentIndex])
        }
    }

    fun skipPrevious() {
        if (queue.isNotEmpty()) {
            currentIndex = if (currentIndex - 1 < 0) queue.size - 1 else currentIndex - 1
            play(queue[currentIndex])
        }
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (true) {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        _playbackPosition.value = it.currentPosition.toLong()
                    }
                }
                delay(500)
            }
        }
    }

    // YouTube position updates mostly arrive via onYoutubeTimeUpdate (driven by JS setInterval),
    // this just keeps `isPlaying` progress consistent if that channel is ever delayed.
    private fun startProgressTrackerYoutube() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (true) {
                delay(500)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }
}

/**
 * Optimized Player Manager for the Samples Vertical Swipe Feed.
 * Samples now play real ~30s *video* previews (iTunes `musicVideo` entity),
 * rendered via a VideoView owned by the active feed page (see
 * SampleVideoSurface in SamplesScreen.kt) which registers itself here as
 * the [SampleVideoBridge]. This manager just holds shared playback state.
 */
interface SampleVideoBridge {
    fun loadAndPlay(url: String)
    fun pause()
    fun resume()
    fun seekTo(ms: Long)
    fun currentPositionMs(): Long
}

class SamplesPlayerManager {
    var bridge: SampleVideoBridge? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    /** Called by SamplesViewModel when the visible feed page changes. */
    fun playTrack(track: Track, nextTrack: Track? = null) {
        _isBuffering.value = true
        _isPlaying.value = false
        _playbackPosition.value = 0L
        bridge?.loadAndPlay(track.previewUrl)
    }

    /** Called by SampleVideoSurface once its VideoView reports "prepared". */
    fun onPrepared(durationMs: Long) {
        _duration.value = durationMs
        _isBuffering.value = false
        _isPlaying.value = true
        startProgressTracker()
    }

    fun onCompleted() {
        _isPlaying.value = false
        stopProgressTracker()
    }

    fun pause() {
        bridge?.pause()
        _isPlaying.value = false
        stopProgressTracker()
    }

    fun resume() {
        bridge?.resume()
        _isPlaying.value = true
        startProgressTracker()
    }

    fun stop() {
        bridge?.pause()
        stopProgressTracker()
        _isPlaying.value = false
        _playbackPosition.value = 0L
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (true) {
                bridge?.let { _playbackPosition.value = it.currentPositionMs() }
                delay(500)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }
}
