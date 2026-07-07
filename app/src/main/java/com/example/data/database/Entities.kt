package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.Track
import com.example.data.model.TrackSource

@Entity(tableName = "saved_tracks")
data class SavedTrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val previewUrl: String,
    val artworkUrl: String,
    val durationMs: Long,
    val genre: String,
    val isDownloaded: Boolean,
    val isFavorite: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
    // Persisted so a favorited/downloaded YouTube full-song still plays correctly
    // (not just its iTunes-preview counterpart) when reopened from Library.
    val source: String = "ITUNES",
    val youtubeVideoId: String? = null,
    val isVideo: Boolean = false
) {
    fun toTrack(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        previewUrl = previewUrl,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        genre = genre,
        isDownloaded = isDownloaded,
        isFavorite = isFavorite,
        source = if (source == "YOUTUBE") TrackSource.YOUTUBE else TrackSource.ITUNES,
        youtubeVideoId = youtubeVideoId,
        isVideo = isVideo
    )

    companion object {
        fun fromTrack(track: Track, isDownloaded: Boolean = track.isDownloaded, isFavorite: Boolean = track.isFavorite) = SavedTrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            previewUrl = track.previewUrl,
            artworkUrl = track.artworkUrl,
            durationMs = track.durationMs,
            genre = track.genre,
            isDownloaded = isDownloaded,
            isFavorite = isFavorite,
            source = track.source.name,
            youtubeVideoId = track.youtubeVideoId,
            isVideo = track.isVideo
        )
    }
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val jamId: String,
    val senderId: String,
    val senderName: String,
    val senderAvatarUrl: String,
    val text: String,
    val timestamp: Long,
    val replyToId: String?,
    val replyToText: String?,
    val replyToSenderName: String?,
    val reactionsJson: String, // JSON serialization of reactions
    val status: String // "SENT", "DELIVERED", "READ"
)
