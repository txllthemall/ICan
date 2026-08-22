package com.ican.camera.profiles

/**
 * Minimal placeholder for future camera emulation profiles.
 */
data class CameraProfile(
    val id: String,
    val manufacturer: String,
    val model: String,
    val releaseYear: Int,
    val profileType: ProfileType,
    
    // Reserve sections for future detailed characteristics
    val photoCharacteristics: PhotoProfile? = null,
    val videoCharacteristics: VideoProfile? = null,
    val sensorCharacteristics: SensorProfile? = null,
    val lensCharacteristics: LensProfile? = null,
    val colorCharacteristics: ColorProfile? = null,
    val compressionCharacteristics: CompressionProfile? = null
)

enum class ProfileType {
    SMARTPHONE,
    COMPACT_DIGITAL,
    CAMCORDER,
    FILM_EMULATION
}

// Minimal placeholder sub-models
data class PhotoProfile(val resolutionX: Int, val resolutionY: Int)
data class VideoProfile(val maxFps: Int, val supportedResolutions: List<String>)
data class SensorProfile(val sensorType: String)
data class LensProfile(val focalLength: Float)
data class ColorProfile(val toneCurve: String)
data class CompressionProfile(val jpegQuality: Int)
