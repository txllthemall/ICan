package com.ican.camera.capabilities

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import com.ican.camera.util.LogUtil
import java.io.File
import java.util.*

class ManualSensorCapabilityProbe(private val context: Context) {

    private val report = StringBuilder()
    private val cameraMapper = PhysicalCameraMapper(context)

    fun runProbe() {
        report.clear()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraMap = cameraMapper.getCameraMap()
        
        try {
            val cameraIds = cameraManager.cameraIdList
            line("=== ICAN MANUAL SENSOR CAPABILITY REPORT ===")
            line("Total cameras found: ${cameraIds.size}")

            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val semanticLens = cameraMap.entries.find { it.value.id == id }?.key
                reportCameraManualCapabilities(id, semanticLens, characteristics)
            }
            line("=== END REPORT ===")
            
            val finalReport = report.toString()
            LogUtil.i(finalReport)
            
            try {
                val file = File(context.cacheDir, "ican_manual_sensor_capabilities.txt")
                file.writeText(finalReport)
            } catch (e: Exception) {
                LogUtil.e("Failed to write manual sensor report to file", e)
            }
            
        } catch (e: Exception) {
            LogUtil.e("Failed to run manual sensor capability probe", e)
        }
    }

    private fun line(text: String) {
        report.append(text).append("\n")
    }

    private fun reportCameraManualCapabilities(id: String, semanticLens: RearLens?, chars: CameraCharacteristics) {
        line("--------------------------------------------------")
        line("Camera ID: $id")
        line("Semantic Lens: ${semanticLens ?: "FRONT/OTHER"}")

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        line("Manual Sensor: ${if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) "YES" else "NO"}")
        line("Manual Post Processing: ${if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) "YES" else "NO"}")

        reportSensitivity(chars)
        reportExposureTime(chars)
        reportExposureCompensation(chars)
        reportAE(chars)
        reportAWB(chars)
        reportFocus(chars)
        reportLensInfo(chars)
        reportRawDetails(chars)
    }

    private fun reportSensitivity(chars: CameraCharacteristics) {
        line("\nISO:")
        val range = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (range != null) {
            line("- Min: ${range.lower}")
            line("- Max: ${range.upper}")
        } else {
            line("- Range not available")
        }
    }

    private fun reportExposureTime(chars: CameraCharacteristics) {
        line("\nExposure:")
        val range = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (range != null) {
            line("- Min: ${range.lower} ns (${formatExposure(range.lower)})")
            line("- Max: ${range.upper} ns (${formatExposure(range.upper)})")
        }
        val maxFrameDuration = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
        line("- Max Frame Duration: $maxFrameDuration ns")
    }

    private fun formatExposure(ns: Long): String {
        if (ns <= 0) return "0"
        val seconds = ns / 1_000_000_000.0
        return if (seconds < 1.0) {
            "1/${(1.0 / seconds).toInt()} s"
        } else {
            String.format(Locale.US, "%.2f s", seconds)
        }
    }

    private fun reportExposureCompensation(chars: CameraCharacteristics) {
        line("\nExposure Compensation:")
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        if (range != null && step != null) {
            val minEv = range.lower * step.toDouble()
            val maxEv = range.upper * step.toDouble()
            line("- Range: ${range.lower}..${range.upper}")
            line("- Step: $step EV")
            line("- Effective Range: ${String.format(Locale.US, "%.2f", minEv)} EV .. ${String.format(Locale.US, "%.2f", maxEv)} EV")
        }
    }

    private fun reportAE(chars: CameraCharacteristics) {
        line("\nAE:")
        val modes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        line("- Modes: ${modes?.joinToString { mode ->
            when (mode) {
                CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF"
                CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
                CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
                CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
                CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "ON_AUTO_FLASH_REDEYE"
                CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "ON_EXTERNAL_FLASH"
                else -> "UNKNOWN($mode)"
            }
        }}")
        val lock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)
        line("- Lock Supported: ${if (lock == true) "YES" else "NO"}")
    }

    private fun reportAWB(chars: CameraCharacteristics) {
        line("\nAWB:")
        val modes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        line("- Modes: ${modes?.joinToString { mode ->
            when (mode) {
                CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
                CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
                CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY"
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
                CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
                CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
                CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
                CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
                CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
                else -> "UNKNOWN($mode)"
            }
        }}")
        val lock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)
        line("- Lock Supported: ${if (lock == true) "YES" else "NO"}")
    }

    private fun reportFocus(chars: CameraCharacteristics) {
        line("\nFocus:")
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        line("- AF Modes: ${modes?.joinToString { mode ->
            when (mode) {
                CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF/MANUAL"
                CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
                CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONT_VIDEO"
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONT_PICTURE"
                CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
                else -> "UNKNOWN($mode)"
            }
        }}")
        
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val hyperfocal = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
        line("- Minimum Focus Distance: ${minFocus ?: "FIXED"} diopters")
        if (minFocus != null && minFocus > 0) {
            line("- Approx Closest Focus: ${String.format(Locale.US, "%.3f", 1.0 / minFocus)} m")
        }
        line("- Hyperfocal: $hyperfocal")
    }

    private fun reportLensInfo(chars: CameraCharacteristics) {
        line("\nLens:")
        line("- Apertures: ${chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString()}")
        line("- Focal Lengths: ${chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()}")
    }

    private fun reportRawDetails(chars: CameraCharacteristics) {
        line("\nRAW Sensor:")
        line("- White Level: ${chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)}")
        line("- Black Level Pattern: ${chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)}")
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        line("- CFA: ${when(cfa) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "MONO_NIR"
            else -> "UNKNOWN($cfa)"
        }}")
        val tsSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        line("- Timestamp Source: ${when(tsSource) {
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            else -> "UNKNOWN($tsSource)"
        }}")
        line("- Sensor Orientation: ${chars.get(CameraCharacteristics.SENSOR_ORIENTATION)}")
    }
}
