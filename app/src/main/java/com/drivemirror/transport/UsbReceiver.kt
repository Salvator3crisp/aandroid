package com.drivemirror.transport

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.drivemirror.service.TouchInjectionService
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB Receiver - lato head unit.
 * Riceve frame con header a 16 byte.
 */
class UsbReceiver(
    private val context: Context,
    private val onFrame: (ByteArray, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "UsbReceiver"
        private val MAGIC = byteArrayOf(0x44, 0x4D, 0x49, 0x52)
        private const val HEADER_SIZE = 16
        private const val READ_BUFFER_SIZE = 512 * 1024
        private const val TYPE_VIDEO: Byte = 0x01
        private const val TYPE_TOUCH: Byte = 0x02
        private const val TOUCH_SWIPE: Byte = 0x05
    }

    private val isRunning = AtomicBoolean(false)
    private var receiverThread: Thread? = null
    private var dispatchThread: Thread? = null
    private val frameQueue = LinkedBlockingQueue<Pair<ByteArray, Boolean>>(60)

    // Contatori per log diagnostici
    private var frameCounter = 0
    private var lastLogTime = System.currentTimeMillis()

    fun start() {
        if (isRunning.getAndSet(true)) return
        receiverThread = Thread({ receiveLoop() }, "UsbReceiver").also { it.start() }
        dispatchThread = Thread({ dispatchLoop() }, "UsbDispatcher").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun receiveLoop() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull()
        if (device == null) {
            Log.w(TAG, "Nessun dispositivo USB — attesa...")
            while (isRunning.get()) {
                Thread.sleep(100)
            }
            return
        }

        val pair = findBulkInEndpoint(device) ?: run {
            Log.e(TAG, "Nessun endpoint bulk IN")
            return
        }
        val (usbIface, endpoint) = pair
        val conn = usbManager.openDevice(device) ?: return
        conn.claimInterface(usbIface, true)

        val readBuffer = ByteArray(READ_BUFFER_SIZE)
        val accumulator = ByteBuffer.allocate(READ_BUFFER_SIZE * 4)

        while (isRunning.get()) {
            try {
                val len = conn.bulkTransfer(endpoint, readBuffer, READ_BUFFER_SIZE, 200)
                if (len > 0) {
                    accumulator.put(readBuffer, 0, len)
                    parsePackets(accumulator)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore lettura USB", e)
                break
            }
        }

        try {
            conn.releaseInterface(usbIface)
            conn.close()
        } catch (e: Exception) {
            Log.e(TAG, "Errore chiusura USB", e)
        }
    }

    private fun parsePackets(buffer: ByteBuffer) {
        buffer.flip()
        while (buffer.remaining() >= HEADER_SIZE) {
            buffer.mark()
            val magic = ByteArray(4)
            buffer.get(magic)
            if (!magic.contentEquals(MAGIC)) {
                buffer.reset()
                buffer.position(buffer.position() + 1)
                continue
            }
            val packetType = buffer.get()
            buffer.position(buffer.position() + 3) // salta reserved
            val payloadLength = buffer.int
            val flags = buffer.get()
            buffer.position(buffer.position() + 3) // salta reserved

            if (buffer.remaining() < payloadLength) {
                buffer.reset()
                buffer.compact()
                return
            }
            val payload = ByteArray(payloadLength)
            buffer.get(payload)

            when (packetType) {
                TYPE_VIDEO -> {
                    frameQueue.offer(Pair(payload, (flags.toInt() and 0x01) != 0))
                }
                TYPE_TOUCH -> handleTouch(payload, flags)
                else -> Log.w(TAG, "Tipo pacchetto sconosciuto: 0x${packetType.toString(16)}")
            }
        }
        buffer.compact()
    }

    private fun handleTouch(payload: ByteArray, subType: Byte) {
        if (payload.size < 8) return
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val x = buf.float
        val y = buf.float
        val x2 = if (payload.size >= 16) buf.float else 0f
        val y2 = if (payload.size >= 16) buf.float else 0f

        val touchType = when (subType) {
            0x01.toByte() -> "tap"
            0x02.toByte() -> "down"
            0x03.toByte() -> "move"
            0x04.toByte() -> "up"
            TOUCH_SWIPE   -> "swipe"
            else          -> "tap"
        }

        val service = TouchInjectionService.instance
        if (service != null) {
            if (touchType == "swipe") service.injectSwipe(x, y, x2, y2)
            else service.injectTouch(x, y, touchType)
        } else {
            val intent = Intent(TouchInjectionService.ACTION_INJECT_TOUCH).apply {
                putExtra(TouchInjectionService.EXTRA_TOUCH_X, x)
                putExtra(TouchInjectionService.EXTRA_TOUCH_Y, y)
                putExtra(TouchInjectionService.EXTRA_TOUCH_TYPE, touchType)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    private fun dispatchLoop() {
        while (isRunning.get()) {
            try {
                val frame = frameQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                onFrame(frame.first, frame.second)

                // Log diagnostico ogni 5 secondi
                frameCounter++
                val now = System.currentTimeMillis()
                if (now - lastLogTime > 5000) {
                    Log.i(TAG, "Ricevuti $frameCounter frame negli ultimi 5s")
                    frameCounter = 0
                    lastLogTime = now
                }
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun findBulkInEndpoint(device: android.hardware.usb.UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                    return Pair(iface, ep)
                }
            }
        }
        return null
    }

    fun getQueueDepth(): Int = frameQueue.size

    fun stop() {
        isRunning.set(false)
        receiverThread?.interrupt()
        receiverThread?.join(1000)
        receiverThread = null
        dispatchThread?.interrupt()
        dispatchThread?.join(500)
        dispatchThread = null
        frameQueue.clear()
    }
}