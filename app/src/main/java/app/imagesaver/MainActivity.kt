package app.imagesaver

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
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
    private var pendingNummer: String = ""
    private var pendingStand: String = ""
    private var discoveryJob: Job? = null
    private var savedUri: Uri? = null

    private lateinit var urlInput: EditText
    private lateinit var nummerInput: EditText
    private lateinit var standInput: EditText
    private lateinit var discoverButton: Button
    private lateinit var discoverStatus: TextView
    private lateinit var openButton: Button
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
        nummerInput = findViewById(R.id.nummerInput)
        standInput = findViewById(R.id.standInput)
        discoverButton = findViewById(R.id.discoverButton)
        discoverStatus = findViewById(R.id.discoverStatus)
        openButton = findViewById(R.id.openButton)
        connectButton = findViewById(R.id.connectButton)
        streamStatus = findViewById(R.id.streamStatus)
        resultText = findViewById(R.id.resultText)
        gpsText = findViewById(R.id.gpsText)
        preview = findViewById(R.id.preview)

        val prefs = getSharedPreferences("imagesaver", MODE_PRIVATE)
        urlInput.setText(prefs.getString("url", ""))
        captureService = CaptureService(MediaStoreImageStorage(this), PrefsIdStore(this))
        discoverButton.setOnClickListener {
            if (discoveryJob?.isActive == true) cancelDiscovery() else startDiscovery()
        }
        openButton.setOnClickListener { openSavedImage() }

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
        cancelDiscovery()
        stopStream() // release the connection while in background
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startDiscovery() {
        val ip = NetworkHelper.localIpv4(this)
        val hosts = ip?.let { LanDiscovery.subnetHosts(it) }.orEmpty()
        discoverStatus.visibility = View.VISIBLE
        if (hosts.isEmpty()) {
            discoverStatus.text = "Discovery failed: no local IPv4 network (connect to Wi-Fi/LAN)"
            return
        }
        val prefix = ip!!.substringBeforeLast('.')
        discoverButton.text = "Cancel"
        discoverStatus.text = "Scanning $prefix.0/24 …"
        discoveryJob = scope.launch {
            try {
                val found = LanDiscovery.scan(hosts, onProgress = { done, total ->
                    runOnUiThread { discoverStatus.text = "Scanning $prefix.0/24: $done/$total" }
                }) { LanDiscovery.probeHost(it) }
                if (found.isEmpty()) {
                    discoverStatus.text = "No HTTP devices found on $prefix.0/24 (port 80)"
                } else {
                    discoverStatus.text = "Found ${found.size} device(s)"
                    showCandidates(found)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                discoverStatus.text = "Discovery cancelled"
                throw e
            } catch (e: Exception) {
                discoverStatus.text = "Discovery failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                discoverButton.text = "Discover"
            }
        }
    }

    private fun cancelDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    private fun showCandidates(found: List<DiscoveryCandidate>) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Select camera")
            .setItems(found.map { it.label }.toTypedArray()) { _, i -> urlInput.setText(found[i].url) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSavedImage() {
        val uri = savedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            resultText.text = "${resultText.text}\nNo app installed that can open this image"
        }
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
        pendingNummer = nummerInput.text.toString()
        pendingStand = standInput.text.toString()
        savedUri = null
        openButton.visibility = View.GONE
        if (FilenameBuilder.normalizeNummer(pendingNummer) == null) {
            resultText.text = "Nummer must be a decimal number (e.g. 12 or 12.5)"
            gpsText.text = ""
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        requestLocationAndCapture()
    }

    private fun requestLocationAndCapture() {
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
        if (requestCode == REQ_STORAGE) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestLocationAndCapture()
            else resultText.text = "Save failed: storage permission denied"
        }
        if (requestCode == REQ_LOCATION) doCapture(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
    }

    private fun doCapture(locationAllowed: Boolean) {
        val frame = pendingFrame
        val nummer = pendingNummer
        val stand = pendingStand
        resultText.text = "Saving…"
        gpsText.text = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                captureService.capture(frame, nummer, stand) {
                    if (locationAllowed) LocationHelper.current(this@MainActivity) else null
                }
            }
            when (result) {
                is CaptureResult.Saved -> {
                    resultText.text = "Saved: ${result.fileName} (${formatSize(result.sizeBytes)})"
                    savedUri = result.uri?.let { Uri.parse(it) }
                    openButton.visibility = if (savedUri != null) View.VISIBLE else View.GONE
                }
                is CaptureResult.Failed -> {
                    resultText.text = result.message
                    savedUri = null
                    openButton.visibility = View.GONE
                }
            }
            gpsText.text = if (result.gpsAvailable) "" else
                "GPS coordinates unavailable${if (locationAllowed) "" else " (location permission denied)"} – using ${FilenameBuilder.NO_GPS}"
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f KB (%d bytes)", bytes / 1024.0, bytes)

    private companion object {
        const val REQ_LOCATION = 1
        const val REQ_STORAGE = 2
    }
}
