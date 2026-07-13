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

interface YouTubePlayerBridge {
    fun loadVideo(videoId: String)
    fun play()
    fun pause()
    fun seekTo(seconds: Float)
}

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

    // Ek hi videoId ke liye relay resolve sirf ek baar retry hota hai
    // (mid-playback error pe) - taaki relay baar-baar down hone par hum WebView
    // fallback pe atak na jayein, lekin infinite retry loop bhi na bane.
    private var relayRetriedForVideoId: String? = null

    // True jab current YOUTUBE-source track BrokenX relay se resolve hui real
    // audio URL se ExoPlayer ke through baj raha hai (PRIMARY). False = purana
    // WebView/IFrame fallback (SECONDARY) use ho raha hai.
    private var isRelayAudio = false

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

            if (_currentTrack.value?.source == TrackSource.YOUTUBE && !isRelayAudio) {
                if (isPlayingNow) youtubeBridge?.play() else youtubeBridge?.pause()
            }

            if (suppressNextPlayPauseBroadcast) {
                suppressNextPlayPauseBroadcast = false
            } else {
                onLocalPlayPause?.invoke(isPlayingNow, _playbackPosition.value)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            // ExoPlayer state seedha reflect hoti hai jab track ITUNES ho ya
            // relay-resolved audio bajj raha ho - dono me ExoPlayer hi asli
            // source hai. WebView fallback ke case me state bridge callbacks
            // (onYoutubePlayerStateChanged) se aati hai, isliye yahan skip.
            val exoDrivesStateNatively = _currentTrack.value?.source == TrackSource.ITUNES || isRelayAudio
            if (!exoDrivesStateNatively) return
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
            if (reason == Player.DISCONTINUITY_REASON_SEEK && _currentTrack.value?.source == TrackSource.YOUTUBE && !isRelayAudio) {
                val targetSeekMs = newPosition.positionMs
                _playbackPosition.value = targetSeekMs
                youtubeBridge?.seekTo(targetSeekMs / 1000f)
                if (!isApplyingRemote) onLocalSeek?.invoke(targetSeekMs)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val track = _currentTrack.value ?: return
            if (isRelayAudio && track.source == TrackSource.YOUTUBE) {
                val videoId = track.youtubeVideoId
                // WebView fallback background me unreliable hai (WebView JS
                // suspend ho sakta hai jab app background me ho), isliye usse
                // bachne ke liye ek baar relay ko fresh URL ke saath phir try
                // karte hain (aksar error expired/one-time signed url ki wajah
                // se hoti hai, poore video ke unavailable hone ki wajah se
                // nahi) - sirf isi videoId ke liye ek hi baar, taaki loop na bane.
                if (videoId != null && relayRetriedForVideoId != videoId) {
                    relayRetriedForVideoId = videoId
                    Log.e("MusicPlayer", "Relay audio playback error, retrying relay resolve once", error)
                    retryRelayThenFallback(track, videoId)
                } else {
                    Log.e("MusicPlayer", "Relay audio playback error again, falling back to YouTube WebView", error)
                    playYoutubeViaWebViewFallback(track)
                }
            } else if (track.source == TrackSource.ITUNES) {
                registerPlaybackFailureAndMaybeStop()
            }
        }
    }

    private fun retryRelayThenFallback(track: Track, videoId: String) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    relayService.resolve(videoId = videoId, relayKey = relayApiKey.ifBlank { null })
                }
                if (loadedYoutubeVideoId != videoId) return@launch // stale, user ne dusra gaana chala diya
                isRelayAudio = true
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
                Log.e("MusicPlayer", "Relay retry failed for $videoId, falling back to YouTube WebView", e)
                if (loadedYoutubeVideoId != videoId) return@launch
                playYoutubeViaWebViewFallback(track)
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

    private fun preloadNextTrack() {
        preloadJob?.cancel()
        val next = peekNextTrack() ?: return
        if (next.source != TrackSource.YOUTUBE) return
        val videoId = next.youtubeVideoId ?: return
        if (preloadedStreamUrls.containsKey(videoId)) return
        preloadJob = scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    relayService.resolve(videoId = videoId, relayKey = relayApiKey.ifBlank { null })
                }
                preloadedStreamUrls[videoId] = response.stream_url
            } catch (e: Exception) {
                // Best-effort prefetch only - if it fails, playYoutubeTrack()
                // will just resolve it fresh (with its own WebView fallback)
                // when this track is actually reached, same as before.
                Log.w("MusicPlayer", "Prefetch resolve failed for $videoId", e)
            }
        }
    }

    private fun playItunesTrack(track: Track) {
        isRelayAudio = false
        PlaybackBridge.virtualModeActive = false
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

            controller.repeatMode = Player.REPEAT_MODE_OFF // silent-audio fallback repeat na reh jaye
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }
        startProgressTracker()
    }

    private fun playYoutubeTrack(track: Track) {
        isRelayAudio = false
        PlaybackBridge.virtualModeActive = false // fresh track: relay ko pehle try karo real ExoPlayer audio ke saath
        relayRetriedForVideoId = null
        cancelBufferingWatchdog()
        // FIX: agar pichla gaana WebView fallback se baj raha tha, use yahan pause
        // na karne se woh background me chalta hi rehta tha - jab naya gaana relay se
        // seedha ExoPlayer pe start hota, dono gaane ek saath baj jate the, aur dono
        // ke network calls saath chalne se buffering bhi bahut kharab ho jati thi.
        youtubeBridge?.pause()
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

        // PRIMARY: BrokenX relay (Revo-music /resolve) se ek real audio stream
        // url resolve karke seedha ExoPlayer se bajao - WebView/IFrame bilkul
        // touch nahi hota. Fail hone par (relay down, timeout, 401/502 etc.)
        // SECONDARY/fallback WebView tareeka use hota hai.
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
                isRelayAudio = true
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

                    controller.repeatMode = Player.REPEAT_MODE_OFF // silent-audio fallback repeat na reh jaye
                    controller.setMediaItem(mediaItem)
                    controller.prepare()
                    controller.play()
                }
                startProgressTracker()
            } catch (e: Exception) {
                Log.e("MusicPlayer", "Relay resolve failed for $videoId, falling back to YouTube WebView", e)
                if (loadedYoutubeVideoId != videoId) return@launch
                playYoutubeViaWebViewFallback(track)
            }
        }
    }

    // SECONDARY/fallback path - purana silent-audio-trick + WebView IFrame,
    // ab sirf tab chalta hai jab relay resolve fail ho jaye (ya baad me error de).
    private fun playYoutubeViaWebViewFallback(track: Track) {
        isRelayAudio = false
        val videoId = track.youtubeVideoId
        if (videoId == null) {
            registerPlaybackFailureAndMaybeStop()
            return
        }
        loadedYoutubeVideoId = videoId
        _isBuffering.value = true
        _isPlaying.value = false

        // Control-center ab is asli gaane ki duration/position dikhayega
        // (silent keep-alive clip ki nahi) - onYoutubeTimeUpdate() isko
        // asli WebView playback ke saath aage badhata rahega.
        PlaybackBridge.virtualModeActive = true
        PlaybackBridge.virtualPositionMs = 0L
        PlaybackBridge.virtualDurationMs = track.durationMs
        PlaybackBridge.virtualIsBuffering = true

        // --- BACKGROUND SILENT AUDIO TRICK ---
        runOnController { controller ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist ?: "YouTube Stream")
                .build()

            val packageName = context.packageName
            val silentAudioUri = "android.resource://$packageName/raw/silent_audio"

            val fastMediaItem = MediaItem.Builder()
                .setUri(silentAudioUri)
                .setMediaId("yt_${track.id}")
                .setMediaMetadata(metadata)
                .build()

            // FIX: silent_audio.mp3 sirf kuch second ka hai aur pehle loop set nahi tha,
            // isliye kuch second baad hi yeh track khatam (STATE_ENDED) ho jata tha.
            // Jaise hi yeh khatam hota, MediaSession ka underlying player "ended" state me
            // chala jata - background me notification/seek bar gayab ho jata tha aur
            // Android service ko foreground na maan ke thodi der me WebView audio + service
            // dono ko rok/kill kar deta tha. REPEAT_MODE_ONE lagane se yeh keep-alive track
            // hamesha loop karta rahega jab tak asli (WebView) audio chal raha hai.
            controller.repeatMode = Player.REPEAT_MODE_ONE
            controller.setMediaItem(fastMediaItem)
            controller.prepare()
            controller.play() // Service ko active rakhne ke liye silent audio play karna zaruri hai
        }

        youtubeBridge?.loadVideo(videoId)
        startBufferingWatchdog(videoId)
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
        if (_currentTrack.value?.source == TrackSource.YOUTUBE && !isRelayAudio) {
            youtubeBridge?.pause()
            _isPlaying.value = false
            runOnController { it.pause() } // Silent audio pause karna zaroori hai
        } else {
            runOnController { it.pause() }
        }
    }

    fun resume() {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE && !isRelayAudio) {
            youtubeBridge?.play()
            _isPlaying.value = true
            runOnController { it.play() } // Silent audio wapas start karo
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
        if (!isRelayAudio) youtubeBridge?.pause()
        _isPlaying.value = false
        _playbackPosition.value = 0L
        PlaybackBridge.virtualModeActive = false
    }

    fun stopAndDismiss() {
        stop()
        _currentTrack.value = null
    }

    fun seekTo(position: Long) {
        if (_currentTrack.value?.source == TrackSource.YOUTUBE && !isRelayAudio) {
            // WebView fallback: asli seek WebView/IFrame video pe hoti hai.
            // FIX: pehle yahan bhi silent keep-alive clip ko isi (bade) position
            // pe seekTo() kiya jata tha - jo clip ki apni chhoti duration se
            // bahar hota tha aur wahi flicker/instability wapas la deta jo
            // periodic time-sync me thi. Ab bas virtual position turant update
            // karte hain (control center turant naya position dikhaye) - real
            // sync onYoutubeTimeUpdate() se hota rahega.
            youtubeBridge?.seekTo(position / 1000f)
            PlaybackBridge.virtualPositionMs = position
        } else {
            runOnController { it.seekTo(position) } // ITUNES/relay audio ke liye ExoPlayer hi asli player hai
        }
        _playbackPosition.value = position
        if (!isApplyingRemote) onLocalSeek?.invoke(position)
    }

    fun onYoutubePlayerStateChanged(state: Int, currentTimeSec: Double, durationSec: Double, videoId: String) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return
        if (videoId != loadedYoutubeVideoId) return
        when (state) {
            1 -> { // PLAYING
                _isPlaying.value = true
                _isBuffering.value = false
                hasReachedPlayingState = true
                consecutivePlaybackFailures = 0
                cancelBufferingWatchdog()
                startProgressTracker()
                if (suppressNextPlayPauseBroadcast) suppressNextPlayPauseBroadcast = false
                else onLocalPlayPause?.invoke(true, (currentTimeSec * 1000).toLong())
            }
            2 -> { // PAUSED
                _isPlaying.value = false
                _isBuffering.value = false
                stopProgressTracker()
                if (suppressNextPlayPauseBroadcast) suppressNextPlayPauseBroadcast = false
                else onLocalPlayPause?.invoke(false, (currentTimeSec * 1000).toLong())
            }
            3 -> _isBuffering.value = true // BUFFERING
            0 -> { cancelBufferingWatchdog(); handleTrackEnded() } // ENDED
        }
        if (!isRelayAudio) PlaybackBridge.virtualIsBuffering = _isBuffering.value
        if (durationSec > 0) _duration.value = (durationSec * 1000).toLong()
    }

    fun onYoutubeTimeUpdate(currentTimeSec: Double, durationSec: Double, videoId: String) {
        if (_currentTrack.value?.source != TrackSource.YOUTUBE) return
        if (videoId != loadedYoutubeVideoId) return
        val currentMs = (currentTimeSec * 1000).toLong()
        _playbackPosition.value = currentMs

        // FIX: pehle yahan silent keep-alive clip (kuch second ki) ko zabardasti
        // asli gaane ke (bohot bada) position pe seekTo() kiya jata tha - yeh
        // clip ki apni duration se bahar hota tha, isliye ExoPlayer baar-baar
        // clamp/loop discontinuity trigger karta aur control-center ka seek bar
        // flicker karke gayab ho jata (kabhi-kabhi playback hi ruk jata). Ab
        // hum silent clip ko chhedte nahi - bas "virtual" position/duration
        // update karte hain jo PlaybackService ka NotificationFacadePlayer
        // control-center ko dikhata hai.
        if (!isRelayAudio) {
            PlaybackBridge.virtualPositionMs = currentMs
            if (durationSec > 0) PlaybackBridge.virtualDurationMs = (durationSec * 1000).toLong()
        }

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
