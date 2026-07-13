package com.example.di

import android.content.Context
import com.example.BuildConfig
import com.example.data.database.MusicDatabase
import com.example.data.network.ITunesService
import com.example.data.network.LrcLibService
import com.example.data.network.RelayService
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

    val lrcLibService: LrcLibService by lazy {
        LrcLibService.create()
    }

    // RELAY_BASE_URL is the ONLY network config the app carries - it points at
    // the Netlify proxy (see /netlify-proxy), never directly at YouTube or the
    // real relay backend. No API key of any kind is embedded in the app;
    // the proxy's Netlify Functions add the real YouTube/relay keys
    // server-side from their own environment variables.
    val relayService: RelayService by lazy {
        RelayService.create(BuildConfig.RELAY_BASE_URL)
    }

    val musicRepository: MusicRepository by lazy {
        MusicRepository(
            apiService = apiService,
            relayService = relayService,
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
        MusicPlayer(context, relayService)
    }

    val samplesPlayerManager: SamplesPlayerManager by lazy {
        SamplesPlayerManager()
    }
}
