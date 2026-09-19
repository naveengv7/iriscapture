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
- Camera2 API used directly for focus, zoom, flash and lens selection. **Manual exposure and
  ISO are not actually applied:** `SENSOR_SENSITIVITY` and `SENSOR_EXPOSURE_TIME` are set
  nowhere, and the ISO and shutter constants only appear in a log line (section 24)
- 3 capture modes: Telephoto, Main Camera, Front Camera
- 5-signal heuristic eye presence detection (no false saves on backgrounds)
- ISO 29794-6 inspired quality assessment (6 metrics; a 7th, gaze angle, was removed on 2026-09-19)
- Laplacian variance sharpness analysis
- Dual-format output: cropped JPEG + full-sensor RAW (DNG) with embedded metadata
- Images-per-eye field on the login screen (1-20, default 5), validated and stored but **not
  yet wired into the capture loop**, which works to a fixed 5 (see section 6)
- Automatic right-eye-then-left-eye session management

**Package:** `edu.clarkson.iriscapture` (renamed on 2026-09-19 from the inherited
`com.google.mediapipe.examples.facelandmarker`; see the changelog)

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

### Source Files (`app/src/main/java/edu/clarkson/iriscapture/`)

| File | Lines | Description |
|------|-------|-------------|
| `MainActivity.kt` | 36 | Single-activity host with ViewBinding |
| `MainViewModel.kt` | 42 | Shared ViewModel: participantId, imagesPerEye |
| `IrisMetadata.kt` | 199 | EXIF-embeddable metadata data class with JSON serialization |
| `EyeImageCropper.kt` | 216 | BitmapRegionDecoder-based ROI extraction with EXIF rotation handling |
| `EyePresenceDetector.kt` | 672 | 5-signal heuristic eye detection (no ML) |
| `SharpnessAnalyzer.kt` | 108 | Laplacian variance sharpness scoring |
| `IrisQualityAssessor.kt` | 771 | ISO 29794-6 inspired quality assessment (6 metrics) |
| `OverlayView.kt` | 391 | Custom View for iris target, crosshair, quality panel |

### Fragment Files (`fragment/`)

| File | Lines | Description |
|------|-------|-------------|
| `LoginFragment.kt` | 60 | Participant ID and images-per-eye input |
| `PermissionsFragment.kt` | 92 | Camera permission request |
| `ModeSelectionFragment.kt` | 74 | Capture mode selection cards |
| `CameraFragment.kt` | 2843 | Main camera pipeline, capture loop, focus, save |

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
- Participant ID must be exactly **3** digits, and the layout caps the field at `maxLength="3"`
- Images per eye must be an integer between 1 and 20 inclusive
- Both checks live in `LoginFragment`; a failure raises a `Toast` and blocks navigation
- `MainViewModel.setImagesPerEye()` stores the value exactly as given and does **not** clamp it,
  so the 1-20 range is enforced only by the fragment

**Restored 2026-09-19.** The build immediately before this revision required a **6**-digit ID
while the layout capped the field at three characters, so ENTER could never succeed and the app
could not get past its first screen. The same build ignored the images-per-eye field entirely.
Both the 3-digit rule and the images-per-eye validation were restored.

### Images per eye is stored but not yet used

**Corrected 2026-09-19.** This section previously claimed that the images-per-eye value scaled
three properties in `CameraFragment`: the target image count, the attempt cap and a session
timeout. **That was false.** `LoginFragment` validates the value and `MainViewModel` holds it, but
`CameraFragment` never reads `viewModel.imagesPerEye`. What the capture loop actually uses:

| Property | Actual value | Scales with images per eye? |
|----------|--------------|------------------------------|
| Target accepted images per eye | `TARGET_QUALITY_IMAGES_PER_EYE`, a compile-time constant of 5 | No |
| Attempt cap per eye | `MAX_CAPTURE_ATTEMPTS_PER_EYE`, a compile-time constant | No |

Every session therefore collects **5** accepted images per eye whatever the operator enters.
Wiring the stored value through to the loop is outstanding work, not current behaviour.

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

**File:** `CameraFragment.kt` (2843 lines)

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
- **OIS:** `LENS_OPTICAL_STABILIZATION_MODE_ON` is applied **to the preview request only**,
  never to the still capture request (see "ISP and OIS never reached a saved image" below)
- **ISP bypass:** **not applied.** No saved image has ever had it (see below)
- **Iris radius:** `0.15 / previewZoom` (see "Iris radius derivation" below): `0.15` on a physical
  telephoto lens driven at 1x, `0.05` when telephoto is reached by 3x zoom on the main camera

### Mode: Main Camera (`MODE_MAIN_8X`)

- **Lens selection:** Main camera (focal 5-10mm)
- **Preview zoom:** 4x digital
- **Capture zoom:** 1x (full sensor)
- **Focus:** Auto with standard metering
- **Flash:** Torch mode
- **ISP:** whatever `TEMPLATE_STILL_CAPTURE` defaults to; the app sets no ISP keys on the still
  request (see below)
- **Iris radius:** `0.15 / 4.0 = 0.0375` (see "Iris radius derivation" below)

### Mode: Front Camera (`MODE_FRONT`)

- **Lens selection:** Front camera with narrowest FOV (largest focal length)
- **Preview zoom:** 1.25x
- **Capture zoom:** 1x
- **Focus:** Continuous auto
- **Flash:** None (no front flash)
- **ISP:** whatever `TEMPLATE_STILL_CAPTURE` defaults to; the app sets no ISP keys on the still
  request (see below)
- **Iris radius:** `0.15 / 1.25 = 0.12` (see "Iris radius derivation" below)
- **Expand factor:** 1.2x (tighter crop than rear cameras)

### ISP and OIS never reached a saved image

**Corrected 2026-09-19.** Earlier revisions of this document listed an ISP bypass and a
capture-side OIS enable as active tuning on the telephoto mode. **They were never applied to any
captured image.** `EDGE_MODE_OFF`, `NOISE_REDUCTION_MODE_OFF`, `SHADING_MODE_OFF`, the hot-pixel
setting, the capture-side OIS enable and the AE/flash copying all lived in
`applyCommonCaptureSettings()` and `applyOptimalExposureSettings()`, which were only ever reachable
from the `takePicture` chain. That chain was unreachable and was deleted on 2026-09-19, and the
helpers went with it. Every image this application has ever saved therefore went through **full ISP
processing, including denoising and edge enhancement**, which for a study of iris texture is a
material fact about the existing data as well as about current behaviour.

