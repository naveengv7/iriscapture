/*
 * SharpnessAnalyzer - Calculates image sharpness for best-frame selection
 *
 * OPTIMIZED FOR IRIS CAPTURE:
 * - Full resolution analysis for iris ROI (no downsampling)
 * - Combined Laplacian + Tenengrad (Sobel) metrics
 * - Tenengrad is more robust for iris micro-texture detection
 *
 * Higher values indicate sharper/more focused images.
 */
package com.google.mediapipe.examples.facelandmarker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

object SharpnessAnalyzer {
    private const val TAG = "SharpnessAnalyzer"

    // Weight for combining Laplacian and Tenengrad scores
    // Tenengrad is better for fine textures (iris patterns)
    private const val LAPLACIAN_WEIGHT = 0.3
    private const val TENENGRAD_WEIGHT = 0.7

    // Use full resolution for telephoto iris analysis
    private const val USE_FULL_RES_FOR_IRIS = true

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
     * Calculate sharpness score focused on a specific iris region.
     * Uses normalized coordinates (0.0-1.0) for iris center and radius.
     *
     * OPTIMIZED: Uses full resolution for iris ROI and combines
     * Laplacian variance with Tenengrad gradient for better iris texture detection.
     *
     * EXIF-aware: Input coordinates are in display space (from MediaPipe), but
     * BitmapFactory.decodeByteArray returns pixels in raw sensor orientation.
     * This function transforms display-space coordinates to raw pixel space
     * using the JPEG's EXIF rotation tag.
     *
     * @param jpegBytes JPEG image bytes
     * @param irisNormX Normalized X coordinate of iris center (0.0-1.0) in display space
     * @param irisNormY Normalized Y coordinate of iris center (0.0-1.0) in display space
     * @param irisNormRadius Normalized radius of iris (relative to display width)
     * @param expandFactor How much to expand the iris ROI (1.0 = exact iris, 2.0 = 2x size)
     * @return Sharpness score (higher is better)
     */
    fun calculateSharpnessForIris(
        jpegBytes: ByteArray,
        irisNormX: Float,
        irisNormY: Float,
        irisNormRadius: Float,
        expandFactor: Float = 1.5f
    ): Double {
        // Get raw image dimensions without full decode (before EXIF rotation)
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, boundsOptions)
        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight

        if (rawW <= 0 || rawH <= 0) {
            Log.w(TAG, "IRIS_SHARPNESS: Failed to get image dimensions")
            return calculateSharpness(jpegBytes, null)
        }

        // Read EXIF rotation and transform display-space coords to raw pixel space.
        // BitmapFactory.decodeByteArray returns pixels in raw sensor orientation,
        // so the ROI must be computed in raw pixel space.
        val exifDegrees = EyeImageCropper.getExifRotationDegrees(jpegBytes)
        val (rawNormX, rawNormY) = EyeImageCropper.displayToRawNorm(irisNormX, irisNormY, exifDegrees)

        // irisNormRadius is relative to display width; compute pixel radius
        val displayWidth = if (exifDegrees == 90 || exifDegrees == 270) rawH else rawW
        val irisPixelRadius = irisNormRadius * displayWidth
        val expandedPixelRadius = irisPixelRadius * expandFactor

        // Compute ROI in raw pixel coordinates
        val rawCenterX = rawNormX * rawW
        val rawCenterY = rawNormY * rawH
        val roiLeftFull = (rawCenterX - expandedPixelRadius).toInt().coerceIn(0, rawW - 1)
        val roiTopFull = (rawCenterY - expandedPixelRadius).toInt().coerceIn(0, rawH - 1)
        val roiRightFull = (rawCenterX + expandedPixelRadius).toInt().coerceIn(roiLeftFull + 1, rawW)
        val roiBottomFull = (rawCenterY + expandedPixelRadius).toInt().coerceIn(roiTopFull + 1, rawH)

        val irisRoiFull = Rect(roiLeftFull, roiTopFull, roiRightFull, roiBottomFull)
        val roiSize = irisRoiFull.width() * irisRoiFull.height()

