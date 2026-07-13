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
 * Talks to the Netlify proxy in /netlify-proxy, NOT to the real relay backend
 * or YouTube directly. The app sends no API key of any kind - the proxy's two
 * Netlify Functions (/api/search, /api/resolve) hold the real relay backend
 * URL/key and the YouTube Data API key as server-side environment variables
 * and attach them when forwarding the request. Search falls back to the
 * YouTube Data API server-side (inside the proxy) if the relay call fails;
 * playback falls back to the WebView/IFrame player only if /resolve fails.
 */
interface RelayService {

    @GET("search")
    suspend fun search(
        @Query("query") query: String,
        @Query("limit") limit: Int = 20
    ): RelaySearchResponse

    @GET("resolve")
    suspend fun resolve(
        @Query("video_id") videoId: String
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