What the live still request, `takeSingleCapture()`, actually sets on its
`TEMPLATE_STILL_CAPTURE` builder is only: the JPEG and (when supported) RAW targets,
`CONTROL_AF_MODE_CONTINUOUS_PICTURE`, `JPEG_QUALITY = 100`, `CONTROL_ZOOM_RATIO` at 1x and
`SCALER_CROP_REGION` covering the full active array. Everything else, ISP included, is left at the
template default.

Consequences worth recording:

- `DISABLE_ISP_FOR_TELEPHOTO` (still declared `true`) is now an **orphaned constant**: it is
  referenced nowhere in the source. Its presence in section 24 is a record of a setting that does
  nothing.
- `USE_OIS_FOR_TELEPHOTO` (also `true`) is still live, but only where it sets
  `LENS_OPTICAL_STABILIZATION_MODE_ON` on the **preview** request builder. The still request
  carries no OIS key of its own.
- Flash is likewise set only on the preview request, as `FLASH_MODE_TORCH`. Torch is a continuous
  state rather than a per-frame flash, so the scene is lit while the still is taken, but the still
  request carries no `FLASH_MODE` or AE key of its own and the app copies none across.

This is recorded here as a factual correction. Whether the tuning should be restored to the live
still path is a research decision and is **not** decided by this document; it is tracked as an open
item in limitation 14.

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
rectangle, so the corrected values change quality scores on all modes except `MAIN_8X`.

**These figures are only valid now that zoom is expressed once.** The table assumes the still is
magnified by exactly the preview zoom Z that `CONTROL_ZOOM_RATIO` reports. Until the double-zoom
fix of 2026-09-19 (section 11) that was not true: `setZoom()` set the ratio **and** narrowed the
crop region, so a conforming HAL magnified by `Z * Z` while this derivation read back `Z`. Every
derived radius was therefore additionally wrong by a further factor of Z. Telephoto on a physical
lens is the exception, because Z is 1.0 there and `Z * Z` is the same number; the zoom-based
telephoto, `MAIN_8X` and front-camera rows were all affected. Any radius, crop size or quality
score measured on a device before that fix, including the front-camera numbers in the section 23
validation record, was produced under the doubled magnification and needs re-measuring.

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

**Corrected 2026-09-19: zoom used to be applied twice.**

**API 30+ (preferred), when `zoomRatioRange` is available:** zoom is expressed **once**, through
`CONTROL_ZOOM_RATIO`. `SCALER_CROP_REGION` is then set to the **full active array**, which in
post-zoom coordinates means "the whole zoomed field of view", that is, no extra crop. It is set
explicitly rather than left out, because `previewRequestBuilder` is long lived and an omitted key
would leave whatever narrowed rectangle an earlier request had put there.

The previous code set the ratio and *also* narrowed the crop to `activeArray / z`. The Camera2
contract is that once `CONTROL_ZOOM_RATIO` is anything other than 1.0, `SCALER_CROP_REGION` is
interpreted in the **post-zoom** coordinate system, so a HAL that honours both keys reads that as a
second z-fold crop on top of the ratio. Effective magnification was `z * z`: 4x asked for, 16x
delivered. Expect the preview to look less magnified than it used to, and confirm that the
on-screen target is still fillable at a comfortable standoff distance in every mode.

**Legacy (below API 30, or no zoom ratio range):** unchanged. `CONTROL_ZOOM_RATIO` does not exist
there, so `SCALER_CROP_REGION` is the only way to zoom and it is in **active array** coordinates.
Narrowing it to `activeArray / z` around the requested centre is correct on that path, and that is
still what happens.

**Why this mattered beyond the preview.** `processAndCropCenterBased()` derives the iris radius by
reading `CONTROL_ZOOM_RATIO` back off the same builder, so it saw `z` while the still was actually
magnified by `z * z`, making every crop radius wrong by a further factor of `z`. The derivation is
only self-consistent while the ratio is the sole expression of zoom. See section 9.

**Preview versus still.** The preview runs at the mode's zoom for alignment; `takeSingleCapture()`
builds its own `TEMPLATE_STILL_CAPTURE` request at 1x over the full active array, so the still is
always full sensor.

---

## 12. Automated Capture Loop

**Entry point:** `startAutomatedCapture()` → `performQualityCaptureLoop()`

### Loop Logic

```kotlin
while (qualityCount < TARGET_QUALITY_IMAGES_PER_EYE &&
       attemptCount < MAX_CAPTURE_ATTEMPTS_PER_EYE &&
       isAutomatedCaptureRunning) {

    val qualityFromBurst = performTelephotoCaptureSingle(isRightEye)
    qualityCount += qualityFromBurst  // 0 or 1; the variable name is a leftover,
                                      // the call performs a single capture

    if (needsMore) delay(500)  // Inter-attempt pause
}
```

### Exit Conditions (any one triggers exit)
1. Accepted images reached the target count, `TARGET_QUALITY_IMAGES_PER_EYE`, a compile-time
   constant of 5 (it does not track the images-per-eye login field, see section 6)
2. Attempt count reached `MAX_CAPTURE_ATTEMPTS_PER_EYE`, a compile-time constant of 30
3. User pressed Stop, which clears `isAutomatedCaptureRunning`

**Corrected 2026-09-19: there is no session timeout.** This section previously showed a fourth
loop term, `elapsedTime < totalSessionTimeoutMs`, and listed "session timeout exceeded" as a
fourth exit condition. No such constant or elapsed-time check exists in `CameraFragment`; the only
timeout in the whole file is `FOCUS_LOCK_TIMEOUT_MS` (3000 ms), which bounds a single autofocus
wait, not the session. The two loop bounds above and the Stop button are the only things that end
a session. See limitation 8.

### Eye Switching

