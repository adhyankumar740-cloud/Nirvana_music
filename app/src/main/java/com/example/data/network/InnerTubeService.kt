package com.example.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks DIRECTLY to YouTube Music's internal ("InnerTube") API - the same
 * private API music.youtube.com's own web player uses. No server of ours is
 * involved: no Render relay, no YouTube Data API key/quota. This is the same
 * approach used by open-source YT Music clients (e.g. Metrolist/InnerTune).
 *
 * Two calls only, matching what MusicRepository/MusicPlayer already expect
 * from the old RelayService (same response shapes: [RelaySearchResponse],
 * [RelayResolveResponse]) so the rest of the app barely has to change:
 *  - search(query)  -> POST .../youtubei/v1/search   (WEB_REMIX client)
 *  - resolve(videoId) -> POST .../youtubei/v1/player (IOS client, so
 *    adaptive audio formats usually come back as ready-to-use URLs that
 *    don't need YouTube's JS signature-cipher solved)
 *
 * CAVEAT (read this before assuming it's bulletproof):
 * YouTube does not publish this API. It can change field names/response
 * shapes at any time, the same way it occasionally breaks Metrolist/NewPipe/
 * ytmusicapi etc. and needs a small patch. Two things in particular can go
 * wrong later and are NOT handled here:
 *   1. If YouTube stops giving IOS a plain "url" and only returns a
 *      "signatureCipher"/"cipher" field, [resolve] will throw - decoding
 *      that requires running YouTube's player JS (what NewPipeExtractor /
 *      Metrolist's MetrolistExtractor dependency does). Not implemented here
 *      to keep this dependency-free; can be added later if it starts
 *      happening.
 *   2. The clientVersion strings below are frozen at write time. If search
 *      or resolve start failing outright (not just parsing oddly), bumping
 *      these to current YouTube Music web/iOS app versions is the first
 *      thing to try.
 * This file could NOT be tested against the real YouTube endpoints from the
 * sandbox this was written in (no network access there) - please test it for
 * real in Android Studio before relying on it, and check Logcat tag
 * "InnerTubeService" if search/playback come back empty.
 */
object InnerTubeService {

    private const val TAG = "InnerTubeService"

    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX3"
    private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?key=$API_KEY&prettyPrint=false"
    private const val PLAYER_URL = "https://music.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false"

    private const val WEB_REMIX_CLIENT_VERSION = "1.20241030.01.00"
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0"

    private const val IOS_CLIENT_VERSION = "19.29.1"
    private const val IOS_DEVICE_MODEL = "iPhone16,2"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------------

    /**
     * Drop-in replacement for RelayService.search(). [limit] is applied
     * client-side (InnerTube search doesn't take a page-size param; it
     * returns a shelf and we just cap how many of it we keep).
     */
    suspend fun search(query: String, limit: Int = 20): RelaySearchResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("context", webRemixContext())
            put("query", query)
        }

        val request = Request.Builder()
            .url(SEARCH_URL)
            .header("Content-Type", "application/json")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("User-Agent", WEB_USER_AGENT)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "search() HTTP ${response.code}: ${raw.take(500)}")
                throw java.io.IOException("InnerTube search failed: HTTP ${response.code}")
            }
            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                Log.e(TAG, "search() bad JSON: ${raw.take(500)}", e)
                throw e
            }
            val tracks = parseSearchResults(json).take(limit)
            RelaySearchResponse(query = query, results = tracks)
        }
    }

    // ---------------------------------------------------------------------
    // RESOLVE / STREAM URL
    // ---------------------------------------------------------------------

    /** Drop-in replacement for RelayService.resolve(). */
    suspend fun resolve(videoId: String): RelayResolveResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("context", iosContext())
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(PLAYER_URL)
            .header("Content-Type", "application/json")
            .header("User-Agent", IOS_USER_AGENT)
            .header("X-YouTube-Client-Name", "5")
            .header("X-YouTube-Client-Version", IOS_CLIENT_VERSION)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "resolve() HTTP ${response.code}: ${raw.take(500)}")
                throw java.io.IOException("InnerTube player request failed: HTTP ${response.code}")
            }
            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                Log.e(TAG, "resolve() bad JSON: ${raw.take(500)}", e)
                throw e
            }

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            if (status != null && status != "OK") {
                val reason = playability.optString("reason", status)
                throw java.io.IOException("Video unplayable ($status): $reason")
            }

            val streamUrl = bestAudioUrl(json)
                ?: throw java.io.IOException(
                    "No direct-URL audio format returned for $videoId (cipher-only formats " +
                        "aren't decoded by this client - see InnerTubeService doc comment)."
                )

            RelayResolveResponse(video_id = videoId, stream_url = streamUrl)
        }
    }

    // ---------------------------------------------------------------------
    // Request context builders
    // ---------------------------------------------------------------------

    private fun webRemixContext(): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", WEB_REMIX_CLIENT_VERSION)
            put("hl", "en")
            put("gl", "US")
        })
    }

    private fun iosContext(): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "IOS")
            put("clientVersion", IOS_CLIENT_VERSION)
            put("deviceModel", IOS_DEVICE_MODEL)
            put("hl", "en")
            put("gl", "US")
        })
    }

    // ---------------------------------------------------------------------
    // Response parsing
    // ---------------------------------------------------------------------

    /**
     * Recursively collects every JSON object that has a key named [key],
     * anywhere in the tree. Deliberately schema-loose (rather than one fixed
     * path of ["contents"]["tabbedSearchResultsRenderer"][...]) because
     * InnerTube's exact nesting shifts around between result types/YouTube
     * rollouts, and a fixed path is the first thing that silently breaks.
     */
    private fun collectByKey(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { out.add(it) }
                val names = node.names() ?: return
                for (i in 0 until names.length()) {
                    val k = names.getString(i)
                    collectByKey(node.opt(k), key, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectByKey(node.opt(i), key, out)
                }
            }
        }
    }

    private fun parseSearchResults(root: JSONObject): List<RelaySearchTrack> {
        val renderers = mutableListOf<JSONObject>()
        collectByKey(root, "musicResponsiveListItemRenderer", renderers)

        val results = mutableListOf<RelaySearchTrack>()
        for (renderer in renderers) {
            try {
                val videoId = extractVideoId(renderer) ?: continue
                val flexColumns = renderer.optJSONArray("flexColumns") ?: continue

                val title = flexColumnText(flexColumns, 0)?.let { firstRunText(it) } ?: continue
                val secondColumnRuns = flexColumns.optJSONObject(1)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")

                val artist = secondColumnRuns?.let { runs ->
                    (0 until runs.length())
                        .mapNotNull { runs.optJSONObject(it)?.optString("text") }
                        .firstOrNull { it.isNotBlank() && it != " • " }
                } ?: "Unknown Artist"

                val durationSeconds = secondColumnRuns?.let { runs ->
                    (0 until runs.length())
                        .mapNotNull { runs.optJSONObject(it)?.optString("text") }
                        .firstOrNull { Regex("""^\d+(:\d{2}){1,2}$""").matches(it.trim()) }
                        ?.let { parseDurationToSeconds(it) }
                } ?: 0

                val thumbnails = renderer.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                val thumbnailUrl = thumbnails
                    ?.optJSONObject(thumbnails.length() - 1)
                    ?.optString("url")
                    .orEmpty()

                results.add(
                    RelaySearchTrack(
                        video_id = videoId,
                        title = title,
                        artist = artist,
                        thumbnail = thumbnailUrl,
                        duration_sec = durationSeconds
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping one unparseable search result", e)
            }
        }
        return results.distinctBy { it.video_id }
    }

    private fun flexColumnText(flexColumns: JSONArray, index: Int): JSONArray? =
        flexColumns.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

    private fun firstRunText(runs: JSONArray): String? =
        runs.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

    private fun extractVideoId(renderer: JSONObject): String? {
        renderer.optJSONObject("playlistItemData")?.optString("videoId")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    private fun parseDurationToSeconds(text: String): Int {
        val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    /** Picks the highest-bitrate audio-only adaptive format that has a direct (uncyphered) URL. */
    private fun bestAudioUrl(playerResponse: JSONObject): String? {
        val adaptiveFormats = playerResponse.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats") ?: return null

        var bestUrl: String? = null
        var bestBitrate = -1

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType")
            if (!mimeType.startsWith("audio/")) continue

            val url = format.optString("url").takeIf { it.isNotBlank() } ?: continue
            val bitrate = format.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }
        return bestUrl
    }
}
