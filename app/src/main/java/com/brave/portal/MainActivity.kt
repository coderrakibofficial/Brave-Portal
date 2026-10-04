package com.brave.portal

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.provider.MediaStore
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.net.URISyntaxException

/**
 * BRAVE Portal - a thin, secure WebView container around the existing website.
 * All real functionality (login, classes, videos...) stays on the website.
 */
class MainActivity : AppCompatActivity() {

    // ---- config (comes from brave.properties via BuildConfig) ----
    private val startUrl: String = BuildConfig.WEBSITE_URL
    // ---- views ----
    private lateinit var webView: WebView
    private lateinit var content: FrameLayout
    private lateinit var fullscreenHost: FrameLayout
    private lateinit var loadingView: View
    private lateinit var loadingText: TextView
    private lateinit var offlineView: View
    private lateinit var offlineTitle: TextView
    private lateinit var offlineMessage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // ---- state ----
    private val mainHandler = Handler(Looper.getMainLooper())
    private var firstLoadDone = false
    private var pageHadError = false
    private var failedUrl: String? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: (() -> Unit)? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cameraFile: File? = null
    private var notificationsReady = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileCallback
            fileCallback = null
            val photo = cameraFile
            cameraFile = null
            val data = result.data
            val pickedSomething = data != null && (data.data != null || data.clipData != null)
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                pickedSomething -> WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
                photo != null && photo.length() > 0 ->      // photo taken with the camera
                    arrayOf(FileProvider.getUriForFile(this, "$packageName.fileprovider", photo))
                else -> null
            }
            cb?.onReceiveValue(uris)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* nothing to do */ }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDownload
            pendingDownload = null
            if (granted) action?.invoke() else toast(R.string.download_failed)
        }

    // =====================================================================
    // Lifecycle
    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()               // native splash (dismissed after first frame)
        super.onCreate(savedInstanceState)

        setupWindow()
        setContentView(R.layout.activity_main)
        bindViews()
        applyInsets()
        setupWebView()
        setupPullToRefresh()
        setupBackHandling()
        Notifications.createChannels(this)

        offlineView.findViewById<Button>(R.id.retryButton).setOnClickListener { retry() }

        // Restore the page after process death, otherwise open the start page / deep link.
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) webView.loadUrl(initialUrl(intent))
        else intentTargetUrl(intent)?.let { webView.loadUrl(it) }   // notification tap after process restore

        // Show a hint if the first load is slow
        mainHandler.postDelayed({
            if (!firstLoadDone && loadingView.visibility == View.VISIBLE) {
                loadingText.setText(R.string.loading_slow)
            }
        }, 8000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentTargetUrl(intent)?.let { webView.loadUrl(it) }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    override fun onStop() {
        super.onStop()
        unregisterNetworkCallback()
        CookieManager.getInstance().flush()     // persist session cookies
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // =====================================================================
    // Window / insets
    // =====================================================================
    @Suppress("DEPRECATION")
    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false      // light icons on dark bar
            isAppearanceLightNavigationBars = false
        }
    }

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        content = findViewById(R.id.content)
        fullscreenHost = findViewById(R.id.fullscreenHost)
        loadingView = findViewById(R.id.loadingView)
        loadingText = findViewById(R.id.loadingText)
        offlineView = findViewById(R.id.offlineView)
        offlineTitle = findViewById(R.id.offlineTitle)
        offlineMessage = findViewById(R.id.offlineMessage)
        progress = findViewById(R.id.progress)
        swipeRefresh = findViewById(R.id.swipeRefresh)
    }

    /** Keeps the website below the status bar and above the keyboard / nav bar. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.ime() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // =====================================================================
    // WebView setup
    // =====================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)   // needed by some embedded video players
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                    // localStorage / sessionStorage
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true                      // respect the site's <meta viewport>
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100                              // ignore system font scale (keeps layout intact)
            setSupportMultipleWindows(true)             // target="_blank" / window.open
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT        // normal HTTP caching
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.webViewClient = PortalWebViewClient()
        webView.webChromeClient = PortalChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    /** Portal URL requested by a notification tap (extra "url") or a deep link; null if none/unsafe. */
    private fun intentTargetUrl(intent: Intent?): String? {
        PortalUrls.safeInternalUrl(intent?.getStringExtra(Notifications.EXTRA_URL))?.let { return it }
        return intent?.data?.let { if (isInternal(it)) it.toString() else null }
    }

    private fun initialUrl(intent: Intent?): String = intentTargetUrl(intent) ?: startUrl

    // =====================================================================
    // Pull-to-refresh (can be switched off with PULL_TO_REFRESH=false in brave.properties)
    // =====================================================================
    private fun setupPullToRefresh() {
        swipeRefresh.isEnabled = BuildConfig.PULL_TO_REFRESH
        swipeRefresh.setColorSchemeResources(R.color.brave_gold)
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.brave_black)
        // long pull needed -> avoids accidental refreshes while scrolling a lesson
        swipeRefresh.setDistanceToTriggerSync((160 * resources.displayMetrics.density).toInt())
        swipeRefresh.setOnRefreshListener { webView.reload() }
    }

    /** Runs once after the first successful page load (so the permission prompt never covers the splash). */
    private fun setupNotificationsOnce() {
        if (notificationsReady) return
        notificationsReady = true
        Notifications.subscribe(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = getSharedPreferences("brave_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("asked_notification_permission", false)) {
                prefs.edit().putBoolean("asked_notification_permission", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // =====================================================================
    // Back button
    // =====================================================================
    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()          // leave fullscreen video only
                    webView.canGoBack() -> webView.goBack()         // previous portal page
                    else -> finish()                                // nothing left: exit app
                }
            }
        })
    }

    // =====================================================================
    // URL routing
    // =====================================================================
    private fun isInternal(uri: Uri): Boolean = PortalUrls.isInternal(uri)

    /** @return true if the URL was handled outside the WebView (do NOT load it in the WebView). */
    private fun routeExternally(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (isInternal(uri)) false else { openExternal(Intent(Intent.ACTION_VIEW, uri)); true }
            }
            "about", "blob", "data", "javascript" -> false
            "file", "content" -> true                       // never allow local files
            "intent" -> { openIntentUrl(uri.toString()); true }
            // mailto:, tel:, sms:, smsto:, geo:, whatsapp:, market:, etc.
            else -> { openExternal(Intent(Intent.ACTION_VIEW, uri)); true }
        }
    }

    private fun openExternal(intent: Intent) {
        try {
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        } catch (e: SecurityException) {
            toast(R.string.no_app_found)
        }
    }

    /** Handles Chrome-style intent:// links safely. */
    private fun openIntentUrl(url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null        // security: never allow explicit components
                selector = null
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrEmpty()) {
                    val fb = Uri.parse(fallback)
                    if (isInternal(fb)) webView.loadUrl(fallback)
                    else openExternal(Intent(Intent.ACTION_VIEW, fb))
                } else if (intent.`package` != null) {
                    openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${intent.`package`}")))
                } else toast(R.string.no_app_found)
            }
        } catch (e: URISyntaxException) {
            toast(R.string.no_app_found)
        }
    }

    // =====================================================================
    // Loading / offline UI
    // =====================================================================
    private fun hideLoading() {
        if (loadingView.visibility != View.VISIBLE) return
        loadingView.animate().alpha(0f).setDuration(250).withEndAction {
            loadingView.visibility = View.GONE
            loadingView.alpha = 1f
        }.start()
    }

    private fun showOffline(url: String?) {
        failedUrl = url
        val online = isOnline()
        offlineTitle.setText(if (online) R.string.unreachable_title else R.string.offline_title)
        offlineMessage.setText(if (online) R.string.unreachable_message else R.string.offline_message)
        swipeRefresh.isRefreshing = false
        offlineView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        progress.visibility = View.GONE
    }

    private fun retry() {
        offlineView.visibility = View.GONE
        if (!firstLoadDone) {
            loadingText.setText(R.string.loading)
            loadingView.alpha = 1f
            loadingView.visibility = View.VISIBLE
        }
        pageHadError = false
        webView.loadUrl(failedUrl ?: startUrl)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Auto-retry when the connection comes back while the offline screen is visible. */
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread { if (offlineView.visibility == View.VISIBLE) retry() }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) { /* non-critical */ }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        } catch (_: Exception) { }
        networkCallback = null
    }

    // =====================================================================
    // WebViewClient
    // =====================================================================
    private inner class PortalWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false          // iframes (video embeds) load normally
            return routeExternally(request.url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageHadError = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush()
            swipeRefresh.isRefreshing = false
            if (!pageHadError) {
                firstLoadDone = true
                offlineView.visibility = View.GONE
                hideLoading()
                setupNotificationsOnce()
            }
        }

        // Server failures (HTTP 5xx) on the main page -> branded retry screen instead of a raw error page.
        // 4xx (e.g. 404) are left to the website's own pages.
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                pageHadError = true
                showOffline(request.url.toString())
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                pageHadError = true
                showOffline(request.url.toString())
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()                                   // never ignore certificate problems
            pageHadError = true
            showOffline(error.url)
        }
    }

    // =====================================================================
    // WebChromeClient (fullscreen video, popups, uploads, progress)
    // =====================================================================
    private inner class PortalChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (!firstLoadDone || newProgress >= 100) {
                progress.visibility = View.GONE
            } else {
                progress.visibility = View.VISIBLE
                progress.progress = newProgress
            }
        }

        // ---- fullscreen video ----
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            fullscreenHost.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            fullscreenHost.visibility = View.VISIBLE
            content.visibility = View.INVISIBLE                // keep the lesson alive, just hidden
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            setSystemBarsHidden(true)
        }

        override fun onHideCustomView() = hideCustomView()

        // Avoids a known NullPointerException in some WebView versions
        override fun getDefaultVideoPoster(): Bitmap? =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        // ---- target="_blank" / window.open ----
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
        ): Boolean {
            val temp = WebView(this@MainActivity)
            var handled = false
            fun handle(uri: Uri) {
                if (handled) return
                if (uri.toString() == "about:blank") return
                handled = true
                if (!routeExternally(uri)) webView.loadUrl(uri.toString())
                mainHandler.post { temp.destroy() }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    handle(r.url); return true
                }
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    url?.let { handle(Uri.parse(it)) }
                    v.stopLoading()
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }

        // ---- file uploads (<input type="file">): files, gallery AND camera for image fields ----
        override fun onShowFileChooser(
            webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, params.createIntent())
                    putExtra(Intent.EXTRA_TITLE, getString(R.string.choose_file))
                    val wantsImage = params.isCaptureEnabled ||
                        params.acceptTypes.any { it.startsWith("image") || it.isBlank() }
                    if (wantsImage) {
                        createCameraIntent()?.let { putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
                    }
                }
                fileChooserLauncher.launch(chooser)
                true
            } catch (e: Exception) {
                fileCallback = null
                callback.onReceiveValue(null)
                true
            }
        }
        // Live camera / microphone / location requests from the website are denied by default.
    }

    /** Camera intent that saves into the app's private cache (no CAMERA permission required). */
    private fun createCameraIntent(): Intent? = try {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("IMG_", ".jpg", dir)
        cameraFile = file
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file))
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (e: Exception) { cameraFile = null; null }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenHost.removeView(view)
        fullscreenHost.visibility = View.GONE
        content.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setSystemBarsHidden(false)
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (hidden)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }

    // =====================================================================
    // Downloads (PDF, ZIP, images ...) via the system DownloadManager
    // =====================================================================
    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast(R.string.download_unsupported)
            return
        }
        // Android 9 and older need the storage permission for the public Downloads folder.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = { startDownload(url, userAgent, contentDisposition, mimeType) }
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                mimeType?.let { setMimeType(it) }
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setTitle(fileName)
                setDescription(getString(R.string.app_name))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(R.string.download_started)
        } catch (e: Exception) {
            toast(R.string.download_failed)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