When the right eye finishes, the loop calls `unlock3AAndZoomOutTo1x(keepZoom = true)`, waits
`delay(1500)` and starts the left-eye loop.

**Corrected 2026-09-19, two separate errors.**

*The countdown does not exist.* This section used to describe "a 7-second countdown with status
messages: Switching to LEFT eye in Xs...". There is no countdown and no such status message in the
source. The gap between eyes is a flat 1500 ms, preceded by the closing status line of the
right-eye loop and its own 1500 ms pause, so the participant gets roughly three seconds with no
explicit instruction to switch eyes. Worth revisiting as a usability matter, but documented here as
it behaves.

*The left eye used to be captured at a different zoom from the right.* **Any telephoto data
collected before 2026-09-19 has mismatched left and right eyes.** The telephoto branch called
`unlock3AAndZoomOutTo1x(keepZoom = false)`, which reset zoom to 1x with nothing re-applying it
afterwards. The right eye was therefore captured at 3x and the left eye at 1x, against an
on-screen alignment target that is the same size for both, so the participant would have had to
stand at a completely different distance for the left eye (on a zoom-based telephoto device roughly
3x closer, likely inside the minimum focus distance), and the iris radius that
`processAndCropCenterBased()` derives from the applied preview zoom was wrong for the left eye as
well. The flag had been tied to `USE_LANDSCAPE_FOR_TELEPHOTO`, a screen-orientation constant that
has nothing to do with zoom and is `false`. The branch now passes `keepZoom = true`, matching the
`MAIN_8X` and `FRONT` branch, which always did. The 3A reset that is the real purpose of the call
(release the AE and AWB locks, cancel the AF trigger, clear the AF and AE regions, so the left eye
gets a fresh focus and metering cycle) is preserved, and the end-of-session reset back to 1x still
happens in `resetAfterCapture()`, which calls the same function with `keepZoom = false`.

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

**Corrected 2026-09-19 against the source.** The table that stood here described an adaptive
scheme (a shorter alignment delay on subsequent attempts, a skipped focus settle, a 2000 ms focus
timeout and 2 retries) that **is not in the current code**. The live values are:

| Phase | Value | Constant |
|-------|-------|----------|
| Alignment delay | 4000 ms, every attempt, not just the first | literal `delay(4000)` |
| Focus settle | 500 ms with manual focus, 1500 ms otherwise | literal `delay(if (useManualFocus) 500 else 1500)` |
| Focus lock timeout | 3000 ms | `FOCUS_LOCK_TIMEOUT_MS` |
| Focus retries | 3 | `FOCUS_RETRY_COUNT` |
| Inter-attempt pause | 2000 ms | literal `delay(2000)` |

The adaptive scheme appears to have been lost along with the rest of the working tree rather than
deliberately reverted, but it is not present, so it is not documented as behaviour. See section 22.

---

## 14. Eye Image Cropper

**File:** `EyeImageCropper.kt` (216 lines)
**Type:** `object` singleton

### Purpose
Extracts the eye region from a full-resolution JPEG using BitmapRegionDecoder (memory-efficient, no full decode).

### Input
- `jpegBytes`: Full JPEG bytes
- `irisNormX`, `irisNormY`: Normalized iris center (0-1, display space)
- `irisNormRadius`: Normalized iris radius (relative to display width)
- `expandFactor`: Padding multiplier. **Corrected 2026-09-19.** The only live caller,
  `CameraFragment.processAndCropCenterBased()`, passes **2.0x for the rear modes** (Telephoto and
  Main Camera) and **1.2x for the front camera**, so those are the values that actually run. The
  parameter's declared default of `4.0f` is never used, because every call site supplies the
  argument explicitly; this line previously reported that unused default as the rear value.
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

**`JPEG_ORIENTATION` is now set nowhere (noted 2026-09-19).** The only code that ever set
`CaptureRequest.JPEG_ORIENTATION` lived on the `takePicture` chain, which was unreachable and was
deleted on 2026-09-19. Stills therefore carry EXIF orientation **0**. That was already true before
the deletion, since the live path never ran the code that set it, but it is now structural rather
than incidental: nothing in the source can set it. The cropper does not depend on the tag being
meaningful, because it compensates through `effectiveRotation` below, and this is also why
`exifDegrees` is always 0 on the live path, which is what made the crop-axis bug (section 27,
entry 6) possible.

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

**File:** `EyePresenceDetector.kt` (672 lines)
**Type:** `object` singleton

### Purpose
Heuristic-based eye presence validation to prevent saving images when no eye is in the frame (e.g., white backgrounds, skin, random objects). Uses 5 complementary signals with no ML dependency.

**Wired into the pipeline on 2026-09-19.** This section previously described the detector as
though it had always been part of the capture path. It had not: the class existed and was
documented, but nothing called it, so no frame was ever rejected for containing no eye. Its call
site was lost with the working tree and was reconstructed on 2026-09-19 from the position it
occupied in the pre-loss build. `processAndCropCenterBased()` now invokes
`EyePresenceDetector.detect()` on the cropped eye image, **between the crop and the sharpness
measurement**, and rejects the attempt when no eye is detected, so quality assessment no longer
runs on eyeless frames. The call is wrapped in a `try/catch` because `detect()` allocates two int
arrays the size of the whole crop and an uncaught OOM there would leak the bitmap and abort the
session. The rejection behaviour is a best-faith reconstruction and should be reviewed against
intended behaviour; the signal weights and thresholds below were never lost and are unchanged.

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

**File:** `SharpnessAnalyzer.kt` (108 lines)
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

**File:** `IrisQualityAssessor.kt` (771 lines)
**Type:** `object` singleton

The source describes itself as "ISO 29794-6 inspired". This is **not** a conformant implementation
of that standard and no conformance is claimed. Treat the composite score as an acceptance gate
internal to this application.

### Six Quality Metrics

