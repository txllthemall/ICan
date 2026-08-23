package com.ican.camera.manual

import android.net.Uri

data class BracketFrameMetadata(
    val intendedEvOffset: Float,
    val actualEvOffset: Float,
    val requestedIso: Int,
    val requestedExposureTimeNs: Long,
    val observedIso: Int? = null,
    val observedExposureTimeNs: Long? = null,
    val observedFocusDistanceDiopters: Float? = null,
    val captureStatus: String = "PENDING",
    val outputUri: Uri? = null
)

data class BracketSetMetadata(
    val setId: String,
    val baselineIso: Int,
    val baselineExposureTimeNs: Long,
    val baselineFocusDistanceDiopters: Float,
    val frames: Map<String, BracketFrameMetadata> = emptyMap(),
    val result: String = "IN_PROGRESS"
)
