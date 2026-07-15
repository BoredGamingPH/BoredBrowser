package com.boredgaming.boredbrowser

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.boredgaming.boredbrowser.overlay.FloatingGuideService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "boredbrowser/overlay_methods").setMethodCallHandler { call, result ->
            when (call.method) {
                "hasOverlayPermission" -> result.success(Settings.canDrawOverlays(this))
                "requestOverlayPermission" -> {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    result.success(null)
                }
                "isOverlayRunning" -> result.success(FloatingGuideService.isRunning)
                "openExternalUrl" -> {
                    val url = call.argument<String>("url") ?: ""
                    if (url.isBlank()) {
                        result.success(false)
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { startActivity(intent) }
                            .onSuccess { result.success(true) }
                            .onFailure { result.success(false) }
                    }
                }
                "startOverlay" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        result.success(false)
                    } else {
                        val serviceIntent = Intent(this, FloatingGuideService::class.java).apply {
                            putExtra(FloatingGuideService.EXTRA_URL, call.argument<String>("url") ?: "")
                            putExtra(FloatingGuideService.EXTRA_SHOW_BUBBLE, call.argument<Boolean>("showBubble") ?: true)
                            putExtra(FloatingGuideService.EXTRA_BUBBLE_OPACITY, call.argument<Double>("bubbleOpacity") ?: 1.0)
                        }
                        startOverlayService(serviceIntent)
                        moveTaskToBack(true)
                        result.success(true)
                    }
                }
                "stopOverlay" -> {
                    startService(Intent(this, FloatingGuideService::class.java).setAction(FloatingGuideService.ACTION_STOP))
                    result.success(null)
                }
                "showFloatingBrowser" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        result.success(false)
                    } else {
                        val serviceIntent = Intent(this, FloatingGuideService::class.java).apply {
                            action = FloatingGuideService.ACTION_SHOW_BROWSER
                            putExtra(FloatingGuideService.EXTRA_URL, call.argument<String>("url") ?: "")
                            putExtra(FloatingGuideService.EXTRA_SHOW_BUBBLE, call.argument<Boolean>("showBubble") ?: true)
                            putExtra(FloatingGuideService.EXTRA_BUBBLE_OPACITY, call.argument<Double>("bubbleOpacity") ?: 1.0)
                        }
                        startOverlayService(serviceIntent)
                        moveTaskToBack(true)
                        result.success(true)
                    }
                }
                "updateOverlaySettings" -> {
                    if (!Settings.canDrawOverlays(this) || !FloatingGuideService.isRunning) {
                        result.success(false)
                    } else {
                        val serviceIntent = Intent(this, FloatingGuideService::class.java).apply {
                            action = FloatingGuideService.ACTION_UPDATE_SETTINGS
                            putExtra(FloatingGuideService.EXTRA_SHOW_BUBBLE, call.argument<Boolean>("showBubble") ?: true)
                            putExtra(FloatingGuideService.EXTRA_BUBBLE_OPACITY, call.argument<Double>("bubbleOpacity") ?: 1.0)
                        }
                        startOverlayService(serviceIntent)
                        result.success(true)
                    }
                }
                "hideFloatingBrowser" -> {
                    startService(Intent(this, FloatingGuideService::class.java).setAction(FloatingGuideService.ACTION_HIDE_BROWSER))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun startOverlayService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

