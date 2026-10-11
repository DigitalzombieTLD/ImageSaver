package app.imagesaver

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One adjustable V4L2 control advertised by `/api/capabilities`. [default] may be a sentinel outside min..max. */
data class CameraControl(
    val name: String,
    val type: String,
    val min: Int,
    val max: Int,
    val step: Int,
    val default: Int?,
    val value: Int?,
) {
    val isMenu: Boolean get() = type == TYPE_MENU
    val effectiveStep: Int get() = if (isMenu || step <= 0) 1 else step

    /** Menu controls offer every index in min..max; integer controls honour step. */
    fun accepts(v: Int): Boolean = v in min..max && (isMenu || (v - min) % effectiveStep == 0)

    /** Valid current value if the camera reported one inside min..max, else null. */
    val validValue: Int? get() = value?.takeIf { it in min..max }

    val title: String get() = ControlLabels.title(name)

    companion object {
        const val TYPE_INT = "int"
        const val TYPE_MENU = "menu"
    }
}

data class CameraCapabilities(
    val connected: Boolean,
    val device: String?,
    val format: String?,
    val controls: List<CameraControl>,
)

/** Saved settings from GET /api/settings. Resolution/FPS/quality are read-only status; the app never sends them. */
data class CameraSettings(
    val resolution: String?,
    val fps: Int?,
    val quality: Int?,
    val controls: Map<String, Int>,
) {
    val display: String
        get() = listOfNotNull(
            resolution,
            fps?.let { "$it fps" },
            quality?.let { "quality $it" },
        ).joinToString(", ").ifEmpty { "no mode information" }
}

sealed class SettingsResult {
    data class Applied(val settings: CameraSettings?) : SettingsResult()
    data class Failed(val message: String) : SettingsResult()
}

object ControlLabels {
    val MAIN = listOf("brightness", "contrast", "saturation", "gamma", "gain", "sharpness", "focus_absolute")
    private val MORE_ORDER = listOf(
        "hue", "power_line_frequency", "white_balance_temperature", "backlight_compensation",
        "auto_exposure", "exposure_time_absolute",
    )

