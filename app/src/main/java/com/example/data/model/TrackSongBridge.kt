package com.example.data.model

/**
 * Converts between [Track] (the app's current, iTunes-sourced playback model)
 * and [Song] (the source-agnostic model JamManager syncs over Firebase).
 *
 * Kept as a single small bridge so that when Home Search / Samples move to a
 * different source (e.g. YouTube), only this file (and Track's shape) needs
 * to change - JamManager and JamChatManager stay untouched.
 */
object TrackSongBridge {
    fun toSong(track: Track): Song = Song(
        id = track.id.toString(),
        title = track.title,
        artist = track.artist,
        duration = track.durationMs,
        source = "itunes",
        streamUrl = track.previewUrl,
        genre = track.genre,
        artwork = track.artworkUrl
    )

    fun toTrack(song: Song): Track = Track(
        id = song.id.toLongOrNull() ?: song.id.hashCode().toLong(),
        title = song.title,
        artist = song.artist,
        album = "",
        previewUrl = song.streamUrl,
        artworkUrl = song.artwork,
        durationMs = song.duration,
        genre = song.genre
    )
}
