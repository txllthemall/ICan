package com.ican.camera.engine

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.video.Quality

enum class CameraMode {
    PHOTO, VIDEO
}

enum class RecordingState {
    IDLE, STARTING, RECORDING, STOPPING, FINALIZING, ERROR
}

data class CameraState(
    val mode: CameraMode = CameraMode.PHOTO,
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
