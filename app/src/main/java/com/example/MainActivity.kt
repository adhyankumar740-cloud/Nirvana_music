package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.di.AppContainer
import com.example.player.YouTubePlayerHost
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.JamScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SamplesScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.JamViewModel
import com.example.ui.viewmodel.MusicViewModel
import com.example.ui.viewmodel.PlaylistViewModel
import com.example.ui.viewmodel.SamplesViewModel

class MainActivity : ComponentActivity() {

    // Initialize di container locally
    private val appContainer by lazy { AppContainer(applicationContext) }

    // Setup all ViewModels cleanly with factories
    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(applicationContext)
    }

    private val musicViewModel: MusicViewModel by viewModels {
        MusicViewModel.Factory(appContainer.musicRepository, appContainer.musicPlayer)
    }

    private val samplesViewModel: SamplesViewModel by viewModels {
        SamplesViewModel.Factory(appContainer.musicRepository, appContainer.samplesPlayerManager, appContainer.musicPlayer)
    }

    private val jamViewModel: JamViewModel by viewModels {
        JamViewModel.Factory(appContainer.jamManager, appContainer.jamChatManager, appContainer.musicPlayer)
    }

    private val playlistViewModel: PlaylistViewModel by viewModels {
        PlaylistViewModel.Factory(appContainer.musicRepository, appContainer.musicPlayer)
    }

    // No-op either way: if denied, the foreground service/playback still runs,
    // it just won't be able to show the media notification or lock-screen
    // controls (required as a runtime permission on Android 13+/API 33+).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MyApplicationTheme {
                val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
                
                if (!isLoggedIn) {
                    AuthScreen(authViewModel = authViewModel)
                } else {
                    MainAppLayout(
                        musicViewModel = musicViewModel,
                        authViewModel = authViewModel,
                        samplesViewModel = samplesViewModel,
                        jamViewModel = jamViewModel,
                        playlistViewModel = playlistViewModel
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT pause musicPlayer here: it's backed by a Media3 foreground
        // service (PlaybackService) specifically so iTunes-sourced playback
        // keeps running after this Activity is destroyed (backgrounded,
        // rotated, or swiped from Recents). Pausing it here was killing
        // background playback - PlaybackService.onTaskRemoved() stops itself
        // once nothing is playing, so this call was tearing down the service
        // right as the app was backgrounded.
        // The Samples feed is foreground-only content (its ExoPlayer instances
        // live in Compose, not a service), so it's correct to pause that one.
        appContainer.samplesPlayerManager.pause()
    }
}

@Composable
fun MainAppLayout(
    musicViewModel: MusicViewModel,
    authViewModel: AuthViewModel,
    samplesViewModel: SamplesViewModel,
    jamViewModel: JamViewModel,
    playlistViewModel: PlaylistViewModel
) {
    val selectedTab by musicViewModel.selectedTab.collectAsState()

    // "Play Full Song" on the Samples feed starts playback in musicViewModel.player
    // (see SamplesViewModel.playFullSong) but never left the Samples tab, so the
    // song audibly started while the screen stayed on the sample feed - it looked
    // like tapping the button did nothing. Once a full song actually starts
    // playing while the user is still on "samples", jump them to Home, where the
    // BottomPlayerTray / NowPlayingScreen live.
    val fullSongCurrentTrack by musicViewModel.player.currentTrack.collectAsState()
    val isResolvingFullSong by samplesViewModel.isResolvingFullSong.collectAsState()
    LaunchedEffect(fullSongCurrentTrack, isResolvingFullSong) {
        if (selectedTab == "samples" && !isResolvingFullSong && fullSongCurrentTrack != null) {
            musicViewModel.selectTab("home")
        }
    }

    // playbackError was already being set internally by MusicPlayer whenever
    // auto-skip gives up after repeated failures, but nothing ever displayed
    // it - so a failure just looked like silent, endless skipping with no
    // explanation. Surfacing it here (once, at the app root) fixes that for
    // every screen, not just Now Playing.
    val snackbarHostState = remember { SnackbarHostState() }
    val playbackError by musicViewModel.player.playbackError.collectAsState()
    LaunchedEffect(playbackError) {
        val message = playbackError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        musicViewModel.player.clearPlaybackError()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data, containerColor = MaterialTheme.colorScheme.errorContainer)
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == "home",
                    onClick = { 
                        musicViewModel.selectTab("home")
                        samplesViewModel.playerManager.pause() // Pause sample player when switching tabs
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home tab", modifier = Modifier.size(24.dp)) },
                    label = { Text("Home", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == "samples",
                    onClick = { 
                        musicViewModel.selectTab("samples")
                        musicViewModel.player.pause() // Pause main player when viewing samples feed
                    },
                    icon = { Icon(Icons.Default.MusicNote, contentDescription = "Samples tab", modifier = Modifier.size(24.dp)) },
                    label = { Text("Samples", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == "jam",
                    onClick = { 
                        musicViewModel.selectTab("jam")
                        samplesViewModel.playerManager.pause()
                    },
                    icon = { Icon(Icons.Default.Group, contentDescription = "Jam tab", modifier = Modifier.size(24.dp)) },
                    label = { Text("Jam Room", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == "library",
                    onClick = { 
                        musicViewModel.selectTab("library")
                        samplesViewModel.playerManager.pause()
                    },
                    icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library tab", modifier = Modifier.size(24.dp)) },
                    label = { Text("Library", style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray
                    )
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                "home" -> HomeScreen(musicViewModel = musicViewModel, authViewModel = authViewModel, playlistViewModel = playlistViewModel)
                "samples" -> SamplesScreen(samplesViewModel = samplesViewModel)
                "jam" -> JamScreen(jamViewModel = jamViewModel, authViewModel = authViewModel)
                "library" -> LibraryScreen(musicViewModel = musicViewModel, authViewModel = authViewModel, playlistViewModel = playlistViewModel)
            }

            // NOTE: originally kept visibly small here whenever a YouTube full-song
            // was playing, because YouTube's Terms of Service require the official
            // IFrame player to stay visible (it can't be hidden/disguised) when used
            // this way. Hidden below at the user's explicit request, with that ToS
            // risk understood - YouTube could revoke API access for this app if this
            // is flagged, which would break YouTube-sourced playback entirely.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 12.dp, end = 12.dp)
                    .size(1.dp)
            ) {
                YouTubePlayerHost(musicPlayer = musicViewModel.player, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
