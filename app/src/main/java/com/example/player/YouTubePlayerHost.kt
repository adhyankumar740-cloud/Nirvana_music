package com.example.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
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

// 1. JADU: Yeh custom view Android ko chakma dega ki view hamesha VISIBLE hai
class BackgroundWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        // App background mein ho tab bhi system ko bolo ki view VISIBLE hai taaki stream buffer na kare
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }
}

object YouTubeWebViewHolder {
    private var webView: BackgroundWebView? = null
    val contextWrapper = MutableContextWrapper(null)
    val bridge = YoutubeBridgeImpl()

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(context: Context): WebView {
        // Memory leak se bachne ke liye applicationContext wrapper use karenge
        contextWrapper.baseContext = context.applicationContext

        if (webView == null) {
            webView = BackgroundWebView(contextWrapper).apply {
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                
                webChromeClient = WebChromeClient()
                addJavascriptInterface(bridge, "AndroidBridge")
                
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
            val webView = YouTubeWebViewHolder.getOrCreateWebView(ctx)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
        update = { webView ->
            // 2. DYNAMIC UPDATE: Har recomposition par naya player instance bridge ko pass hoga
            YouTubeWebViewHolder.bridge.musicPlayer = musicPlayer
            
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
        }
    )
}

// Dynamic reference holding bridge class
class YoutubeBridgeImpl {
    private val mainHandler = Handler(Looper.getMainLooper())
    var musicPlayer: MusicPlayer? = null

    @JavascriptInterface
    fun onReady() {}

    @JavascriptInterface
    fun onStateChange(state: Int, currentTimeSec: Double, durationSec: Double) {
        mainHandler.post {
            musicPlayer?.onYoutubePlayerStateChanged(state, currentTimeSec, durationSec)
        }
    }

    @JavascriptInterface
    fun onTimeUpdate(currentTimeSec: Double, durationSec: Double) {
        mainHandler.post {
            musicPlayer?.onYoutubeTimeUpdate(currentTimeSec, durationSec)
        }
    }
}
