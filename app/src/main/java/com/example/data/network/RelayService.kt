package com.example.data.network

import com.example.data.model.Track
import com.example.data.model.TrackSource
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class RelayResolveResponse(
    val video_id: String,
    val stream_url: String
)

@JsonClass(generateAdapter = true)
data class RelaySearchTrack(
    val video_id: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val duration_sec: Int
)

@JsonClass(generateAdapter = true)
data class RelaySearchResponse(
    val query: String,
    val results: List<RelaySearchTrack>
)

/**
 * Talks to the BrokenX relay backend (Revo-music's app.py) - PRIMARY source
 * for both search/metadata (/search, via youtube_search - no Google API key)
 * and audio (/resolve). Falls back to YouTube Data API for search and to the
 * WebView/IFrame player for playback only if the relay call fails.
 */
interface RelayService {

    @GET("search")
    suspend fun search(
        @Query("query") query: String,
        @Query("limit") limit: Int = 20,
        @Header("X-Relay-Key") relayKey: String?
    ): RelaySearchResponse

    @GET("resolve")
    suspend fun resolve(
        @Query("video_id") videoId: String,
        @Header("X-Relay-Key") relayKey: String?
    ): RelayResolveResponse

    companion object {
        fun create(baseUrl: String): RelayService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                // download_song() fetches/converts from YouTube on first resolve,
                // so the first request per video id can be slow - the usual 15s
                // API timeout would be too short here.
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl) // must end with "/", e.g. "https://your-relay.com/"
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

            return retrofit.create(RelayService::class.java)
        }
    }
}

/**
 * Converts a relay /search result into the app's unified [Track] model.
 * Uses the SAME id scheme as YouTubeVideoDetailItem.toTrack()
 * (videoId.hashCode()) so favorites/downloads/history stay consistent
 * regardless of whether a video was found via the relay or the YouTube
 * Data API fallback.
 */
fun RelaySearchTrack.toTrack(): Track = Track(
    id = video_id.hashCode().toLong(),
    title = title,
    artist = artist,
    album = "YouTube",
    previewUrl = "",
    artworkUrl = thumbnail,
    durationMs = duration_sec * 1000L,
    genre = "Music",
    source = TrackSource.YOUTUBE,
    youtubeVideoId = video_id
)
