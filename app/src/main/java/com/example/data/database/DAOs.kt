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
