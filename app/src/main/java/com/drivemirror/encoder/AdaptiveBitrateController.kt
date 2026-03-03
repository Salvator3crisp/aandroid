package com.drivemirror.encoder

import android.media.MediaCodec
import android.os.Bundle
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AdaptiveBitrateController(
    private val codec: MediaCodec,
    private val getQueueDepth: () -> Int,
    private val onBitrateChanged: (Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "AdaptiveBitrate"
        const val MAX_BITRATE     = 8_000_000
        const val DEFAULT_BITRATE = 4_000_000
        const val MIN_BITRATE     =   500_000
        private const val STEP_UP   =   500_000
        private const val STEP_DOWN = 1_000_000
        private const val QUEUE_HIGH = 20
        private const val QUEUE_LOW  =  5
        private const val COOLDOWN_MS = 2_000L
    }

    private var currentBitrate = DEFAULT_BITRATE
    private var lastChangeTime = 0L
    private val queueHistory = ArrayDeque<Int>(5)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null

    fun start() {
        future = scheduler.scheduleAtFixedRate({ evaluateAndAdjust() }, 1L, 1L, TimeUnit.SECONDS)
        Log.i(TAG, "ABR started — ${DEFAULT_BITRATE / 1000}kbps")
    }

    private fun evaluateAndAdjust() {
        val depth = getQueueDepth()
        if (queueHistory.size >= 5) queueHistory.removeFirst()
        queueHistory.addLast(depth)
        val avg = queueHistory.average()
        val now = System.currentTimeMillis()
        if (now - lastChangeTime < COOLDOWN_MS) return

        val newBitrate = when {
            avg > QUEUE_HIGH -> (currentBitrate - STEP_DOWN).coerceAtLeast(MIN_BITRATE)
            avg < QUEUE_LOW && currentBitrate < MAX_BITRATE -> (currentBitrate + STEP_UP).coerceAtMost(MAX_BITRATE)
            else -> currentBitrate
        }

        if (newBitrate != currentBitrate) {
            try {
                val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate) }
                codec.setParameters(params)
                Log.i(TAG, "Bitrate ${currentBitrate/1000} → ${newBitrate/1000}kbps (queue avg=${"%.1f".format(avg)})")
                onBitrateChanged(newBitrate)
            } catch (e: Exception) {
                Log.e(TAG, "setParameters failed", e)
            }
            currentBitrate = newBitrate
            lastChangeTime = now
        }
    }

    fun requestKeyFrame() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) { Log.e(TAG, "Keyframe failed", e) }
    }

    fun stop() {
        future?.cancel(false)
        scheduler.shutdown()
        Log.i(TAG, "ABR stopped")
    }
}