        // Decide on sample size based on ROI size and USE_FULL_RES_FOR_IRIS flag
        // For iris, we want full resolution to capture micro-texture
        // But if ROI is very large, we may need to downsample for memory
        val sampleSize = when {
            USE_FULL_RES_FOR_IRIS && roiSize < 1000 * 1000 -> 1  // Full res for iris ROI < 1MP
            roiSize < 4000 * 4000 -> 2  // Half res for larger ROIs
            else -> 4
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: return 0.0

        return try {
            val width = bitmap.width
            val height = bitmap.height

            // Calculate iris ROI in bitmap coordinates (accounting for downsampling)
            val roiLeft = (roiLeftFull / sampleSize).coerceIn(0, width - 1)
            val roiTop = (roiTopFull / sampleSize).coerceIn(0, height - 1)
            val roiRight = (roiRightFull / sampleSize).coerceIn(roiLeft + 1, width)
            val roiBottom = (roiBottomFull / sampleSize).coerceIn(roiTop + 1, height)

            val irisRoi = Rect(roiLeft, roiTop, roiRight, roiBottom)
            Log.d(TAG, "IRIS_SHARPNESS: ROI=$irisRoi (${irisRoi.width()}x${irisRoi.height()}) " +
                    "from bitmap ${width}x${height} (sampleSize=$sampleSize), " +
                    "displayCoords=(${(irisNormX*100).toInt()}%, ${(irisNormY*100).toInt()}%) " +
                    "exif=${exifDegrees}°")

            // Calculate combined sharpness score using both metrics
            val laplacianScore = calculateLaplacianVariance(bitmap, irisRoi)
            val tenengradScore = calculateTenengrad(bitmap, irisRoi)

            // Normalize and combine scores
            // Tenengrad typically gives larger values, so we normalize them
            val normalizedTenengrad = tenengradScore / 100.0  // Scale down Tenengrad

            val combinedScore = (LAPLACIAN_WEIGHT * laplacianScore) +
                               (TENENGRAD_WEIGHT * normalizedTenengrad)

            Log.d(TAG, "IRIS_SHARPNESS: Laplacian=${String.format("%.1f", laplacianScore)} " +
                    "Tenengrad=${String.format("%.1f", tenengradScore)} " +
                    "Combined=${String.format("%.1f", combinedScore)}")

            combinedScore
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

    /**
     * Tenengrad sharpness calculation using Sobel gradient.
     *
     * Tenengrad is more robust for fine texture detection than Laplacian.
     * It uses Sobel operators to compute gradients in x and y directions,
     * then calculates the sum of squared gradient magnitudes.
     *
     * This is particularly effective for iris texture which has
     * radial and circular patterns at multiple scales.
     */
    private fun calculateTenengrad(bitmap: Bitmap, roiRect: Rect?): Double {
        val width = bitmap.width
        val height = bitmap.height

        // Define region of interest (default: center 50%)
        val roi = roiRect ?: Rect(
            width / 4,
            height / 4,
            3 * width / 4,
            3 * height / 4
        )

        // Clamp ROI to bitmap bounds (need 1 pixel border for Sobel)
        val left = roi.left.coerceIn(1, width - 2)
        val top = roi.top.coerceIn(1, height - 2)
        val right = roi.right.coerceIn(left + 1, width - 1)
        val bottom = roi.bottom.coerceIn(top + 1, height - 1)

        val roiWidth = right - left
        val roiHeight = bottom - top

        if (roiWidth < 3 || roiHeight < 3) return 0.0

        // Get pixels for the ROI (with 1 pixel border for Sobel)
        val extLeft = (left - 1).coerceAtLeast(0)
        val extTop = (top - 1).coerceAtLeast(0)
        val extRight = (right + 1).coerceAtMost(width)
        val extBottom = (bottom + 1).coerceAtMost(height)
        val extWidth = extRight - extLeft
        val extHeight = extBottom - extTop

        val pixels = IntArray(extWidth * extHeight)
        bitmap.getPixels(pixels, 0, extWidth, extLeft, extTop, extWidth, extHeight)

        // Convert to grayscale
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        // Apply Sobel operators and calculate Tenengrad
        // Sobel X: [-1, 0, 1; -2, 0, 2; -1, 0, 1]
        // Sobel Y: [-1, -2, -1; 0, 0, 0; 1, 2, 1]
        var sumGradientSq = 0.0
        var count = 0

        // Offset to account for extended border
        val xOffset = left - extLeft
        val yOffset = top - extTop

        for (y in 1 until roiHeight - 1) {
            for (x in 1 until roiWidth - 1) {
                val ey = y + yOffset
                val ex = x + xOffset

                if (ey < 1 || ey >= extHeight - 1 || ex < 1 || ex >= extWidth - 1) continue

                val idx = ey * extWidth + ex

                // Sobel X gradient
                val gx = -gray[idx - extWidth - 1] + gray[idx - extWidth + 1] +
                        -2 * gray[idx - 1] + 2 * gray[idx + 1] +
                        -gray[idx + extWidth - 1] + gray[idx + extWidth + 1]

                // Sobel Y gradient
                val gy = -gray[idx - extWidth - 1] - 2 * gray[idx - extWidth] - gray[idx - extWidth + 1] +
                        gray[idx + extWidth - 1] + 2 * gray[idx + extWidth] + gray[idx + extWidth + 1]

                // Squared gradient magnitude (Tenengrad)
                val gradientSq = gx.toDouble() * gx + gy.toDouble() * gy
                sumGradientSq += gradientSq
                count++
            }
        }

        if (count == 0) return 0.0

        // Return mean squared gradient
        return sumGradientSq / count
    }

    /**
     * Calculate sharpness using both Laplacian and Tenengrad for maximum accuracy.
     * This is the recommended method for critical iris quality assessment.
     */
    fun calculateCombinedSharpness(bitmap: Bitmap, roiRect: Rect? = null): Double {
        val laplacian = calculateLaplacianVariance(bitmap, roiRect)
        val tenengrad = calculateTenengrad(bitmap, roiRect)
        val normalizedTenengrad = tenengrad / 100.0

        return (LAPLACIAN_WEIGHT * laplacian) + (TENENGRAD_WEIGHT * normalizedTenengrad)
    }

    /**
     * Data class to hold image with its sharpness score
     */
    data class ScoredImage(
        val jpegBytes: ByteArray,
        val sharpnessScore: Double,
        val timestamp: Long,
        val filename: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ScoredImage
            return timestamp == other.timestamp
        }

        override fun hashCode(): Int {
            return timestamp.hashCode()
        }
    }

    /**
     * Select the best (sharpest) image from a list
     */
    fun selectBest(images: List<ScoredImage>): ScoredImage? {
        return images.maxByOrNull { it.sharpnessScore }
    }
}
