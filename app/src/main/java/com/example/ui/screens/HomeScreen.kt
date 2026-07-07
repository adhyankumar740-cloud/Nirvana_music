package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.MusicViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    musicViewModel: MusicViewModel,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val username by authViewModel.username.collectAsState()
    val searchQuery by musicViewModel.searchQuery.collectAsState()
    val searchResults by musicViewModel.searchResults.collectAsState()
    val isSearching by musicViewModel.isSearching.collectAsState()
    val homeTracks by musicViewModel.homeTracks.collectAsState()

    val currentTrack by musicViewModel.player.currentTrack.collectAsState()
    val isPlaying by musicViewModel.player.isPlaying.collectAsState()
    val playbackPos by musicViewModel.player.playbackPosition.collectAsState()
    val duration by musicViewModel.player.duration.collectAsState()
    val isBuffering by musicViewModel.player.isBuffering.collectAsState()

    var showPlayerDetail by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("Chill") }

    val categories = listOf("Chill", "Pop", "Lo-Fi", "Electronic", "Alternative")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (currentTrack != null) 72.dp else 0.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Good Evening,",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    Text(
                        text = username,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    musicViewModel.search(it)
                },
                placeholder = { Text("Search songs, artists...", color = Color.Gray) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search icon",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { musicViewModel.search("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = Color.Gray
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .testTag("search_input")
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (searchQuery.isEmpty()) {
                // Categories
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(categories) { category ->
                        val isSelected = category == selectedCategory
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                                )
                                .clickable {
                                    selectedCategory = category
                                    val term = when (category) {
                                        "Lo-Fi" -> "lofi chill"
                                        "Electronic" -> "synthwave"
                                        else -> category.lowercase()
                                    }
                                    musicViewModel.fetchHomeRecommendations()
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = category,
                                color = if (isSelected) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Recommended tracks
                Text(
                    text = "Recommended for You",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(homeTracks) { track ->
                            TrackRow(
                                track = track,
                                onPlayClick = { musicViewModel.playTrack(track, homeTracks) },
                                onFavoriteClick = { musicViewModel.toggleFavorite(track) },
                                onDownloadClick = { musicViewModel.toggleDownload(track) }
                            )
                        }
                    }
                }
            } else {
                // Search Results
                Text(
                    text = "Search Results",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (searchResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No results found for \"$searchQuery\"",
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(searchResults) { track ->
                            TrackRow(
                                track = track,
                                onPlayClick = { musicViewModel.playTrack(track, searchResults) },
                                onFavoriteClick = { musicViewModel.toggleFavorite(track) },
                                onDownloadClick = { musicViewModel.toggleDownload(track) }
                            )
                        }
                    }
                }
            }
        }

        // Bottom Player Tray
        if (currentTrack != null) {
            BottomPlayerTray(
                track = currentTrack!!,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                onPlayPauseClick = {
                    if (isPlaying) musicViewModel.player.pause() else musicViewModel.player.resume()
                },
                onNextClick = { musicViewModel.player.skipNext() },
                onTrayClick = { showPlayerDetail = true }
            )
        }

        // Fullscreen Player Detail Bottom Sheet
        if (showPlayerDetail && currentTrack != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showPlayerDetail = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.background,
                dragHandle = {
                    IconButton(onClick = { showPlayerDetail = false }) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Close player detail",
                            tint = Color.White
                        )
                    }
                }
            ) {
                PlayerDetailView(
                    track = currentTrack!!,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    playbackPos = playbackPos,
                    duration = duration,
                    onPlayPauseClick = {
                        if (isPlaying) musicViewModel.player.pause() else musicViewModel.player.resume()
                    },
                    onPrevClick = { musicViewModel.player.skipPrevious() },
                    onNextClick = { musicViewModel.player.skipNext() },
                    onSeek = { musicViewModel.player.seekTo(it) },
                    onFavoriteClick = { musicViewModel.toggleFavorite(currentTrack!!) },
                    onDownloadClick = { musicViewModel.toggleDownload(currentTrack!!) }
                )
            }
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onPlayClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = "${track.title} artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Favorites & Download controls
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite icon",
                    tint = if (track.isFavorite) MaterialTheme.colorScheme.tertiary else Color.Gray
                )
            }

            IconButton(onClick = onDownloadClick) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download icon",
                    tint = if (track.isDownloaded) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
    }
}

@Composable
fun BottomPlayerTray(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onTrayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF13131A))
            .clickable { onTrayClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = "${track.title} art",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = track.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayPauseClick) {
                    if (isBuffering) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause Icon",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                IconButton(onClick = onNextClick) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip Next",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerDetailView(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playbackPos: Long,
    duration: Long,
    onPlayPauseClick: () -> Unit,
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onFavoriteClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F14))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = "${track.title} artwork large",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(24.dp))
        )

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = Color.Gray,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row {
                IconButton(onClick = onFavoriteClick) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite button",
                        tint = if (track.isFavorite) MaterialTheme.colorScheme.tertiary else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = onDownloadClick) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download button",
                        tint = if (track.isDownloaded) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress Slider
        Slider(
            value = if (duration > 0) playbackPos.toFloat() / duration.toFloat() else 0f,
            onValueChange = {
                if (duration > 0) {
                    onSeek((it * duration).toLong())
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.DarkGray
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatTime(playbackPos), color = Color.Gray, fontSize = 12.sp)
            Text(text = formatTime(duration), color = Color.Gray, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Skip previous",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.width(32.dp))

            IconButton(onClick = onPlayPauseClick, modifier = Modifier.size(64.dp)) {
                if (isBuffering) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = "Play pause",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(32.dp))

            IconButton(onClick = onNextClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Skip next",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(36.dp))
    }
}

fun formatTime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / (1000 * 60)) % 60
    return String.format("%02d:%02d", min, sec)
}
