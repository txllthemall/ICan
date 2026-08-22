package com.ican.camera.processing.video

/**
 * Abstraction for video processing.
 */
interface VideoProcessingStrategy {
    /**
     * Configuration for the video pipeline.
     */
    fun configurePipeline()
}

/**
 * Default strategy that performs no processing (direct Recorder path).
 */
class DirectVideoStrategy : VideoProcessingStrategy {
    override fun configurePipeline() {
        // No-op for direct path.
    }
}
