# IrisCapture

IrisCapture is an Android application for research-grade iris image acquisition. It drives the
camera through the Camera2 API directly, with no machine learning runtime of any kind: eye
alignment is manual (the participant centers one eye inside an on-screen target), and every
captured frame passes through an automated quality gate so that only images that pass are written
to disk. The app supports three capture configurations (rear telephoto, rear main camera, and
front camera), collects the right eye and then the left eye in a single session, and saves a
cropped JPEG plus an optional full-sensor RAW (DNG) with capture metadata embedded in EXIF.

This repository is the public code artifact accompanying
<!-- TODO: paper title, venue and year. Replace this line with the full reference once available. -->

Implementation detail well beyond the scope of this file (pipeline stages, coordinate transforms,
per-mode camera parameters, constant tables, known limitations) lives in
[`IrisCapture_Documentation.md`](IrisCapture_Documentation.md).

---

## Offline by construction

**The application never transmits data off the device.** This is enforced by the code, not by
policy:

* `app/src/main/AndroidManifest.xml` declares exactly one permission,
  `android.permission.CAMERA`. There is no `INTERNET` permission, so the Android runtime denies
  the process any network socket.
* There is no networking code anywhere in the sources: no HTTP client, no socket usage, no
  `WebView`, no analytics, crash-reporting, telemetry or cloud SDK.
* The dependency set is AndroidX and Material only (core-ktx, appcompat, material,
  constraintlayout, fragment-ktx, navigation, window). No ML, vision or network library is used.

Both claims can be verified from a clean checkout:

```bash
grep -n "uses-permission" app/src/main/AndroidManifest.xml
grep -rnE "java\.net|okhttp|retrofit|HttpURLConnection|Socket\(|WebView|firebase|grpc" app/src/main/java
```

The first command prints a single line, for `android.permission.CAMERA`. The second prints
nothing; the only `http` strings in the tree are the Apache License URLs in file headers.

Note that *building* the project does require network access, because Gradle downloads its own
distribution and the AndroidX dependencies. That is a property of the build, not of the app.

---

## Hardware requirements

* **A physical Android device.** The pipeline depends on real optics, autofocus and flash, so an
  emulator is not useful.
* **Android 11 (API 30) or newer in practice.** The manifest declares `minSdk 24`, but the
  effective floor is higher and reviewers should treat API 30 as the real requirement:
  * zoom control uses `CONTROL_ZOOM_RATIO`, which is API 30+;
  * per-lens output targeting uses `OutputConfiguration.setPhysicalCameraId()`, which is API 28+;
  * the MediaStore save path only sets `RELATIVE_PATH` and `IS_PENDING` on API 29+, and no legacy
    storage permission is declared, so saving is not expected to work on API 24 to 28.
* **A rear telephoto capability** for the highest-quality mode. Two device families are handled
  automatically: devices exposing a physical telephoto lens (focal length above 12 mm), which is
  used at 1x, and devices that switch lenses internally in response to zoom, which are driven at
  3x on the main camera. Devices with neither fall back to the main camera and the app warns the
  user. The documentation reports testing on a Pixel 10 Pro (physical telephoto) and a Samsung
  Galaxy S21 Ultra (zoom-based lens switch).
* **`RAW_SENSOR` support on the selected lens** if DNG output is wanted. Some devices do not
  expose RAW on the telephoto camera; the app detects this and continues with JPEG only.
* **Free storage.** A full-sensor DNG is tens of megabytes, and a session writes one per accepted
  image per eye.

---

## Build and run

### Toolchain

| Component | Version |
|---|---|
| JDK (to run the build) | **17** |
| Gradle (via wrapper) | 8.14.2 |
| Android Gradle Plugin | 8.11.0 |
| Kotlin | 1.7.10 |
| `compileSdk` / `targetSdk` | 34 |
| Declared `minSdk` | 24 (see the effective requirement above) |
| Application ID | `edu.clarkson.iriscapture` |

