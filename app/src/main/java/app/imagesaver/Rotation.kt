package app.imagesaver

/** Preview rotation in 90-degree steps; display-only, never applied to saved JPEG bytes. */
object Rotation {
    fun right(degrees: Int): Int = normalize(degrees + 90)
    fun left(degrees: Int): Int = normalize(degrees - 90)
    fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
    fun swapsAxes(degrees: Int): Boolean = normalize(degrees) % 180 != 0
}
