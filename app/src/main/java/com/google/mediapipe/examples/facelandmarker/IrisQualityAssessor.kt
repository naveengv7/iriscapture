package com.google.mediapipe.examples.facelandmarker

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ISO 29794-6 inspired iris quality assessment.
 * Computes 7 quality metrics on a cropped eye image and produces
 * a composite score with pass/fail determination.
 */
object IrisQualityAssessor {
    private const val TAG = "IrisQualityAssessor"

    // Quality metric definitions
    enum class MetricId {
        USABLE_IRIS_AREA,
        GAZE_ANGLE,
        PUPIL_IRIS_RATIO,
        IRIS_PUPIL_CONTRAST,
        ILLUMINATION_UNIFORMITY,
        MOTION_BLUR,
        SHARPNESS
    }

    data class MetricResult(
        val id: MetricId,
        val name: String,
        val rawValue: Float,
        val normalizedScore: Float,  // 0-100
        val passed: Boolean,
        val weight: Float,
        val isCritical: Boolean
    )

    data class IrisQualityResult(
        val overallScore: Float,     // 0-100 weighted composite
        val overallPassed: Boolean,
        val metrics: List<MetricResult>
    )

    // Thresholds
    private const val USABLE_IRIS_THRESHOLD = 0.50f
    private const val USABLE_IRIS_THRESHOLD_FRONT = 0.35f  // Lower threshold for front camera (no flash)
    private const val GAZE_DISPLACEMENT_THRESHOLD = 0.25f
    private const val PUPIL_RATIO_MIN = 0.2f
    private const val PUPIL_RATIO_MAX = 0.7f
    private const val CONTRAST_THRESHOLD = 0.4f
    private const val UNIFORMITY_THRESHOLD = 0.50f
    private const val MOTION_BLUR_THRESHOLD = 4.0f
    private const val COMPOSITE_PASS_THRESHOLD = 40f

    // Intensity thresholds for usable iris detection (intensity-based fallback)
    private const val IRIS_INTENSITY_THRESHOLD_REAR = 180    // Dark iris expected with flash
    private const val IRIS_INTENSITY_THRESHOLD_FRONT = 220   // Brighter iris without flash

