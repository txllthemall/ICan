package com.ican.camera.engine

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.video.Quality
import com.ican.camera.capabilities.RearLens
import com.ican.camera.manual.ManualLimits
import com.ican.camera.manual.ManualSensorState
import com.ican.camera.manual.ObservedSensorState
import com.ican.camera.processing.ICanAutoConfig
import com.ican.camera.processing.ProcessingMode
import com.ican.camera.profiles.CameraProfile

enum class CameraMode {
    PHOTO, VIDEO
}

enum class RecordingState {
    IDLE, STARTING, RECORDING, STOPPING, FINALIZING, ERROR
}

data class CameraState(
    val mode: CameraMode = CameraMode.PHOTO,
    val selectedRearLens: RearLens = RearLens.MAIN,
    val manualSensorState: ManualSensorState = ManualSensorState(),
    val observedSensorState: ObservedSensorState = ObservedSensorState(),
    val manualLimits: ManualLimits = ManualLimits(),
    val isManualControlsVisible: Boolean = false,
    val isRawCaptureEnabled: Boolean = false,
    val isRawSupported: Boolean = false,
    val isRawJpegSupported: Boolean = false,
    val processingMode: ProcessingMode = ProcessingMode.NONE,
    val icanAutoConfig: ICanAutoConfig = ICanAutoConfig(),
    val activeProfile: CameraProfile? = null,
    val isReady: Boolean = false,
    val isCapturing: Boolean = false, // For Photo
    val recordingState: RecordingState = RecordingState.IDLE,
    val recordingDurationMillis: Long = 0,
    val lastThumbnailUri: Uri? = null,
    val lastThumbnailMimeType: String? = null,
    val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    val isTorchOn: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 1.0f,
    val hasFlash: Boolean = false,
    val hasTorch: Boolean = false,
    val supportedQualities: List<Quality> = emptyList(),
    val selectedQuality: Quality? = null,
    val error: String? = null
)
