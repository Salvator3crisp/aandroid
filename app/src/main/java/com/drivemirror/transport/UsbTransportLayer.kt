package com.drivemirror.transport

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * USB Transport Layer per DriveMirror.
 * Invia frame H.264 con header di 16 byte allineato con UsbReceiver.
 */
class UsbTransportLayer(private val context: Context) {

    companion object {
        private const val TAG = "UsbTransport"
        private const val TRANSFER_TIMEOUT_MS = 100
        private const val MAX_QUEUE_SIZE = 30

        // Header: 16 byte: [MAGIC(4)][TYPE(1)][RESERVED(3)][LENGTH(4)][FLAGS(1)][RESERVED(3)]
        private val MAGIC = byteArrayOf(0x44, 0x4D, 0x49, 0x52) // "DMIR"
        private const val HEADER_SIZE = 16
        private const val TYPE_VIDEO: Byte = 0x01

        // Vendor ID noti per Android Auto
        private val KNOWN_VENDOR_IDS = setOf(0x18D1, 0x04E8, 0x2717, 0x22B8)
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    private var usbInterface: UsbInterface? = null

    private val frameQueue = LinkedBlockingQueue<FramePacket>(MAX_QUEUE_SIZE)
    private val isSending = AtomicBoolean(false)
    private var senderThread: Thread? = null

    private val totalBytesSent = AtomicLong(0)
    private val framesSent = AtomicLong(0)

    // Contatori per log diagnostici
    private var frameCounter = 0
    private var lastLogTime = System.currentTimeMillis()

    data class FramePacket(val data: ByteArray, val isKeyFrame: Boolean)

    /**
     * Tenta la connessione USB. Restituisce true solo se riesce a connettersi a un dispositivo valido.
     * NON entra mai in modalità mock.
     */
    fun connect(): Boolean {
        val device = findCompatibleDevice()
        if (device == null) {
            Log.e(TAG, "Nessun dispositivo USB compatibile trovato.")
            return false
        }

        if (!usbManager.hasPermission(device)) {
            Log.e(TAG, "Permesso USB negato per ${device.productName}")
            return false
        }

        return setupConnection(device)
    }

    private fun findCompatibleDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            KNOWN_VENDOR_IDS.contains(device.vendorId) || hasValidBulkInterface(device)
        }
    }

    private fun hasValidBulkInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    return true
                }
            }
        }
        return false
    }

    private fun setupConnection(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    val conn = usbManager.openDevice(device) ?: continue
                    if (conn.claimInterface(iface, true)) {
                        connection = conn
                        usbInterface = iface
                        bulkOutEndpoint = endpoint
                        Log.i(TAG, "USB connesso: ${device.productName}, maxPacket=${endpoint.maxPacketSize}")
                        startSender()
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Accoda un frame per l'invio.
     */
    fun sendFrame(data: ByteArray, isKeyFrame: Boolean) {
        if (frameQueue.size >= MAX_QUEUE_SIZE - 1) {
            val dropped = frameQueue.poll()
            Log.v(TAG, "Coda piena, frame scartato (keyframe=${dropped?.isKeyFrame})")
        }
        frameQueue.offer(FramePacket(data.copyOf(), isKeyFrame))
    }

    private fun startSender() {
        isSending.set(true)
        senderThread = Thread({
            while (isSending.get()) {
                try {
                    val packet = frameQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    sendPacketOverUsb(packet)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "UsbFrameSender").also { it.start() }
    }

    private fun sendPacketOverUsb(packet: FramePacket) {
        val endpoint = bulkOutEndpoint ?: return
        val conn = connection ?: return

        val payload = buildFrame(packet.data, packet.isKeyFrame)

        var offset = 0
        while (offset < payload.size && isSending.get()) {
            val chunkSize = minOf(endpoint.maxPacketSize * 16, payload.size - offset)
            val transferred = conn.bulkTransfer(endpoint, payload, offset, chunkSize, TRANSFER_TIMEOUT_MS)
            if (transferred < 0) {
                Log.e(TAG, "USB transfer fallito a offset $offset")
                break
            }
            offset += transferred
        }

        framesSent.incrementAndGet()
        totalBytesSent.addAndGet(payload.size.toLong())

        // Log diagnostico ogni 5 secondi
        frameCounter++
        val now = System.currentTimeMillis()
        if (now - lastLogTime > 5000) {
            Log.i(TAG, "Inviati $frameCounter frame negli ultimi 5s (totale ${framesSent.get()})")
            frameCounter = 0
            lastLogTime = now
        }
    }

    /**
     * Costruisce il frame con header a 16 byte.
     */
    private fun buildFrame(data: ByteArray, isKeyFrame: Boolean): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + data.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)                          // 4 magic
        buffer.put(TYPE_VIDEO)                     // 1 type
        buffer.put(ByteArray(3))                   // 3 reserved
        buffer.putInt(data.size)                    // 4 length
        buffer.put(if (isKeyFrame) 0x01 else 0x00) // 1 flags
        buffer.put(ByteArray(3))                    // 3 reserved
        buffer.put(data)                            // payload
        return buffer.array()
    }

    fun disconnect() {
        isSending.set(false)
        senderThread?.interrupt()
        senderThread?.join(1000)
        senderThread = null

        connection?.releaseInterface(usbInterface)
        connection?.close()
        connection = null
        bulkOutEndpoint = null
        usbInterface = null
        frameQueue.clear()

        Log.i(TAG, "USB disconnesso. Inviati ${framesSent.get()} frame, ${totalBytesSent.get() / 1024} KB")
    }

    data class TransportStats(
        val framesSent: Long,
        val totalKbSent: Long,
        val queueDepth: Int
    )

    fun getStats() = TransportStats(
        framesSent = framesSent.get(),
        totalKbSent = totalBytesSent.get() / 1024,
        queueDepth = frameQueue.size
    )
}