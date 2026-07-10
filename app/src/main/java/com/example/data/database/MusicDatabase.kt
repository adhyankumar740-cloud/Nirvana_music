package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SavedTrackEntity::class,
        ChatMessageEntity::class,
        SearchHistoryEntity::class,
        PlayHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun savedTrackDao(): SavedTrackDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun playHistoryDao(): PlayHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "harmonix_music_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
