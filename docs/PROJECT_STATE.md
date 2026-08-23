# ICan Project State

## Product vision
ICan is intended to become a full stock-camera replacement for Android, combining:
- fast direct camera capture
- ICan Auto computational photography
- authentic camera/device emulation
- photo and video
- future profile-specific UI, motion, sound and haptics

## Primary development device
- OnePlus 15
- model identifier: CPH2745
- this is the primary validation target
- do not hardcode behavior for this device

## Completed stable checkpoints

### Phase 1
Production photo foundation.

Verified:
- CameraX preview
- photo capture
- MediaStore saving
- rapid capture
- focus
- zoom
- flash
- front/rear switching
- lifecycle stability

### Phase 2A
Production video foundation.

Verified:
- PHOTO / VIDEO modes
- VideoCapture / Recorder
- microphone permission handling
- recording timer
- torch
- MediaStore video output
- video playback
- photo functionality preserved

### Phase 2B
Processing architecture foundation.

Implemented:
- ProcessingMode
- NONE
- ICAN_AUTO
- CAMERA_PROFILE
- ProcessingPipeline
- PhotoProcessingStrategy
- VideoProcessingStrategy
- CameraProfile
- ICanAutoConfig

### Phase 2C
Processed photo pipeline.

Git checkpoint:
"Phase 2C: processed photo pipeline"

Verified physically on OnePlus 15:
- PHOTO / NONE captures successfully
- PHOTO / AUTO captures successfully
- PHOTO / CAMERA_PROFILE captures successfully
- processed output is full-color
- video still works
- processing selector is hidden in VIDEO mode

Important Phase 2C implementation:
- public CameraX APIs only
- restricted ImageCapture.Builder.setBufferFormat was removed
- processed still path uses ImageProxy/intermediate processing
- OpenGL ES 3.0 GPU foundation
- EGL + FBO processing
- YUV-to-RGB shader path
- JPEG encoding
- MediaStore output
- ImageProxy cleanup
- GL resource cleanup

## Current architecture

CameraEngine
→ ProcessingPipeline
→ Processing strategy
→ direct or processed output

NONE:
CameraX direct path

ICAN_AUTO:
currently processed test path, real computational photography not implemented yet

CAMERA_PROFILE:
currently processed test path, real camera profiles not implemented yet

## Known limitations

- ICan Auto is not yet real computational photography
- Camera profiles do not yet emulate real cameras
- processed video is not implemented
- current YUV/color assumptions still need deeper validation
- high-resolution memory usage and GPU readback need future optimization
- current developer processing selector is temporary
- UI is not final
- haptic engine is not implemented
- camera profile visual themes are not implemented

## Engineering rules

- preserve verified photo/video functionality
- no restricted AndroidX Camera APIs
- no whole-file rewrites of critical CameraEngine code without strong reason
- prefer small targeted changes
- physical-device validation matters more than build success
- do not hardcode OnePlus-specific capabilities
- use runtime capability detection
- create a Git checkpoint after every stable phase
- do not claim a feature works until verified on the physical device

## Next planned phase

Phase 2D:
Device Capability Probe.

Goal:
discover the actual Camera2 / CameraX capabilities exposed by the connected OnePlus 15 CPH2745 before implementing real ICan Auto or camera profiles.

### Phase 2D.1 — Device Capability Probe
**Status: COMPLETED**
- Implementation of `CameraCapabilityProbe` to inspect Camera2 characteristics.
- Structured Logcat reporting of identity, sensor, lens, control, and stream capabilities.
- Integrated into `MainActivity` startup.

### Phase 2D.2 — Physical Camera Mapping
**Status: COMPLETED**
- Implementation of `PhysicalCameraMapper` to classify logical and physical cameras.
- Semantic classification: FRONT, MAIN, ULTRAWIDE, TELEPHOTO.
- Deterministic focal-length based sorting for rear multi-camera systems.
- Report logged and saved to app cache.

