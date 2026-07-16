package com.example.player.ytplayer

/**
 * Ported from Metrolist's `com.metrolist.music.constants.AudioQuality`.
 * AUTO lets [YTPlayerUtils] pick the best available adaptive-audio format;
 * LOW/HIGH bias that choice toward the smallest/largest bitrate format.
 */
enum class AudioQuality {
    AUTO,
    LOW,
    HIGH,
}
