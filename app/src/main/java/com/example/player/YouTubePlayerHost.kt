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
// The video id the app most recently asked us to load. Sent back with every
// callback so the Android side can tell a fresh event apart from a stale one
// that was actually about the video being swapped OUT (loadVideoById() can
// fire a trailing state/error event for the outgoing video right as the new
// one is requested) - without this, a stale "ended"/error for song A could
// arrive just after song B started and be misread as "song B already ended",
// which used to cause an instant, wrong auto-skip.
var currentVideoId = '';
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
        AndroidBridge.onStateChange(e.data, player.getCurrentTime(), player.getDuration(), currentVideoId);
      },
      onError: function(e) { AndroidBridge.onError(e.data, currentVideoId); }
    }
  });
}
function loadVideo(id) {
  currentVideoId = id;
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
    try { AndroidBridge.onTimeUpdate(player.getCurrentTime(), player.getDuration(), currentVideoId); } catch (e) {}
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

                // baseUrl here becomes the Referer YouTube sees for every request this
                // WebView makes (iframe_api load, video playback, etc). It used to be
                // set to "https://www.youtube.com" - which looked like a convenient way
                // to satisfy the IFrame API's origin check, but it actually means every
                // request claims to BE youtube.com identifying itself, which YouTube's
                // WebView Media Integrity API now rejects as invalid app identification
                // (onError code 153). Per YouTube's Required Minimum Functionality docs,
                // the Referer must instead identify the EMBEDDING APP as a reverse-DNS
                // HTTPS URL (https://developers.google.com/youtube/terms/required-minimum-functionality#set-the-referer),
                // i.e. "https://<your.application.id>" - using the real package name
                // here is what makes YouTube treat this as a legitimate, identified app
                // instead of a spoofed request, which is what was causing every single
                // video (regardless of key/queue) to fail to load.
                loadDataWithBaseURL(
                    "https://${context.applicationContext.packageName}",
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
    fun onStateChange(state: Int, currentTimeSec: Double, durationSec: Double, videoId: String) {
        mainHandler.post {
            musicPlayer?.onYoutubePlayerStateChanged(state, currentTimeSec, durationSec, videoId)
        }
    }

    @JavascriptInterface
    fun onTimeUpdate(currentTimeSec: Double, durationSec: Double, videoId: String) {
        mainHandler.post {
            musicPlayer?.onYoutubeTimeUpdate(currentTimeSec, durationSec, videoId)
        }
    }

    // error codes: 2=invalid id, 5=HTML5 error, 100=not found/removed,
    // 101/150=embedding disallowed by uploader
    @JavascriptInterface
    fun onError(errorCode: Int, videoId: String) {
        mainHandler.post {
            musicPlayer?.onYoutubePlayerError(errorCode, videoId)
        }
    }
}
