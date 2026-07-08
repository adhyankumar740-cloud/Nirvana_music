package com.example.player

import android.annotation.SuppressLint
import android.content.Context
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
var isPlayerReady = false;
// If loadVideo()/playVideo() arrive while the IFrame API is still loading
// (typically the very first "Play" tap right after the app starts, before
// the external youtube.com/iframe_api script + YT.Player finish initializing),
// the calls used to be silently dropped (player was undefined) - the UI stayed
// on "buffering" forever since no video was ever actually loaded. Now we queue
// the pending action and flush it once onReady fires.
var pendingVideoId = null;
var pendingShouldPlay = false;
function onYouTubeIframeAPIReady() {
  player = new YT.Player('player', {
    height: '100%',
    width: '100%',
    videoId: '',
    playerVars: { playsinline: 1, controls: 1, rel: 0, modestbranding: 1 },
    events: {
      onReady: function(e) {
        isPlayerReady = true;
        AndroidBridge.onReady();
        if (pendingVideoId) {
          player.loadVideoById(pendingVideoId);
          pendingVideoId = null;
        } else if (pendingShouldPlay) {
          player.playVideo();
        }
        pendingShouldPlay = false;
      },
      onStateChange: function(e) {
        AndroidBridge.onStateChange(e.data, player.getCurrentTime(), player.getDuration());
      }
    }
  });
}
function loadVideo(id) {
  if (isPlayerReady && player && player.loadVideoById) {
    player.loadVideoById(id);
  } else {
    pendingVideoId = id;
  }
}
function playVideo() {
  if (isPlayerReady && player && player.playVideo) {
    player.playVideo();
  } else {
    pendingShouldPlay = true;
  }
}
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

// Background execution ko simulate karne ke liye custom WebView
class BackgroundWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }
}

object YouTubeWebViewHolder {
    private var webView: BackgroundWebView? = null
    val bridge = YoutubeBridgeImpl()

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(context: Context): WebView {
        if (webView == null) {
            // Bina kisi null wrapper ke, safe context initialize kiya
            webView = BackgroundWebView(context.applicationContext).apply {
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
            // Bridge ko hamesha latest musicPlayer ka reference milega bina crash ke
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
