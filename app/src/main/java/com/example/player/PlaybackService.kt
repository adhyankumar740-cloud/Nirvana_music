package com.example.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.example.BuildConfig

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    lateinit var player: ExoPlayer
        private set

    // ExoPlayer yahan sirf silent keep-alive track chalata hai, isliye iske apne
    // timeline mein kabhi "next" nahi hota - system control center is wajah se
    // Next button hide/disable kar deta tha. Yeh callback next/previous ko
    // hamesha available dikhata hai aur press hone par real logic
    // (MusicPlayer.skipNext/skipPrevious) ko call karta hai, default
    // ExoPlayer seek-to-next/previous behaviour ko chalne diye bina.
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
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    PlaybackBridge.onNext?.invoke()
                    return SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    PlaybackBridge.onPrevious?.invoke()
                    return SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }
    }

    // System control-center/lock-screen ka seek bar isi Player object se apni
    // position/duration/state padhta hai. Jab tak asli gaana relay-resolved
    // audio (ya iTunes) se seedha ExoPlayer pe baj raha hai, uski apni values
    // hi sahi hain - koi wrapping zaroori nahi. Lekin jab (SECONDARY) WebView
    // fallback active ho aur ExoPlayer sirf silent keep-alive clip loop kar
    // raha ho, tab uski chhoti si duration/position ki jagah hum
    // PlaybackBridge me MusicPlayer dwara update ki hui "virtual" (asli
    // gaane ki) values dikhate hain - taaki seek bar sahi length dikhaye,
    // sahi position pe aage badhe, aur baar-baar reset/flicker na ho.
    private class NotificationFacadePlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        override fun getCurrentPosition(): Long =
            if (PlaybackBridge.virtualModeActive) PlaybackBridge.virtualPositionMs
            else super.getCurrentPosition()

        override fun getDuration(): Long =
            if (PlaybackBridge.virtualModeActive && PlaybackBridge.virtualDurationMs > 0) PlaybackBridge.virtualDurationMs
            else super.getDuration()

        override fun getContentDuration(): Long = getDuration()

        override fun getBufferedPosition(): Long =
            if (PlaybackBridge.virtualModeActive) PlaybackBridge.virtualPositionMs
            else super.getBufferedPosition()

        override fun getContentBufferedPosition(): Long = getBufferedPosition()

        override fun getBufferedPercentage(): Int =
            if (PlaybackBridge.virtualModeActive) 100 else super.getBufferedPercentage()

        // Silent clip REPEAT_MODE_ONE ke saath baar-baar khud STATE_BUFFERING/
        // STATE_ENDED se guzarta rehta hai jab woh loop karta hai - agar yeh
        // seedha control-center tak pahunch jaye to notification/seek bar
        // baar-baar flicker karta ya gayab ho jata (yehi original bug tha).
        // Virtual mode me hum sirf MusicPlayer ki asli buffering state dikhate
        // hain, silent clip ki nahi.
        override fun getPlaybackState(): Int {
            if (PlaybackBridge.virtualModeActive) {
                return if (PlaybackBridge.virtualIsBuffering) Player.STATE_BUFFERING else Player.STATE_READY
            }
            return super.getPlaybackState()
        }

        override fun isPlayingAd(): Boolean = false

        // Lock-screen/notification seek bar ko drag karne par yeh call hota
        // hai. Virtual mode me silent clip ko seek karne ka koi fayda nahi
        // (uski duration hi kuch second ki hai) - iski jagah asli WebView
        // video ko seek karo via MusicPlayer.
        override fun seekTo(positionMs: Long) {
            if (PlaybackBridge.virtualModeActive) {
                PlaybackBridge.virtualPositionMs = positionMs
                PlaybackBridge.onSeek?.invoke(positionMs)
            } else {
                super.seekTo(positionMs)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Relay ka /audio/{filename} endpoint bhi X-Relay-Key maangta hai, aur
        // ExoPlayer seedha (app backend se hoke nahi) us URL ko hit karta hai -
        // isliye header yahan ExoPlayer ke HTTP data source pe default request
        // property ke roop me lagana zaroori hai.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            if (BuildConfig.RELAY_API_KEY.isNotBlank()) {
                setDefaultRequestProperties(mapOf("X-Relay-Key" to BuildConfig.RELAY_API_KEY))
            }
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setAudioAttributes(audioAttributes, false) // silent track hai, isko audio focus fight karne ki zaroorat nahi
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, NotificationFacadePlayer(player))
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