### Phase 2D.3 — Physical Lens Selection
**Status: COMPLETED**
- Implementation of physical lens selection for rear photography.
- Support for UW, MAIN, and TELE lenses via CameraX `addCameraFilter`.
- Verified runtime results on OnePlus 15 CPH2745:
  - ULTRAWIDE
    - Target Physical ID: 3
    - Actually Bound ID: 3
    - Focal Length: 2.31 mm
    - Sensor Physical Size: 5.013504 x 3.760128
    - Bind Success: true
  - MAIN
    - Target Physical ID: 2
    - Actually Bound ID: 2
    - Focal Length: 5.59 mm
    - Sensor Physical Size: 8.192 x 6.144
    - Bind Success: true
  - TELEPHOTO
    - Target Physical ID: 4
    - Actually Bound ID: 4
    - Focal Length: 12.19 mm
    - Sensor Physical Size: 5.24288 x 3.93216
    - Bind Success: true
- Physical-device validation confirmed:
  - Field of view changes correctly between UW / MAIN / TELE.
  - Photo capture works after physical lens rebinding.
  - Binding is performed to the actual resolved physical Camera2 IDs, not digital crop/zoom.

### Phase 2D.4 — Stream / FPS / Stabilization / Dynamic Range Probe
**Status: COMPLETED**
- Implementation of `StreamCapabilityProbe` to discover real Camera2 capabilities.
- Inspection of AE FPS ranges, high-speed video support, and stabilization modes.
- Collection of dynamic range profiles (API 33+) and color space profiles (API 34+).
- Integrated into development startup sequence.
- Verified runtime results on OnePlus 15 CPH2745:
  - **MAIN / physical ID 2**:
    - normal 4K up to 30 fps
    - normal 1440p / 1080p up to 60 fps
    - constrained high-speed 4K up to 120 fps
    - constrained high-speed 1080p up to 240 fps
  - **ULTRAWIDE / physical ID 3**:
    - normal 4K up to 30 fps
    - normal 1440p / 1080p up to 60 fps
    - constrained high-speed 1080p up to 240 fps
  - **TELEPHOTO / physical ID 4**:
    - normal 4K up to 30 fps
    - normal 1440p / 1080p up to 60 fps
    - constrained high-speed 1080p up to 240 fps
  - **FRONT / ID 1**:
    - normal streams up to 60 fps
    - no constrained high-speed configurations reported
  - **All exposed cameras report**:
    - video stabilization OFF / ON / PREVIEW_STABILIZATION
    - optical stabilization exposed only as OFF
    - dynamic range: STANDARD, HLG10, HDR10, HDR10_PLUS, DOLBY_VISION_10B_HDR_OEM
    - STREAM_USE_CASE support
  - **Color-space probe confirmed**:
    - PRIVATE output supports SRGB and BT2020_HLG
    - YUV_420_888 reports SRGB
    - JPEG reports SRGB
- **Engineering Note**: Advertised capability does NOT automatically mean a complete recording configuration is operational. High-speed and HDR combinations must be validated with real capture sessions before being exposed to users.

### Phase 2D.5 — Video Configuration Capability & Session Validation
**Status: COMPLETED**
- Implementation of `VideoConfigurationValidator` to discover and verify high-speed and HDR configurations.
- Refined as a non-invasive diagnostic component.
- VERIFIED on OnePlus 15 CPH2745 (MAIN camera):
  - **Advertised by Camera2**: 1080p60, 4K30, 1080p120 HS, 1080p240 HS, 4K120 HS, HLG10.
  - **Session Creation Succeeded**: All six selected MAIN configurations successfully reached `onConfigured` state in Camera2.
  - **Feasibility established**: STANDARD, HIGH_SPEED, and HLG10 session configurations are functional on the hardware.
- NOT YET VERIFIED:
  - Production encoded recording for every experimental configuration.
  - Actual 1080p120 / 240 encoded output.
  - Actual 4K120 encoded output.
  - Actual HLG10 encoded output (bit-depth/profile correctness).
