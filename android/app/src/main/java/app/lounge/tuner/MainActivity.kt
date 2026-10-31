package app.lounge.tuner

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Tuner's TV interface: the same index.html as the Linux version, in a fullscreen WebView. */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (port, token) = Tuner.start(this)
        val origin = "http://127.0.0.1:$port"
        web = WebView(this).apply {
            setBackgroundColor(0xff141414.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    // Nothing outside Tuner opens in here
                    if (url.host != "127.0.0.1" || url.port != port) return true
                    // There is no Lounge home screen on Android, so going home leaves the app
                    if (url.path.isNullOrEmpty() || url.path == "/") {
                        finish()
                        return true
                    }
                    return false
                }

                // A TV short on memory can stop the page; start it again instead of letting the app crash with it
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.w(TAG, "page stopped, crashed=${detail.didCrash()}")
                    recreate()
                    return true
                }
            }
        }
        setContentView(web)
        CookieManager.getInstance().setCookie(origin, "tuner=$token; Path=/; HttpOnly; SameSite=Strict") {
            web.loadUrl("$origin/tuner")
        }
    }

    override fun onResume() {
        super.onResume()
        web.requestFocus()
    }

    // The remote's Back goes to the page, which decides whether it closes a menu or leaves Tuner
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) web.evaluateJavascript("remote('back')", null)
        return true
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
