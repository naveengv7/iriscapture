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
package edu.clarkson.iriscapture.fragment

import android.Manifest
import edu.clarkson.iriscapture.EyeImageCropper
import edu.clarkson.iriscapture.EyePresenceDetector
import edu.clarkson.iriscapture.IrisMetadata
import edu.clarkson.iriscapture.IrisQualityAssessor
import edu.clarkson.iriscapture.SharpnessAnalyzer
import org.json.JSONObject
import android.graphics.BitmapFactory
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import edu.clarkson.iriscapture.MainViewModel
import edu.clarkson.iriscapture.R
import edu.clarkson.iriscapture.databinding.FragmentCameraBinding
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

class CameraFragment : Fragment() {

    companion object {
        private const val TAG = "IrisCapture"

        // Capture Modes
        const val MODE_TELEPHOTO = "telephoto"      // 5x optical, manual alignment
        const val MODE_MAIN_8X = "main_8x"          // Main camera, 8x digital, center-based
        const val MODE_FRONT = "front"              // Front camera, max zoom, center-based

        // Zoom levels per mode
        private const val ZOOM_LEVEL_WIDE = 1.0f
        // Note: ZOOM_TELEPHOTO is superseded by dynamic telephotoZoomLevel for universal device support
        private const val ZOOM_TELEPHOTO_DEFAULT = 1.0f  // Default for physical telephoto (Pixel), overridden to 3x for zoom-based (Samsung)
        private const val ZOOM_MAIN_8X = 4.0f       // 4x digital zoom for main camera (portrait mode)
        private const val ZOOM_FRONT_MAX = 4.0f     // Max practical zoom for front camera
        private const val FRONT_DEFAULT_ZOOM = 1.25f // Default front camera zoom for preview

        // Camera Optimization Settings (based on research)
        private const val TARGET_ISO_MIN = 100       // Minimum ISO for lowest noise
        private const val TARGET_ISO_MAX = 400       // Maximum ISO to limit noise
        private const val MIN_SHUTTER_SPEED_NS = 4_000_000L   // 1/250s = 4ms = 4,000,000 ns
        private const val MAX_SHUTTER_SPEED_NS = 8_000_000L   // 1/125s = 8ms = 8,000,000 ns
        private const val FOCUS_LOCK_TIMEOUT_MS = 3000L       // Max time to wait for focus lock
        private const val FOCUS_RETRY_COUNT = 3               // Number of focus retries before proceeding
        private const val USE_SEQUENTIAL_CAPTURE = true       // Use sequential instead of burst (more compatible)

        // AF Metering region size (fraction of frame)
        private const val AF_METERING_FRACTION = 12           // 1/12 = ~8% of frame (was 1/4 = 25%)
        private const val AF_METERING_FRACTION_TELEPHOTO = 16 // 1/16 = ~6% for telephoto (more precise)

        // Sharpness Quality Gate Settings
        // Rear cameras: Higher threshold (better optics, higher resolution)
        // Front camera: Lower threshold (smaller sensor, softer lens, ~60% of rear)
        private const val SHARPNESS_THRESHOLD_REAR = 50.0     // For main and telephoto cameras
        private const val SHARPNESS_THRESHOLD_FRONT = 20.0    // For selfie camera (lower res/quality)
        private const val TARGET_QUALITY_IMAGES_PER_EYE = 5   // Number of high-quality images needed per eye
        private const val MAX_CAPTURE_ATTEMPTS_PER_EYE = 30   // Max attempts before giving up (prevents infinite loop)

        // === TELEPHOTO IRIS CAPTURE OPTIMIZATIONS ===

        // Flash timing optimization: Reduce delay to minimize pupil constriction
        // Pupil constricts within 200-300ms of bright light exposure
        private const val FLASH_STABILIZATION_DELAY_MS = 150L  // Was 1000ms, now 150ms for larger pupil
        private const val POST_FLASH_CAPTURE_DELAY_MS = 50L    // Brief delay after flash before capture

        // OIS (Optical Image Stabilization) - critical for telephoto macro
        private const val USE_OIS_FOR_TELEPHOTO = true

        // ISP bypass for maximum iris texture preservation
        private const val DISABLE_ISP_FOR_TELEPHOTO = true

        // === ORIENTATION MODE ===
        // All modes now use portrait orientation for easier handling
        private const val USE_LANDSCAPE_FOR_TELEPHOTO = false  // Portrait
        private const val USE_LANDSCAPE_FOR_MAIN = false       // Portrait (was landscape)
        private const val USE_LANDSCAPE_FOR_FRONT = false      // Portrait (was landscape)

        // === CENTER-BASED CROPPING MODE ===
        // Use center-based cropping (user aligns eye to crosshair) instead of MediaPipe detection
        // This is independent of orientation - portrait mode can still use center-based cropping
        private const val USE_CENTER_CROP_FOR_TELEPHOTO = true  // Center-based (manual alignment)
        private const val USE_CENTER_CROP_FOR_MAIN = true       // Center-based (manual alignment)
        private const val USE_CENTER_CROP_FOR_FRONT = true      // Center-based (manual alignment)

        // Minimum iris size requirement (fraction of frame height)
        // 15% means iris must fill at least 15% of the shorter frame dimension
        private const val MIN_IRIS_SIZE_FRACTION = 0.15f
    }

    // Current capture mode (from navigation args)
    private var captureMode: String = MODE_MAIN_8X

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private val viewModel: MainViewModel by activityViewModels()

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    // Camera2 variables
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var cameraId: String? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var previewSize: Size? = null
    private var isFrontCamera = false
    private var isFlashOn = false
    private var maxZoom: Float = 1f
    private var zoomRatioRange: Range<Float>? = null
    private var activeArraySize: Rect? = null
    @Volatile private var lastCropRegion: Rect? = null
    private var sensorOrientation: Int = 0
    private var currentCharacteristics: CameraCharacteristics? = null

    // Sensor control ranges (populated from camera characteristics)
    private var isoRange: Range<Int>? = null
    private var exposureTimeRange: Range<Long>? = null
    @Volatile private var lastAfState: Int = CaptureResult.CONTROL_AF_STATE_INACTIVE
    @Volatile private var lastAeState: Int = CaptureResult.CONTROL_AE_STATE_INACTIVE

    // Focus distance info (in diopters, distance = 1/diopters in meters)
    private var minFocusDistanceDiopters: Float = 0f  // Maximum diopters = minimum distance
    private var minFocusDistanceCm: Float = Float.MAX_VALUE  // Actual minimum focus distance in cm
    private var useManualFocus: Boolean = false  // Enable manual focus at minimum distance

    // OIS (Optical Image Stabilization) capability
    private var hasOisSupport: Boolean = false
    private var oisModes: IntArray? = null

    // Landscape mode for telephoto
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // Physical device orientation tracking (accelerometer-based, independent of display rotation)
    // Used for reliable JPEG_ORIENTATION calculation, especially in forced-landscape modes
    private var deviceOrientationDegrees: Int = 0
    private var orientationEventListener: OrientationEventListener? = null

    // Lens Selection
    private data class LensOption(
        val openCameraId: String,      // usually the logical camera to open
        val physicalId: String?,       // physical lens to force
        val label: String,
        val focalMm: Float?,
        val sensorArea: Int // Width * Height of active array
    )
    private var availableLenses: List<LensOption> = emptyList()
    private var selectedPhysicalId: String? = null
    private var currentCameraIndex = 0

    // Automated Capture
    private var isAutomatedCaptureRunning = false
    private var captureJob: Job? = null

    // Controls container height for quality panel positioning
    private var controlsContainerHeight = 0f

    // Camera fallback tracking
    private var isTelephotoFallback = false  // True if telephoto mode but no telephoto lens available

    // Universal Telephoto Detection
    // Supports both physical telephoto cameras (Pixel) and zoom-based lens switching (Samsung, etc.)
    private var useZoomBasedTelephoto = false  // True if using zoom to trigger telephoto lens
    private var telephotoZoomLevel = 1.0f      // Dynamic zoom level for telephoto mode
    private var telephotoDescription = "5x Optical"  // Description for UI

    // Dynamic sharpness threshold based on camera type
    private val sharpnessThreshold: Double
        get() = if (isFrontCamera) SHARPNESS_THRESHOLD_FRONT else SHARPNESS_THRESHOLD_REAR

    // Iris coordinates for post-processing (normalized 0-1, in preview/display space)
    @Volatile private var captureIrisNormX: Float = 0.5f
    @Volatile private var captureIrisNormY: Float = 0.5f
    @Volatile private var captureIrisNormRadius: Float = 0.05f

    // RAW Buffer Matching
    private val rawResultQueue = TreeMap<Long, TotalCaptureResult>()
    private val rawImageQueue = TreeMap<Long, Image>()

    // Filename Matching
    private val filenameMap = ConcurrentHashMap<Long, String>()

    // Modes
    // RAW enabled by default for maximum quality iris capture
    private var isRawMode = true
    private var isBurstMode = false

    // Quality Capture Tracking
    @Volatile private var rightEyeQualityCount = 0  // High-quality images saved for right eye
    @Volatile private var leftEyeQualityCount = 0   // High-quality images saved for left eye
    @Volatile private var rightEyeAttemptCount = 0  // Total capture attempts for right eye
    @Volatile private var leftEyeAttemptCount = 0   // Total capture attempts for left eye

    // Which eye is being captured. Set by performEyeCapture; its only reader was the
    // burst capture path, removed 2026-09-19 as unreachable. Kept as capture state.
    @Volatile private var captureIsRightEye: Boolean = true

    // Flag to prevent old ImageReader listener from saving during single capture mode
    @Volatile private var isSingleCaptureActive: Boolean = false

    // Pending RAW capture for single capture mode (saved only if quality passes)
    @Volatile private var pendingRawImage: Image? = null
    @Volatile private var pendingRawResult: TotalCaptureResult? = null
    private val pendingRawLock = Object()

    // Latest quality result for overlay display
    @Volatile private var lastQualityResult: IrisQualityAssessor.IrisQualityResult? = null

