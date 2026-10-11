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
import android.widget.CheckBox
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
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
    private var pendingIncludeLocation = true
    private var discoveryJob: Job? = null
    private var savedUri: Uri? = null
    private var currentCapabilities: CameraCapabilities? = null
    private var capabilitiesHost: String? = null
    private var availableModes: List<CameraMode> = emptyList()
    private var fpsOptions: List<Int?> = emptyList()
    private var displayedSettings: CameraSettings? = null
    private var apiStatusMessage: String? = null

    private lateinit var urlInput: EditText
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var qualityInput: EditText
    private lateinit var applySettings: Button
    private lateinit var cameraSettingsText: TextView
    private lateinit var nummerInput: EditText
    private lateinit var standInput: EditText
    private lateinit var includeLocation: CheckBox
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
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        qualityInput = findViewById(R.id.qualityInput)
        applySettings = findViewById(R.id.applySettings)
        cameraSettingsText = findViewById(R.id.cameraSettingsText)
        nummerInput = findViewById(R.id.nummerInput)
        standInput = findViewById(R.id.standInput)
        includeLocation = findViewById(R.id.includeLocation)
        discoverButton = findViewById(R.id.discoverButton)
        discoverStatus = findViewById(R.id.discoverStatus)
        openButton = findViewById(R.id.openButton)
        connectButton = findViewById(R.id.connectButton)
        streamStatus = findViewById(R.id.streamStatus)
        resultText = findViewById(R.id.resultText)
        gpsText = findViewById(R.id.gpsText)
        preview = findViewById(R.id.preview)

        val prefs = getSharedPreferences("imagesaver", MODE_PRIVATE)
        urlInput.setText(CameraApi.normalizeHost(prefs.getString("url", "")).orEmpty())
        qualityInput.visibility = View.GONE
        resolutionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val previousFps = fpsOptions.getOrNull(fpsSpinner.selectedItemPosition)
                updateFrameRateOptions(previousFps)
            }
        }
        applySettings.setOnClickListener { onApplySettings() }
        includeLocation.isChecked = prefs.getBoolean("includeLocation", true)
        includeLocation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("includeLocation", isChecked).apply()
        }
        captureService = CaptureService(MediaStoreImageStorage(this), PrefsIdStore(this))
        discoverButton.setOnClickListener {
            if (discoveryJob?.isActive == true) cancelDiscovery() else startDiscovery()
        }
        openButton.setOnClickListener { openSavedImage() }

        connectButton.setOnClickListener {
            if (streamJob?.isActive == true) {
                stopStream()
            } else {
                val host = CameraApi.normalizeHost(urlInput.text.toString())
                if (host == null) {
                    streamStatus.text = "Enter a valid camera IP or hostname"
                } else {
                    urlInput.setText(host)
                    prefs.edit().putString("url", host).apply()
                    startStream(host)
                }
            }
        }
        findViewById<Button>(R.id.zoomIn).setOnClickListener { preview.zoomIn() }
        findViewById<Button>(R.id.zoomOut).setOnClickListener { preview.zoomOut() }
        findViewById<Button>(R.id.rotateLeft).setOnClickListener { preview.rotateLeft() }
        findViewById<Button>(R.id.rotateRight).setOnClickListener { preview.rotateRight() }
        findViewById<Button>(R.id.zoomReset).setOnClickListener { preview.resetZoom() }
        findViewById<Button>(R.id.captureButton).setOnClickListener { onCapturePressed() }
        preview.onDoubleTapCapture = { onCapturePressed() }
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
        discoverStatus.visibility = View.VISIBLE
        val iface = try { NetworkHelper.hotspotInterfaces(this).firstOrNull() } catch (e: SecurityException) { null }
        val ip = iface?.ipv4
        val hosts = ip?.let { LanDiscovery.subnetHosts(it) }.orEmpty()
        if (hosts.isEmpty()) {
            discoverStatus.text = "Hotspot not found. Turn on the Android Wi-Fi hotspot (or USB tethering) and connect the camera " +
                "to it. Android does not let apps list hotspot clients, and some devices hide or share the hotspot " +
                "interface; if this keeps failing, enter the camera URL manually."
            return
        }
        val prefix = ip!!.substringBeforeLast('.')
        discoverButton.text = "Cancel"
        discoverStatus.text = "Scanning hotspot ${iface!!.name} $prefix.0/24 …"
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
            .setItems(found.map { it.label }.toTypedArray()) { _, i -> urlInput.setText(found[i].host) }
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

    private fun startStream(host: String, settingsRequest: SettingsRequest? = null) {
        val url = CameraApi.streamUrl(host)
        val previous = streamJob ?: stoppingJob
        streamJob = scope.launch {
            previous?.cancelAndJoin() // never overlap attempts when URL changes/restarts
            if (url == null) {
                streamStatus.text = "Enter a valid camera IP or hostname"
                connectButton.text = "Start"
                return@launch
            }
            if (capabilitiesHost != host) {
                currentCapabilities = null
                capabilitiesHost = null
                availableModes = emptyList()
                displayedSettings = null
                resolutionSpinner.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    emptyList<String>(),
                )
                fpsSpinner.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("Camera default"),
                )
                qualityInput.visibility = View.GONE
                updateCameraSettingsText()
            }
            connectButton.text = "Stop"
            var usePostSettingsForFirstAttempt = false
            if (settingsRequest != null) {
                // single POST per user action; never repeated by the retry loop below
                cameraSettingsText.text = "Applying settings…"
                when (val r = withContext(Dispatchers.IO) {
                    runInterruptible { CameraApi.postSettings(host, settingsRequest) }
                }) {
                    is SettingsResult.Applied -> {
                        showSettings(r.settings)
                        apiStatusMessage = null
                        updateCameraSettingsText()
                        usePostSettingsForFirstAttempt = true
                    }
                    is SettingsResult.Failed -> {
                        apiStatusMessage = r.message
                        updateCameraSettingsText()
                    }
                }
            }
            runWithRetry(onError = { e ->
                streamStatus.text = "Disconnected${e?.message?.let { ": $it" } ?: ""} – retrying in 1s…"
            }) {
                streamStatus.text = "Connecting…"
                if (usePostSettingsForFirstAttempt) {
                    usePostSettingsForFirstAttempt = false
                } else {
                    refreshCameraInfo(host)
                }
                readStream(url)
            }
        }
    }

    private suspend fun refreshCameraInfo(host: String) {
        val capabilities = withContext(Dispatchers.IO) { runInterruptible { CameraApi.fetchCapabilities(host) } }
        val settings = withContext(Dispatchers.IO) { runInterruptible { CameraApi.fetchSettings(host) } }
        withContext(Dispatchers.Main.immediate) {
            if (capabilities.isSuccess) {
                capabilitiesHost = host
                showCapabilities(capabilities.getOrThrow())
            } else {
                apiStatusMessage = capabilities.exceptionOrNull()?.message ?: "Unable to read camera capabilities"
            }
            if (settings.isSuccess) {
                showSettings(settings.getOrThrow())
            } else {
                val settingsError = settings.exceptionOrNull()?.message ?: "Unable to read camera settings"
                apiStatusMessage = listOfNotNull(apiStatusMessage, settingsError).joinToString("\n")
            }
            if (capabilities.getOrNull()?.connected == false) {
                apiStatusMessage = listOfNotNull("Camera reports that it is disconnected", apiStatusMessage).joinToString("\n")
            }
            updateCameraSettingsText()
        }
    }

    private fun showCapabilities(capabilities: CameraCapabilities) {
        currentCapabilities = capabilities
        availableModes = capabilities.modes
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            availableModes.map { it.displayName },
        )
        resolutionSpinner.isEnabled = availableModes.isNotEmpty()
        val quality = capabilities.quality
        qualityInput.visibility = if (quality == null) View.GONE else View.VISIBLE
        qualityInput.hint = quality?.let { "Quality ${it.min}–${it.max} step ${it.step}" } ?: "Unsupported"
        if (quality != null && qualityInput.text.isNullOrBlank()) {
            qualityInput.setText(quality.value.toString())
        }
        apiStatusMessage = if (capabilities.connected) {
            "Capabilities loaded${capabilities.device?.let { " for $it" } ?: ""}"
        } else {
            "Camera reports that it is disconnected"
        }
        updateCameraSettingsText()
    }

    private fun showSettings(s: CameraSettings) {
        displayedSettings = s
        val modeIndex = availableModes.indexOfFirst { it.resolution == s.resolution }
        if (modeIndex >= 0) {
            resolutionSpinner.setSelection(modeIndex)
            updateFrameRateOptions(s.fps)
        }
        if (s.quality != null && currentCapabilities?.quality != null) {
            qualityInput.setText(s.quality.toString())
        }
        updateCameraSettingsText()
    }

    private fun updateFrameRateOptions(preferred: Int? = null) {
        val mode = availableModes.getOrNull(resolutionSpinner.selectedItemPosition)
        fpsOptions = listOf(null) + mode?.fps.orEmpty().map { it as Int? }
        fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            fpsOptions.map { it?.let { value -> "$value fps" } ?: "Camera default" },
        )
        fpsSpinner.isEnabled = fpsOptions.size > 1
        fpsSpinner.setSelection(fpsOptions.indexOf(preferred).takeIf { it >= 0 } ?: 0)
    }

    private fun updateCameraSettingsText() {
        val lines = listOfNotNull(
            apiStatusMessage,
            displayedSettings?.let { "Settings: ${it.display}" },
        )
        cameraSettingsText.text = lines.joinToString("\n")
    }

    private fun onApplySettings() {
        val host = CameraApi.normalizeHost(urlInput.text.toString())
        if (host == null) {
            cameraSettingsText.text = "Enter a valid camera IP or hostname"
            return
        }
        val capabilities = currentCapabilities
        if (capabilities == null || capabilitiesHost != host) {
            cameraSettingsText.text = "Start the camera stream to load its capabilities before applying settings"
            return
        }
        val mode = availableModes.getOrNull(resolutionSpinner.selectedItemPosition)
        if (mode == null) {
            cameraSettingsText.text = "No camera resolutions are available"
            return
        }
        val fps = fpsOptions.getOrNull(fpsSpinner.selectedItemPosition)
        val qualityRange = capabilities.quality
        val quality = if (qualityRange != null) {
            CameraApi.parseQuality(qualityInput.text.toString(), qualityRange)
                ?: run {
                    cameraSettingsText.text =
                        "Quality must be ${qualityRange.min}–${qualityRange.max} in steps of ${qualityRange.step}"
                    return
                }
        } else {
            null
        }
        val validationError = CameraApi.validateSettings(capabilities, mode.resolution, fps, quality)
        if (validationError != null) {
            cameraSettingsText.text = validationError
            return
        }
        startStream(host, SettingsRequest(mode.resolution, fps, quality))
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
        pendingIncludeLocation = includeLocation.isChecked
        savedUri = null
        openButton.visibility = View.GONE
        if (FilenameBuilder.normalizeNummer(pendingNummer) == null) {
            resultText.text = "Nummer must be a decimal number (e.g. 12 or 12.5)"
            gpsText.text = ""
            return
        }
        if (FilenameBuilder.normalizeStand(pendingStand) == null) {
            resultText.text = "Stand must be a decimal number (e.g. 12 or 12.5)"
            gpsText.text = ""
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        continueCaptureAfterStoragePermission()
    }

    private fun continueCaptureAfterStoragePermission() {
        if (pendingIncludeLocation) requestLocationAndCapture() else doCapture(false)
    }

    private fun requestLocationAndCapture() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            doCapture(locationLookupEnabled = true, locationPermissionGranted = true)
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
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) continueCaptureAfterStoragePermission()
            else resultText.text = "Save failed: storage permission denied"
        }
        if (requestCode == REQ_LOCATION) {
            doCapture(locationLookupEnabled = true, locationPermissionGranted = grantResults.any { it == PackageManager.PERMISSION_GRANTED })
        }
    }

    private fun doCapture(locationLookupEnabled: Boolean, locationPermissionGranted: Boolean = false) {
        val frame = pendingFrame
        val nummer = pendingNummer
        val stand = pendingStand
        resultText.text = "Saving…"
        gpsText.text = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                captureService.capture(
                    frame,
                    nummer,
                    stand,
                    includeLocation = locationPermissionGranted,
                ) { if (locationPermissionGranted) LocationHelper.current(this@MainActivity) else null }
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
            gpsText.text = when {
                locationLookupEnabled && result.gpsAvailable -> ""
                !locationLookupEnabled -> "Location lookup disabled – using ${FilenameBuilder.NO_GPS}"
                else -> "GPS coordinates unavailable (permission denied, no fix, or timeout) – using ${FilenameBuilder.NO_GPS}"
            }
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else String.format(java.util.Locale.ROOT, "%.1f KB (%d bytes)", bytes / 1024.0, bytes)

    private companion object {
        const val REQ_LOCATION = 1
        const val REQ_STORAGE = 2
    }
}