JDK 17 is required by Android Gradle Plugin 8.x. The compiled bytecode still targets Java 8
(`sourceCompatibility` and `targetCompatibility` are 1.8), so the two should not be confused: the
build fails on JDK 11, and raising `jvmTarget` is not a substitute.

Point the build at an Android SDK installation that has `compileSdk 34` installed, either by
setting `ANDROID_HOME` or by creating a `local.properties` file in the repository root containing
`sdk.dir=/path/to/Android/Sdk`. `local.properties` is intentionally not tracked by git.

### Command line

macOS and Linux:

```bash
./gradlew assembleDebug
./gradlew installDebug        # with a device attached and USB debugging enabled
```

Windows:

```bat
gradlew.bat assembleDebug
gradlew.bat installDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` and can also be installed
with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

### Android Studio

Open the repository root as an existing project and let Gradle sync. Confirm that the project JDK
is 17 (Settings, Build Tools, Gradle, Gradle JDK), select a connected physical device, and run the
`app` configuration. There is no model file to download and no extra setup step.

---

## Using the app

1. **Participant screen.** Enter a 3-digit participant ID and the number of images to collect per
   eye (1 to 20, default 5). The images-per-eye value also scales the attempt cap and the session
   timeout, so raising it gives the session proportionally more time.
2. **Permission screen.** Grant camera access. The screen advances automatically if the permission
   has already been granted.
3. **Mode screen.** Choose one of three capture configurations:
   * **Telephoto**, the highest-quality mode, using a physical telephoto lens where present and
     zoom-based lens switching otherwise;
   * **Main Camera**, the rear main lens, with digital zoom for preview alignment;
   * **Front Camera**, for self-capture, with no flash available.

   Every mode captures both eyes in one session.
4. **Capture screen.** A target circle and crosshair mark where the iris must sit. Move the device
   closer until the iris fills the circle, then press **Capture** to start the automated loop. The
   loop repeats align, focus lock, flash (rear modes only), capture, crop, and quality assessment
   until the requested number of accepted images is reached, the attempt cap is hit, the session
   times out, or **Stop** is pressed. The right eye is collected first, then a short countdown
   announces the switch to the left eye. Accepted and rejected attempts are both reported on
   screen, rejected ones with a reason.

   Three toggles sit on the capture screen: `R` enables or disables RAW capture (enabled by
   default), `MF` switches to manual focus at the minimum focus distance, and `B` selects the
   legacy burst path, which is retained but not used by the main flow.

---

## Output and metadata

Images are written through `MediaStore` into the shared album:

```
Pictures/FaceLandmarker/
```

The album name is inherited from the upstream project and has deliberately not been changed yet.
It is documented here as it actually behaves; renaming it is a pending decision.

### Filenames

```
{participantId}_{cameraLabel}_{eyeCode}_{attemptNum}_{mode}_Q{qualityScore}.jpg
{participantId}_{cameraLabel}_{eyeCode}_{attemptNum}_{mode}_Q{qualityScore}_raw.dng
```

| Field | Values |
|---|---|
| `participantId` | the 3-digit pseudonymous ID entered on the first screen |
| `cameraLabel` | `Tele`, `Main8x`, `Front` (or `Back` as a fallback) |
| `eyeCode` | `R` for the right eye, `L` for the left eye |
| `attemptNum` | the capture attempt index within that eye |
| `mode` | `R` when RAW capture is enabled, `N` when it is not |
| `qualityScore` | the composite quality score of the accepted image |

> **Parsing warning.** The `{mode}` field uses `R` for "RAW enabled", which is visually identical
> to the `R` used for the right eye in `{eyeCode}`. They are different fields in different
> positions. In `001_Tele_R_3_R_Q72.jpg` the first `R` is the eye (right) and the second `R` is the
> capture mode (RAW enabled); the same image captured with RAW disabled would be
> `001_Tele_R_3_N_Q72.jpg`. Split on `_` by position rather than searching for `R`.

### Embedded metadata

