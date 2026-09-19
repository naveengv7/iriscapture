package com.google.mediapipe.examples.facelandmarker

/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var guidePaint = Paint()
    private var boxPaint = Paint()
    private var focusPaint = Paint()
    private var irisTargetPaint = Paint()
    private var irisTargetFillPaint = Paint()
    private var textPaint = Paint()
    private var irisSizeIndicatorPaint = Paint()
    private var qualityPanelBgPaint = Paint()
    private var qualityTitlePaint = Paint()
    private var qualityMetricPaint = Paint()
    private var qualityPassPaint = Paint()
    private var qualityFailPaint = Paint()
    private var qualityLinePaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    var showCenteringGuide = false
    var showEyeAlignmentBox = false
    var eyeAlignmentBox: RectF? = null
    var frozenEyeBox: RectF? = null
    var focusRing: RectF? = null
    var isTargetingRightEye: Boolean? = null // True = Right Eye, False = Left Eye, Null = None/Reset

    // === LANDSCAPE SINGLE-EYE MODE ===
    var showIrisTarget = false  // Show circular iris target for landscape mode
    var irisTargetRadius = 0f   // Current iris size (0-1 normalized, relative to view height)
    var minIrisTargetRadius = 0.15f  // Minimum acceptable iris size (15% of frame height)
    var irisQualityOk = false   // True if iris is large enough in frame

    // === ISO 29794-6 QUALITY PANEL ===
    var qualityResult: IrisQualityAssessor.IrisQualityResult? = null
    var showQualityOverlay = false
    // Bottom margin in px for the quality panel. CameraFragment raises this so the
    // panel clears the controls container. Reconstructed from compiled output 2026-09-19.
    var qualityPanelBottomMargin = 40f

    init {
        initPaints()
    }

    fun clear() {
        initPaints()
        invalidate()
    }

    private fun initPaints() {
        guidePaint.color = Color.GREEN
        guidePaint.strokeWidth = 5f
        guidePaint.style = Paint.Style.STROKE

        boxPaint.color = Color.CYAN
        boxPaint.strokeWidth = 5f
        boxPaint.style = Paint.Style.STROKE

        focusPaint.color = Color.GREEN
        focusPaint.strokeWidth = 6f
        focusPaint.style = Paint.Style.STROKE

        // Iris target circle for landscape single-eye mode
        irisTargetPaint.color = Color.parseColor("#00FF00")  // Bright green
        irisTargetPaint.strokeWidth = 4f
        irisTargetPaint.style = Paint.Style.STROKE
        irisTargetPaint.isAntiAlias = true

        irisTargetFillPaint.color = Color.parseColor("#2200FF00")  // Semi-transparent green
        irisTargetFillPaint.style = Paint.Style.FILL
        irisTargetFillPaint.isAntiAlias = true

        // Text for instructions
        textPaint.color = Color.WHITE
        textPaint.textSize = 48f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isAntiAlias = true
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK)

        // Iris size indicator (shows current vs required size)
        irisSizeIndicatorPaint.color = Color.YELLOW
        irisSizeIndicatorPaint.strokeWidth = 3f
        irisSizeIndicatorPaint.style = Paint.Style.STROKE
        irisSizeIndicatorPaint.isAntiAlias = true
        irisSizeIndicatorPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 10f), 0f)

        // Quality panel paints
        qualityPanelBgPaint.color = Color.argb(200, 0, 0, 0)
        qualityPanelBgPaint.style = Paint.Style.FILL

        qualityTitlePaint.color = Color.WHITE
        qualityTitlePaint.textSize = 36f
        qualityTitlePaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        qualityTitlePaint.isAntiAlias = true

        qualityMetricPaint.color = Color.WHITE
        qualityMetricPaint.textSize = 28f
        qualityMetricPaint.typeface = android.graphics.Typeface.MONOSPACE
        qualityMetricPaint.isAntiAlias = true

        qualityPassPaint.color = Color.GREEN
        qualityPassPaint.textSize = 28f
        qualityPassPaint.typeface = android.graphics.Typeface.MONOSPACE
        qualityPassPaint.isAntiAlias = true

        qualityFailPaint.color = Color.RED
        qualityFailPaint.textSize = 28f
        qualityFailPaint.typeface = android.graphics.Typeface.MONOSPACE
        qualityFailPaint.isAntiAlias = true

        qualityLinePaint.color = Color.GRAY
        qualityLinePaint.strokeWidth = 1f
        qualityLinePaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // === LANDSCAPE SINGLE-EYE IRIS TARGET ===
        if (showIrisTarget) {
            drawIrisTarget(canvas)
        }

        if (showCenteringGuide && !showIrisTarget) {
            // Only show simple crosshair if iris target is not shown
            val cx = width / 2f
            val cy = height / 2f
            val len = 100f
            canvas.drawLine(cx - len, cy, cx + len, cy, guidePaint)
            canvas.drawLine(cx, cy - len, cx, cy + len, guidePaint)
        }

        // Calculate scaling and offsets
        val scaledImageWidth = imageWidth * scaleFactor
        val scaledImageHeight = imageHeight * scaleFactor
        val offsetX = (width - scaledImageWidth) / 2f
        val offsetY = (height - scaledImageHeight) / 2f

        val boxToDraw = frozenEyeBox ?: eyeAlignmentBox
        if (showEyeAlignmentBox && boxToDraw != null) {
            // Apply scale and offset to the rect (boxToDraw is in image coordinates)
            val rect = RectF(
                boxToDraw.left * scaleFactor + offsetX,
                boxToDraw.top * scaleFactor + offsetY,
                boxToDraw.right * scaleFactor + offsetX,
                boxToDraw.bottom * scaleFactor + offsetY
            )
            canvas.drawRect(rect, boxPaint)
        }

        // Draw Focus Ring if active
        focusRing?.let { box ->
            val rect = RectF(
                box.left * scaleFactor + offsetX,
                box.top * scaleFactor + offsetY,
                box.right * scaleFactor + offsetX,
                box.bottom * scaleFactor + offsetY
            )
            canvas.drawRect(rect, focusPaint)

            // Draw crosshair
            val cx = rect.centerX()
            val cy = rect.centerY()
            val len = 20f
            canvas.drawLine(cx - len, cy, cx + len, cy, focusPaint)
            canvas.drawLine(cx, cy - len, cx, cy + len, focusPaint)
        }

        // Draw quality panel if available
        if (showQualityOverlay && qualityResult != null) {
            drawQualityPanel(canvas)
        }
    }

    /**
     * Set image dimensions for scaling calculations.
     */
    fun setImageDimensions(width: Int, height: Int) {
        this.imageWidth = width
        this.imageHeight = height
        scaleFactor = max(this.width * 1f / width, this.height * 1f / height)
        invalidate()
    }

    /**
     * Draw the circular iris target for landscape single-eye mode.
     * Shows:
     * - Outer circle: Minimum required iris size (dashed yellow)
     * - Inner circle: Target zone for iris placement (solid green)
     * - Center crosshair
     * - Instructions text
     * - Size indicator (current iris size vs required)
     */
    private fun drawIrisTarget(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val frameSize = minOf(width, height).toFloat()

        // Minimum required iris radius (dashed yellow circle)
        val minRadius = frameSize * minIrisTargetRadius
        canvas.drawCircle(cx, cy, minRadius, irisSizeIndicatorPaint)

        // Target zone (slightly smaller, solid green)
        val targetRadius = minRadius * 0.9f
        canvas.drawCircle(cx, cy, targetRadius, irisTargetFillPaint)
        canvas.drawCircle(cx, cy, targetRadius, irisTargetPaint)

        // Inner target circle (where iris center should be)
        val innerRadius = targetRadius * 0.3f
        irisTargetPaint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, innerRadius, irisTargetPaint)
        irisTargetPaint.strokeWidth = 4f

        // Center crosshair
        val crossLen = innerRadius * 0.8f
        canvas.drawLine(cx - crossLen, cy, cx + crossLen, cy, irisTargetPaint)
        canvas.drawLine(cx, cy - crossLen, cx, cy + crossLen, irisTargetPaint)

        // Eye label
        val eyeLabel = when (isTargetingRightEye) {
            true -> "RIGHT EYE"
            false -> "LEFT EYE"
            null -> "ALIGN EYE"
        }

        // Draw instructions at top
        canvas.drawText(
            "Position $eyeLabel in the circle",
            cx,
            cy - minRadius - 60f,
            textPaint
        )

        // Draw size indicator at bottom
        val sizeText = if (irisQualityOk) {
            "✓ Iris size OK - Ready to capture"
        } else {
            "Move CLOSER until iris fills the circle"
        }

        // Change text color based on quality
        val originalColor = textPaint.color
        textPaint.color = if (irisQualityOk) Color.GREEN else Color.YELLOW
        canvas.drawText(sizeText, cx, cy + minRadius + 80f, textPaint)
        textPaint.color = originalColor

        // Draw corner brackets to indicate frame bounds
        drawCornerBrackets(canvas, cx, cy, minRadius * 1.3f)
    }

    /**
     * Draw corner brackets to help user align iris in frame
     */
    private fun drawCornerBrackets(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val bracketLen = size * 0.15f
        val corners = arrayOf(
            floatArrayOf(cx - size, cy - size),  // Top-left
            floatArrayOf(cx + size, cy - size),  // Top-right
            floatArrayOf(cx - size, cy + size),  // Bottom-left
            floatArrayOf(cx + size, cy + size)   // Bottom-right
        )

        guidePaint.strokeWidth = 3f

        // Top-left
        canvas.drawLine(corners[0][0], corners[0][1], corners[0][0] + bracketLen, corners[0][1], guidePaint)
        canvas.drawLine(corners[0][0], corners[0][1], corners[0][0], corners[0][1] + bracketLen, guidePaint)

        // Top-right
        canvas.drawLine(corners[1][0], corners[1][1], corners[1][0] - bracketLen, corners[1][1], guidePaint)
        canvas.drawLine(corners[1][0], corners[1][1], corners[1][0], corners[1][1] + bracketLen, guidePaint)

        // Bottom-left
        canvas.drawLine(corners[2][0], corners[2][1], corners[2][0] + bracketLen, corners[2][1], guidePaint)
        canvas.drawLine(corners[2][0], corners[2][1], corners[2][0], corners[2][1] - bracketLen, guidePaint)

        // Bottom-right
        canvas.drawLine(corners[3][0], corners[3][1], corners[3][0] - bracketLen, corners[3][1], guidePaint)
        canvas.drawLine(corners[3][0], corners[3][1], corners[3][0], corners[3][1] - bracketLen, guidePaint)

        guidePaint.strokeWidth = 5f
    }

    /**
     * Update iris size indicator (called from CameraFragment during preview)
     * @param normalizedRadius Iris radius as fraction of frame height (0.0-1.0)
     */
    fun updateIrisSize(normalizedRadius: Float) {
        irisTargetRadius = normalizedRadius
        irisQualityOk = normalizedRadius >= minIrisTargetRadius
        invalidate()
    }

    /**
     * Draw the ISO 29794-6 quality assessment panel at the bottom-center of the canvas.
     * Shows overall score with pass/fail and individual metric breakdown.
     */
    private fun drawQualityPanel(canvas: Canvas) {
        val result = qualityResult ?: return

        val panelWidth = 520f
        val lineHeight = 34f
        val padding = 16f
        val numLines = result.metrics.size + 2  // title + separator + metrics
        val panelHeight = padding * 2 + lineHeight * numLines + 8f

        // Position at bottom-center
        val panelLeft = (width - panelWidth) / 2f
        val panelTop = height - panelHeight - qualityPanelBottomMargin
        val panelRight = panelLeft + panelWidth
        val panelBottom = panelTop + panelHeight

        // Draw background
        canvas.drawRoundRect(panelLeft, panelTop, panelRight, panelBottom, 12f, 12f, qualityPanelBgPaint)

        var y = panelTop + padding + lineHeight

        // Title line: "IRIS QUALITY: 78/100 PASS"
        val overallLabel = "IRIS QUALITY: ${result.overallScore.toInt()}/100"
        val passLabel = if (result.overallPassed) "PASS" else "FAIL"
        val titlePaint = if (result.overallPassed) qualityPassPaint else qualityFailPaint

        qualityTitlePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(overallLabel, panelLeft + padding, y, qualityTitlePaint)

        titlePaint.textSize = 36f
        titlePaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        titlePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(passLabel, panelRight - padding, y, titlePaint)
        titlePaint.textSize = 28f
        titlePaint.typeface = android.graphics.Typeface.MONOSPACE

        y += lineHeight * 0.3f

        // Separator line
        canvas.drawLine(panelLeft + padding, y, panelRight - padding, y, qualityLinePaint)
        y += lineHeight * 0.7f

        // Individual metrics
        for (metric in result.metrics) {
            val statusPaint = if (metric.passed) qualityPassPaint else qualityFailPaint
            val statusText = if (metric.passed) "PASS" else "FAIL"

            // Draw metric name in white
            qualityMetricPaint.textAlign = Paint.Align.LEFT
            val nameText = String.format("%-16s", metric.name)
            canvas.drawText(nameText, panelLeft + padding, y, qualityMetricPaint)

            // Draw score
            val scoreNumText = String.format("%3d", metric.normalizedScore.toInt())
            canvas.drawText(scoreNumText, panelLeft + padding + 300f, y, qualityMetricPaint)

            // Draw PASS/FAIL in color
            statusPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(statusText, panelRight - padding, y, statusPaint)

            y += lineHeight
        }
    }

    companion object {
        private const val TAG = "OverlayView"
    }
}
