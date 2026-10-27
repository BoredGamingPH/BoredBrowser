package com.boredgaming.boredbrowser.overlay

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.boredgaming.boredbrowser.MainActivity
import com.boredgaming.boredbrowser.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class FloatingGuideService : Service() {
    private data class HistoryEntry(
        val title: String,
        val url: String,
        val lastVisitedAt: Long,
        val visitCount: Int,
    )

    private data class Bookmark(
        val title: String,
        val url: String,
        val addedAt: Long,
    )

    private data class BrowserTab(
        val id: Int,
        val webView: WebView,
        var title: String,
        var url: String,
        var scrollX: Int = 0,
        var scrollY: Int = 0,
    )

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var bubbleView: View? = null
    private var browserView: View? = null
    private var closeTargetView: TextView? = null
    private var webView: WebView? = null
    private var browserContentView: FrameLayout? = null
    private var addressBar: EditText? = null
    private var findBar: View? = null
    private var findInput: EditText? = null
    private var findCount: TextView? = null
    private var browserParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var webState: Bundle? = null
    private val tabs = mutableListOf<BrowserTab>()
    private val downloadUrls = mutableSetOf<String>()
    private var activeTabIndex: Int = -1
    private var nextTabId: Int = 1
    private var browserAttached: Boolean = false
    private var browserMinimized: Boolean = false
    private var floatingWindow: Boolean = false
    private var browserOpacity: Float = 1f
    private var textZoom: Int = 100
    private var browserFocusable: Boolean = true
    private var windowGrip: View? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var lastUrl: String = DEFAULT_URL
    private var lastScrollX: Int = 0
    private var lastScrollY: Int = 0
    private var bubbleVisible: Boolean = true
    private var bubbleOpacity: Float = 1f
    private var adBlockEnabled: Boolean = true

    private inner class ScrollBridge(private val owner: WebView) {
        @JavascriptInterface
        fun save(x: Int, y: Int, url: String?) {
            owner.post {
                if (!isActiveWebView(owner)) return@post
                val pageUrl = url?.takeIf { it.isNotBlank() } ?: webView?.url ?: lastUrl
                val remembered = rememberedScrollFor(pageUrl)
                if (y == 0 && remembered.second > 0 && browserMinimized) return@post
                lastUrl = pageUrl
                lastScrollX = x.coerceAtLeast(0)
                lastScrollY = y.coerceAtLeast(0)
                activeTab()?.let { tab ->
                    tab.url = pageUrl
                    tab.scrollX = lastScrollX
                    tab.scrollY = lastScrollY
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        lastUrl = prefs.getString(KEY_LAST_URL, DEFAULT_URL)?.takeIf { isWebUrl(it) } ?: DEFAULT_URL
        lastScrollX = prefs.getInt(KEY_SCROLL_X, 0)
        lastScrollY = prefs.getInt(KEY_SCROLL_Y, 0)
        bubbleVisible = prefs.getBoolean(KEY_SHOW_BUBBLE, true)
        bubbleOpacity = prefs.getFloat(KEY_BUBBLE_OPACITY, 1f).coerceIn(MIN_BUBBLE_OPACITY, 1f)
        adBlockEnabled = prefs.getBoolean(KEY_AD_BLOCK_ENABLED, true)
        floatingWindow = prefs.getBoolean(KEY_FLOATING_WINDOW, false)
        browserOpacity = prefs.getFloat(KEY_BROWSER_OPACITY, 1f).coerceIn(MIN_BROWSER_OPACITY, 1f)
        textZoom = prefs.getInt(KEY_TEXT_ZOOM, 100).coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() && browserView == null }?.let {
            lastUrl = normalizeUrl(it)
            prefs.edit().putString(KEY_LAST_URL, lastUrl).apply()
        }
        if (intent?.hasExtra(EXTRA_SHOW_BUBBLE) == true) {
            bubbleVisible = intent.getBooleanExtra(EXTRA_SHOW_BUBBLE, true)
            prefs.edit().putBoolean(KEY_SHOW_BUBBLE, bubbleVisible).apply()
        }
        if (intent?.hasExtra(EXTRA_BUBBLE_OPACITY) == true) {
            bubbleOpacity = intent.getDoubleExtra(EXTRA_BUBBLE_OPACITY, 1.0).toFloat().coerceIn(MIN_BUBBLE_OPACITY, 1f)
            prefs.edit().putFloat(KEY_BUBBLE_OPACITY, bubbleOpacity).apply()
        }
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_SHOW_BROWSER -> showBrowser(lastUrl)
            ACTION_HIDE_BROWSER -> hideBrowser(returnToBubble = true)
            ACTION_UPDATE_SETTINGS -> updateBubbleSettings()
            else -> showBrowser(lastUrl)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        hideBrowser(returnToBubble = false, destroy = true)
        removeBubble()
        hideCloseTarget()
        super.onDestroy()
    }

    private fun updateBubbleSettings() {
        showBubble()
        bringBubbleToFront()
    }
    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        bubbleView?.let {
            it.alpha = effectiveBubbleAlpha()
            return
        }

        val bubble = ImageView(this).apply {
            contentDescription = "BoredBrowser bubble"
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = roundedDrawable(0xFF14161B.toInt(), 0xFF31353D.toInt(), sdp(16).toFloat())
            setPadding(sdp(3), sdp(3), sdp(3), sdp(3))
            elevation = sdp(8).toFloat()
            alpha = effectiveBubbleAlpha()
            setOnClickListener { toggleBrowser() }
            setOnLongClickListener {
                stopSelf()
                true
            }
        }

        val size = bubbleSize()
        val savedX = prefs.getInt(KEY_BUBBLE_X, screenWidth() - size - sdp(8))
        val savedY = prefs.getInt(KEY_BUBBLE_Y, sdp(8))
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampToScreen(savedX, size, screenWidth())
            y = clampToScreen(savedY, size, screenHeight())
        }

        attachDrag(bubble, params, snap = true)
        bubbleView = bubble
        bubbleParams = params
        windowManager.addView(bubble, params)
    }

    private fun showBrowser(url: String) {
        if (!Settings.canDrawOverlays(this)) return
        showBubble()
        if (browserView != null) {
            browserView?.visibility = View.VISIBLE
            browserView?.alpha = 1f
            browserView?.translationX = 0f
            browserView?.translationY = 0f
            attachBrowserIfNeeded()
            applyBrowserWindowLayout()
            bringBubbleToFront()
            return
        }

        val container = object : LinearLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_OUTSIDE -> {
                        releaseBrowserFocus()
                        return true
                    }
                    MotionEvent.ACTION_DOWN -> regainBrowserFocus()
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xFF1D2128.toInt(), 0xFF31353D.toInt(), sdp(8).toFloat())
            elevation = sdp(10).toFloat()
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    handleBrowserBack(webView)
                    true
                } else {
                    false
                }
            }
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sdp(6), sdp(4), sdp(6), sdp(4))
            setBackgroundColor(0xFF14161B.toInt())
        }
        val address = EditText(this).apply {
            setSingleLine(true)
            textSize = scaledSp(13f)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFA9ADB5.toInt())
            hint = "Search or URL"
            setText(url)
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF31353D.toInt(), sdp(6).toFloat())
            setPadding(sdp(8), 0, sdp(8), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { view, _, _ ->
                val target = normalizeUrl(view.text.toString())
                webView?.loadUrl(target)
                hideKeyboard(view)
                true
            }
        }
        addressBar = address

        val content = FrameLayout(this)
        browserContentView = content
        val savedActiveTab = prefs.getInt(KEY_ACTIVE_TAB, 0)
        restoreSavedTabs(url)
        if (tabs.isEmpty()) createTab(url)
        switchToTab(savedActiveTab.coerceIn(0, tabs.lastIndex))

        titleBar.addView(iconButton("⌂") { webView?.loadUrl(DEFAULT_URL) })
        titleBar.addView(iconButton("‹") { handleBrowserBack(webView) })
        titleBar.addView(address, LinearLayout.LayoutParams(0, toolbarButtonSize(), 0.72f).apply { marginStart = sdp(4); marginEnd = sdp(4) })
        titleBar.addView(menuButton("▣") { anchor -> showTabsPopup(anchor) })
        titleBar.addView(menuButton("⋮") { anchor -> showToolbarMenu(anchor, webView) })

        container.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight()))
        container.addView(createFindBar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight()))
        container.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(createWindowGrip(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, windowGripHeight()))

        val params = WindowManager.LayoutParams(
            browserWidth(), browserHeight(), overlayType(),
            browserWindowFlags(minimized = false),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        browserView = container
        browserParams = params
        browserFocusable = true
        updateBrowserBounds(params)
        windowManager.addView(container, params)
        browserAttached = true
        browserMinimized = false
        bringBubbleToFront()
    }

    private fun createFindBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sdp(6), sdp(4), sdp(6), sdp(4))
            setBackgroundColor(0xFF14161B.toInt())
            visibility = View.GONE
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            textSize = scaledSp(13f)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFA9ADB5.toInt())
            hint = "Find in page"
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF31353D.toInt(), sdp(6).toFloat())
            setPadding(sdp(8), 0, sdp(8), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = runFind(s?.toString().orEmpty())
            })
            setOnEditorActionListener { view, _, _ ->
                webView?.findNext(true)
                hideKeyboard(view)
                true
            }
        }
        val count = TextView(this).apply {
            textSize = scaledSp(12f)
            setTextColor(0xFFA9ADB5.toInt())
            gravity = Gravity.CENTER
            minWidth = sdp(44)
        }
        findInput = input
        findCount = count
        bar.addView(input, LinearLayout.LayoutParams(0, toolbarButtonSize(), 1f).apply { marginEnd = sdp(4) })
        bar.addView(count, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, toolbarButtonSize()).apply { marginEnd = sdp(4) })
        bar.addView(iconButton("▲") { webView?.findNext(false) })
        bar.addView(iconButton("▼") { webView?.findNext(true) })
        bar.addView(menuButton("×") { closeFindBar() })
        findBar = bar
        return bar
    }

    private fun openFindBar() {
        val bar = findBar ?: return
        val input = findInput ?: return
        bar.visibility = View.VISIBLE
        input.requestFocus()
        input.selectAll()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        runFind(input.text.toString())
    }

    private fun closeFindBar() {
        findBar?.visibility = View.GONE
        findInput?.let { hideKeyboard(it) }
        findCount?.text = ""
        tabs.forEach { it.webView.clearMatches() }
    }

    private fun runFind(query: String) {
        if (findBar?.visibility != View.VISIBLE) return
        val web = webView ?: return
        if (query.isBlank()) {
            web.clearMatches()
            findCount?.text = ""
        } else {
            web.findAllAsync(query)
        }
    }

    private fun createTab(initialUrl: String = DEFAULT_URL): BrowserTab {
        val web = createWebView(initialUrl)
        val tab = BrowserTab(nextTabId++, web, "New Tab", normalizeUrl(initialUrl))
        tabs.add(tab)
        browserContentView?.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        web.visibility = View.GONE
        saveTabs()
        return tab
    }

    private fun createWebView(initialUrl: String): WebView =
        WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.textZoom = textZoom
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    return if (shouldBlockRequest(request)) emptyBlockedResponse() else null
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    // Downloads and about:blank also report "finished"; they aren't pages to remember.
                    pageUrl?.takeIf { isWebUrl(it) && it !in downloadUrls }?.let {
                        val previousUrl = lastUrl
                        updateTabFor(view, it)
                        if (isActiveWebView(view)) {
                            lastUrl = it
                            if (previousUrl != it) {
                                restoreRememberedScrollFor(it)
                            }
                            prefs.edit()
                                .putString(KEY_LAST_URL, it)
                                .putInt(KEY_SCROLL_X, lastScrollX)
                                .putInt(KEY_SCROLL_Y, lastScrollY)
                                .apply()
                            addressBar?.setText(it)
                            installScrollBridge(view)
                            recordHistory(it, view?.title)
                            restoreScrollWhenReady()
                            runFind(findInput?.text?.toString().orEmpty())
                        }
                        saveTabs()
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    updateTabFor(view, title = title)
                    saveTabs()
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?,
                ): Boolean {
                    callback ?: return false
                    openFileChooser(callback, params)
                    return true
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view != null && callback != null) showFullscreenVideo(view, callback)
                }

                override fun onHideCustomView() = hideFullscreenVideo(notifyPage = false)
            }
            setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                if (isActiveWebView(this)) {
                    findCount?.text = if (numberOfMatches == 0) "0/0" else "${activeMatchOrdinal + 1}/$numberOfMatches"
                }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                downloadUrls.add(url)
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
                // A download never commits, so the back/forward list still holds the real page.
                val pageUrl = copyBackForwardList().currentItem?.url?.takeIf { isWebUrl(it) }
                tabs.firstOrNull { it.webView == this }?.let { tab ->
                    if (pageUrl != null) tab.url = pageUrl
                    if (isActiveWebView(this)) {
                        lastUrl = tab.url
                        addressBar?.setText(tab.url)
                    }
                }
                saveTabs()
            }
            addJavascriptInterface(ScrollBridge(this), SCROLL_BRIDGE_NAME)
            if (webState?.let { restoreState(it) != null } != true) {
                loadUrl(normalizeUrl(initialUrl))
            }
        }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        saveBrowserState(saveBundle = false)
        webView?.clearMatches()
        tabs.forEachIndexed { tabIndex, tab ->
            tab.webView.visibility = if (tabIndex == index) View.VISIBLE else View.GONE
        }
        activeTabIndex = index
        val active = tabs[index]
        webView = active.webView
        lastUrl = active.url
        lastScrollX = active.scrollX
        lastScrollY = active.scrollY
        addressBar?.setText(active.url)
        restoreScrollWhenReady()
        saveTabs()
        runFind(findInput?.text?.toString().orEmpty())
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        val nextIndex = when {
            activeTabIndex > index -> activeTabIndex - 1
            activeTabIndex >= tabs.size -> tabs.lastIndex
            else -> activeTabIndex
        }
        // Detach the closed WebView first so switchToTab doesn't save state from it.
        if (webView == removed.webView) webView = null
        activeTabIndex = -1
        browserContentView?.removeView(removed.webView)
        removed.webView.destroy()
        if (tabs.isEmpty()) {
            createTab(DEFAULT_URL)
            switchToTab(0)
            return
        }
        switchToTab(nextIndex.coerceIn(0, tabs.lastIndex))
    }

    private fun newTab(url: String = DEFAULT_URL) {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(this, "Maximum tabs reached", Toast.LENGTH_SHORT).show()
            return
        }
        createTab(url)
        switchToTab(tabs.lastIndex)
    }

    private fun updateTabFor(view: WebView?, url: String? = null, title: String? = null) {
        val tab = tabs.firstOrNull { it.webView == view } ?: return
        url?.takeIf { it.isNotBlank() }?.let { tab.url = it }
        title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
        if (isActiveWebView(view)) {
            lastUrl = tab.url
            addressBar?.setText(tab.url)
        }
    }

    private fun restoreSavedTabs(fallbackUrl: String) {
        val raw = prefs.getString(KEY_TABS, "[]") ?: "[]"
        val saved = runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.optJSONObject(index) }
                .filterNotNull()
                .filter { isWebUrl(it.optString("url")) }
                .take(MAX_TABS)
        }.getOrDefault(emptyList())
        if (saved.isEmpty()) {
            createTab(fallbackUrl)
            return
        }
        saved.forEach { json ->
            createTab(json.optString("url")).apply {
                title = json.optString("title").ifBlank { title }
                scrollX = json.optInt("scrollX", 0).coerceAtLeast(0)
                scrollY = json.optInt("scrollY", 0).coerceAtLeast(0)
            }
        }
    }

    private fun saveTabs() {
        // Tabs are cleared when the browser is destroyed; late callbacks must not wipe the saved list.
        if (tabs.isEmpty()) return
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(JSONObject().apply {
                put("id", tab.id)
                put("title", tab.title)
                put("url", tab.url)
                put("scrollX", tab.scrollX)
                put("scrollY", tab.scrollY)
            })
        }
        prefs.edit()
            .putString(KEY_TABS, array.toString())
            .putInt(KEY_ACTIVE_TAB, activeTabIndex.coerceAtLeast(0))
            .apply()
    }

    private fun activeTab(): BrowserTab? = tabs.getOrNull(activeTabIndex)

    private fun isActiveWebView(view: WebView?): Boolean = view != null && activeTab()?.webView == view

    private fun applyBrowserWindowLayout() {
        val view = browserView ?: return
        val params = browserParams ?: return
        browserMinimized = false
        browserFocusable = true
        updateBrowserBounds(params)
        if (browserAttached) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    // Sets size, position, flags and opacity for the open browser window in its current mode.
    private fun updateBrowserBounds(params: WindowManager.LayoutParams) {
        if (floatingWindow) {
            val screenW = screenWidth()
            val screenH = screenHeight()
            params.width = prefs.getInt(KEY_WINDOW_WIDTH, (screenW * 0.92f).roundToInt()).coerceIn(minWindowWidth(), screenW)
            params.height = prefs.getInt(KEY_WINDOW_HEIGHT, (screenH * 0.55f).roundToInt()).coerceIn(minWindowHeight(), screenH)
            params.x = clampToScreen(prefs.getInt(KEY_WINDOW_X, (screenW - params.width) / 2), params.width, screenW)
            params.y = clampToScreen(prefs.getInt(KEY_WINDOW_Y, (screenH * 0.06f).roundToInt()), params.height, screenH)
        } else {
            params.width = browserWidth()
            params.height = browserHeight()
            params.x = 0
            params.y = 0
        }
        params.flags = browserWindowFlags(minimized = false)
        params.alpha = browserOpacity
        windowGrip?.visibility = if (floatingWindow) View.VISIBLE else View.GONE
    }

    private fun updateBrowserWindow() {
        val view = browserView ?: return
        val params = browserParams ?: return
        if (browserAttached) runCatching { windowManager.updateViewLayout(view, params) }
    }

    // In floating mode, tapping the app behind hands it keyboard and Back key focus.
    private fun releaseBrowserFocus() {
        if (!floatingWindow || browserMinimized || !browserFocusable) return
        browserFocusable = false
        addressBar?.let { hideKeyboard(it) }
        browserParams?.flags = browserWindowFlags(minimized = false)
        updateBrowserWindow()
    }

    private fun regainBrowserFocus() {
        if (browserFocusable || browserMinimized) return
        browserFocusable = true
        browserParams?.flags = browserWindowFlags(minimized = false)
        updateBrowserWindow()
    }

    private fun createWindowGrip(): View {
        val grip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF14161B.toInt())
            visibility = if (floatingWindow) View.VISIBLE else View.GONE
        }
        val move = TextView(this).apply {
            text = "═══"
            contentDescription = "Drag to move the browser window"
            gravity = Gravity.CENTER
            textSize = scaledSp(12f)
            setTextColor(0xFFA9ADB5.toInt())
        }
        val resize = TextView(this).apply {
            text = "◢"
            contentDescription = "Drag to resize the browser window"
            gravity = Gravity.CENTER
            textSize = scaledSp(16f)
            setTextColor(0xFFA9ADB5.toInt())
        }
        grip.addView(View(this), LinearLayout.LayoutParams(windowGripHeight() * 2, LinearLayout.LayoutParams.MATCH_PARENT))
        grip.addView(move, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        grip.addView(resize, LinearLayout.LayoutParams(windowGripHeight() * 2, LinearLayout.LayoutParams.MATCH_PARENT))
        attachWindowDrag(move, resize = false)
        attachWindowDrag(resize, resize = true)
        windowGrip = grip
        return grip
    }

    private fun setTextZoom(value: Int) {
        textZoom = value.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        tabs.forEach { it.webView.settings.textZoom = textZoom }
        prefs.edit().putInt(KEY_TEXT_ZOOM, textZoom).apply()
    }

    private fun attachWindowDrag(handle: View, resize: Boolean) {
        var startX = 0
        var startY = 0
        var startW = 0
        var startH = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, event ->
            val params = browserParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startW = params.width
                    startH = params.height
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (resize) {
                        params.width = (startW + dx).coerceIn(minWindowWidth(), screenWidth() - params.x)
                        params.height = (startH + dy).coerceIn(minWindowHeight(), screenHeight() - params.y)
                    } else {
                        params.x = clampToScreen(startX + dx, params.width, screenWidth())
                        params.y = clampToScreen(startY + dy, params.height, screenHeight())
                    }
                    updateBrowserWindow()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit()
                        .putInt(KEY_WINDOW_X, params.x)
                        .putInt(KEY_WINDOW_Y, params.y)
                        .putInt(KEY_WINDOW_WIDTH, params.width)
                        .putInt(KEY_WINDOW_HEIGHT, params.height)
                        .apply()
                }
            }
            true
        }
    }

    private fun showWindowPopup(anchor: View) {
        lateinit var popup: PopupWindow
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF3A404B.toInt(), sdp(8).toFloat())
            setPadding(sdp(12), sdp(8), sdp(12), sdp(10))
            minimumWidth = historyMenuWidth()
        }
        val mode = TextView(this).apply {
            text = if (floatingWindow) "Mode: Floating window" else "Mode: Full screen"
            contentDescription = "Switch between full screen and floating window"
            textSize = scaledSp(14f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                popup.dismiss()
                floatingWindow = !floatingWindow
                prefs.edit().putBoolean(KEY_FLOATING_WINDOW, floatingWindow).apply()
                applyBrowserWindowLayout()
                bringBubbleToFront()
            }
        }
        menu.addView(mode, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))

        val label = TextView(this).apply {
            text = "Opacity: ${(browserOpacity * 100).roundToInt()}%"
            textSize = scaledSp(13f)
            setTextColor(0xFFA9ADB5.toInt())
        }
        menu.addView(label)
        val slider = SeekBar(this).apply {
            max = 100 - (MIN_BROWSER_OPACITY * 100).roundToInt()
            progress = (browserOpacity * 100).roundToInt() - (MIN_BROWSER_OPACITY * 100).roundToInt()
            contentDescription = "Browser window opacity"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    browserOpacity = (value + (MIN_BROWSER_OPACITY * 100).roundToInt()) / 100f
                    label.text = "Opacity: ${(browserOpacity * 100).roundToInt()}%"
                    browserParams?.alpha = browserOpacity
                    updateBrowserWindow()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.edit().putFloat(KEY_BROWSER_OPACITY, browserOpacity).apply()
                }
            })
        }
        menu.addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))

        val zoomLabel = TextView(this).apply {
            text = "Text size"
            textSize = scaledSp(13f)
            setTextColor(0xFFA9ADB5.toInt())
        }
        menu.addView(zoomLabel)
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val zoomValue = TextView(this).apply {
            text = "$textZoom%"
            contentDescription = "Text size $textZoom percent"
            gravity = Gravity.CENTER
            textSize = scaledSp(14f)
            setTextColor(Color.WHITE)
        }
        fun zoomButton(label: String, description: String, action: () -> Unit) = TextView(this).apply {
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            textSize = scaledSp(14f)
            setTextColor(Color.WHITE)
            background = roundedDrawable(0xFF313844.toInt(), 0xFF3A404B.toInt(), sdp(6).toFloat())
            setOnClickListener {
                action()
                zoomValue.text = "$textZoom%"
                zoomValue.contentDescription = "Text size $textZoom percent"
            }
        }
        val buttonSize = toolbarButtonSize()
        zoomRow.addView(zoomButton("A−", "Smaller text") { setTextZoom(textZoom - TEXT_ZOOM_STEP) }, LinearLayout.LayoutParams(buttonSize, buttonSize))
        zoomRow.addView(zoomValue, LinearLayout.LayoutParams(0, buttonSize, 1f))
        zoomRow.addView(zoomButton("A+", "Larger text") { setTextZoom(textZoom + TEXT_ZOOM_STEP) }, LinearLayout.LayoutParams(buttonSize, buttonSize))
        zoomRow.addView(
            zoomButton("Reset", "Reset text size") { setTextZoom(100) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, buttonSize).apply { marginStart = sdp(8) },
        )
        menu.addView(zoomRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))

        popup = PopupWindow(menu, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = sdp(8).toFloat()
        }
        showPopupAboveBubble(popup, anchor, -historyMenuHorizontalOffset(), sdp(4))
    }

    private fun attachBrowserIfNeeded() {
        val view = browserView ?: return
        val params = browserParams ?: return
        if (browserAttached) return
        runCatching { windowManager.addView(view, params) }
            .onSuccess { browserAttached = true }
    }

    private fun openLauncher() {
        hideBrowser(returnToBubble = true)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun handleBrowserBack(web: WebView?) {
        when {
            web?.canGoBack() == true -> web.goBack()
            browserAttached && !browserMinimized -> hideBrowser(returnToBubble = false)
        }
    }

    private fun toggleBrowser() {
        if (browserView == null || !browserAttached || browserMinimized) {
            showBrowser(lastUrl)
        } else {
            hideBrowser(returnToBubble = false)
        }
    }

    private fun hideBrowser(returnToBubble: Boolean = true, destroy: Boolean = false) {
        hideFullscreenVideo(notifyPage = true)
        saveBrowserState(saveBundle = destroy)
        if (destroy) {
            browserView?.let {
                if (browserAttached) {
                    runCatching { windowManager.removeView(it) }
                    browserAttached = false
                }
            }
            browserView = null
            tabs.forEach { it.webView.destroy() }
            tabs.clear()
            webView = null
            browserParams = null
            browserContentView = null
            addressBar = null
            findBar = null
            findInput = null
            findCount = null
            windowGrip = null
            activeTabIndex = -1
            browserMinimized = false
        } else {
            minimizeBrowserWindow()
        }
        if (returnToBubble) showBubble()
    }

    private fun minimizeBrowserWindow() {
        val view = browserView ?: return
        val params = browserParams ?: return
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.translationX = (browserWidth() + sdp(24)).toFloat()
        view.translationY = 0f
        params.flags = browserWindowFlags(minimized = true)
        // Android 12+ blocks touches through untouchable overlays above 80% opacity.
        params.alpha = 0f
        browserMinimized = true
        if (browserAttached) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun saveBrowserState(saveBundle: Boolean) {
        webView?.let { web ->
            lastUrl = currentPageUrl(web) ?: lastUrl
            val remembered = rememberedScrollFor(lastUrl)
            if (web.scrollY > 0 || remembered.second == 0) {
                lastScrollX = web.scrollX
                lastScrollY = web.scrollY
            } else {
                lastScrollX = remembered.first
                lastScrollY = remembered.second
            }
            if (saveBundle) {
                webState = Bundle().also { web.saveState(it) }
            }
            activeTab()?.takeIf { it.webView == web }?.let { tab ->
                tab.url = lastUrl
                tab.scrollX = lastScrollX
                tab.scrollY = lastScrollY
            }
            persistScroll(lastUrl, lastScrollX, lastScrollY)
            saveTabs()
            // Skip the async capture when tearing down; the WebView is destroyed right after.
            if (!saveBundle) capturePageScroll(web)
        }
    }

    private fun restoreScrollWhenReady() {
        restoreScrollAttempt(attempt = 0)
    }

    private fun restoreScrollAttempt(attempt: Int) {
        val web = webView ?: return
        if (lastScrollX == 0 && lastScrollY == 0) return
        web.postDelayed({
            val current = webView ?: return@postDelayed
            current.scrollTo(lastScrollX, lastScrollY)
            restorePageScroll(current, lastScrollX, lastScrollY)
            if (attempt < SCROLL_RESTORE_ATTEMPTS && current.scrollY < lastScrollY) {
                restoreScrollAttempt(attempt + 1)
            }
        }, SCROLL_RESTORE_DELAY_MS)
    }

    private fun installScrollBridge(web: WebView?) {
        web?.evaluateJavascript(
            """
            (function() {
              if (window.__boredBrowserScrollHook) return;
              window.__boredBrowserScrollHook = true;
              var send = function() {
                // Report device pixels so values match WebView.scrollX/scrollY.
                var ratio = window.devicePixelRatio || 1;
                var x = Math.round((window.pageXOffset || document.documentElement.scrollLeft || document.body.scrollLeft || 0) * ratio);
                var y = Math.round((window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0) * ratio);
                if (window.$SCROLL_BRIDGE_NAME && window.$SCROLL_BRIDGE_NAME.save) {
                  window.$SCROLL_BRIDGE_NAME.save(x, y, window.location.href);
                }
              };
              window.addEventListener('scroll', function() {
                clearTimeout(window.__boredBrowserScrollTimer);
                window.__boredBrowserScrollTimer = setTimeout(send, 120);
              }, { passive: true });
              window.addEventListener('pagehide', send);
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun capturePageScroll(web: WebView) {
        web.evaluateJavascript(
            """
            (function() {
              var ratio = window.devicePixelRatio || 1;
              return JSON.stringify({
                x: Math.round((window.pageXOffset || document.documentElement.scrollLeft || document.body.scrollLeft || 0) * ratio),
                y: Math.round((window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0) * ratio),
                url: window.location.href
              });
            })();
            """.trimIndent(),
        ) { value ->
            val payload = decodeJsString(value) ?: return@evaluateJavascript
            runCatching {
                val json = JSONObject(payload)
                val pageUrl = json.optString("url", lastUrl).takeIf { it.isNotBlank() } ?: lastUrl
                val remembered = rememberedScrollFor(pageUrl)
                val capturedY = json.optInt("y", lastScrollY).coerceAtLeast(0)
                if (capturedY == 0 && remembered.second > 0 && browserMinimized) return@runCatching
                lastUrl = pageUrl
                lastScrollX = json.optInt("x", lastScrollX).coerceAtLeast(0)
                lastScrollY = capturedY
                activeTab()?.takeIf { it.webView == web }?.let { tab ->
                    tab.url = pageUrl
                    tab.scrollX = lastScrollX
                    tab.scrollY = lastScrollY
                }
                persistScroll(pageUrl, lastScrollX, lastScrollY)
                saveTabs()
            }
        }
    }

    private fun restorePageScroll(web: WebView, x: Int, y: Int) {
        web.evaluateJavascript(
            """
            (function() {
              // x and y arrive in device pixels; the DOM scrolls in CSS pixels.
              var ratio = window.devicePixelRatio || 1;
              var x = $x / ratio;
              var y = $y / ratio;
              window.scrollTo(x, y);
              document.documentElement.scrollLeft = x;
              document.documentElement.scrollTop = y;
              if (document.body) {
                document.body.scrollLeft = x;
                document.body.scrollTop = y;
              }
              return Math.round((window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0) * ratio);
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun decodeJsString(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        return runCatching { JSONArray("[$value]").getString(0) }.getOrNull()
    }

    private fun restoreRememberedScrollFor(url: String) {
        val remembered = rememberedScrollFor(url)
        lastScrollX = remembered.first
        lastScrollY = remembered.second
    }

    private fun rememberedScrollFor(url: String): Pair<Int, Int> {
        val raw = prefs.getString(KEY_SCROLL_BY_URL, "{}") ?: "{}"
        return runCatching {
            val saved = JSONObject(raw).optJSONObject(url) ?: return@runCatching 0 to 0
            saved.optInt("x", 0) to saved.optInt("y", 0)
        }.getOrDefault(0 to 0)
    }

    private fun rememberScrollFor(url: String, x: Int, y: Int) {
        val raw = prefs.getString(KEY_SCROLL_BY_URL, "{}") ?: "{}"
        val entries = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        entries.put(url, JSONObject().apply {
            put("x", x)
            put("y", y)
            put("savedAt", System.currentTimeMillis())
        })
        prefs.edit().putString(KEY_SCROLL_BY_URL, entries.toString()).apply()
    }

    private fun persistScroll(url: String, x: Int, y: Int) {
        prefs.edit()
            .putString(KEY_LAST_URL, url)
            .putInt(KEY_SCROLL_X, x)
            .putInt(KEY_SCROLL_Y, y)
            .apply()
        rememberScrollFor(url, x, y)
    }

    private fun bringBubbleToFront() {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        runCatching { windowManager.removeView(bubble) }
        runCatching { windowManager.addView(bubble, params) }
    }

    private fun removeBubble() {
        bubbleView?.let {
            runCatching { windowManager.removeView(it) }
        }
        bubbleView = null
        bubbleParams = null
    }

    private fun showCloseTarget() {
        if (!Settings.canDrawOverlays(this) || closeTargetView != null) return
        val target = TextView(this).apply {
            text = "×"
            contentDescription = "Drag here to close BoredBrowser"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = scaledSp(34f)
            typeface = Typeface.DEFAULT_BOLD
            background = ovalDrawable(0xDD3B1111.toInt(), 0xFFFF6B5C.toInt())
            elevation = sdp(9).toFloat()
        }
        val size = closeTargetSize()
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth() - size) / 2
            y = screenHeight() - closeTargetSize() - sdp(32)
        }
        closeTargetView = target
        windowManager.addView(target, params)
    }

    private fun hideCloseTarget() {
        closeTargetView?.let {
            runCatching { windowManager.removeView(it) }
        }
        closeTargetView = null
    }

    private fun updateCloseTarget(params: WindowManager.LayoutParams) {
        closeTargetView?.background = if (isInCloseTarget(params)) {
            ovalDrawable(0xEEB3261E.toInt(), 0xFFFFD0C8.toInt())
        } else {
            ovalDrawable(0xDD3B1111.toInt(), 0xFFFF6B5C.toInt())
        }
    }

    private fun isInCloseTarget(params: WindowManager.LayoutParams): Boolean {
        val centerX = params.x + params.width / 2
        val centerY = params.y + params.height / 2
        return centerY >= screenHeight() - closeTargetSize() - sdp(76) && centerX >= screenWidth() / 2 - sdp(88) && centerX <= screenWidth() / 2 + sdp(88)
    }
    private fun attachDrag(handle: View, params: WindowManager.LayoutParams, snap: Boolean) {
        var initialX = 0
        var initialY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        var longPressed = false
        // The touch listener consumes every event, so long-press has to be detected here.
        val longPress = Runnable {
            if (!moved) {
                longPressed = true
                hideCloseTarget()
                handle.performLongClick()
            }
        }
        handle.setOnTouchListener { view, event ->
            val targetView = if (snap) bubbleView ?: view else browserView ?: view
            if (longPressed && event.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    longPressed = false
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    if (snap) showCloseTarget()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    moved = moved || abs(dx) > sdp(4) || abs(dy) > sdp(4)
                    if (moved) view.removeCallbacks(longPress)
                    params.x = clampToScreen(initialX + dx, params.width, screenWidth())
                    params.y = clampToScreen(initialY + dy, params.height, screenHeight())
                    runCatching { windowManager.updateViewLayout(targetView, params) }
                    if (snap) updateCloseTarget(params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    if (snap && moved && isInCloseTarget(params)) {
                        hideCloseTarget()
                        stopSelf()
                        return@setOnTouchListener true
                    }

                    if (snap && moved) {
                        params.x = if (params.x + params.width / 2 < screenWidth() / 2) sdp(8) else max(sdp(8), screenWidth() - params.width - sdp(8))
                        params.y = clampToScreen(params.y, params.height, screenHeight())
                        prefs.edit().putInt(KEY_BUBBLE_X, params.x).putInt(KEY_BUBBLE_Y, params.y).apply()
                        runCatching { windowManager.updateViewLayout(targetView, params) }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    if (snap) hideCloseTarget()
                    true
                }
                else -> true
            }
        }
    }

    private fun clampToScreen(value: Int, viewSize: Int, screenSize: Int): Int {
        val maxValue = max(0, screenSize - viewSize)
        return value.coerceIn(0, maxValue)
    }

    private fun effectiveBubbleAlpha(): Float = if (bubbleVisible) bubbleOpacity else HIDDEN_BUBBLE_OPACITY

    private fun iconButton(label: String, action: () -> Unit): ImageButton {
        val description = buttonDescription(label)
        return ImageButton(this).apply {
            contentDescription = description
            setImageDrawable(TextDrawable(label))
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF31353D.toInt(), sdp(6).toFloat())
            setOnClickListener { action() }
            setOnLongClickListener {
                Toast.makeText(this@FloatingGuideService, description, Toast.LENGTH_SHORT).show()
                true
            }
            val size = toolbarButtonSize()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = sdp(4) }
        }
    }


    private fun menuButton(label: String, action: (View) -> Unit): ImageButton {
        val description = buttonDescription(label)
        return ImageButton(this).apply {
            contentDescription = description
            setImageDrawable(TextDrawable(label))
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF31353D.toInt(), sdp(6).toFloat())
            setOnClickListener { action(it) }
            setOnLongClickListener {
                Toast.makeText(this@FloatingGuideService, description, Toast.LENGTH_SHORT).show()
                true
            }
            val size = toolbarButtonSize()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun showTabsPopup(anchor: View) {
        lateinit var popup: PopupWindow
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF3A404B.toInt(), sdp(8).toFloat())
            setPadding(sdp(6), sdp(6), sdp(6), sdp(6))
        }

        val newTab = TextView(this).apply {
            text = "+ New Tab"
            contentDescription = "Open a new tab"
            textSize = scaledSp(14f)
            setTextColor(Color.WHITE)
            setPadding(sdp(12), sdp(10), sdp(12), sdp(10))
            minWidth = tabsMenuWidth()
            setOnClickListener {
                popup.dismiss()
                newTab()
            }
        }
        menu.addView(newTab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))

        tabs.forEachIndexed { index, tab ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedDrawable(if (index == activeTabIndex) 0xFF313844.toInt() else 0x00252A33, 0x0031353D, sdp(6).toFloat())
            }
            val title = TextView(this).apply {
                text = "${if (index == activeTabIndex) "* " else ""}${tab.title.ifBlank { tab.url }}"
                contentDescription = "Switch to tab ${index + 1}"
                textSize = scaledSp(13f)
                setTextColor(Color.WHITE)
                setSingleLine(true)
                setPadding(sdp(10), 0, sdp(8), 0)
                setOnClickListener {
                    popup.dismiss()
                    switchToTab(index)
                }
            }
            val close = TextView(this).apply {
                text = "×"
                contentDescription = "Close tab ${index + 1}"
                gravity = Gravity.CENTER
                textSize = scaledSp(18f)
                setTextColor(0xFFFFD0C8.toInt())
                setOnClickListener {
                    popup.dismiss()
                    closeTab(index)
                }
            }
            row.addView(title, LinearLayout.LayoutParams(0, menuItemHeight(), 1f))
            row.addView(close, LinearLayout.LayoutParams(menuItemHeight(), menuItemHeight()))
            menu.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))
        }

        popup = PopupWindow(menu, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = sdp(8).toFloat()
        }
        showPopupAboveBubble(popup, anchor, -tabsMenuHorizontalOffset(), sdp(4))
    }

    private fun showToolbarMenu(anchor: View, web: WebView?) {
        lateinit var popup: PopupWindow
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF3A404B.toInt(), sdp(8).toFloat())
            setPadding(sdp(6), sdp(6), sdp(6), sdp(6))
        }

        fun addMenuItem(label: String, description: String, action: () -> Unit) {
            val item = TextView(this).apply {
                text = label
                contentDescription = description
                textSize = scaledSp(14f)
                setTextColor(Color.WHITE)
                setPadding(sdp(12), sdp(10), sdp(12), sdp(10))
                minWidth = menuMinWidth()
                background = roundedDrawable(0x00252A33, 0x0031353D, sdp(6).toFloat())
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
                setOnLongClickListener {
                    Toast.makeText(this@FloatingGuideService, description, Toast.LENGTH_SHORT).show()
                    true
                }
            }
            menu.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))
        }

        addMenuItem("Forward", "Go forward") { if (web?.canGoForward() == true) web.goForward() }
        addMenuItem("Reload", "Reload page") { web?.reload() }
        addMenuItem("Find in Page", "Search text on this page") { openFindBar() }
        addMenuItem("View", "Window mode, opacity and text size") { showWindowPopup(anchor) }
        val currentUrl = web?.let { currentPageUrl(it) }
        val bookmarked = currentUrl != null && isBookmarked(currentUrl)
        addMenuItem(if (bookmarked) "★ Bookmarked" else "☆ Add Bookmark", if (bookmarked) "Remove this page from bookmarks" else "Bookmark this page") {
            toggleBookmark(web)
        }
        addMenuItem("Bookmarks", "Open saved bookmarks") { showBookmarksPopup(anchor, web) }
        addMenuItem(if (adBlockEnabled) "Ad Blocker: On" else "Ad Blocker: Off", "Toggle built-in ad blocker") {
            adBlockEnabled = !adBlockEnabled
            prefs.edit().putBoolean(KEY_AD_BLOCK_ENABLED, adBlockEnabled).apply()
            Toast.makeText(this, if (adBlockEnabled) "Ad blocker on" else "Ad blocker off", Toast.LENGTH_SHORT).show()
            web?.reload()
        }
        addMenuItem("History", "Open browsing history") { showHistoryPopup(anchor, web) }
        addMenuItem("Downloads", "Open downloaded files") { openDownloads() }
        addMenuItem("☕ Donate", "Support BoredBrowser on Ko-fi") { web?.loadUrl(KOFI_URL) }
        addMenuItem("Launcher", "Open BoredBrowser launcher") { openLauncher() }

        popup = PopupWindow(menu, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = sdp(8).toFloat()
        }
        showPopupAboveBubble(popup, anchor, -menuHorizontalOffset(), sdp(4))
    }

    private fun showHistoryPopup(anchor: View, web: WebView?) {
        lateinit var popup: PopupWindow
        val history = loadHistoryEntries()
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF3A404B.toInt(), sdp(8).toFloat())
            setPadding(sdp(6), sdp(6), sdp(6), sdp(6))
        }

        val header = TextView(this).apply {
            text = "History"
            textSize = scaledSp(13f)
            setTextColor(0xFFA9ADB5.toInt())
            setPadding(sdp(12), sdp(8), sdp(12), sdp(8))
        }
        menu.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))

        if (history.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No pages yet"
                textSize = scaledSp(14f)
                setTextColor(Color.WHITE)
                setPadding(sdp(12), sdp(10), sdp(12), sdp(10))
                minWidth = historyMenuWidth()
            }
            menu.addView(empty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))
        } else {
            history.take(HISTORY_MENU_LIMIT).forEach { entry ->
                val item = TextView(this).apply {
                    text = entry.title.ifBlank { entry.url }
                    contentDescription = "Open ${entry.url}"
                    textSize = scaledSp(14f)
                    setTextColor(Color.WHITE)
                    setSingleLine(true)
                    setPadding(sdp(12), sdp(10), sdp(12), sdp(10))
                    minWidth = historyMenuWidth()
                    background = roundedDrawable(0x00252A33, 0x0031353D, sdp(6).toFloat())
                    setOnClickListener {
                        popup.dismiss()
                        web?.loadUrl(entry.url)
                    }
                    setOnLongClickListener {
                        Toast.makeText(this@FloatingGuideService, entry.url, Toast.LENGTH_SHORT).show()
                        true
                    }
                }
                menu.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))
            }

            val clear = TextView(this).apply {
                text = "Clear History"
                contentDescription = "Clear browsing history"
                textSize = scaledSp(14f)
                setTextColor(0xFFFFD0C8.toInt())
                setPadding(sdp(12), sdp(10), sdp(12), sdp(10))
                minWidth = historyMenuWidth()
                setOnClickListener {
                    prefs.edit().remove(KEY_HISTORY).apply()
                    popup.dismiss()
                }
            }
            menu.addView(clear, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))
        }

        popup = PopupWindow(menu, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = sdp(8).toFloat()
        }
        showPopupAboveBubble(popup, anchor, -historyMenuHorizontalOffset(), sdp(4))
    }

    // The bubble is a separate overlay window drawn above popups, so hide it and let touches pass
    // through while a menu is open.
    private fun showPopupAboveBubble(popup: PopupWindow, anchor: View, xOffset: Int, yOffset: Int) {
        setBubbleSuppressed(true)
        popup.setOnDismissListener { setBubbleSuppressed(false) }
        popup.showAsDropDown(anchor, xOffset, yOffset)
    }

    private fun setBubbleSuppressed(suppressed: Boolean) {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        bubble.visibility = if (suppressed) View.INVISIBLE else View.VISIBLE
        params.flags = if (suppressed) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    private fun recordHistory(url: String, title: String?) {
        val normalizedUrl = normalizeUrl(url)
        val existing = loadHistoryEntries()
        val previous = existing.firstOrNull { it.url == normalizedUrl }
        val updated = HistoryEntry(
            title = title?.takeIf { it.isNotBlank() } ?: previous?.title ?: normalizedUrl,
            url = normalizedUrl,
            lastVisitedAt = System.currentTimeMillis(),
            visitCount = (previous?.visitCount ?: 0) + 1,
        )
        val merged = listOf(updated) + existing.filterNot { it.url == normalizedUrl }
        saveHistoryEntries(merged.take(MAX_HISTORY_ENTRIES))
    }

    private fun loadHistoryEntries(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                HistoryEntry(
                    title = item.optString("title"),
                    url = item.optString("url"),
                    lastVisitedAt = item.optLong("lastVisitedAt"),
                    visitCount = item.optInt("visitCount", 1),
                )
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun saveHistoryEntries(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("title", entry.title)
                put("url", entry.url)
                put("lastVisitedAt", entry.lastVisitedAt)
                put("visitCount", entry.visitCount)
            })
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun showBookmarksPopup(anchor: View, web: WebView?) {
        lateinit var popup: PopupWindow
        val bookmarks = loadBookmarks()
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF3A404B.toInt(), sdp(8).toFloat())
            setPadding(sdp(6), sdp(6), sdp(6), sdp(6))
        }

        val header = TextView(this).apply {
            text = "Bookmarks"
            textSize = scaledSp(13f)
            setTextColor(0xFFA9ADB5.toInt())
            setPadding(sdp(12), sdp(8), sdp(12), sdp(8))
            minWidth = historyMenuWidth()
        }
        menu.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))

        if (bookmarks.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No bookmarks yet"
                textSize = scaledSp(14f)
                setTextColor(Color.WHITE)
                setPadding(sdp(12), sdp(10), sdp(12), sdp(10))
                minWidth = historyMenuWidth()
            }
            menu.addView(empty, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, menuItemHeight()))
        } else {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            bookmarks.forEach { bookmark ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val title = TextView(this).apply {
                    text = bookmark.title.ifBlank { bookmark.url }
                    contentDescription = "Open ${bookmark.url}"
                    textSize = scaledSp(14f)
                    setTextColor(Color.WHITE)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(sdp(12), 0, sdp(8), 0)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        popup.dismiss()
                        web?.loadUrl(bookmark.url)
                    }
                    setOnLongClickListener {
                        Toast.makeText(this@FloatingGuideService, bookmark.url, Toast.LENGTH_SHORT).show()
                        true
                    }
                }
                val remove = TextView(this).apply {
                    text = "×"
                    contentDescription = "Remove bookmark ${bookmark.title}"
                    gravity = Gravity.CENTER
                    textSize = scaledSp(18f)
                    setTextColor(0xFFFFD0C8.toInt())
                    setOnClickListener {
                        removeBookmark(bookmark.url)
                        list.removeView(row)
                        if (list.childCount == 0) popup.dismiss()
                    }
                }
                row.addView(title, LinearLayout.LayoutParams(0, menuItemHeight(), 1f))
                row.addView(remove, LinearLayout.LayoutParams(menuItemHeight(), menuItemHeight()))
                list.addView(row, LinearLayout.LayoutParams(historyMenuWidth(), menuItemHeight()))
            }
            // Cap the visible rows so a long list scrolls instead of running off screen.
            val scroller = ScrollView(this).apply { addView(list) }
            val visibleRows = bookmarks.size.coerceAtMost(BOOKMARK_MENU_VISIBLE_ROWS)
            menu.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, visibleRows * menuItemHeight()))
        }

        popup = PopupWindow(menu, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = sdp(8).toFloat()
        }
        showPopupAboveBubble(popup, anchor, -historyMenuHorizontalOffset(), sdp(4))
    }

    private fun toggleBookmark(web: WebView?) {
        val url = web?.let { currentPageUrl(it) } ?: return
        if (isBookmarked(url)) {
            removeBookmark(url)
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show()
        } else {
            val bookmark = Bookmark(web.title?.takeIf { it.isNotBlank() } ?: url, url, System.currentTimeMillis())
            saveBookmarks(listOf(bookmark) + loadBookmarks())
            Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isBookmarked(url: String): Boolean = loadBookmarks().any { it.url == url }

    private fun removeBookmark(url: String) = saveBookmarks(loadBookmarks().filterNot { it.url == url })

    private fun loadBookmarks(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                Bookmark(
                    title = item.optString("title"),
                    url = item.optString("url"),
                    addedAt = item.optLong("addedAt"),
                )
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun saveBookmarks(bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            array.put(JSONObject().apply {
                put("title", bookmark.title)
                put("url", bookmark.url)
                put("addedAt", bookmark.addedAt)
            })
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }

    private fun openFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams?) {
        // Answer any earlier request so its page doesn't wait forever.
        FileChooserActivity.pendingCallback?.invoke(null)
        val chooser = runCatching { params?.createIntent() }.getOrNull()
            ?: Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            chooser.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val reopenBrowser = browserAttached && !browserMinimized
        FileChooserActivity.pendingCallback = { uris ->
            callback.onReceiveValue(uris)
            if (reopenBrowser && isRunning) showBrowser(lastUrl)
        }
        // Overlays draw above activities, so the browser has to step aside for the picker.
        hideBrowser(returnToBubble = true)
        val intent = Intent(this, FileChooserActivity::class.java)
            .putExtra(FileChooserActivity.EXTRA_CHOOSER_INTENT, chooser)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            FileChooserActivity.pendingCallback?.invoke(null)
            FileChooserActivity.pendingCallback = null
            Toast.makeText(this, "Can't open the file picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) hideFullscreenVideo(notifyPage = true)
                true
            }
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START }
        fullscreenView = container
        fullscreenCallback = callback
        setBubbleSuppressed(true)
        runCatching { windowManager.addView(container, params) }
            .onSuccess { container.requestFocus() }
            .onFailure { hideFullscreenVideo(notifyPage = true) }
    }

    private fun hideFullscreenVideo(notifyPage: Boolean) {
        val container = fullscreenView ?: return
        val callback = fullscreenCallback
        fullscreenView = null
        fullscreenCallback = null
        (container as? FrameLayout)?.removeAllViews()
        runCatching { windowManager.removeView(container) }
        setBubbleSuppressed(false)
        if (notifyPage) callback?.onCustomViewHidden()
    }

    private fun isWebUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    // WebView.url reports a download link after a download starts; fall back to the committed page.
    private fun currentPageUrl(web: WebView): String? =
        web.url?.takeIf { isWebUrl(it) && it !in downloadUrls }
            ?: web.copyBackForwardList().currentItem?.url?.takeIf { isWebUrl(it) }

    private fun enqueueDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        // DownloadManager only fetches http(s); blob:/data: URLs live inside the page.
        if (!isWebUrl(url)) {
            Toast.makeText(this, "This file can't be downloaded", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setMimeType(mimeType)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            userAgent?.let { addRequestHeader("User-Agent", it) }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // Public Downloads needs a storage permission before Android 10, which a service can't request.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } else {
                setDestinationInExternalFilesDir(this@FloatingGuideService, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
        }
        runCatching { (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request) }
            .onSuccess { Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show() }
    }

    private fun openDownloads() {
        hideBrowser(returnToBubble = true)
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "No downloads app found", Toast.LENGTH_SHORT).show() }
    }

    private fun buttonDescription(label: String): String = when (label) {
        "☰" -> "Open BoredBrowser launcher"
        "‹" -> "Go back"
        "›" -> "Go forward"
        "↻" -> "Reload page"
        "⌂" -> "Open home page"
        "⋮" -> "Open browser actions"
        "▣" -> "Open tabs"
        "▲" -> "Previous match"
        "▼" -> "Next match"
        "×" -> "Close find bar"
        else -> label
    }

    private fun shouldBlockRequest(request: WebResourceRequest?): Boolean {
        if (!adBlockEnabled || request == null || request.isForMainFrame) return false
        val uri = request.url ?: return false
        val host = uri.host?.lowercase() ?: return false
        val url = uri.toString().lowercase()
        if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        return AD_URL_PARTS.any { url.contains(it) }
    }

    private fun emptyBlockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), pendingFlags()
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, FloatingGuideService::class.java).setAction(ACTION_STOP), pendingFlags()
        )
        val showIntent = PendingIntent.getService(
            this, 3, Intent(this, FloatingGuideService::class.java).setAction(ACTION_SHOW_BROWSER), pendingFlags()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("BoredBrowser Active")
            .setContentText("Tap the bubble to open your floating browser.")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_view, "Open Guide", showIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BoredBrowser", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun normalizeUrl(input: String): String {
        val value = input.trim()
        if (value.isBlank()) return DEFAULT_URL
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return if (value.contains(".") && !value.contains(" ")) "https://$value" else "https://www.google.com/search?q=${java.net.URLEncoder.encode(value, "UTF-8")}"
    }

    private fun roundedDrawable(fill: Int, stroke: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        setStroke(sdp(1), stroke)
    }

    private fun ovalDrawable(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(sdp(2), stroke)
    }

    private fun uiScale(): Float {
        val shortestDp = minOf(screenBoundsWidth(), screenBoundsHeight()) / resources.displayMetrics.density
        return (shortestDp / 420f).coerceIn(0.82f, 1.18f)
    }

    private fun sdp(value: Int): Int = max(1, (value * uiScale() * resources.displayMetrics.density).roundToInt())
    private fun scaledSp(value: Float): Float = value * uiScale().coerceIn(0.90f, 1.12f)
    private fun bubbleSize(): Int = sdp(58).coerceIn(dp(46), dp(72))
    private fun toolbarButtonSize(): Int = sdp(38).coerceIn(dp(34), dp(48))
    private fun toolbarHeight(): Int = sdp(48).coerceIn(dp(42), dp(60))
    private fun closeTargetSize(): Int = sdp(72).coerceIn(dp(58), dp(88))
    private fun menuItemHeight(): Int = sdp(42).coerceIn(dp(38), dp(52))
    private fun menuMinWidth(): Int = sdp(148).coerceIn(dp(128), dp(190))
    private fun menuHorizontalOffset(): Int = sdp(116).coerceIn(dp(96), dp(160))
    private fun tabsMenuWidth(): Int = sdp(240).coerceIn(dp(200), dp(320))
    private fun tabsMenuHorizontalOffset(): Int = sdp(206).coerceIn(dp(160), dp(270))
    private fun historyMenuWidth(): Int = sdp(230).coerceIn(dp(190), dp(300))
    private fun historyMenuHorizontalOffset(): Int = sdp(198).coerceIn(dp(150), dp(250))
    private fun browserWindowFlags(minimized: Boolean): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or if (minimized) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            // Let touches outside a floating window reach the app behind, and tell us when they do.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                if (browserFocusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    private fun windowGripHeight(): Int = sdp(22).coerceIn(dp(18), dp(28))
    private fun minWindowWidth(): Int = sdp(240).coerceAtMost(screenWidth())
    private fun minWindowHeight(): Int = sdp(260).coerceAtMost(screenHeight())
    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun screenWidth(): Int = screenBoundsWidth()
    private fun screenHeight(): Int = screenBoundsHeight()
    private fun browserWidth(): Int = screenBoundsWidth()
    private fun browserHeight(): Int = screenBoundsHeight()
    private fun screenBoundsWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.currentWindowMetrics.bounds.width() else resources.displayMetrics.widthPixels
    private fun screenBoundsHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels
    private fun hideKeyboard(view: View) = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_STOP = "com.boredgaming.boredbrowser.overlay.STOP"
        const val ACTION_SHOW_BROWSER = "com.boredgaming.boredbrowser.overlay.SHOW_BROWSER"
        const val ACTION_HIDE_BROWSER = "com.boredgaming.boredbrowser.overlay.HIDE_BROWSER"
        const val ACTION_UPDATE_SETTINGS = "com.boredgaming.boredbrowser.overlay.UPDATE_SETTINGS"
        const val EXTRA_URL = "url"
        const val EXTRA_SHOW_BUBBLE = "showBubble"
        const val EXTRA_BUBBLE_OPACITY = "bubbleOpacity"
        private const val CHANNEL_ID = "boredbrowser_overlay"
        private const val NOTIFICATION_ID = 42
        private const val PREFS = "floating_guide"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_SCROLL_X = "scroll_x"
        private const val KEY_SCROLL_Y = "scroll_y"
        private const val KEY_SCROLL_BY_URL = "scroll_by_url"
        private const val KEY_HISTORY = "browser_history"
        private const val KEY_BOOKMARKS = "browser_bookmarks"
        private const val KEY_TABS = "browser_tabs"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val KEY_AD_BLOCK_ENABLED = "ad_block_enabled"
        private const val KEY_FLOATING_WINDOW = "floating_window"
        private const val KEY_BROWSER_OPACITY = "browser_opacity"
        private const val KEY_WINDOW_X = "window_x"
        private const val KEY_WINDOW_Y = "window_y"
        private const val KEY_WINDOW_WIDTH = "window_width"
        private const val KEY_WINDOW_HEIGHT = "window_height"
        private const val MIN_BROWSER_OPACITY = 0.30f
        private const val KEY_TEXT_ZOOM = "text_zoom"
        private const val MIN_TEXT_ZOOM = 50
        private const val MAX_TEXT_ZOOM = 200
        private const val TEXT_ZOOM_STEP = 10
        private const val KEY_SHOW_BUBBLE = "show_bubble"
        private const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        private const val MIN_BUBBLE_OPACITY = 0.10f
        private const val HIDDEN_BUBBLE_OPACITY = 0.10f
        private const val SCROLL_BRIDGE_NAME = "BoredBrowserScroll"
        private const val SCROLL_RESTORE_DELAY_MS = 250L
        private const val SCROLL_RESTORE_ATTEMPTS = 16
        private const val MAX_HISTORY_ENTRIES = 100
        private const val HISTORY_MENU_LIMIT = 12
        private const val BOOKMARK_MENU_VISIBLE_ROWS = 8
        private const val MAX_TABS = 4
        private const val DEFAULT_URL = "https://www.google.com"
        private const val KOFI_URL = "https://ko-fi.com/boredgamingph"
        private val AD_HOSTS = setOf(
            "2mdn.net",
            "adform.net",
            "adnxs.com",
            "adsafeprotected.com",
            "adsrvr.org",
            "amazon-adsystem.com",
            "app-measurement.com",
            "casalemedia.com",
            "criteo.com",
            "criteo.net",
            "doubleclick.net",
            "facebook.net",
            "google-analytics.com",
            "googleadservices.com",
            "googlesyndication.com",
            "googletagmanager.com",
            "googletagservices.com",
            "hotjar.com",
            "moatads.com",
            "outbrain.com",
            "scorecardresearch.com",
            "taboola.com",
        )
        private val AD_URL_PARTS = listOf(
            "/ads/",
            "/adserver/",
            "/advertising/",
            "/analytics.js",
            "/banner-ad",
            "/banners/",
            "/collect?",
            "/doubleclick/",
            "/gampad/",
            "/pagead/",
            "/pixel?",
            "/popunder",
            "/prebid",
            "/sponsored/",
            "adservice",
            "adserver",
            "googleads",
        )
    }
}

