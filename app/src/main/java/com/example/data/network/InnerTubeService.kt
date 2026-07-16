package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import com.example.data.model.Track
import com.example.data.model.TrackSource
import com.example.player.ytplayer.AudioQuality
import com.example.player.ytplayer.YTPlayerUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single song result from an InnerTube search - mirrors what Home Search shows. */
data class YTSearchTrack(
    val video_id: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val duration_sec: Int
)

data class YTSearchResponse(
    val query: String,
    val results: List<YTSearchTrack>
)

/** A resolved, directly-playable audio stream URL for [video_id]. */
data class YTStreamResolution(
    val video_id: String,
    val stream_url: String
)

/**
 * Talks directly to YouTube Music's own internal (InnerTube) API, entirely
 * on-device - PRIMARY source for both search/metadata (this replaces the old
 * relay's /search) and audio (this replaces the old relay's /resolve).
 *
 * This is the same approach Metrolist and YouTube Music's own official app
 * use: no server of ours sits in between anymore (no relay, no Render
 * deployment, no YouTube Data API key/quota) - so there's nothing left of
 * ours in this path that can go down, get rate-limited, or need redeploying.
 * Streaming URLs are resolved locally via [YTPlayerUtils], which handles
 * signature/n-parameter deciphering and PoToken generation itself.
 */
class InnerTubeService(private val context: Context) {

    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Home Search - searches YouTube Music's own song catalog directly. */
    suspend fun search(query: String, limit: Int = 20): YTSearchResponse = withContext(Dispatchers.IO) {
        val items = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items
        val songs = items.filterIsInstance<SongItem>().take(limit)
        YTSearchResponse(
            query = query,
            results = songs.map { song ->
                YTSearchTrack(
                    video_id = song.id,
                    title = song.title,
                    artist = song.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" },
                    thumbnail = song.thumbnail,
                    duration_sec = song.duration ?: 0
                )
            }
        )
    }

    /**
     * Resolves [videoId] to a real, directly-playable audio stream URL.
     * [YTPlayerUtils] already tries several fallback clients internally (see
     * its own doc comments) before giving up, so a single call here is as
     * resilient as the old relay's own retry logic - just with no network
     * hop to a third-party server in between.
     */
    suspend fun resolve(videoId: String): YTStreamResolution = withContext(Dispatchers.IO) {
        val playbackData = YTPlayerUtils.playerResponseForPlayback(
            videoId = videoId,
            audioQuality = AudioQuality.AUTO,
            connectivityManager = connectivityManager
        ).getOrThrow()
        YTStreamResolution(video_id = videoId, stream_url = playbackData.streamUrl)
    }

    companion object {
        fun create(context: Context): InnerTubeService = InnerTubeService(context)
    }
}

/** Converts an InnerTube search result into the app's unified [Track] model. */
fun YTSearchTrack.toTrack(): Track = Track(
    id = video_id.hashCode().toLong(),
    title = title,
    artist = artist,
    album = "YouTube Music",
    previewUrl = "",
    artworkUrl = thumbnail,
    durationMs = duration_sec * 1000L,
    genre = "Music",
    source = TrackSource.YOUTUBE,
    youtubeVideoId = video_id
)
