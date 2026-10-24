package com.kodiakking.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://www.kodiakking.com/"
        private const val SITE_DOMAIN = "kodiakking.com"
        private const val STATE_WEBVIEW = "webview_state"
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout

    private var mainFrameError = false
    private var firstPageLoaded = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            filePathCallback = null
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> hideCustomView()
                webView.canGoBack() -> webView.goBack()
            }
            updateBackCallback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep the splash up until the first page has painted (max a few seconds).
        val splashStart = System.currentTimeMillis()
        splash.setKeepOnScreenCondition {
            !firstPageLoaded && !mainFrameError && System.currentTimeMillis() - splashStart < 4000
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            if (customView == null) {
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                v.setPadding(0, 0, 0, 0)
            }
            insets
        }

        setupWebView()

        swipeRefresh.setColorSchemeResources(R.color.brand)
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setOnRefreshListener { reloadPage() }

        findViewById<Button>(R.id.retryButton).setOnClickListener { reloadPage() }

        onBackPressedDispatcher.addCallback(this, backCallback)
        setupUpdateBar()

        val restored = savedInstanceState?.getBundle(STATE_WEBVIEW)?.let { webView.restoreState(it) }
        if (restored == null) {
            webView.loadUrl(deepLinkUrl(intent) ?: HOME_URL)
        } else {
            firstPageLoaded = true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString KodiakKingApp/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return handleUrl(request.url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mainFrameError = false
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                if (!mainFrameError) {
                    errorView.visibility = View.GONE
                    firstPageLoaded = true
                    showUpdateBarOnce()
                }
                updateBackCallback()
                CookieManager.getInstance().flush()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateBackCallback()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameError = true
                    swipeRefresh.isRefreshing = false
                    progress.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    Toast.makeText(this@MainActivity, R.string.no_app_found, Toast.LENGTH_SHORT).show()
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden(); return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(
                    view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                WindowInsetsControllerCompat(window, root).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
                ViewCompat.requestApplyInsets(root)
                updateBackCallback()
            }

            override fun onHideCustomView() = hideCustomView()
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val uri = Uri.parse(url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (uri.scheme == "https" || uri.scheme == "http")
            ) {
                try {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val req = DownloadManager.Request(uri).apply {
                        setMimeType(mimeType)
                        addRequestHeader("User-Agent", userAgent)
                        CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                        setTitle(fileName)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this, getString(R.string.download_started) + " " + fileName, Toast.LENGTH_SHORT).show()
                    return@setDownloadListener
                } catch (_: Exception) { /* fall through to browser */ }
            }
            openExternal(uri)
        }
    }


    // ---- Update check: runs only when the user taps the button. No background work. ----
    private var updateBarShown = false

    private fun setupUpdateBar() {
        val bar = findViewById<View>(R.id.updateBar)
        findViewById<View>(R.id.updateDismiss).setOnClickListener { bar.visibility = View.GONE }
        findViewById<View>(R.id.updateButton).setOnClickListener { checkForUpdate() }
    }

    private fun showUpdateBarOnce() {
        if (updateBarShown) return
        updateBarShown = true
        findViewById<View>(R.id.updateBar).visibility = View.VISIBLE
        checkForUpdate(silent = true) // one quick check per app open; nothing runs afterwards
    }

    private var updateUrl: String? = null

    private fun checkForUpdate(silent: Boolean = false) {
        val btn = findViewById<android.widget.TextView>(R.id.updateButton)
        updateUrl?.let { openExternal(Uri.parse(it)); return }  // "Update available" tapped -> download
        btn.text = "Checking…"
        btn.isEnabled = false
        Thread {
            val result = runCatching { fetchLatest() }
            runOnUiThread {
                btn.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                val current = BuildConfig.VERSION_NAME
                val latest = result.getOrNull()
                when {
                    latest == null -> {
                        btn.text = getString(R.string.check_update)
                        if (!silent) Toast.makeText(this, "Couldn't check for updates. Try again later.", Toast.LENGTH_SHORT).show()
                    }
                    compareVersions(latest.first, current) > 0 -> {
                        updateUrl = latest.second
                        btn.text = "Update available ⬇"
                        AlertDialog.Builder(this).setTitle("Kodiak King")
                            .setMessage("Version ${latest.first} is available.\nYou have $current.")
                            .setPositiveButton("Download") { _, _ -> openExternal(Uri.parse(latest.second)) }
                            .setNegativeButton("Later", null)
                            .show()
                    }
                    else -> btn.text = "Up to date ✓"
                }
            }
        }.start()
    }

    /** Reads the Kodiak King download link on the site's home page, e.g. odysee.com/@Kodiak_King:4/Kodiak-King-1.2:x */
    private fun fetchLatest(): Pair<String, String> {
        val conn = java.net.URL(HOME_URL).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "KodiakKingApp/${BuildConfig.VERSION_NAME}")
        conn.setRequestProperty("Cache-Control", "no-cache")
        val html = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        val re = Regex("""href="(https?://[^"]*?/Kodiak-King-(\d+(?:[.-]\d+)*)[^"]*)"""", RegexOption.IGNORE_CASE)
        return re.findAll(html)
            .map { it.groupValues[2].replace('-', '.') to it.groupValues[1].replace("&amp;", "&") }
            .maxWithOrNull { a, b -> compareVersions(a.first, b.first) }
            ?: error("no download link found")
    }

    private fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** Returns true if the URL was handled outside the WebView. */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val host = uri.host?.lowercase() ?: return false
            if (host == SITE_DOMAIN || host.endsWith(".$SITE_DOMAIN")) return false
            openExternal(uri)
            return true
        }
        if (scheme == "intent") {
            try {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    intent.getStringExtra("browser_fallback_url")?.let { openExternal(Uri.parse(it)) }
                }
            } catch (_: Exception) { }
            return true
        }
        // tel:, mailto:, sms:, geo:, market:, etc.
        openExternal(uri)
        return true
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reloadPage() {
        errorView.visibility = View.GONE
        mainFrameError = false
        val current = webView.url
        if (current.isNullOrEmpty() || current == "about:blank") webView.loadUrl(HOME_URL) else webView.reload()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(root)
        updateBackCallback()
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = customView != null || webView.canGoBack()
    }

    private fun deepLinkUrl(intent: Intent?): String? {
        val data = intent?.data ?: return null
        val host = data.host?.lowercase() ?: return null
        return if (data.scheme == "https" && (host == SITE_DOMAIN || host.endsWith(".$SITE_DOMAIN")))
            data.toString() else null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkUrl(intent)?.let { webView.loadUrl(it) }
    }

    // Folding/unfolding, rotation and resizing are handled here (see configChanges in the
    // manifest), so the WebView is never recreated and the page is not reloaded.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ViewCompat.requestApplyInsets(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = Bundle()
        webView.saveState(state)
        outState.putBundle(STATE_WEBVIEW, state)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
