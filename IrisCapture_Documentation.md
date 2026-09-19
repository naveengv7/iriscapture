# IrisCapture - Comprehensive Application Documentation

**Version:** 1.0
**Date:** February 2026, last revised 2026-09-19 (see [Changelog](#27-changelog))
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
17. [Iris Quality Assessor (ISO 29794-6 Inspired)](#17-iris-quality-assessor-iso-29794-6-inspired)
18. [Iris Metadata & EXIF Embedding](#18-iris-metadata--exif-embedding)
19. [RAW (DNG) Capture](#19-raw-dng-capture)
20. [Overlay View](#20-overlay-view)
21. [Image Save Pipeline](#21-image-save-pipeline)
22. [Performance Optimizations](#22-performance-optimizations)
23. [Device Compatibility](#23-device-compatibility)
24. [Configuration Constants Reference](#24-configuration-constants-reference)
25. [Data Flow Diagram](#25-data-flow-diagram)
26. [Known Limitations](#26-known-limitations)
27. [Changelog](#27-changelog)

---

## 1. Application Overview

IrisCapture is an Android application designed for research-grade iris biometric image capture. It uses the Camera2 API directly with no machine learning dependencies. The application captures high-resolution iris images from both eyes using manual alignment (user centers their eye in an on-screen target), applies a multi-stage quality pipeline, and saves only images that pass all quality checks.

**Key characteristics:**
- Zero ML dependencies (no TensorFlow, no MediaPipe at runtime)
- Camera2 API for full manual control over focus, exposure, ISO, and OIS
- 3 capture modes: Telephoto, Main Camera, Front Camera
- 5-signal heuristic eye presence detection (no false saves on backgrounds)
- ISO 29794-6 inspired quality assessment (6 metrics; a 7th, gaze angle, was removed on 2026-09-19)
- Laplacian variance sharpness analysis
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
| `MainActivity.kt` | 36 | Single-activity host with ViewBinding |
| `MainViewModel.kt` | 32 | Shared ViewModel: participantId, imagesPerEye |
| `IrisMetadata.kt` | 199 | EXIF-embeddable metadata data class with JSON serialization |
| `EyeImageCropper.kt` | 211 | BitmapRegionDecoder-based ROI extraction with EXIF rotation handling |
| `EyePresenceDetector.kt` | 672 | 5-signal heuristic eye detection (no ML) |
| `SharpnessAnalyzer.kt` | 128 | Laplacian variance sharpness scoring |
| `IrisQualityAssessor.kt` | 720 | ISO 29794-6 inspired quality assessment (6 metrics) |
| `OverlayView.kt` | 391 | Custom View for iris target, crosshair, quality panel |

### Fragment Files (`fragment/`)

| File | Lines | Description |
|------|-------|-------------|
| `LoginFragment.kt` | 48 | Participant ID and images-per-eye input |
| `PermissionsFragment.kt` | 92 | Camera permission request |
| `ModeSelectionFragment.kt` | 74 | Capture mode selection cards |
| `CameraFragment.kt` | ~3039 | Main camera pipeline, capture loop, focus, save |

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
- **Participant ID:** `EditText`, 3-digit numeric ID (validated: exactly 3 characters)
- **Images per Eye:** `EditText`, number input, range 1-20, default 5
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

**File:** `CameraFragment.kt` (~3039 lines)

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
   i. SharpnessAnalyzer.calculateSharpness() → Laplacian variance
   j. IrisQualityAssessor.assess() → 6 quality metrics
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
- **Capture zoom:** 1x (full sensor); iris coordinates map preview-to-capture
- **Focus:** Auto with 6% metering region, or Manual at minimum focus distance
- **Flash:** Torch mode on rear cameras, turned on AFTER focus lock
- **OIS:** Enabled if available (`LENS_OPTICAL_STABILIZATION_MODE_ON`)
- **ISP bypass:** Edge enhancement OFF, noise reduction OFF, hot pixel OFF, shading OFF
- **Iris radius:** `0.15 / previewZoom` (see "Iris radius derivation" below): `0.15` on a physical
  telephoto lens driven at 1x, `0.05` when telephoto is reached by 3x zoom on the main camera

### Mode: Main Camera (`MODE_MAIN_8X`)

- **Lens selection:** Main camera (focal 5-10mm)
- **Preview zoom:** 4x digital
- **Capture zoom:** 1x (full sensor)
- **Focus:** Auto with standard metering
- **Flash:** Torch mode
- **ISP:** Standard settings (edge HIGH_QUALITY, noise FAST)
- **Iris radius:** `0.15 / 4.0 = 0.0375` (see "Iris radius derivation" below)

### Mode: Front Camera (`MODE_FRONT`)

- **Lens selection:** Front camera with narrowest FOV (largest focal length)
- **Preview zoom:** 1.25x
- **Capture zoom:** 1x
- **Focus:** Continuous auto
- **Flash:** None (no front flash)
- **ISP:** Standard settings
- **Iris radius:** `0.15 / 1.25 = 0.12` (see "Iris radius derivation" below)
- **Expand factor:** 1.2x (tighter crop than rear cameras)

### Iris radius derivation

The normalized iris radius handed to `EyeImageCropper` is not a per-mode constant. Until
2026-09-19 it was a hardcoded `when (captureMode)` block whose values were guesses (only the
`MAIN_8X` branch had actually been derived); it is now computed in `processAndCropCenterBased()`
from the zoom the preview is really running at:

```kotlin
irisNormRadius = MIN_IRIS_SIZE_FRACTION / previewZoom   // MIN_IRIS_SIZE_FRACTION = 0.15f
```

**Why the division.** The participant aligns their iris to the on-screen target that `OverlayView`
draws at radius `MIN_IRIS_SIZE_FRACTION` (0.15) of the display frame width, and they see that
target through the *preview*, which runs at the mode's zoom level. `takeSingleCapture()` however
forces the still to 1x over the full active array, so every feature in the frame shrinks by the
preview zoom factor Z when it lands in the still. The correct normalized radius in the captured
still is therefore the on-screen fraction divided by the preview zoom, not the on-screen fraction
itself.

**Source of `previewZoom`.** `CONTROL_ZOOM_RATIO` is read back from the live preview request
(`previewRequestBuilder`), so the value tracks whatever the zoom-selection logic actually applied,
including the telephoto fallback path where `telephotoZoomLevel` is set to the device's `maxZoom`.
`CONTROL_ZOOM_RATIO` only exists from API 30, so below that the code falls back to the per-mode
zoom constant the mode asks `setZoom()` for. A final guard catches an unset, zero, negative or NaN
zoom by falling back to the bare on-screen target size (0.15).

**Values before and after the correction:**

| Mode | Preview zoom | Old (hardcoded) | Current (derived) |
|------|--------------|-----------------|-------------------|
| Telephoto, physical lens | 1.0x | 0.06 | **0.15** |
| Telephoto, zoom-based | 3.0x | 0.06 | **0.05** |
| Telephoto, fallback device | `maxZoom` | 0.06 | **0.15 / maxZoom** |
| Main Camera | 4.0x | 0.0375 | 0.0375 (unchanged) |
| Front Camera | 1.25x | 0.05 | **0.12** |

This radius drives every polar sampling grid in `IrisQualityAssessor` as well as the crop
rectangle, so the corrected values change quality scores on all modes except `MAIN_8X`. The
derived values still need confirmation against real captures on a device.

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

    val qualityFromBurst = performTelephotoCaptureSingle(isRightEye)
    qualityCount += qualityFromBurst  // 0 or 1; the variable name is a leftover,
                                      // the call performs a single capture

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
- **Light irises:** Actually easier, with stronger limbal contrast with both sclera and pupil.
- **Light skin:** Sclera detection requires brightness contrast (30+) between center and flanks, preventing false positives from light skin.

---

## 16. Sharpness Analyzer

**File:** `SharpnessAnalyzer.kt` (128 lines)
**Type:** `object` singleton

### Algorithm

There is exactly one sharpness metric: **the variance of the Laplacian response**.

- Kernel: `[0, 1, 0; 1, -4, 1; 0, 1, 0]`
- Score = variance of the Laplacian response across the ROI
- The Laplacian highlights rapid intensity change (edges), so a sharper image produces a
  wider spread of responses and a higher variance
- Grayscale conversion uses the standard luminance weights (0.299 R, 0.587 G, 0.114 B)

### Region of Interest

`calculateSharpness()` takes an optional `roiRect`. The live capture path passes `null`, so the
ROI defaults to the **centre 50 percent** of the supplied bitmap (`width/4 .. 3*width/4` by
`height/4 .. 3*height/4`). The bitmap it is given is already the cropped eye region produced by
`EyeImageCropper`, so the measured area is the middle of the eye crop, which is where the iris
sits. The ROI is clamped to the bitmap bounds and the call returns 0.0 if fewer than 3 pixels
remain on a side.

The byte-array overload (`calculateSharpness(jpegBytes, roiRect)`) decodes with
`inSampleSize = 2` before measuring. It is not used by the live capture path.

### Correction on 2026-09-19: the Tenengrad blend never ran

Earlier revisions of this document described a combined score of
`0.3 x Laplacian + 0.7 x Tenengrad`, computed by `calculateSharpnessForIris()` /
`calculateCombinedSharpness()` with a `BitmapRegionDecoder` ROI-only decode path. **That blend was
never on the live path.** Its only caller was the burst-capture path, which had no reachable entry
point (see section 26), so the metric actually gating every capture was, and still is, plain
Laplacian variance. The blend, the Tenengrad implementation, the `LAPLACIAN_WEIGHT` /
`TENENGRAD_WEIGHT` constants and the iris-specific decode path have been deleted rather than left
in place looking like the metric in use.

The thresholds below are unaffected: they were always tuned against plain Laplacian variance, so
they remain valid. Do not blend another score into this metric without re-tuning them.

### Sharpness Thresholds

| Camera | Threshold | Rationale |
|--------|-----------|-----------|
| Rear (telephoto, main) | 50.0 | Better optics, higher resolution |
| Front | 20.0 | Smaller sensor, softer lens (~60% of rear quality) |

---

## 17. Iris Quality Assessor (ISO 29794-6 Inspired)

**File:** `IrisQualityAssessor.kt` (720 lines)
**Type:** `object` singleton

The source describes itself as "ISO 29794-6 inspired". This is **not** a conformant implementation
of that standard and no conformance is claimed. Treat the composite score as an acceptance gate
internal to this application.

### Six Quality Metrics

| # | Metric | Weight | Critical | Threshold | Description |
|---|--------|--------|----------|-----------|-------------|
| 1 | Usable Iris Area | 0.2222 | Yes | 50% (rear), 35% (front) | Fraction of iris annulus not occluded by eyelids |
| 2 | Pupil-to-Iris Ratio | 0.1111 | No | 0.20 - 0.70 | Optimal at 0.45 |
| 3 | Iris-Pupil Contrast | 0.1667 | Yes | ≥ 0.40 (rear), ≥ 0.10 (front) | Weber contrast between pupil and iris zones |
| 4 | Illumination Uniformity | 0.1111 | No | ≥ 0.50 | 8-sector angular uniformity, specular penalty |
| 5 | Motion Blur | 0.1667 | No | ≤ 6.0 anisotropy (provisional) | Gradient orientation histogram peak / full-histogram mean |
| 6 | Sharpness | 0.2222 | Yes | Mode-specific (see section 16) | Reuses the SharpnessAnalyzer score |

The weights sum to 1.0, so the composite stays on a 0 to 100 scale.

### Removed on 2026-09-19: Gaze Angle

A seventh metric, **Gaze Angle** (weight 0.10, critical, threshold 0.25 displacement), used to sit
between metrics 1 and 2. It measured the offset of the iris centre from the midpoint of the eye
corners, normalized by eye width, which requires eyelid landmarks. Those landmarks disappeared when
MediaPipe was removed: `EyeImageCropper` always passes `upperEyelidPoints = lowerEyelidPoints =
null`, so `computeGazeAngle()` returned a hard-coded `0.15f` on every image. That produced a
constant normalized score of exactly 70.0, a critical gate that could never fail, and a fixed
contribution of +7.0 to every composite score.

The metric, its `computeGazeAngle()` implementation and its `GAZE_DISPLACEMENT_THRESHOLD` constant
were deleted. The six surviving weights were **renormalised by dividing each by 0.90** (the weight
sum left after removing gaze), so they still add to 1.0 and `COMPOSITE_PASS_THRESHOLD` remains
meaningful at `40f` on the same 0 to 100 scale:

| Old weight | New weight | Metrics |
|------------|------------|---------|
| 0.20 | 0.2222 | Usable Iris, Sharpness |
| 0.15 | 0.1667 | Contrast, Motion Blur |
| 0.10 | 0.1111 | Pupil Ratio, Uniformity |

If eyelid landmarks ever become available again, the metric can be re-added and the weights
renormalised once more.

### Pass/Fail Logic

```
compositeScore = Σ(metric.normalizedScore × metric.weight)
allCriticalPassed = all critical metrics individually pass
overallPassed = (compositeScore >= 40) AND allCriticalPassed
```

**Critical metrics (must ALL pass individually), three of them:** Usable Iris, Contrast,
Sharpness. Gaze Angle used to be the fourth.

### Metric Details

#### Metric 1: Usable Iris Area
- **With landmarks:** Polar grid sampling in iris annulus, check against eyelid polylines
- **Intensity fallback (no landmarks):** Radial scan, count dark pixels (iris-like intensity)
  - Rear camera threshold: intensity < 180 (flash makes iris darker)
  - Front camera threshold: intensity < 220 (ambient light makes iris brighter)

#### Metric 2: Pupil-to-Iris Ratio
- Radial intensity profile from iris center outward (36 angles × 20 radial steps)
- Peak gradient = pupil-iris boundary
- Optimal: 0.45 (center of 0.20-0.70 range)

#### Metric 3: Iris-Pupil Contrast
- Weber contrast: `|meanIris - meanPupil| / (meanPupil + 1)`
- Samples inner 80% of pupil zone and iris annulus separately

#### Metric 4: Illumination Uniformity
- Divides iris annulus into 8 angular sectors
- Uniformity = `1 - (maxSectorMean - minSectorMean) / (maxSectorMean + 1)`
- Specular penalty: pixels > 242 brightness

#### Metric 5: Motion Blur (corrected 2026-09-19)

Gradient orientation anisotropy over the iris bounding box, weighted by gradient magnitude:

- **36 bins over `[0, pi)`**, so each bin spans **5 degrees**. Gradient orientation is mod pi, not
  mod 2pi, so the `atan2` result is folded into `[0, pi)` before binning.
- Sobel gradients; samples with magnitude below 5 are skipped. An ROI smaller than 5 by 5 pixels
  returns the isotropic floor.
- **Anisotropy = peak bin / mean over the FULL histogram**, giving a range of **1.0** (every bin
  equal, perfectly isotropic, no directional blur) to **36.0** (all gradient energy in a single
  5 degree bin, perfectly directional).
- Low anisotropy = sharp (gradients point every which way). High anisotropy = motion blur (one
  dominant edge orientation).

**Score mapping** is anchored at the isotropic floor rather than at 0, because 0 is not reachable
by the metric:

```
excess   = anisotropy - 1.0                     // 0 at perfect isotropy
headroom = MOTION_BLUR_THRESHOLD - 1.0          // the excess at the threshold
score    = (1 - excess / (2 * headroom)) * 100  // clamped to 0..100
```

So the score is 100 at anisotropy 1.0, exactly 50 at the threshold, 0 at `2 * threshold - 1`
(11.0 with the current threshold), and decreases monotonically in between.

**Two bugs were fixed here on 2026-09-19:**

1. The mean was taken over the **non-zero bins only**, which cancels exactly the concentration
   the metric exists to detect. An isotropic histogram (all bins `v`) gave `v / v = 1.0`, and a
   fully directional one (one bin `V`, the rest zero) also gave `V / V = 1.0`. The metric
   therefore returned about 1.0 for a sharp image and for a fully motion-blurred one alike, and
   passed unconditionally. The mean is now taken over the full histogram.
2. Binning used `atan2` over the full 2pi range although gradient orientation is mod pi, so a
   single straight edge populated two opposite bins and halved the measured peak. The angle is
   now folded first. The bin count is unchanged at 36, so bins are 5 degrees wide, not 10.

**`MOTION_BLUR_THRESHOLD` moved from 4.0 to 6.0. This is a provisional, reasoned value, not an
empirical one, and it requires re-tuning against a real capture set before the metric is trusted.**
The old threshold of 4.0 carried no information, because the old formula was pinned near 1.0
regardless of input, so there is no historical baseline to calibrate against. 6.0 means the
dominant orientation bin carries 6x the mean bin energy, roughly 17 percent of all gradient energy
inside one 5 degree band, which is a strong directional signature. The in-file comment carries the
same warning.

#### Metric 6: Sharpness
- Reuses the `SharpnessAnalyzer` Laplacian variance score computed on the cropped eye image
- Normalized score = `(sharpness / threshold) * 50`, clamped to 0..100, so the mode threshold
  maps to exactly 50 and passing the threshold is equivalent to scoring at least 50
- If the threshold is not positive, the normalized score defaults to 50

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
  "irisSensorCoordinates": { "centerX": 2016.0, "centerY": 1512.0, "radius": 453.6 },
  "irisNormalized": { "x": 0.5, "y": 0.5, "radius": 0.15 },
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
| Quality Panel | After quality pass | Semi-transparent black panel showing the six quality metric scores |
| Center Crosshair | When no iris target | Simple green crosshair (+) at center |
| Focus Ring | During focus | Green rectangle around focus area |

### Quality Panel Layout

```
┌─────────────────────────┐
│ IRIS QUALITY: 72/100 PASS│
│─────────────────────────│
│ Usable Iris   92   PASS │
│ Pupil Ratio   85   PASS │
│ Contrast      65   PASS │
│ Uniformity    78   PASS │
│ Motion Blur   90   PASS │
│ Sharpness     52   PASS │
└─────────────────────────┘
```

The rows are rendered from `qualityResult.metrics` in list order, so the panel now shows six rows:
the Gaze Angle row disappeared with the metric on 2026-09-19. The panel auto-hides 3 seconds after
a quality image is saved.

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
| Alignment delay | 4000ms (first) / 500ms (subsequent) | Adaptive: user already positioned after first attempt |
| Focus settling | 300-500ms (first) / skipped (subsequent) | AF runs during alignment delay |
| Focus lock | ≤2000ms | Reduced timeout from 3000ms, 2 retries (down from 3) |
| Flash stabilization | 150ms | Reduced from 1000ms (minimize pupil constriction) |
| Capture | ~200ms | Single shot |
| Eye crop | ~50-100ms | BitmapRegionDecoder (ROI only, not full 12MP) |
| Eye presence check | ~5-10ms | Shared grayscale, efficient sampling |
| Sharpness | ~30-50ms | Single Laplacian pass over the centre 50% of the already-cropped eye image |
| Quality assessment | ~20-40ms | Polar grid sampling |
| JPEG save | ~50ms | MediaStore ContentResolver |
| RAW save | ~1-3s | Parallel with JPEG via coroutine async |
| Inter-attempt delay | 500ms | Reduced from 2000ms |

**Estimated total per attempt:** ~1.5s (subsequent) to ~5s (first)

### Memory Optimizations
- **BitmapRegionDecoder** for ROI extraction in `EyeImageCropper` (never decodes the full 12MP
  image into memory)
- **Sharpness runs on the already-cropped eye bitmap**, so it needs no decode of its own (the
  separate ROI-decoding sharpness path was deleted on 2026-09-19 as unreachable)
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
| `MIN_IRIS_SIZE_FRACTION` | 0.15 | On-screen iris target radius; also the numerator of the iris radius derivation (section 9) |

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
| `PUPIL_RATIO_MIN` / `MAX` | 0.20 / 0.70 | Valid pupil-to-iris ratio range |
| `CONTRAST_THRESHOLD` | 0.40 | Minimum iris-pupil contrast |
| `UNIFORMITY_THRESHOLD` | 0.50 | Minimum illumination uniformity |
| `MOTION_BLUR_THRESHOLD` | 6.0 | Maximum gradient anisotropy. **Provisional, needs empirical re-tuning** (was 4.0, a value that carried no information because the old formula was degenerate) |
| `MOTION_BLUR_NUM_BINS` | 36 | Orientation bins over `[0, pi)`, 5 degrees each; also the metric's upper bound |
| `MOTION_BLUR_ISOTROPIC` | 1.0 | The metric's lower bound, a perfectly isotropic histogram |
| `COMPOSITE_PASS_THRESHOLD` | 40 | Minimum weighted composite score |

`GAZE_DISPLACEMENT_THRESHOLD` (0.25) was deleted on 2026-09-19 with the Gaze Angle metric.

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
│ SharpnessAnalyzer   │──── Laplacian variance (~30-50ms)
│ calculateSharpness()│     Centre 50% of the eye crop
└──────┬──────────────┘
       │ sharpness score (double)
       ▼
┌─────────────────────┐
│ IrisQualityAssessor │──── 6 quality metrics (~20-40ms)
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

4. **No eyelid landmark detection:** Since MediaPipe was removed, the quality assessor uses the intensity-based fallback for usable iris area estimation instead of precise eyelid polylines. The landmark path in `computeUsableIrisArea()` is retained but is never taken, because `EyeImageCropper` always passes null eyelid point lists. The Gaze Angle metric depended entirely on those landmarks and was removed on 2026-09-19 rather than left returning a constant (see section 17).

5. **Front camera limitations:** No flash available, lower sharpness thresholds, wider contrast tolerance. Front camera images are inherently lower quality than rear cameras for iris capture.

6. **Burst and focus-bracket paths are gone:** The only capture path is single-capture (`takeSingleCapture` → `processAndCropCenterBased`). The burst path (`takeBurstWithBestFrame`), the focus-bracketing path (`takeFocusBracketBurst`) and their `FOCUS_BRACKET_*` constants were deleted on 2026-09-19 after their unreachability was established: the `B` button only toggles its own colour, `setupModeUI()` sets it to `View.GONE` in every mode, and the burst-active flag could never become true. The burst path was also already broken, since the iris coordinates it fed to the sharpness analyzer were never assigned anywhere. `isBurstMode` survives as an unread capture-state flag and the hidden `B` button still toggles it.

7. **RAW not available on all lenses:** Some physical camera IDs (especially telephoto on certain devices) may not support RAW_SENSOR format. The app gracefully falls back to JPEG-only.

8. **Session timeout is absolute:** The `totalSessionTimeoutMs` covers both eyes combined, so a slow right-eye session may leave insufficient time for the left eye.

9. **Package name is legacy:** The package `com.google.mediapipe.examples.facelandmarker` was inherited from the original TensorFlow/MediaPipe codebase. All ML dependencies have been removed, but the package name was retained for compatibility.

10. **Portrait mode only:** All capture modes currently force portrait orientation. The landscape mode constants (`USE_LANDSCAPE_FOR_*`) are all set to `false`.

11. **`MOTION_BLUR_THRESHOLD` is provisional:** The corrected motion-blur metric now spans 1.0 to 36.0, but its threshold of 6.0 is a reasoned starting point rather than a measured one. The previous value could not be carried over, because the formula it was chosen against returned roughly 1.0 for every image, so no historical baseline exists. It must be re-tuned against a real capture set before the metric is trusted.

12. **The derived iris radius needs device validation:** The normalized iris radius is now computed from the live preview zoom (section 9) instead of per-mode literals. The derivation is sound, but the resulting values have not yet been confirmed against real captures, and they moved substantially on two modes (telephoto on a physical lens, 0.06 to 0.15, and front camera, 0.05 to 0.12).

---

## 27. Changelog

### 2026-09-19: four quality-metric defects fixed

All four defects were verified against the source before being fixed. **Composite quality scores
produced before this date are not comparable with those produced after it:** the Gaze Angle
metric's fixed +7.0 contribution is gone, every remaining weight scales by 1/0.9, and the
motion-blur sub-score now varies across its full range instead of sitting in a compressed band.
Quality figures recorded in filenames (`Q{score}`) and in EXIF metadata carry no marker of which
scale they were produced on, so date the capture session to tell them apart.

1. **Gaze Angle removed; six metrics remain, not seven.** `computeGazeAngle()` returned a
   hard-coded `0.15f` whenever eyelid landmarks were absent, and `EyeImageCropper` always passes
   null for them because MediaPipe was removed. The metric therefore scored exactly 70.0 on every
   image, and its critical gate could never fail. The metric, its implementation and
   `GAZE_DISPLACEMENT_THRESHOLD` were deleted. The remaining six weights were renormalised by
   dividing by 0.90 (0.2222 usable iris, 0.1111 pupil ratio, 0.1667 contrast, 0.1111 uniformity,
   0.1667 motion blur, 0.2222 sharpness), so they still sum to 1.0, the composite stays on 0..100
   and `COMPOSITE_PASS_THRESHOLD` remains 40f. The critical-gate set dropped from four metrics to
   three. See section 17.

2. **Motion blur corrected.** Anisotropy used a mean over non-zero histogram bins only, which
   returned about 1.0 for a sharp image and for a fully blurred one alike, so the metric passed
   unconditionally. It now uses the full-histogram mean, and gradient orientation is folded to
   `[0, pi)` before binning, making the 36 bins 5 degrees wide rather than 10. The value range is
   now 1.0 (isotropic) to 36.0 (fully directional), and the score mapping was re-anchored to give
   100 at 1.0 and 50 at the threshold. `MOTION_BLUR_THRESHOLD` moved from 4.0 to 6.0, **a
   provisional judgement call that requires empirical re-tuning** (see limitation 11). See
   section 17.

3. **Sharpness documentation corrected; dead code removed.** This document previously described a
   `0.3 x Laplacian + 0.7 x Tenengrad` blend. **That blend never ran.** The live metric is, and
   always was, plain Laplacian variance over the centre 50 percent of the crop. The blend, the
   `LAPLACIAN_WEIGHT` / `TENENGRAD_WEIGHT` constants and the iris-specific ROI decode path were
   deleted along with the unreachable burst and focus-bracket capture paths that were their only
   callers. The thresholds (50.0 rear, 20.0 front) are unchanged and remain valid, because they
   were always tuned against plain Laplacian variance. See sections 16 and 26.

4. **Iris radius derived instead of hardcoded.** The per-mode `when` block was replaced by
   `MIN_IRIS_SIZE_FRACTION / previewZoom`, reading `CONTROL_ZOOM_RATIO` back from the live preview
   request, with a per-mode fallback below API 30 and a guard against zero, negative and NaN
   zooms. Corrected values: telephoto on a physical lens 0.06 to 0.15, front camera 0.05 to 0.12,
   zoom-based telephoto 0.06 to 0.05, `MAIN_8X` unchanged at 0.0375, and fallback devices now
   track `maxZoom` instead of a fixed literal. See section 9.