| # | Metric | Weight | Critical | Threshold | Description |
|---|--------|--------|----------|-----------|-------------|
| 1 | Usable Iris Area | 0.2222 | Yes | ≥ 0.50 unoccluded (rear), ≥ 0.35 (front) | Fraction of the iris annulus not occluded by eyelid, eyelash or glare, judged against the iris's own intensity distribution (rewritten 2026-09-19) |
| 2 | Pupil-to-Iris Ratio | 0.1111 | No | 0.20 - 0.70 | Optimal at 0.45 |
| 3 | Iris-Pupil Contrast | 0.1667 | Yes | ≥ 0.40 (rear), ≥ 0.10 (front) | Weber contrast between pupil and iris zones. The threshold is supplied by the caller: `CameraFragment` passes `if (captureMode == MODE_FRONT) 0.10f else 0.4f`; `CONTRAST_THRESHOLD = 0.4f` in `IrisQualityAssessor` is only the parameter default |
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

#### Metric 1: Usable Iris Area (rewritten 2026-09-19)

Returns the fraction of the iris annulus that is usable iris texture, that is, **not** occluded by
eyelid, eyelash or specular glare. Two paths exist:

- **With eyelid landmarks** (`computeUsableIrisAreaWithLandmarks()`): polar grid sampling inside
  the annulus, each sample tested against the upper and lower eyelid polylines. This path is dead
  in the current build, because `EyeImageCropper` always passes null landmark lists (limitation 4),
  so it is never taken.
- **Without landmarks** (`computeUsableIrisAreaIntensity()`, the live path): an adaptive,
  distribution-relative occlusion measure, described below.

**Why it was rewritten.** The previous implementation counted annulus samples whose intensity fell
below a fixed absolute threshold (180 on rear cameras, 220 on the front camera) and returned that
fraction. That measures pixel *darkness*, not occlusion, and it failed in both directions:

- On a dark iris every sample fell below the threshold, so the metric returned exactly 1.0 however
  much eyelid was covering the iris. This was confirmed saturating on a real device, logging
  `darkCount=180 totalCount=180 ratio=1.0` and a normalized score of 100.0. Since this is a
  **critical** gate, a value pinned at 1.0 carried no information at all and could never reject
  anything.
- A light blue or grey iris, correctly exposed, sits *above* those thresholds. The same metric
  would have failed such a participant on a critical gate for being correctly exposed.

**How the current version works.** The sampling geometry is unchanged, so sample counts stay
comparable with previously logged values: 36 angles by 5 radii across the annulus, from
`0.4 * irisRadius` out to `irisRadius`. What changed is the decision rule, which is now relative to
the iris itself rather than to an absolute grey level:

1. Collect the in-bounds annulus samples as 8-bit luma. Fewer than 20 usable samples returns a
   neutral 0.5.
2. Derive a robust centre and spread **from those samples**: the `median`, then the median absolute
   deviation `mad`, converted to a standard-deviation equivalent by the usual factor 1.4826 and
   floored so that a nearly flat crop does not flag everything:
   `sigma = max(mad * 1.4826, USABLE_IRIS_MIN_SIGMA)`, and `band = USABLE_IRIS_DEVIATION_K * sigma`.
3. Count a sample as occluded when it departs from that distribution:
   - `gray >= USABLE_IRIS_SPECULAR_CUTOFF` (250): near-saturated, glare hiding the texture;
   - `gray > median + band`: much brighter than the iris, so eyelid skin or sclera;
   - `gray < median - band`: much darker than the iris, so eyelash or deep shadow.
4. Return `1 - occludedFraction`, clamped to 0..1. The three occlusion counts are logged separately
   (`bright`, `dark`, `specular`) alongside the median, mad, sigma and band.

Because the reference is the iris's own distribution, the metric behaves the same way for any iris
colour and under any overall exposure, which is exactly what the absolute-threshold version could
not do. `IRIS_INTENSITY_THRESHOLD_REAR` and `IRIS_INTENSITY_THRESHOLD_FRONT` were deleted with the
old implementation. The `isFrontCamera` flag is still threaded into the function, but it now only
appears in the log line; the only remaining front/rear difference is the pass threshold, discussed
below.

**Known limitation, stated in the source.** If more than half of the annulus is occluded, the
median describes the **occluder** rather than the iris, and the metric **over-reports usability**:
the occluder becomes the reference and the still-visible iris starts to look like the outlier.
Occlusion that heavy is expected to be caught instead by the eye-presence detector (section 15) and
by the contrast metric, but this metric on its own cannot be trusted in that regime.

**The new constants are reasoned starting points and are NOT empirically tuned.**
`USABLE_IRIS_SPECULAR_CUTOFF = 250`, `USABLE_IRIS_DEVIATION_K = 2.5f` and
`USABLE_IRIS_MIN_SIGMA = 4.0f` were chosen by argument, not measured against a capture set. They
need validating on real captures before this gate is trusted, exactly as `MOTION_BLUR_THRESHOLD`
does (limitation 11).

**For the researcher: the front/rear pass threshold split needs re-examining.**
`USABLE_IRIS_THRESHOLD = 0.50` and `USABLE_IRIS_THRESHOLD_FRONT = 0.35` are unchanged in value but
have changed in meaning. They now mean *"fraction of the annulus that is unoccluded"*; before the
rewrite they meant *"fraction of samples that are dark"*. Those are different quantities, and the
bars were never re-derived for the new one. A lower bar for the front camera was defensible for a
darkness count, because the front camera has no flash and its images are brighter overall, so fewer
samples fall below an absolute dark threshold. It is questionable for an occlusion measure: how
much eyelid, eyelash or glare covers the iris is a property of the participant and the framing, not
of which camera took the picture. **Recommendation:** re-examine the split, most likely collapsing
it to a single bar, and re-derive that bar from annotated captures rather than carrying the old
pair forward.

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
- Sobel gradients; samples with magnitude below 5 are skipped.
- **Degenerate inputs return the threshold, not the floor (changed 2026-09-19).** An ROI smaller
  than 5 by 5 pixels, and an ROI in which no gradient clears the magnitude floor, both return
  `MOTION_BLUR_THRESHOLD`, which maps to a neutral score of 50. They previously returned
  `MOTION_BLUR_ISOTROPIC`, scoring a perfect 100 on a metric worth a sixth of the composite, so
  a blank or blown-out crop was rewarded for having nothing to measure. Rejecting such a crop is
  the sharpness metric's job, not this one's.
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

