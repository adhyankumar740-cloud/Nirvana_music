package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.model.TrackSource
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
import com.example.ui.viewmodel.SamplesViewModel

class MainActivity : ComponentActivity() {

    // Initialize di container locally
    private val appContainer by lazy { AppContainer(applicationContext) }

    // Setup all ViewModels cleanly with factories
    private val authViewModel: AuthViewModel by viewModels()

    private val musicViewModel: MusicViewModel by viewModels {
        MusicViewModel.Factory(appContainer.musicRepository, appContainer.musicPlayer)
    }

    private val samplesViewModel: SamplesViewModel by viewModels {
        SamplesViewModel.Factory(appContainer.musicRepository, appContainer.samplesPlayerManager, appContainer.musicPlayer)
    }

    private val jamViewModel: JamViewModel by viewModels {
        JamViewModel.Factory(appContainer.jamManager, appContainer.jamChatManager, appContainer.musicPlayer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                        jamViewModel = jamViewModel
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop playing to release system audio resources
        appContainer.musicPlayer?.stop()
        appContainer.samplesPlayerManager?.stop()
    }
}

@Composable
fun MainAppLayout(
    musicViewModel: MusicViewModel,
    authViewModel: AuthViewModel,
    samplesViewModel: SamplesViewModel,
    jamViewModel: JamViewModel
) {
    val selectedTab by musicViewModel.selectedTab.collectAsState()

    Scaffold(
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
                "home" -> HomeScreen(musicViewModel = musicViewModel, authViewModel = authViewModel)
                "samples" -> SamplesScreen(samplesViewModel = samplesViewModel)
                "jam" -> JamScreen(jamViewModel = jamViewModel, authViewModel = authViewModel)
                "library" -> LibraryScreen(musicViewModel = musicViewModel, authViewModel = authViewModel)
            }

            // Mounted ONCE, persistently, so it survives tab navigation. Kept visibly
            // small (not hidden) whenever a YouTube full-song is playing - required by
            // YouTube's Terms of Service (the official player can't be hidden/disguised).
            val activeTrack by musicViewModel.player.currentTrack.collectAsState()
            val isYoutubeActive = activeTrack?.source == TrackSource.YOUTUBE
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 12.dp, end = 12.dp)
                    .size(if (isYoutubeActive) 72.dp else 1.dp)
            ) {
                YouTubePlayerHost(musicPlayer = musicViewModel.player, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
