package com.ican.camera.engine

import androidx.camera.core.CameraInfo
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector

class CameraCapabilities(cameraInfo: CameraInfo) {
    val hasFlash: Boolean = cameraInfo.hasFlashUnit()
    val zoomRange: ClosedRange<Float> = (cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f)..(cameraInfo.zoomState.value?.maxZoomRatio ?: 1.0f)
    
    @Suppress("DEPRECATION")
    val supportedQualities: List<Quality> = QualitySelector.getSupportedQualities(cameraInfo)
    
    // Future expansion: exposure ranges, specific resolutions, stabilization, etc.
}