Each JPEG carries a JSON document in the EXIF `UserComment` tag describing the iris geometry in
cropped-image coordinates, the source resolution, the quality figures, and the capture context
(mode, participant ID, and a timestamp in epoch milliseconds). `ImageDescription` and `Software`
are also set. Each DNG carries a comparable JSON document in its description field, additionally
recording the sensor-space crop region and iris coordinates so that the RAW frame can be related
back to the cropped JPEG. Full field listings are in
[`IrisCapture_Documentation.md`](IrisCapture_Documentation.md).

---

## Quality assessment

Before an image is kept it passes through a multi-stage gate: a heuristic eye-presence check that
rejects frames containing no eye, a sharpness measure combining Laplacian variance and Tenengrad
gradient energy, and a composite quality score built from several sub-measures, some of which must
also pass individually. An image is written only if every stage passes; otherwise it is discarded
together with its pending RAW frame and the loop retries. The composite score is recorded in the
filename and in the embedded metadata.

The implementation describes itself as "ISO/IEC 29794-6 inspired". **It is not a conformant
implementation of that standard, and no conformance is claimed here.** Several of the individual
measures are currently under review and revision, and their behavior should not be inferred from
their names. Anyone relying on these figures should read the current definitions in
[`IrisCapture_Documentation.md`](IrisCapture_Documentation.md) and the source directly, and should
treat the composite score as an acceptance gate internal to this tool rather than as a
standardized quality value.

---

## Ethics and data handling

<!-- TODO: IRB / ethics approval. Fill in the approving body and the protocol number, for example
     "Data collection was carried out under protocol <NUMBER> approved by <BODY>." -->

**Pseudonymization.** Participants are identified only by the 3-digit ID entered on the first
screen. The app does not collect or store names, email addresses, dates of birth, device
identifiers, or location. The participant ID and a capture timestamp are the only
participant-linked fields written into filenames and metadata. Any mapping from participant ID to
a real identity exists only outside this repository and outside the device, and is the
responsibility of the study team.

**Shared-storage caveat.** Captured images are written to shared media storage
(`Pictures/FaceLandmarker` via `MediaStore`), not to app-private storage. This makes them visible
to the device gallery and to any other app holding media access. On a device with cloud photo
backup enabled, the gallery app may therefore synchronize iris images off the device. The
application itself never transmits anything, but it also cannot prevent this. For data collection,
use a dedicated device with photo backup and account synchronization disabled, transfer the images
over a cable, and delete them from the device afterwards.

**Biometric data.** Iris images are biometric identifiers and generally cannot be reissued if they
leak. Store and share them accordingly.

<!-- TODO: acquisition device actually used for the paper's dataset (model, Android version,
     capture mode, and whether RAW was enabled). -->

---

## License and attribution

Licensed under the Apache License, Version 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

```
Copyright 2026 Clarkson University

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

This project is derived from the `face_landmarker` Android example in Google's
[`google-ai-edge/mediapipe-samples`](https://github.com/google-ai-edge/mediapipe-samples)
repository, which is licensed under the Apache License 2.0 and carries
`Copyright 2023 The TensorFlow Authors`. Files that retain upstream code retain that notice
alongside the notice above.

---

## Citation

<!-- TODO: BibTeX entry for the accompanying paper. -->

```bibtex
TODO: BibTeX entry for the accompanying paper
```

<!-- TODO: DOI for the paper and, if one is minted separately, a DOI for this software artifact. -->

Contact: <!-- TODO: corresponding-author contact email -->

---

## Acknowledgments

IrisCapture began as a fork of the MediaPipe Face Landmarker Android sample. The MediaPipe and
TensorFlow Lite dependencies, the model download step, and the landmark-detection pipeline have
all been removed. What remains from upstream is the Gradle scaffolding, the single-activity
Navigation Component structure, and parts of the camera fragment skeleton. Two upstream names
still survive in observable behavior and are documented above rather than quietly changed: the
`Pictures/FaceLandmarker` output album and the EXIF `Software` tag value.
