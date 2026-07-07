package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class YouTubeSearchResponse(
    @Json(name = "items") val items: List<YouTubeSearchItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class YouTubeSearchItem(
    @Json(name = "id") val id: YouTubeVideoId?,
    @Json(name = "snippet") val snippet: YouTubeSnippet?
)

@JsonClass(generateAdapter = true)
data class YouTubeVideoId(
    @Json(name = "videoId") val videoId: String?
)

@JsonClass(generateAdapter = true)
data class YouTubeSnippet(
    @Json(name = "title") val title: String?,
    @Json(name = "channelTitle") val channelTitle: String?,
    @Json(name = "thumbnails") val thumbnails: YouTubeThumbnails?
)

@JsonClass(generateAdapter = true)
data class YouTubeThumbnails(
    @Json(name = "high") val high: YouTubeThumbnail?,
    @Json(name = "medium") val medium: YouTubeThumbnail?,
    @Json(name = "default") val default: YouTubeThumbnail?
)

@JsonClass(generateAdapter = true)
data class YouTubeThumbnail(
    @Json(name = "url") val url: String?
)

@JsonClass(generateAdapter = true)
data class YouTubeVideoListResponse(
    @Json(name = "items") val items: List<YouTubeVideoDetailItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class YouTubeVideoDetailItem(
    @Json(name = "id") val id: String?,
    @Json(name = "snippet") val snippet: YouTubeSnippet?,
    @Json(name = "contentDetails") val contentDetails: YouTubeContentDetails?
)

@JsonClass(generateAdapter = true)
data class YouTubeContentDetails(
    // ISO-8601 duration, e.g. "PT3M45S"
    @Json(name = "duration") val duration: String?
)

/** Converts a search+details result pair into the app's unified [Track] model (source = YOUTUBE). */
fun YouTubeVideoDetailItem.toTrack(): Track {
    val thumb = snippet?.thumbnails?.high?.url
        ?: snippet?.thumbnails?.medium?.url
        ?: snippet?.thumbnails?.default?.url
        ?: ""
    return Track(
        id = (id ?: "").hashCode().toLong(),
        title = snippet?.title ?: "Unknown Title",
        artist = snippet?.channelTitle ?: "Unknown Artist",
        album = "YouTube",
        previewUrl = "",
        artworkUrl = thumb,
        durationMs = parseIso8601DurationMs(contentDetails?.duration),
        genre = "Music",
        source = TrackSource.YOUTUBE,
        youtubeVideoId = id
    )
}

/** Parses a YouTube "contentDetails.duration" ISO-8601 string like PT3M45S into milliseconds. */
fun parseIso8601DurationMs(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    val regex = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
    val match = regex.matchEntire(iso) ?: return 0L
    val (h, m, s) = match.destructured
    val hours = h.toLongOrNull() ?: 0L
    val minutes = m.toLongOrNull() ?: 0L
    val seconds = s.toLongOrNull() ?: 0L
    return ((hours * 3600) + (minutes * 60) + seconds) * 1000L
}
