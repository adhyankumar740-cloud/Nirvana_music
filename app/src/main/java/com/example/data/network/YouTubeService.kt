package com.example.data.network

import com.example.data.model.YouTubeSearchResponse
import com.example.data.model.YouTubeVideoListResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface YouTubeService {

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("part") part: String = "snippet",
        @Query("type") type: String = "video",
        @Query("videoCategoryId") videoCategoryId: String = "10", // "Music" category
        @Query("maxResults") maxResults: Int = 20,
        @Query("key") apiKey: String
    ): YouTubeSearchResponse

    /** Fetch duration/snippet for a batch of video ids (comma-separated), needed since search doesn't return duration. */
    @GET("videos")
    suspend fun getVideoDetails(
        @Query("id") ids: String,
        @Query("part") part: String = "snippet,contentDetails",
        @Query("key") apiKey: String
    ): YouTubeVideoListResponse

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/youtube/v3/"

        fun create(): YouTubeService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

            return retrofit.create(YouTubeService::class.java)
        }
    }
}
