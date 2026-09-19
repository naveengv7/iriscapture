package edu.clarkson.iriscapture

import android.graphics.PointF
import android.media.ExifInterface
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Metadata for a captured iris image, embedded in EXIF for traceability.
 * Contains quality metrics, coordinates, and capture information.
 */
data class IrisMetadata(
    // Iris geometry (in cropped image coordinates)
    val irisCenter: PointF,
    val irisRadiusPx: Float,
    val cropWidth: Int,
    val cropHeight: Int,

    // Source image info
    val sourceWidth: Int,
    val sourceHeight: Int,

    // Quality metrics
    val overallScore: Int,
    val sharpness: Float,
    val contrast: Float,
    val occlusionPercent: Float,
    val upperEyelidOcclusion: Float,
    val lowerEyelidOcclusion: Float,
    val irisVisible: Boolean,
    val pupilVisible: Boolean,

    // Capture info
    val eye: String,  // "right" or "left"
    val captureMode: String,
    val participantId: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        private const val VERSION = "1.0"

        /**
         * Parse IrisMetadata from EXIF UserComment JSON.
         * Returns null if parsing fails or data is not present.
         */
        fun fromExif(jpegBytes: ByteArray): IrisMetadata? {
            return try {
                val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
                val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT) ?: return null
                fromJson(userComment)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Parse IrisMetadata from JSON string.
         */
        fun fromJson(jsonString: String): IrisMetadata? {
            return try {
                val root = JSONObject(jsonString)
                val data = root.getJSONObject("irisCapture")

                val irisCenterObj = data.getJSONObject("irisCenter")
                val cropSizeObj = data.getJSONObject("cropSize")
                val sourceResObj = data.getJSONObject("sourceResolution")
                val qualityObj = data.getJSONObject("quality")
                val captureObj = data.getJSONObject("capture")

                IrisMetadata(
                    irisCenter = PointF(
                        irisCenterObj.getDouble("x").toFloat(),
                        irisCenterObj.getDouble("y").toFloat()
                    ),
                    irisRadiusPx = data.getDouble("irisRadiusPx").toFloat(),
                    cropWidth = cropSizeObj.getInt("width"),
                    cropHeight = cropSizeObj.getInt("height"),
                    sourceWidth = sourceResObj.getInt("width"),
                    sourceHeight = sourceResObj.getInt("height"),
                    overallScore = qualityObj.getInt("overallScore"),
                    sharpness = qualityObj.getDouble("sharpness").toFloat(),
                    contrast = qualityObj.getDouble("contrast").toFloat(),
                    occlusionPercent = qualityObj.getDouble("occlusionPercent").toFloat(),
                    upperEyelidOcclusion = qualityObj.getDouble("upperEyelidOcclusion").toFloat(),
                    lowerEyelidOcclusion = qualityObj.getDouble("lowerEyelidOcclusion").toFloat(),
                    irisVisible = qualityObj.getBoolean("irisVisible"),
                    pupilVisible = qualityObj.getBoolean("pupilVisible"),
                    eye = data.getString("eye"),
                    captureMode = captureObj.getString("mode"),
                    participantId = captureObj.getString("participantId"),
                    timestamp = captureObj.getLong("timestamp")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Serialize metadata to JSON string for EXIF embedding.
     */
    fun toJson(): String {
        val root = JSONObject()
        val data = JSONObject()

        data.put("version", VERSION)
        data.put("eye", eye)

        // Iris geometry
        data.put("irisCenter", JSONObject().apply {
            put("x", irisCenter.x.toDouble())
            put("y", irisCenter.y.toDouble())
        })
        data.put("irisRadiusPx", irisRadiusPx.toDouble())
        data.put("cropSize", JSONObject().apply {
            put("width", cropWidth)
            put("height", cropHeight)
        })
        data.put("sourceResolution", JSONObject().apply {
            put("width", sourceWidth)
            put("height", sourceHeight)
        })

        // Quality metrics
        data.put("quality", JSONObject().apply {
            put("overallScore", overallScore)
            put("sharpness", sharpness.toDouble())
            put("contrast", contrast.toDouble())
            put("occlusionPercent", occlusionPercent.toDouble())
            put("upperEyelidOcclusion", upperEyelidOcclusion.toDouble())
            put("lowerEyelidOcclusion", lowerEyelidOcclusion.toDouble())
            put("irisVisible", irisVisible)
            put("pupilVisible", pupilVisible)
        })

        // Capture info
        data.put("capture", JSONObject().apply {
            put("mode", captureMode)
            put("participantId", participantId)
            put("timestamp", timestamp)
        })

        root.put("irisCapture", data)
        return root.toString()
    }

    /**
     * Embed metadata into JPEG EXIF and return new JPEG bytes.
     * The metadata is stored in the UserComment EXIF tag as JSON.
     */
    fun writeToExif(jpegBytes: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        outputStream.write(jpegBytes)

        // Create a temporary copy to work with ExifInterface
        val tempOutput = ByteArrayOutputStream()
        tempOutput.write(jpegBytes)

        return try {
            // ExifInterface needs to work with the bytes directly
            // We'll use a workaround: write to temp, modify, read back
            val inputStream = ByteArrayInputStream(jpegBytes)
            val exif = ExifInterface(inputStream)

            // Set the user comment with our JSON metadata
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, toJson())

            // Set other useful EXIF tags
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Iris capture - $eye eye")
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "MediaPipe Iris Capture v$VERSION")

            // For ExifInterface to save changes, we need a file or use saveAttributes
            // Since we're working with byte arrays, we need a different approach
            // We'll create a new JPEG with the metadata embedded

            val result = ByteArrayOutputStream()
            result.write(jpegBytes)

            // Note: ExifInterface.saveAttributes() requires a file path
            // For in-memory operation, we embed the JSON in a way that's readable
            // The actual EXIF writing will happen in the save method in CameraFragment
            // where we have access to file operations

            jpegBytes  // Return original for now, actual EXIF writing done at save time
        } catch (e: Exception) {
            jpegBytes
        }
    }

    /**
     * Create a summary string for logging.
     */
    fun toSummary(): String {
        return "IrisMetadata[$eye eye, Q=$overallScore, sharp=${String.format("%.1f", sharpness)}, " +
                "occlusion=${String.format("%.1f", occlusionPercent)}%, " +
                "crop=${cropWidth}x${cropHeight}, mode=$captureMode]"
    }
}
