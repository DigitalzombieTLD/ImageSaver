package app.imagesaver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GpsCoordinates(val latitude: Double, val longitude: Double)

/** Builds safe image filenames: ID_date_time_lat_lon|X[_nummer][_stand].jpg */
object FilenameBuilder {
    const val NO_GPS = "X"
    const val MAX_TEXT_LENGTH = 50

    fun sanitize(text: String): String =
        text.trim()
            .replace(Regex("[^A-Za-z0-9.-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-', '.')
            .take(MAX_TEXT_LENGTH)
            .trim('-', '.')

    /**
     * Validates the "Nummer" input. Accepts an optionally signed decimal number (',' or '.' as separator),
     * no exponent. Returns the normalized plain representation (e.g. "12,5" -> "12.5", ".5" -> "0.5"),
     * "" for blank input, or null when the input is not a sensible number.
     */
    fun normalizeNummer(input: String): String? {
        val s = input.trim().replace(',', '.')
        if (s.isEmpty()) return ""
        val m = Regex("^([+-]?)(\\d*)(?:\\.(\\d*))?$").matchEntire(s) ?: return null
        val sign = m.groupValues[1]
        val intPart = m.groupValues[2]
        val frac = m.groupValues[3]
        if (intPart.isEmpty() && frac.isEmpty()) return null
        if (intPart.length + frac.length > 18) return null
        val normalized = (if (intPart.isEmpty()) "0" else intPart) + (if (frac.isEmpty()) "" else ".$frac")
        return (if (sign == "-") "-" else "") + normalized
    }

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
        nummer: String,
        stand: String,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val parts = mutableListOf(
            String.format(Locale.ROOT, "%05d", id),
            formatTime(time, zone),
            formatCoordinates(coords),
        )
        val safeNummer = sanitize(normalizeNummer(nummer) ?: "")
        if (safeNummer.isNotEmpty()) parts.add(safeNummer)
        val safeStand = sanitize(stand)
        if (safeStand.isNotEmpty()) parts.add(safeStand)
        return parts.joinToString("_") + ".jpg"
    }
}