**File:** `IrisMetadata.kt` (199 lines)

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

**File:** `OverlayView.kt` (391 lines)

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

**Corrected 2026-09-19.** The table that stood here described a February 2026 timing
optimization (adaptive alignment delay, skipped focus settle, a 2000 ms focus timeout with 2
retries, a 500 ms inter-attempt delay) that **is not present in the current source**. Those
reductions appear to have been lost with the working tree rather than reverted on purpose. The
figures below are what the code does now: fixed delays read from the source, and measured stage
costs carried over from the earlier revision where the stage itself did not change.

| Phase | Duration | Source |
|-------|----------|--------|
| Alignment delay | 4000ms, on **every** attempt | literal `delay(4000)`, not adaptive |
| Focus settling | 500ms with manual focus, 1500ms otherwise | literal `delay(if (useManualFocus) 500 else 1500)` |
| Focus lock | up to 3000ms, 3 retries | `FOCUS_LOCK_TIMEOUT_MS`, `FOCUS_RETRY_COUNT` |
| Flash stabilization | 150ms, plus 50ms post-flash | `FLASH_STABILIZATION_DELAY_MS`, `POST_FLASH_CAPTURE_DELAY_MS` |
| Capture | ~200ms | Single shot, measured |
| Eye crop | ~50-100ms | BitmapRegionDecoder (ROI only, not full 12MP), measured |
| Eye presence check | ~5-10ms | Shared grayscale, efficient sampling, measured |
| Sharpness | ~30-50ms | Single Laplacian pass over the centre 50% of the already-cropped eye image, measured |
| Quality assessment | ~20-40ms | Polar grid sampling, measured |
| JPEG save | ~50ms | MediaStore ContentResolver, measured |
| RAW save | ~1-3s | Parallel with JPEG via coroutine async, measured |
| Inter-attempt delay | 2000ms | literal `delay(2000)` |

**Fixed delays alone come to roughly 7.7 seconds per attempt** (4000 alignment + 1500 focus settle
+ 150 + 50 flash + 2000 inter-attempt), before any of the measured stage costs or the focus lock
wait. A 30-attempt eye is therefore a multi-minute exercise. The earlier "~1.5s per attempt"
estimate described the optimized scheme and no longer applies.

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

### Device validation record: 2026-09-19, Pixel 10 Pro, front camera mode

First end-to-end run on real hardware after the 2026-09-19 fixes. **Device:** Pixel 10 Pro.
**Mode:** front camera. Verified from the device log and from the files pulled off the device:

| Check | Observed | Expected |
|-------|----------|----------|
| Derived iris radius | `radius=0.120000005` from a live preview zoom of 1.25 | `MIN_IRIS_SIZE_FRACTION / previewZoom = 0.15 / 1.25 = 0.12`. Matched |
| Crop axis | `irisR=293.76` from `rawDims=3440x2448` with `effectiveRotation=270` | `0.12 * 2448`, the **short** axis. Matched. Before the axis fix the same frame gave 412.8, because the radius was normalised against the 3440 long axis |
| Full pipeline | Captured, cropped, scored and saved both JPEG and DNG | Completed without error |
| Output image | A pulled sample was a correctly framed, in-focus **705x705** eye crop | A usable crop centred on the iris |

**Motion blur: the first empirical data point.** Genuine in-focus captures scored **92.0 to 93.9**
on the motion-blur sub-score. Inverting the score mapping in section 17
(`score = (1 - (d - 1) / (2 * (T - 1))) * 100`, with `T = MOTION_BLUR_THRESHOLD = 6.0`) puts the
measured directionality `d` at roughly **1.6 to 1.8**, well below the provisional threshold. This
is the first real evidence that the corrected metric moves off its 1.0 isotropic floor and that
good captures sit near the bottom of its range, which is what the correction was meant to achieve.
**It does not calibrate the threshold.** It fixes only a lower anchor. An upper bound still has to
be established from deliberately blurred frames (for example by panning the device during the
exposure), so that the separation between acceptable and rejected directionality is measured rather
than guessed. Until that is done, `MOTION_BLUR_THRESHOLD = 6.0` remains provisional (limitation 11).

**Scope.** One device, one mode, one session. Nothing here validates the rear telephoto or main
camera paths, the rewritten usable-iris metric, or any threshold other than the lower anchor noted
above.

**This run predates the double-zoom fix.** It was taken before the commit that stopped `setZoom()`
applying the zoom twice (`CONTROL_ZOOM_RATIO` and then a second narrowing of
`SCALER_CROP_REGION`), which meant the still was magnified by `z * z` while the radius derivation
read back `z`. The two arithmetic checks above are unaffected, because they are arithmetic on
values taken straight from the log (`0.15 / 1.25 = 0.12`, and `0.12 * 2448 = 293.76`). The
**framing** observation is not: the 705x705 crop was produced under the doubled magnification, so
the on-device framing and the comfortable standoff distance need re-confirming in each mode now
that zoom is expressed once.

---

## 24. Configuration Constants Reference

### Capture Settings

