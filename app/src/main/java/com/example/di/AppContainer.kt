package com.example.di

import android.content.Context
import com.example.announcement.AnnouncementManager
import com.example.data.database.MusicDatabase
import com.example.data.local.OnboardingPreferences
import com.example.data.network.ITunesService
import com.example.data.network.InnerTubeService
import com.example.data.network.LrcLibService
import com.example.data.repository.MusicRepository
import com.example.data.sync.PlaylistCloudSync
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

    val musicRepository: MusicRepository by lazy {
        MusicRepository(
            apiService = apiService,
            innerTubeService = InnerTubeService,
            lrcLibService = lrcLibService,
            savedTrackDao = database.savedTrackDao(),
            searchHistoryDao = database.searchHistoryDao(),
            playHistoryDao = database.playHistoryDao(),
            playlistDao = database.playlistDao(),
            // Seeds recommendations with the genres/artists picked during
            // first-launch onboarding, so a brand-new user's Home feed
            // reflects their taste before any real listening history exists.
            onboardingGenres = OnboardingPreferences.getSelectedGenres(context),
            onboardingArtists = OnboardingPreferences.getSelectedArtists(context)
        )
    }

    // Reads announcement/update popups pushed from the web Admin Panel
    // (public/admin/index.html) via Firebase Realtime Database.
    val announcementManager: AnnouncementManager by lazy {
        AnnouncementManager(context)
    }

    // Real cross-device Jam (Firebase Realtime Database). Requires a Firebase
    // project + google-services.json - see SETUP_GUIDE.md.
    val jamManager: JamManager by lazy {
        JamManager()
    }

    val jamChatManager: JamChatManager by lazy {
        JamChatManager()
    }

    // Backs playlists up to Firebase Realtime Database, keyed by the logged-in
    // account's email, so "delete the app -> log back in" restores them - see
    // PlaylistCloudSync's own doc comment for how the restore merge works.
    val playlistCloudSync: PlaylistCloudSync by lazy {
        PlaylistCloudSync()
    }

    val musicPlayer: MusicPlayer by lazy {
        MusicPlayer(context)
    }

    val samplesPlayerManager: SamplesPlayerManager by lazy {
        SamplesPlayerManager()
    }
}
