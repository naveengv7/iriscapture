// RECONSTRUCTED 2026-09-19 from decompiled compiled output (jadx dex -> Java).
// The original Kotlin source was lost; comments and idiom below are re-authored.
// All numeric constants, thresholds and weights were transcribed verbatim from the
// decompiled bytecode and should be reviewed against intended behavior before relying
// on this gate for research data.

package com.google.mediapipe.examples.facelandmarker

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Heuristic, model-free eye/iris presence detector.
 *
 * Runs five independent cues over a candidate eye crop and combines them into a
 * single weighted confidence score:
 *  1. Radial gradient consistency - do edges near the expected limbus point radially?
 *  2. Fast Radial Symmetry Transform (FRST) - does a circular structure vote for a
 *     center close to the expected iris center?
 *  3. Sclera detection - bright, low-saturation regions flanking the iris on both sides.
 *  4. Limbal boundary - radial gradient projection peak near the expected iris radius.
 *  5. Histogram bimodality - dark (pupil/iris) and bright (sclera) modes, dark mass centered.
 *
 * A composite score at or above [COMPOSITE_THRESHOLD] plus a geometric veto
 * (at least one of the radial/FRST cues above [GEOMETRIC_VETO_THRESHOLD]) is required
 * to report a detection.
 */
object EyePresenceDetector {
    private const val TAG = "EyePresenceDetector"

    // Composite scoring weights (sum to 1.0)
    private const val WEIGHT_RADIAL = 0.3f
    private const val WEIGHT_FRST = 0.25f
    private const val WEIGHT_SCLERA = 0.2f
    private const val WEIGHT_LIMBAL = 0.15f
    private const val WEIGHT_HISTOGRAM = 0.1f

    // Decision thresholds
    private const val COMPOSITE_THRESHOLD = 0.28f
    private const val GEOMETRIC_VETO_THRESHOLD = 0.1f

    /**
     * Detect whether a real eye is present at the expected iris location.
     *
     * Converts the bitmap to grayscale once, runs all five cues, combines them with the
     * WEIGHT_* constants and applies the composite threshold plus geometric veto.
     *
     * @param bitmap Candidate eye image (already cropped/aligned by the caller).
     * @param irisCenter Expected iris center, in [bitmap] pixel coordinates.
     * @param irisRadius Expected iris radius, in pixels.
     * @param isFrontCamera True when captured on the front camera (no flash), which relaxes
     *                      the sclera brightness/saturation gates.
     * @return [PresenceResult] with the pass/fail decision, clamped confidence, each cue score
     *         and a human readable reason.
     */
    fun detect(
        bitmap: Bitmap,
        irisCenter: PointF,
        irisRadius: Float,
        isFrontCamera: Boolean = false
    ): PresenceResult {
        val startTime = System.currentTimeMillis()
        val width = bitmap.width
        val height = bitmap.height

        if (width < 20 || height < 20 || irisRadius < 5.0f) {
            return PresenceResult(false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, "Image too small")
        }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Integer luma approximation: (77*R + 150*G + 29*B) >> 8
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (((p shr 16 and 255) * 77) + ((p shr 8 and 255) * 150) + ((p and 255) * 29)) shr 8
        }

        val radialScore = computeRadialGradientConsistency(gray, width, height, irisCenter, irisRadius)
        val frstScore = computeRadialSymmetry(gray, width, height, irisCenter, irisRadius)
        val scleraScore = detectSclera(pixels, gray, width, height, irisCenter, irisRadius, isFrontCamera)
        val limbalScore = detectLimbalBoundary(gray, width, height, irisCenter, irisRadius)
        val histogramScore = checkHistogramBimodality(gray, width, height, irisCenter, irisRadius)

        val composite = (WEIGHT_RADIAL * radialScore) +
                (WEIGHT_FRST * frstScore) +
                (WEIGHT_SCLERA * scleraScore) +
                (WEIGHT_LIMBAL * limbalScore) +
                (histogramScore * WEIGHT_HISTOGRAM)

        // Geometric veto: at least one circular-structure cue must fire.
        val geometricPass = radialScore > GEOMETRIC_VETO_THRESHOLD || frstScore > GEOMETRIC_VETO_THRESHOLD
        val eyeDetected = composite >= COMPOSITE_THRESHOLD && geometricPass