- **Engineering Note**: Encoded video validation was intentionally deferred to the future production video-settings / recording implementation rather than maintaining a separate temporary diagnostic recording stack. 4K120, 240 fps, and HDR modes are not yet marked production-ready.

### Phase 2E — RAW / Manual Sensor Foundation
**Status: IN PROGRESS**
- Planned goals:
  - public Camera2 RAW_SENSOR validation
  - DNG capture
  - ISO range and manual ISO control
  - exposure-time control
  - manual focus-distance control
  - AE/AWB lock capabilities
  - exposure compensation
  - per-physical-camera manual capability validation
  - foundation for future computational photography / ICan Auto

### Phase 2E.1 — RAW Capture Foundation
**Status: COMPLETED**
- Implementation of RAW+JPEG simultaneous capture foundation using public CameraX 1.6.1 APIs.
- Generic capability-driven RAW support (validated via `ImageCaptureCapabilities`).
- Isolated diagnostic RAW capture path (`RAW+JPEG TEST`).
- DNG output saved to `DCIM/ICan/RAW` via MediaStore.
- Deterministic diagnostic report `ican_raw_capture_validation.txt`.
- Physically verified results on OnePlus 15 CPH2745:
  - Normal PHOTO startup preview remains stable.
  - Normal JPEG capture remains functional.
  - NONE / AUTO / CAMERA_PROFILE remain functional.
  - Physical UW / MAIN / TELE switching remains functional.
  - FRONT / BACK switching remains functional.
  - Normal VIDEO preview and recording remain functional.
  - CameraX public RAW+JPEG simultaneous capture works on the semantic MAIN camera.
  - One shutter produces a real DNG RAW file plus companion JPEG.
  - Android Gallery recognizes and opens the DNG as RAW.
  - Companion JPEG is valid and displayable.
  - RAW capture no longer breaks subsequent PHOTO or VIDEO sessions.
  - Normal photo thumbnail behavior is restored.
  - RAW+JPEG thumbnail uses the companion JPEG rather than the DNG.
  - Experimental/unvalidated video-quality UI was removed.
  - Stale debug Quality object text was removed.
- **Architecture Note**: RAW support remains capability-driven and must not depend on hardcoded OnePlus physical camera IDs. The current RAW implementation is a foundation only.
- **Note**: Manual controls, RAW processing, and computational RAW pipelines are deferred to future subphases.

### Phase 2E.2 — Manual Sensor Controls
**Status: COMPLETED**
- Planned scope:
  - Manual ISO
  - Manual exposure time
  - Manual focus distance
  - AE lock
  - AWB lock
  - Exposure compensation
  - Capability-driven limits
  - Per-physical-camera validation
  - Foundation for controlled multi-frame capture

### Phase 2E.2A — Manual Sensor Capability Probe
**Status: COMPLETED**
- Implementation of `ManualSensorCapabilityProbe` to discover Camera2 manual sensor limits.
- Inspection of ISO range, exposure time range, and manual sensor capabilities.
- Collection of exposure compensation limits, AE/AWB lock support, and focus/lens metadata.
- Reporting of RAW sensor details (white level, black level, CFA) for future computational work.
- Integrated into development startup sequence.
- Verified on OnePlus 15 CPH2745 (camera ID 0 = LOGICAL REAR).

