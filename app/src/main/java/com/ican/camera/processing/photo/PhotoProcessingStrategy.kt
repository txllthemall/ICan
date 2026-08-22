package com.ican.camera.processing.photo

import android.net.Uri
import androidx.camera.core.ImageProxy

/**
 * Abstraction for photo processing.
 */
interface PhotoProcessingStrategy {
    /**
     * Executes the processing logic on the provided ImageProxy.
     */
    suspend fun processPhoto(imageProxy: ImageProxy): ProcessingResult
}

data class ProcessingResult(
    val success: Boolean,
    val outputUri: Uri? = null,
    val error: Throwable? = null
)

/**
 * Default strategy that performs no processing.
 * NOTE: This is a placeholder as NONE mode uses direct CameraX path.
 */
class DirectPhotoStrategy : PhotoProcessingStrategy {
    override suspend fun processPhoto(imageProxy: ImageProxy): ProcessingResult {
        imageProxy.close()
        return ProcessingResult(success = true)
    }
}
