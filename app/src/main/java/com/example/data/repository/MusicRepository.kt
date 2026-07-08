package com.example.data.repository

import com.example.data.database.SavedTrackDao
import com.example.data.database.SavedTrackEntity
import com.example.data.model.Lyrics
import com.example.data.model.Track
import com.example.data.model.parseSyncedLyrics
import com.example.data.model.toTrack
import com.example.data.network.ITunesService
import com.example.data.network.LrcLibService
import com.example.data.network.YouTubeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MusicRepository(
    private val apiService: ITunesService,
    private val youtubeService: YouTubeService,
    private val youtubeApiKey: String,
    private val lrcLibService: LrcLibService,
    private val savedTrackDao: SavedTrackDao
) {
    // Cache for Samples feed to ensure instant load times
    private val samplesCache = mutableListOf<Track>()

    /**
     * Samples feed - iTunes `musicVideo` entity, which (unlike the plain "song" entity)
     * provides a real ~30s *video* preview URL, not just audio. Used by the vertical
     * Samples swipe feed so each card can show a short music video instead of a static
     * image + audio-only preview.
     */
    fun getSamplesFeed(term: String = "top hit"): Flow<List<Track>> = flow {
        if (samplesCache.isNotEmpty() && term == "top hit") {
            emit(samplesCache)
        }

        try {
            val response = apiService.search(term = term, media = "musicVideo", entity = "musicVideo", limit = 40)
            val tracks = response.results
                .filter { !it.previewUrl.isNullOrEmpty() }
                .map { it.toTrack(isVideo = true) }

            val enrichedTracks = tracks.map { track ->
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }

            if (term == "top hit") {
                samplesCache.clear()
                samplesCache.addAll(enrichedTracks)
            }
            emit(enrichedTracks)
        } catch (e: Exception) {
            e.printStackTrace()
            if (samplesCache.isNotEmpty()) {
                emit(samplesCache)
            } else {
                emit(emptyList())
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Home screen's default (pre-search) recommendation row - kept on the original
     * iTunes song search so it's unaffected by the Samples/Search changes above.
     */
    fun getHomeFeaturedTracks(term: String = "chill lofi"): Flow<List<Track>> = flow {
        try {
            val response = apiService.search(term = term, media = "music", entity = "song", limit = 30)
            val tracks = response.results
                .filter { !it.previewUrl.isNullOrEmpty() }
                .map { it.toTrack() }
            val enriched = tracks.map { track ->
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }
            emit(enriched)
        } catch (e: Exception) {
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Home Search - now backed by the YouTube Data API v3 so users can find and play
     * full songs (not just 30s previews). Two calls: `search` for matches, then
     * `videos` (batched) to pull real durations, since search results don't include them.
     */
    fun searchTracks(query: String): Flow<List<Track>> = flow {
        try {
            val searchResponse = youtubeService.search(query = query, maxResults = 25, apiKey = youtubeApiKey)
            val videoIds = searchResponse.items.mapNotNull { it.id?.videoId }
            if (videoIds.isEmpty()) {
                emit(emptyList())
                return@flow
            }

            val detailsResponse = youtubeService.getVideoDetails(ids = videoIds.joinToString(","), apiKey = youtubeApiKey)
            val tracks = detailsResponse.items.map { it.toTrack() }
            val enriched = tracks.map { track ->
                val localEntity = savedTrackDao.getSavedTrackById(track.id)
                track.copy(
                    isDownloaded = localEntity?.isDownloaded ?: false,
                    isFavorite = localEntity?.isFavorite ?: false
                )
            }
            emit(enriched)
        } catch (e: Exception) {
            e.printStackTrace()
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    /** Finds the single best-matching YouTube video for a title+artist (used by Samples' "Play Full Song" button). */
    suspend fun findBestYouTubeMatch(title: String, artist: String): Track? = withContext(Dispatchers.IO) {
        try {
            val query = "$title $artist"
            val searchResponse = youtubeService.search(query = query, maxResults = 5, apiKey = youtubeApiKey)
            val videoId = searchResponse.items.firstNotNullOfOrNull { it.id?.videoId } ?: return@withContext null
            val detailsResponse = youtubeService.getVideoDetails(ids = videoId, apiKey = youtubeApiKey)
            detailsResponse.items.firstOrNull()?.toTrack()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Autoplay recommendation for when the queue naturally ends. YouTube deprecated
     * the search.list `relatedToVideoId` parameter in 2023 (no longer supported), so
     * there's no public API for "true" related-video recommendations. This is an
     * honest approximation: search by artist (+ genre as a tiebreaker), skipping
     * anything in [excludeIds] (current queue + recent play history) to avoid repeats.
     */
    suspend fun getAutoplayRecommendation(currentTrack: Track, excludeIds: Set<Long>): Track? = withContext(Dispatchers.IO) {
        try {
            val queries = listOf(
                "${currentTrack.artist} ${currentTrack.genre}".trim(),
                currentTrack.artist
            )
            for (query in queries) {
                if (query.isBlank()) continue
                val searchResponse = youtubeService.search(query = query, maxResults = 15, apiKey = youtubeApiKey)
                val videoIds = searchResponse.items.mapNotNull { it.id?.videoId }
                    .filter { it.hashCode().toLong() !in excludeIds }
                if (videoIds.isEmpty()) continue

                val detailsResponse = youtubeService.getVideoDetails(ids = videoIds.take(10).joinToString(","), apiKey = youtubeApiKey)
                val candidate = detailsResponse.items.map { it.toTrack() }.firstOrNull { it.id !in excludeIds }
                if (candidate != null) return@withContext candidate
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Fetches lyrics from LRCLIB (free, no key). Returns null if the track isn't found - no placeholder text. */
    suspend fun getLyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        try {
            val results = lrcLibService.search(trackName = track.title, artistName = track.artist)
            val best = results.firstOrNull { !it.instrumental.let { inst -> inst == true } } ?: return@withContext null
            val synced = parseSyncedLyrics(best.syncedLyrics)
            val lyrics = Lyrics(plain = best.plainLyrics, synced = synced)
            if (lyrics.isAvailable) lyrics else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getSavedTracks(): Flow<List<Track>> {
        return savedTrackDao.getAllSavedTracks().map { list ->
            list.map { it.toTrack() }
        }
    }

    fun getDownloadedTracks(): Flow<List<Track>> {
        return savedTrackDao.getDownloadedTracks().map { list ->
            list.map { it.toTrack() }
        }
    }

    fun getFavoriteTracks(): Flow<List<Track>> {
        return savedTrackDao.getFavoriteTracks().map { list ->
            list.map { it.toTrack() }
        }
    }

    suspend fun toggleFavorite(track: Track) = withContext(Dispatchers.IO) {
        val existing = savedTrackDao.getSavedTrackById(track.id)
        if (existing != null) {
            val updated = existing.copy(isFavorite = !existing.isFavorite)
            if (!updated.isFavorite && !updated.isDownloaded) {
                savedTrackDao.deleteSavedTrackById(track.id)
            } else {
                savedTrackDao.insertSavedTrack(updated)
            }
        } else {
            val entity = SavedTrackEntity.fromTrack(track, isFavorite = true)
            savedTrackDao.insertSavedTrack(entity)
        }
    }

    suspend fun toggleDownload(track: Track) = withContext(Dispatchers.IO) {
        val existing = savedTrackDao.getSavedTrackById(track.id)
        if (existing != null) {
            val updated = existing.copy(isDownloaded = !existing.isDownloaded)
            if (!updated.isFavorite && !updated.isDownloaded) {
                savedTrackDao.deleteSavedTrackById(track.id)
            } else {
                savedTrackDao.insertSavedTrack(updated)
            }
        } else {
            val entity = SavedTrackEntity.fromTrack(track, isDownloaded = true)
            savedTrackDao.insertSavedTrack(entity)
        }
    }

    suspend fun isTrackFavorite(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        savedTrackDao.getSavedTrackById(trackId)?.isFavorite ?: false
    }

    suspend fun isTrackDownloaded(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        savedTrackDao.getSavedTrackById(trackId)?.isDownloaded ?: false
    }
}
