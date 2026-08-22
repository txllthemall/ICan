package com.ican.camera.capabilities

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import com.ican.camera.util.LogUtil
import java.io.File

class CameraCapabilityProbe(private val context: Context) {

    private val report = StringBuilder()

    fun runProbe() {
        report.clear()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = cameraManager.cameraIdList
            line("CAPABILITY_PROBE_START")
            line("=== ICAN CAMERA CAPABILITY REPORT ===")
            line("Total cameras found: ${cameraIds.size}")

            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                reportCamera(id, characteristics)
            }
            line("=== END OF REPORT ===")
            line("CAPABILITY_PROBE_COMPLETE")
            
            val finalReport = report.toString()
            
            // 1. Log once
            LogUtil.i(finalReport)
            
            // 2. Write to cache
            try {
                val file = File(context.cacheDir, "ican_camera_capabilities.txt")
                file.writeText(finalReport)
                LogUtil.i("Report written to: ${file.absolutePath}")
            } catch (e: Exception) {
                LogUtil.e("Failed to write report to file", e)
            }
            
        } catch (e: Exception) {
            LogUtil.e("Failed to run camera capability probe", e)
        }
    }

    private fun line(text: String) {
        report.append(text).append("\n")
    }

    private fun reportCamera(id: String, chars: CameraCharacteristics) {
        val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "BACK"
            CameraMetadata.LENS_FACING_FRONT -> "FRONT"
            CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

        val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

        line("\nCamera ID: $id")
        line("Facing: $facing")
        line("Hardware Level: $hwLevel")

        reportSensor(chars)
        reportLens(chars)
        reportControl(chars)
        reportFlash(chars)
        reportCapabilities(chars)
        reportStreams(chars)
        reportMultiCamera(chars)
    }

    private fun reportSensor(chars: CameraCharacteristics) {
        line("Sensor:")
        line("  Active Array: ${chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)}")
        line("  Pixel Array: ${chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")
        line("  Physical Size: ${chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)}")
        line("  Sensitivity Range: ${chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)}")
        line("  Exposure Time Range: ${chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)}")
        line("  Max Frame Duration: ${chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)}")
        line("  Orientation: ${chars.get(CameraCharacteristics.SENSOR_ORIENTATION)}")
    }

    private fun reportLens(chars: CameraCharacteristics) {
        line("Lens:")
        line("  Focal Lengths: ${chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()}")
        line("  Apertures: ${chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString()}")
        line("  Min Focus Distance: ${chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)}")
        line("  Hyperfocal Distance: ${chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)}")
        
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        line("  OIS Modes: ${oisModes?.joinToString { mode ->
            when (mode) {
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
                else -> "UNKNOWN($mode)"
            }
        }}")
    }

    private fun reportControl(chars: CameraCharacteristics) {
        line("Control:")
        line("  AE Modes: ${chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.joinToString()}")
        line("  AF Modes: ${chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString()}")
        line("  AWB Modes: ${chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.joinToString()}")
        line("  Scene Modes: ${chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.joinToString()}")
        line("  Antibanding Modes: ${chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.joinToString()}")
        line("  Exp Comp Range: ${chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)}")
        line("  Exp Comp Step: ${chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)}")
    }

    private fun reportFlash(chars: CameraCharacteristics) {
        line("Flash:")
        line("  Available: ${chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)}")
    }

    private fun reportCapabilities(chars: CameraCharacteristics) {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        line("Capabilities:")
        line("  RAW: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)}")
        line("  Manual Sensor: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)}")
        line("  Manual Post Processing: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)}")
        line("  Burst Capture: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)}")
        line("  YUV Reprocessing: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING)}")
        line("  Private Reprocessing: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING)}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            line("  Logical Multi Camera: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)}")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            line("  Ultra High Res Sensor: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            line("  Dynamic Range 10-bit: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)}")
            line("  Stream Use Case: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE)}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            line("  Color Space Profiles: ${hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES)}")
        }
    }

    private fun hasCap(caps: IntArray, cap: Int): String = if (caps.contains(cap)) "YES" else "NO"

    private fun reportStreams(chars: CameraCharacteristics) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        line("Streams:")
        reportFormatSizes(map, ImageFormat.JPEG, "JPEG")
        reportFormatSizes(map, ImageFormat.YUV_420_888, "YUV_420_888")
        reportFormatSizes(map, ImageFormat.RAW_SENSOR, "RAW_SENSOR")
    }

    private fun reportFormatSizes(map: StreamConfigurationMap, format: Int, name: String) {
        val sizes = map.getOutputSizes(format) ?: return
        if (sizes.isEmpty()) return
        
        val sorted = sizes.sortedByDescending { it.width * it.height }
        line("  $name max: ${sorted.first()}")
        if (sorted.size > 1) {
            line("  $name samples: ${sorted.take(3).joinToString()} ... ${sorted.last()}")
        }
    }

    private fun reportMultiCamera(chars: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val physicalIds = chars.physicalCameraIds
            if (physicalIds.isNotEmpty()) {
                line("Physical cameras: ${physicalIds.joinToString()}")
            }
        }
    }
}
