package com.drivemirror.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.drivemirror.R
import com.drivemirror.service.ScreenMirrorService
import com.drivemirror.transport.UsbReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "MainActivity"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val MIME_TYPE = "video/avc"

        // Chiavi per salvataggio stato
        private const val KEY_RESULT_CODE = "result_code"
        private const val KEY_RESULT_DATA = "result_data"
        private const val KEY_IS_MIRRORING = "is_mirroring"
    }

    private lateinit var videoSurface: SurfaceView
    private lateinit var phoneControls: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var startButton: Button

    private var isRunningOnCar = false

    // FIX: usa NO_RESULT_CODE (Int.MIN_VALUE) come sentinel — Activity.RESULT_OK vale -1
    private var pendingResultCode: Int = ScreenMirrorService.NO_RESULT_CODE
    private var pendingResultData: Intent? = null
    private var isMirroringActive = false

    // Video receiver (modalità AUTO)
    private var decoder: MediaCodec? = null
    private var usbReceiver: UsbReceiver? = null
    private var isReceiving = false
    private var decoderThread: Thread? = null

    // Service connection
    private var mirrorService: ScreenMirrorService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenMirrorService.LocalBinder
            mirrorService = binder.getService()
            isBound = true
            Log.i(TAG, "Service connected, pending=$pendingResultCode")

            // Se abbiamo dati pending, avvia subito
            if (pendingResultCode != ScreenMirrorService.NO_RESULT_CODE && pendingResultData != null) {
                doStartMirroring(pendingResultCode, pendingResultData!!)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mirrorService = null
            isBound = false
        }
    }

    // Launcher per MediaProjection
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Projection result: ${result.resultCode}, data=${result.data != null}")

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Salva i dati IMMEDIATAMENTE
            pendingResultCode = result.resultCode
            pendingResultData = result.data
            isMirroringActive = true

            if (isBound && mirrorService != null) {
                doStartMirroring(result.resultCode, result.data!!)
            } else {
                // Bind e aspetta
                bindMirrorService()
                Toast.makeText(this, "Avvio servizio...", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Permesso negato", Toast.LENGTH_SHORT).show()
            isMirroringActive = false
            updateStatus("Permesso negato", StatusState.DISCONNECTED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Recupera stato se presente
        savedInstanceState?.let {
            pendingResultCode = it.getInt(KEY_RESULT_CODE, ScreenMirrorService.NO_RESULT_CODE)
            pendingResultData = it.getParcelable(KEY_RESULT_DATA)
            isMirroringActive = it.getBoolean(KEY_IS_MIRRORING, false)
            Log.d(TAG, "Restored state: code=$pendingResultCode, mirroring=$isMirroringActive")
        }

        setContentView(R.layout.activity_main)

        initViews()

        // Determina modalità
        val fromAuto = intent?.getBooleanExtra("from_android_auto", false) ?: false
        isRunningOnCar = fromAuto || isRunningOnAndroidAuto()
        Log.i(TAG, "Mode: ${if (isRunningOnCar) "CAR" else "PHONE"}")

        if (isRunningOnCar) {
            enterCarMode()
        } else {
            enterPhoneMode()
        }
    }

    private fun initViews() {
        videoSurface = findViewById(R.id.videoSurface)
        phoneControls = findViewById(R.id.phoneControls)
        statusText = findViewById(R.id.statusText)
        statusIcon = findViewById(R.id.statusIcon)
        startButton = findViewById(R.id.startButton)

        videoSurface.holder.addCallback(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // SALVA TUTTO - fondamentale per sopravvivere a rotazioni
        outState.putInt(KEY_RESULT_CODE, pendingResultCode)
        outState.putParcelable(KEY_RESULT_DATA, pendingResultData)
        outState.putBoolean(KEY_IS_MIRRORING, isMirroringActive)
        Log.d(TAG, "State saved: code=$pendingResultCode, mirroring=$isMirroringActive")
    }

    override fun onStart() {
        super.onStart()
        if (!isRunningOnCar && !isBound) {
            bindMirrorService()
        }
    }

    override fun onStop() {
        super.onStop()
        // NON unbind qui per mantenere il servizio vivo
    }

    private fun bindMirrorService() {
        Intent(this, ScreenMirrorService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun enterCarMode() {
        Log.i(TAG, "enterCarMode")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        phoneControls.visibility = View.GONE
        videoSurface.visibility = View.VISIBLE

        updateStatus("In attesa video...")

        // Avvia receiver con delay per permettere surface creation
        lifecycleScope.launch {
            delay(500)
            if (videoSurface.holder.surface?.isValid == true && !isReceiving) {
                startVideoReceiver(videoSurface.holder.surface)
            }
        }
    }

    private fun enterPhoneMode() {
        Log.i(TAG, "enterPhoneMode")
        phoneControls.visibility = View.VISIBLE
        videoSurface.visibility = View.GONE

        stopVideoReceiver()

        startButton.setOnClickListener {
            if (isMirroringActive) {
                stopMirroring()
            } else {
                showDisclaimerAndStart()
            }
        }

        updateButtonState()
        checkUsbStatus()
    }

    private fun updateButtonState() {
        startButton.text = if (isMirroringActive) "⏹ Stop Mirroring" else "▶ Avvia Mirroring"
    }

    // SurfaceHolder.Callback
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (isRunningOnCar && !isReceiving) {
            startVideoReceiver(holder.surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopVideoReceiver()
    }

    // Video Receiver per modalità CAR
    private fun startVideoReceiver(surface: Surface) {
        if (isReceiving) return
        isReceiving = true

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            }

            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, surface, null, 0)
                start()
            }

            usbReceiver = UsbReceiver(applicationContext) { data, isKey ->
                feedDecoder(data, isKey)
            }.also { it.start() }

            decoderThread = Thread({ drainDecoder() }, "Decoder").apply { start() }
            updateStatus("🟢 Ricezione attiva", StatusState.ACTIVE)

        } catch (e: Exception) {
            Log.e(TAG, "Decoder error", e)
            updateStatus("🔴 Errore decoder", StatusState.DISCONNECTED)
            isReceiving = false
        }
    }

    private fun feedDecoder(data: ByteArray, isKeyFrame: Boolean) {
        try {
            val codec = decoder ?: return
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) return

            codec.getInputBuffer(idx)?.apply {
                clear()
                put(data)
                val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                codec.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, flags)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Feed error", e)
        }
    }

    private fun drainDecoder() {
        val info = MediaCodec.BufferInfo()
        while (isReceiving) {
            try {
                val idx = decoder?.dequeueOutputBuffer(info, 10_000) ?: break
                if (idx >= 0) {
                    // FIX: passa true per renderizzare il frame sulla surface.
                    // Il vecchio codice passava info.presentationTimeUs (Long)
                    // come flag booleano, semanticamente sbagliato.
                    decoder?.releaseOutputBuffer(idx, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Drain error", e)
                break
            }
        }
    }

    private fun stopVideoReceiver() {
        if (!isReceiving) return
        isReceiving = false

        decoderThread?.interrupt()
        decoderThread?.join(500)
        decoderThread = null

        usbReceiver?.stop()
        usbReceiver = null

        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
    }

    // Mirroring per modalità PHONE
    private fun showDisclaimerAndStart() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Avviso di Sicurezza")
            .setMessage("DriveMirror è destinato ESCLUSIVAMENTE a uso durante la sosta.\n\n" +
                    "L'utilizzo durante la guida è illegale e pericoloso.")
            .setPositiveButton("Accetto") { _, _ -> requestProjection() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun doStartMirroring(resultCode: Int, data: Intent) {
        Log.d(TAG, "Starting mirror service: code=$resultCode")

        // Ferma eventuale receiver video
        stopVideoReceiver()

        val serviceIntent = Intent(this, ScreenMirrorService::class.java).apply {
            putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenMirrorService.EXTRA_DATA, data)
        }

        // Verifica prima di avviare
        val verifyCode = serviceIntent.getIntExtra(ScreenMirrorService.EXTRA_RESULT_CODE, ScreenMirrorService.NO_RESULT_CODE)
        val verifyData = serviceIntent.getParcelableExtra<Intent>(ScreenMirrorService.EXTRA_DATA)
        Log.d(TAG, "Service intent verify: code=$verifyCode, hasData=${verifyData != null}")

        // Avvia servizio
        ContextCompat.startForegroundService(this, serviceIntent)

        if (!isBound) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        updateStatus("🔴 Mirroring attivo", StatusState.ACTIVE)
        updateButtonState()
    }

    private fun stopMirroring() {
        Log.d(TAG, "Stopping mirror")

        mirrorService?.stopMirroring()
        stopService(Intent(this, ScreenMirrorService::class.java))

        isMirroringActive = false
        pendingResultCode = ScreenMirrorService.NO_RESULT_CODE
        pendingResultData = null

        updateStatus("Fermato", StatusState.DISCONNECTED)
        updateButtonState()
    }

    private fun checkUsbStatus() {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val connected = usb.deviceList.isNotEmpty()
        updateStatus(
            if (connected) "USB pronto" else "Nessun USB",
            if (connected) StatusState.CONNECTED else StatusState.DISCONNECTED
        )
    }

    private fun isRunningOnAndroidAuto(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val uim = getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager
            uim.currentModeType == Configuration.UI_MODE_TYPE_CAR
        } else false
    }

    private fun updateStatus(msg: String, state: StatusState = StatusState.DISCONNECTED) {
        runOnUiThread {
            statusText.text = msg
            val color = when (state) {
                StatusState.ACTIVE -> android.R.color.holo_green_dark
                StatusState.CONNECTED -> android.R.color.holo_blue_dark
                StatusState.DISCONNECTED -> android.R.color.holo_red_dark
            }
            statusText.setTextColor(ContextCompat.getColor(this, color))
        }
    }

    enum class StatusState { ACTIVE, CONNECTED, DISCONNECTED }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        stopVideoReceiver()
        super.onDestroy()
    }
}