package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedTrackDao {
    @Query("SELECT * FROM saved_tracks ORDER BY addedAt DESC")
    fun getAllSavedTracks(): Flow<List<SavedTrackEntity>>

    @Query("SELECT * FROM saved_tracks WHERE isDownloaded = 1 ORDER BY addedAt DESC")
    fun getDownloadedTracks(): Flow<List<SavedTrackEntity>>

    @Query("SELECT * FROM saved_tracks WHERE isFavorite = 1 ORDER BY addedAt DESC")
    fun getFavoriteTracks(): Flow<List<SavedTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedTrack(track: SavedTrackEntity)

    @Query("DELETE FROM saved_tracks WHERE id = :id")
    suspend fun deleteSavedTrackById(id: Long)

    @Query("SELECT * FROM saved_tracks WHERE id = :id LIMIT 1")
    suspend fun getSavedTrackById(id: Long): SavedTrackEntity?
}

/** Row shape for "top N distinct values by frequency, most recent first as tiebreak" aggregate queries. */
data class ValueCount(val value: String, val count: Int)

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuery(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentQueries(limit: Int = 50): List<SearchHistoryEntity>

    // Most-searched terms, ranked by frequency then recency. Capped to the
    // last 200 searches so old, no-longer-relevant interests fade out over time.
    @Query(
        """
        SELECT query AS value, COUNT(*) AS count FROM (
            SELECT query, timestamp FROM search_history ORDER BY timestamp DESC LIMIT 200
        )
        GROUP BY query
        ORDER BY count DESC, MAX(timestamp) DESC
        LIMIT :limit
        """
    )
    suspend fun getTopQueries(limit: Int = 5): List<ValueCount>

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()
}

@Dao
interface PlayHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlay(entry: PlayHistoryEntity)

    @Query("SELECT * FROM play_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentPlays(limit: Int = 50): List<PlayHistoryEntity>

    // Top genres by listen frequency, capped to the last 300 plays so taste
    // can drift over time instead of being locked in by old listening habits.
    @Query(
        """
        SELECT genre AS value, COUNT(*) AS count FROM (
            SELECT genre, timestamp FROM play_history WHERE genre != 'Music' ORDER BY timestamp DESC LIMIT 300
        )
        GROUP BY genre
        ORDER BY count DESC, MAX(timestamp) DESC
        LIMIT :limit
        """
    )
    suspend fun getTopGenres(limit: Int = 5): List<ValueCount>

    @Query(
        """
        SELECT artist AS value, COUNT(*) AS count FROM (
            SELECT artist, timestamp FROM play_history ORDER BY timestamp DESC LIMIT 300
        )
        GROUP BY artist
        ORDER BY count DESC, MAX(timestamp) DESC
        LIMIT :limit
        """
    )
    suspend fun getTopArtists(limit: Int = 5): List<ValueCount>

    @Query("DELETE FROM play_history")
    suspend fun clearPlayHistory()
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE jamId = :jamId ORDER BY timestamp ASC")
    fun getMessagesForJam(jamId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE jamId = :jamId")
    suspend fun clearMessagesForJam(jamId: String)
}