        val reason = when {
            eyeDetected -> "Eye detected (confidence=${String.format("%.2f", composite)})"
            !geometricPass -> "No circular iris structure found"
            composite < COMPOSITE_THRESHOLD -> "Low confidence (${String.format("%.2f", composite)})"
            else -> "Detection failed"
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "EYE_PRESENCE: detected=$eyeDetected composite=${String.format("%.3f", composite)}" +
                " radial=${String.format("%.3f", radialScore)}" +
                " frst=${String.format("%.3f", frstScore)}" +
                " sclera=${String.format("%.3f", scleraScore)}" +
                " limbal=${String.format("%.3f", limbalScore)}" +
                " histogram=${String.format("%.3f", histogramScore)}" +
                " ${duration}ms - $reason")

        return PresenceResult(
            eyeDetected,
            composite.coerceIn(0.0f, 1.0f),
            radialScore,
            frstScore,
            scleraScore,
            limbalScore,
            histogramScore,
            reason
        )
    }

    /**
     * Result of an eye presence check: the decision, the clamped composite confidence,
     * each individual cue score and a human readable reason string.
     */
    data class PresenceResult(
        val eyeDetected: Boolean,
        val confidence: Float,
        val radialConsistencyScore: Float,
        val frstScore: Float,
        val scleraScore: Float,
        val limbalScore: Float,
        val histogramScore: Float,
        val reason: String
    )

    // ========================================================================
    // Cue 1: Radial gradient consistency
    // ========================================================================

    /**
     * Sample Sobel gradients on three concentric rings (0.85x, 1.0x, 1.15x the expected
     * iris radius), 72 angular samples each, and measure how strongly the strong edges
     * align with the radial direction.
     *
     * Score blends the magnitude-weighted radial alignment (consistency) with the fraction
     * of samples that produced a strong edge (coverage). Returns 0 when too few edges exist.
     */
    private fun computeRadialGradientConsistency(
        gray: IntArray,
        width: Int,
        height: Int,
        irisCenter: PointF,
        irisRadius: Float
    ): Float {
        val cx = irisCenter.x
        val cy = irisCenter.y

        var weightedAlignmentSum = 0.0f
        var magnitudeSum = 0.0f
        var strongEdgeCount = 0

        val radii = floatArrayOf(0.85f * irisRadius, irisRadius, 1.15f * irisRadius)

        for (radius in radii) {
            for (i in 0 until 72) {
                val theta = i * (6.2831855f / 72)
                val px = ((cos(theta) * radius) + cx).roundToInt()
                val py = ((radius * sin(theta)) + cy).roundToInt()

                if (px < 1 || px >= width - 1 || py < 1 || py >= height - 1) continue

                val idx = (py * width) + px

                val gx = (-gray[idx - width - 1] + gray[idx - width + 1] +
                        (gray[idx - 1] * -2) + (gray[idx + 1] * 2) +
                        -gray[idx + width - 1] + gray[idx + width + 1]).toFloat()

                val gy = (-gray[idx - width - 1] - (gray[idx - width] * 2) - gray[idx - width + 1] +
                        gray[idx + width - 1] + (gray[idx + width] * 2) + gray[idx + width + 1]).toFloat()

                val mag = sqrt((gx * gx) + (gy * gy))
                if (mag >= 10.0f) {
                    strongEdgeCount++
                    val rx = cos(theta)
                    val ry = sin(theta)
                    val alignment = abs(((gx * rx) + (gy * ry)) / mag)
                    weightedAlignmentSum += alignment * mag
                    magnitudeSum += mag
                }
            }
        }

        val totalSamples = radii.size * 72
        val coverage = strongEdgeCount.toFloat() / totalSamples

        if (strongEdgeCount < 12 || coverage < 0.08f) {
            Log.d(TAG, "RADIAL: Too few edges ($strongEdgeCount/$totalSamples) → score=0")
            return 0.0f
        }

        val consistency = if (magnitudeSum > 0.0f) weightedAlignmentSum / magnitudeSum else 0.0f
        val consistencyScore = ((consistency - 0.3f) / 0.4f).coerceIn(0.0f, 1.0f)
        val coverageScore = (coverage / 0.4f).coerceIn(0.0f, 1.0f)
        val score = ((0.7f * consistencyScore) + (0.3f * coverageScore)).coerceIn(0.0f, 1.0f)

        Log.d(TAG, "RADIAL: consistency=${String.format("%.3f", consistency)}" +
                " coverage=${String.format("%.3f", coverage)}" +
                " edges=$strongEdgeCount/$totalSamples → score=${String.format("%.3f", score)}")
        return score
    }

    // ========================================================================
    // Cue 2: Fast Radial Symmetry Transform (FRST)
    // ========================================================================

    /**
     * Fast radial symmetry transform restricted to the expected iris radius.
     *
     * Every strong gradient pixel in an ROI around the expected center votes (in both
     * gradient directions) for a center one radius away, accumulating into a search
     * window. The score combines how concentrated the vote peak is (peak / mean of
     * non-zero bins) with how close that peak lands to the expected center.
     */
    private fun computeRadialSymmetry(
        gray: IntArray,
        width: Int,
        height: Int,
        irisCenter: PointF,
        irisRadius: Float
    ): Float {
        val expectedCx = irisCenter.x.roundToInt()
        val expectedCy = irisCenter.y.roundToInt()
        val r = irisRadius.roundToInt()

        val searchRadius = max(20, (irisRadius * 0.5f).roundToInt())
        val searchW = (searchRadius * 2) + 1
        val originX = (expectedCx - searchRadius).coerceIn(0, max(0, width - searchW))
        val originY = (expectedCy - searchRadius).coerceIn(0, max(0, height - searchW))

        if (searchW <= 0 || originX + searchW > width || originY + searchW > height) return 0.0f

        val votes = FloatArray(searchW * searchW)
        val counts = IntArray(searchW * searchW)

        val roiMargin = r + 10
        val roiLeft = max(1, expectedCx - roiMargin)
        val roiTop = max(1, expectedCy - roiMargin)
        val roiRight = min(width - 2, expectedCx + roiMargin)
        val roiBottom = min(height - 2, expectedCy + roiMargin)

        if (roiLeft >= roiRight || roiTop >= roiBottom) return 0.0f

        var totalVotes = 0
        for (y in roiTop..roiBottom) {
            for (x in roiLeft..roiRight) {
                val idx = (y * width) + x

                val gx = (-gray[idx - width - 1] + gray[idx - width + 1] +
                        (gray[idx - 1] * -2) + (gray[idx + 1] * 2) +
                        -gray[idx + width - 1] + gray[idx + width + 1]).toFloat()

                val gy = (-gray[idx - width - 1] - (gray[idx - width] * 2) - gray[idx - width + 1] +
                        gray[idx + width - 1] + (gray[idx + width] * 2) + gray[idx + width + 1]).toFloat()

                val mag = sqrt((gx * gx) + (gy * gy))
                if (mag < 15.0f) continue

                val dx = gx / mag
                val dy = gy / mag

                for (sign in intArrayOf(-1, 1)) {
                    val vcx = (((r * dx).roundToInt() * sign) + x) - originX
                    val vcy = (((r * dy).roundToInt() * sign) + y) - originY
                    if (vcx in 0 until searchW && vcy in 0 until searchW) {
                        val vi = (vcy * searchW) + vcx
                        votes[vi] = votes[vi] + mag
                        counts[vi] = counts[vi] + 1
                        totalVotes++
                    }
                }
            }
        }

        if (totalVotes < 20) {
            Log.d(TAG, "FRST: Too few votes ($totalVotes) → score=0")
            return 0.0f
        }

        var peakValue = 0.0f
        var peakIdx = 0
        for (i in votes.indices) {
            if (votes[i] > peakValue) {
                peakValue = votes[i]
                peakIdx = i
            }
        }

        val peakX = ((peakIdx % searchW) + originX).toFloat()
        val peakY = ((peakIdx / searchW) + originY).toFloat()
        val distFromExpected = sqrt(
            ((peakX - expectedCx) * (peakX - expectedCx)) + ((peakY - expectedCy) * (peakY - expectedCy))
        )
        val normalizedDist = distFromExpected / irisRadius

        var voteSum = 0.0f
        var nonZeroCount = 0
        for (v in votes) {
            if (v > 0.0f) {
                voteSum += v
                nonZeroCount++
            }
        }

        val meanVote = if (nonZeroCount > 0) voteSum / nonZeroCount else 1.0f
        val peakRatio = if (meanVote > 0.0f) peakValue / meanVote else 0.0f

        val concentrationScore = ((peakRatio - 1.5f) / 4.0f).coerceIn(0.0f, 1.0f)
        val proximityScore = if (normalizedDist < 0.5f) {
            1.0f
        } else {
            (1.0f - ((normalizedDist - 0.5f) * 2.0f)).coerceIn(0.0f, 1.0f)
        }
        val score = ((0.7f * concentrationScore) + (0.3f * proximityScore)).coerceIn(0.0f, 1.0f)

        Log.d(TAG, "FRST: peakRatio=${String.format("%.2f", peakRatio)}" +
                " peakAt=(${peakX.roundToInt()},${peakY.roundToInt()})" +
                " dist=${String.format("%.1f", distFromExpected)}" +
                " votes=$totalVotes → score=${String.format("%.3f", score)}")
        return score
    }

    // ========================================================================
    // Cue 3: Sclera detection
    // ========================================================================

    /**
     * Look for sclera (bright, low-saturation, non-specular pixels) in the horizontal band
     * either side of the iris, and require the flanks to be measurably brighter than the
     * central iris region.
     *
     * Score = bilateral coverage (the weaker of the two sides) + a symmetry bonus when the
     * two sides agree + a bonus proportional to the flank/center brightness contrast.
     * Returns 0 if either flank is empty or the contrast is below 30 gray levels.
     */
    private fun detectSclera(
        pixels: IntArray,
        gray: IntArray,
        width: Int,
        height: Int,
        irisCenter: PointF,
        irisRadius: Float,
        isFrontCamera: Boolean
    ): Float {
        val cx = irisCenter.x.roundToInt()
        val cy = irisCenter.y.roundToInt()

        val bandTop = max(0, (cy - (irisRadius * 0.7f)).roundToInt())
        val bandBottom = min(height - 1, (cy + (0.7f * irisRadius)).roundToInt())
        val irisLeft = max(0, (cx - (irisRadius * 1.1f)).roundToInt())
        val irisRight = min(width - 1, (cx + (1.1f * irisRadius)).roundToInt())

        var leftScleraCount = 0
        var leftTotalCount = 0
        var rightScleraCount = 0
        var rightTotalCount = 0

        // Front camera has no flash, so the sclera reads darker and slightly more saturated.
        val brightnessMin = if (isFrontCamera) 130 else 150
        val saturationMax = if (isFrontCamera) 55 else 45

        // Mean brightness of the central (iris) column band.
        var centerSum = 0L
        var centerCount = 0
        val centerLeft = max(0, (cx - (irisRadius * 0.5f)).roundToInt())
        val centerRight = min(width - 1, (cx + (irisRadius * 0.5f)).roundToInt())
        for (y in bandTop..bandBottom) {
            for (x in centerLeft..centerRight) {
                val idx = (y * width) + x
                if (idx < gray.size) {
                    centerSum += gray[idx]
                    centerCount++
                }
            }
        }
        val centerMeanBrightness = if (centerCount > 0) (centerSum / centerCount).toInt() else 128

        var flankSum = 0L
        var flankCount = 0

        // Left flank
        for (y in bandTop..bandBottom) {
            for (x in 0 until irisLeft) {
                val idx = (y * width) + x
                if (idx >= pixels.size) continue
                val pixel = pixels[idx]
                val r = (pixel shr 16) and 255
                val g = (pixel shr 8) and 255
                val b = pixel and 255
                leftTotalCount++
                flankSum += gray[idx]
                flankCount++
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val saturation = if (maxC > 0) ((maxC - minC) * 255) / maxC else 0
                if (maxC >= brightnessMin && saturation <= saturationMax && maxC < 250) {
                    leftScleraCount++
                }
            }
        }

        // Right flank
        for (y in bandTop..bandBottom) {
            for (x in irisRight + 1 until width) {
                val idx = (y * width) + x
                if (idx >= pixels.size) continue
                val pixel = pixels[idx]
                val r = (pixel shr 16) and 255
                val g = (pixel shr 8) and 255
                val b = pixel and 255
                rightTotalCount++
                flankCount++
                val maxC = max(r, max(g, b))
                flankSum += gray[idx]
                val minC = min(r, min(g, b))
                val saturation = if (maxC > 0) ((maxC - minC) * 255) / maxC else 0
                if (maxC >= brightnessMin && saturation <= saturationMax && maxC < 250) {
                    rightScleraCount++
                }
            }
        }

        if (leftTotalCount == 0 || rightTotalCount == 0) return 0.0f

        val flankMeanBrightness = if (flankCount > 0) (flankSum / flankCount).toInt() else 128
        val brightnessContrast = flankMeanBrightness - centerMeanBrightness
        if (brightnessContrast < 30) {
            Log.d(TAG, "SCLERA: Insufficient contrast (center=$centerMeanBrightness," +
                    " flanks=$flankMeanBrightness, diff=$brightnessContrast) → score=0")
            return 0.0f
        }

        val leftRatio = leftScleraCount.toFloat() / leftTotalCount
        val rightRatio = rightScleraCount.toFloat() / rightTotalCount
        val minRatio = min(leftRatio, rightRatio)
        val maxRatio = max(leftRatio, rightRatio)
        val symmetry = if (maxRatio > 0.01f) minRatio / maxRatio else 0.0f

        val bilateralScore = minRatio.coerceIn(0.0f, 0.5f) * 2.0f
        val symmetryBonus = if (symmetry > 0.3f) 0.15f else 0.0f
        val contrastBonus = ((brightnessContrast - 30.0f) / 100.0f).coerceIn(0.0f, 0.35f)
        val score = ((bilateralScore * 0.5f) + symmetryBonus + contrastBonus).coerceIn(0.0f, 1.0f)

        Log.d(TAG, "SCLERA: leftRatio=${String.format("%.3f", leftRatio)}" +
                " rightRatio=${String.format("%.3f", rightRatio)}" +
                " contrast=$brightnessContrast (center=$centerMeanBrightness" +
                " flanks=$flankMeanBrightness) → score=${String.format("%.3f", score)}")
        return score
    }

    // ========================================================================
    // Cue 4: Limbal boundary
    // ========================================================================

    /**
     * Build a radial projection of Sobel gradient magnitude from 0.4x to 1.8x the expected
     * iris radius, sampled only over the left and right limbal arcs (where the eyelids do
     * not occlude the limbus).
     *
     * The projection peak should be a sharp ring at roughly the expected radius: the score
     * combines peak-to-background ratio with a penalty for peaks far from that radius.
     */
    private fun detectLimbalBoundary(
        gray: IntArray,
        width: Int,
        height: Int,
        irisCenter: PointF,
        irisRadius: Float
    ): Float {
        val cx = irisCenter.x
        val cy = irisCenter.y

        val minR = (0.4f * irisRadius).roundToInt()
        val maxR = (1.8f * irisRadius).roundToInt().coerceAtMost((min(width, height) / 2) - 2)
        val numSteps = maxR - minR
        if (numSteps < 5) return 0.0f

        val projection = FloatArray(numSteps)
        val projectionCounts = IntArray(numSteps)

        // Two 60-degree arcs, left and right of the iris, sampled every 5 degrees.
        val arcAngles = ArrayList<Float>()
        for (i in 0 until 12) {
            arcAngles.add((((i * 5.0f) - 30.0f) * 3.1415927f) / 180.0f)
            arcAngles.add((((i * 5.0f) + 150.0f) * 3.1415927f) / 180.0f)
        }

        for (ri in 0 until numSteps) {
            val r = (minR + ri).toFloat()
            for (angle in arcAngles) {
                val px = ((cos(angle) * r) + cx).roundToInt()
                val py = ((r * sin(angle)) + cy).roundToInt()

                if (px < 1 || px >= width - 1 || py < 1 || py >= height - 1) continue

                val idx = (py * width) + px

                val gx = -gray[idx - width - 1] + gray[idx - width + 1] +
                        (gray[idx - 1] * -2) + (gray[idx + 1] * 2) +
                        -gray[idx + width - 1] + gray[idx + width + 1]

                val gy = -gray[idx - width - 1] - (gray[idx - width] * 2) - gray[idx - width + 1] +
                        gray[idx + width - 1] + (gray[idx + width] * 2) + gray[idx + width + 1]

                val magnitude = sqrt(((gx * gx) + (gy * gy)).toFloat())
                projection[ri] = projection[ri] + magnitude
                projectionCounts[ri] = projectionCounts[ri] + 1
            }
        }

        for (i in projection.indices) {
            if (projectionCounts[i] > 0) {
                projection[i] = projection[i] / projectionCounts[i]
            }
        }

        var peakValue = 0.0f
        var peakIndex = 0
        for (i in projection.indices) {
            if (projection[i] > peakValue) {
                peakValue = projection[i]
                peakIndex = i
            }
        }

        // Background mean excludes a +/-3 step neighbourhood around the peak.
        var meanSum = 0.0f
        var meanCount = 0
        for (i in projection.indices) {
            if (abs(i - peakIndex) > 3) {
                meanSum += projection[i]
                meanCount++
            }
        }

        val meanValue = if (meanCount > 0) meanSum / meanCount else 1.0f
        val peakRatio = if (meanValue > 0.1f) peakValue / meanValue else 1.0f

        val peakRadius = minR + peakIndex
        val radiusDeviation = abs(peakRadius - irisRadius) / irisRadius
        val strengthScore = ((peakRatio - 1.0f) / 3.0f).coerceIn(0.0f, 1.0f)
        val radiusPenalty = if (radiusDeviation < 0.5f) {
            1.0f
        } else {
            (1.0f - (radiusDeviation - 0.5f)).coerceIn(0.0f, 1.0f)
        }
        val score = (strengthScore * radiusPenalty).coerceIn(0.0f, 1.0f)

        Log.d(TAG, "LIMBAL: peakRatio=${String.format("%.2f", peakRatio)}" +
                " peakRadius=$peakRadius expected=${irisRadius.roundToInt()}" +
                " → score=${String.format("%.3f", score)}")
        return score
    }

    // ========================================================================
    // Cue 5: Histogram bimodality
    // ========================================================================

    /**
     * Check that the intensity histogram looks like an eye: a dark mode (pupil/iris), a
     * bright mode (sclera/specular), a wide dynamic range between the extreme peaks, and a
     * dark-pixel centroid that sits near the expected iris center.
     *
     * The histogram is smoothed with a 1-2-3-2-1 kernel; peaks must exceed 1.5% of all
     * pixels and dominate their +/-3 bin neighbourhood.
     */
    private fun checkHistogramBimodality(
        gray: IntArray,
        width: Int,
        height: Int,
        irisCenter: PointF,
        irisRadius: Float
    ): Float {
        /** One detected histogram peak: its intensity bin and its smoothed height. */
        data class Peak(val intensity: Int, val height: Float)

        val cx = irisCenter.x.roundToInt()
        val cy = irisCenter.y.roundToInt()

        val histogram = IntArray(256)
        for (g in gray) {
            val bin = g.coerceIn(0, 255)
            histogram[bin] = histogram[bin] + 1
        }

        val totalPixels = gray.size.toFloat()

        // 1-2-3-2-1 smoothing
        val smoothed = FloatArray(256)
        for (i in 2 until 254) {
            smoothed[i] = (histogram[i - 2] + (histogram[i - 1] * 2.0f) + (histogram[i] * 3.0f) +
                    (histogram[i + 1] * 2.0f) + histogram[i + 2]) / 9.0f
        }

        val minPeakHeight = 0.015f * totalPixels
        val peaks = ArrayList<Peak>()
        for (i in 5 until 251) {
            if (smoothed[i] > smoothed[i - 1] && smoothed[i] > smoothed[i + 1] &&
                smoothed[i] > minPeakHeight &&
                smoothed[i] >= smoothed[max(0, i - 3)] && smoothed[i] >= smoothed[min(255, i + 3)]
            ) {
                peaks.add(Peak(i, smoothed[i]))
            }
        }

        val darkPeaks = peaks.filter { it.intensity < 100 }
        val brightPeaks = peaks.filter { it.intensity >= 150 }
        val hasDark = darkPeaks.isNotEmpty()
        val hasBright = brightPeaks.isNotEmpty()

        val dynamicRange = if (peaks.size >= 2) {
            peaks.maxOf { it.intensity } - peaks.minOf { it.intensity }
        } else {
            0
        }

        // Centroid of the dark pixels should land near the expected iris center.
        var darkCenterX = 0.0f
        var darkCenterY = 0.0f
        var darkPixelCount = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (gray[(y * width) + x] < 100) {
                    darkCenterX += x
                    darkCenterY += y
                    darkPixelCount++
                }
            }
        }

        val centered = if (darkPixelCount > 0) {
            val meanDarkX = darkCenterX / darkPixelCount
            val meanDarkY = darkCenterY / darkPixelCount
            val dist = sqrt(
                ((meanDarkX - cx) * (meanDarkX - cx)) + ((meanDarkY - cy) * (meanDarkY - cy))
            )
            dist / (irisRadius * 2.0f) < 0.5f
        } else {
            false
        }

        var score = 0.0f
        if (hasDark) score += 0.3f
        if (hasBright) score += 0.3f
        if (dynamicRange > 60) score += 0.15f
        if (centered) score += 0.25f

        Log.d(TAG, "HISTOGRAM: peaks=${peaks.size} dark=${darkPeaks.size} bright=${brightPeaks.size}" +
                " range=$dynamicRange centered=$centered → score=${String.format("%.3f", score)}")
        return score.coerceIn(0.0f, 1.0f)
    }
}
