package com.drivemirror.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMirrorService : LifecycleService() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val TAG = "ScreenMirrorService"
        private const val CHANNEL_ID = "drivemirror_channel"
        private const val NOTIFICATION_ID = 1001
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FPS = 30
        const val NO_RESULT_CODE = Int.MIN_VALUE
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScreenMirrorService = this@ScreenMirrorService
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private val speedLock = SpeedLockManager(this)
    private val isRunning = AtomicBoolean(false)

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        Log.d(TAG, "onStartCommand startId=$startId")

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, NO_RESULT_CODE) ?: NO_RESULT_CODE
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode == NO_RESULT_CODE || data == null) {
            Log.e(TAG, "Dati mancanti, arresto")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection null")

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "Projection fermata dal sistema")
                    stopMirroring()
                    stopSelf()
                }
            }, null)

            observeSpeedLock()
            
            // Condivide la proiezione con lo schermo Android Auto
            com.drivemirror.ui.DriveMirrorCarScreen.sharedProjection = mediaProjection
            
            startMirroring()

        } catch (e: Exception) {
            Log.e(TAG, "Avvio fallito", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_REDELIVER_INTENT
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("Mirroring in avvio...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeSpeedLock() {
        speedLock.start()
        speedLock.speedState.onEach { state ->
            when (state) {
                SpeedLockManager.SpeedState.MOVING -> {
                    pauseStream()
                    updateNotification("🚗 In movimento — pausa")
                }
                SpeedLockManager.SpeedState.SAFE -> {
                    if (isRunning.get()) resumeStream()
                    updateNotification("🔴 Mirroring attivo")
                }
                else -> updateNotification("🔴 Mirroring (no GPS)")
            }
        }.launchIn(lifecycleScope)
    }

    private fun startMirroring() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Already running")
            return
        }

        try {
            // Avvisa il CarScreen che la proiezione è pronta (se lo schermo auto è attivo, creerà il VirtualDisplay)
            com.drivemirror.ui.DriveMirrorCarScreen.getInstance()?.onProjectionReady()

            Log.i(TAG, "Mirroring started - waiting for CarScreen surface")
            updateNotification("🔴 Mirroring attivo")

        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            isRunning.set(false)
            stopSelf()
        }
    }

    private fun pauseStream() {
        try { virtualDisplay?.surface = null } catch (e: Exception) {}
    }

    private fun resumeStream() {
        // La ripresa viene gestita dal ciclo di vita del CarScreen
    }

    fun stopMirroring() {
        if (!isRunning.getAndSet(false)) return

        Log.i(TAG, "Stopping...")

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        speedLock.stop()
        
        // Notifica il CarScreen di fermare il display virtuale
        com.drivemirror.ui.DriveMirrorCarScreen.getInstance()?.onProjectionStopped()

        Log.i(TAG, "Stopped")
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let {
                metrics.widthPixels = it.width()
                metrics.heightPixels = it.height()
            }
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        metrics.densityDpi = resources.displayMetrics.densityDpi
        return metrics
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "DriveMirror", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Screen mirroring"; setSound(null, null) }
                .also { notificationManager.createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DriveMirror")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopMirroring()
        super.onDestroy()
    }
}