    /**
     * Perform full iris quality assessment on a cropped eye image.
     *
     * @param cropResult The cropped eye image from EyeImageCropper
     * @param existingSharpnessScore Sharpness score from SharpnessAnalyzer (used for metric 7)
     * @param sharpnessThreshold Mode-specific sharpness threshold for normalization
     * @param contrastThreshold Mode-specific contrast threshold
     * @param isFrontCamera True if using front camera (adjusts intensity thresholds for no-flash capture)
     * @return IrisQualityResult with overall score and individual metric results
     */
    fun assess(
        cropResult: EyeImageCropper.EyeCropResult,
        existingSharpnessScore: Double,
        sharpnessThreshold: Double,
        contrastThreshold: Float = CONTRAST_THRESHOLD,
        isFrontCamera: Boolean = false
    ): IrisQualityResult {
        val bitmap = cropResult.croppedBitmap
        val irisCenter = cropResult.irisCenter
        val irisRadius = cropResult.irisRadius

        val metrics = mutableListOf<MetricResult>()

        // 1. Usable Iris Area
        // Front camera without flash needs lower threshold and higher intensity tolerance
        val usableIrisThreshold = if (isFrontCamera) USABLE_IRIS_THRESHOLD_FRONT else USABLE_IRIS_THRESHOLD
        val usableArea = computeUsableIrisArea(
            bitmap, irisCenter, irisRadius,
            cropResult.upperEyelidPoints, cropResult.lowerEyelidPoints,
            isFrontCamera
        )
        val usableScore = (usableArea * 100f).coerceIn(0f, 100f)
        metrics.add(MetricResult(
            id = MetricId.USABLE_IRIS_AREA,
            name = "Usable Iris",
            rawValue = usableArea,
            normalizedScore = usableScore,
            passed = usableArea >= usableIrisThreshold,
            weight = 0.20f,
            isCritical = true
        ))

        // 2. Gaze Angle
        val gazeDisplacement = computeGazeAngle(
            bitmap, irisCenter, irisRadius,
            cropResult.upperEyelidPoints, cropResult.lowerEyelidPoints
        )
        val gazeScore = ((1f - gazeDisplacement / 0.5f) * 100f).coerceIn(0f, 100f)
        metrics.add(MetricResult(
            id = MetricId.GAZE_ANGLE,
            name = "Gaze Angle",
            rawValue = gazeDisplacement,
            normalizedScore = gazeScore,
            passed = gazeDisplacement <= GAZE_DISPLACEMENT_THRESHOLD,
            weight = 0.10f,
            isCritical = true
        ))

        // 3. Pupil-to-Iris Ratio
        val pupilRatio = computePupilIrisRatio(bitmap, irisCenter, irisRadius)
        val ratioScore = if (pupilRatio in PUPIL_RATIO_MIN..PUPIL_RATIO_MAX) {
            // Best at 0.45 (center of range)
            val distFromOptimal = abs(pupilRatio - 0.45f) / 0.25f
            ((1f - distFromOptimal) * 100f).coerceIn(0f, 100f)
        } else {
            // Out of range penalty
            val distOutside = if (pupilRatio < PUPIL_RATIO_MIN) PUPIL_RATIO_MIN - pupilRatio
            else pupilRatio - PUPIL_RATIO_MAX
            (max(0f, 50f - distOutside * 200f))
        }
        metrics.add(MetricResult(
            id = MetricId.PUPIL_IRIS_RATIO,
            name = "Pupil Ratio",
            rawValue = pupilRatio,
            normalizedScore = ratioScore,
            passed = pupilRatio in PUPIL_RATIO_MIN..PUPIL_RATIO_MAX,
            weight = 0.10f,
            isCritical = false
        ))

        // 4. Iris-Pupil Contrast
        val contrast = computeIrisPupilContrast(bitmap, irisCenter, irisRadius, pupilRatio)
        val contrastScore = (contrast / 1.0f * 100f).coerceIn(0f, 100f)
        metrics.add(MetricResult(
            id = MetricId.IRIS_PUPIL_CONTRAST,
            name = "Contrast",
            rawValue = contrast,
            normalizedScore = contrastScore,
            passed = contrast >= contrastThreshold,
            weight = 0.15f,
            isCritical = true
        ))

        // 5. Illumination Uniformity
        val uniformity = computeIlluminationUniformity(bitmap, irisCenter, irisRadius, pupilRatio)
        val uniformityScore = (uniformity * 100f).coerceIn(0f, 100f)
        metrics.add(MetricResult(
            id = MetricId.ILLUMINATION_UNIFORMITY,
            name = "Uniformity",
            rawValue = uniformity,
            normalizedScore = uniformityScore,
            passed = uniformity >= UNIFORMITY_THRESHOLD,
            weight = 0.10f,
            isCritical = false
        ))

        // 6. Motion Blur
        val directionality = computeMotionBlur(bitmap, irisCenter, irisRadius)
        val blurScore = ((MOTION_BLUR_THRESHOLD * 2f - directionality) / (MOTION_BLUR_THRESHOLD * 2f) * 100f).coerceIn(0f, 100f)
        metrics.add(MetricResult(
            id = MetricId.MOTION_BLUR,
            name = "Motion Blur",
            rawValue = directionality,
            normalizedScore = blurScore,
            passed = directionality <= MOTION_BLUR_THRESHOLD,
            weight = 0.15f,
            isCritical = false
        ))

        // 7. Sharpness (reuse existing SharpnessAnalyzer score)
        val normalizedSharpness = if (sharpnessThreshold > 0) {
            ((existingSharpnessScore / sharpnessThreshold) * 50f).coerceIn(0.0, 100.0).toFloat()
        } else {
            50f
        }
        metrics.add(MetricResult(
            id = MetricId.SHARPNESS,
            name = "Sharpness",
            rawValue = existingSharpnessScore.toFloat(),
            normalizedScore = normalizedSharpness,
            passed = existingSharpnessScore >= sharpnessThreshold,
            weight = 0.20f,
            isCritical = true
        ))

        // Composite score = weighted sum
        val compositeScore = metrics.sumOf { (it.normalizedScore * it.weight).toDouble() }.toFloat()

        // Overall pass = composite >= threshold AND all critical metrics pass
        val allCriticalPassed = metrics.filter { it.isCritical }.all { it.passed }
        val overallPassed = compositeScore >= COMPOSITE_PASS_THRESHOLD && allCriticalPassed

        Log.d(TAG, "ISO_QUALITY: score=${String.format("%.1f", compositeScore)} " +
                "passed=$overallPassed criticalOk=$allCriticalPassed")
        metrics.forEach { m ->
            Log.d(TAG, "ISO_QUALITY:   ${m.name}: ${String.format("%.1f", m.normalizedScore)} " +
                    "${if (m.passed) "PASS" else "FAIL"}${if (m.isCritical) " [CRITICAL]" else ""}")
        }

        return IrisQualityResult(
            overallScore = compositeScore,
            overallPassed = overallPassed,
            metrics = metrics
        )
    }