| Constant | Value | Description |
|----------|-------|-------------|
| `TARGET_ISO_MIN` | 100 | **Log-only.** Intended minimum ISO. `SENSOR_SENSITIVITY` is never set, so this is computed solely to appear in a `SENSOR_CONTROL` log line |
| `TARGET_ISO_MAX` | 400 | **Log-only**, as above |
| `MIN_SHUTTER_SPEED_NS` | 4,000,000 (1/250s) | **Log-only.** `SENSOR_EXPOSURE_TIME` is never set |
| `MAX_SHUTTER_SPEED_NS` | 8,000,000 (1/125s) | **Log-only**, as above |
| `FOCUS_LOCK_TIMEOUT_MS` | 3000 | Max time for focus lock (this table previously said 2000) |
| `FOCUS_RETRY_COUNT` | 3 | AF retries before proceeding (this table previously said 2) |
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
| `USE_OIS_FOR_TELEPHOTO` | true | Gates `LENS_OPTICAL_STABILIZATION_MODE_ON` on the **preview** request only. The still request carries no OIS key |
| `DISABLE_ISP_FOR_TELEPHOTO` | true | **Orphaned constant: referenced nowhere.** The ISP bypass it used to gate lived on the deleted `takePicture` path, so it has never been applied to a saved image (sections 9 and 26) |

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
| `USABLE_IRIS_THRESHOLD` | 0.50 | Rear camera minimum **unoccluded** annulus fraction. Unchanged in value, changed in meaning by the 2026-09-19 rewrite (section 17) |
| `USABLE_IRIS_THRESHOLD_FRONT` | 0.35 | Front camera equivalent. **The front/rear split predates the rewrite and should be re-examined:** occlusion is not a property of which camera took the picture |
| `USABLE_IRIS_SPECULAR_CUTOFF` | 250 | At or above this luma a sample counts as glare. **Reasoned, not empirically tuned** |
| `USABLE_IRIS_DEVIATION_K` | 2.5 | Robust sigmas away from the annulus median before a sample counts as occluded. **Reasoned, not empirically tuned** |
| `USABLE_IRIS_MIN_SIGMA` | 4.0 | Floor on the robust sigma, so a nearly flat crop does not flag every sample. **Reasoned, not empirically tuned** |
| `PUPIL_RATIO_MIN` / `MAX` | 0.20 / 0.70 | Valid pupil-to-iris ratio range |
| `CONTRAST_THRESHOLD` | 0.40 | Minimum iris-pupil contrast |
| `UNIFORMITY_THRESHOLD` | 0.50 | Minimum illumination uniformity |
| `MOTION_BLUR_THRESHOLD` | 6.0 | Maximum gradient anisotropy. **Provisional, needs empirical re-tuning** (was 4.0, a value that carried no information because the old formula was degenerate) |
| `MOTION_BLUR_NUM_BINS` | 36 | Orientation bins over `[0, pi)`, 5 degrees each; also the metric's upper bound |
| `MOTION_BLUR_ISOTROPIC` | 1.0 | The metric's lower bound, a perfectly isotropic histogram |
| `COMPOSITE_PASS_THRESHOLD` | 40 | Minimum weighted composite score |

`GAZE_DISPLACEMENT_THRESHOLD` (0.25) was deleted on 2026-09-19 with the Gaze Angle metric.
`IRIS_INTENSITY_THRESHOLD_REAR` (180) and `IRIS_INTENSITY_THRESHOLD_FRONT` (220) were deleted on
the same date with the darkness-counting usable-iris metric they belonged to (section 17).

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

4. **No eyelid landmark detection:** Since MediaPipe was removed, the quality assessor uses the intensity-based fallback for usable iris area estimation instead of precise eyelid polylines. The landmark path in `computeUsableIrisArea()` is retained but is never taken, because `EyeImageCropper` always passes null eyelid point lists. That fallback was rewritten on 2026-09-19 to measure occlusion relative to the iris's own intensity distribution instead of counting dark pixels (see section 17 and limitation 13). The Gaze Angle metric depended entirely on those landmarks and was removed on 2026-09-19 rather than left returning a constant (see section 17).

5. **Front camera limitations:** No flash available, lower sharpness thresholds, wider contrast tolerance. Front camera images are inherently lower quality than rear cameras for iris capture.

6. **Burst and focus-bracket paths are gone:** The only capture path is single-capture (`takeSingleCapture` → `processAndCropCenterBased`). The burst path (`takeBurstWithBestFrame`), the focus-bracketing path (`takeFocusBracketBurst`) and their `FOCUS_BRACKET_*` constants were deleted on 2026-09-19 after their unreachability was established: the `B` button only toggles its own colour, `setupModeUI()` sets it to `View.GONE` in every mode, and the burst-active flag could never become true. The burst path was also already broken, since the iris coordinates it fed to the sharpness analyzer were never assigned anywhere. `isBurstMode` survives as an unread capture-state flag and the hidden `B` button still toggles it.

7. **RAW not available on all lenses:** Some physical camera IDs (especially telephoto on certain devices) may not support RAW_SENSOR format. The app gracefully falls back to JPEG-only.

8. **There is no session timeout:** Earlier revisions of this document described a `totalSessionTimeoutMs` bounding the whole session. **No such constant or elapsed-time check exists in `CameraFragment`** (the only timeout in the file is `FOCUS_LOCK_TIMEOUT_MS`, which bounds one autofocus wait). A session is bounded only by the per-eye attempt cap (`MAX_CAPTURE_ATTEMPTS_PER_EYE`, 30, applied separately to each eye, so up to 60 attempts in total) and by the operator pressing Stop. A participant who cannot hold alignment will therefore keep the session running until the attempt caps are exhausted rather than being cut off on time. If a wall-clock bound is wanted for a collection protocol, it has to be added. See section 12.

9. **Package name: resolved 2026-09-19.** The inherited package `com.google.mediapipe.examples.facelandmarker` was renamed throughout to `edu.clarkson.iriscapture`, together with the application ID, the manifest, the navigation graph, the layouts and the tests. This limitation is closed and is kept here only so the change is discoverable from where it used to be described. Two upstream names still survive in observable behaviour and are deliberately unchanged: the `Pictures/FaceLandmarker` output album and the EXIF `Software` tag value.

10. **Portrait mode only:** All capture modes currently force portrait orientation. The landscape mode constants (`USE_LANDSCAPE_FOR_*`) are all set to `false`.

11. **`MOTION_BLUR_THRESHOLD` is provisional:** The corrected motion-blur metric now spans 1.0 to 36.0, but its threshold of 6.0 is a reasoned starting point rather than a measured one. The previous value could not be carried over, because the formula it was chosen against returned roughly 1.0 for every image, so no historical baseline exists. The 2026-09-19 device run (section 23) supplies the first empirical data point: genuine in-focus front-camera captures scored 92.0 to 93.9, which inverts to a directionality of roughly 1.6 to 1.8. That anchors the good-capture end of the scale, but it establishes no upper bound. Deliberately blurred frames are still needed before the threshold can be called anything but provisional.

