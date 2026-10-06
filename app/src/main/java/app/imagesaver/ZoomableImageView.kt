package app.imagesaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Fit-to-view bitmap with zoom (1x–8x), pinch, and pan. */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private var zoom = ZoomLimits.MIN
    private var panX = 0f
    private var panY = 0f
    private val matrix = Matrix()
    private var rotation = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setZoomInternal(zoom * d.scaleFactor)
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            panX -= dx
            panY -= dy
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetZoom()
            return true
        }
    })

    fun setFrame(bmp: Bitmap) {
        bitmap = bmp
        invalidate()
    }

    fun zoomIn() = setZoomInternal(zoom * ZoomLimits.STEP)
    fun zoomOut() = setZoomInternal(zoom / ZoomLimits.STEP)
    fun resetZoom() {
        panX = 0f
        panY = 0f
        setZoomInternal(ZoomLimits.MIN)
    }

    fun rotateLeft() = setRotation(Rotation.left(rotation))
    fun rotateRight() = setRotation(Rotation.right(rotation))

    private fun setRotation(deg: Int) {
        rotation = deg
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun setZoomInternal(z: Float) {
        zoom = ZoomLimits.clamp(z)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val swap = Rotation.swapsAxes(rotation)
        val bw = (if (swap) bmp.height else bmp.width).toFloat()
        val bh = (if (swap) bmp.width else bmp.height).toFloat()
        val fit = minOf(width / bw, height / bh)
        val scale = fit * zoom
        val scaledW = bw * scale
        val scaledH = bh * scale
        // keep the image covering the view when larger than it, centered otherwise
        val maxX = maxOf(0f, (scaledW - width) / 2)
        val maxY = maxOf(0f, (scaledH - height) / 2)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        matrix.reset()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postRotate(rotation.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(width / 2f + panX, height / 2f + panY)
        canvas.drawBitmap(bmp, matrix, null)
    }
}
