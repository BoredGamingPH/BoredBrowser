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
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
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
import kotlin.math.abs
import kotlin.math.max

class FloatingGuideService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var bubbleView: View? = null
    private var browserView: View? = null
    private var closeTargetView: TextView? = null
    private var webView: WebView? = null
    private var browserParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var lastUrl: String = DEFAULT_URL
    private var bubbleVisible: Boolean = true
    private var bubbleOpacity: Float = 1f

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        lastUrl = prefs.getString(KEY_LAST_URL, DEFAULT_URL) ?: DEFAULT_URL
        bubbleVisible = prefs.getBoolean(KEY_SHOW_BUBBLE, true)
        bubbleOpacity = prefs.getFloat(KEY_BUBBLE_OPACITY, 1f).coerceIn(MIN_BUBBLE_OPACITY, 1f)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let {
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
        hideBrowser(returnToBubble = false)
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
            background = roundedDrawable(0xFF14161B.toInt(), 0xFF31353D.toInt(), dp(16).toFloat())
            setPadding(dp(3), dp(3), dp(3), dp(3))
            elevation = dp(8).toFloat()
            alpha = effectiveBubbleAlpha()
            setOnClickListener { toggleBrowser() }
            setOnLongClickListener {
                stopSelf()
                true
            }
        }

        val params = WindowManager.LayoutParams(
            dp(58), dp(58), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_BUBBLE_X, screenWidth() - dp(66))
            y = prefs.getInt(KEY_BUBBLE_Y, dp(8))
        }

        attachDrag(bubble, params, snap = true)
        bubbleView = bubble
        bubbleParams = params
        windowManager.addView(bubble, params)
    }

    private fun showBrowser(url: String) {
        if (!Settings.canDrawOverlays(this)) return
        showBubble()
        dockBubbleAtTop()
        if (browserView != null) {
            browserView?.visibility = View.VISIBLE
            resizeBrowserToScreen()
            webView?.loadUrl(normalizeUrl(url))
            bringBubbleToFront()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xFF1D2128.toInt(), 0xFF31353D.toInt(), dp(8).toFloat())
            elevation = dp(10).toFloat()
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(0xFF14161B.toInt())
        }
        val address = EditText(this).apply {
            setSingleLine(true)
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFA9ADB5.toInt())
            hint = "Search or URL"
            setText(url)
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF31353D.toInt(), dp(6).toFloat())
            setPadding(dp(8), 0, dp(8), 0)
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
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    pageUrl?.let {
                        lastUrl = it
                        prefs.edit().putString(KEY_LAST_URL, it).apply()
                        address.setText(it)
                    }
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(normalizeUrl(url))
        }
        webView = web

        titleBar.addView(iconButton("⌂") { web.loadUrl(DEFAULT_URL) })
        titleBar.addView(address, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        titleBar.addView(menuButton("⋮") { anchor -> showToolbarMenu(anchor, web) })

        container.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        container.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val params = WindowManager.LayoutParams(
            browserWidth(), browserHeight(), overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
        bringBubbleToFront()
    }

    private fun resizeBrowserToScreen() {
        val view = browserView ?: return
        val params = browserParams ?: return
        params.width = browserWidth()
        params.height = browserHeight()
        params.x = 0
        params.y = 0
        runCatching { windowManager.updateViewLayout(view, params) }
    }
    private fun openLauncher() {
        hideBrowser(returnToBubble = true)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun toggleBrowser() {
        if (browserView == null) {
            showBrowser(lastUrl)
        } else {
            hideBrowser(returnToBubble = false)
        }
    }

    private fun hideBrowser(returnToBubble: Boolean = true) {
        browserView?.let {
            runCatching { windowManager.removeView(it) }
        }
        browserView = null
        webView?.destroy()
        webView = null
        if (returnToBubble) showBubble()
    }

    private fun dockBubbleAtTop() {
        val params = bubbleParams ?: return
        params.x = max(dp(8), screenWidth() - params.width - dp(8))
        params.y = dp(8)
        prefs.edit().putInt(KEY_BUBBLE_X, params.x).putInt(KEY_BUBBLE_Y, params.y).apply()
        bubbleView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
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
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            background = ovalDrawable(0xDD3B1111.toInt(), 0xFFFF6B5C.toInt())
            elevation = dp(9).toFloat()
        }
        val size = dp(72)
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth() - size) / 2
            y = screenHeight() - dp(104)
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
        return centerY >= screenHeight() - dp(148) && centerX >= screenWidth() / 2 - dp(88) && centerX <= screenWidth() / 2 + dp(88)
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
                    moved = moved || abs(dx) > dp(4) || abs(dy) > dp(4)
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
                        params.x = if (params.x + params.width / 2 < screenWidth() / 2) dp(8) else max(dp(8), screenWidth() - params.width - dp(8))
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
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF31353D.toInt(), dp(6).toFloat())
            setOnClickListener { action() }
            setOnLongClickListener {
                Toast.makeText(this@FloatingGuideService, description, Toast.LENGTH_SHORT).show()
                true
            }
            val size = dp(38)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(4) }
        }
    }


    private fun menuButton(label: String, action: (View) -> Unit): ImageButton {
        val description = buttonDescription(label)
        return ImageButton(this).apply {
            contentDescription = description
            setImageDrawable(TextDrawable(label))
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF31353D.toInt(), dp(6).toFloat())
            setOnClickListener { action(it) }
            setOnLongClickListener {
                Toast.makeText(this@FloatingGuideService, description, Toast.LENGTH_SHORT).show()
                true
            }
            val size = dp(38)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun showToolbarMenu(anchor: View, web: WebView) {
        lateinit var popup: PopupWindow
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xFF252A33.toInt(), 0xFF3A404B.toInt(), dp(8).toFloat())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        fun addMenuItem(label: String, description: String, action: () -> Unit) {
            val item = TextView(this).apply {
                text = label
                contentDescription = description
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minWidth = dp(148)
                background = roundedDrawable(0x00252A33, 0x0031353D, dp(6).toFloat())
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
                setOnLongClickListener {
                    Toast.makeText(this@FloatingGuideService, description, Toast.LENGTH_SHORT).show()
                    true
                }
            }
            menu.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        }

        addMenuItem("Back", "Go back") { if (web.canGoBack()) web.goBack() }
        addMenuItem("Forward", "Go forward") { if (web.canGoForward()) web.goForward() }
        addMenuItem("Reload", "Reload page") { web.reload() }
        addMenuItem("☕ Donate", "Support BoredBrowser on Ko-fi") { web.loadUrl(KOFI_URL) }
        addMenuItem("Launcher", "Open BoredBrowser launcher") { openLauncher() }

        popup = PopupWindow(menu, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        popup.showAsDropDown(anchor, -dp(116), dp(4))
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
        setStroke(dp(1), stroke)
    }

    private fun ovalDrawable(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(2), stroke)
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
        private const val KEY_SHOW_BUBBLE = "show_bubble"
        private const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        private const val MIN_BUBBLE_OPACITY = 0.10f
        private const val HIDDEN_BUBBLE_OPACITY = 0.10f
        private const val DEFAULT_URL = "https://www.google.com"
        private const val KOFI_URL = "https://ko-fi.com/boredgamingph"
    }
}