    // ========================================================================
    // Metric 1: Usable Iris Area
    // ========================================================================

    /**
     * Compute fraction of iris annulus that is not occluded by eyelids.
     * Samples points in a polar grid within the iris annulus and checks
     * if each point is above/below eyelid polylines.
     * Falls back to intensity-based scan when no landmarks available.
     *
     * @param isFrontCamera If true, uses higher intensity threshold for front camera (no flash)
     */
    private fun computeUsableIrisArea(
        bitmap: Bitmap,
        irisCenter: PointF,
        irisRadius: Float,
        upperEyelidPoints: List<PointF>?,
        lowerEyelidPoints: List<PointF>?,
        isFrontCamera: Boolean = false
    ): Float {
        val hasLandmarks = upperEyelidPoints != null && lowerEyelidPoints != null &&
                upperEyelidPoints.size >= 3 && lowerEyelidPoints.size >= 3

        if (hasLandmarks) {
            return computeUsableIrisAreaWithLandmarks(
                irisCenter, irisRadius,
                upperEyelidPoints!!, lowerEyelidPoints!!,
                bitmap.width, bitmap.height
            )
        }
        return computeUsableIrisAreaIntensity(bitmap, irisCenter, irisRadius, isFrontCamera)
    }

    private fun computeUsableIrisAreaWithLandmarks(
        irisCenter: PointF,
        irisRadius: Float,
        upperPoints: List<PointF>,
        lowerPoints: List<PointF>,
        imgWidth: Int,
        imgHeight: Int
    ): Float {
        val pupilRadius = irisRadius * 0.4f  // Approximate pupil
        val numAngles = 36
        val numRadii = 5
        var visibleCount = 0
        var totalCount = 0

        for (ai in 0 until numAngles) {
            val angle = (ai.toFloat() / numAngles) * 2f * Math.PI.toFloat()
            for (ri in 1..numRadii) {
                val r = pupilRadius + (irisRadius - pupilRadius) * (ri.toFloat() / numRadii)
                val px = irisCenter.x + r * cos(angle)
                val py = irisCenter.y + r * sin(angle)

                // Check bounds
                if (px < 0 || px >= imgWidth || py < 0 || py >= imgHeight) continue

                totalCount++

                // Check if point is between eyelids (visible)
                val upperY = interpolatePolylineY(upperPoints, px)
                val lowerY = interpolatePolylineY(lowerPoints, px)

                if (upperY != null && lowerY != null) {
                    if (py > upperY && py < lowerY) {
                        visibleCount++
                    }
                } else {
                    // If we can't determine eyelid position, assume visible
                    visibleCount++
                }
            }
        }

        return if (totalCount > 0) visibleCount.toFloat() / totalCount else 0.5f
    }

