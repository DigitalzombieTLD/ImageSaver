package app.imagesaver

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

/** Supported camera resolutions (id as used by the camera's `/camera/settings` API). */
enum class CameraResolution(val id: Int, val label: String, val width: Int, val height: Int) {
    QVGA(6, "QVGA", 320, 240),
    VGA(2, "VGA", 640, 480),
    SVGA(1, "SVGA", 800, 600),
    XGA(3, "XGA", 1024, 768),
    SXGA(4, "SXGA", 1280, 1024),
    UXGA(5, "UXGA", 1600, 1200);

    val displayName: String get() = "$label ${width}x$height"

    companion object {
        fun fromId(id: Int): CameraResolution? = values().firstOrNull { it.id == id }
    }
}

/** Currently active camera settings, as reported by the camera. */
data class CameraSettings(val resolutionId: Int, val name: String, val width: Int, val height: Int, val quality: Int) {
    val display: String get() = "$name ${width}x$height, quality $quality"
}

sealed class SettingsResult {
    data class Applied(val settings: CameraSettings) : SettingsResult()
    data class Failed(val message: String) : SettingsResult()
}

/** Pure helpers: host normalization, endpoint construction, request body and JSON parsing. */
object CameraApi {
    const val MIN_QUALITY = 10
    const val MAX_QUALITY = 40
    const val MAX_BODY_BYTES = 40

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val HOST_CHARS = Regex("^[A-Za-z0-9._-]+$")
    private val PORT = Regex("^[0-9]{1,5}$")

    /**
     * Extracts the host (optionally with :port) from user input or an older saved URL, dropping scheme,
     * credentials, path, query and fragment. Returns null when no valid host remains.
     */
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
    fun statusUrl(host: String): String? = normalizeHost(host)?.let { "http://$it/status" }
    fun settingsUrl(host: String): String? = normalizeHost(host)?.let { "http://$it/camera/settings" }

    fun parseQuality(text: String?): Int? {
        val t = text?.trim() ?: return null
        if (t.isEmpty() || !t.all { it in '0'..'9' } || t.length > 3) return null
        return t.toInt().takeIf { it in MIN_QUALITY..MAX_QUALITY }
    }

    /** Exact form body `resolution=<id>&quality=<n>`; null if invalid or longer than [MAX_BODY_BYTES]. */
    fun settingsBody(resolutionId: Int, quality: Int): ByteArray? {
        if (CameraResolution.fromId(resolutionId) == null || quality !in MIN_QUALITY..MAX_QUALITY) return null
        val body = "resolution=$resolutionId&quality=$quality".toByteArray(Charsets.US_ASCII)
        return body.takeIf { it.size <= MAX_BODY_BYTES }
    }

    /** Parses `/status` JSON, reading only resolution and quality fields. Null if malformed/incomplete. */
    fun parseStatus(json: String): CameraSettings? = parse(json, "resolution_id", "resolution_name", "resolution_width", "resolution_height")

    /** Parses a successful `/camera/settings` response. Null if malformed/incomplete or it reports failure. */
    fun parseSettingsResponse(json: String): CameraSettings? {
        val o = try { JSONObject(json) } catch (e: JSONException) { return null }
        if (o.has("error")) return null
        for (k in listOf("ok", "success", "saved", "applied")) {
            if (o.has(k) && o.opt(k) == false) return null
        }
        return parse(json, "resolution_id", "name", "width", "height")
    }

    private fun parse(json: String, idKey: String, nameKey: String, wKey: String, hKey: String): CameraSettings? = try {
        val o = JSONObject(json)
        val id = o.getInt(idKey)
        val q = o.getInt("jpeg_quality")
        val name = o.getString(nameKey)
        val w = o.getInt(wKey)
        val h = o.getInt(hKey)
        if (name.isEmpty() || w <= 0 || h <= 0) null else CameraSettings(id, name, w, h, q)
    } catch (e: JSONException) {
        null
    }

    fun errorForStatus(code: Int): String = when (code) {
        400 -> "Camera rejected the settings (HTTP 400: invalid resolution or quality)"
        503 -> "Camera busy or unavailable (HTTP 503)"
        500 -> "Camera error while applying settings (HTTP 500)"
        else -> "Unexpected camera response (HTTP $code)"
    }

    /** Blocking. Reads `/status` once; null on any failure. */
    fun fetchStatus(host: String): CameraSettings? {
        val url = statusUrl(host) ?: return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            try {
                if (c.responseCode != 200) null else parseStatus(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            null
        }
    }

    /** Blocking. POSTs the settings once. */
    fun postSettings(host: String, resolutionId: Int, quality: Int): SettingsResult {
        val url = settingsUrl(host) ?: return SettingsResult.Failed("Invalid camera host")
        val body = settingsBody(resolutionId, quality) ?: return SettingsResult.Failed("Invalid resolution or quality")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            try {
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                if (code != 200) return SettingsResult.Failed(errorForStatus(code))
                val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val s = parseSettingsResponse(text)
                if (s == null) SettingsResult.Failed("Camera response was malformed or reported failure") else SettingsResult.Applied(s)
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            SettingsResult.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
