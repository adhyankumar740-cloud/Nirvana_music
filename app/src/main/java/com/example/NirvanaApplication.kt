package com.example

import android.app.Application
import com.example.player.ytplayer.cipher.CipherDeobfuscator

/**
 * Initializes the on-device InnerTube playback pipeline (cipher deobfuscation +
 * PoToken generation, both ported from Metrolist) once at process startup, so
 * the first song play doesn't pay for a cold WebView/config init.
 *
 * This replaces the old relay/render server entirely - there is no backend of
 * ours left to be unreliable; search and streaming both talk to YouTube Music
 * directly from the app, the same way Metrolist itself does.
 */
class NirvanaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CipherDeobfuscator.initialize(this)
    }
}