    /**
     * Interpolate Y value of a polyline at a given X.
     */
    private fun interpolatePolylineY(points: List<PointF>, x: Float): Float? {
        if (points.size < 2) return null

        // Sort points by X
        val sorted = points.sortedBy { it.x }

        // Find the two bounding points
        if (x < sorted.first().x || x > sorted.last().x) return null

        for (i in 0 until sorted.size - 1) {
            if (x >= sorted[i].x && x <= sorted[i + 1].x) {
                val t = if (sorted[i + 1].x - sorted[i].x > 0.001f)
                    (x - sorted[i].x) / (sorted[i + 1].x - sorted[i].x)
                else 0.5f
                return sorted[i].y + t * (sorted[i + 1].y - sorted[i].y)
            }
        }
        return null
    }

    /**
     * Intensity-based fallback: scan radially and detect skin-tone occluding regions.
     *
     * @param isFrontCamera If true, uses higher intensity threshold (220) for front camera
     *                      since there's no flash and iris appears brighter under ambient light.
     *                      Rear cameras with flash use lower threshold (180) for darker iris.
     */
    private fun computeUsableIrisAreaIntensity(
        bitmap: Bitmap,
        irisCenter: PointF,
        irisRadius: Float,
        isFrontCamera: Boolean = false
    ): Float {
        val pupilRadius = irisRadius * 0.4f
        val numAngles = 36
        val numRadii = 5
        var darkCount = 0  // Iris-like intensity
        var totalCount = 0

        val width = bitmap.width
        val height = bitmap.height

        // Front camera without flash: iris appears brighter, use higher threshold
        // Rear camera with flash: iris appears darker with good contrast, use lower threshold
        val intensityThreshold = if (isFrontCamera) IRIS_INTENSITY_THRESHOLD_FRONT else IRIS_INTENSITY_THRESHOLD_REAR

        for (ai in 0 until numAngles) {
            val angle = (ai.toFloat() / numAngles) * 2f * Math.PI.toFloat()
            for (ri in 1..numRadii) {
                val r = pupilRadius + (irisRadius - pupilRadius) * (ri.toFloat() / numRadii)
                val px = (irisCenter.x + r * cos(angle)).toInt()
                val py = (irisCenter.y + r * sin(angle)).toInt()

                if (px < 0 || px >= width || py < 0 || py >= height) continue

                totalCount++
                val pixel = bitmap.getPixel(px, py)
                val gray = ((pixel shr 16 and 0xFF) * 0.299 +
                        (pixel shr 8 and 0xFF) * 0.587 +
                        (pixel and 0xFF) * 0.114).toInt()

                // Iris region is typically darker than skin and not specular (< 242)
                // Threshold varies: 180 for rear (flash), 220 for front (no flash)
                if (gray < intensityThreshold) {
                    darkCount++
                }
            }
        }

        Log.d(TAG, "USABLE_IRIS_INTENSITY: isFront=$isFrontCamera threshold=$intensityThreshold " +
                "darkCount=$darkCount totalCount=$totalCount ratio=${if (totalCount > 0) darkCount.toFloat() / totalCount else 0f}")

        return if (totalCount > 0) darkCount.toFloat() / totalCount else 0.5f
    }

    // ========================================================================
    // Metric 2: Gaze Angle
    // ========================================================================

