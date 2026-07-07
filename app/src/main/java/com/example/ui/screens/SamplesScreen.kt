package com.example.ui.screens

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.player.SampleVideoBridge
import com.example.ui.viewmodel.SamplesViewModel

@Composable
fun SamplesScreen(
    samplesViewModel: SamplesViewModel,
    modifier: Modifier = Modifier
) {
    val samples by samplesViewModel.samples.collectAsState()
    val isLoading by samplesViewModel.isLoading.collectAsState()
    val currentIndex by samplesViewModel.currentIndex.collectAsState()
    val isPlaying by samplesViewModel.playerManager.isPlaying.collectAsState()
    val isBuffering by samplesViewModel.playerManager.isBuffering.collectAsState()
    val isResolvingFullSong by samplesViewModel.isResolvingFullSong.collectAsState()

    // Pause player when leaving this screen
    DisposableEffect(Unit) {
        onDispose {
            samplesViewModel.playerManager.pause()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isLoading && samples.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (samples.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Failed to load samples. Try searching on home page.", color = Color.Gray)
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { samples.size })

            // Coordinate Compose pager state swiping with ViewModel
            LaunchedEffect(pagerState.currentPage) {
                samplesViewModel.onSwipe(pagerState.currentPage)
            }

            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val track = samples[page]
                val isActivePage = page == currentIndex

                SampleFeedCard(
                    track = track,
                    isActive = isActivePage,
                    isPlaying = isPlaying && isActivePage,
                    isBuffering = isBuffering && isActivePage,
                    isResolvingFullSong = isResolvingFullSong,
                    playerManager = samplesViewModel.playerManager,
                    onPlayPauseToggle = {
                        if (isPlaying) samplesViewModel.playerManager.pause() else samplesViewModel.playerManager.resume()
                    },
                    onFavoriteClick = { samplesViewModel.toggleFavorite(track) },
                    onDownloadClick = { samplesViewModel.toggleDownload(track) },
                    onPlayFullSongClick = { samplesViewModel.playFullSong(track) }
                )
            }
        }
    }
}

/**
 * Renders the real ~30s video preview (iTunes `musicVideo` previewUrl) for the
 * currently active feed page via a VideoView. Only mounted for the active page -
 * adjacent pages just show the static blurred artwork, keeping this light
 * (no simultaneous decoders running for off-screen pages).
 */
@Composable
private fun SampleVideoSurface(
    track: Track,
    playerManager: com.example.player.SamplesPlayerManager,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).also { videoView ->
                videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    playerManager.onPrepared(mp.duration.toLong())
                    videoView.start()
                }
                videoView.setOnCompletionListener { playerManager.onCompleted() }
                playerManager.bridge = object : SampleVideoBridge {
                    override fun loadAndPlay(url: String) {
                        videoView.setVideoURI(Uri.parse(url))
                        videoView.requestFocus()
                    }
                    override fun pause() {
                        if (videoView.isPlaying) videoView.pause()
                    }
                    override fun resume() {
                        if (!videoView.isPlaying) videoView.start()
                    }
                    override fun seekTo(ms: Long) {
                        videoView.seekTo(ms.toInt())
                    }
                    override fun currentPositionMs(): Long =
                        try { videoView.currentPosition.toLong() } catch (e: Exception) { 0L }
                }
                playerManager.playTrack(track)
            }
        }
    )
}

@Composable
fun SampleFeedCard(
    track: Track,
    isActive: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isResolvingFullSong: Boolean,
    playerManager: com.example.player.SamplesPlayerManager,
    onPlayPauseToggle: () -> Unit,
    onFavoriteClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onPlayFullSongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Blurred background image (always shown, as a backdrop behind the video)
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.35f,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Real video preview - only the active page actually decodes/plays video.
        if (isActive) {
            SampleVideoSurface(
                track = track,
                playerManager = playerManager,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Linear dark gradients for reading comfort
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.5f),
                            Color.Black.copy(alpha = 0.95f)
                        ),
                        startY = 300f
                    )
                )
        )

        // 4. Main Center Card Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp, top = 24.dp, start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Music sign",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SAMPLES FEED",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = track.genre,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Tap-to-play overlay (sits above the video, center of screen)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable { onPlayPauseToggle() },
                contentAlignment = Alignment.Center
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                } else if (!isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play video preview",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            // Bottom track details & actions layout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = track.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        color = Color.LightGray,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.album,
                        color = Color.Gray,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Play Full Song - finds the best YouTube match and plays it in-app.
                    Button(
                        onClick = onPlayFullSongClick,
                        enabled = !isResolvingFullSong,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("play_full_song_button")
                    ) {
                        if (isResolvingFullSong) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play full song",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Play Full Song",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Vertical actions panel
                Column(
                    modifier = Modifier.fillMaxHeight(0.3f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = onFavoriteClick,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Sample Favorite",
                            tint = if (track.isFavorite) MaterialTheme.colorScheme.tertiary else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = onDownloadClick,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Sample Download",
                            tint = if (track.isDownloaded) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
