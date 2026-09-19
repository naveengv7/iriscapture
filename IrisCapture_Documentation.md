# IrisCapture - Comprehensive Application Documentation

**Version:** 1.0
**Date:** February 2026
**Platform:** Android (minSdk 24, targetSdk 34, compileSdk 34)
**Language:** Kotlin 1.7.10
**Build System:** Gradle 8.14.2, AGP 8.11.0

---

## Table of Contents

1. [Application Overview](#1-application-overview)
2. [Architecture](#2-architecture)
3. [Navigation Flow](#3-navigation-flow)
4. [File Inventory](#4-file-inventory)
5. [Build Configuration](#5-build-configuration)
6. [Login Screen](#6-login-screen)
7. [Mode Selection Screen](#7-mode-selection-screen)
8. [Camera Fragment - Core Pipeline](#8-camera-fragment---core-pipeline)
9. [Capture Modes](#9-capture-modes)
10. [Universal Telephoto Detection](#10-universal-telephoto-detection)
11. [Camera2 API Integration](#11-camera2-api-integration)
12. [Automated Capture Loop](#12-automated-capture-loop)
13. [Focus System](#13-focus-system)
14. [Eye Image Cropper](#14-eye-image-cropper)
15. [Eye Presence Detector](#15-eye-presence-detector)
16. [Sharpness Analyzer](#16-sharpness-analyzer)
17. [ISO 29794-6 Quality Assessor](#17-iso-29794-6-quality-assessor)
18. [Iris Metadata & EXIF Embedding](#18-iris-metadata--exif-embedding)
19. [RAW (DNG) Capture](#19-raw-dng-capture)
20. [Overlay View](#20-overlay-view)
21. [Image Save Pipeline](#21-image-save-pipeline)
22. [Performance Optimizations](#22-performance-optimizations)
23. [Device Compatibility](#23-device-compatibility)
24. [Configuration Constants Reference](#24-configuration-constants-reference)
25. [Data Flow Diagram](#25-data-flow-diagram)
26. [Known Limitations](#26-known-limitations)

---

## 1. Application Overview

IrisCapture is an Android application designed for research-grade iris biometric image capture. It uses the Camera2 API directly with no machine learning dependencies. The application captures high-resolution iris images from both eyes using manual alignment (user centers their eye in an on-screen target), applies a multi-stage quality pipeline, and saves only images that pass all quality checks.

**Key characteristics:**
- Zero ML dependencies (no TensorFlow, no MediaPipe at runtime)
- Camera2 API for full manual control over focus, exposure, ISO, and OIS
- 3 capture modes: Telephoto, Main Camera, Front Camera
- 5-signal heuristic eye presence detection (no false saves on backgrounds)
- ISO 29794-6 inspired quality assessment (7 metrics)
- Combined Laplacian + Tenengrad sharpness analysis
- Dual-format output: cropped JPEG + full-sensor RAW (DNG) with embedded metadata
- Configurable images per eye (1-20, default 5)
- Automatic right-eye-then-left-eye session management

**Package:** `com.google.mediapipe.examples.facelandmarker` (legacy name retained from original codebase)

---

## 2. Architecture

The app follows MVVM + Fragment Navigation architecture:

```
MainActivity (host)
  └── NavHostFragment (nav_graph.xml)
       ├── LoginFragment       → Participant ID + images per eye
       ├── PermissionsFragment → Camera permission gate
       ├── ModeSelectionFragment → Choose capture mode
       └── CameraFragment      → Main capture pipeline
```

**Key architectural patterns:**
- **Single Activity:** `MainActivity` hosts all fragments via Navigation Component
- **Shared ViewModel:** `MainViewModel` stores participant ID and images-per-eye setting across fragments
- **View Binding:** All fragments use generated binding classes (no `findViewById`)
- **Coroutines:** Capture loops use `lifecycleScope.launch(Dispatchers.IO)` for async camera operations
- **Singleton Analyzers:** `SharpnessAnalyzer`, `IrisQualityAssessor`, `EyePresenceDetector`, and `EyeImageCropper` are all Kotlin `object` singletons

---

## 3. Navigation Flow

```
LoginFragment
  │  (Enter 3-digit participant ID + images per eye)
  ▼
PermissionsFragment
  │  (Request CAMERA permission, auto-advance if granted)
  ▼
ModeSelectionFragment
  │  (Choose: Telephoto / Main Camera / Front Camera)
  ▼
CameraFragment
  │  (Capture session: Right eye → countdown → Left eye)
  └── Back button → ModeSelectionFragment
```

**Navigation graph:** `res/navigation/nav_graph.xml`
**Start destination:** `login_fragment`
**Fragment transitions:** All use Navigation Component actions with `popUpTo` for clean back stack.

---

## 4. File Inventory

### Source Files (`app/src/main/java/com/google/mediapipe/examples/facelandmarker/`)

| File | Lines | Description |
|------|-------|-------------|
| `MainActivity.kt` | 37 | Single-activity host with ViewBinding |
| `MainViewModel.kt` | 39 | Shared ViewModel: participantId, imagesPerEye |
| `IrisMetadata.kt` | 200 | EXIF-embeddable metadata data class with JSON serialization |
| `EyeImageCropper.kt` | 220 | BitmapRegionDecoder-based ROI extraction with EXIF rotation handling |
| `EyePresenceDetector.kt` | 703 | 5-signal heuristic eye detection (no ML) |
| `SharpnessAnalyzer.kt` | 388 | Laplacian + Tenengrad combined sharpness scoring |
| `IrisQualityAssessor.kt` | 698 | ISO 29794-6 inspired quality assessment (7 metrics) |
| `OverlayView.kt` | 390 | Custom View for iris target, crosshair, quality panel |

### Fragment Files (`fragment/`)

| File | Lines | Description |
|------|-------|-------------|
| `LoginFragment.kt` | 58 | Participant ID and images-per-eye input |
| `PermissionsFragment.kt` | 93 | Camera permission request |
| `ModeSelectionFragment.kt` | 75 | Capture mode selection cards |
| `CameraFragment.kt` | ~3584 | Main camera pipeline, capture loop, focus, save |

### Layout Files (`res/layout/`)

| File | Description |
|------|-------------|
| `activity_main.xml` | NavHostFragment container |
| `fragment_login.xml` | Participant ID input + images per eye |
| `fragment_mode_selection.xml` | 3 capture mode cards + instructions |
| `fragment_camera.xml` | TextureView + overlay + controls bar |
| `info_bottom_sheet.xml` | Legacy bottom sheet (hidden) |

### Other Resources

| File | Description |
|------|-------------|
| `res/navigation/nav_graph.xml` | Navigation graph with 4 fragments and actions |
| `AndroidManifest.xml` | Camera permission, single-activity, orientation handling |
| `app/build.gradle` | Groovy build config, no ML dependencies |

---

## 5. Build Configuration

**`app/build.gradle`:**
```groovy
compileSdk 34
minSdk 24
targetSdk 34
```

**Dependencies (no ML):**
- `androidx.core:core-ktx:1.8.0`
- `androidx.appcompat:appcompat:1.5.1`
- `com.google.android.material:material:1.7.0`
- `androidx.constraintlayout:constraintlayout:2.1.4`
- `androidx.fragment:fragment-ktx:1.5.4`
- `androidx.navigation:navigation-fragment-ktx:2.5.3`
- `androidx.navigation:navigation-ui-ktx:2.5.3`
- `androidx.window:window:1.1.0-alpha03`

**Build command (Windows):**
```powershell
powershell.exe -Command ".\gradlew.bat assembleDebug 2>&1"
```

---

## 6. Login Screen

**File:** `LoginFragment.kt` + `fragment_login.xml`

### UI Elements
- **Participant ID:** `EditText` — 3-digit numeric ID (validated: exactly 3 characters)
- **Images per Eye:** `EditText` — number input, range 1-20, default 5
- **Enter Button:** Validates inputs, stores in `MainViewModel`, navigates to permissions

### Validation Rules
- Participant ID must be exactly 3 digits
- Images per eye must be integer between 1 and 20 inclusive
- `MainViewModel.setImagesPerEye()` uses `coerceIn(1, 20)` for safety

### Dynamic Scaling
The images-per-eye value drives three dynamic properties in `CameraFragment`:

| Property | Formula | Example (10 images) |
|----------|---------|---------------------|
| `targetQualityImagesPerEye` | `viewModel.imagesPerEye` | 10 |
| `maxCaptureAttemptsPerEye` | `maxOf(30, imagesPerEye * 6)` | 60 |
| `totalSessionTimeoutMs` | `maxOf(90_000, imagesPerEye * 2 * 9_000)` | 180,000ms (3 min) |

---

## 7. Mode Selection Screen

**File:** `ModeSelectionFragment.kt` + `fragment_mode_selection.xml`

### Three Capture Modes

| Mode | Card Color | Description | Zoom | Camera |
|------|-----------|-------------|------|--------|
| **Telephoto** | Blue (`#E3F2FD`) | Highest quality, optical zoom | 1x (physical) or 3x (zoom-based) | Rear telephoto or main |
| **Main Camera** | Orange (`#FFF3E0`) | Close-up, 4x digital zoom | 4x | Rear main |
| **Front Camera** | Purple (`#F3E5F5`) | Self-capture, no flash | 1.25x | Front |

Each card navigates to `CameraFragment` with a `capture_mode` argument bundle.

### Info Text
"Each mode captures BOTH eyes (Right then Left). RAW + JPEG images saved with quality metrics. Flash used on rear cameras only."

---

## 8. Camera Fragment - Core Pipeline

**File:** `CameraFragment.kt` (~3584 lines)

### Overall Pipeline (per attempt)

```
1. User presses CAPTURE
2. Automated capture loop starts for RIGHT eye
3. For each attempt:
   a. Set focus metering region (6% center)
   b. Alignment delay (4s first attempt, 500ms subsequent)
   c. Focus lock (AF trigger with retry, 2s timeout)
   d. Flash ON + AE/AWB lock (rear cameras)
   e. Flash stabilization delay (150ms)
   f. Single JPEG+RAW capture at 1x zoom (full sensor)
   g. EyeImageCropper.cropFromStoredCoordinates() → BitmapRegionDecoder ROI
   h. EyePresenceDetector.detect() → 5-signal eye validation
   i. SharpnessAnalyzer.calculateSharpness() → Laplacian+Tenengrad
   j. IrisQualityAssessor.assess() → 7 ISO metrics
   k. If all pass: save cropped JPEG (with EXIF metadata) + RAW DNG (parallel)
   l. If fail: discard, show status, retry
   m. Flash OFF, release AE/AWB locks
   n. 500ms inter-attempt delay
4. When target count reached → 7s countdown → LEFT eye
5. Repeat steps 3-4 for LEFT eye
6. Session complete
```

### Key Class Members

| Member | Type | Description |
|--------|------|-------------|
| `captureMode` | String | `"telephoto"`, `"main_8x"`, or `"front"` |
| `isFrontCamera` | Boolean | True for front camera mode |
| `cameraDevice` | CameraDevice? | Open Camera2 device |
| `captureSession` | CameraCaptureSession? | Active capture session |
| `imageReader` | ImageReader? | JPEG image reader |
| `rawImageReader` | ImageReader? | RAW_SENSOR image reader |
| `rightEyeQualityCount` | Int | Quality images saved for right eye |
| `leftEyeQualityCount` | Int | Quality images saved for left eye |
| `lastPresenceReason` | String? | Last eye presence failure reason |
| `lastQualityResult` | IrisQualityResult? | Latest quality result for overlay display |

---

## 9. Capture Modes

### Mode: Telephoto (`MODE_TELEPHOTO`)

- **Lens selection:** Physical telephoto (focal > 12mm) if available, otherwise zoom-based switching (3x)
- **Preview zoom:** `telephotoZoomLevel` (1x physical or 3x zoom-based)
- **Capture zoom:** 1x (full sensor) — iris coordinates map preview-to-capture
- **Focus:** Auto with 6% metering region, or Manual at minimum focus distance
- **Flash:** Torch mode on rear cameras, turned on AFTER focus lock
- **OIS:** Enabled if available (`LENS_OPTICAL_STABILIZATION_MODE_ON`)
- **ISP bypass:** Edge enhancement OFF, noise reduction OFF, hot pixel OFF, shading OFF
- **Iris radius:** `0.06f` normalized

### Mode: Main Camera (`MODE_MAIN_8X`)

- **Lens selection:** Main camera (focal 5-10mm)
- **Preview zoom:** 4x digital
- **Capture zoom:** 1x (full sensor)
- **Focus:** Auto with standard metering
- **Flash:** Torch mode
- **ISP:** Standard settings (edge HIGH_QUALITY, noise FAST)
- **Iris radius:** `0.15 / 4.0 = 0.0375f` normalized

### Mode: Front Camera (`MODE_FRONT`)

- **Lens selection:** Front camera with narrowest FOV (largest focal length)
- **Preview zoom:** 1.25x
- **Capture zoom:** 1x
- **Focus:** Continuous auto
- **Flash:** None (no front flash)
- **ISP:** Standard settings
- **Iris radius:** `0.05f` normalized
- **Expand factor:** 1.2x (tighter crop than rear cameras)

---

## 10. Universal Telephoto Detection

**Strategy:** Support both physical telephoto lenses (Pixel) and zoom-based lens switching (Samsung, Xiaomi).

```
Step 1: Search for physical telephoto lens (focal > 12mm)
  Found → Use physical lens at 1x zoom
  Not Found → Step 2

Step 2: Check zoom capability of main camera
  maxZoom >= 3x → Use main camera at 3x zoom (triggers lens switching)
  maxZoom < 3x → True fallback (warn user)
```

**Key variables:**
- `useZoomBasedTelephoto`: True if using zoom to trigger telephoto
- `telephotoZoomLevel`: Dynamic zoom level (1.0 for physical, 3.0 for zoom-based)
- `telephotoDescription`: UI label ("5x Optical" or "3x Zoom")
- `isTelephotoFallback`: True only if no telephoto AND insufficient zoom

---

## 11. Camera2 API Integration

### Camera Setup Flow

```
onResume() → startBackgroundThread() → openCamera()
  → logAllCameras() + logLogicalBackPhysicals()
  → setUpCameraOutputs()
    → queryLensesForFacing() → enumerate logical + physical cameras
    → Mode-specific lens selection
    → Read characteristics (zoom range, ISO range, focus distance, OIS)
    → Create ImageReader (JPEG) + rawImageReader (RAW_SENSOR)
  → createCameraPreviewSession()
    → Configure outputs (physical camera support via OutputConfiguration)
    → Set initial zoom, start preview repeating request
```

### Physical Camera Support

For devices with physical camera IDs (API 28+), the app uses `OutputConfiguration.setPhysicalCameraId()` and `SessionConfiguration` to target specific physical lenses through the logical camera.

### Lens Query (`queryLensesForFacing()`)

Enumerates all cameras matching the target facing direction:
1. Add logical cameras with focal length and RAW support info
2. For logical multi-cameras (API 28+), add each physical camera ID
3. Return as `List<LensOption>` with focal length, sensor area, and labels

### Zoom Implementation

**API 30+ (preferred):** `CONTROL_ZOOM_RATIO` + `SCALER_CROP_REGION`
**Legacy:** `SCALER_CROP_REGION` only (center crop based on zoom ratio)

Both paths set crop region for consistent behavior. Zoom is applied to both preview and capture requests (preview zoomed for alignment, capture at 1x for quality).

---

## 12. Automated Capture Loop

**Entry point:** `startAutomatedCapture()` → `performQualityCaptureLoop()`

### Loop Logic

```kotlin
while (qualityCount < targetQualityImagesPerEye &&
       attemptCount < maxCaptureAttemptsPerEye &&
       isAutomatedCaptureRunning &&
       (elapsedTime) < totalSessionTimeoutMs) {

    val qualityFromBurst = performTelephotoCaptureSingle(isRightEye, attemptCount)
    qualityCount += qualityFromBurst  // 0 or 1

    if (needsMore) delay(500)  // Inter-attempt pause
}
```

### Exit Conditions (any one triggers exit)
1. Quality images reached target count
2. Attempt count reached maximum
3. User pressed Stop
4. Session timeout exceeded

### Eye Switching
After right eye completes, there's a 7-second countdown with status messages: "Switching to LEFT eye in Xs..."

---

## 13. Focus System

### Auto Focus (`triggerFocusWithRetry()`)

```
For attempt 1..FOCUS_RETRY_COUNT (2):
  1. Cancel previous AF trigger
  2. Wait 50ms
  3. Set AF to IDLE
  4. Wait 100ms
  5. Trigger AF_START
  6. Wait for FOCUSED_LOCKED or NOT_FOCUSED_LOCKED (timeout: 2000ms)
  7. If locked → return success
  8. If timeout → retry
```

### Manual Focus

When `useManualFocus = true`:
- `CONTROL_AF_MODE_OFF`
- `LENS_FOCUS_DISTANCE = minFocusDistanceDiopters` (closest possible)
- Focus button toggles, displays distance in cm

### Metering Regions

| Mode | Fraction | Size |
|------|----------|------|
| Standard | 1/12 = ~8% | For main and front cameras |
| Telephoto | 1/16 = ~6% | More precise for telephoto |

The metering rectangle is centered on the sensor active array center, applied to both AF and AE regions.

### Focus Timing Optimization

| Phase | First Attempt | Subsequent |
|-------|---------------|------------|
| Alignment delay | 4000ms | 500ms |
| Focus stabilization | 300-500ms | Skipped |
| Focus lock timeout | 2000ms | 2000ms |

---

## 14. Eye Image Cropper

**File:** `EyeImageCropper.kt` (220 lines)
**Type:** `object` singleton

### Purpose
Extracts the eye region from a full-resolution JPEG using BitmapRegionDecoder (memory-efficient, no full decode).

### Input
- `jpegBytes`: Full JPEG bytes
- `irisNormX`, `irisNormY`: Normalized iris center (0-1, display space)
- `irisNormRadius`: Normalized iris radius (relative to display width)
- `expandFactor`: Padding multiplier (default 4.0x for rear, 1.2x for front)
- `isFrontCamera`: Affects rotation correction

### Coordinate Transform Pipeline

```
Display Space (normalized 0-1)
  → displayToRawNorm() (inverse EXIF rotation)
  → Raw JPEG Pixel Space
  → Crop rectangle (center ± expandFactor * irisRadius)
  → BitmapRegionDecoder.decodeRegion()
  → transformBitmap() (effectiveRotation to display orientation)
```

### EXIF Rotation Handling

The EXIF rotation handling accounts for device-specific differences:

```kotlin
// Pixel devices front/back camera correction:
val effectiveRotation = if (isFrontCamera) {
    (exifDegrees - 90 + 360) % 360  // Over-rotates by 90
} else {
    (exifDegrees + 90) % 360         // Under-rotates by 90
}
```

### Output (`EyeCropResult`)
- `croppedBitmap`: Rotated to display orientation
- `irisCenter`: In cropped-bitmap coordinates (post-rotation)
- `irisRadius`: In cropped-bitmap pixel units
- `cropRect`: In raw JPEG pixel space
- `exifRotationDegrees`: Cached from JPEG (avoids re-parsing)

---

## 15. Eye Presence Detector

**File:** `EyePresenceDetector.kt` (703 lines)
**Type:** `object` singleton

### Purpose
Heuristic-based eye presence validation to prevent saving images when no eye is in the frame (e.g., white backgrounds, skin, random objects). Uses 5 complementary signals with no ML dependency.

### Signal Architecture

| # | Signal | Weight | Type | Description |
|---|--------|--------|------|-------------|
| 1 | Radial Gradient Consistency | 0.30 | Geometric (PRIMARY) | Samples Sobel gradients around expected iris circumference, measures radial alignment |
| 2 | Fast Radial Symmetry (FRST) | 0.25 | Geometric | Gradient voting for radially symmetric center point |
| 3 | Sclera Presence | 0.20 | Intensity/Color | HSV white-region analysis with bilateral + brightness contrast |
| 4 | Limbal Boundary | 0.15 | Gradient | Radial Sobel projection for iris-sclera edge detection |
| 5 | Histogram Bimodality | 0.10 | Intensity | Dark center (pupil/iris) + bright flanks (sclera) spatial check |

### Decision Logic

```
composite = Σ(signal_i × weight_i)
geometricPass = (radialScore > 0.10) OR (frstScore > 0.10)
eyeDetected = (composite >= 0.28) AND geometricPass
```

**Hard veto:** If BOTH geometric signals score below 0.10, the image is rejected regardless of composite score. This prevents white backgrounds from passing via sclera/histogram alone.

### Signal 1: Radial Gradient Consistency (PRIMARY)

**How it works:**
1. Sample 72 points (every 5 degrees) at 3 radii (0.85R, 1.0R, 1.15R)
2. Compute Sobel gradient at each point
3. Skip weak gradients (magnitude < 10)
4. Calculate alignment: `|dot(gradient, radialDirection)| / magnitude`
5. Score = weighted consistency (70%) + edge coverage (30%)

**Key property:** A uniform surface (white wall) produces zero gradients → score 0.
**Cost:** < 0.5ms for 72 samples.

### Signal 2: Fast Radial Symmetry Transform (FRST)

**How it works:**
1. For each pixel in ROI around expected iris boundary, compute gradient
2. Each gradient pixel votes for a center at distance R along its gradient direction
3. Both directions (inward/outward) to handle dark-on-bright and bright-on-dark
4. Find peak in vote accumulator
5. Score = peak concentration (70%) + proximity to expected center (30%)

**Key property:** No gradients → no votes → score 0.
**Cost:** ~2-3ms for 500x500 image.

### Signal 3: Sclera Presence (Enhanced)

**How it works:**
1. Scan horizontal band around iris center
2. Count white/low-saturation pixels on left and right flanks
3. **CRITICAL enhancement:** Compute brightness contrast between center and flanks
4. If `flankMeanBrightness - centerMeanBrightness < 30` → score 0 (rejects white backgrounds)
5. Score = bilateral sclera ratio + symmetry bonus + contrast bonus

**Thresholds (front vs rear):**
- Brightness minimum: 130 (front) / 150 (rear)
- Saturation maximum: 55 (front) / 45 (rear)

### Signal 4: Limbal Boundary Detection

**How it works:**
1. Sample horizontal arcs only (avoid eyelid contamination)
2. Compute radial Sobel gradient magnitude at each radius step
3. Build radial projection profile
4. Find peak (expected at iris-sclera boundary)
5. Score = peak strength × radius accuracy

### Signal 5: Histogram Bimodality

**How it works:**
1. Build 256-bin intensity histogram, smooth with 5-tap kernel
2. Find peaks (local maxima above threshold)
3. Check for dark peaks (<100) and bright peaks (>=150)
4. Spatial validation: dark pixels should be centrally located
5. Score = dark present (0.30) + bright present (0.30) + dynamic range (0.15) + centered (0.25)

### Shared Optimizations
- **Single pixel extraction:** `bitmap.getPixels()` called once
- **Shared grayscale:** Precomputed once, used by all gradient-based signals
- **Typical total time:** ~5-10ms per image

### Eye Color Robustness
- **Dark irises:** Geometric signals (1, 2) detect iris-sclera boundary regardless of iris color. Sclera contrast still works (sclera is always lighter than iris).
- **Light irises:** Actually easier — stronger limbal contrast with both sclera and pupil.
- **Light skin:** Sclera detection requires brightness contrast (30+) between center and flanks, preventing false positives from light skin.

---

## 16. Sharpness Analyzer

**File:** `SharpnessAnalyzer.kt` (388 lines)
**Type:** `object` singleton

### Algorithms

**1. Laplacian Variance**
- Kernel: `[0, 1, 0; 1, -4, 1; 0, 1, 0]`
- Score = variance of Laplacian response across ROI
- Detects edges/rapid intensity change — sharper images have higher variance

**2. Tenengrad (Sobel Gradient Magnitude)**
- Sobel X and Y gradients
- Score = mean squared gradient magnitude, normalized by /100
- More robust for fine iris micro-texture detection

**Combined score:** `0.3 × Laplacian + 0.7 × Tenengrad`

### Iris-Specific Sharpness (`calculateSharpnessForIris()`)

**Optimized path for iris capture:**
1. Read JPEG dimensions without full decode
2. Transform display-space iris coordinates to raw pixel space (using EXIF rotation)
3. Compute ROI rectangle (iris center ± 1.5x iris radius)
4. **BitmapRegionDecoder:** Decode ONLY the ROI (not full 12MP image)
5. Sample size: 1 for ROI < 1MP, 2 for < 4MP, 4 otherwise
6. Run combined sharpness on the decoded ROI bitmap

**Performance:** Saves ~200-300ms per call compared to full-image decode.

### Sharpness Thresholds

| Camera | Threshold | Rationale |
|--------|-----------|-----------|
| Rear (telephoto, main) | 50.0 | Better optics, higher resolution |
| Front | 20.0 | Smaller sensor, softer lens (~60% of rear quality) |

---

## 17. ISO 29794-6 Quality Assessor

**File:** `IrisQualityAssessor.kt` (698 lines)
**Type:** `object` singleton

### Seven Quality Metrics

| # | Metric | Weight | Critical | Threshold | Description |
|---|--------|--------|----------|-----------|-------------|
| 1 | Usable Iris Area | 0.20 | Yes | 50% (rear), 35% (front) | Fraction of iris annulus not occluded by eyelids |
| 2 | Gaze Angle | 0.10 | Yes | ≤ 0.25 displacement | Iris center vs eye midpoint offset |
| 3 | Pupil-to-Iris Ratio | 0.10 | No | 0.20 - 0.70 | Optimal at 0.45 |
| 4 | Iris-Pupil Contrast | 0.15 | Yes | ≥ 0.40 (rear), ≥ 0.10 (front) | Weber contrast between pupil and iris zones |
| 5 | Illumination Uniformity | 0.10 | No | ≥ 0.50 | 8-sector angular uniformity, specular penalty |
| 6 | Motion Blur | 0.15 | No | ≤ 4.0 anisotropy | Gradient direction histogram peak/mean ratio |
| 7 | Sharpness | 0.20 | Yes | Mode-specific (see above) | Reuses SharpnessAnalyzer score |

### Pass/Fail Logic

```
compositeScore = Σ(metric.normalizedScore × metric.weight)
allCriticalPassed = all critical metrics individually pass
overallPassed = (compositeScore >= 40) AND allCriticalPassed
```

**Critical metrics (must ALL pass individually):** Usable Iris, Gaze Angle, Contrast, Sharpness.

### Metric Details

#### Metric 1: Usable Iris Area
- **With landmarks:** Polar grid sampling in iris annulus, check against eyelid polylines
- **Intensity fallback (no landmarks):** Radial scan, count dark pixels (iris-like intensity)
  - Rear camera threshold: intensity < 180 (flash makes iris darker)
  - Front camera threshold: intensity < 220 (ambient light makes iris brighter)

#### Metric 2: Gaze Angle
- Without landmarks: Returns 0.15 (moderate score, ~70/100)
- With landmarks: Computes iris center offset from eye corner midpoint, normalized by eye width

#### Metric 3: Pupil-to-Iris Ratio
- Radial intensity profile from iris center outward (36 angles × 20 radial steps)
- Peak gradient = pupil-iris boundary
- Optimal: 0.45 (center of 0.20-0.70 range)

#### Metric 4: Iris-Pupil Contrast
- Weber contrast: `|meanIris - meanPupil| / (meanPupil + 1)`
- Samples inner 80% of pupil zone and iris annulus separately

#### Metric 5: Illumination Uniformity
- Divides iris annulus into 8 angular sectors
- Uniformity = `1 - (maxSectorMean - minSectorMean) / (maxSectorMean + 1)`
- Specular penalty: pixels > 242 brightness

#### Metric 6: Motion Blur
- 36-bin gradient direction histogram (10-degree bins) weighted by gradient magnitude
- Anisotropy = peak / mean of non-zero bins
- Low anisotropy = sharp (uniform gradient directions)
- High anisotropy = motion blur (dominant directional streak)

---

## 18. Iris Metadata & EXIF Embedding

**File:** `IrisMetadata.kt` (200 lines)

### Data Class Fields

```kotlin
data class IrisMetadata(
    // Iris geometry (cropped image coordinates)
    val irisCenter: PointF,
    val irisRadiusPx: Float,
    val cropWidth: Int, cropHeight: Int,

    // Source image info
    val sourceWidth: Int, sourceHeight: Int,

    // Quality metrics
    val overallScore: Int,
    val sharpness: Float,
    val contrast: Float,
    val occlusionPercent: Float,
    val upperEyelidOcclusion: Float, lowerEyelidOcclusion: Float,
    val irisVisible: Boolean, pupilVisible: Boolean,

    // Capture info
    val eye: String,  // "right" or "left"
    val captureMode: String,
    val participantId: String,
    val timestamp: Long
)
```

### JSON Structure (embedded in EXIF UserComment)

```json
{
  "irisCapture": {
    "version": "1.0",
    "eye": "right",
    "irisCenter": { "x": 250.0, "y": 300.0 },
    "irisRadiusPx": 80.0,
    "cropSize": { "width": 640, "height": 480 },
    "sourceResolution": { "width": 4032, "height": 3024 },
    "quality": {
      "overallScore": 72,
      "sharpness": 85.3,
      "contrast": 0.65,
      "occlusionPercent": 15.0,
      "upperEyelidOcclusion": 0.0,
      "lowerEyelidOcclusion": 0.0,
      "irisVisible": true,
      "pupilVisible": true
    },
    "capture": {
      "mode": "telephoto",
      "participantId": "001",
      "timestamp": 1739290800000
    }
  }
}
```

### EXIF Tags Written
- `TAG_USER_COMMENT`: Full JSON metadata
- `TAG_IMAGE_DESCRIPTION`: "Iris capture - {eye} eye"
- `TAG_SOFTWARE`: "MediaPipe Iris Capture v1.0"
- `TAG_ORIENTATION`: `ORIENTATION_NORMAL` (bitmap already rotated)

---

## 19. RAW (DNG) Capture

### How RAW Works
1. `rawImageReader` created for `ImageFormat.RAW_SENSOR` (largest RAW size)
2. Single capture adds both JPEG and RAW targets to the capture request
3. RAW image stored in `pendingRawImage` (not immediately saved)
4. Only saved if JPEG passes quality checks
5. `DngCreator` writes the RAW with orientation and metadata description

### RAW Metadata (DNG Description)

```json
{
  "version": "1.0",
  "cropRegion": { "left": 1000, "top": 800, "right": 3000, "bottom": 2200, "width": 2000, "height": 1400 },
  "irisSensorCoordinates": { "centerX": 2016.0, "centerY": 1512.0, "radius": 181.0 },
  "irisNormalized": { "x": 0.5, "y": 0.5, "radius": 0.06 },
  "sensorInfo": { "width": 4032, "height": 3024, "exifRotationDegrees": 90 },
  "irisMetadata": { ... full IrisMetadata JSON ... }
}
```

### Save Flow
```
takeSingleCapture() → JPEG callback → processAndCropCenterBased()
  → if quality PASS:
       coroutineScope {
           async { saveCroppedImageWithMetadata() }  // JPEG
           async { savePendingRaw() }                 // RAW DNG
       }
  → if quality FAIL:
       clearPendingRaw()  // Discard RAW
```

---

## 20. Overlay View

**File:** `OverlayView.kt` (390 lines)

### Visual Elements

| Element | When Shown | Description |
|---------|-----------|-------------|
| Iris Target Circle | Center-based modes (all modes) | Green circle with semi-transparent fill, inner crosshair |
| Corner Brackets | With iris target | 4 L-shaped guides around the target |
| Size Indicator | During preview | "Move CLOSER until iris fills the circle" / "Iris size OK" |
| Eye Label | During capture | "Position RIGHT EYE in the circle" |
| Quality Panel | After quality pass | Semi-transparent black panel showing ISO metric scores |
| Center Crosshair | When no iris target | Simple green crosshair (+) at center |
| Focus Ring | During focus | Green rectangle around focus area |

### Quality Panel Layout

```
┌─────────────────────────┐
│ IRIS QUALITY: 72/100 PASS│
│─────────────────────────│
│ Usable Iris   92   PASS │
│ Gaze Angle    70   PASS │
│ Pupil Ratio   85   PASS │
│ Contrast      65   PASS │
│ Uniformity    78   PASS │
│ Motion Blur   90   PASS │
│ Sharpness     52   PASS │
└─────────────────────────┘
```

Auto-hides after 3 seconds (single capture) or 5 seconds (burst).

---

## 21. Image Save Pipeline

### File Naming Convention

**Cropped JPEG (quality-passed):**
```
{participantId}_{cameraLabel}_{eyeCode}_{attemptNum}_{mode}_Q{qualityScore}.jpg
```
Example: `001_Tele_R_3_R_Q72.jpg`

**RAW DNG:**
```
{same as above}_raw.dng
```

### Save Location
```
MediaStore → Pictures/FaceLandmarker/
```

Uses `ContentResolver` with `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`. On API 29+, uses `RELATIVE_PATH` and `IS_PENDING` for atomic writes.

### JPEG Quality
- Cropped images: 95% JPEG quality
- Full captures: 100% JPEG quality

### EXIF Writing Process
1. Compress bitmap to JPEG (`Bitmap.compress()`)
2. Write to temp file (`File.createTempFile()`)
3. Open with `ExifInterface`
4. Set orientation, metadata JSON, description, software
5. `exif.saveAttributes()`
6. Read back modified bytes
7. Delete temp file
8. Save to MediaStore

---

## 22. Performance Optimizations

### Capture Timing (per attempt)

| Phase | Duration | Optimization |
|-------|----------|--------------|
| Alignment delay | 4000ms (first) / 500ms (subsequent) | Adaptive — user already positioned after first attempt |
| Focus settling | 300-500ms (first) / skipped (subsequent) | AF runs during alignment delay |
| Focus lock | ≤2000ms | Reduced timeout from 3000ms, 2 retries (down from 3) |
| Flash stabilization | 150ms | Reduced from 1000ms (minimize pupil constriction) |
| Capture | ~200ms | Single shot |
| Eye crop | ~50-100ms | BitmapRegionDecoder (ROI only, not full 12MP) |
| Eye presence check | ~5-10ms | Shared grayscale, efficient sampling |
| Sharpness | ~30-50ms | Laplacian+Tenengrad single pass with shared grayscale |
| Quality assessment | ~20-40ms | Polar grid sampling |
| JPEG save | ~50ms | MediaStore ContentResolver |
| RAW save | ~1-3s | Parallel with JPEG via coroutine async |
| Inter-attempt delay | 500ms | Reduced from 2000ms |

**Estimated total per attempt:** ~1.5s (subsequent) to ~5s (first)

### Memory Optimizations
- **BitmapRegionDecoder** for all ROI extraction (never decodes full 12MP image into memory)
- **Shared grayscale arrays** between Laplacian and Tenengrad (single conversion)
- **Shared pixel/grayscale arrays** in EyePresenceDetector across all 5 signals
- **Bitmap recycling** after save completes (both JPEG and RAW)
- **Parallel JPEG + RAW saves** via `coroutineScope { async {} }`

---

## 23. Device Compatibility

### Tested Devices

| Device | Camera ID | Telephoto | Notes |
|--------|-----------|-----------|-------|
| **Pixel 10 Pro** | Physical telephoto (focal ~19mm) | Physical lens, 1x zoom | OIS available, RAW supported |
| **Samsung Galaxy S21 Ultra** | Logical camera, zoom-based | 3x zoom triggers lens switch | RAW supported on main lens |

### Universal Telephoto Detection

The app automatically adapts to device capabilities:

1. **Pixel-style** (physical telephoto): Detects lens with focal > 12mm, opens via physical camera ID, uses 1x zoom on that lens
2. **Samsung-style** (zoom-based): No physical telephoto ID exposed, uses main camera at 3x+ zoom which triggers internal lens switching
3. **Budget devices** (no telephoto, low zoom): Shows warning dialog, falls back to main camera at maximum zoom

### Front Camera Selection

Uses narrowest-FOV front camera (largest focal length) to avoid ultra-wide selfie cameras. Calculates horizontal FOV from focal length and sensor physical size.

---

## 24. Configuration Constants Reference

### Capture Settings

| Constant | Value | Description |
|----------|-------|-------------|
| `TARGET_ISO_MIN` | 100 | Minimum ISO for lowest noise |
| `TARGET_ISO_MAX` | 400 | Maximum ISO to limit noise |
| `MIN_SHUTTER_SPEED_NS` | 4,000,000 (1/250s) | Fastest shutter speed |
| `MAX_SHUTTER_SPEED_NS` | 8,000,000 (1/125s) | Slowest acceptable shutter |
| `FOCUS_LOCK_TIMEOUT_MS` | 2000 | Max time for focus lock |
| `FOCUS_RETRY_COUNT` | 2 | AF retries before proceeding |
| `AF_METERING_FRACTION` | 12 (1/12 = ~8%) | Standard metering region |
| `AF_METERING_FRACTION_TELEPHOTO` | 16 (1/16 = ~6%) | Telephoto metering region |

### Sharpness Thresholds

| Constant | Value | Camera |
|----------|-------|--------|
| `SHARPNESS_THRESHOLD_REAR` | 50.0 | Main and telephoto |
| `SHARPNESS_THRESHOLD_FRONT` | 20.0 | Front camera |

### Zoom Levels

| Constant | Value | Mode |
|----------|-------|------|
| `ZOOM_LEVEL_WIDE` | 1.0 | Capture (full sensor) |
| `ZOOM_TELEPHOTO_DEFAULT` | 1.0 | Physical telephoto |
| `ZOOM_MAIN_8X` | 4.0 | Main camera preview |
| `ZOOM_FRONT_MAX` | 4.0 | Front max practical |
| `FRONT_DEFAULT_ZOOM` | 1.25 | Front preview |

### Telephoto Optimizations

| Constant | Value | Description |
|----------|-------|-------------|
| `FOCUS_BRACKET_STEPS` | 5 | Focus bracketing: number of distances |
| `FOCUS_BRACKET_STEP_DIOPTERS` | 0.3 | Bracket step size in diopters |
| `FLASH_STABILIZATION_DELAY_MS` | 150 | Time after flash before capture |
| `POST_FLASH_CAPTURE_DELAY_MS` | 50 | Brief delay post-flash |
| `USE_OIS_FOR_TELEPHOTO` | true | Enable OIS for telephoto |
| `DISABLE_ISP_FOR_TELEPHOTO` | true | Bypass ISP processing |

### Eye Presence Detection

| Constant | Value | Description |
|----------|-------|-------------|
| `WEIGHT_RADIAL` | 0.30 | Radial gradient consistency weight |
| `WEIGHT_FRST` | 0.25 | FRST weight |
| `WEIGHT_SCLERA` | 0.20 | Sclera presence weight |
| `WEIGHT_LIMBAL` | 0.15 | Limbal boundary weight |
| `WEIGHT_HISTOGRAM` | 0.10 | Histogram bimodality weight |
| `COMPOSITE_THRESHOLD` | 0.28 | Minimum composite to pass |
| `GEOMETRIC_VETO_THRESHOLD` | 0.10 | Minimum geometric signal to avoid veto |

### Quality Assessment

| Constant | Value | Description |
|----------|-------|-------------|
| `USABLE_IRIS_THRESHOLD` | 0.50 | Rear camera usable iris minimum |
| `USABLE_IRIS_THRESHOLD_FRONT` | 0.35 | Front camera usable iris minimum |
| `GAZE_DISPLACEMENT_THRESHOLD` | 0.25 | Maximum gaze offset |
| `PUPIL_RATIO_MIN` / `MAX` | 0.20 / 0.70 | Valid pupil-to-iris ratio range |
| `CONTRAST_THRESHOLD` | 0.40 | Minimum iris-pupil contrast |
| `UNIFORMITY_THRESHOLD` | 0.50 | Minimum illumination uniformity |
| `MOTION_BLUR_THRESHOLD` | 4.0 | Maximum gradient anisotropy |
| `COMPOSITE_PASS_THRESHOLD` | 40 | Minimum weighted composite score |

---

## 25. Data Flow Diagram

```
┌─────────────┐
│ Camera2 API │
│  (1x zoom)  │
└──────┬──────┘
       │ JPEG bytes + RAW image
       ▼
┌─────────────────────┐
│ EyeImageCropper     │──── BitmapRegionDecoder
│ cropFromStored      │     (ROI only, ~50ms)
│ Coordinates()       │
└──────┬──────────────┘
       │ EyeCropResult (cropped bitmap + coordinates)
       ▼
┌─────────────────────┐
│ EyePresenceDetector │──── 5 signals (~5-10ms)
│ detect()            │     Geometric veto check
└──────┬──────────────┘
       │ PresenceResult (detected? + scores)
       │ [If NOT detected → reject, show "No eye detected"]
       ▼
┌─────────────────────┐
│ SharpnessAnalyzer   │──── Laplacian + Tenengrad (~30-50ms)
│ calculateSharpness()│     Single-pass shared grayscale
└──────┬──────────────┘
       │ sharpness score (double)
       ▼
┌─────────────────────┐
│ IrisQualityAssessor │──── 7 ISO metrics (~20-40ms)
│ assess()            │     Composite + critical check
└──────┬──────────────┘
       │ IrisQualityResult (pass/fail + scores)
       │ [If FAIL → reject, show failed metrics]
       ▼
┌─────────────────────────────────┐
│ Save (parallel coroutines)      │
│ ┌───────────────────────┐       │
│ │ JPEG: saveCroppedImage│       │
│ │ WithMetadata()        │ async │
│ └───────────────────────┘       │
│ ┌───────────────────────┐       │
│ │ RAW: savePendingRaw() │ async │
│ └───────────────────────┘       │
└─────────────────────────────────┘
       │
       ▼
  Pictures/FaceLandmarker/
  ├── 001_Tele_R_3_R_Q72.jpg     (cropped JPEG + EXIF metadata)
  └── 001_Tele_R_3_R_Q72_raw.dng (full-sensor RAW + JSON description)
```

---

## 26. Known Limitations

1. **No real-time iris tracking:** The app uses manual alignment (user centers eye in target). There is no continuous iris detection during preview.

2. **EXIF rotation is device-specific:** The `effectiveRotation` calculation in `EyeImageCropper` was tuned for Pixel and Samsung devices. Other manufacturers may need additional rotation corrections.

3. **Eye presence detector tuning:** The composite threshold (0.28) and geometric veto threshold (0.10) may need adjustment for specific capture conditions (e.g., very dark environments, extreme close-up distances).

4. **No eyelid landmark detection:** Since MediaPipe was removed, the quality assessor uses intensity-based fallback for usable iris area estimation instead of precise eyelid polylines. Gaze angle defaults to a moderate score (0.15) without landmarks.

5. **Front camera limitations:** No flash available, lower sharpness thresholds, wider contrast tolerance. Front camera images are inherently lower quality than rear cameras for iris capture.

6. **Burst mode is legacy:** The primary capture path is single-capture (`takeSingleCapture` → `processAndCropCenterBased`). Burst capture (`takeBurstWithBestFrame`) and focus bracketing (`takeFocusBracketBurst`) code is retained but not actively used in the main flow.

7. **RAW not available on all lenses:** Some physical camera IDs (especially telephoto on certain devices) may not support RAW_SENSOR format. The app gracefully falls back to JPEG-only.

8. **Session timeout is absolute:** The `totalSessionTimeoutMs` covers both eyes combined, so a slow right-eye session may leave insufficient time for the left eye.

9. **Package name is legacy:** The package `com.google.mediapipe.examples.facelandmarker` was inherited from the original TensorFlow/MediaPipe codebase. All ML dependencies have been removed, but the package name was retained for compatibility.

10. **Portrait mode only:** All capture modes currently force portrait orientation. The landscape mode constants (`USE_LANDSCAPE_FOR_*`) are all set to `false`.
