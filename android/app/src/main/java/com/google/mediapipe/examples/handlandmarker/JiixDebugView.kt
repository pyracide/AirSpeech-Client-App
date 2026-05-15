package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.google.mediapipe.examples.handlandmarker.myscript.Item

/**
 * A debug overlay view that reconstructs and renders JIIX stroke data.
 *
 * JIIX Item.X and Item.Y coordinates are in millimetres (mm), as submitted
 * to the MyScript OffscreenEditor via DisplayMetricsConverter. This view
 * re-normalises those coordinates to fill its own bounds, letting you verify
 * that the shape MyScript received matches what you drew on-screen.
 *
 * Enable by setting [isJiixDebugEnabled] = true and calling [showStrokes].
 */
class JiixDebugView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Public API ────────────────────────────────────────────────────────────

    /** Set to true to enable the view (it still starts GONE). */
    var isJiixDebugEnabled = false

    /** Called when the user taps to dismiss the popup. */
    var onDismissListener: (() -> Unit)? = null

    /**
     * Show the JIIX stroke items as a debug overlay.
     * Coordinates are expected in millimetres (the exact values sent to MyScript).
     */
    fun showStrokes(items: List<Item>) {
        Log.d("JiixDebugView", "showStrokes called with ${items.size} items, enabled=$isJiixDebugEnabled")
        if (!isJiixDebugEnabled) return
        currentItems = items
        visibility = VISIBLE
        invalidate()
    }

    /** Hide the popup. */
    fun dismiss() {
        visibility = GONE
        currentItems = emptyList()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private var currentItems: List<Item> = emptyList()

    // Background
    private val bgPaint = Paint().apply {
        color = Color.argb(220, 15, 15, 30)   // near-black, semi-transparent
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.argb(255, 0, 200, 255)   // cyan border
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // Strokes
    private val strokePaint = Paint().apply {
        color = Color.argb(255, 0, 230, 120)   // bright green — easy to distinguish
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    // Dot at each sample point (makes density visible)
    private val dotPaint = Paint().apply {
        color = Color.argb(180, 255, 200, 0)   // amber
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Label
    private val labelPaint = Paint().apply {
        color = Color.argb(255, 0, 200, 255)
        textSize = 30f
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val PADDING = 32f
    private val HEADER_H = 52f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // ALWAYS draw background + border so we can see the view is active
        val bgRect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, 16f, 16f, bgPaint)
        canvas.drawRoundRect(bgRect, 16f, 16f, borderPaint)

        // Header
        canvas.drawText("JIIX Debug (tap to dismiss)", PADDING, HEADER_H - 10f, labelPaint)

        if (currentItems.isEmpty()) {
            canvas.drawText("Waiting for JIIX data...", PADDING, HEADER_H + 40f, labelPaint)
            return
        }

        // Drawing area inside the popup
        val drawLeft  = PADDING
        val drawTop   = HEADER_H + PADDING / 2f
        val drawRight = w - PADDING
        val drawBot   = h - PADDING

        // Collect all X, Y to compute bounding box (in mm)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (item in currentItems) {
            if (item.type != "stroke") continue
            item.X?.forEach { x -> if (x < minX) minX = x; if (x > maxX) maxX = x }
            item.Y?.forEach { y -> if (y < minY) minY = y; if (y > maxY) maxY = y }
        }

        if (minX == Float.MAX_VALUE) {
            // No valid strokes
            labelPaint.color = Color.RED
            canvas.drawText("No stroke data in JIIX", PADDING, drawTop + 40f, labelPaint)
            labelPaint.color = Color.argb(255, 0, 200, 255) // reset
            return
        }

        val rangeX = (maxX - minX).coerceAtLeast(1f)
        val rangeY = (maxY - minY).coerceAtLeast(1f)

        // Scale uniformly to preserve aspect ratio
        val scaleX = (drawRight - drawLeft) / rangeX
        val scaleY = (drawBot - drawTop) / rangeY
        val scale  = minOf(scaleX, scaleY)

        // Centre the drawing in the available space
        val scaledW = rangeX * scale
        val scaledH = rangeY * scale
        val offX = drawLeft + (drawRight - drawLeft - scaledW) / 2f
        val offY = drawTop  + (drawBot - drawTop  - scaledH) / 2f

        fun mmToCanvasX(x: Float) = offX + (x - minX) * scale
        fun mmToCanvasY(y: Float) = offY + (y - minY) * scale

        var strokeCount = 0
        var pointCount  = 0

        for (item in currentItems) {
            if (item.type != "stroke") continue
            val xs = item.X ?: continue
            val ys = item.Y ?: continue
            if (xs.isEmpty() || xs.size != ys.size) continue

            strokeCount++
            pointCount += xs.size

            val path = Path()
            path.moveTo(mmToCanvasX(xs[0]), mmToCanvasY(ys[0]))
            for (i in 1 until xs.size) {
                path.lineTo(mmToCanvasX(xs[i]), mmToCanvasY(ys[i]))
            }
            canvas.drawPath(path, strokePaint)

            // Draw sample dots
            for (i in xs.indices) {
                canvas.drawCircle(mmToCanvasX(xs[i]), mmToCanvasY(ys[i]), 3f, dotPaint)
            }
        }

        // Stats footer
        val statsY = drawBot - 2f
        val smallLabel = Paint(labelPaint).apply { textSize = 22f; color = Color.LTGRAY }
        canvas.drawText(
            "$strokeCount stroke(s)  |  $pointCount pts  |  " +
            "X:[${String.format("%.1f", minX)}..${String.format("%.1f", maxX)}] mm  " +
            "Y:[${String.format("%.1f", minY)}..${String.format("%.1f", maxY)}] mm",
            PADDING, statsY, smallLabel
        )

        Log.d("JiixDebugView", "Drew $strokeCount strokes, $pointCount pts. " +
              "Bounds X:[${minX}..${maxX}] Y:[${minY}..${maxY}] mm")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            dismiss()
            onDismissListener?.invoke()
            return true
        }
        return true   // consume all touches so they don't fall through
    }
}
