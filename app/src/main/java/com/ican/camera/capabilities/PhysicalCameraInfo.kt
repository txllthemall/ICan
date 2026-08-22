package com.ican.camera.capabilities

import android.graphics.Rect
import android.util.SizeF

enum class CameraClassification {
    FRONT,
    MAIN,
    ULTRAWIDE,
    TELEPHOTO,
    UNKNOWN
}

data class PhysicalCameraInfo(
    val id: String,
    val facing: Int,
    val focalLengths: List<Float>,
    val physicalSize: SizeF?,
    val activeArraySize: Rect?,
    val classification: CameraClassification,
    val parentLogicalId: String? = null
)