### Phase 2E.2B — Manual Sensor Control Core
**Status: COMPLETED**
- Implementation of `ManualSensorController` to apply Camera2 capture request options via CameraX interop.
- Support for AUTO/MANUAL exposure (ISO, Shutter Speed) and focus (Diopters).
- Support for AE/AWB lock and exposure compensation.
- Capability-driven limits and safety clamping.
- Isolated from VIDEO mode to prevent regressions.
- Minimal developer UI (PRO CORE) added for photo-mode validation.
- Physically verified behavior on OnePlus 15 CPH2745:
  - **MANUAL EXPOSURE**:
    - Runtime Camera2 manual ISO and exposure-time control works.
    - Manual exposure uses `CONTROL_AE_MODE = OFF`.
    - Requested values confirmed through real `CaptureResult` metadata (e.g., Requested ISO: 3200, Observed ISO: 3200).
    - Observed: `AE Mode: OFF`, `AE State: INACTIVE`.
  - **MANUAL FOCUS**:
    - Runtime manual focus-distance control works.
    - Manual focus uses `CONTROL_AF_MODE = OFF`.
    - Requested focus distance confirmed through `CaptureResult` (e.g., Requested: 10.0 D, Observed: 9.999999 D).
    - Observed: `AF Mode: OFF`, `AF State: INACTIVE`.
  - **AUTO RESTORATION**:
    - `MANUAL -> AUTO` exposure and `MANUAL_FOCUS -> AUTO_FOCUS` restoration works.
    - Automatic exposure resumes selecting its own ISO/exposure values.
    - PHOTO autofocus restores `CONTINUOUS_PICTURE`.
    - Tap-to-focus remains functional.
  - **AE/AWB LOCK + EV**:
    - Physically smoke-tested successfully.
    - EV compensation works in AUTO exposure.
    - AE lock and AWB lock work as expected.
- **Architecture Note**: Manual controls use a centralized `ManualSensorController`. Limits remain capability-driven per active physical camera. One coherent `CaptureRequestOptions` bundle represents current manual state. Actual `CaptureResult` telemetry is available for validation.
- **Note**: Final Pro UI, manual Kelvin/tint, and computational features are deferred to later phases.

### Phase 2E.3 — Controlled Multi-Frame Capture Foundation
**Status: IN PROGRESS**
- Foundation for controlled multi-frame capture.

### Phase 2E.3A — Controlled Exposure Bracketing
**Status: COMPLETED**
- Implementation of `ExposureBracketController` to capture deterministic 3-frame sets (-2, 0, +2 EV).
- Use of existing `ManualSensorController` to freeze baseline exposure/focus/AWB.
- Deterministic sequencing: Apply -> Confirm (via `CaptureResult`) -> Capture.
- Baseline derived from stable AUTO exposure metadata.
- Brackets saved to `DCIM/ICan/Brackets/<setId>/`.
- Deterministic diagnostic report `ican_bracket_validation.txt`.
- Minimal developer UI for triggering and monitoring bracket status.
- Physically verified results on OnePlus 15 CPH2745:
  - **Bracket Set**: 20260823_080414
  - **Baseline**: Observed ISO: 3758, Observed Exposure: 11217138 ns, Focus: 2.224308 D
  - **-2 EV frame**: Requested ISO: 3758, Requested Exposure: 2804284 ns | Observed ISO: 3758, Observed Exposure: 2804281 ns | Actual EV Offset: -2.00 | Capture: SUCCESS
  - **0 EV frame**: Requested ISO: 3758, Requested Exposure: 11217138 ns | Observed ISO: 3758, Observed Exposure: 11217127 ns | Actual EV Offset: 0.00 | Capture: SUCCESS
  - **+2 EV frame**: Requested ISO: 3758, Requested Exposure: 44868552 ns | Observed ISO: 3758, Observed Exposure: 44868508 ns | Actual EV Offset: +2.00 | Capture: SUCCESS
- **Engineering Conclusions**:
  - All three frames were captured from one continuous CameraX session.
  - The same `ImageCapture` instance was reused.
  - ISO remained constant across the bracket.
  - Exposure time produced the intended -2 / 0 / +2 EV offsets.
  - `CaptureResult` confirmed actual hardware exposure before each shutter.
  - No arbitrary sleeps were required and no CameraX rebind occurred between frames.
  - Deterministic three-frame exposure bracketing is now physically verified.

### Phase 2E.3B — Multi-Frame Alignment Input Foundation
**Status: PLANNED**
- Planned purpose: Prepare bracket frames for motion/alignment analysis before HDR fusion.
