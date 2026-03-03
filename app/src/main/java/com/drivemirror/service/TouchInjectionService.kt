package com.drivemirror.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

/**
 * DriveMirror Touch Injection Service.
 *
 * Receives touch events from the head unit (forwarded via broadcast from UsbReceiver),
 * remaps coordinates from the car display resolution → phone display resolution,
 * and injects them as real touch gestures using AccessibilityService.dispatchGesture().
 *
 * Activation: Impostazioni → Accessibilità → DriveMirror Touch → Abilita
 */
class TouchInjectionService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchInjectionService"

        // Broadcast action from UsbReceiver when a touch packet arrives
        const val ACTION_INJECT_TOUCH = "com.drivemirror.INJECT_TOUCH"
        const val EXTRA_TOUCH_X = "x"
        const val EXTRA_TOUCH_Y = "y"
        const val EXTRA_TOUCH_TYPE = "type"    // down, move, up
        const val EXTRA_TOUCH_SOURCE_W = "src_w"
        const val EXTRA_TOUCH_SOURCE_H = "src_h"

        private const val GESTURE_DURATION_MS = 50L
        private const val SWIPE_DURATION_MS = 150L

        // Singleton reference so UsbReceiver can call directly (same process)
        @Volatile
        var instance: TouchInjectionService? = null
            private set
    }

    private var phoneDisplayWidth = 0
    private var phoneDisplayHeight = 0

    // Track ongoing gestures (pointer ID → active path)
    private val activePointers = mutableMapOf<Int, PointF>()

    private val touchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_INJECT_TOUCH) return
            val x = intent.getFloatExtra(EXTRA_TOUCH_X, -1f)
            val y = intent.getFloatExtra(EXTRA_TOUCH_Y, -1f)
            val type = intent.getStringExtra(EXTRA_TOUCH_TYPE) ?: "tap"
            val srcW = intent.getIntExtra(EXTRA_TOUCH_SOURCE_W, 1280)
            val srcH = intent.getIntExtra(EXTRA_TOUCH_SOURCE_H, 720)

            if (x < 0 || y < 0) return
            injectTouch(x, y, type, srcW, srcH)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        updateDisplayMetrics()

        registerReceiver(touchReceiver, IntentFilter(ACTION_INJECT_TOUCH),
            RECEIVER_EXPORTED)

        Log.i(TAG, "TouchInjectionService connected — display: ${phoneDisplayWidth}x${phoneDisplayHeight}")
    }

    /**
     * Main entry point. Called from UsbReceiver directly (same process)
     * or via broadcast (cross-process fallback).
     */
    fun injectTouch(
        srcX: Float, srcY: Float,
        type: String,
        srcWidth: Int = 1280, srcHeight: Int = 720
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires API 24+")
            return
        }

        // Remap coordinates: head unit display → phone display
        val phoneX = (srcX / srcWidth) * phoneDisplayWidth
        val phoneY = (srcY / srcHeight) * phoneDisplayHeight

        Log.v(TAG, "Touch $type: src(${srcX},${srcY}) → phone(${phoneX},${phoneY})")

        val gesture = buildGesture(phoneX, phoneY, type) ?: return

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.v(TAG, "Gesture dispatched: $type")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture cancelled: $type")
            }
        }, null)
    }

    /**
     * Inject a swipe gesture (for scroll events from the head unit).
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun injectSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        srcWidth: Int = 1280, srcHeight: Int = 720
    ) {
        val pStartX = (startX / srcWidth) * phoneDisplayWidth
        val pStartY = (startY / srcHeight) * phoneDisplayHeight
        val pEndX = (endX / srcWidth) * phoneDisplayWidth
        val pEndY = (endY / srcHeight) * phoneDisplayHeight

        val path = Path().apply {
            moveTo(pStartX, pStartY)
            lineTo(pEndX, pEndY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun buildGesture(x: Float, y: Float, type: String): GestureDescription? {
        val path = Path().apply { moveTo(x, y) }

        val duration = when (type) {
            "down", "up", "tap" -> GESTURE_DURATION_MS
            "longpress" -> 600L
            else -> GESTURE_DURATION_MS
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun updateDisplayMetrics() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let {
                phoneDisplayWidth = it.width()
                phoneDisplayHeight = it.height()
            }
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            phoneDisplayWidth = metrics.widthPixels
            phoneDisplayHeight = metrics.heightPixels
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        Log.w(TAG, "TouchInjectionService interrupted")
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(touchReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
