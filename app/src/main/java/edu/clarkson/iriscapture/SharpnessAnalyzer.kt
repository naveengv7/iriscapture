/*
 * SharpnessAnalyzer - Calculates image sharpness for iris capture.
 *
 * THE metric is plain Laplacian variance. Higher values mean a sharper image.
 * The pass/fail thresholds in CameraFragment (SHARPNESS_THRESHOLD_REAR = 50.0,
 * SHARPNESS_THRESHOLD_FRONT = 20.0) are tuned against exactly this metric, so do
 * not blend another score into it without re-tuning them.
 *
 * 2026-09-19: a documented Laplacian + Tenengrad (Sobel) blend used to live here
 * behind calculateSharpnessForIris / calculateCombinedSharpness, weighted 0.3 / 0.7.
 * Nothing ever called it - its only caller was the burst capture path, which had no
 * reachable entry point - so it was removed along with that path rather than left to
 * look like the metric in use.
 */
package edu.clarkson.iriscapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect

object SharpnessAnalyzer {

    /**
     * Calculate sharpness score using Laplacian variance.
     * Higher score = sharper image.
     *
     * @param jpegBytes JPEG image bytes
     * @param roiRect Optional region of interest (null = use center 50%)
     * @return Sharpness score (higher is better)
     */
    fun calculateSharpness(jpegBytes: ByteArray, roiRect: Rect? = null): Double {
        val options = BitmapFactory.Options().apply {
            // Decode at 1/2 size for better iris detail detection (was 1/4)
            inSampleSize = 2
        }
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: return 0.0

        return try {
            calculateLaplacianVariance(bitmap, roiRect)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Calculate sharpness from a Bitmap.
     */
    fun calculateSharpness(bitmap: Bitmap, roiRect: Rect? = null): Double {
        return calculateLaplacianVariance(bitmap, roiRect)
    }

    /**
     * Laplacian variance calculation.
     * The Laplacian operator highlights regions of rapid intensity change (edges).
     * Variance of the Laplacian response indicates overall sharpness.
     */
    private fun calculateLaplacianVariance(bitmap: Bitmap, roiRect: Rect?): Double {
        val width = bitmap.width
        val height = bitmap.height

        // Define region of interest (default: center 50%)
        val roi = roiRect ?: Rect(
            width / 4,
            height / 4,
            3 * width / 4,
            3 * height / 4
        )

        // Clamp ROI to bitmap bounds
        val left = roi.left.coerceIn(1, width - 2)
        val top = roi.top.coerceIn(1, height - 2)
        val right = roi.right.coerceIn(left + 1, width - 1)
        val bottom = roi.bottom.coerceIn(top + 1, height - 1)

        // Extract grayscale values for the ROI
        val roiWidth = right - left
        val roiHeight = bottom - top

        if (roiWidth < 3 || roiHeight < 3) return 0.0

        // Get pixels for the ROI
        val pixels = IntArray(roiWidth * roiHeight)
        bitmap.getPixels(pixels, 0, roiWidth, left, top, roiWidth, roiHeight)

        // Convert to grayscale
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Standard luminance formula
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        // Apply Laplacian kernel: [0, 1, 0; 1, -4, 1; 0, 1, 0]
        // and calculate variance
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 1 until roiHeight - 1) {
            for (x in 1 until roiWidth - 1) {
                val idx = y * roiWidth + x

                // Laplacian: center * -4 + top + bottom + left + right
                val laplacian = -4 * gray[idx] +
                        gray[idx - roiWidth] +  // top
                        gray[idx + roiWidth] +  // bottom
                        gray[idx - 1] +         // left
                        gray[idx + 1]           // right

                sum += laplacian
                sumSq += laplacian.toDouble() * laplacian
                count++
            }
        }

        if (count == 0) return 0.0

        // Variance = E[X^2] - E[X]^2
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)

        return variance
    }
}
