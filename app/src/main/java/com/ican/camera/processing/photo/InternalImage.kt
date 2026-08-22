package com.ican.camera.processing.photo

import android.graphics.Rect
import androidx.camera.core.ImageInfo

/**
 * Stable internal representation for processed still images.
 */
data class InternalImage(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val format: Int,
    val timestamp: Long,
    val cropRect: Rect,
    val cameraMetadata: ImageInfo? = null
)