    /**
     * Compute gaze displacement: iris center relative to eye corners midpoint.
     * Returns normalized displacement (0 = centered, 1 = at edge).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun computeGazeAngle(
        bitmap: Bitmap,
        irisCenter: PointF,
        irisRadius: Float,
        upperEyelidPoints: List<PointF>?,
        lowerEyelidPoints: List<PointF>?
    ): Float {
        if (upperEyelidPoints == null || lowerEyelidPoints == null ||
            upperEyelidPoints.isEmpty() || lowerEyelidPoints.isEmpty()) {
            // No landmarks: assume on-axis with moderate score
            return 0.15f  // score ~70
        }

        // Use the widest points as inner/outer corners
        val allPoints = upperEyelidPoints + lowerEyelidPoints
        val leftMost = allPoints.minByOrNull { it.x } ?: return 0.15f
        val rightMost = allPoints.maxByOrNull { it.x } ?: return 0.15f

        val midX = (leftMost.x + rightMost.x) / 2f
        val midY = (leftMost.y + rightMost.y) / 2f
        val eyeWidth = rightMost.x - leftMost.x

        if (eyeWidth < 1f) return 0.15f

        val dx = abs(irisCenter.x - midX) / eyeWidth
        val dy = abs(irisCenter.y - midY) / eyeWidth

        return sqrt(dx * dx + dy * dy)
    }

    // ========================================================================
    // Metric 3: Pupil-to-Iris Ratio
    // ========================================================================

    /**
     * Estimate pupil radius from radial intensity profile.
     * Scans from iris center outward, finds the peak gradient (pupil-iris boundary).
     */
    private fun computePupilIrisRatio(
        bitmap: Bitmap,
        irisCenter: PointF,
        irisRadius: Float
    ): Float {
        val width = bitmap.width
        val height = bitmap.height
        val numAngles = 36
        val numRadialSteps = 20

        // Build radial intensity profile (averaged over all angles)
        val profile = FloatArray(numRadialSteps)
        val counts = IntArray(numRadialSteps)

        for (ai in 0 until numAngles) {
            val angle = (ai.toFloat() / numAngles) * 2f * Math.PI.toFloat()
            for (ri in 0 until numRadialSteps) {
                val r = irisRadius * (ri.toFloat() / numRadialSteps)
                val px = (irisCenter.x + r * cos(angle)).toInt()
                val py = (irisCenter.y + r * sin(angle)).toInt()

                if (px < 0 || px >= width || py < 0 || py >= height) continue

                val pixel = bitmap.getPixel(px, py)
                val gray = ((pixel shr 16 and 0xFF) * 0.299 +
                        (pixel shr 8 and 0xFF) * 0.587 +
                        (pixel and 0xFF) * 0.114).toFloat()

                profile[ri] += gray
                counts[ri]++
            }
        }

        // Normalize
        for (i in profile.indices) {
            if (counts[i] > 0) profile[i] = profile[i] / counts[i]
        }

        // Find peak gradient (pupil-iris boundary)
        var maxGradient = 0f
        var boundaryIdx = (numRadialSteps * 0.4).toInt()  // Default fallback

        for (i in 2 until numRadialSteps - 1) {
            val gradient = profile[i + 1] - profile[i - 1]
            if (gradient > maxGradient) {
                maxGradient = gradient
                boundaryIdx = i
            }
        }

        val pupilRadius = irisRadius * (boundaryIdx.toFloat() / numRadialSteps)
        val ratio = pupilRadius / irisRadius

        return ratio.coerceIn(0.1f, 0.9f)
    }

    // ========================================================================
    // Metric 4: Iris-Pupil Contrast
    // ========================================================================

    /**
     * Weber contrast between mean pupil zone and mean iris annulus intensity.
     */
    private fun computeIrisPupilContrast(
        bitmap: Bitmap,
        irisCenter: PointF,
        irisRadius: Float,
        pupilRatio: Float
    ): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pupilRadius = irisRadius * pupilRatio
        val numAngles = 36
        val numRadii = 5

