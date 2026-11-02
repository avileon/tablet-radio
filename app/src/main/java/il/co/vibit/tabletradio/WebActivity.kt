package il.co.vibit.tabletradio

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView

/** דפדפן פנימי ל-VOD חוקי ו-YouTube. נשאר בתוך האפליקציה (מצב קיוסק), עם תמיכה בווידאו במסך מלא. */
class WebActivity : Activity() {

    companion object {
        const val EXTRA_ID = "vod_id"
    }

    private lateinit var web: WebView
    private lateinit var fullscreen: FrameLayout
    private lateinit var topBar: View
    private lateinit var progress: ProgressBar
    private lateinit var title: TextView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var site: VodSite? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        setContentView(R.layout.activity_web)
        site = Stations.vodById(intent.getStringExtra(EXTRA_ID))
        val s = site ?: run { finish(); return }

        web = findViewById(R.id.web)
        fullscreen = findViewById(R.id.fullscreen)
        topBar = findViewById(R.id.webTopBar)
        progress = findViewById(R.id.webProgress)
        title = findViewById(R.id.webTitle)
        title.text = s.name

        findViewById<View>(R.id.webClose).setOnClickListener { finish() }
        findViewById<View>(R.id.webBack).setOnClickListener { if (web.canGoBack()) web.goBack() else finish() }
        findViewById<View>(R.id.webHome).setOnClickListener { web.loadUrl(s.url) }

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // חלק מהאתרים חוסמים "WebView" — מזדהים כ-Chrome רגיל
            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handle(request.url.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handle(url)

            private fun handle(url: String): Boolean {
                // http/https — נשאר בתוך האפליקציה; קישורים לאפליקציות חיצוניות (intent:, market: וכו') — חסומים
                return !(url.startsWith("http://") || url.startsWith("https://"))
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreen.visibility = View.VISIBLE
                web.visibility = View.GONE
                topBar.visibility = View.GONE
                hideSystemUi()
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(s.url)
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        web.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        // הרדיו מושהה בזמן צפייה וחוזר ביציאה
        RadioService.send(this, RadioService.ACTION_PAUSE_FOR_TV)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onStop() {
        RadioService.send(this, RadioService.ACTION_RESUME)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }
}
