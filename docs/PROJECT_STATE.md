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