    fun title(name: String): String = when (name) {
        "focus_absolute" -> "Focus"
        else -> name.split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    /** Friendly label for a menu index when known; the raw number is always kept in the text. */
    fun menuLabel(name: String, value: Int): String {
        val friendly = when (name) {
            "power_line_frequency" -> listOf("Disabled", "50 Hz", "60 Hz", "Auto").getOrNull(value)
            "auto_exposure" -> listOf("Auto", "Manual", "Shutter priority", "Aperture priority").getOrNull(value)
            else -> null
        }
        return if (friendly != null) "$value – $friendly" else "$value"
    }

    /** Splits into (main-page controls in fixed order, remaining controls for the submenu). */
    fun split(controls: List<CameraControl>): Pair<List<CameraControl>, List<CameraControl>> {
        val main = MAIN.mapNotNull { n -> controls.firstOrNull { it.name == n } }
        val rest = controls.filter { it.name !in MAIN }
            .sortedWith(compareBy({ MORE_ORDER.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))
        return main to rest
    }
}

/** Helpers for the Mobile Webcam API and blocking HTTP calls, always called off the UI thread. */
object CameraApi {
    private const val MAX_JSON_BYTES = 1_048_576
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val HOST_CHARS = Regex("^[A-Za-z0-9._-]+$")
    private val PORT = Regex("^[0-9]{1,5}$")
    private val RESOLUTION = Regex("^[1-9][0-9]*x[1-9][0-9]*$")

    fun normalizeHost(input: String?): String? {
        var s = input?.trim() ?: return null
        s = s.replace(SCHEME, "")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 2) return null
            val addr = s.substring(1, end)
            if (!addr.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
            val rest = s.substring(end + 1)
            if (rest.isNotEmpty() && !(rest.startsWith(":") && PORT.matches(rest.substring(1)))) return null
            return s
        }
        val host = s.substringBefore(':')
        if (host.isEmpty() || !HOST_CHARS.matches(host) || host.startsWith(".") || host.startsWith("-")) return null
        if (s.contains(':')) {
            val port = s.substringAfter(':')
            if (!PORT.matches(port) || port.toInt() !in 1..65535) return null
        }
        return s
    }

    fun streamUrl(host: String): String? = normalizeHost(host)?.let { "http://$it/stream" }
    fun capabilitiesUrl(host: String): String? = normalizeHost(host)?.let { "http://$it/api/capabilities" }
    fun settingsUrl(host: String): String? = normalizeHost(host)?.let { "http://$it/api/settings" }

    /** Lenient: ignores resolutions/FPS/quality; controls with unusable metadata are skipped, never fatal. */
    fun parseCapabilities(json: String): CameraCapabilities? {
        return try {
            val root = JSONObject(json)
            val connected = when (val c = root.opt("connected")) {
                is Boolean -> c
                null, JSONObject.NULL -> true
                else -> return null
            }
            val controls = LinkedHashMap<String, CameraControl>()
            // "controls" wins; "focus_controls" may repeat the same names
            for (key in listOf("controls", "focus_controls")) {
                for (control in parseControlGroup(root.opt(key))) controls.putIfAbsent(control.name, control)
            }
            CameraCapabilities(
                connected = connected,
                device = root.optString("device").takeIf { it.isNotBlank() && it != "null" },
                format = root.optString("format").takeIf { it.isNotBlank() && it != "null" },
                controls = controls.values.toList(),
            )
        } catch (e: JSONException) {
            null
        }
    }

    private fun parseControlGroup(group: Any?): List<CameraControl> {
        val result = mutableListOf<CameraControl>()
        when (group) {
            is JSONObject -> group.keys().forEach { name ->
                parseControl(name, group.opt(name) as? JSONObject)?.let(result::add)
            }
            is JSONArray -> for (i in 0 until group.length()) {
                val o = group.optJSONObject(i) ?: continue
                parseControl(o.optString("name", ""), o)?.let(result::add)
            }
        }
        return result
    }

    private fun parseControl(name: String, o: JSONObject?): CameraControl? {
        if (o == null || name.isBlank()) return null
        val type = o.optString("type", CameraControl.TYPE_INT).lowercase()
        if (type != CameraControl.TYPE_INT && type != CameraControl.TYPE_MENU) return null
        val min = o.optIntStrict("min") ?: return null
        val max = o.optIntStrict("max") ?: return null
        if (min > max) return null
        val step = o.optIntStrict("step") ?: 1
        return CameraControl(name, type, min, max, step, o.optIntStrict("default"), o.optIntStrict("value"))
    }

    fun parseSettings(json: String): CameraSettings? {
        return try {
            val root = JSONObject(json)
            parseSettingsObject(root.optJSONObject("settings") ?: root)
        } catch (e: JSONException) {
            null
        }
    }

    fun parseSettingsResponse(json: String): CameraSettings? {
        return try {
            val root = JSONObject(json)
            if (root.opt("saved") == false || root.has("error")) return null
            parseSettingsObject(root.optJSONObject("settings") ?: root)
        } catch (e: JSONException) {
            null
        }
    }

    private fun parseSettingsObject(root: JSONObject): CameraSettings {
        val controls = LinkedHashMap<String, Int>()
        root.optJSONObject("controls")?.let { c ->
            c.keys().forEach { k -> c.optIntStrict(k)?.let { controls[k] = it } }
        }
        return CameraSettings(
            resolution = root.optString("resolution", "").takeIf { RESOLUTION.matches(it) },
            fps = root.optIntStrict("fps")?.takeIf { it > 0 },
            quality = root.optIntStrict("quality")?.takeIf { it >= 0 },
            controls = controls,
        )
    }

    /** POST body: only `controls` (name -> integer); resolution and FPS are never sent. */
    fun settingsBody(changes: Map<String, Int>): ByteArray? {
        if (changes.isEmpty()) return null
        val controls = JSONObject()
        changes.forEach { (k, v) -> controls.put(k, v) }
        return JSONObject().put("controls", controls).toString().toByteArray(Charsets.UTF_8)
    }

    /** Returns an error message if any change is for an unadvertised control or violates min/max/step. */
    fun validateControls(capabilities: CameraCapabilities, changes: Map<String, Int>): String? {
        if (changes.isEmpty()) return "No controls changed"
        for ((name, value) in changes) {
            val control = capabilities.controls.firstOrNull { it.name == name }
                ?: return "Camera does not support $name"
            if (!control.accepts(value)) {
                return "${control.title} must be ${control.min}–${control.max}" +
                    (if (control.isMenu) "" else " in steps of ${control.effectiveStep}")
            }
        }
        return null
    }

    fun errorForStatus(code: Int): String = when (code) {
        400 -> "Camera rejected the request (HTTP 400: invalid request)"
        503 -> "Camera unavailable (HTTP 503)"
        500 -> "Camera error (HTTP 500)"
        else -> "Unexpected camera response (HTTP $code)"
    }

    fun fetchCapabilities(host: String): Result<CameraCapabilities> =
        get(capabilitiesUrl(host), "capabilities") { parseCapabilities(it) }

    fun fetchSettings(host: String): Result<CameraSettings> =
        get(settingsUrl(host), "settings") { parseSettings(it) }

    private fun <T> get(url: String?, label: String, parse: (String) -> T?): Result<T> {
        if (url == null) return Result.failure(IOException("Invalid camera host"))
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            try {
                val code = connection.responseCode
                if (code !in 200..299) return Result.failure(IOException(errorForStatus(code)))
                val value = connection.inputStream.use { parse(readJson(it)) }
                    ?: return Result.failure(IOException("Malformed camera $label response"))
                Result.success(value)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Result.failure(IOException("Camera $label request failed: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    fun postSettings(host: String, changes: Map<String, Int>): SettingsResult {
        val url = settingsUrl(host) ?: return SettingsResult.Failed("Invalid camera host")
        val body = settingsBody(changes) ?: return SettingsResult.Failed("No controls to apply")
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json")
            try {
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = try { connection.errorStream?.use { readJson(it) } } catch (e: IOException) { null }
                    val reason = detail?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                        ?.takeIf { it.isNotBlank() }
                    return SettingsResult.Failed(errorForStatus(code) + (reason?.let { ": $it" } ?: ""))
                }
                val text = connection.inputStream.use { readJson(it) }
                if (text.isBlank()) return SettingsResult.Applied(null)
                val settings = parseSettingsResponse(text)
                    ?: return SettingsResult.Failed("Camera did not confirm the change: ${text.take(200)}")
                SettingsResult.Applied(settings)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            SettingsResult.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun JSONObject.optIntStrict(key: String): Int? =
        (opt(key) as? Number)?.toInt()?.takeIf { (opt(key) as Number).toDouble() == it.toDouble() }

    private fun readJson(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_JSON_BYTES) throw IOException("Camera response is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }
}
