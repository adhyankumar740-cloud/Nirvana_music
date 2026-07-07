package com.example.data.model

/**
 * Source-agnostic song representation used by the Jam sync layer (JamManager)
 * so that playback state can be broadcast/received over Firebase without
 * depending on any one music source's model (iTunes Track today, YouTube
 * video tomorrow). See TrackSongBridge for the conversion to/from [Track].
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val source: String,
    val streamUrl: String,
    val genre: String,
    val artwork: String
)
