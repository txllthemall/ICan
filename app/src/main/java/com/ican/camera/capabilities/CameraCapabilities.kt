package com.ican.camera.capabilities

import android.hardware.camera2.CameraCharacteristics

/**
 * Data class to hold captured camera capabilities.
 * Currently just a marker for future usage if we need to pass these around.
 */
data class CameraCapabilities(
    val id: String,
    val facing: Int,
    val characteristics: CameraCharacteristics
)
