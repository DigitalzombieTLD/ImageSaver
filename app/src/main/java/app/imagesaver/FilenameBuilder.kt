package app.imagesaver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GpsCoordinates(val latitude: Double, val longitude: Double)

/** Builds safe image filenames: ID_date_time_lat_lon|no_gps[_text].jpg */
object FilenameBuilder {
    const val NO_GPS = "_no_gps"
    const val MAX_TEXT_LENGTH = 50

    fun sanitize(text: String): String =
        text.trim()
            .replace(Regex("[^A-Za-z0-9.-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-', '.')
            .take(MAX_TEXT_LENGTH)
            .trim('-', '.')

    fun formatTime(time: Date, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).apply { timeZone = zone }.format(time)

    fun formatCoordinates(coords: GpsCoordinates?): String {
        if (coords == null || !coords.latitude.isFinite() || !coords.longitude.isFinite()) return NO_GPS
        return String.format(Locale.ROOT, "%.6f_%.6f", coords.latitude, coords.longitude)
    }

    fun build(
        id: Long,
        time: Date,
        coords: GpsCoordinates?,
        text: String,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val parts = mutableListOf(
            String.format(Locale.ROOT, "%05d", id),
            formatTime(time, zone),
            formatCoordinates(coords).trimStart('_'),
        )
        val safe = sanitize(text)
        if (safe.isNotEmpty()) parts.add(safe)
        return parts.joinToString("_") + ".jpg"
    }
}
