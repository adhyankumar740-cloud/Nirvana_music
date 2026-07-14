package com.example.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    lateinit var player: ExoPlayer
        private set

    // ExoPlayer yahan hamesha ek hi media item ke saath kaam karta hai (poori
    // playlist load nahi hoti, agla/pichla track queue+autoplay logic se app
    // ke andar decide hota hai), isliye iske apne timeline mein kabhi "next"
    // nahi hota - system control center is wajah se Next button hide/disable
    // kar deta tha. Yeh callback next/previous ko hamesha available dikhata
    // hai aur press hone par real logic (MusicPlayer.skipNext/skipPrevious)
    // ko call karta hai, default ExoPlayer seek-to-next/previous behaviour ko
    // chalne diye bina.
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val defaultResult = super.onConnect(session, controller)
            val availableCommands = defaultResult.availablePlayerCommands.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.accept(
                defaultResult.availableSessionCommands,
                availableCommands
            )
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            // FIX: returning RESULT_ERROR_NOT_SUPPORTED here told Media3 the
            // command failed, which caused system UI (notification, lock
            // screen, Bluetooth/Auto) to treat Next/Previous as broken after
            // a single press - sometimes disabling or hiding them entirely.
            // RESULT_INFO_SKIPPED means "handled, just not via the default
            // player behaviour" - it lets us run our own skipNext/skipPrevious
            // logic while keeping the buttons enabled and responsive.
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    PlaybackBridge.onNext?.invoke()
                    return SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    PlaybackBridge.onPrevious?.invoke()
                    return SessionResult.RESULT_INFO_SKIPPED
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // FIX: pehle ExoPlayer seedha raw HTTP data source se stream karta tha,
        // isliye har naye track pe (chahe MusicPlayer ne background me URL
        // resolve bhi kar liya ho) audio bytes fresh network se hi aate the -
        // yani buffering baar baar hoti thi. Ab ExoPlayer ISI shared disk cache
        // (PlaybackCache) se hoke play karta hai jisme MusicPlayer.preloadNextTrack()
        // agle gaane ke chunks pehle se utaar chuka hota hai - agar wo chunk
        // already cache me hai to ExoPlayer network wait kiye bina seedha disk
        // se serve kar deta hai, cache miss hone par hi normal network fetch
        // hoti hai (aur wo bhi cache me save ho jaata hai agli baar ke liye).
        val cacheDataSourceFactory = PlaybackCache.cacheDataSourceFactory(this)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            // FIX: ExoPlayer ab hamesha asli, audible track bajata hai (relay-
            // resolved YouTube audio ya iTunes preview) - purane silent
            // keep-alive setup ke ulat, isko ab audio focus properly handle
            // karna zaroori hai (calls pe pause, duck for notifications, doosre
            // music app ke upar se na bajna), isliye 'true'.
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            // FIX: background playback (screen off) needs a real audio stream
            // to keep buffering over the network without the CPU going to
            // sleep mid-download. WAKE_LOCK was already declared in the
            // manifest but never actually used - WAKE_MODE_NETWORK holds a
            // partial wake lock + wifi lock while playing, which is what
            // actually keeps relay-resolved audio streaming smoothly once the
            // screen turns off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession ?: return super.onTaskRemoved(rootIntent)
        if (!session.player.playWhenReady || session.player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