12. **The derived iris radius is validated on the front camera only:** The normalized iris radius is now computed from the live preview zoom (section 9) instead of per-mode literals. On 2026-09-19 the front-camera path was confirmed on a Pixel 10 Pro (`radius=0.120000005` at a preview zoom of 1.25, and a 705x705 crop that framed the iris correctly; see the device validation record in section 23). The rear telephoto and main-camera values are still unconfirmed, and telephoto on a physical lens moved substantially (0.06 to 0.15), so that mode in particular should be checked on a device before its scores are used.

13. **The rewritten usable-iris metric is untuned and degrades above 50 percent occlusion:** The live usable-iris path (section 17) now judges occlusion relative to the iris's own median and robust sigma rather than against an absolute intensity, which makes it independent of iris colour and of overall exposure. Two caveats follow from that design. First, **if more than half of the annulus is occluded the median describes the occluder rather than the iris**, so the metric over-reports usability exactly when the image is worst; heavy occlusion has to be caught by the eye-presence detector and the contrast metric instead. Second, `USABLE_IRIS_SPECULAR_CUTOFF` (250), `USABLE_IRIS_DEVIATION_K` (2.5) and `USABLE_IRIS_MIN_SIGMA` (4.0) are reasoned starting points, not empirically tuned values. Separately, the pass thresholds `USABLE_IRIS_THRESHOLD` (0.50) and `USABLE_IRIS_THRESHOLD_FRONT` (0.35) now mean "fraction unoccluded" rather than "fraction dark", and the front/rear split they encode should be re-examined, because occlusion does not depend on which camera took the picture.

14. **OPEN ITEM, flagged for decision: no capture-side image tuning has ever been applied.** The telephoto ISP bypass (`EDGE_MODE_OFF`, `NOISE_REDUCTION_MODE_OFF`, `SHADING_MODE_OFF`, hot pixel), the capture-side OIS enable, the AE and flash copying, and the manual ISO and exposure-time settings all lived in `applyCommonCaptureSettings()` and `applyOptimalExposureSettings()`, which were reachable only from the `takePicture` chain. That chain was unreachable and was deleted on 2026-09-19. **Consequently every image this application has ever saved was produced with full ISP processing, including denoising and edge enhancement, with OIS set only on the preview, and with the camera's own auto-exposure rather than the intended ISO and shutter targets.** For a study of iris texture this is material: denoising and edge enhancement alter exactly the high-frequency detail such a study measures, and it applies retrospectively to data already collected, not only to future captures. `DISABLE_ISP_FOR_TELEPHOTO` is now an orphaned constant and the ISO and shutter constants are log-only. Whether to restore any of this to the live still path is a research decision that this document does not make; it is recorded here so the decision is made deliberately rather than by default. See sections 9, 11, 22 and 24.

15. **Timing optimizations described in earlier revisions are not in the code:** The February 2026 capture-timing work (adaptive alignment delay, skipped focus settle on subsequent attempts, a 2000 ms focus timeout with 2 retries, a 500 ms inter-attempt delay) is absent from the current source, which uses a flat 4000 ms alignment delay on every attempt, a 1500 ms focus settle, a 3000 ms timeout with 3 retries and a 2000 ms inter-attempt delay. It appears to have been lost with the working tree rather than deliberately reverted. Fixed delays alone therefore come to roughly 7.7 seconds per attempt. See sections 13 and 22.

---

## 27. Changelog

### 2026-09-19: quality-metric defects fixed, usable-iris metric rewritten, pipeline repairs

Every defect below was verified against the source before being fixed. **Composite quality scores
produced before this date are not comparable with those produced after it:** the Gaze Angle
metric's fixed +7.0 contribution is gone, every remaining weight scales by 1/0.9, the motion-blur
sub-score now varies across its full range instead of sitting in a compressed band, and the
usable-iris sub-score, which used to saturate at 100.0 on dark irides, now varies with actual
occlusion. Quality figures recorded in filenames (`Q{score}`) and in EXIF metadata carry no marker
of which scale they were produced on, so date the capture session to tell them apart.

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

5. **Usable iris area rewritten: it measured darkness, not occlusion.** The intensity fallback,
   which is the only path that ever runs, counted annulus samples below a fixed absolute intensity
   (180 rear, 220 front) and returned that fraction. On a dark iris every sample fell below the
   threshold, so the metric returned exactly 1.0 no matter how much eyelid covered the iris; it was
   confirmed saturating on a real device (`darkCount=180 totalCount=180 ratio=1.0`, score 100.0) on
   a **critical** gate, meaning that gate carried no information and could never reject anything.
   The same rule would have failed a light blue or grey iris for being correctly exposed. The
   metric now derives a robust centre (median) and spread (MAD scaled by 1.4826, floored at
   `USABLE_IRIS_MIN_SIGMA`) from the annulus samples themselves and counts a sample as occluded
   when it departs from the iris's own distribution: brighter than `median + k*sigma` (eyelid skin
   or sclera), darker than `median - k*sigma` (eyelash or shadow), or at/above
   `USABLE_IRIS_SPECULAR_CUTOFF` (glare). It returns `1 - occludedFraction`, so it now behaves the
   same way for any iris colour. New constants `USABLE_IRIS_SPECULAR_CUTOFF = 250`,
   `USABLE_IRIS_DEVIATION_K = 2.5f` and `USABLE_IRIS_MIN_SIGMA = 4.0f`, all reasoned rather than
   tuned; `IRIS_INTENSITY_THRESHOLD_REAR` and `IRIS_INTENSITY_THRESHOLD_FRONT` deleted. Known
   limitation: above 50 percent occlusion the median describes the occluder and the metric
   over-reports usability. The pass thresholds (0.50 rear, 0.35 front) now mean "fraction
   unoccluded" and their front/rear split should be re-examined. See section 17 and limitation 13.

