package com.ican.camera.manual

enum class ExposureMode { AUTO, MANUAL }
enum class FocusMode { AUTO, MANUAL }

data class ManualSensorState(
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val focusMode: FocusMode = FocusMode.AUTO,
    val iso: Int = 100,
    val exposureTimeNs: Long = 33333333L, // 1/30s
    val focusDistanceDiopters: Float = 0.0f, // Infinity
    val aeLocked: Boolean = false,
    val awbLocked: Boolean = false,
    val exposureCompensationEv: Float = 0.0f
)

data class ObservedSensorState(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val aeMode: Int? = null,
    val aeState: Int? = null,
    val awbMode: Int? = null,
    val awbState: Int? = null,
    val afMode: Int? = null,
    val afState: Int? = null
)

data class ManualLimits(
    val isoRange: IntRange = 100..100,
    val exposureTimeRange: LongRange = 0L..0L,
    val minFocusDistance: Float = 0.0f,
    val evRange: IntRange = 0..0,
    val evStep: Float = 0.0f,
    val manualSensorSupported: Boolean = false,
    val aeLockSupported: Boolean = false,
    val awbLockSupported: Boolean = false
)
