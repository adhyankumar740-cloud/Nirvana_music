package com.example.di

import android.content.Context
import com.example.BuildConfig
import com.example.data.database.MusicDatabase
import com.example.data.network.ITunesService
import com.example.data.network.LrcLibService
import com.example.data.network.RelayService
import com.example.data.network.YouTubeService
import com.example.data.repository.MusicRepository
import com.example.jam.JamChatManager
import com.example.jam.JamManager
import com.example.player.MusicPlayer
import com.example.player.SamplesPlayerManager

class AppContainer(private val context: Context) {

    val database: MusicDatabase by lazy {
        MusicDatabase.getDatabase(context)
    }

    val apiService: ITunesService by lazy {
        ITunesService.create()
    }

    val youtubeService: YouTubeService by lazy {
        YouTubeService.create()
    }

    val lrcLibService: LrcLibService by lazy {
        LrcLibService.create()
    }

    val relayService: RelayService by lazy {
        RelayService.create(BuildConfig.RELAY_BASE_URL)
    }

    val musicRepository: MusicRepository by lazy {
        MusicRepository(
            apiService = apiService,
            youtubeService = youtubeService,
            youtubeApiKey = BuildConfig.YOUTUBE_API_KEY,
            lrcLibService = lrcLibService,
            savedTrackDao = database.savedTrackDao(),
            searchHistoryDao = database.searchHistoryDao(),
            playHistoryDao = database.playHistoryDao(),
            playlistDao = database.playlistDao()
        )
    }

    // Real cross-device Jam (Firebase Realtime Database). Requires a Firebase
    // project + google-services.json - see SETUP_GUIDE.md.
    val jamManager: JamManager by lazy {
        JamManager()
    }

    val jamChatManager: JamChatManager by lazy {
        JamChatManager()
    }

    val musicPlayer: MusicPlayer by lazy {
        MusicPlayer(context, relayService, BuildConfig.RELAY_API_KEY)
    }

    val samplesPlayerManager: SamplesPlayerManager by lazy {
        SamplesPlayerManager()
    }
}
