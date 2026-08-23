package com.ican.camera.manual

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import com.ican.camera.capabilities.RearLens
import com.ican.camera.util.LogUtil
import java.io.File
import java.util.Locale

class ManualSensorController(private val context: Context) {

    private var activeLimits = ManualLimits()
    private var currentState = ManualSensorState()
    private var lastAppliedSemanticLens: RearLens? = null
    private var lastId: String? = null
    private var lastObserved: ObservedSensorState? = null

    fun updateLimits(id: String): ManualLimits {
        lastId = id
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(id)

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val awbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true

        activeLimits = ManualLimits(
            isoRange = (isoRange?.lower ?: 100)..(isoRange?.upper ?: 100),
            exposureTimeRange = (expRange?.lower ?: 0L)..(expRange?.upper ?: 0L),
            minFocusDistance = minFocus,
            evRange = (evRange?.lower ?: 0)..(evRange?.upper ?: 0),
            evStep = evStep,
            manualSensorSupported = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            aeLockSupported = aeLock,
            awbLockSupported = awbLock
        )
        
        // Clamp current state to new limits
        currentState = currentState.copy(
            iso = currentState.iso.coerceIn(activeLimits.isoRange),
            exposureTimeNs = currentState.exposureTimeNs.coerceIn(activeLimits.exposureTimeRange),
            focusDistanceDiopters = currentState.focusDistanceDiopters.coerceIn(0f, activeLimits.minFocusDistance)
        )
        
        return activeLimits
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyState(cameraControl: CameraControl, state: ManualSensorState, semanticLens: RearLens) {
        currentState = state
        lastAppliedSemanticLens = semanticLens
        val c2Control = Camera2CameraControl.from(cameraControl)
        val optionsBuilder = CaptureRequestOptions.Builder()

        // 1. Exposure
        if (state.exposureMode == ExposureMode.MANUAL && activeLimits.manualSensorSupported) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, state.iso)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, state.exposureTimeNs)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, state.exposureTimeNs)
        } else {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            
            if (activeLimits.evRange.first != 0 || activeLimits.evRange.last != 0) {
                val steps = (state.exposureCompensationEv / activeLimits.evStep).toInt()
                    .coerceIn(activeLimits.evRange)
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
            }
        }

        // 2. Focus
        if (state.focusMode == FocusMode.MANUAL) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            optionsBuilder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, state.focusDistanceDiopters)
        } else {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // 3. Locks
        if (activeLimits.aeLockSupported) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, state.aeLocked)
        }
        if (activeLimits.awbLockSupported) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, state.awbLocked)
        }

        val options = optionsBuilder.build()
        c2Control.setCaptureRequestOptions(options)
        
        LogUtil.i("Manual sensor state applied for $semanticLens: $state")
    }

    private var lastReportTime = 0L
    private val REPORT_THROTTLE_MS = 500L

    fun onCaptureResult(result: ObservedSensorState) {
        lastObserved = result
        val now = System.currentTimeMillis()
        if (now - lastReportTime < REPORT_THROTTLE_MS) return
        
        lastReportTime = now
        writeValidationReport(result)
    }

    fun getLastId(): String? = lastId
    fun getLastObserved(): ObservedSensorState? = lastObserved
    fun getActiveLimits(): ManualLimits = activeLimits

    private fun writeValidationReport(observed: ObservedSensorState) {
        val report = StringBuilder()
        report.append("=== ICAN MANUAL SENSOR VALIDATION ===\n\n")
        report.append("Semantic Lens: ${lastAppliedSemanticLens ?: "UNKNOWN"}\n\n")
        
        report.append("Requested:\n")
        report.append("Exposure Mode: ${currentState.exposureMode}\n")
        if (currentState.exposureMode == ExposureMode.MANUAL) {
            report.append("ISO: ${currentState.iso}\n")
            report.append("Exposure Time: ${currentState.exposureTimeNs} ns\n")
        } else {
            report.append("ISO: (Stored) ${currentState.iso}\n")
            report.append("Exposure Time: (Stored) ${currentState.exposureTimeNs} ns\n")
            report.append("EV Compensation: ${currentState.exposureCompensationEv}\n")
        }
        
        report.append("Focus Mode: ${currentState.focusMode}\n")
        if (currentState.focusMode == FocusMode.MANUAL) {
            report.append("Focus Distance: ${currentState.focusDistanceDiopters} D\n")
        } else {
            report.append("Focus Distance: (Stored) ${currentState.focusDistanceDiopters} D\n")
        }
        
        report.append("AE Lock: ${currentState.aeLocked}\n")
        report.append("AWB Lock: ${currentState.awbLocked}\n")

        report.append("\nApplication:\n")
        report.append("CaptureRequestOptions Applied: YES\n")

        report.append("\nObserved Capture Result:\n")
        report.append("ISO: ${observed.iso}\n")
        report.append("Exposure Time: ${observed.exposureTimeNs} ns\n")
        report.append("Focus Distance: ${observed.focusDistanceDiopters} D\n")
        report.append("AE Mode: ${decodeAE(observed.aeMode)}\n")
        report.append("AE State: ${decodeAEState(observed.aeState)}\n")
        report.append("AWB Mode: ${decodeAWB(observed.awbMode)}\n")
        report.append("AWB State: ${decodeAWBState(observed.awbState)}\n")
        report.append("AF Mode: ${decodeAF(observed.afMode)}\n")
        report.append("AF State: ${decodeAFState(observed.afState)}\n")
        
        report.append("\n=== END ===\n")

        try {
            val file = File(context.cacheDir, "ican_manual_sensor_validation.txt")
            file.writeText(report.toString())
        } catch (e: Exception) {
            LogUtil.e("Failed to write manual validation report", e)
        }
    }

    private fun decodeAE(m: Int?) = when(m) {
        0 -> "OFF"
        1 -> "ON"
        2 -> "ON_AUTO_FLASH"
        3 -> "ON_ALWAYS_FLASH"
        else -> "UNKNOWN($m)"
    }
    
    private fun decodeAEState(s: Int?) = when(s) {
        0 -> "INACTIVE"
        1 -> "SEARCHING"
        2 -> "CONVERGED"
        3 -> "LOCKED"
        4 -> "FLASH_REQUIRED"
        5 -> "PRECAPTURE"
        else -> "UNKNOWN($s)"
    }

    private fun decodeAWB(m: Int?) = when(m) {
        0 -> "OFF"
        1 -> "AUTO"
        else -> "UNKNOWN($m)"
    }

    private fun decodeAWBState(s: Int?) = when(s) {
        0 -> "INACTIVE"
        1 -> "SEARCHING"
        2 -> "CONVERGED"
        3 -> "LOCKED"
        else -> "UNKNOWN($s)"
    }

    private fun decodeAF(m: Int?) = when(m) {
        0 -> "OFF"
        1 -> "AUTO"
        2 -> "MACRO"
        3 -> "CONTINUOUS_VIDEO"
        4 -> "CONTINUOUS_PICTURE"
        5 -> "EDOF"
        else -> "UNKNOWN($m)"
    }

    private fun decodeAFState(s: Int?) = when(s) {
        0 -> "INACTIVE"
        1 -> "PASSIVE_SCAN"
        2 -> "PASSIVE_FOCUSED"
        3 -> "ACTIVE_SCAN"
        4 -> "FOCUSED_LOCKED"
        5 -> "NOT_FOCUSED_LOCKED"
        6 -> "PASSIVE_UNFOCUSED"
        else -> "UNKNOWN($s)"
    }
}
