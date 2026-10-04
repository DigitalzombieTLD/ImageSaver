package app.imagesaver

object ZoomLimits {
    const val MIN = 1f
    const val MAX = 8f
    const val STEP = 1.25f

    fun clamp(zoom: Float): Float = if (zoom.isNaN()) MIN else zoom.coerceIn(MIN, MAX)
}