6. **Iris radius was normalised against the wrong image axis.** `EyeImageCropper` chose the axis to
   scale the normalized radius by from `exifDegrees`, while the cropped bitmap is rotated by
   `effectiveRotation`. Those two always differ by 90 degrees, so the radius was normalised against
   an axis the crop is never rendered in. On the live path `JPEG_ORIENTATION` is never set, so
   `exifDegrees` was always 0 and the sensor **long** axis was used, inflating every radius by the
   frame aspect ratio. The axis is now selected from `effectiveRotation`. Verified on a device the
   same day: `irisR=293.76` from `rawDims=3440x2448` at `effectiveRotation=270`, which is
   `0.12 * 2448` (the short axis), where the pre-fix code gave 412.8 off the 3440 axis. See
   section 23.

7. **Login validation restored; the app could not get past its first screen.** `LoginFragment`
   required a **6**-digit participant ID while `fragment_login.xml` caps that field at
   `maxLength="3"`, so the ENTER button could never succeed and no session could start. The check
   is back to exactly 3 digits, matching the layout. The images-per-eye field, which the broken
   build ignored entirely, is validated again (integer, 1 to 20) and stored on `MainViewModel`.
   Note that storing it is all that happens: `CameraFragment` still does not read it, so the
   capture loop remains fixed at 5 accepted images per eye. See section 6.

8. **Eye-presence gate wired into the capture pipeline.** `EyePresenceDetector` existed and was
   documented (section 15) but was not actually invoked. `processAndCropCenterBased()` now calls
   `EyePresenceDetector.detect()` on the cropped eye image, between the crop and the sharpness
   measurement, and rejects the attempt when no eye is detected, so quality assessment no longer
   runs on frames containing no eye. The call is wrapped in a `try/catch` because `detect()`
   allocates two int arrays the size of the whole crop and an uncaught OOM there would leak the
   bitmap and abort the session. The rejection behaviour is a best-faith reconstruction of the
   pre-loss build and should be reviewed against intended behaviour. See section 15.

9. **Package renamed to `edu.clarkson.iriscapture`.** The inherited
   `com.google.mediapipe.examples.facelandmarker` was replaced throughout: sources, application ID,
   manifest, navigation graph, layouts and tests. Long-standing limitation 9 is closed by this.
   Two upstream names deliberately survive in observable behaviour and were **not** changed,
   because renaming them would change where images land and what existing files claim about
   themselves: the `Pictures/FaceLandmarker` output album and the EXIF `Software` tag value.

10. **Camera2 zoom was applied twice.** `setZoom()` set `CONTROL_ZOOM_RATIO` and then also narrowed
    `SCALER_CROP_REGION` to `activeArray / z`. Once the zoom ratio is non-unity the crop region is
    interpreted in post-zoom coordinates, so a conforming HAL read that as a second z-fold crop and
    effective magnification became `z * z` (4x asked for, 16x delivered). The ratio path now pins
    the crop region to the full active array, so zoom is expressed exactly once; the legacy
    sub-API-30 path, which correctly zooms by crop region alone, is unchanged. This also made the
    iris-radius derivation self-consistent, since it reads `CONTROL_ZOOM_RATIO` back and was
    getting `z` while the still was magnified by `z * z`, leaving every crop radius wrong by a
    further factor of `z`. **Expect the preview to look less magnified than before, and confirm the
    on-screen target is still fillable at a comfortable standoff in each mode.** Any radius, crop
    size or quality figure measured on a device before this fix needs re-measuring. See sections 9
    and 11.

11. **The left eye was captured at a different zoom from the right.** Between eyes the telephoto
    branch called `unlock3AAndZoomOutTo1x(keepZoom = false)`, which reset zoom to 1x with nothing
    re-applying it, so the right eye was captured at 3x and the left at 1x against an unchanged
    on-screen alignment target. The flag had been tied to `USE_LANDSCAPE_FOR_TELEPHOTO`, a screen
    orientation constant unrelated to zoom. It now passes `keepZoom = true`, matching the `MAIN_8X`
    and `FRONT` branch; the 3A reset that is the real purpose of the call is preserved, and the
    end-of-session reset to 1x still happens in `resetAfterCapture()`. **Any telephoto data
    collected before 2026-09-19 has mismatched left and right eyes and should be treated as
    such.** See section 12.

12. **Motion-blur degenerate cases no longer score a free 100.** A crop too small to measure
    (ROI under 5 by 5) or with no gradient above the magnitude floor returned the isotropic floor,
    which mapped to a perfect 100 on a metric worth a sixth of the composite, so a blank or
    blown-out crop was rewarded for having nothing to measure. Both now return
    `MOTION_BLUR_THRESHOLD`, a neutral 50. Rejecting such a crop is the sharpness metric's job.
    See section 17.

13. **Dead code removed, and what that revealed.** The whole `takePicture` chain was deleted after
    its unreachability was established, and with it `applyCommonCaptureSettings()`,
    `applyOptimalExposureSettings()` and `captureIrisNormX/Y/Radius`; also `captureIsRightEye`,
    `USE_SEQUENTIAL_CAPTURE`, a private `calculateSharpness` overload and `SharpnessAnalyzer`'s
    unused `ByteArray` overload. Two consequences are **pre-existing rather than introduced by the
    deletion**, because the deleted code never ran on the live path: `CaptureRequest.JPEG_ORIENTATION`
    is now set nowhere, so stills carry EXIF orientation 0 structurally (section 14); and the
    telephoto ISP bypass, capture-side OIS, AE/flash copying and manual ISO and exposure settings
    have never been applied to any saved image, leaving `DISABLE_ISP_FOR_TELEPHOTO` orphaned and
    the ISO and shutter constants log-only. **That last point is flagged for a research decision
    and is tracked as limitation 14.** See sections 9, 22 and 24.

14. **Documentation corrected against the source in four further places.** The eye-switch
    "7-second countdown" does not exist (the gap is a flat 1500 ms with no switch-eyes prompt,
    section 12); the February 2026 capture-timing optimizations are absent from the code, which
    still uses a 4000 ms alignment delay on every attempt, a 3000 ms focus timeout and 3 retries,
    and a 2000 ms inter-attempt delay (sections 13 and 22, limitation 15);
    `FOCUS_LOCK_TIMEOUT_MS` and `FOCUS_RETRY_COUNT` were listed as 2000 and 2 but are 3000 and 3
    (section 24); and "full manual control over focus, exposure, ISO and OIS" in section 1
    overstated what is applied.
