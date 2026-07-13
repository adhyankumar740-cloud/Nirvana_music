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
    // Control-center/lock-screen se seek bar drag hone par (WebView fallback
    // active hote hue) yeh asli MusicPlayer.seekTo() ko call karta hai, taaki
    // asli WebView video seek ho - silent keep-alive clip ko seek karne ka
    // koi matlab nahi (uski duration hi kuch second ki hai).
    var onSeek: ((Long) -> Unit)? = null

    // --- Virtual (WebView-fallback) playback position/duration ---
    // Jab asli gaana WebView/IFrame se baj raha ho (relay resolve fail hone ke
    // baad ka SECONDARY path), PlaybackService ka ExoPlayer sirf chhota sa
    // silent_audio.mp3 loop kar raha hota hai - uski apni currentPosition/
    // duration control-center ke seek bar ke liye bekaar hai (sirf kuch second
    // ki hai aur baar-baar loop hoti hai). Pehle MusicPlayer har time-update pe
    // is chhoti si clip ko zabardasti asli gaane ke (bohot bada) position pe
    // seekTo() karne ki koshish karta tha - jo clip ki apni duration se bahar
    // hota tha, isliye ExoPlayer baar-baar clamp/loop/discontinuity trigger
    // karta, aur control-center ka seek bar flicker karke gayab ho jata tha
    // (kabhi-kabhi playback hi ruk jata).
    //
    // Fix: ab MusicPlayer seekTo() zabardasti nahi karta - bas yeh "virtual"
    // values update karta hai, aur PlaybackService apne MediaSession ko ek
    // ForwardingPlayer wrapper deta hai jo control-center ko yehi virtual
    // (asli gaane ke) position/duration/buffering-state dikhata hai, silent
    // clip ki apni values ki jagah. Underlying silent ExoPlayer bilkul
    // disturb nahi hota, isliye woh stable rehta hai aur service zinda rehti
    // hai.
    @Volatile var virtualModeActive: Boolean = false
    @Volatile var virtualPositionMs: Long = 0L
    @Volatile var virtualDurationMs: Long = 0L
    @Volatile var virtualIsBuffering: Boolean = false
}

