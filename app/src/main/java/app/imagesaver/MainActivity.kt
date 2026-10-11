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
import android.widget.LinearLayout
import android.widget.SeekBar
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
    private val controlRows = LinkedHashMap<String, ControlRow>()
    private var controlsStructure: List<List<Any>> = emptyList()
    private var ignoreSavedSettings = false
    private var displayedSettings: CameraSettings? = null
    private var apiStatusMessage: String? = null

    private lateinit var urlInput: EditText
    private lateinit var mainControls: LinearLayout
    private lateinit var moreControls: LinearLayout
    private lateinit var moreControlsToggle: Button
    private lateinit var applyControls: Button
    private lateinit var controlsStatus: TextView
    private lateinit var debugIgnoreSaved: CheckBox
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
        mainControls = findViewById(R.id.mainControls)
        moreControls = findViewById(R.id.moreControls)
        moreControlsToggle = findViewById(R.id.moreControlsToggle)
        applyControls = findViewById(R.id.applyControls)
        controlsStatus = findViewById(R.id.controlsStatus)
        debugIgnoreSaved = findViewById(R.id.debugIgnoreSaved)
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
        applyControls.setOnClickListener { onApplyControls() }
        moreControlsToggle.setOnClickListener {
            val show = moreControls.visibility != View.VISIBLE
            moreControls.visibility = if (show) View.VISIBLE else View.GONE
            moreControlsToggle.text = if (show) "More camera controls ▾" else "More camera controls ▸"
        }
        ignoreSavedSettings = prefs.getBoolean("ignoreSavedSettings", false)
        debugIgnoreSaved.isChecked = ignoreSavedSettings
        debugIgnoreSaved.setOnCheckedChangeListener { _, isChecked ->
            ignoreSavedSettings = isChecked
            prefs.edit().putBoolean("ignoreSavedSettings", isChecked).apply()
            if (isChecked) displayedSettings = null
            buildControls()
            updateCameraSettingsText()
            controlsStatus.text = if (isChecked) {
                "Saved settings are ignored by the app from now on."
            } else {
                "Saved settings are loaded at the next (re)connect."
            }
        }
        updateCameraSettingsText()
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

    private fun startStream(host: String, controlChanges: Map<String, Int>? = null) {
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
                displayedSettings = null
                apiStatusMessage = null
                buildControls()
                updateCameraSettingsText()
            }
            connectButton.text = "Stop"
            var skipRefreshForFirstAttempt = false
            if (controlChanges != null) {
                // single POST per explicit user Apply; never repeated by the retry loop below
                controlsStatus.text = "Applying ${controlChanges.size} control(s)…"
                when (val r = withContext(Dispatchers.IO) {
                    runInterruptible { CameraApi.postSettings(host, controlChanges) }
                }) {
                    is SettingsResult.Applied -> {
                        if (!ignoreSavedSettings && r.settings != null) displayedSettings = r.settings
                        controlRows.values.forEach { row ->
                            if (row.spec.name in controlChanges) {
                                row.baseline = row.selected
                                row.touched = false
                            }
                        }
                        controlsStatus.text = "Applied: " +
                            controlChanges.entries.joinToString(", ") { "${ControlLabels.title(it.key)}=${it.value}" } +
                            " – restarting stream"
                        updateCameraSettingsText()
                        skipRefreshForFirstAttempt = true
                    }
                    is SettingsResult.Failed -> controlsStatus.text = "Apply failed: ${r.message}"
                }
            }
            runWithRetry(onError = { e ->
                streamStatus.text = "Disconnected${e?.message?.let { ": $it" } ?: ""} – retrying in 1s…"
            }) {
                streamStatus.text = "Connecting…"
                if (skipRefreshForFirstAttempt) {
                    skipRefreshForFirstAttempt = false
                } else {
                    refreshCameraInfo(host)
                }
                readStream(url)
            }
        }
    }

    private suspend fun refreshCameraInfo(host: String) {
        val capabilities = withContext(Dispatchers.IO) { runInterruptible { CameraApi.fetchCapabilities(host) } }
        // Debug mode: never read (or display) the saved settings; capabilities are still needed to build controls.
        val settings = if (ignoreSavedSettings) null else {
            withContext(Dispatchers.IO) { runInterruptible { CameraApi.fetchSettings(host) } }
        }
        withContext(Dispatchers.Main.immediate) {
            if (capabilities.isSuccess) {
                capabilitiesHost = host
                if (settings != null && settings.isSuccess) displayedSettings = settings.getOrThrow()
                showCapabilities(capabilities.getOrThrow())
            } else {
                apiStatusMessage = capabilities.exceptionOrNull()?.message ?: "Unable to read camera capabilities"
                if (settings != null && settings.isSuccess) displayedSettings = settings.getOrThrow()
            }
            if (settings != null && settings.isFailure) {
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
        val structure = capabilities.controls.map { listOf(it.name, it.type, it.min, it.max, it.step) }
        if (structure != controlsStructure) {
            controlsStructure = structure
            buildControls()
        }
        apiStatusMessage = if (capabilities.connected) {
            "Capabilities loaded${capabilities.device?.let { " for $it" } ?: ""} (${capabilities.controls.size} controls)"
        } else {
            "Camera reports that it is disconnected"
        }
        updateCameraSettingsText()
    }

    private class ControlRow(val spec: CameraControl, var baseline: Int?, var selected: Int, var touched: Boolean = false) {
        val changed: Boolean get() = touched && (baseline == null || selected != baseline)
    }

    /** Rebuilds slider/menu rows from the advertised capabilities; only controls the camera reports are shown. */
    private fun buildControls() {
        mainControls.removeAllViews()
        moreControls.removeAllViews()
        controlRows.clear()
        val capabilities = currentCapabilities
        val (main, more) = ControlLabels.split(capabilities?.controls.orEmpty())
        main.forEach { addControlRow(mainControls, it) }
        more.forEach { addControlRow(moreControls, it) }
        if (capabilities == null) controlsStructure = emptyList()
        moreControlsToggle.visibility = if (more.isEmpty()) View.GONE else View.VISIBLE
        if (more.isEmpty()) moreControls.visibility = View.GONE
        applyControls.isEnabled = controlRows.isNotEmpty()
    }

    private fun addControlRow(parent: LinearLayout, spec: CameraControl) {
        val saved = if (ignoreSavedSettings) null else displayedSettings?.controls?.get(spec.name)?.takeIf { it in spec.min..spec.max }
        val known = saved ?: spec.validValue
        val row = ControlRow(spec, known, known ?: spec.min)
        controlRows[spec.name] = row
        val label = TextView(this)
        fun refreshLabel() {
            val shown = if (spec.isMenu) ControlLabels.menuLabel(spec.name, row.selected) else row.selected.toString()
            label.text = "${spec.title}: $shown" + (if (known == null && !row.touched) " (current unknown)" else "") +
                " [${spec.min}–${spec.max}${if (spec.isMenu) "" else ", step ${spec.effectiveStep}"}]"
        }
        refreshLabel()
        parent.addView(label)
        if (spec.isMenu) {
            val values = (spec.min..spec.max).toList()
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                values.map { ControlLabels.menuLabel(spec.name, it) },
            )
            spinner.setSelection(values.indexOf(row.selected).coerceAtLeast(0))
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val v = values[position]
                    if (v != row.selected) {
                        row.selected = v
                        row.touched = true
                        refreshLabel()
                    }
                }
            }
            parent.addView(spinner)
        } else {
            val step = spec.effectiveStep
            val seek = SeekBar(this)
            seek.max = (spec.max - spec.min) / step
            seek.progress = ((row.selected - spec.min) / step).coerceIn(0, seek.max)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    row.selected = spec.min + progress * step
                    row.touched = true
                    refreshLabel()
                }
            })
            parent.addView(seek)
        }
    }

    private fun updateCameraSettingsText() {
        val lines = listOfNotNull(
            apiStatusMessage,
            if (ignoreSavedSettings) {
                "Debug: saved camera settings are ignored by the app (settings not loaded or restored). The Pi server " +
                    "may still reapply its own saved settings when the stream starts."
            } else {
                displayedSettings?.let { "Active (read-only): ${it.display}" }
            },
        )
        cameraSettingsText.text = lines.joinToString("\n")
    }

    private fun onApplyControls() {
        val host = CameraApi.normalizeHost(urlInput.text.toString())
        if (host == null) {
            controlsStatus.text = "Enter a valid camera IP or hostname"
            return
        }
        val capabilities = currentCapabilities
        if (capabilities == null || capabilitiesHost != host) {
            controlsStatus.text = "Start the camera stream to load its controls before applying"
            return
        }
        val changes = controlRows.values.filter { it.changed }.associate { it.spec.name to it.selected }
        val error = CameraApi.validateControls(capabilities, changes)
        if (error != null) {
            controlsStatus.text = error
            return
        }
        startStream(host, changes)
    }

    private fun stopStream() {
        stoppingJob = streamJob?.also { it.cancel() }
        streamJob = null
        connectButton.text = "Start"
        streamStatus.text = "Stopped"
    }

    /** Blocking; runs on IO. Returns when the stream ends, throws on failure (incl. no frame ever shown). */
    private suspend fun readStream(url: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "multipart/x-mixed-replace, image/jpeg, */*")
        try {
            runInterruptible {
                if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                val contentType = conn.contentType
                var shown = false
                try {
                    conn.inputStream.use { stream ->
                        FrameStream.consume(
                            stream,
                            contentType,
                            decode = { jpeg -> BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) },
                            onDecodeFailure = { stats ->
                                if (stats.decoded == 0) runOnUiThread { streamStatus.text = stats.failureDiagnostic() }
                            },
                        ) { jpeg, bmp, first ->
                            shown = true
                            runOnUiThread {
                                latestFrame = jpeg
                                preview.setFrame(bmp)
                                if (first) streamStatus.text = "Connected"
                            }
                        }
                    }
                } catch (e: java.io.IOException) {
                    if (shown) throw e
                    throw java.io.IOException("${e.message} [Content-Type: ${contentType ?: "none"}]", e)
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
