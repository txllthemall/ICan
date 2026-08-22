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
**Status: IN PROGRESS**
- Implementation of `PhysicalCameraMapper` to classify logical and physical cameras.
- Semantic classification: FRONT, MAIN, ULTRAWIDE, TELEPHOTO.
- Deterministic focal-length based sorting for rear multi-camera systems.
- Report logged and saved to app cache.
