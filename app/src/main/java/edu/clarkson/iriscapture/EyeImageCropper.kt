package edu.clarkson.iriscapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.media.ExifInterface
import android.util.Log
import java.io.ByteArrayInputStream
import kotlin.math.sqrt

/**
 * Crops eye region from full-resolution JPEG using stored iris coordinates.
 * Uses BitmapRegionDecoder for memory efficiency.
 */
object EyeImageCropper {
    private const val TAG = "EyeImageCropper"

    data class EyeCropResult(
        val croppedBitmap: Bitmap,
        val cropRect: Rect,
        val irisCenter: PointF,      // In cropped-image coordinates (post-rotation)
        val irisRadius: Float,       // In cropped-image pixel units
        val upperEyelidPoints: List<PointF>?,  // In cropped-image coordinates (post-rotation)
        val lowerEyelidPoints: List<PointF>?,  // In cropped-image coordinates (post-rotation)
        // Reconstructed from compiled output 2026-09-19
        val exifRotationDegrees: Int = 0       // EXIF orientation of the source JPEG (0/90/180/270)
    )

    /**
     * Read EXIF rotation degrees from JPEG bytes.
     * Returns 0, 90, 180, or 270.
     * Public so SharpnessAnalyzer and other modules can reuse this logic.
     */
    fun getExifRotationDegrees(jpegBytes: ByteArray): Int {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF orientation", e)
            0
        }
    }

    /**
     * Transform normalized coordinates from display space to raw JPEG pixel space.
     * EXIF degrees indicates how many degrees CW to rotate raw pixels to get display orientation.
     * Public so SharpnessAnalyzer and other modules can reuse this logic.
     */
    fun displayToRawNorm(dnx: Float, dny: Float, exifDegrees: Int): Pair<Float, Float> {
        return when (exifDegrees) {
            90 -> Pair(dny, 1f - dnx)
            180 -> Pair(1f - dnx, 1f - dny)
            270 -> Pair(1f - dny, dnx)
            else -> Pair(dnx, dny)
        }
    }

    /**
     * Rotate a bitmap by the given degrees CW, optionally mirroring it horizontally.
     * Returns the original bitmap if there is nothing to do.
     * Reconstructed from compiled output 2026-09-19 (was rotateBitmap before mirror support).
     */
    private fun transformBitmap(bitmap: Bitmap, degrees: Int, flipHorizontal: Boolean = false): Bitmap {
        if (degrees == 0 && !flipHorizontal) return bitmap
        val matrix = Matrix()
        if (degrees != 0) matrix.postRotate(degrees.toFloat())
        if (flipHorizontal) matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    /**
     * Transform a point from raw (unrotated) cropped-bitmap space to rotated (display) cropped-bitmap space.
     * @param point Point in raw cropped bitmap coordinates
     * @param rawCropW Width of the raw (unrotated) cropped bitmap
     * @param rawCropH Height of the raw (unrotated) cropped bitmap
     * @param exifDegrees EXIF rotation degrees
     */
    private fun rawCropToDisplayPoint(point: PointF, rawCropW: Int, rawCropH: Int, exifDegrees: Int): PointF {
        return when (exifDegrees) {
            90 -> PointF(rawCropH - 1f - point.y, point.x)
            180 -> PointF(rawCropW - 1f - point.x, rawCropH - 1f - point.y)
            270 -> PointF(point.y, rawCropW - 1f - point.x)
            else -> point
        }
    }

    /**
     * Crop eye region using stored normalized iris coordinates.
     * Used for center-based capture where user manually aligns eye to center.
     *
     * @param jpegBytes Full-resolution JPEG bytes
     * @param irisNormX Normalized X coordinate of iris center (0.0-1.0)
     * @param irisNormY Normalized Y coordinate of iris center (0.0-1.0)
     * @param irisNormRadius Normalized iris radius (0.0-1.0)
     * @param expandFactor Expansion factor for crop region
     * @param isFrontCamera True when the frame came from the front camera; selects the
     *        opposite rotation direction (reconstructed from compiled output 2026-09-19)
     * @return EyeCropResult with cropped bitmap (no eyelid landmarks), or null on failure
     */
    fun cropFromStoredCoordinates(
        jpegBytes: ByteArray,
        irisNormX: Float,
        irisNormY: Float,
        irisNormRadius: Float,
        expandFactor: Float = 4.0f,
        isFrontCamera: Boolean = false
    ): EyeCropResult? {
        // Get raw image dimensions without decoding (these are sensor-orientation pixels)
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, boundsOptions)
        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight
        if (rawW <= 0 || rawH <= 0) {
            Log.w(TAG, "Failed to get image dimensions")
            return null
        }

        // Read EXIF rotation to handle coordinate space transformation
        val exifDegrees = getExifRotationDegrees(jpegBytes)

        // Reconstructed from compiled output 2026-09-19: the cropped bitmap needs an extra
        // quarter turn on top of the EXIF value, and the front camera turns the other way.
        val effectiveRotation = if (isFrontCamera) ((exifDegrees - 90) + 360) % 360 else (exifDegrees + 90) % 360
        Log.d(TAG, "CROP_ROTATION: exifDegrees=$exifDegrees isFrontCamera=$isFrontCamera effectiveRotation=$effectiveRotation")

        // Input coordinates are in display space; transform to raw JPEG pixel space
        val (rawNormX, rawNormY) = displayToRawNorm(irisNormX, irisNormY, exifDegrees)

        // Display width determines the iris radius scale.
        // FIXED 2026-09-19: this previously selected the axis from exifDegrees while the
        // bitmap is rotated by effectiveRotation. Those always differ by 90 degrees, so the
        // radius was normalised against an axis the crop is never rendered in. On the live
        // path JPEG_ORIENTATION is never set, so exifDegrees was always 0 and displayWidth
        // resolved to the sensor long axis, inflating every radius by the aspect ratio
        // (612 px instead of 461 px on a 4080x3072 still). Use the rotation actually applied.
        val displayWidth = if (effectiveRotation == 90 || effectiveRotation == 270) rawH else rawW
        val irisRadius = irisNormRadius * displayWidth

        // Convert to raw pixel coordinates
        val irisCx = rawNormX * rawW
        val irisCy = rawNormY * rawH

        Log.d(TAG, "CROP_EXIF: exifDegrees=$exifDegrees rawDims=${rawW}x${rawH} " +
                "displayNorm=($irisNormX,$irisNormY) rawNorm=($rawNormX,$rawNormY) " +
                "rawPixel=($irisCx,$irisCy) irisR=$irisRadius")

        // Expand crop region in raw pixel space
        val padding = expandFactor * irisRadius
        val cropLeft = (irisCx - padding).toInt().coerceIn(0, rawW - 1)
        val cropTop = (irisCy - padding).toInt().coerceIn(0, rawH - 1)
        val cropRight = (irisCx + padding).toInt().coerceIn(cropLeft + 1, rawW)
        val cropBottom = (irisCy + padding).toInt().coerceIn(cropTop + 1, rawH)
        val cropRect = Rect(cropLeft, cropTop, cropRight, cropBottom)

        if (cropRect.width() < 10 || cropRect.height() < 10) {
            Log.w(TAG, "Crop region too small: ${cropRect.width()}x${cropRect.height()}")
            return null
        }

        // Decode only the crop region (in raw pixel space)
        val rawCroppedBitmap = try {
            val decoder = BitmapRegionDecoder.newInstance(jpegBytes, 0, jpegBytes.size, false)
            if (decoder == null) {
                Log.w(TAG, "Failed to create BitmapRegionDecoder")
                return null
            }
            val bmp = decoder.decodeRegion(cropRect, BitmapFactory.Options())
            decoder.recycle()
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "BitmapRegionDecoder failed", e)
            return null
        }

        if (rawCroppedBitmap == null) {
            Log.w(TAG, "decodeRegion returned null")
            return null
        }

        // Iris center in raw cropped bitmap coordinates
        val rawIrisCenter = PointF(irisCx - cropLeft, irisCy - cropTop)
        val rawCropW = rawCroppedBitmap.width
        val rawCropH = rawCroppedBitmap.height

        // Rotate cropped bitmap to display orientation (effectiveRotation: recovered 2026-09-19)
        val croppedBitmap = transformBitmap(rawCroppedBitmap, effectiveRotation)

        // Transform iris center to rotated coordinate space
        val croppedIrisCenter = rawCropToDisplayPoint(rawIrisCenter, rawCropW, rawCropH, effectiveRotation)

        Log.d(TAG, "CROP_STORED: cropRect=$cropRect (${rawCropW}x${rawCropH}) " +
                "rotated=${croppedBitmap.width}x${croppedBitmap.height} exif=$exifDegrees " +
                "effectiveRot=$effectiveRotation irisCenter=$croppedIrisCenter, irisR=$irisRadius " +
                "front=$isFrontCamera")

        return EyeCropResult(
            croppedBitmap = croppedBitmap,
            cropRect = cropRect,
            irisCenter = croppedIrisCenter,
            irisRadius = irisRadius,
            upperEyelidPoints = null,
            lowerEyelidPoints = null,
            exifRotationDegrees = exifDegrees
        )
    }
}