    // Reusable callback for repeating requests
    private val repeatingPreviewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastCropRegion = result.get(CaptureResult.SCALER_CROP_REGION)
        }
    }

    override fun onResume() {
        super.onResume()
        orientationEventListener?.enable()

        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
            return
        }

        // Maximize brightness
        val layoutParams = requireActivity().window.attributes
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        requireActivity().window.attributes = layoutParams

        startBackgroundThread()

        if (fragmentCameraBinding.viewFinder.isAvailable) {
            openCamera(fragmentCameraBinding.viewFinder.width, fragmentCameraBinding.viewFinder.height)
        } else {
            fragmentCameraBinding.viewFinder.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        orientationEventListener?.disable()
        stopAutomatedCapture()
        closeCamera()
        stopBackgroundThread()

        // Restore original orientation when leaving landscape modes
        val usedLandscape = when (captureMode) {
            MODE_TELEPHOTO -> USE_LANDSCAPE_FOR_TELEPHOTO
            MODE_MAIN_8X -> USE_LANDSCAPE_FOR_MAIN
            MODE_FRONT -> USE_LANDSCAPE_FOR_FRONT
            else -> false
        }
        if (usedLandscape) {
            requireActivity().requestedOrientation = originalOrientation
            Log.d(TAG, "LANDSCAPE_MODE: Restored original orientation")
        }

        super.onPause()

        // Restore brightness
        val layoutParams = requireActivity().window.attributes
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        requireActivity().window.attributes = layoutParams
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        orientationEventListener?.disable()
        orientationEventListener = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read capture mode from navigation arguments
        captureMode = arguments?.getString("capture_mode") ?: MODE_MAIN_8X
        Log.d(TAG, "CAPTURE_MODE: Starting in mode: $captureMode")

        // Set camera type based on mode
        isFrontCamera = (captureMode == MODE_FRONT)

        // Hide the MediaPipe results RecyclerView (no longer used)
        fragmentCameraBinding.recyclerviewResults.visibility = View.GONE

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Initialize physical orientation listener (accelerometer-based)
        // This is more reliable than Display.getRotation() for JPEG orientation,
        // especially when the activity forces a specific screen orientation.
        orientationEventListener = object : OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientationDegrees = orientation
            }
        }

        initBottomSheetControls()

        fragmentCameraBinding.cameraCaptureButton.setOnClickListener {
            if (!isAutomatedCaptureRunning) {
                startAutomatedCapture()
            }
        }

        fragmentCameraBinding.stopButton.setOnClickListener {
            stopAutomatedCapture()
        }

        fragmentCameraBinding.cameraSwitchButton.setBackgroundColor(Color.BLUE)
        fragmentCameraBinding.cameraSwitchButton.setOnClickListener {
            switchCamera()
        }

        fragmentCameraBinding.flashButton.setBackgroundColor(Color.BLUE)
        fragmentCameraBinding.flashButton.setOnClickListener {
            toggleFlash()
        }

        fragmentCameraBinding.btnRaw.setOnClickListener {
            isRawMode = !isRawMode
            if (isRawMode) {
                isBurstMode = false
                fragmentCameraBinding.btnBurst.setBackgroundColor(Color.TRANSPARENT)
                fragmentCameraBinding.btnRaw.setBackgroundColor(Color.BLUE)
            } else {
                fragmentCameraBinding.btnRaw.setBackgroundColor(Color.TRANSPARENT)
            }
            switchCameraInternal()
        }

        // NOTE (2026-09-19): this "B" button is hidden (setupModeUI sets it to View.GONE) and
        // isBurstMode is never read by the capture pipeline, so it only toggles its own
        // highlight. The burst / focus-bracket capture path it used to imply was removed as
        // dead code; the live path is takeSingleCapture + processAndCropCenterBased.
        fragmentCameraBinding.btnBurst.setOnClickListener {
            isBurstMode = !isBurstMode
            if (isBurstMode) {
                isRawMode = false
                fragmentCameraBinding.btnRaw.setBackgroundColor(Color.TRANSPARENT)
                fragmentCameraBinding.btnBurst.setBackgroundColor(Color.BLUE)
            } else {
                fragmentCameraBinding.btnBurst.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        // Manual Focus toggle (telephoto only) - focuses at minimum distance for largest iris
        fragmentCameraBinding.btnManualFocus.setOnClickListener {
            useManualFocus = !useManualFocus
            if (useManualFocus) {
                fragmentCameraBinding.btnManualFocus.setBackgroundColor(Color.parseColor("#FF5722"))
                val distanceInfo = if (minFocusDistanceCm < Float.MAX_VALUE) {
                    "at ${minFocusDistanceCm.toInt()}cm"
                } else {
                    "(distance unknown)"
                }
                showStatus("Manual Focus ON $distanceInfo")
                Log.d(TAG, "MANUAL_FOCUS: Enabled - will focus at $minFocusDistanceDiopters diopters (${minFocusDistanceCm}cm)")
            } else {
                fragmentCameraBinding.btnManualFocus.setBackgroundColor(Color.TRANSPARENT)
                showStatus("Auto Focus ON")
                Log.d(TAG, "MANUAL_FOCUS: Disabled - using auto focus")
            }
        }

        fragmentCameraBinding.backButton.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_camera_to_mode_selection)
        }

        // Setup mode-specific UI
        setupModeUI()
    }

    private fun setupModeUI() {
        // Hide elements common to all modes
        fragmentCameraBinding.btnVoiceCapture.visibility = View.GONE  // Remove voice button
        fragmentCameraBinding.flashButton.visibility = View.GONE      // Remove flash button
        fragmentCameraBinding.cameraSwitchButton.visibility = View.GONE  // Remove camera switch
        fragmentCameraBinding.recyclerviewResults.visibility = View.GONE  // Hide MediaPipe text outputs
        fragmentCameraBinding.bottomSheetLayout.root.visibility = View.GONE  // Hide bottom sheet
        fragmentCameraBinding.btnRaw.visibility = View.GONE  // Hide RAW button
        fragmentCameraBinding.btnBurst.visibility = View.GONE  // Hide Burst button
        fragmentCameraBinding.btnManualFocus.visibility = View.GONE  // Hide MF button by default

        when (captureMode) {
            MODE_TELEPHOTO -> {
                // Title will be updated after camera setup when we know the actual telephoto capability
                fragmentCameraBinding.tvModeTitle.text = "TELEPHOTO MODE (detecting...)"
                fragmentCameraBinding.tvModeTitle.setTextColor(Color.parseColor("#4CAF50"))

                // Check orientation mode
                if (USE_LANDSCAPE_FOR_TELEPHOTO) {
                    originalOrientation = requireActivity().requestedOrientation
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    Log.d(TAG, "LANDSCAPE_MODE: Locked to landscape for telephoto iris capture")
                }

                // Center-based cropping: show iris target for manual alignment
                if (USE_CENTER_CROP_FOR_TELEPHOTO) {
                    fragmentCameraBinding.tvModeInstructions.text =
                        "🔬 TELEPHOTO SINGLE-EYE MODE\n\n" +
                        "• Hold phone PORTRAIT (vertical)\n" +
                        "• Position ONE eye in the green circle\n" +
                        "• Keep ~30cm distance (arm's length)\n" +
                        "• Tap CAPTURE when size indicator shows ✓"

                    fragmentCameraBinding.overlay.showIrisTarget = true
                    fragmentCameraBinding.overlay.showCenteringGuide = false
                    fragmentCameraBinding.overlay.minIrisTargetRadius = MIN_IRIS_SIZE_FRACTION
                    fragmentCameraBinding.overlay.isTargetingRightEye = true  // Default to right eye
                    fragmentCameraBinding.overlay.invalidate()
                } else {
                    fragmentCameraBinding.tvModeInstructions.text =
                        "📷 5x OPTICAL ZOOM MODE\n\n" +
                        "• Hold phone PORTRAIT (vertical)\n" +
                        "• Keep ~30cm distance (arm's length)\n" +
                        "• Align your eye to the crosshair (+)\n" +
                        "• Tap CAPTURE when steady"
                    fragmentCameraBinding.overlay.showCenteringGuide = true
                    fragmentCameraBinding.overlay.showIrisTarget = false
                    fragmentCameraBinding.overlay.isTargetingRightEye = true  // Default to right eye
                    fragmentCameraBinding.overlay.invalidate()
                }

                // Show Manual Focus toggle for telephoto mode
                fragmentCameraBinding.btnManualFocus.visibility = View.VISIBLE
            }
            MODE_MAIN_8X -> {
                fragmentCameraBinding.tvModeTitle.text = "MAIN CAMERA (Close-Up)"
                fragmentCameraBinding.tvModeTitle.setTextColor(Color.parseColor("#FF9800"))

                // Check orientation and cropping mode
                if (USE_LANDSCAPE_FOR_MAIN) {
                    originalOrientation = requireActivity().requestedOrientation
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    Log.d(TAG, "LANDSCAPE_MODE: Locked to landscape for main camera iris capture")
                }

                // Center-based cropping: show iris target for manual alignment
                if (USE_CENTER_CROP_FOR_MAIN) {
                    fragmentCameraBinding.tvModeInstructions.text =
                        "🔬 CLOSE-UP SINGLE-EYE MODE\n\n" +
                        "• Hold phone PORTRAIT (vertical)\n" +
                        "• Position ONE eye in the green circle\n" +
                        "• Move VERY CLOSE (~10cm) for maximum detail\n" +
                        "• Tap CAPTURE when size indicator shows ✓"

                    fragmentCameraBinding.overlay.showIrisTarget = true
                    fragmentCameraBinding.overlay.showCenteringGuide = false
                    fragmentCameraBinding.overlay.minIrisTargetRadius = MIN_IRIS_SIZE_FRACTION
                    fragmentCameraBinding.overlay.isTargetingRightEye = true  // Default to right eye
                    fragmentCameraBinding.overlay.invalidate()
                } else {
                    fragmentCameraBinding.tvModeInstructions.text =
                        "Auto-detection enabled. Position your face in frame.\n" +
                        "Tap CAPTURE to start automated eye capture sequence."
                    fragmentCameraBinding.overlay.showCenteringGuide = false
                    fragmentCameraBinding.overlay.isTargetingRightEye = true  // Default to right eye
                    fragmentCameraBinding.overlay.invalidate()
                }
            }
            MODE_FRONT -> {
                fragmentCameraBinding.tvModeTitle.text = "FRONT CAMERA (Self-Capture)"
                fragmentCameraBinding.tvModeTitle.setTextColor(Color.parseColor("#9C27B0"))

                // Check orientation mode
                if (USE_LANDSCAPE_FOR_FRONT) {
                    originalOrientation = requireActivity().requestedOrientation
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    Log.d(TAG, "LANDSCAPE_MODE: Locked to landscape for front camera iris capture")
                }

                // Center-based cropping: show iris target for manual alignment
                if (USE_CENTER_CROP_FOR_FRONT) {
                    fragmentCameraBinding.tvModeInstructions.text =
                        "🤳 SELFIE SINGLE-EYE MODE\n\n" +
                        "• Hold phone PORTRAIT (vertical)\n" +
                        "• Position ONE eye in the green circle\n" +
                        "• Move close for maximum iris detail\n" +
                        "• Tap CAPTURE when size indicator shows ✓"

                    fragmentCameraBinding.overlay.showIrisTarget = true
                    fragmentCameraBinding.overlay.showCenteringGuide = false
                    fragmentCameraBinding.overlay.minIrisTargetRadius = MIN_IRIS_SIZE_FRACTION
                    fragmentCameraBinding.overlay.isTargetingRightEye = true  // Default to right eye
                    fragmentCameraBinding.overlay.invalidate()
                } else {
                    fragmentCameraBinding.tvModeInstructions.text =
                        "Self-capture mode. Position your face in frame.\n" +
                        "Tap CAPTURE to start automated eye capture sequence."
                    fragmentCameraBinding.overlay.showCenteringGuide = false
                    fragmentCameraBinding.overlay.isTargetingRightEye = true  // Default to right eye
                    fragmentCameraBinding.overlay.invalidate()
                }
            }
        }

        // Measure controls container height after layout for quality panel positioning
        fragmentCameraBinding.controlsContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    controlsContainerHeight = fragmentCameraBinding.controlsContainer.height.toFloat()
                    Log.d(TAG, "LAYOUT: Controls container height measured: $controlsContainerHeight px")
                    // Remove listener after first measurement
                    fragmentCameraBinding.controlsContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        )
    }


    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            adjustAspectRatio(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        cameraManager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        logAllCameras()
        logLogicalBackPhysicals()
        setUpCameraOutputs(width, height)
        adjustAspectRatio(width, height)
        try {
            if (!PermissionsFragment.hasPermissions(requireContext())) return
            if (cameraId == null) {
                Log.e(TAG, "No suitable camera found")
                return
            }
            cameraManager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    
                    createCameraPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private fun logAllCameras() {
        try {
            for (id in cameraManager.cameraIdList) {
                val c = cameraManager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString()
                val phys = if (android.os.Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.joinToString() else "n/a"
                Log.d(TAG, "camId=$id facing=$facing focal=[$focal] caps=[$caps] physicalIds=[$phys]")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging cameras", e)
        }
    }

    private fun logLogicalBackPhysicals() {
        try {
            for (id in cameraManager.cameraIdList) {
                val c = cameraManager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val isLogical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                if (!isLogical || android.os.Build.VERSION.SDK_INT < 28) continue

                Log.d(TAG, "LOGICAL_BACK=$id physicalIds=${c.physicalCameraIds.joinToString()}")

                for (pid in c.physicalCameraIds) {
                    val pc = cameraManager.getCameraCharacteristics(pid)
                    val focal = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
                    val active = pc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    Log.d(TAG, "  PID=$pid focal=[$focal] active=$active")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "logLogicalBackPhysicals failed", e)
        }
    }

    private fun switchCamera() {
        isFrontCamera = !isFrontCamera
        currentCameraIndex = 0
        switchCameraInternal()
    }

    private fun switchCameraInternal() {
        closeCamera()
        if (fragmentCameraBinding.viewFinder.isAvailable) {
            openCamera(fragmentCameraBinding.viewFinder.width, fragmentCameraBinding.viewFinder.height)
        }
    }

    private fun setUpCameraOutputs(width: Int, height: Int) {
        try {
            cameraId = null
            currentCharacteristics = null

            val targetFacing = if (isFrontCamera)
                CameraCharacteristics.LENS_FACING_FRONT
            else
                CameraCharacteristics.LENS_FACING_BACK

            availableLenses = queryLensesForFacing(targetFacing)
            
            availableLenses.forEachIndexed { i, l ->
                Log.d(TAG, "LENS[$i] open=${l.openCameraId} phys=${l.physicalId} focal=${l.focalMm} area=${l.sensorArea} label=${l.label}")
            }

            if (availableLenses.isEmpty()) {
                Log.e(TAG, "No cameras found for facing: $targetFacing")
                return
            }

            var bestIndex = 0

            if (isFrontCamera) {
                // Front: Prefer Narrowest FOV (largest focal length) to avoid ultra-wide
                bestIndex = pickFrontNormalIndex()
                Log.d(TAG, "FRONT_CAMERA: Selected lens index=$bestIndex")
            } else {
                // Back camera selection based on capture mode
                when (captureMode) {
                    MODE_TELEPHOTO -> {
                        // Universal Telephoto Detection Strategy:
                        // 1. Try physical telephoto lens (Pixel, OnePlus, etc.) - focal > 12mm
                        // 2. Fallback to zoom-based switching (Samsung, Xiaomi, etc.) - use main camera with 3x+ zoom

                        // Step 1: Look for physical telephoto lens
                        val telephotoIndex = availableLenses.indices.find {
                            val focal = availableLenses[it].focalMm
                            focal != null && focal > 12f  // Telephoto typically 15-20mm
                        }

                        if (telephotoIndex != null) {
                            // Physical telephoto found (Pixel-style)
                            bestIndex = telephotoIndex
                            isTelephotoFallback = false
                            useZoomBasedTelephoto = false
                            telephotoZoomLevel = 1.0f  // Use 1x zoom on physical telephoto
                            val focalMm = availableLenses[telephotoIndex].focalMm ?: 0f
                            // Estimate optical zoom based on focal length (main camera ~6-7mm)
                            val estimatedZoom = if (focalMm > 0) (focalMm / 6.5f).coerceIn(2f, 10f) else 5f
                            telephotoDescription = "${estimatedZoom.toInt()}x Optical"
                            Log.d(TAG, "TELEPHOTO_MODE: Physical telephoto found - focal=${focalMm}mm, ~${estimatedZoom.toInt()}x optical")
                        } else {
                            // Step 2: No physical telephoto - check for zoom-based switching
                            // Use main camera (largest sensor) and high zoom to trigger lens switch
                            Log.d(TAG, "TELEPHOTO_MODE: No physical telephoto, checking zoom-based switching...")

                            // Select main camera (largest sensor area or focal 5-10mm)
                            val mainCameraIndex = availableLenses.indices.find {
                                val focal = availableLenses[it].focalMm
                                focal != null && focal > 5f && focal < 10f
                            } ?: availableLenses.indices.maxByOrNull { availableLenses[it].sensorArea } ?: 0

                            bestIndex = mainCameraIndex

                            // We'll check zoom capability after opening camera
                            // For now, mark as potential zoom-based telephoto
                            useZoomBasedTelephoto = true
                            isTelephotoFallback = false  // Not a fallback, it's zoom-based switching
                            telephotoZoomLevel = 3.0f    // Default 3x, will be adjusted based on device capability
                            telephotoDescription = "3x Zoom"

                            Log.d(TAG, "TELEPHOTO_MODE: Using zoom-based telephoto on main camera (index=$mainCameraIndex)")
                        }
                    }
                    MODE_MAIN_8X -> {
                        // Main camera: Select lens with focal 5-10mm (typical main camera)
                        val mainCameraIndex = availableLenses.indices.find {
                            val focal = availableLenses[it].focalMm
                            focal != null && focal > 5f && focal < 10f
                        }
                        if (mainCameraIndex != null) {
                            bestIndex = mainCameraIndex
                            Log.d(TAG, "MAIN_8X_MODE: Selected lens with focal=${availableLenses[mainCameraIndex].focalMm}mm")
                        } else {
                            // Fallback: use camera ID 0 or max sensor area
                            val id0Index = availableLenses.indexOfFirst { it.openCameraId == "0" && it.physicalId == null }
                            if (id0Index >= 0) {
                                bestIndex = id0Index
                            } else {
                                bestIndex = availableLenses.indices.maxByOrNull { availableLenses[it].sensorArea } ?: 0
                            }
                        }
                    }
                    else -> {
                        // Default: main camera
                        val mainCameraIndex = availableLenses.indices.find {
                            val focal = availableLenses[it].focalMm
                            focal != null && focal > 5f && focal < 10f
                        }
                        bestIndex = mainCameraIndex ?: 0
                    }
                }
            }
            
            Log.d(TAG, "CHOSEN lens index=$bestIndex => ${availableLenses[bestIndex].label}")

            // Note: Fallback warning is now shown after zoom capability is checked (below)
            // This allows zoom-based telephoto to work on devices like Samsung

            currentCameraIndex = bestIndex
            val opt = availableLenses[currentCameraIndex]
            cameraId = opt.openCameraId
            selectedPhysicalId = opt.physicalId

            val characteristicsId = selectedPhysicalId ?: cameraId!!
            currentCharacteristics = cameraManager.getCameraCharacteristics(characteristicsId)

            if (cameraId != null && currentCharacteristics != null) {
                val characteristics = currentCharacteristics!!
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                zoomRatioRange = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else null

                maxZoom = zoomRatioRange?.upper ?: characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                // Get ISO and exposure time ranges for manual control
                isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                Log.d(TAG, "SENSOR_CONTROL: ISO range=$isoRange, Exposure range=$exposureTimeRange")

                // Log optimal settings we'll use
                val targetIso = isoRange?.let {
                    TARGET_ISO_MIN.coerceIn(it.lower, it.upper.coerceAtMost(TARGET_ISO_MAX))
                } ?: TARGET_ISO_MIN
                Log.d(TAG, "SENSOR_CONTROL: Target ISO=$targetIso (low noise), Target shutter=${MIN_SHUTTER_SPEED_NS/1_000_000}ms-${MAX_SHUTTER_SPEED_NS/1_000_000}ms")

                // Get minimum focus distance (in diopters: distance = 1/diopters in meters)
                // Store as class members for use in capture functions
                minFocusDistanceDiopters = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                minFocusDistanceCm = if (minFocusDistanceDiopters > 0) (100f / minFocusDistanceDiopters) else Float.MAX_VALUE
                val hyperfocalDiopters = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f

                Log.d(TAG, "CAMERA_INIT: cameraId=$cameraId maxZoom=${maxZoom}x zoomRange=$zoomRatioRange")
                Log.d(TAG, "CAMERA_INIT: activeArray=$activeArraySize sensorOrientation=$sensorOrientation")
                Log.d(TAG, "CAMERA_INIT: captureMode=$captureMode (zoom will be mode-specific)")

                // Finalize telephoto settings based on actual device capability
                if (captureMode == MODE_TELEPHOTO) {
                    if (useZoomBasedTelephoto) {
                        // Zoom-based telephoto (Samsung, Xiaomi, etc.)
                        if (maxZoom >= 3.0f) {
                            // Device supports enough zoom for telephoto lens switching
                            // Most devices switch to telephoto at 2x-3x zoom
                            telephotoZoomLevel = 3.0f.coerceAtMost(maxZoom)
                            telephotoDescription = "${telephotoZoomLevel.toInt()}x Zoom"
                            isTelephotoFallback = false
                            Log.d(TAG, "TELEPHOTO_MODE: Zoom-based telephoto enabled at ${telephotoZoomLevel}x (maxZoom=${maxZoom}x)")

                            // Show info toast and update title
                            activity?.runOnUiThread {
                                showZoomBasedTelephotoInfo()
                            }
                        } else {
                            // Device doesn't support enough zoom - true fallback
                            isTelephotoFallback = true
                            useZoomBasedTelephoto = false
                            telephotoZoomLevel = maxZoom
                            telephotoDescription = "No Telephoto"
                            Log.w(TAG, "TELEPHOTO_MODE: Insufficient zoom (${maxZoom}x < 3x), true fallback mode")

                            activity?.runOnUiThread {
                                val fallbackFocal = availableLenses.getOrNull(currentCameraIndex)?.focalMm
                                    ?.let { String.format("%.1fmm", it) } ?: "unknown"
                                showTelephotoFallbackWarning(fallbackFocal)
                            }
                        }
                    } else {
                        // Physical telephoto lens (Pixel, etc.) - update title with actual description
                        activity?.runOnUiThread {
                            if (_fragmentCameraBinding != null && isAdded) {
                                fragmentCameraBinding.tvModeTitle.text = "TELEPHOTO MODE ($telephotoDescription)"
                                Log.d(TAG, "TELEPHOTO_MODE: Physical telephoto active - $telephotoDescription")
                            }
                        }
                    }
                }
                Log.d(TAG, "FOCUS_CAPABILITY: minFocusDiopters=$minFocusDistanceDiopters (${minFocusDistanceCm}cm) hyperfocal=$hyperfocalDiopters")

                // Check for macro/close-focus capability
                if (minFocusDistanceCm < 10f) {
                    Log.d(TAG, "FOCUS_CAPABILITY: MACRO MODE AVAILABLE! Can focus as close as ${minFocusDistanceCm}cm")
                } else if (captureMode == MODE_TELEPHOTO) {
                    Log.d(TAG, "FOCUS_CAPABILITY: TELEPHOTO minimum focus distance: ${minFocusDistanceCm}cm")
                }

                // Check OIS (Optical Image Stabilization) capability - critical for telephoto macro
                oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                hasOisSupport = oisModes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                Log.d(TAG, "OIS_CAPABILITY: available=$hasOisSupport modes=${oisModes?.joinToString()}")
                if (hasOisSupport && captureMode == MODE_TELEPHOTO) {
                    Log.d(TAG, "OIS_CAPABILITY: OIS ENABLED for telephoto - will stabilize for iris capture")
                }

                if (map != null) {
                    if (isRawMode) {
                        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                        Log.d(TAG, "RAW sizes for camId=$cameraId: ${rawSizes?.joinToString()}")
                    }

                    previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                        width, height)

                    val largest = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                    Log.d(TAG, "RESOLUTION_ANALYSIS: JPEG sizes available: ${map.getOutputSizes(ImageFormat.JPEG)?.joinToString()}")
                    Log.d(TAG, "RESOLUTION_ANALYSIS: Largest JPEG: $largest")
                    if (largest != null) {
                        imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 15)
                        imageReader?.setOnImageAvailableListener({ reader ->
                            backgroundExecutor.execute {
                                val image = reader.acquireNextImage() ?: return@execute
                                val timestamp = image.timestamp
                                Log.d(TAG, "IMAGE_RECEIVED: timestamp=$timestamp mapKeys=${filenameMap.keys.take(5)}")
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                image.close()

                                val filename = filenameMap.remove(timestamp)
                                Log.d(TAG, "IMAGE_RECEIVED: filename=$filename bytes=${bytes.size}")

                                if (isSingleCaptureActive) {
                                    // Skip saving - single capture mode handles its own saving
                                    Log.d(TAG, "IMAGE_RECEIVED: Skipping save (single capture mode active)")
                                } else if (filename != null) {
                                    saveImage(bytes, filename)
                                } else {
                                    saveImage(bytes, "pic_${System.currentTimeMillis()}.jpg")
                                }
                            }
                        }, backgroundHandler)
                    }

                    if (isRawMode) {
                        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                        Log.d(TAG, "RESOLUTION_ANALYSIS: RAW sizes available: ${rawSizes?.joinToString()}")
                        if (rawSizes != null && rawSizes.isNotEmpty()) {
                            val largestRaw = rawSizes.maxByOrNull { it.width * it.height }!!
                            Log.d(TAG, "RESOLUTION_ANALYSIS: Largest RAW: $largestRaw (${largestRaw.width * largestRaw.height / 1_000_000.0}MP)")
                            rawImageReader = ImageReader.newInstance(largestRaw.width, largestRaw.height, ImageFormat.RAW_SENSOR, 10)
                            rawImageReader?.setOnImageAvailableListener({ reader ->
                                backgroundExecutor.execute {
                                    val image = reader.acquireNextImage() ?: return@execute
                                    synchronized(rawImageQueue) {
                                        rawImageQueue[image.timestamp] = image
                                        checkAndSaveMatchedRaw()
                                    }
                                }
                            }, backgroundHandler)
                        } else {
                            activity?.runOnUiThread {
                                Toast.makeText(requireContext(), "RAW not supported on this lens", Toast.LENGTH_SHORT).show()
                            }
                            isRawMode = false
                        }
                    }
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun pickFrontNormalIndex(): Int {
        if (availableLenses.isEmpty()) return 0

        fun hfovRad(opt: LensOption): Double {
            val id = opt.physicalId ?: opt.openCameraId
            val c = try {
                cameraManager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                return Double.POSITIVE_INFINITY
            }

            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()?.toDouble() ?: return Double.POSITIVE_INFINITY

            val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return Double.POSITIVE_INFINITY

            return 2.0 * atan((phys.width.toDouble() / 2.0) / focal)
        }

        val indices = availableLenses.indices.toList()
        val physIdx = indices.filter { availableLenses[it].physicalId != null }
        val pool = if (physIdx.isNotEmpty()) physIdx else indices

        return pool.minByOrNull { hfovRad(availableLenses[it]) } ?: 0
    }

    private fun chooseOptimalSize(choices: Array<Size>, textureViewWidth: Int, textureViewHeight: Int): Size {
        return choices.sortedByDescending { it.width * it.height }.first()
    }

    private fun adjustAspectRatio(viewWidth: Int, viewHeight: Int) {
        if (previewSize == null) return

        val bufferWidth = previewSize!!.height
        val bufferHeight = previewSize!!.width

        activity?.runOnUiThread {
            val params = fragmentCameraBinding.viewFinder.layoutParams
            val overlayParams = fragmentCameraBinding.overlay.layoutParams

            val ratio = bufferWidth.toFloat() / bufferHeight.toFloat()

            if (viewWidth < viewHeight * ratio) {
                params.height = viewHeight
                params.width = (viewHeight * ratio).toInt()
            } else {
                params.width = viewWidth
                params.height = (viewWidth / ratio).toInt()
            }

            fragmentCameraBinding.viewFinder.layoutParams = params
            overlayParams.width = params.width
            overlayParams.height = params.height
            fragmentCameraBinding.overlay.layoutParams = overlayParams

            if (isFrontCamera) {
                val matrix = Matrix()
                matrix.setScale(1f, 1f, params.width / 2f, params.height / 2f)
                fragmentCameraBinding.viewFinder.setTransform(matrix)
            } else {
                fragmentCameraBinding.viewFinder.setTransform(null)
            }
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = fragmentCameraBinding.viewFinder.surfaceTexture!!

            if (previewSize != null) {
                texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
            } else {
                texture.setDefaultBufferSize(fragmentCameraBinding.viewFinder.width, fragmentCameraBinding.viewFinder.height)
            }

            val surface = Surface(texture)

            val usePhysical = (android.os.Build.VERSION.SDK_INT >= 28 && selectedPhysicalId != null)

            if (usePhysical) {
                val pid = selectedPhysicalId!!
                val outConfigs = mutableListOf<OutputConfiguration>()

                fun cfg(s: Surface) = OutputConfiguration(s).apply { setPhysicalCameraId(pid) }

                outConfigs.add(cfg(surface))
                imageReader?.surface?.let { outConfigs.add(cfg(it)) }
                rawImageReader?.surface?.let { outConfigs.add(cfg(it)) }

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outConfigs,
                    Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return
                            captureSession = session
                            try {
                                previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                previewRequestBuilder!!.addTarget(surface)

                                previewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                                // Enable OIS for telephoto preview - stabilizes viewfinder for precise alignment
                                if (USE_OIS_FOR_TELEPHOTO && hasOisSupport && captureMode == MODE_TELEPHOTO) {
                                    previewRequestBuilder!!.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                                    Log.d(TAG, "PREVIEW_OIS: Enabled optical stabilization for telephoto preview")
                                }

                                // Set preview zoom based on mode
                                val startZoom = when {
                                    isFrontCamera -> FRONT_DEFAULT_ZOOM
                                    captureMode == MODE_TELEPHOTO -> telephotoZoomLevel  // Dynamic: 1x for physical, 3x+ for zoom-based
                                    captureMode == MODE_MAIN_8X -> ZOOM_MAIN_8X
                                    else -> ZOOM_LEVEL_WIDE
                                }
                                Log.d(TAG, "PREVIEW_ZOOM: Setting initial zoom=$startZoom for mode=$captureMode (telephotoZoom=$telephotoZoomLevel)")
                                setZoom(startZoom)
                                startAnalysis()
                                session.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
                            } catch (e: CameraAccessException) { e.printStackTrace() }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }
                )
                cameraDevice!!.createCaptureSession(sessionConfig)
            } else {
                val targets = mutableListOf(surface)
                imageReader?.surface?.let { targets.add(it) }
                rawImageReader?.surface?.let { targets.add(it) }

                previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewRequestBuilder!!.addTarget(surface)

                cameraDevice!!.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            previewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                            // Enable OIS for telephoto preview - stabilizes viewfinder for precise alignment
                            if (USE_OIS_FOR_TELEPHOTO && hasOisSupport && captureMode == MODE_TELEPHOTO) {
                                previewRequestBuilder!!.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                                Log.d(TAG, "PREVIEW_OIS: Enabled optical stabilization for telephoto preview")
                            }

                            // Set preview zoom based on mode
                            val startZoom = when {
                                isFrontCamera -> FRONT_DEFAULT_ZOOM
                                captureMode == MODE_TELEPHOTO -> telephotoZoomLevel  // Dynamic: 1x for physical, 3x+ for zoom-based
                                captureMode == MODE_MAIN_8X -> ZOOM_MAIN_8X
                                else -> ZOOM_LEVEL_WIDE
                            }
                            Log.d(TAG, "PREVIEW_ZOOM: Setting initial zoom=$startZoom for mode=$captureMode (telephotoZoom=$telephotoZoomLevel)")
                            setZoom(startZoom)
                            startAnalysis()
                            session.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
                        } catch (e: CameraAccessException) { e.printStackTrace() }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, null)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closeCamera() {
        stopAnalysis()
        clearPendingRaw()  // Release any pending RAW image before closing
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        rawImageReader?.close()
        rawImageReader = null
    }

    // Analysis functions are no-ops since MediaPipe was removed
    private fun startAnalysis() {
        // No-op: MediaPipe removed, using center-based capture
    }

    private fun stopAnalysis() {
        // No-op: MediaPipe removed
    }

    private fun toggleFlash() {
        isFlashOn = !isFlashOn
        try {
            if (isFlashOn) {
                previewRequestBuilder?.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                previewRequestBuilder?.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun setZoom(zoomLevel: Float, centerX: Float? = null, centerY: Float? = null) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            val z = zoomLevel.coerceIn(1f, maxZoom)
            Log.d(TAG, "ZOOM_DEBUG: setZoom called with level=$zoomLevel coerced=$z centerX=$centerX centerY=$centerY maxZoom=$maxZoom")

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && zoomRatioRange != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)

                if (activeArraySize != null) {
                    val aa = activeArraySize!!
                    val cropW = (aa.width() / z).toInt()
                    val cropH = (aa.height() / z).toInt()

                    // Use provided center or default to sensor center
                    val cx = centerX ?: aa.exactCenterX().toFloat()
                    val cy = centerY ?: aa.exactCenterY().toFloat()

                    val cropX = (cx - cropW / 2f).toInt().coerceIn(aa.left, aa.right - cropW)
                    val cropY = (cy - cropH / 2f).toInt().coerceIn(aa.top, aa.bottom - cropH)
                    val cropRect = Rect(cropX, cropY, cropX + cropW, cropY + cropH)
                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                    Log.d(TAG, "ZOOM_DEBUG: Set cropRegion=$cropRect for zoom=$z center=($cx, $cy)")
                }
            } else {
                val aa = activeArraySize ?: return
                val cropW = (aa.width() / z).toInt()
                val cropH = (aa.height() / z).toInt()

                val cx = (centerX ?: aa.exactCenterX().toFloat())
                val cy = (centerY ?: aa.exactCenterY().toFloat())

                val cropX = (cx - cropW / 2f).toInt().coerceIn(aa.left, aa.right - cropW)
                val cropY = (cy - cropH / 2f).toInt().coerceIn(aa.top, aa.bottom - cropH)

                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
            }

            session.setRepeatingRequest(builder.build(), repeatingPreviewCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setZoom failed", e)
        }
    }

    private fun unlock3AAndZoomOutTo1x(keepZoom: Boolean = false) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)

            // Only reset zoom if keepZoom is false (for landscape modes, we keep the zoom)
            if (!keepZoom) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && zoomRatioRange != null) {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
                    activeArraySize?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
                } else {
                    activeArraySize?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
                }
                Log.d(TAG, "unlock3A: Zoom reset to 1x")
            } else {
                Log.d(TAG, "unlock3A: Keeping current zoom for landscape mode")
            }

            session.setRepeatingRequest(builder.build(), repeatingPreviewCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "unlock3AAndZoomOutTo1x failed", e)
        }
    }

    private fun showStatus(message: String) {
        val binding = _fragmentCameraBinding ?: return
        activity?.runOnUiThread {
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.text = message
        }
    }

    private fun startAutomatedCapture() {
        if (isAutomatedCaptureRunning) return
        isAutomatedCaptureRunning = true

        // Determine if using center-based cropping (manual alignment to crosshair)
        val useCenterCrop = (captureMode == MODE_TELEPHOTO && USE_CENTER_CROP_FOR_TELEPHOTO) ||
                            (captureMode == MODE_MAIN_8X && USE_CENTER_CROP_FOR_MAIN) ||
                            (captureMode == MODE_FRONT && USE_CENTER_CROP_FOR_FRONT)

        // Set appropriate zoom for each mode (don't reset to 1.0x)
        val targetZoom = when (captureMode) {
            MODE_TELEPHOTO -> telephotoZoomLevel  // Dynamic: 1x for physical, 3x+ for zoom-based
            MODE_MAIN_8X -> ZOOM_MAIN_8X
            MODE_FRONT -> FRONT_DEFAULT_ZOOM
            else -> ZOOM_LEVEL_WIDE
        }
        setZoom(targetZoom)
        Log.d(TAG, "CAPTURE_START: useCenterCrop=$useCenterCrop, zoom=$targetZoom for mode=$captureMode (telephotoZoom=$telephotoZoomLevel)")

        fragmentCameraBinding.overlay.frozenEyeBox = null
        fragmentCameraBinding.overlay.eyeAlignmentBox = null

        fragmentCameraBinding.cameraCaptureButton.visibility = View.GONE
        fragmentCameraBinding.stopButton.visibility = View.VISIBLE
        fragmentCameraBinding.overlay.qualityPanelBottomMargin = controlsContainerHeight + 16f  // Position above controls container
        fragmentCameraBinding.cameraSwitchButton.isEnabled = false

        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (captureMode) {
                    MODE_TELEPHOTO -> {
                        // Telephoto: Manual capture without MediaPipe
                        // User aligns to center crosshair
                        // Reset quality counters
                        rightEyeQualityCount = 0
                        leftEyeQualityCount = 0
                        rightEyeAttemptCount = 0
                        leftEyeAttemptCount = 0

                        // Capture RIGHT eye until we have enough quality images
                        performQualityCaptureLoop(isRightEye = true, useTelephoto = true)

                        // Keep zoom for telephoto if in landscape mode
                        unlock3AAndZoomOutTo1x(keepZoom = USE_LANDSCAPE_FOR_TELEPHOTO)
                        delay(1500)

                        // Capture LEFT eye until we have enough quality images
                        performQualityCaptureLoop(isRightEye = false, useTelephoto = true)
                    }
                    MODE_MAIN_8X, MODE_FRONT -> {
                        // Reset quality counters
                        rightEyeQualityCount = 0
                        leftEyeQualityCount = 0
                        rightEyeAttemptCount = 0
                        leftEyeAttemptCount = 0

                        // Center-based capture: User aligns eye to crosshair
                        Log.d(TAG, "CAPTURE_MODE: Using center-based capture (manual alignment)")

                        // Capture RIGHT eye until we have enough quality images
                        performQualityCaptureLoop(isRightEye = true, useTelephoto = true)

                        // Keep zoom for visual feedback
                        unlock3AAndZoomOutTo1x(keepZoom = true)
                        delay(1500)

                        // Capture LEFT eye until we have enough quality images
                        performQualityCaptureLoop(isRightEye = false, useTelephoto = true)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Capture interrupted: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    resetAfterCapture()
                }
            }
        }
    }

    /**
     * Quality capture loop - continues capturing until we have enough high-quality images.
     * Applies to all camera modes (telephoto, main, front).
     *
     * @param isRightEye Whether capturing right eye (true) or left eye (false)
     * @param useTelephoto Whether to use telephoto capture (true) - always true since MediaPipe was removed
     */
    private suspend fun performQualityCaptureLoop(isRightEye: Boolean, useTelephoto: Boolean) {
        val eyeLabel = if (isRightEye) "RIGHT" else "LEFT"

        // Get current counts
        var qualityCount = if (isRightEye) rightEyeQualityCount else leftEyeQualityCount
        var attemptCount = if (isRightEye) rightEyeAttemptCount else leftEyeAttemptCount

        Log.d(TAG, "QUALITY_LOOP: Starting capture loop for $eyeLabel eye, " +
                "target=$TARGET_QUALITY_IMAGES_PER_EYE, maxAttempts=$MAX_CAPTURE_ATTEMPTS_PER_EYE")

        withContext(Dispatchers.Main) {
            showStatus("$eyeLabel Eye: 0/$TARGET_QUALITY_IMAGES_PER_EYE quality images")
        }

        // Loop until we have enough quality images or hit max attempts
        while (qualityCount < TARGET_QUALITY_IMAGES_PER_EYE &&
               attemptCount < MAX_CAPTURE_ATTEMPTS_PER_EYE &&
               isAutomatedCaptureRunning) {

            attemptCount++
            if (isRightEye) rightEyeAttemptCount = attemptCount else leftEyeAttemptCount = attemptCount

            Log.d(TAG, "QUALITY_LOOP: $eyeLabel eye attempt $attemptCount/$MAX_CAPTURE_ATTEMPTS_PER_EYE, " +
                    "quality so far: $qualityCount/$TARGET_QUALITY_IMAGES_PER_EYE")

            withContext(Dispatchers.Main) {
                showStatus("$eyeLabel Eye: $qualityCount/$TARGET_QUALITY_IMAGES_PER_EYE (attempt $attemptCount)")
            }

            // Perform the capture (returns number of quality images from this burst)
            // Always use center-based capture (MediaPipe removed)
            val qualityFromBurst = performTelephotoCaptureSingle(isRightEye)

            // Update quality count
            qualityCount += qualityFromBurst
            if (isRightEye) rightEyeQualityCount = qualityCount else leftEyeQualityCount = qualityCount

            Log.d(TAG, "QUALITY_LOOP: $eyeLabel eye burst yielded $qualityFromBurst quality images, " +
                    "total: $qualityCount/$TARGET_QUALITY_IMAGES_PER_EYE")

            // Brief pause between attempts if we need more
            if (qualityCount < TARGET_QUALITY_IMAGES_PER_EYE && attemptCount < MAX_CAPTURE_ATTEMPTS_PER_EYE) {
                withContext(Dispatchers.Main) {
                    if (qualityFromBurst == 0) {
                        showStatus("$eyeLabel Eye: Low quality - repositioning... ($qualityCount/$TARGET_QUALITY_IMAGES_PER_EYE)")
                    } else {
                        showStatus("$eyeLabel Eye: $qualityCount/$TARGET_QUALITY_IMAGES_PER_EYE - continue capturing")
                    }
                }
                delay(2000)  // Give user time to reposition if needed
            }
        }

        // Final status
        val finalStatus = when {
            qualityCount >= TARGET_QUALITY_IMAGES_PER_EYE ->
                "$eyeLabel Eye: ✓ Complete! $qualityCount quality images saved"
            attemptCount >= MAX_CAPTURE_ATTEMPTS_PER_EYE ->
                "$eyeLabel Eye: Max attempts reached. $qualityCount/$TARGET_QUALITY_IMAGES_PER_EYE quality images"
            else ->
                "$eyeLabel Eye: Stopped. $qualityCount/$TARGET_QUALITY_IMAGES_PER_EYE quality images"
        }

        Log.d(TAG, "QUALITY_LOOP: $finalStatus")
        withContext(Dispatchers.Main) {
            showStatus(finalStatus)
        }
        delay(1500)
    }

    /**
     * Single telephoto capture - performs one burst and returns count of quality images.
     * Used by the quality capture loop.
     */
    private suspend fun performTelephotoCaptureSingle(isRightEye: Boolean): Int {
        return performTelephotoCapture(isRightEye)
    }

    /**
     * Telephoto capture mode - user manually aligns eye to center crosshair
     * No MediaPipe detection needed since user provides the alignment
     *
     * OPTIMIZATIONS FOR MAXIMUM IRIS QUALITY:
     * 1. Uses actual minimum focus distance from camera characteristics
     * 2. Smaller AF metering region (~6%) for precise iris focus
     * 3. Optional manual focus at minimum distance for largest possible iris
     */
    private suspend fun performTelephotoCapture(isRightEye: Boolean): Int {
        val eyeLabel = if (isRightEye) "RIGHT" else "LEFT"

        // Calculate recommended distance based on actual lens capability
        // Add 10cm buffer for user comfort and AF margin
        val recommendedDistanceCm = if (minFocusDistanceCm < Float.MAX_VALUE) {
            (minFocusDistanceCm + 10).toInt()
        } else {
            40  // Fallback if not available
        }
        val minDistanceDisplay = if (minFocusDistanceCm < Float.MAX_VALUE) {
            "${minFocusDistanceCm.toInt()}cm minimum"
        } else {
            "~30cm minimum"
        }

        val focusModeText = if (useManualFocus) "[MANUAL FOCUS]" else "[AUTO FOCUS]"

        captureIsRightEye = isRightEye

        withContext(Dispatchers.Main) {
            fragmentCameraBinding.overlay.isTargetingRightEye = isRightEye

            // Check if using center-based cropping for current mode
            val useCenterCrop = when (captureMode) {
                MODE_TELEPHOTO -> USE_CENTER_CROP_FOR_TELEPHOTO
                MODE_MAIN_8X -> USE_CENTER_CROP_FOR_MAIN
                MODE_FRONT -> USE_CENTER_CROP_FOR_FRONT
                else -> false
            }

            // Update UI - show iris target for center-based cropping
            if (useCenterCrop) {
                fragmentCameraBinding.overlay.showIrisTarget = true
                fragmentCameraBinding.overlay.showCenteringGuide = false
                fragmentCameraBinding.tvModeInstructions.text =
                    "Position $eyeLabel EYE in the green circle\n" +
                    "Move closer until iris fills the target\n" +
                    "Distance: ~${recommendedDistanceCm}cm • $focusModeText"
                showStatus("Align $eyeLabel Eye in circle - Move closer if needed")
            } else {
                fragmentCameraBinding.overlay.showCenteringGuide = true
                fragmentCameraBinding.overlay.showIrisTarget = false
                fragmentCameraBinding.tvModeInstructions.text =
                    "Align your $eyeLabel EYE to the crosshair (+)\n" +
                    "Distance: ${recommendedDistanceCm}cm ($minDistanceDisplay)\n" +
                    focusModeText
                showStatus("Align $eyeLabel Eye - Keep ${recommendedDistanceCm}cm distance")
            }

            // Refresh overlay to show updated eye label
            fragmentCameraBinding.overlay.invalidate()
        }

        Log.d(TAG, "TELEPHOTO_CAPTURE: Starting capture for $eyeLabel eye, " +
                "minFocusDist=${minFocusDistanceCm}cm, recommended=${recommendedDistanceCm}cm, " +
                "manualFocus=$useManualFocus")

        // Give user time to align and position
        delay(4000)

        withContext(Dispatchers.Main) {
            showStatus("Focusing on $eyeLabel Eye...")
        }

        // Set center iris coordinates (user aligned to center)
        captureIrisNormX = 0.5f
        captureIrisNormY = 0.5f
        // For center-based cropping, iris should fill a good portion of frame
        val useCenterCropForRadius = when (captureMode) {
            MODE_TELEPHOTO -> USE_CENTER_CROP_FOR_TELEPHOTO
            MODE_MAIN_8X -> USE_CENTER_CROP_FOR_MAIN
            MODE_FRONT -> USE_CENTER_CROP_FOR_FRONT
            else -> false
        }
        captureIrisNormRadius = if (useCenterCropForRadius) {
            MIN_IRIS_SIZE_FRACTION * 1.5f  // ~22% of frame for center-based capture
        } else {
            0.15f  // Standard radius for MediaPipe mode
        }

        // Setup focus - either manual or auto with precise metering region
        try {
            activeArraySize?.let { aa ->
                val centerX = aa.centerX()
                val centerY = aa.centerY()
                // Use smaller metering region for telephoto (~6% vs old 25%)
                val meteringSize = minOf(aa.width(), aa.height()) / AF_METERING_FRACTION_TELEPHOTO
                val meteringRect = Rect(
                    centerX - meteringSize / 2,
                    centerY - meteringSize / 2,
                    centerX + meteringSize / 2,
                    centerY + meteringSize / 2
                )
                val metering = MeteringRectangle(meteringRect, MeteringRectangle.METERING_WEIGHT_MAX)

                Log.d(TAG, "TELEPHOTO_FOCUS: Metering region: $meteringRect " +
                        "(${meteringSize}px = ${100.0 * meteringSize / minOf(aa.width(), aa.height())}% of frame)")

                if (useManualFocus && minFocusDistanceDiopters > 0) {
                    // MANUAL FOCUS MODE: Set focus to minimum distance for largest iris
                    Log.d(TAG, "TELEPHOTO_FOCUS: Using MANUAL focus at ${minFocusDistanceDiopters} diopters (${minFocusDistanceCm}cm)")
                    previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    previewRequestBuilder?.set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocusDistanceDiopters)
                    previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
                    captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)

                    withContext(Dispatchers.Main) {
                        showStatus("Manual focus set to ${minFocusDistanceCm.toInt()}cm")
                    }
                } else {
                    // AUTO FOCUS MODE: Use continuous AF with precise metering
                    Log.d(TAG, "TELEPHOTO_FOCUS: Using AUTO focus with ${100 / AF_METERING_FRACTION_TELEPHOTO}% metering region")
                    previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
                    previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
                    captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set focus regions", e)
        }

        // Wait for focus to stabilize
        delay(if (useManualFocus) 500 else 1500)

        // Turn on flash for rear camera only (front cameras typically don't have flash)
        if (!isFrontCamera) {
            withContext(Dispatchers.Main) {
                showStatus("Hold steady - turning on light...")
            }

            try {
                previewRequestBuilder?.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
                Log.d(TAG, "TELEPHOTO_CAPTURE: Flash ON")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to turn on flash (may not be available)", e)
            }

            // OPTIMIZED: Reduced flash delay to minimize pupil constriction
            // Pupil constricts within 200-300ms of bright light exposure
            // Using shorter delay preserves larger pupil = more visible iris texture
            delay(FLASH_STABILIZATION_DELAY_MS)
            Log.d(TAG, "TELEPHOTO_FLASH: Flash stabilization delay=${FLASH_STABILIZATION_DELAY_MS}ms (optimized for larger pupil)")
        } else {
            withContext(Dispatchers.Main) {
                showStatus("Hold steady - capturing without flash...")
            }
            Log.d(TAG, "FRONT_CAMERA: Skipping flash (not available on front camera)")
            delay(300)  // Brief stabilization delay
        }

        // For auto focus mode, trigger one-shot AF to lock focus
        val focusSuccess: Boolean
        if (useManualFocus) {
            // Manual focus is already set, no need to trigger AF
            focusSuccess = true
            Log.d(TAG, "TELEPHOTO_FOCUS: Manual focus - skipping AF trigger")
            withContext(Dispatchers.Main) {
                showStatus("Manual focus locked at ${minFocusDistanceCm.toInt()}cm")
            }
        } else {
            // Auto focus: trigger one-shot AF to lock
            withContext(Dispatchers.Main) {
                showStatus("Locking focus...")
            }

            try {
                previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
            } catch (e: Exception) { }

            delay(300)

            withContext(Dispatchers.Main) {
                showStatus("Locking focus (with retry)...")
            }

            focusSuccess = triggerFocusWithRetry()
        }
        Log.d(TAG, "TELEPHOTO_FOCUS: Focus lock result = $focusSuccess (AF state=$lastAfState, manualFocus=$useManualFocus)")

        if (!focusSuccess) {
            withContext(Dispatchers.Main) {
                showStatus("Focus lock failed after retries - capturing anyway")
            }
            delay(300)
        } else {
            withContext(Dispatchers.Main) {
                showStatus("Focus locked successfully!")
            }
            delay(200)
        }

        // Lock exposure and white balance
        try {
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_LOCK, true)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
            Log.d(TAG, "TELEPHOTO_FOCUS: AE/AWB locked")
        } catch (e: Exception) { e.printStackTrace() }

        delay(POST_FLASH_CAPTURE_DELAY_MS)

        val mode = if (isRawMode) "R" else "N"
        val attemptNum = if (isRightEye) rightEyeAttemptCount else leftEyeAttemptCount

        withContext(Dispatchers.Main) {
            showStatus("Capturing $eyeLabel Eye...")
        }

        // === SINGLE CAPTURE WITH MEDIAPIPE PROCESSING ===
        // 1. Capture single full-resolution JPEG
        // 2. Run MediaPipe on downsampled image to detect iris
        // 3. Crop eye region from full-res using landmarks
        // 4. Quality check on cropped image
        // 5. Save only if quality passes

        Log.d(TAG, "TELEPHOTO_CAPTURE: Starting single capture for $eyeLabel eye")

        // Capture single image
        val jpegBytes = suspendCancellableCoroutine<ByteArray?> { cont ->
            takeSingleCapture { bytes ->
                if (cont.isActive) cont.resume(bytes)
            }
        }

        var qualityCount = 0

        if (jpegBytes == null) {
            Log.e(TAG, "TELEPHOTO_CAPTURE: Failed to capture image")
            clearPendingRaw()  // Clean up any pending RAW
            withContext(Dispatchers.Main) {
                showStatus("✗ Capture failed - retrying...")
            }
        } else {
            Log.d(TAG, "TELEPHOTO_CAPTURE: Captured ${jpegBytes.size} bytes, processing...")

            withContext(Dispatchers.Main) {
                showStatus("Processing $eyeLabel Eye...")
            }

            // Process and crop the captured image using CENTER-BASED cropping
            // (Manual alignment mode - user centers eye in preview)
            val processResult = processAndCropCenterBased(jpegBytes, isRightEye)

            if (processResult != null) {
                // Quality passed - save the cropped image
                val cameraLabel = when (captureMode) {
                    MODE_TELEPHOTO -> "Tele"
                    MODE_MAIN_8X -> "Main8x"
                    MODE_FRONT -> "Front"
                    else -> "Back"
                }
                val eyeCode = if (isRightEye) "R" else "L"
                val qualityScore = processResult.metadata.overallScore
                val filename = "${viewModel.participantId}_${cameraLabel}_${eyeCode}_${attemptNum}_${mode}_Q${qualityScore}"

                saveCroppedImageWithMetadata(processResult.croppedBitmap, processResult.metadata, filename)
                processResult.croppedBitmap.recycle()

                // Save RAW image if available (full sensor, uncropped) with metadata
                val rawSaved = savePendingRaw(filename, processResult)
                Log.d(TAG, "TELEPHOTO_CAPTURE: RAW saved = $rawSaved")

                qualityCount = 1
                lastQualityResult = processResult.qualityResult

                Log.d(TAG, "TELEPHOTO_CAPTURE: Quality image saved - ${processResult.metadata.toSummary()}")
                withContext(Dispatchers.Main) {
                    val rawStatus = if (rawSaved) " + RAW" else ""
                    showStatus("✓ Quality image saved (Q=${qualityScore})$rawStatus")

                    // Show quality overlay
                    fragmentCameraBinding.overlay.qualityResult = processResult.qualityResult
                    fragmentCameraBinding.overlay.showQualityOverlay = true
                    fragmentCameraBinding.overlay.invalidate()

                    // Auto-hide quality panel after 3 seconds
                    fragmentCameraBinding.overlay.postDelayed({
                        fragmentCameraBinding.overlay.showQualityOverlay = false
                        fragmentCameraBinding.overlay.invalidate()
                    }, 3000)
                }
            } else {
                Log.w(TAG, "TELEPHOTO_CAPTURE: Image failed quality check")
                // Clear pending RAW since quality check failed
                clearPendingRaw()
                withContext(Dispatchers.Main) {
                    showStatus("✗ Quality check failed - reposition")
                }
            }
        }

        delay(500)

        // Turn off flash (if rear camera) and release locks
        try {
            if (!isFrontCamera) {
                previewRequestBuilder?.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_LOCK, false)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
            Log.d(TAG, "CAPTURE_CLEANUP: ${if (!isFrontCamera) "Flash OFF, " else ""}locks released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release locks", e)
        }

        return qualityCount
    }

    /**
     * Show info dialog when using zoom-based telephoto (Samsung, Xiaomi, etc.)
     * Explains that the device uses automatic lens switching at higher zoom levels.
     */
    private fun showZoomBasedTelephotoInfo() {
        if (_fragmentCameraBinding == null || !isAdded) return

        // Update the mode title to reflect zoom-based telephoto
        fragmentCameraBinding.tvModeTitle.text = "TELEPHOTO MODE ($telephotoDescription)"
        fragmentCameraBinding.tvModeTitle.setTextColor(Color.parseColor("#4CAF50"))

        // Show a brief toast instead of blocking dialog
        Toast.makeText(
            requireContext(),
            "Using ${telephotoDescription} zoom (auto lens switching)",
            Toast.LENGTH_SHORT
        ).show()

        Log.d(TAG, "TELEPHOTO_MODE: Zoom-based telephoto active - $telephotoDescription")
    }

    /**
     * Show warning dialog when telephoto mode is selected but no telephoto lens is available
     * AND zoom-based switching is not possible (maxZoom < 3x).
     * Informs user they'll be using the main camera instead.
     */
    private fun showTelephotoFallbackWarning(fallbackFocal: String) {
        if (_fragmentCameraBinding == null || !isAdded) return

        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ Telephoto Not Available")
            .setMessage(
                "This device does not have a telephoto lens and doesn't support enough zoom for lens switching.\n\n" +
                "The app will use the main camera ($fallbackFocal) at ${maxZoom}x zoom instead.\n\n" +
                "For best results:\n" +
                "• Hold the phone closer to the eye\n" +
                "• Use good lighting\n" +
                "• Keep steady while capturing"
            )
            .setPositiveButton("Continue") { dialog, _ ->
                dialog.dismiss()
                // Update the mode title to reflect actual camera
                fragmentCameraBinding.tvModeTitle.text = "MAIN CAMERA (No Telephoto)"
                fragmentCameraBinding.tvModeTitle.setTextColor(Color.parseColor("#FF9800")) // Orange warning color
            }
            .setNegativeButton("Go Back") { dialog, _ ->
                dialog.dismiss()
                // Navigate back to mode selection
                Navigation.findNavController(requireView()).navigateUp()
            }
            .setCancelable(false)
            .show()
    }

    private fun stopAutomatedCapture() {
        captureJob?.cancel()
        captureJob = null
        isAutomatedCaptureRunning = false

        if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) {
            resetAfterCapture()
        } else {
            activity?.runOnUiThread { resetAfterCapture() }
        }
    }

    private fun resetAfterCapture() {
        if (_fragmentCameraBinding == null) return

        fragmentCameraBinding.overlay.showEyeAlignmentBox = false
        fragmentCameraBinding.overlay.showCenteringGuide = false  // Hide center crosshair
        fragmentCameraBinding.overlay.showIrisTarget = false      // Hide iris target (landscape mode)
        fragmentCameraBinding.overlay.frozenEyeBox = null
        fragmentCameraBinding.overlay.isTargetingRightEye = null
        fragmentCameraBinding.overlay.eyeAlignmentBox = null
        fragmentCameraBinding.overlay.focusRing = null

        fragmentCameraBinding.cameraCaptureButton.visibility = View.VISIBLE
        fragmentCameraBinding.stopButton.visibility = View.GONE
        fragmentCameraBinding.overlay.qualityPanelBottomMargin = controlsContainerHeight + 16f  // Position above controls container
        fragmentCameraBinding.cameraSwitchButton.isEnabled = true
        fragmentCameraBinding.statusText.visibility = View.GONE

        backgroundHandler?.post {
            try {
                captureSession?.stopRepeating()
                captureSession?.abortCaptures()
            } catch (_: Exception) { }

            unlock3AAndZoomOutTo1x()
        }

        fragmentCameraBinding.overlay.invalidate()
        isAutomatedCaptureRunning = false
    }

    private fun meteringFromEyeBox(
        eyeBoxPx: RectF,
        imgW: Int,
        imgH: Int
    ): MeteringRectangle? {
        val crop = lastCropRegion ?: activeArraySize ?: return null

        var nx0 = (eyeBoxPx.left / imgW).coerceIn(0f, 1f)
        var ny0 = (eyeBoxPx.top / imgH).coerceIn(0f, 1f)
        var nx1 = (eyeBoxPx.right / imgW).coerceIn(0f, 1f)
        var ny1 = (eyeBoxPx.bottom / imgH).coerceIn(0f, 1f)

        val cx = (nx0 + nx1) / 2f
        val cy = (ny0 + ny1) / 2f
        // Reduced from 0.25 (25%) to ~8% for more precise iris focus
        // Smaller metering region = AF focuses specifically on iris detail
        val minFrac = 1.0f / AF_METERING_FRACTION  // ~8% of frame
        val halfW = max((nx1 - nx0) / 2f, minFrac / 2f)
        val halfH = max((ny1 - ny0) / 2f, minFrac / 2f)

        nx0 = (cx - halfW).coerceIn(0f, 1f)
        nx1 = (cx + halfW).coerceIn(0f, 1f)
        ny0 = (cy - halfH).coerceIn(0f, 1f)
        ny1 = (cy + halfH).coerceIn(0f, 1f)

        if (isFrontCamera) {
            val mx0 = 1f - nx1
            val mx1 = 1f - nx0
            nx0 = mx0
            nx1 = mx1
        }

        fun map(nx: Float, ny: Float): PointF {
            val w = crop.width().toFloat()
            val h = crop.height().toFloat()

            return when (sensorOrientation) {
                90 -> PointF(
                    crop.left + ny * w,
                    crop.top + (1f - nx) * h
                )
                270 -> PointF(
                    crop.left + (1f - ny) * w,
                    crop.top + nx * h
                )
                else -> PointF(
                    crop.left + nx * w,
                    crop.top + ny * h
                )
            }
        }

        val p00 = map(nx0, ny0)
        val p11 = map(nx1, ny1)
        val left = min(p00.x, p11.x).toInt()
        val top = min(p00.y, p11.y).toInt()
        val right = max(p00.x, p11.x).toInt()
        val bottom = max(p00.y, p11.y).toInt()

        val r = Rect(left, top, right, bottom)
        r.intersect(activeArraySize!!)
        if (r.width() <= 0 || r.height() <= 0) return null

        // Log metering region size for debugging (should be ~8% of frame now)
        val aa = activeArraySize!!
        val meteringPct = (100.0 * r.width() * r.height()) / (aa.width() * aa.height())
        Log.d(TAG, "METERING_REGION: ${r.width()}x${r.height()} = ${String.format("%.1f", meteringPct)}% of sensor (was 25%, now ~8%)")

        return MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    /**
     * Trigger autofocus and wait for lock with timeout.
     * Returns true if focus locked successfully, false otherwise.
     */
    private suspend fun triggerFocus(): Boolean = suspendCancellableCoroutine { cont ->
        try {
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

            val startTime = System.currentTimeMillis()
            val callback = object : CameraCaptureSession.CaptureCallback() {
                private var isResumed = false

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    lastCropRegion = result.get(CaptureResult.SCALER_CROP_REGION)

                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    lastAfState = afState ?: CaptureResult.CONTROL_AF_STATE_INACTIVE
                    lastAeState = aeState ?: CaptureResult.CONTROL_AE_STATE_INACTIVE

                    // Check for timeout
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > FOCUS_LOCK_TIMEOUT_MS && !isResumed) {
                        isResumed = true
                        Log.w(TAG, "FOCUS: Timeout after ${elapsed}ms, AF state=$afState")
                        if (cont.isActive) cont.resume(false)
                        return
                    }

                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        if (!isResumed) {
                            isResumed = true
                            val success = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            Log.d(TAG, "FOCUS: Lock achieved in ${elapsed}ms, success=$success, AF state=$afState")
                            if (cont.isActive) {
                                cont.resume(success)
                            }
                            try {
                                previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
                                session.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
                            } catch (e: Exception) { }
                        }
                    }
                }
            }
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), callback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "FOCUS: Exception during trigger", e)
            if (cont.isActive) cont.resume(false)
        }
    }

    /**
     * Trigger focus with retries. Returns true if any attempt succeeded.
     */
    private suspend fun triggerFocusWithRetry(): Boolean {
        for (attempt in 1..FOCUS_RETRY_COUNT) {
            Log.d(TAG, "FOCUS: Attempt $attempt of $FOCUS_RETRY_COUNT")

            // Reset AF trigger
            try {
                previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                captureSession?.capture(previewRequestBuilder!!.build(), null, backgroundHandler)
                delay(100)
                previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), repeatingPreviewCallback, backgroundHandler)
                delay(200)
            } catch (e: Exception) { }

            val success = triggerFocus()
            if (success) {
                Log.d(TAG, "FOCUS: Success on attempt $attempt")
                return true
            }

            if (attempt < FOCUS_RETRY_COUNT) {
                Log.d(TAG, "FOCUS: Retrying...")
                delay(300)
            }
        }

        Log.w(TAG, "FOCUS: All $FOCUS_RETRY_COUNT attempts failed")
        return false
    }

    /**
     * Apply optimal exposure settings for iris capture.
     * NOTE: Manual exposure (AE_MODE_OFF) is INCOMPATIBLE with flash/torch.
     * Camera HAL returns "Broken pipe" error when using manual exposure with flash.
     * We use auto exposure which properly manages flash timing.
     */
    private fun applyOptimalExposureSettings(builder: CaptureRequest.Builder) {
        // Keep auto exposure mode for flash compatibility
        // Copy AE mode from preview (which has flash settings)
        val currentAeMode = previewRequestBuilder?.get(CaptureRequest.CONTROL_AE_MODE)
        if (currentAeMode != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, currentAeMode)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        // Copy flash mode from preview
        val currentFlashMode = previewRequestBuilder?.get(CaptureRequest.FLASH_MODE)
        if (currentFlashMode != null) {
            builder.set(CaptureRequest.FLASH_MODE, currentFlashMode)
        }

        Log.d(TAG, "EXPOSURE: Auto mode (flash compatible), AE=$currentAeMode, Flash=$currentFlashMode")
    }

    private fun takePicture(eye: String, number: Int, mode: String) {
        if (cameraDevice == null || captureSession == null) return
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

            imageReader?.surface?.let { captureBuilder.addTarget(it) }

            if (isRawMode && rawImageReader != null) {
                captureBuilder.addTarget(rawImageReader!!.surface)
            }

            applyCommonCaptureSettings(captureBuilder)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && zoomRatioRange != null) {
                val z = (previewRequestBuilder?.get(CaptureRequest.CONTROL_ZOOM_RATIO) ?: ZOOM_LEVEL_WIDE)
                    .coerceIn(zoomRatioRange!!.lower, zoomRatioRange!!.upper)

                captureBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)

                val cropRegion = previewRequestBuilder?.get(CaptureRequest.SCALER_CROP_REGION)
                Log.d(TAG, "CAPTURE_DEBUG: zoom=$z cropRegion=$cropRegion eye=$eye number=$number")

                cropRegion?.let {
                    captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, it)
                }
            } else {
                val cropRegion = previewRequestBuilder?.get(CaptureRequest.SCALER_CROP_REGION)
                Log.d(TAG, "CAPTURE_DEBUG (legacy): cropRegion=$cropRegion eye=$eye number=$number")
                cropRegion?.let {
                    captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, it)
                }
            }

            val rotation = requireActivity().windowManager.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))

            val cameraLabel = if (isFrontCamera) "Front" else "Back"

            // Include iris coordinates in filename for post-processing crop
            // Format: IX{x%}_IY{y%}_IR{radius%} - all as integers 0-100
            val irisX = (captureIrisNormX * 100).toInt().coerceIn(0, 100)
            val irisY = (captureIrisNormY * 100).toInt().coerceIn(0, 100)
            val irisR = (captureIrisNormRadius * 100).toInt().coerceIn(1, 50)

            val filename = "${viewModel.participantId}_${cameraLabel}_${eye}_${number}_${mode}_IX${irisX}_IY${irisY}_IR${irisR}"
            Log.d(TAG, "CAPTURE_FILENAME: $filename (iris at ${irisX}%,${irisY}% radius ${irisR}%)")
            captureBuilder.setTag(filename)

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber)
                    val tag = request.tag as? String
                    if (tag != null) {
                        filenameMap[timestamp] = tag
                    }
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (isRawMode) {
                        synchronized(rawResultQueue) {
                            rawResultQueue[result.get(CaptureResult.SENSOR_TIMESTAMP)!!] = result
                            checkAndSaveMatchedRaw()
                        }
                    }
                }
            }

            captureSession?.capture(captureBuilder.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun takePicture() {
        takePicture("Manual", 0, "N")
    }

    private fun checkAndSaveMatchedRaw() {
        val matchedTimestamps = rawResultQueue.keys.intersect(rawImageQueue.keys)
        for (ts in matchedTimestamps) {
            val result = rawResultQueue.remove(ts)!!
            val image = rawImageQueue.remove(ts)!!

            // Check if warmup
            val tag = result.request.tag as? String
            if (tag != null && tag.contains("Warmup")) {
                image.close()
                continue
            }

            val characteristics = currentCharacteristics ?: continue
            val filename = tag ?: "pic_${ts}"
            saveRawImage(image, characteristics, result, filename)
            image.close()
        }
    }

    /**
     * Result of processing and cropping a captured image.
     */
    data class ProcessedCropResult(
        val croppedBitmap: Bitmap,
        val metadata: IrisMetadata,
        val qualityResult: IrisQualityAssessor.IrisQualityResult,
        // Additional data for RAW metadata
        val cropRect: Rect,              // Crop region in RAW sensor pixel coordinates
        val irisNormX: Float,            // Normalized iris X (0-1) used for cropping
        val irisNormY: Float,            // Normalized iris Y (0-1) used for cropping
        val irisNormRadius: Float,       // Normalized iris radius used for cropping
        val sensorWidth: Int,            // Full sensor width in pixels
        val sensorHeight: Int,           // Full sensor height in pixels
        val exifRotationDegrees: Int     // EXIF rotation degrees (0, 90, 180, 270)
    )

    /**
     * Capture a single JPEG image.
     * Used by the new single-capture flow instead of burst capture.
     *
     * @param onComplete Callback with JPEG bytes, or null if capture failed
     */
    private fun takeSingleCapture(onComplete: (ByteArray?) -> Unit) {
        if (cameraDevice == null || captureSession == null) {
            Log.e(TAG, "SINGLE_CAPTURE: Camera not available")
            onComplete(null)
            return
        }

        // Set flag to prevent old ImageReader listener from saving full images
        isSingleCaptureActive = true

        // Clear any previous pending RAW
        clearPendingRaw()

        // Check if RAW capture is supported for this camera
        val rawSupported = rawImageReader != null
        Log.d(TAG, "SINGLE_CAPTURE: RAW supported = $rawSupported")

        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)

            // Add RAW target if supported
            if (rawSupported) {
                captureBuilder.addTarget(rawImageReader!!.surface)
                Log.d(TAG, "SINGLE_CAPTURE: Added RAW target")
            }

            // Copy settings from preview
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())

            // IMPORTANT: Capture at 1x zoom (full sensor) for maximum quality
            // Preview stays zoomed for user visibility, but capture uses full sensor
            // MediaPipe will detect iris from the full-res image
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && zoomRatioRange != null) {
                val captureZoom = ZOOM_LEVEL_WIDE.coerceIn(zoomRatioRange!!.lower, zoomRatioRange!!.upper)
                captureBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, captureZoom)
                Log.d(TAG, "SINGLE_CAPTURE: Using ${captureZoom}x zoom (full sensor)")

                // Set full sensor crop region (no crop = full sensor)
                activeArraySize?.let { aa ->
                    captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, aa)
                }
            } else {
                // Fallback for older devices: set full sensor crop region
                activeArraySize?.let { aa ->
                    captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, aa)
                }
            }

            // One-shot callback for single capture
            var captureCompleted = false
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    if (!captureCompleted) {
                        Log.d(TAG, "SINGLE_CAPTURE: Capture completed")
                        // Store capture result for RAW (if RAW is supported)
                        if (rawSupported) {
                            synchronized(pendingRawLock) {
                                pendingRawResult = result
                                Log.d(TAG, "SINGLE_CAPTURE: Stored pending RAW result")
                            }
                        }
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    Log.e(TAG, "SINGLE_CAPTURE: Capture failed")
                    if (!captureCompleted) {
                        captureCompleted = true
                        isSingleCaptureActive = false  // Reset flag
                        clearPendingRaw()  // Clean up any pending RAW
                        onComplete(null)
                    }
                }
            }

            // Setup RAW ImageReader callback if supported
            if (rawSupported) {
                rawImageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage()
                    if (image != null) {
                        synchronized(pendingRawLock) {
                            // Close any previous pending image
                            pendingRawImage?.close()
                            pendingRawImage = image
                            Log.d(TAG, "SINGLE_CAPTURE: Stored pending RAW image (${image.width}x${image.height})")
                        }
                    }
                }, backgroundHandler)
            }

            // Setup ImageReader callback for this capture
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null && !captureCompleted) {
                    captureCompleted = true
                    isSingleCaptureActive = false  // Reset flag
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    Log.d(TAG, "SINGLE_CAPTURE: Got ${bytes.size} bytes")
                    onComplete(bytes)
                }
            }, backgroundHandler)

            captureSession?.capture(captureBuilder.build(), captureCallback, backgroundHandler)
            Log.d(TAG, "SINGLE_CAPTURE: Capture request submitted (RAW=$rawSupported)")

        } catch (e: Exception) {
            Log.e(TAG, "SINGLE_CAPTURE: Exception during capture", e)
            isSingleCaptureActive = false  // Reset flag
            clearPendingRaw()  // Clean up any pending RAW
            onComplete(null)
        }
    }

    /**
     * Process a captured JPEG using center-based cropping for manual alignment modes.
     * Used for TELEPHOTO and MAIN_8X (landscape) where user manually centers the eye.
     *
     * @param jpegBytes Full-resolution JPEG bytes
     * @param isRightEye Which eye to crop (based on UI indicator)
     * @return ProcessedCropResult if successful, null if cropping or quality failed
     */
    private fun processAndCropCenterBased(jpegBytes: ByteArray, isRightEye: Boolean): ProcessedCropResult? {
        val eyeLabel = if (isRightEye) "right" else "left"
        Log.d(TAG, "PROCESS_CROP_CENTER: Starting center-based processing for $eyeLabel eye, ${jpegBytes.size} bytes")

        // Step 1: Get full image dimensions
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, boundsOptions)
        val fullWidth = boundsOptions.outWidth
        val fullHeight = boundsOptions.outHeight

        if (fullWidth <= 0 || fullHeight <= 0) {
            Log.e(TAG, "PROCESS_CROP_CENTER: Failed to get image dimensions")
            return null
        }
        Log.d(TAG, "PROCESS_CROP_CENTER: Full image size: ${fullWidth}x${fullHeight}")

        // Step 2: Use center coordinates (user manually aligned eye to center)
        // For manual alignment, eye is centered at (0.5, 0.5)
        val irisNormX = 0.5f
        val irisNormY = 0.5f

        // Iris radius in the CAPTURED STILL, normalised to the still's width.
        //
        // Derivation (corrected 2026-09-19; this used to be a per-mode hardcoded guess):
        // The user aligns their iris to the on-screen target that OverlayView draws at
        // radius MIN_IRIS_SIZE_FRACTION (0.15) of the display frame width, and they see
        // that target through the PREVIEW, which runs at whatever zoom the mode selected.
        // takeSingleCapture, however, forces the still to 1x / full active array, so every
        // feature shrinks by the preview zoom factor Z when it lands in the still:
        //     irisNormRadius = MIN_IRIS_SIZE_FRACTION / previewZoom
        // Read the zoom ACTUALLY applied to the preview request instead of a per-mode
        // literal, so this stays correct when the zoom-selection logic changes or a device
        // takes the telephoto fallback path (telephotoZoomLevel = maxZoom there).
        // Worked values: physical telephoto lens (previewZoom 1.0) -> 0.15;
        // zoom-based telephoto (3.0) -> 0.05; MAIN_8X (ZOOM_MAIN_8X = 4.0) -> 0.0375;
        // front (FRONT_DEFAULT_ZOOM = 1.25) -> 0.12.
        // Please do not replace this with per-mode literals again.
        val previewZoom = run {
            // CONTROL_ZOOM_RATIO only exists from API 30; older devices zoom via
            // SCALER_CROP_REGION alone and report null here.
            val applied = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                previewRequestBuilder?.get(CaptureRequest.CONTROL_ZOOM_RATIO)
            } else null
            // Fallback: the zoom this mode asks setZoom() for (see startAutomatedCapture).
            applied ?: when (captureMode) {
                MODE_TELEPHOTO -> telephotoZoomLevel
                MODE_MAIN_8X -> ZOOM_MAIN_8X
                MODE_FRONT -> FRONT_DEFAULT_ZOOM
                else -> ZOOM_LEVEL_WIDE
            }
        }
        // Guard against an unset, zero or NaN zoom: fall back to the on-screen target size.
        val irisNormRadius = if (previewZoom > 0f) {
            MIN_IRIS_SIZE_FRACTION / previewZoom
        } else {
            MIN_IRIS_SIZE_FRACTION
        }

        // Expand factor: how much padding around the iris
        // Front camera: tighter crop (1.5x) since we want just the eye region
        // Other modes: standard 2x padding
        val expandFactor = when (captureMode) {
            MODE_FRONT -> 1.2f  // Very tight crop for front camera
            else -> 2.0f
        }

        Log.d(TAG, "PROCESS_CROP_CENTER: Using center coordinates (0.5, 0.5), mode=$captureMode, radius=$irisNormRadius, expand=$expandFactor")

        // Step 3: Crop using stored coordinates (center-based)
        val cropResult = EyeImageCropper.cropFromStoredCoordinates(
            jpegBytes = jpegBytes,
            irisNormX = irisNormX,
            irisNormY = irisNormY,
            irisNormRadius = irisNormRadius,
            expandFactor = expandFactor,
            isFrontCamera = isFrontCamera
        )

        if (cropResult == null) {
            Log.w(TAG, "PROCESS_CROP_CENTER: Eye cropping failed")
            return null
        }
        Log.d(TAG, "PROCESS_CROP_CENTER: Cropped eye: ${cropResult.croppedBitmap.width}x${cropResult.croppedBitmap.height}, " +
                "iris center=${cropResult.irisCenter}, radius=${cropResult.irisRadius}")

        // Step 3.5: Eye-presence gate.
        // NOTE (2026-09-19): this wiring is RECONSTRUCTED. The original call was
        // lost with the working tree and was never compiled into any surviving
        // build, so only its position is known (it sat between the crop and the
        // sharpness measurement). The rejection behaviour below is a best-faith
        // reconstruction and should be reviewed against intended behaviour.
        // Rejecting here avoids running quality assessment on a frame with no eye.
        val presenceResult = EyePresenceDetector.detect(
            bitmap = cropResult.croppedBitmap,
            irisCenter = cropResult.irisCenter,
            irisRadius = cropResult.irisRadius,
            isFrontCamera = isFrontCamera
        )
        Log.d(TAG, "PROCESS_CROP_CENTER: Eye presence: detected=${presenceResult.eyeDetected} " +
                "confidence=${presenceResult.confidence} reason=${presenceResult.reason}")
        if (!presenceResult.eyeDetected) {
            Log.w(TAG, "PROCESS_CROP_CENTER: Rejected, no eye detected (${presenceResult.reason})")
            cropResult.croppedBitmap.recycle()
            return null
        }

        // Step 4: Calculate sharpness on cropped image
        val croppedSharpness = SharpnessAnalyzer.calculateSharpness(cropResult.croppedBitmap, null)
        Log.d(TAG, "PROCESS_CROP_CENTER: Cropped image sharpness = $croppedSharpness (threshold=$sharpnessThreshold)")

        // Step 5: Run quality assessment on cropped image
        val qualityResult = try {
            IrisQualityAssessor.assess(
                cropResult = cropResult,
                existingSharpnessScore = croppedSharpness,
                sharpnessThreshold = sharpnessThreshold,
                contrastThreshold = if (captureMode == MODE_FRONT) 0.10f else 0.4f,
                isFrontCamera = isFrontCamera
            )
        } catch (e: Exception) {
            Log.e(TAG, "PROCESS_CROP_CENTER: Quality assessment failed", e)
            null
        }

        if (qualityResult == null) {
            Log.w(TAG, "PROCESS_CROP_CENTER: Quality assessment returned null")
            cropResult.croppedBitmap.recycle()
            return null
        }

        Log.d(TAG, "PROCESS_CROP_CENTER: Quality assessment: overall=${qualityResult.overallScore}, " +
                "passed=${qualityResult.overallPassed}, metrics=${qualityResult.metrics.map { "${it.name}=${it.passed}" }}")

        // Step 6: Check if quality passed
        if (!qualityResult.overallPassed) {
            val failedMetrics = qualityResult.metrics.filter { !it.passed }
                .joinToString(", ") { "${it.name}=${String.format("%.1f", it.normalizedScore)}" }
            Log.w(TAG, "PROCESS_CROP_CENTER: Quality check failed: $failedMetrics")
            cropResult.croppedBitmap.recycle()
            return null
        }

        // Step 7: Build metadata
        val usableIrisMetric = qualityResult.metrics.find { it.name == "Usable Iris" }
        val sharpnessMetric = qualityResult.metrics.find { it.name == "Sharpness" }
        val contrastMetric = qualityResult.metrics.find { it.name == "Contrast" }

        val occlusionPercent = if (usableIrisMetric != null) {
            100f - usableIrisMetric.normalizedScore
        } else 0f

        val metadata = IrisMetadata(
            irisCenter = cropResult.irisCenter,
            irisRadiusPx = cropResult.irisRadius,
            cropWidth = cropResult.croppedBitmap.width,
            cropHeight = cropResult.croppedBitmap.height,
            sourceWidth = fullWidth,
            sourceHeight = fullHeight,
            overallScore = qualityResult.overallScore.toInt(),
            sharpness = sharpnessMetric?.rawValue ?: 0f,
            contrast = contrastMetric?.rawValue ?: 0f,
            occlusionPercent = occlusionPercent,
            upperEyelidOcclusion = 0f,
            lowerEyelidOcclusion = 0f,
            irisVisible = true,
            pupilVisible = true,
            eye = eyeLabel,
            captureMode = captureMode,
            participantId = viewModel.participantId
        )

        Log.d(TAG, "PROCESS_CROP_CENTER: Success! ${metadata.toSummary()}")

        // Get EXIF rotation for RAW metadata
        val exifRotationDegrees = EyeImageCropper.getExifRotationDegrees(jpegBytes)

        return ProcessedCropResult(
            croppedBitmap = cropResult.croppedBitmap,
            metadata = metadata,
            qualityResult = qualityResult,
            cropRect = cropResult.cropRect,
            irisNormX = irisNormX,
            irisNormY = irisNormY,
            irisNormRadius = irisNormRadius,
            sensorWidth = fullWidth,
            sensorHeight = fullHeight,
            exifRotationDegrees = exifRotationDegrees
        )
    }

    private fun applyCommonCaptureSettings(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())

        // === TELEPHOTO IRIS OPTIMIZATIONS ===
        if (captureMode == MODE_TELEPHOTO && DISABLE_ISP_FOR_TELEPHOTO) {
            // Disable aggressive ISP processing to preserve fine iris texture detail
            // Edge enhancement can create artificial edges that mask real iris patterns
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)

            // Disable noise reduction to preserve iris micro-texture
            // Noise reduction smooths fine details which are critical for iris recognition
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)

            // Disable hot pixel correction (minimal impact but ensures raw texture)
            try {
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
            } catch (e: Exception) {
                Log.w(TAG, "HOT_PIXEL_MODE not supported")
            }

            // Disable lens shading correction for consistent illumination analysis
            try {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
            } catch (e: Exception) {
                Log.w(TAG, "SHADING_MODE not supported")
            }

            Log.d(TAG, "TELEPHOTO_ISP: Disabled edge enhancement, noise reduction, hot pixel, shading")
        } else {
            // Standard settings for non-telephoto modes
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            // Use FAST noise reduction mode - it's widely supported across all camera configurations
            // MINIMAL (3) was causing HAL crashes on Pixel 10 Pro with 8x zoom + burst capture
            // FAST (1) provides a good balance between noise reduction and texture preservation
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        }

        // === OIS (Optical Image Stabilization) ===
        // Critical for telephoto macro - even tiny movements cause blur at close range
        if (USE_OIS_FOR_TELEPHOTO && hasOisSupport && captureMode == MODE_TELEPHOTO) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            Log.d(TAG, "TELEPHOTO_OIS: Enabled optical image stabilization")
        }

        // Apply optimal exposure settings (low ISO, fast shutter)
        applyOptimalExposureSettings(builder)

        previewRequestBuilder?.get(CaptureRequest.SCALER_CROP_REGION)?.let {
            builder.set(CaptureRequest.SCALER_CROP_REGION, it)
        }

        // Copy Locks
        previewRequestBuilder?.get(CaptureRequest.CONTROL_AWB_LOCK)?.let {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, it)
        }
        previewRequestBuilder?.get(CaptureRequest.CONTROL_AE_LOCK)?.let {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, it)
        }

        previewRequestBuilder?.get(CaptureRequest.CONTROL_AF_REGIONS)?.let {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, it)
        }
        previewRequestBuilder?.get(CaptureRequest.CONTROL_AE_REGIONS)?.let {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, it)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && zoomRatioRange != null) {
            previewRequestBuilder?.get(CaptureRequest.CONTROL_ZOOM_RATIO)?.let {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, it)
            }
        }
    }

    private fun getOrientation(rotation: Int): Int {
        if (cameraId == null) return 0
        val sensorOrientation = try {
            cameraManager.getCameraCharacteristics(cameraId!!).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (e: Exception) {
            90
        }

        // Use physical device orientation from accelerometer (OrientationEventListener)
        // instead of Display.getRotation(). The display rotation can be unreliable when
        // the activity forces a specific screen orientation via requestedOrientation
        // (e.g., SCREEN_ORIENTATION_LANDSCAPE) combined with configChanges="orientation".
        val roundedOrientation = ((deviceOrientationDegrees + 45) / 90 * 90) % 360

        // Back camera: subtract device rotation
        // Front camera: add device rotation (and handle mirroring elsewhere)
        val jpegOrientation = if (isFrontCamera) {
            (sensorOrientation + roundedOrientation) % 360
        } else {
            (sensorOrientation - roundedOrientation + 360) % 360
        }

        Log.d(TAG, "ORIENTATION_DEBUG: sensor=$sensorOrientation deviceDeg=$deviceOrientationDegrees " +
                "rounded=$roundedOrientation front=$isFrontCamera jpeg=$jpegOrientation (displayRotation=$rotation)")
        return jpegOrientation
    }

    private fun getExifOrientation(rotation: Int): Int {
        val degrees = getOrientation(rotation)
        return when (degrees) {
            0 -> ExifInterface.ORIENTATION_NORMAL
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun saveImage(bytes: ByteArray, filenameBase: String) {
        if (filenameBase.contains("Warmup")) return

        val fileName = if (filenameBase.endsWith(".jpg")) filenameBase else "${filenameBase}.jpg"
        saveToMediaStore(bytes, fileName, "image/jpeg", "Pictures/FaceLandmarker")
    }

    /**
     * Save a cropped eye bitmap as JPEG to MediaStore.
     * The bitmap is already rotated to display orientation by EyeImageCropper,
     * so we write EXIF ORIENTATION_NORMAL to make this explicit for all viewers.
     */
    private fun saveCroppedImage(bitmap: Bitmap, filenameBase: String) {
        try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
            val jpegBytes = baos.toByteArray()

            // Write EXIF orientation = NORMAL since bitmap pixels are already in display orientation.
            // Bitmap.compress() produces JPEG without any EXIF metadata, so we add it explicitly
            // to ensure all image viewers interpret the orientation correctly.
            val finalBytes = try {
                val tempFile = File.createTempFile("crop_", ".jpg", requireContext().cacheDir)
                tempFile.writeBytes(jpegBytes)
                val exif = ExifInterface(tempFile.absolutePath)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.saveAttributes()
                val result = tempFile.readBytes()
                tempFile.delete()
                result
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set EXIF orientation on crop, using raw bytes", e)
                jpegBytes
            }

            val fileName = if (filenameBase.endsWith(".jpg")) filenameBase else "${filenameBase}.jpg"
            saveToMediaStore(finalBytes, fileName, "image/jpeg", "Pictures/FaceLandmarker")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cropped image: $filenameBase", e)
        }
    }

    /**
     * Save a cropped eye bitmap as JPEG with embedded IrisMetadata in EXIF.
     * This is the primary save method for the new single-capture flow.
     *
     * @param bitmap Cropped eye bitmap (already in display orientation)
     * @param metadata IrisMetadata containing quality scores and coordinates
     * @param filenameBase Base filename without extension
     */
    private fun saveCroppedImageWithMetadata(bitmap: Bitmap, metadata: IrisMetadata, filenameBase: String) {
        try {
            // Compress bitmap to JPEG
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
            val jpegBytes = baos.toByteArray()

            // Write EXIF with metadata
            val finalBytes = try {
                val tempFile = File.createTempFile("crop_meta_", ".jpg", requireContext().cacheDir)
                tempFile.writeBytes(jpegBytes)

                val exif = ExifInterface(tempFile.absolutePath)
                // Set orientation to NORMAL since bitmap is already rotated
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                // Embed iris metadata as JSON in UserComment
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, metadata.toJson())
                // Add descriptive tags
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Iris capture - ${metadata.eye} eye")
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "MediaPipe Iris Capture v1.0")
                exif.saveAttributes()

                val result = tempFile.readBytes()
                tempFile.delete()
                result
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write EXIF metadata, using raw bytes", e)
                jpegBytes
            }

            val fileName = if (filenameBase.endsWith(".jpg")) filenameBase else "${filenameBase}.jpg"
            saveToMediaStore(finalBytes, fileName, "image/jpeg", "Pictures/FaceLandmarker")

            Log.d(TAG, "SAVE_CROP: Saved ${metadata.toSummary()} as $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cropped image with metadata: $filenameBase", e)
        }
    }

    /**
     * Clear any pending RAW image and result from single capture mode.
     * Call this before starting a new capture or when quality check fails.
     */
    private fun clearPendingRaw() {
        synchronized(pendingRawLock) {
            pendingRawImage?.close()
            pendingRawImage = null
            pendingRawResult = null
            Log.d(TAG, "PENDING_RAW: Cleared pending RAW data")
        }
    }

    /**
     * Save the pending RAW image if available.
     * Call this after quality check passes with the filename to use.
     * @param filenameBase Base filename (will append _raw.dng)
     * @param processResult The processed crop result containing metadata for RAW
     * @return true if RAW was saved, false if no RAW was pending or save failed
     */
    private fun savePendingRaw(filenameBase: String, processResult: ProcessedCropResult?): Boolean {
        synchronized(pendingRawLock) {
            val image = pendingRawImage
            val result = pendingRawResult
            val characteristics = currentCharacteristics

            if (image == null || result == null || characteristics == null) {
                Log.d(TAG, "PENDING_RAW: No pending RAW to save (image=$image, result=$result, characteristics=$characteristics)")
                clearPendingRaw()
                return false
            }

            // Append _raw to filename
            val rawFilename = "${filenameBase}_raw"
            Log.d(TAG, "PENDING_RAW: Saving RAW as $rawFilename.dng")

            // Build RAW metadata JSON
            val rawMetadataJson = buildRawMetadataJson(processResult)

            try {
                saveRawImage(image, characteristics, result, rawFilename, rawMetadataJson)
                Log.d(TAG, "PENDING_RAW: RAW saved successfully")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "PENDING_RAW: Failed to save RAW", e)
                return false
            } finally {
                // Always clear after attempting to save
                pendingRawImage = null
                pendingRawResult = null
                image.close()
            }
        }
    }

    /**
     * Build JSON metadata for RAW file description.
     * Includes crop coordinates, iris position in full sensor, and full IrisMetadata.
     */
    private fun buildRawMetadataJson(processResult: ProcessedCropResult?): String {
        if (processResult == null) {
            return "{\"error\": \"No process result available\"}"
        }

        val json = JSONObject()

        try {
            // Version
            json.put("version", "1.0")

            // Crop region in RAW sensor pixel coordinates
            val cropRegion = JSONObject().apply {
                put("left", processResult.cropRect.left)
                put("top", processResult.cropRect.top)
                put("right", processResult.cropRect.right)
                put("bottom", processResult.cropRect.bottom)
                put("width", processResult.cropRect.width())
                put("height", processResult.cropRect.height())
            }
            json.put("cropRegion", cropRegion)

            // Iris position in RAW sensor pixel coordinates
            // Calculate from normalized coordinates
            val irisCenterRawX = processResult.irisNormX * processResult.sensorWidth
            val irisCenterRawY = processResult.irisNormY * processResult.sensorHeight
            val irisRadiusRaw = processResult.irisNormRadius * minOf(processResult.sensorWidth, processResult.sensorHeight)

            val irisSensorCoords = JSONObject().apply {
                put("centerX", irisCenterRawX)
                put("centerY", irisCenterRawY)
                put("radius", irisRadiusRaw)
            }
            json.put("irisSensorCoordinates", irisSensorCoords)

            // Normalized iris coordinates used for cropping
            val irisNormalized = JSONObject().apply {
                put("x", processResult.irisNormX.toDouble())
                put("y", processResult.irisNormY.toDouble())
                put("radius", processResult.irisNormRadius.toDouble())
            }
            json.put("irisNormalized", irisNormalized)

            // Sensor info
            val sensorInfo = JSONObject().apply {
                put("width", processResult.sensorWidth)
                put("height", processResult.sensorHeight)
                put("exifRotationDegrees", processResult.exifRotationDegrees)
            }
            json.put("sensorInfo", sensorInfo)

            // Full IrisMetadata (embedded as nested object)
            val irisMetadata = JSONObject(processResult.metadata.toJson())
            json.put("irisMetadata", irisMetadata)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to build RAW metadata JSON", e)
            json.put("error", e.message)
        }

        return json.toString()
    }

    private fun saveRawImage(image: Image, characteristics: CameraCharacteristics, result: TotalCaptureResult, filenameBase: String, metadataJson: String? = null) {
        val fileName = if (filenameBase.endsWith(".dng")) filenameBase else "${filenameBase}.dng"

        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FaceLandmarker")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = requireContext().contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    DngCreator(characteristics, result).use { dngCreator ->
                        val rotation = requireActivity().windowManager.defaultDisplay.rotation
                        dngCreator.setOrientation(getExifOrientation(rotation))

                        // Set description with metadata JSON if provided
                        if (metadataJson != null) {
                            dngCreator.setDescription(metadataJson)
                            Log.d(TAG, "RAW_SAVE: Set DNG description with metadata (${metadataJson.length} chars)")
                        }

                        dngCreator.writeImage(outputStream, image)
                    }
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }

                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Saved $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveToMediaStore(bytes: ByteArray, fileName: String, mimeType: String, path: String) {
        var outputStream: java.io.OutputStream? = null
        try {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, path)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = requireContext().contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                outputStream = resolver.openOutputStream(uri)
                outputStream?.write(bytes)
                outputStream?.flush()

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }

                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Saved $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            outputStream?.close()
        }
    }

    // Laplacian Variance for Sharpness
    private fun calculateSharpness(bitmap: Bitmap?): Double {
        if (bitmap == null) return 0.0

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to Grayscale
        val grayPixels = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            // Luminance
            grayPixels[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        // Laplacian Kernel

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        // Convolve
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = grayPixels[y * width + x]
                val up = grayPixels[(y - 1) * width + x]
                val down = grayPixels[(y + 1) * width + x]
                val left = grayPixels[y * width + (x - 1)]
                val right = grayPixels[y * width + (x + 1)]

                val lap = up + down + left + right - 4 * center

                sum += lap
                sumSq += (lap * lap)
                count++
            }
        }

        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)

        return variance
    }

    private fun initBottomSheetControls() {
        // MediaPipe controls removed - bottom sheet is now unused
        // Hide the bottom sheet since it contained only MediaPipe controls
        fragmentCameraBinding.bottomSheetLayout.root.visibility = View.GONE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun queryLensesForFacing(facing: Int): List<LensOption> {
        val out = LinkedHashMap<String, LensOption>() // key = label/id combo

        fun sensorAreaOf(c: CameraCharacteristics): Int {
            val a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            return if (a != null) a.width() * a.height() else 0
        }

        fun addLogical(id: String) {
            val c = try { cameraManager.getCameraCharacteristics(id) } catch (_: Exception) { return }
            if (c.get(CameraCharacteristics.LENS_FACING) != facing) return

            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportsRaw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true
            
            val mm = if (focal != null) String.format(Locale.US, "%.1fmm", focal) else "?"
            val raw = if (supportsRaw) "RAW" else "no RAW"
            val area = sensorAreaOf(c)

            out["logical:$id"] = LensOption(
                openCameraId = id,
                physicalId = null,
                label = "Auto (logical) ($mm, $raw)",
                focalMm = focal,
                sensorArea = area
            )
        }
        
        fun addPhysical(logicalId: String, pid: String) {
            val pc = try { cameraManager.getCameraCharacteristics(pid) } catch (_: Exception) { return }
            if (pc.get(CameraCharacteristics.LENS_FACING) != facing) return

            val focal = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            val map = pc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportsRaw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true

            val mm = if (focal != null) String.format(Locale.US, "%.1fmm", focal) else "?"
            val raw = if (supportsRaw) "RAW" else "no RAW"
            val area = sensorAreaOf(pc)

            out["phys:$logicalId:$pid"] = LensOption(
                openCameraId = logicalId,
                physicalId = pid,
                label = "Physical $pid ($mm, $raw)",
                focalMm = focal,
                sensorArea = area
            )
        }

        for (id in cameraManager.cameraIdList) {
            val c = try { cameraManager.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            if (c.get(CameraCharacteristics.LENS_FACING) != facing) continue

            addLogical(id)
            
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val isLogical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
             if (isLogical && android.os.Build.VERSION.SDK_INT >= 28) {
                for (pid in c.physicalCameraIds) {
                    addPhysical(id, pid)
                }
            }
        }

        return out.values.toList()
    }
}
