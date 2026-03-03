package com.drivemirror.ui

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

/**
 * Car screen that renders the mirrored phone display onto the Android Auto head unit.
 *
 * Uses NavigationTemplate to obtain a Surface via SurfaceCallback,
 * then creates a VirtualDisplay from ScreenMirrorService's MediaProjection
 * that renders directly onto that Surface — no encoding/decoding needed.
 *
 * If CarAppService is not supported by the head unit, this won't be used
 * and the app falls back to MediaBrowserService (audio-only, but at least visible).
 */
class DriveMirrorCarScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "DriveMirrorCarScreen"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720

        /**
         * Shared MediaProjection — set by ScreenMirrorService when mirroring starts.
         * The CarScreen reads this to create a VirtualDisplay on the car Surface.
         */
        @Volatile
        var sharedProjection: MediaProjection? = null

        @Volatile
        private var instance: DriveMirrorCarScreen? = null

        fun getInstance(): DriveMirrorCarScreen? = instance
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var carSurface: Surface? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val surface = surfaceContainer.surface ?: return
            carSurface = surface
            Log.i(TAG, "Car surface available: ${surfaceContainer.width}x${surfaceContainer.height}")
            tryStartProjection(surface)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            Log.i(TAG, "Car surface destroyed")
            stopProjection()
            carSurface = null
        }

        override fun onVisibleAreaChanged(visibleArea: android.graphics.Rect) {
            Log.d(TAG, "Visible area: $visibleArea")
        }

        override fun onStableAreaChanged(stableArea: android.graphics.Rect) {
            Log.d(TAG, "Stable area: $stableArea")
        }
    }

    init {
        instance = this
        carContext.getCarService(androidx.car.app.AppManager::class.java)
            .setSurfaceCallback(surfaceCallback)
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("DriveMirror")
                            .setOnClickListener { /* no-op */ }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /**
     * Called when a Surface is available AND we have a MediaProjection.
     * Creates a VirtualDisplay that mirrors the phone screen directly onto the car Surface.
     */
    private fun tryStartProjection(surface: Surface) {
        val projection = sharedProjection
        if (projection == null) {
            Log.w(TAG, "No MediaProjection yet — waiting for ScreenMirrorService to start")
            return
        }

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "DriveMirrorCar",
                VIDEO_WIDTH, VIDEO_HEIGHT,
                carContext.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null, null
            )
            Log.i(TAG, "VirtualDisplay created on car surface")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay", e)
        }
    }

    /**
     * Called externally when ScreenMirrorService starts and sets sharedProjection.
     * If the car Surface is already available, starts mirroring immediately.
     */
    fun onProjectionReady() {
        carSurface?.let { tryStartProjection(it) }
    }

    /**
     * Called externally when ScreenMirrorService stops and clears sharedProjection.
     */
    fun onProjectionStopped() {
        stopProjection()
    }

    private fun stopProjection() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
    }
}
