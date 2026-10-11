package app.imagesaver

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class CameraMode(val resolution: String, val fps: List<Int>) {
    val displayName: String
        get() = "$resolution${if (fps.isEmpty()) "" else " (${fps.joinToString(", ")} fps)"}"
}

data class QualityCapability(
    val control: String,
    val min: Int,
    val max: Int,
    val step: Int,
    val default: Int,
    val value: Int,
) {
    fun accepts(value: Int): Boolean = value in min..max && (value - min) % step == 0
}

data class CameraCapabilities(
    val connected: Boolean,
    val device: String?,
    val format: String?,
    val modes: List<CameraMode>,
    val quality: QualityCapability?,
)

data class CameraSettings(val resolution: String, val fps: Int?, val quality: Int?) {
    val display: String
        get() = "$resolution, ${fps?.let { "$it fps" } ?: "camera-default fps"}" +
            (quality?.let { ", quality $it" } ?: "")
}

data class SettingsRequest(val resolution: String, val fps: Int?, val quality: Int?)

sealed class SettingsResult {
    data class Applied(val settings: CameraSettings) : SettingsResult()
    data class Failed(val message: String) : SettingsResult()
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

    fun parseCapabilities(json: String): CameraCapabilities? = try {
        val root = JSONObject(json)
        val connected = root.opt("connected") as? Boolean ?: return null
        val modesJson = root.opt("modes") as? JSONArray ?: return null
        val modes = mutableListOf<CameraMode>()
        for (i in 0 until modesJson.length()) {
            val mode = modesJson.optJSONObject(i) ?: return null
            val resolution = mode.optString("resolution", "").takeIf { RESOLUTION.matches(it) } ?: return null
            val fpsJson = mode.opt("fps") as? JSONArray ?: return null
            val fps = mutableListOf<Int>()
            for (j in 0 until fpsJson.length()) {
                val value = fpsJson.optIntStrict(j) ?: return null
                if (value <= 0) return null
                fps += value
            }
            modes += CameraMode(resolution, fps.distinct())
        }
        if (connected && modes.isEmpty()) return null
        if (!root.has("quality")) return null
        val qualityValue = root.opt("quality")
        val quality = when {
            qualityValue == JSONObject.NULL -> null
            qualityValue is JSONObject -> {
                val control = qualityValue.optString("control", "").takeIf { it.isNotBlank() } ?: return null
                val min = qualityValue.optIntStrict("min") ?: return null
                val max = qualityValue.optIntStrict("max") ?: return null
                val step = qualityValue.optIntStrict("step") ?: return null
                val default = qualityValue.optIntStrict("default") ?: return null
                val value = qualityValue.optIntStrict("value") ?: return null
                if (min > max || step <= 0 || default !in min..max || value !in min..max) return null
                QualityCapability(control, min, max, step, default, value)
            }
            else -> return null
        }
        CameraCapabilities(
            connected = connected,
            device = root.optString("device").takeIf { it.isNotBlank() && it != "null" },
            format = root.optString("format").takeIf { it.isNotBlank() && it != "null" },
            modes = modes,
            quality = quality,
        )
    } catch (e: JSONException) {
        null
    }

    fun parseSettings(json: String): CameraSettings? = try {
        val root = JSONObject(json)
        parseSettingsObject(root)
    } catch (e: JSONException) {
        null
    }

    fun parseSettingsResponse(json: String): CameraSettings? = try {
        val root = JSONObject(json)
        if (root.opt("saved") != true || root.has("error")) return null
        parseSettingsObject(root.optJSONObject("settings") ?: return null)
    } catch (e: JSONException) {
        null
    }

    private fun parseSettingsObject(root: JSONObject): CameraSettings? {
        val resolution = root.optString("resolution", "").takeIf { RESOLUTION.matches(it) } ?: return null
        if (!root.has("fps") || !root.has("quality")) return null
        val fps = if (root.opt("fps") == JSONObject.NULL) null else root.optIntStrict("fps") ?: return null
        val quality = if (root.opt("quality") == JSONObject.NULL) null else root.optIntStrict("quality") ?: return null
        if (fps != null && fps <= 0) return null
        if (quality != null && quality < 0) return null
        return CameraSettings(resolution, fps, quality)
    }

    fun settingsBody(resolution: String, fps: Int?, quality: Int?): ByteArray? {
        if (!RESOLUTION.matches(resolution) || (fps != null && fps <= 0) || (quality != null && quality < 0)) return null
        val body = JSONObject()
            .put("resolution", resolution)
            .put("fps", fps ?: JSONObject.NULL)
        if (quality != null) body.put("quality", quality)
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    fun validateSettings(
        capabilities: CameraCapabilities,
        resolution: String,
        fps: Int?,
        quality: Int?,
    ): String? {
        val mode = capabilities.modes.firstOrNull { it.resolution == resolution }
            ?: return "Select a resolution advertised by the camera"
        if (fps != null && fps !in mode.fps) return "Select a frame rate advertised for $resolution"
        if (quality != null) {
            val range = capabilities.quality ?: return "This camera does not support adjustable JPEG quality"
            if (!range.accepts(quality)) return "Quality must be ${range.min}–${range.max} in steps of ${range.step}"
        }
        return null
    }

    fun parseQuality(text: String?, capability: QualityCapability): Int? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 9 }
            ?.toIntOrNull() ?: return null
        return value.takeIf(capability::accepts)
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

    fun postSettings(host: String, request: SettingsRequest): SettingsResult {
        val url = settingsUrl(host) ?: return SettingsResult.Failed("Invalid camera host")
        val body = settingsBody(request.resolution, request.fps, request.quality)
            ?: return SettingsResult.Failed("Invalid camera settings")
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
                if (code !in 200..299) return SettingsResult.Failed(errorForStatus(code))
                val text = connection.inputStream.use { readJson(it) }
                val settings = parseSettingsResponse(text)
                if (settings == null) SettingsResult.Failed("Malformed camera settings response or change was not saved")
                else SettingsResult.Applied(settings)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            SettingsResult.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun JSONArray.optIntStrict(index: Int): Int? {
        if (index !in 0 until length()) return null
        return (opt(index) as? Number)?.toInt()?.takeIf { (opt(index) as Number).toDouble() == it.toDouble() }
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
