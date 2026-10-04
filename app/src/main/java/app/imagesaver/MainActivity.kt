package app.imagesaver

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var streamJob: Job? = null
    private var stoppingJob: Job? = null
    private var latestFrame: ByteArray? = null
    private var pendingFrame: ByteArray? = null
    private var pendingText: String = ""

    private lateinit var urlInput: EditText
    private lateinit var labelInput: EditText
    private lateinit var connectButton: Button
    private lateinit var streamStatus: TextView
    private lateinit var resultText: TextView
    private lateinit var gpsText: TextView
    private lateinit var preview: ZoomableImageView
    private lateinit var captureService: CaptureService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlInput = findViewById(R.id.urlInput)
        labelInput = findViewById(R.id.labelInput)
        connectButton = findViewById(R.id.connectButton)
        streamStatus = findViewById(R.id.streamStatus)
        resultText = findViewById(R.id.resultText)
        gpsText = findViewById(R.id.gpsText)
        preview = findViewById(R.id.preview)

        val prefs = getSharedPreferences("imagesaver", MODE_PRIVATE)
        urlInput.setText(prefs.getString("url", ""))
        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: filesDir
        captureService = CaptureService(dir, PrefsIdStore(this))

        connectButton.setOnClickListener {
            if (streamJob?.isActive == true) {
                stopStream()
            } else {
                prefs.edit().putString("url", urlInput.text.toString().trim()).apply()
                startStream(urlInput.text.toString().trim())
            }
        }
        findViewById<Button>(R.id.zoomIn).setOnClickListener { preview.zoomIn() }
        findViewById<Button>(R.id.zoomOut).setOnClickListener { preview.zoomOut() }
        findViewById<Button>(R.id.zoomReset).setOnClickListener { preview.resetZoom() }
        findViewById<Button>(R.id.captureButton).setOnClickListener { onCapturePressed() }
    }

    override fun onStop() {
        super.onStop()
        stopStream() // release the connection while in background
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startStream(url: String) {
        val previous = streamJob ?: stoppingJob
        streamJob = scope.launch {
            previous?.cancelAndJoin() // never overlap attempts when URL changes/restarts
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                streamStatus.text = "Enter a valid http:// or https:// URL"
                connectButton.text = "Start"
                return@launch
            }
            connectButton.text = "Stop"
            runWithRetry(onError = { e ->
                streamStatus.text = "Disconnected${e?.message?.let { ": $it" } ?: ""} – retrying in 1s…"
            }) {
                streamStatus.text = "Connecting…"
                withContext(Dispatchers.IO) { readStream(url) }
            }
        }
    }

    private fun stopStream() {
        stoppingJob = streamJob?.also { it.cancel() }
        streamJob = null
        connectButton.text = "Start"
        streamStatus.text = "Stopped"
    }

    /** Blocking; runs on IO. Returns when the stream ends, throws on failure. */
    private suspend fun readStream(url: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        try {
            runInterruptible {
                if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                var first = true
                conn.inputStream.use { stream ->
                    MjpegParser.readFrames(stream) { jpeg ->
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@readFrames
                        val announce = first
                        first = false
                        runOnUiThread {
                            latestFrame = jpeg
                            preview.setFrame(bmp)
                            if (announce) streamStatus.text = "Connected"
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun onCapturePressed() {
        pendingFrame = latestFrame // frame displayed at button press
        pendingText = labelInput.text.toString()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            doCapture(true)
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) doCapture(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
    }

    private fun doCapture(locationAllowed: Boolean) {
        val frame = pendingFrame
        val text = pendingText
        resultText.text = "Saving…"
        gpsText.text = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                captureService.capture(frame, text) {
                    if (locationAllowed) LocationHelper.current(this@MainActivity) else null
                }
            }
            when (result) {
                is CaptureResult.Saved ->
                    resultText.text = "Saved: ${result.fileName} (${formatSize(result.sizeBytes)})"
                is CaptureResult.Failed -> resultText.text = result.message
            }
            gpsText.text = if (result.gpsAvailable) "" else
                "GPS coordinates unavailable${if (locationAllowed) "" else " (location permission denied)"} – using ${FilenameBuilder.NO_GPS}"
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f KB (%d bytes)", bytes / 1024.0, bytes)

    private companion object {
        const val REQ_LOCATION = 1
    }
}