        var pupilSum = 0f
        var pupilCount = 0
        var irisSum = 0f
        var irisCount = 0

        for (ai in 0 until numAngles) {
            val angle = (ai.toFloat() / numAngles) * 2f * Math.PI.toFloat()

            // Sample pupil zone
            for (ri in 1..numRadii) {
                val r = pupilRadius * (ri.toFloat() / numRadii) * 0.8f  // Inner 80% of pupil
                val px = (irisCenter.x + r * cos(angle)).toInt()
                val py = (irisCenter.y + r * sin(angle)).toInt()

                if (px in 0 until width && py in 0 until height) {
                    val pixel = bitmap.getPixel(px, py)
                    val gray = ((pixel shr 16 and 0xFF) * 0.299 +
                            (pixel shr 8 and 0xFF) * 0.587 +
                            (pixel and 0xFF) * 0.114).toFloat()
                    pupilSum += gray
                    pupilCount++
                }
            }

            // Sample iris annulus
            for (ri in 1..numRadii) {
                val r = pupilRadius + (irisRadius - pupilRadius) * (ri.toFloat() / numRadii)
                val px = (irisCenter.x + r * cos(angle)).toInt()
                val py = (irisCenter.y + r * sin(angle)).toInt()

                if (px in 0 until width && py in 0 until height) {
                    val pixel = bitmap.getPixel(px, py)
                    val gray = ((pixel shr 16 and 0xFF) * 0.299 +
                            (pixel shr 8 and 0xFF) * 0.587 +
                            (pixel and 0xFF) * 0.114).toFloat()
                    irisSum += gray
                    irisCount++
                }
            }
        }

        if (pupilCount == 0 || irisCount == 0) return 0.5f

        val meanPupil = pupilSum / pupilCount
        val meanIris = irisSum / irisCount

