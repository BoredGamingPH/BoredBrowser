package com.boredgaming.boredbrowser.overlay

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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.boredgaming.boredbrowser.MainActivity
import com.boredgaming.boredbrowser.R
import org.json.JSONArray
import org.json.JSONObject
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

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var bubbleView: View? = null
    private var browserView: View? = null
    private var closeTargetView: TextView? = null
    private var webView: WebView? = null
    private var browserParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var webState: Bundle? = null
    private var browserAttached: Boolean = false
    private var browserMinimized: Boolean = false
    private var lastUrl: String = DEFAULT_URL
    private var lastScrollX: Int = 0
    private var lastScrollY: Int = 0
    private var bubbleVisible: Boolean = true
    private var bubbleOpacity: Float = 1f

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        lastUrl = prefs.getString(KEY_LAST_URL, DEFAULT_URL) ?: DEFAULT_URL
        lastScrollX = prefs.getInt(KEY_SCROLL_X, 0)
        lastScrollY = prefs.getInt(KEY_SCROLL_Y, 0)
        bubbleVisible = prefs.getBoolean(KEY_SHOW_BUBBLE, true)
        bubbleOpacity = prefs.getFloat(KEY_BUBBLE_OPACITY, 1f).coerceIn(MIN_BUBBLE_OPACITY, 1f)
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
            attachBrowserIfNeeded()
            resizeBrowserToScreen()
            restoreScrollWhenReady()
            bringBubbleToFront()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xFF1D2128.toInt(), 0xFF31353D.toInt(), sdp(8).toFloat())
            elevation = sdp(10).toFloat()
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

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    pageUrl?.let {
                        val previousUrl = lastUrl
                        lastUrl = it
                        if (previousUrl != it) {
                            lastScrollX = 0
                            lastScrollY = 0
                        }
                        prefs.edit()
                            .putString(KEY_LAST_URL, it)
                            .putInt(KEY_SCROLL_X, lastScrollX)
                            .putInt(KEY_SCROLL_Y, lastScrollY)
                            .apply()
                        address.setText(it)
                        recordHistory(it, view?.title)
                        restoreScrollWhenReady()
                    }
                }
            }
            webChromeClient = WebChromeClient()
            if (webState?.let { restoreState(it) != null } != true) {
                loadUrl(normalizeUrl(url))
            }
        }
        webView = web

        titleBar.addView(iconButton("⌂") { web.loadUrl(DEFAULT_URL) })
        titleBar.addView(address, LinearLayout.LayoutParams(0, toolbarButtonSize(), 1f).apply { marginStart = sdp(4); marginEnd = sdp(4) })
        titleBar.addView(menuButton("⋮") { anchor -> showToolbarMenu(anchor, web) })

        container.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight()))
        container.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val params = WindowManager.LayoutParams(
            browserWidth(), browserHeight(), overlayType(),
            browserWindowFlags(minimized = false),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        browserView = container
        browserParams = params
        windowManager.addView(container, params)
        browserAttached = true
        browserMinimized = false
        bringBubbleToFront()
    }

    private fun resizeBrowserToScreen() {
        val view = browserView ?: return
        val params = browserParams ?: return
        params.width = browserWidth()
        params.height = browserHeight()
        params.x = 0
        params.y = 0
        params.flags = browserWindowFlags(minimized = false)
        browserMinimized = false
        if (browserAttached) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
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

    private fun toggleBrowser() {
        if (browserView == null || !browserAttached || browserMinimized) {
            showBrowser(lastUrl)
        } else {
            hideBrowser(returnToBubble = false)
        }
    }

    private fun hideBrowser(returnToBubble: Boolean = true, destroy: Boolean = false) {
        saveBrowserState()
        if (destroy) {
            browserView?.let {
                if (browserAttached) {
                    runCatching { windowManager.removeView(it) }
                    browserAttached = false
                }
            }
            browserView = null
            webView?.destroy()
            webView = null
            browserParams = null
            browserMinimized = false
        } else {
            minimizeBrowserWindow()
        }
        if (returnToBubble) showBubble()
    }

    private fun minimizeBrowserWindow() {
        val view = browserView ?: return
        val params = browserParams ?: return
        view.visibility = View.INVISIBLE
        params.width = 1
        params.height = 1
        params.x = 0
        params.y = 0
        params.flags = browserWindowFlags(minimized = true)
        browserMinimized = true
        if (browserAttached) {
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun saveBrowserState() {
        webView?.let { web ->
            lastUrl = web.url ?: lastUrl
            lastScrollX = web.scrollX
            lastScrollY = web.scrollY
            webState = Bundle().also { web.saveState(it) }
            prefs.edit()
                .putString(KEY_LAST_URL, lastUrl)
                .putInt(KEY_SCROLL_X, lastScrollX)
                .putInt(KEY_SCROLL_Y, lastScrollY)
                .apply()
        }
    }

    private fun restoreScrollWhenReady() {
        webView?.postDelayed({
            webView?.scrollTo(lastScrollX, lastScrollY)
        }, SCROLL_RESTORE_DELAY_MS)
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
        handle.setOnTouchListener { view, event ->
            val targetView = if (snap) bubbleView ?: view else browserView ?: view
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    if (snap) showCloseTarget()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    moved = moved || abs(dx) > sdp(4) || abs(dy) > sdp(4)
                    params.x = clampToScreen(initialX + dx, params.width, screenWidth())
                    params.y = clampToScreen(initialY + dy, params.height, screenHeight())
                    runCatching { windowManager.updateViewLayout(targetView, params) }
                    if (snap) updateCloseTarget(params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

    private fun showToolbarMenu(anchor: View, web: WebView) {
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

        addMenuItem("Back", "Go back") { if (web.canGoBack()) web.goBack() }
        addMenuItem("Forward", "Go forward") { if (web.canGoForward()) web.goForward() }
        addMenuItem("Reload", "Reload page") { web.reload() }
        addMenuItem("History", "Open browsing history") { showHistoryPopup(anchor, web) }
        addMenuItem("☕ Donate", "Support BoredBrowser on Ko-fi") { web.loadUrl(KOFI_URL) }
        addMenuItem("Launcher", "Open BoredBrowser launcher") { openLauncher() }

        popup = PopupWindow(menu, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = sdp(8).toFloat()
        }
        popup.showAsDropDown(anchor, -menuHorizontalOffset(), sdp(4))
    }

    private fun showHistoryPopup(anchor: View, web: WebView) {
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
                        web.loadUrl(entry.url)
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
        popup.showAsDropDown(anchor, -historyMenuHorizontalOffset(), sdp(4))
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

    private fun buttonDescription(label: String): String = when (label) {
        "☰" -> "Open BoredBrowser launcher"
        "‹" -> "Go back"
        "›" -> "Go forward"
        "↻" -> "Reload page"
        "⌂" -> "Open home page"
        "⋮" -> "Open browser actions"
        else -> label
    }

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
    private fun historyMenuWidth(): Int = sdp(230).coerceIn(dp(190), dp(300))
    private fun historyMenuHorizontalOffset(): Int = sdp(198).coerceIn(dp(150), dp(250))
    private fun browserWindowFlags(minimized: Boolean): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or if (minimized) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            0
        }
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
        private const val KEY_HISTORY = "browser_history"
        private const val KEY_SHOW_BUBBLE = "show_bubble"
        private const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        private const val MIN_BUBBLE_OPACITY = 0.10f
        private const val HIDDEN_BUBBLE_OPACITY = 0.10f
        private const val SCROLL_RESTORE_DELAY_MS = 250L
        private const val MAX_HISTORY_ENTRIES = 100
        private const val HISTORY_MENU_LIMIT = 12
        private const val DEFAULT_URL = "https://www.google.com"
        private const val KOFI_URL = "https://ko-fi.com/boredgamingph"
    }
}

