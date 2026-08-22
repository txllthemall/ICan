package com.ican.camera.processing

import android.content.Context
import com.ican.camera.processing.photo.DirectPhotoStrategy
import com.ican.camera.processing.photo.PhotoProcessingStrategy
import com.ican.camera.processing.photo.ProcessedPhotoStrategy
import com.ican.camera.processing.video.DirectVideoStrategy
import com.ican.camera.processing.video.VideoProcessingStrategy

/**
 * Coordinator for processing strategies based on current mode.
 */
class ProcessingPipeline(private val context: Context) {
    
    private var currentMode: ProcessingMode = ProcessingMode.NONE
    private var icanAutoConfig: ICanAutoConfig = ICanAutoConfig()

    fun setMode(mode: ProcessingMode) {
        currentMode = mode
    }

    fun setICanAutoConfig(config: ICanAutoConfig) {
        icanAutoConfig = config
    }

    fun getPhotoStrategy(): PhotoProcessingStrategy {
        return when (currentMode) {
            ProcessingMode.NONE -> DirectPhotoStrategy()
            ProcessingMode.ICAN_AUTO -> ProcessedPhotoStrategy(context, icanAutoConfig)
            ProcessingMode.CAMERA_PROFILE -> ProcessedPhotoStrategy(context, icanAutoConfig) // Placeholder
        }
    }

    fun getVideoStrategy(): VideoProcessingStrategy {
        return when (currentMode) {
            ProcessingMode.NONE -> DirectVideoStrategy()
            ProcessingMode.ICAN_AUTO -> DirectVideoStrategy() // Placeholder
            ProcessingMode.CAMERA_PROFILE -> DirectVideoStrategy() // Placeholder
        }
    }
}
