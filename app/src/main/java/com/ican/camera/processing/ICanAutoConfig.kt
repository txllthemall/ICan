package com.ican.camera.processing

/**
 * Placeholder configuration for future ICan Auto computational photography/video.
 */
data class ICanAutoConfig(
    val useMultiFrame: Boolean = true,
    val hdrEnabled: Boolean = true,
    val denoiseEnabled: Boolean = true,
    val toneMappingEnabled: Boolean = true,
    val sharpeningLevel: Float = 1.0f,
    val naturalColorProcessing: Boolean = true
)
