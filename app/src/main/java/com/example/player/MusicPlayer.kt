package com.example.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.model.Song
import com.example.data.model.Track
import com.example.data.model.TrackSongBridge
import com.example.data.model.TrackSource
import com.example.data.network.RelayResolveResponse
import com.example.data.network.RelayService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepeatMode { OFF, ONE, ALL }

class MusicPlayer(
    private val context: Context,
    private val relayService: RelayService,
    private val relayApiKey: String
) {

    private var mediaController: MediaController? = null
    private val pendingActions = mutableListOf<() -> Unit>()

    var onLocalSongChange: ((Track) -> Unit)? = null
    var onLocalPlayPause: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onLocalSeek: ((positionMs: Long) -> Unit)? = null

    private var isApplyingRemote = false
    private var suppressNextPlayPauseBroadcast = false

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

    // Ek hi videoId ke liye relay resolve sirf ek baar retry hota hai
    // (mid-playback error pe) - taaki relay baar-baar down hone par hum
    // infinite retry loop me na phasein; ek retry ke baad bhi fail ho to
    // track ko skip/error kar dete hain.
    private var relayRetriedForVideoId: String? = null

    private var bufferingWatchdogJob: Job? = null
    private val bufferingTimeoutMs = 12_000L

    // --- Next-track preloading (removes the relay-resolve wait when advancing) ---
    // The relay's /resolve endpoint can be slow on a cold cache (it downloads/
    // converts the video from YouTube on first resolve for that videoId), which
    // is exactly the pause users saw between songs. As soon as a track starts
    // playing, we resolve the *next* track's stream URL in the background and
    // cache it here, so by the time skipNext()/auto-advance actually happens,
    // the URL is usually already sitting in the cache and playback can start
    // immediately instead of waiting on a fresh network round trip.
    private val preloadedStreamUrls = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 5
    }
    private var preloadJob: Job? = null

    // Jab tak preloadJob URL resolve karke iske actual audio bytes disk cache
    // me utaar raha hota hai, uska CacheWriter yahan rakha jaata hai - taaki
    // agar is beech me prediction badal jaaye (user shuffle/skip kar de,
    // naya "next" track ban jaaye) to hum turant isko cancel() karke bandwidth
    // naye sahi target pe laga sakein, purane par waste na ho.
    private var activeCacheWriter: CacheWriter? = null

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    init {
        PlaybackBridge.onNext = { skipNext() }
        PlaybackBridge.onPrevious = { skipPrevious() }
        PlaybackBridge.onSeek = { position -> seekTo(position) }

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

            if (suppressNextPlayPauseBroadcast) {
                suppressNextPlayPauseBroadcast = false
            } else {
                onLocalPlayPause?.invoke(isPlayingNow, _playbackPosition.value)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            // ExoPlayer ab hamesha asli source hai (ITUNES preview ya relay-
            // resolved YouTube audio), isliye state seedha reflect hoti hai.
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
            // Lock-screen/notification seek bar drag hone par yeh fire hota hai
            // (system seek seedha MediaSession ke player pe hoti hai, MusicPlayer
            // ke apne seekTo() se hoke nahi) - isliye yahan se local state aur
            // remote (Jam) listeners ko sync karna zaroori hai.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                val targetSeekMs = newPosition.positionMs
                _playbackPosition.value = targetSeekMs
                if (!isApplyingRemote) onLocalSeek?.invoke(targetSeekMs)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val track = _currentTrack.value ?: return
            when (track.source) {
                TrackSource.YOUTUBE -> {
                    val videoId = track.youtubeVideoId
                    // Ek hi videoId ke liye ek baar relay ko fresh URL ke saath
                    // phir try karte hain (aksar error expired/one-time signed
                    // url ki wajah se hoti hai, poore video ke unavailable hone
                    // ki wajah se nahi) - dusri baar fail hone par track skip.
                    if (videoId != null && relayRetriedForVideoId != videoId) {
                        relayRetriedForVideoId = videoId
                        Log.e("MusicPlayer", "Relay audio playback error, retrying relay resolve once", error)
                        retryRelayResolve(track, videoId)
                    } else {
                        Log.e("MusicPlayer", "Relay audio playback error again, giving up on this track", error)
                        _playbackError.value = "Yeh gaana stream nahi ho paya."
                        registerPlaybackFailureAndMaybeStop()
                    }
                }
                TrackSource.ITUNES -> registerPlaybackFailureAndMaybeStop()
                else -> {}
            }
        }
    }

    private fun retryRelayResolve(track: Track, videoId: String) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    relayService.resolve(videoId = videoId, relayKey = relayApiKey.ifBlank { null })
                }
                if (loadedYoutubeVideoId != videoId) return@launch // stale, user ne dusra gaana chala diya
                runOnController { controller ->
                    val metadata = MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist ?: "Unknown Artist")
                        .build()

                    val mediaItem = MediaItem.Builder()
                        .setUri(response.stream_url)
                        .setMediaId("relay_${track.id}")
                        .setMediaMetadata(metadata)
                        .build()

                    controller.repeatMode = Player.REPEAT_MODE_OFF
                    controller.setMediaItem(mediaItem)
                    controller.prepare()
                    controller.play()
                }
                startProgressTracker()
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Relay retry failed for $videoId, giving up on this track", e)
                if (loadedYoutubeVideoId != videoId) return@launch
                _playbackError.value = "Yeh gaana stream nahi ho paya - relay se connect nahi ho saka."
                registerPlaybackFailureAndMaybeStop()
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
        preloadNextTrack()
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
        preloadNextTrack()
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        preloadNextTrack()
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
                } else {
                    // Pehle yahan kuch nahi hota tha - button dabane pe koi
                    // response hi nahi milta tha (na error, na naya gaana),
                    // isliye "Next" kaam hi nahi karta lagta tha. Ab user ko
                    // clear pata chalega ki koi similar gaana nahi mila
                    // (usually YouTube API key/quota issue ya genre match na milna).
                    _playbackError.value = "Koi agla gaana nahi mila - YouTube API key/quota check karo."
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
        preloadNextTrack()
    }

    // Figures out which track would play next without changing any state -
    // mirrors advance()'s own logic (repeat-one/repeat-all/queue order) so the
    // prediction stays correct with shuffle on. Returns null when the next
    // step would require asking the autoplay provider, since resolving that
    // early would trigger its side effects (network calls, quota use) before
    // the user has actually reached the end of the queue.
    private fun peekNextTrack(): Track? {
        val tracks = _queue.value
        if (tracks.isEmpty() || playOrder.isEmpty()) return null
        if (_repeatMode.value == RepeatMode.ONE) return _currentTrack.value
        val nextPos = playOrderPos + 1
        return when {
            nextPos in playOrder.indices -> tracks.getOrNull(playOrder[nextPos])
            _repeatMode.value == RepeatMode.ALL -> tracks.getOrNull(playOrder[0])
            else -> null
        }
    }

    // Do kaam ek ke baad ek, current track chalte-chalte background me:
    //  1) Agle track ka relay stream URL resolve karo (jaisa pehle hota tha)
    //  2) URL milte hi, USSI TIME uske asli audio chunks disk cache me utaarna
    //     shuru kar do (CacheWriter se) - "chunks jama karna", ExoPlayer ke
    //     asli play hone se pehle hi.
    // Isse buffering sirf pehli baar hoti hai: jab track 2 par switch hota
    // hai, uske bytes pehle se cache me mil jaate hai (PlaybackService ka
    // ExoPlayer isi shared PlaybackCache se padhta hai), aur play() ke end
    // me yeh function dobara call hoke turant track 3 ke chunks jama karna
    // shuru kar deta hai - is tarah har baar sirf agle ek gaane ka hi
    // prefetch chalta hai, kabhi bhi ek se zyada cheezein ek saath download
    // nahi hoti.
    private fun preloadNextTrack() {
        preloadJob?.cancel()
        activeCacheWriter?.cancel()
        activeCacheWriter = null

        val next = peekNextTrack() ?: return
        if (next.source != TrackSource.YOUTUBE) return
        val videoId = next.youtubeVideoId ?: return

        preloadJob = scope.launch {
            try {
                val streamUrl = preloadedStreamUrls[videoId] ?: run {
                    val response = withContext(Dispatchers.IO) {
                        relayService.resolve(videoId = videoId, relayKey = relayApiKey.ifBlank { null })
                    }
                    preloadedStreamUrls[videoId] = response.stream_url
                    response.stream_url
                }

                withContext(Dispatchers.IO) {
                    val dataSource =
                        PlaybackCache.cacheDataSourceFactory(context).createDataSource() as CacheDataSource
                    val cacheWriter = CacheWriter(
                        dataSource,
                        DataSpec(Uri.parse(streamUrl)),
                        /* temporaryBuffer = */ null,
                        /* progressListener = */ null
                    )
                    activeCacheWriter = cacheWriter
                    cacheWriter.cache()
                }
            } catch (e: Exception) {
                // Best-effort prefetch/pre-cache only - agar yeh fail ho jaaye
                // (relay down, cache full, ya prediction badalne se cancel ho
                // gaya) to playYoutubeTrack() apna normal resolve+play flow
                // chalayega jaise pehle hota tha, bas ek extra buffering ke
                // saath jo warna avoid ho jaati.
                Log.w("MusicPlayer", "Next-track prefetch/pre-cache failed or cancelled for $videoId", e)
            } finally {
                activeCacheWriter = null
            }
        }
    }

    private fun playItunesTrack(track: Track) {
        cancelBufferingWatchdog()
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

            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }
        startProgressTracker()
    }

    private fun playYoutubeTrack(track: Track) {
        relayRetriedForVideoId = null
        cancelBufferingWatchdog()
        _currentTrack.value = track
        if (!isApplyingRemote) onLocalSongChange?.invoke(track)

        _isBuffering.value = true
        _isPlaying.value = false
        _playbackPosition.value = 0L
        _duration.value = track.durationMs
        hasReachedPlayingState = false
        _playbackError.value = null

        val videoId = track.youtubeVideoId
        loadedYoutubeVideoId = videoId
        if (videoId == null) {
            registerPlaybackFailureAndMaybeStop()
            return
        }

        // Relay (Revo-music /resolve) se ek real audio stream url resolve
        // karke seedha ExoPlayer se bajao. Fail hone par (relay down, timeout,
        // 401/502 etc.) playerListener.onPlayerError ek baar retry karta hai,
        // uske baad bhi fail ho to track skip/error ho jata hai.
        // FIX: if preloadNextTrack() already resolved this videoId while the
        // previous track was playing, use that cached URL immediately instead
        // of making the caller wait on a fresh (potentially slow) relay call -
        // this is what actually removes the buffering gap between tracks.
        val preloadedUrl = preloadedStreamUrls.remove(videoId)
        scope.launch {
            try {
                val response = if (preloadedUrl != null) {
                    RelayResolveResponse(video_id = videoId, stream_url = preloadedUrl)
                } else {
                    withContext(Dispatchers.IO) {
                        relayService.resolve(videoId = videoId, relayKey = relayApiKey.ifBlank { null })
                    }
                }
                if (loadedYoutubeVideoId != videoId) return@launch // stale, user ne dusra gaana chala diya
                runOnController { controller ->
                    val metadata = MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist ?: "Unknown Artist")
                        .build()

                    val mediaItem = MediaItem.Builder()
                        .setUri(response.stream_url)
                        .setMediaId("relay_${track.id}")
                        .setMediaMetadata(metadata)
                        .build()

                    controller.repeatMode = Player.REPEAT_MODE_OFF
                    controller.setMediaItem(mediaItem)
                    controller.prepare()
                    controller.play()
                }
                startProgressTracker()
                startBufferingWatchdog(videoId)
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Relay resolve failed for $videoId, retrying once", e)
                if (loadedYoutubeVideoId != videoId) return@launch
                if (relayRetriedForVideoId != videoId) {
                    relayRetriedForVideoId = videoId
                    retryRelayResolve(track, videoId)
                } else {
                    _playbackError.value = "Yeh gaana stream nahi ho paya - relay se connect nahi ho saka."
                    registerPlaybackFailureAndMaybeStop()
                }
            }
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
        runOnController { it.pause() }
    }

    fun resume() {
        runOnController { it.play() }
    }

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    fun stop() {
        stopProgressTracker()
        runOnController { it.stop() }
        _isPlaying.value = false
        _playbackPosition.value = 0L
    }

    fun stopAndDismiss() {
        stop()
        _currentTrack.value = null
    }

    fun seekTo(position: Long) {
        runOnController { it.seekTo(position) }
        _playbackPosition.value = position
        if (!isApplyingRemote) onLocalSeek?.invoke(position)
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
                mediaController?.let {
                    if (it.isPlaying) _playbackPosition.value = it.currentPosition.coerceAtLeast(0L)
                }
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
        preloadJob?.cancel()
        activeCacheWriter?.cancel()
        activeCacheWriter = null
        preloadedStreamUrls.clear()
        mediaController?.release()
        mediaController = null
        if (PlaybackBridge.onNext != null) PlaybackBridge.onNext = null
        if (PlaybackBridge.onPrevious != null) PlaybackBridge.onPrevious = null
        if (PlaybackBridge.onSeek != null) PlaybackBridge.onSeek = null
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
