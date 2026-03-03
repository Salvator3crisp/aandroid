package com.drivemirror.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated H.264 encoder using MediaCodec.
 * Exposes getCodec() for AdaptiveBitrateController.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onEncodedFrame: (ByteArray, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = "video/avc"
        private const val TIMEOUT_US = 10_000L
        private const val I_FRAME_INTERVAL = 2
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderThread: Thread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = it.createInputSurface()
            it.start()
            Log.i(TAG, "Encoder started: ${width}x${height} @ ${fps}fps, ${bitrate / 1000}kbps")
        }

        isRunning.set(true)
        encoderThread = Thread({ drainEncoder() }, "H264EncoderDrain").also { it.start() }
    }

    fun getInputSurface(): Surface = inputSurface ?: throw IllegalStateException("Call start() first")

    fun getCodec(): MediaCodec = codec ?: throw IllegalStateException("Encoder not running")

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            val outputIndex = codec?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US) ?: break
            if (outputIndex >= 0) {
                val outputBuffer: ByteBuffer = codec!!.getOutputBuffer(outputIndex) ?: continue
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    codec!!.releaseOutputBuffer(outputIndex, false)
                    continue
                }
                if (bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val data = ByteArray(bufferInfo.size)
                    outputBuffer.get(data)
                    val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    onEncodedFrame(data, isKeyFrame)
                }
                codec!!.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.e(TAG, "Keyframe request failed", e)
        }
    }

    fun stop() {
        isRunning.set(false)
        encoderThread?.join(2000)
        encoderThread = null
        try {
            codec?.signalEndOfInputStream()
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Encoder stop error", e)
        }
        inputSurface?.release()
        inputSurface = null
        codec = null
        Log.i(TAG, "Encoder stopped")
    }
}
