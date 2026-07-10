package com.example.player

/**
 * PlaybackService ka ExoPlayer sirf ek silent "keep-alive" track play karta hai
 * (asli gaana WebView ke andar YouTube se bajta hai), isliye ExoPlayer ke apne
 * timeline mein kabhi "next item" hota hi nahi - Media3 isi wajah se phone ke
 * control center / lock screen mein "Next" button ko automatically disable/hide
 * kar deta tha (sirf "Previous" dikhta tha, jo asal mein current gaana hi
 * restart karta tha).
 *
 * Fix: PlaybackService apne MediaSession.Callback mein next/previous command
 * ko force-enable karta hai, aur press hone par yahin registered callbacks
 * (MusicPlayer.skipNext / skipPrevious) ko call karta hai - taaki system UI
 * ka next/previous bhi wahi real queue+autoplay logic use kare jo app ke andar
 * ke buttons use karte hain.
 */
object PlaybackBridge {
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
}