        // Weber contrast
        val contrast = abs(meanIris - meanPupil) / (meanPupil + 1f)
        return contrast.coerceIn(0f, 2f)
    }

    // ========================================================================
    // Metric 5: Illumination Uniformity
    // ========================================================================

    /**
     * Divide iris annulus into 8 angular sectors; compute uniformity.
     * Also detects specular reflections (pixels > 242).
     */
    private fun computeIlluminationUniformity(
        bitmap: Bitmap,
        irisCenter: PointF,
        irisRadius: Float,
        pupilRatio: Float
    ): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pupilRadius = irisRadius * pupilRatio
        val numSectors = 8
        val numRadii = 5
        val sectorSums = FloatArray(numSectors)
        val sectorCounts = IntArray(numSectors)
        var specularCount = 0
        var totalSamples = 0

        for (ai in 0 until numSectors * 4) { // 32 angular samples
            val angle = (ai.toFloat() / (numSectors * 4)) * 2f * Math.PI.toFloat()
            val sectorIdx = (ai / 4) % numSectors

            for (ri in 1..numRadii) {
                val r = pupilRadius + (irisRadius - pupilRadius) * (ri.toFloat() / numRadii)
                val px = (irisCenter.x + r * cos(angle)).toInt()
                val py = (irisCenter.y + r * sin(angle)).toInt()

                if (px < 0 || px >= width || py < 0 || py >= height) continue

                val pixel = bitmap.getPixel(px, py)
                val gray = ((pixel shr 16 and 0xFF) * 0.299 +
                        (pixel shr 8 and 0xFF) * 0.587 +
                        (pixel and 0xFF) * 0.114).toFloat()

                sectorSums[sectorIdx] += gray
                sectorCounts[sectorIdx]++
                totalSamples++

                if (gray > 242) specularCount++
            }
        }

        // Compute sector means
        val sectorMeans = FloatArray(numSectors)
        var validSectors = 0
        for (i in 0 until numSectors) {
            if (sectorCounts[i] > 0) {
                sectorMeans[i] = sectorSums[i] / sectorCounts[i]
                validSectors++
            }
        }

        if (validSectors < 4) return 0.5f

        val maxMean = sectorMeans.filter { it > 0 }.maxOrNull() ?: return 0.5f
        val minMean = sectorMeans.filter { it > 0 }.minOrNull() ?: return 0.5f

        // Uniformity = 1 - (max-min)/(max+eps)
        val uniformity = 1f - (maxMean - minMean) / (maxMean + 1f)

        // Penalize for specular reflections
        val specularRatio = if (totalSamples > 0) specularCount.toFloat() / totalSamples else 0f
        val specularPenalty = (specularRatio * 5f).coerceAtMost(0.3f)

        return (uniformity - specularPenalty).coerceIn(0f, 1f)
    }

    // ========================================================================
    // Metric 6: Motion Blur
    // ========================================================================

    /**
     * Gradient direction histogram (36 bins) weighted by magnitude.
     * Anisotropy = peak/mean. Low anisotropy = sharp (uniform gradient directions).
     * High anisotropy = motion blur (dominant direction).
     */
    private fun computeMotionBlur(
        bitmap: Bitmap,
        irisCenter: PointF,
        irisRadius: Float
    ): Float {
        val width = bitmap.width
        val height = bitmap.height

        // Define ROI around iris
        val roiLeft = (irisCenter.x - irisRadius).toInt().coerceIn(1, width - 2)
        val roiTop = (irisCenter.y - irisRadius).toInt().coerceIn(1, height - 2)
        val roiRight = (irisCenter.x + irisRadius).toInt().coerceIn(roiLeft + 1, width - 1)
        val roiBottom = (irisCenter.y + irisRadius).toInt().coerceIn(roiTop + 1, height - 1)

        val roiW = roiRight - roiLeft
        val roiH = roiBottom - roiTop
        if (roiW < 5 || roiH < 5) return 2.0f  // Too small, assume OK

        // Get pixels and convert to grayscale
        val pixels = IntArray(roiW * roiH)
        bitmap.getPixels(pixels, 0, roiW, roiLeft, roiTop, roiW, roiH)

        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = ((p shr 16 and 0xFF) * 0.299 +
                    (p shr 8 and 0xFF) * 0.587 +
                    (p and 0xFF) * 0.114).toInt()
        }

        // Compute gradient histogram (36 bins = 10 degree intervals)
        val numBins = 36
        val histogram = FloatArray(numBins)

        for (y in 1 until roiH - 1) {
            for (x in 1 until roiW - 1) {
                val idx = y * roiW + x

                // Sobel gradients
                val gx = -gray[idx - roiW - 1] + gray[idx - roiW + 1] +
                        -2 * gray[idx - 1] + 2 * gray[idx + 1] +
                        -gray[idx + roiW - 1] + gray[idx + roiW + 1]

                val gy = -gray[idx - roiW - 1] - 2 * gray[idx - roiW] - gray[idx - roiW + 1] +
                        gray[idx + roiW - 1] + 2 * gray[idx + roiW] + gray[idx + roiW + 1]

                val magnitude = sqrt((gx * gx + gy * gy).toFloat())
                if (magnitude < 5f) continue  // Skip near-zero gradients

                val angle = atan2(gy.toFloat(), gx.toFloat())
                val normalizedAngle = ((angle + Math.PI.toFloat()) / (2f * Math.PI.toFloat()) * numBins)
                    .toInt().coerceIn(0, numBins - 1)

                histogram[normalizedAngle] += magnitude
            }
        }

        // Compute anisotropy = peak / mean
        val nonZeroBins = histogram.filter { it > 0 }
        if (nonZeroBins.isEmpty()) return 1.0f  // No gradients = likely uniform = ok

        val peakValue = histogram.maxOrNull() ?: 0f
        val meanValue = nonZeroBins.average().toFloat()

        return if (meanValue > 0f) (peakValue / meanValue) else 1.0f
    }
}
