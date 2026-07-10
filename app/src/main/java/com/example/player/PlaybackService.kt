package com.example.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    lateinit var player: ExoPlayer
        private set

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true) [cite: 135]
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Builder ke saath mediaSession initialize kiya taaki system controls linked rahein
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession [cite: 135, 136]

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = mediaSession ?: return super.onTaskRemoved(rootIntent) [cite: 136]
        
        if (!session.player.playWhenReady || session.player.mediaItemCount == 0) { [cite: 136]
            stopSelf() [cite: 136]
        }
        super.onTaskRemoved(rootIntent) [cite: 136, 137]
    }

    override fun onDestroy() {
        mediaSession?.run { [cite: 137]
            player.release() [cite: 137]
            release() [cite: 137]
            mediaSession = null [cite: 137]
        }
        super.onDestroy() [cite: 137]
    }
}
