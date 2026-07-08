package com.example.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

private const val PLAYER_HTML = """
<!DOCTYPE html>
<html>
<body style="margin:0;background:#000;">
<div id="player"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady() {
  player = new YT.Player('player', {
    height: '100%',
    width: '100%',
    videoId: '',
    playerVars: { playsinline: 1, controls: 1, rel: 0, modestbranding: 1 },
    events: {
      onReady: function(e) { AndroidBridge.onReady(); },
      onStateChange: function(e) {
        AndroidBridge.onStateChange(e.data, player.getCurrentTime(), player.getDuration());
      }
    }
  });
}
function loadVideo(id) { if (player && player.loadVideoById) player.loadVideoById(id); }
function playVideo() { if (player && player.playVideo) player.playVideo(); }
function pauseVideo() { if (player && player.pauseVideo) player.pauseVideo(); }
function seekVideo(s) { if (player && player.seekTo) player.seekTo(s, true); }
setInterval(function() {
  if (player && player.getCurrentTime) {
    try { AndroidBridge.onTimeUpdate(player.getCurrentTime(), player.getDuration()); } catch (e) {}
  }
}, 500);
</script>
</body>
</html>
"""

// Global holder to keep WebView alive in the background
object YouTubeWebViewHolder {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(context: Context, musicPlayer: MusicPlayer): WebView {
        if (webView == null) {
            // Crucial: Use applicationContext to decouple from Activity lifecycle
            webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                
                webChromeClient = WebChromeClient()
                addJavascriptInterface(YoutubeBridgeImpl(musicPlayer), "AndroidBridge")
                
                loadDataWithBaseURL(
                    "https://www.youtube.com",
                    PLAYER_HTML,
                    "text/html",
                    "utf-8",
                    null
                )
            }
        }
        return webView!!
    }
}

@Composable
fun YouTubePlayerHost(
    musicPlayer: MusicPlayer,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        factory = { ctx ->
            // Persistent WebView instance fetch karein
            val webView = YouTubeWebViewHolder.getOrCreateWebView(ctx, musicPlayer)
            
            // Agar yeh kisi aur view hierarchy mein attached hai, toh pehle detach karein
            (webView.parent as? ViewGroup)?.removeView(webView)

            // Dynamic callbacks ko update karein taaki controls break na ho
            musicPlayer.youtubeBridge = object : YouTubePlayerBridge {
                override fun loadVideo(videoId: String) {
                    webView.post { webView.evaluateJavascript("loadVideo('$videoId')", null) }
                }
                override fun play() {
                    webView.post { webView.evaluateJavascript("playVideo()", null) }
                }
                override fun pause() {
                    webView.post { webView.evaluateJavascript("pauseVideo()", null) }
                }
                override fun seekTo(seconds: Float) {
                    webView.post { webView.evaluateJavascript("seekVideo($seconds)", null) }
                }
            }
            
            webView
        }
    )
}

private class YoutubeBridgeImpl(private val musicPlayer: MusicPlayer) : Any() {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onReady() {}

    @JavascriptInterface
    fun onStateChange(state: Int, currentTimeSec: Double, durationSec: Double) {
        mainHandler.post {
            musicPlayer.onYoutubePlayerStateChanged(state, currentTimeSec, durationSec)
        }
    }

    @JavascriptInterface
    fun onTimeUpdate(currentTimeSec: Double, durationSec: Double) {
        mainHandler.post {
            musicPlayer.onYoutubeTimeUpdate(currentTimeSec, durationSec)
        }
    }
}
