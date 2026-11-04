package com.example.searchhelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Full-screen crop surface: draws the captured frame, dims everything outside the
 * selection and lets the user drag / move / resize a rectangular region.
 *
 * Coordinates stay in bitmap-pixel space internally (this bitmap IS the screen
 * frame), so [bitmapRect] can be fed straight into [QuestionSolver.cropRegion].
 * Only integer pixels are exposed; nothing here depends on screen density.
 */
class RegionCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null

    /** Where the bitmap is drawn inside this view (fit-center), in view px. */
    private val fit = RectF()

    /** Selection in bitmap pixels. */
    private val selection = RectF()

    private var initialized = false

    // --- gesture state ------------------------------------------------------
    private var mode = MODE_NONE
    private var lastX = 0f
    private var lastY = 0f
    private var grabOffsetX = 0f // selection corner/offset at gesture start
    private var grabOffsetY = 0f
    private var activeCorner = -1

    private val dimPaint = Paint().apply {
        color = 0xB3000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Set the frame to crop. [initialNormalized] is 0..1, or null for a default box. */
    fun setBitmap(bmp: Bitmap, initialNormalized: RectF?) {
        bitmap = bmp
        initialized = false
        val start = initialNormalized
        if (start != null && start.width() > 0.02f && start.height() > 0.02f) {
            pendingNormalized = RectF(start)
        } else {
            pendingNormalized = null
        }
        invalidate()
    }

    private var pendingNormalized: RectF? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutFit()
    }

    private fun layoutFit() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0 || bmp.width == 0 || bmp.height == 0) return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        fit.set(
            (width - drawW) / 2f,
            (height - drawH) / 2f,
            (width + drawW) / 2f,
            (height + drawH) / 2f
        )
        if (!initialized) {
            initialized = true
            val p = pendingNormalized
            val bmp = bitmap ?: return
            if (p != null) {
                selection.set(
                    p.left * bmp.width,
                    p.top * bmp.height,
                    p.right * bmp.width,
                    p.bottom * bmp.height
                )
            } else {
                // Default box: the question area is usually the upper 2/3 of the screen.
                selection.set(
                    bmp.width * 0.06f,
                    bmp.height * 0.10f,
                    bmp.width * 0.94f,
                    bmp.height * 0.88f
                )
            }
            pendingNormalized = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        layoutFit()
        canvas.drawColor(0xFF101010.toInt())
        canvas.drawBitmap(bmp, null, fit, bitmapPaint)

        // Normalize locally so a mid-drag inverted box (dragging up/left) still draws.
        val left = minOf(selection.left, selection.right)
        val top = minOf(selection.top, selection.bottom)
        val right = maxOf(selection.left, selection.right)
        val bottom = maxOf(selection.top, selection.bottom)
        if (right - left < 1f || bottom - top < 1f) return

        val viewRect = toView(RectF(left, top, right, bottom))
        // Dim everything outside the selection: even-odd path = full view minus sel.
        val path = Path().apply {
            addRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), Path.Direction.CW)
            addRect(viewRect, Path.Direction.CW)
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(path, dimPaint)
        canvas.drawRect(viewRect, borderPaint)

        val radius = 12f
        val corners = arrayOf(
            viewRect.left to viewRect.top,
            viewRect.right to viewRect.top,
            viewRect.right to viewRect.bottom,
            viewRect.left to viewRect.bottom
        )
        for ((cx, cy) in corners) {
            canvas.drawCircle(cx, cy, radius, handlePaint)
        }
    }

    // ------------------------------------------------------------------ touch

    private fun toView(r: RectF): RectF {
        val bmp = bitmap ?: return RectF(r)
        if (bmp.width == 0 || bmp.height == 0) return RectF(r)
        val sx = fit.width() / bmp.width
        val sy = fit.height() / bmp.height
        return RectF(
            fit.left + r.left * sx,
            fit.top + r.top * sy,
            fit.left + r.right * sx,
            fit.top + r.bottom * sy
        )
    }

    private fun toBitmap(viewX: Float, viewY: Float): FloatArray {
        val bmp = bitmap ?: return floatArrayOf(viewX, viewY)
        if (bmp.width == 0 || bmp.height == 0) return floatArrayOf(viewX, viewY)
        val sx = fit.width() / bmp.width
        val sy = fit.height() / bmp.height
        return floatArrayOf((viewX - fit.left) / sx, (viewY - fit.top) / sy)
    }

    /** Corner hit tolerance in bitmap pixels (relative to frame height). */
    private val cornerTouch: Float
        get() {
            val bmp = bitmap ?: return 28f
            return maxOf(24f, bmp.height / 50f)
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        val sx = fit.width() / bmp.width
        val sy = fit.height() / bmp.height
        val bmpX = (event.x - fit.left) / sx
        val bmpY = (event.y - fit.top) / sy
        val insideImage = bmpX >= 0 && bmpX <= bmp.width && bmpY >= 0 && bmpY <= bmp.height

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!insideImage) return false
                activeCorner = cornerAt(bmpX, bmpY)
                if (activeCorner >= 0) {
                    mode = MODE_RESIZE
                } else if (selection.contains(bmpX, bmpY)) {
                    mode = MODE_MOVE
                    grabOffsetX = bmpX - selection.left
                    grabOffsetY = bmpY - selection.top
                } else {
                    mode = MODE_NEW
                    selection.set(bmpX, bmpY, bmpX, bmpY)
                }
                lastX = bmpX
                lastY = bmpY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_NONE) return false
                when (mode) {
                    MODE_NEW -> {
                        selection.right = bmpX
                        selection.bottom = bmpY
                    }
                    MODE_MOVE -> {
                        val w = selection.width()
                        val h = selection.height()
                        selection.left = (bmpX - grabOffsetX).coerceIn(0f, bmp.width - w)
                        selection.top = (bmpY - grabOffsetY).coerceIn(0f, bmp.height - h)
                        selection.right = selection.left + w
                        selection.bottom = selection.top + h
                    }
                    MODE_RESIZE -> {
                        when (activeCorner) {
                            0 -> {
                                selection.left = bmpX.coerceIn(0f, selection.right - MIN_SIDE)
                                selection.top = bmpY.coerceIn(0f, selection.bottom - MIN_SIDE)
                            }
                            1 -> {
                                selection.right = bmpX.coerceIn(selection.left + MIN_SIDE, bmp.width.toFloat())
                                selection.top = bmpY.coerceIn(0f, selection.bottom - MIN_SIDE)
                            }
                            2 -> {
                                selection.right = bmpX.coerceIn(selection.left + MIN_SIDE, bmp.width.toFloat())
                                selection.bottom = bmpY.coerceIn(selection.top + MIN_SIDE, bmp.height.toFloat())
                            }
                            3 -> {
                                selection.left = bmpX.coerceIn(0f, selection.right - MIN_SIDE)
                                selection.bottom = bmpY.coerceIn(selection.top + MIN_SIDE, bmp.height.toFloat())
                            }
                        }
                    }
                }
                lastX = bmpX
                lastY = bmpY
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (mode != MODE_NONE) {
                    normalizeSelection()
                    invalidate()
                    mode = MODE_NONE
                    performClick()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun cornerAt(bmpX: Float, bmpY: Float): Int {
        val right = selection.right
        val bottom = selection.bottom
        val t = selection.top
        val l = selection.left
        return when {
            abs(bmpX - l) <= cornerTouch && abs(bmpY - t) <= cornerTouch -> 0
            abs(bmpX - right) <= cornerTouch && abs(bmpY - t) <= cornerTouch -> 1
            abs(bmpX - right) <= cornerTouch && abs(bmpY - bottom) <= cornerTouch -> 2
            abs(bmpX - l) <= cornerTouch && abs(bmpY - bottom) <= cornerTouch -> 3
            else -> -1
        }
    }

    /** Keep l<r / t<b and inside the frame after sloppy gestures. */
    private fun normalizeSelection() {
        val bmp = bitmap ?: return
        val left = selection.left.coerceIn(0f, bmp.width.toFloat())
        val top = selection.top.coerceIn(0f, bmp.height.toFloat())
        val right = selection.right.coerceIn(0f, bmp.width.toFloat())
        val bottom = selection.bottom.coerceIn(0f, bmp.height.toFloat())
        selection.set(
            minOf(left, right),
            minOf(top, bottom),
            maxOf(left, right),
            maxOf(top, bottom)
        )
    }

    /** The selected region as integer bitmap pixels, safe to crop with. */
    fun bitmapRect(): Rect {
        val bmp = bitmap ?: return Rect()
        normalizeSelection()
        // Degenerate / accidental tiny taps: fall back to the whole frame rather than
        // OCR-ing a two-pixel strip. The 24px guard mirrors QuestionSolver.cropRegion.
        if (selection.width() < 24f || selection.height() < 24f) {
            return Rect(0, 0, bmp.width, bmp.height)
        }
        return Rect(
            selection.left.toInt(),
            selection.top.toInt(),
            selection.right.toInt(),
            selection.bottom.toInt()
        )
    }

    /** 0..1 fractions of the frame, for persistence across resolutions. */
    fun normalizedRect(): RectF {
        val bmp = bitmap ?: return RectF(0f, 0f, 1f, 1f)
        val r = bitmapRect()
        return RectF(
            r.left / bmp.width.toFloat(),
            r.top / bmp.height.toFloat(),
            r.right / bmp.width.toFloat(),
            r.bottom / bmp.height.toFloat()
        )
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_NEW = 1
        const val MODE_MOVE = 2
        const val MODE_RESIZE = 3
        const val MIN_SIDE = 24f
    }
}
