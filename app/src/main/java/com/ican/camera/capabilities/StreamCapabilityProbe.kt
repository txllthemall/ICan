package com.ican.camera.capabilities

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import com.ican.camera.util.LogUtil
import java.io.File

class StreamCapabilityProbe(private val context: Context) {

    private val report = StringBuilder()
    private val cameraMapper = PhysicalCameraMapper(context)

    fun runProbe() {
        report.clear()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraMap = cameraMapper.getCameraMap()
        
        try {
            val cameraIds = cameraManager.cameraIdList
            line("=== ICAN STREAM CAPABILITY REPORT ===")
            line("Total cameras found: ${cameraIds.size}")

            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val semanticLens = cameraMap.entries.find { it.value.id == id }?.key
                reportCameraStreams(id, semanticLens, characteristics)
            }
            line("=== END REPORT ===")
            
            val finalReport = report.toString()
            LogUtil.i(finalReport)
            
            try {
                val file = File(context.cacheDir, "ican_stream_capabilities.txt")
                file.writeText(finalReport)
            } catch (e: Exception) {
                LogUtil.e("Failed to write stream report to file", e)
            }
            
        } catch (e: Exception) {
            LogUtil.e("Failed to run stream capability probe", e)
        }
    }

    private fun line(text: String) {
        report.append(text).append("\n")
    }

    private fun reportCameraStreams(id: String, semanticLens: RearLens?, chars: CameraCharacteristics) {
        line("--------------------------------------------------")
        line("Camera ID: $id")
        semanticLens?.let { line("Semantic Lens: $it") }

        reportFpsRanges(chars)
        reportStabilization(chars)
        reportVideoSizes(chars)
        reportDynamicRange(chars)
        reportColorSpaces(chars)
        reportStreamUseCases(chars)
    }

    private fun reportFpsRanges(chars: CameraCharacteristics) {
        line("\nNormal FPS ranges:")
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ranges?.forEach { range ->
            line("- ${range.lower}-${range.upper}")
        }
    }

    private fun reportStabilization(chars: CameraCharacteristics) {
        line("\nVideo stabilization:")
        val videoModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        videoModes?.forEach { mode ->
            val name = when (mode) {
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON"
                2 -> "PREVIEW_STABILIZATION" // CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                else -> "UNKNOWN($mode)"
            }
            line("- $name")
        }

        line("\nOptical stabilization:")
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        oisModes?.forEach { mode ->
            val name = when (mode) {
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
                else -> "UNKNOWN($mode)"
            }
            line("- $name")
        }
    }

    private fun reportVideoSizes(chars: CameraCharacteristics) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        
        line("\nNormal video sizes (PRIVATE):")
        val sizes = map.getOutputSizes(ImageFormat.PRIVATE) ?: emptyArray()
        val targets = listOf(2160, 1440, 1080, 720)
        val filtered = sizes.filter { it.height in targets || it.width in targets }
            .sortedByDescending { it.width * it.height }
            .distinctBy { "${it.width}x${it.height}" }
        
        filtered.forEach { size ->
            val minDuration = map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size)
            val maxFps = if (minDuration > 0) (1_000_000_000L / minDuration).toInt() else 0
            line("- ${size.width}x${size.height} (Min duration: $minDuration ns, Max FPS: $maxFps)")
        }

        line("\nHigh-speed video:")
        val hsSizes = map.highSpeedVideoSizes ?: emptyArray()
        hsSizes.forEach { size ->
            val hsRanges = map.getHighSpeedVideoFpsRangesFor(size)
            line("- ${size.width}x${size.height}: ${hsRanges.joinToString { "${it.lower}-${it.upper}" }}")
        }
    }

    private fun reportDynamicRange(chars: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            line("\nDynamic range:")
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            profiles?.getSupportedProfiles()?.forEach { profile ->
                line("- ${decodeDynamicRange(profile)}")
            }
            
            // Try getRecommendedProfile() instead
            try {
                val method = profiles?.javaClass?.getMethod("getRecommendedProfile")
                val recommended = method?.invoke(profiles) as? Long
                if (recommended != null) {
                    line("Recommended profile: ${decodeDynamicRange(recommended)}")
                }
            } catch (e: Exception) {
                // Ignore if method not found (though it should be there in API 33)
            }
        }
    }

    private fun decodeDynamicRange(profile: Long): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "UNKNOWN($profile)"
        return when (profile) {
            DynamicRangeProfiles.STANDARD -> "STANDARD"
            DynamicRangeProfiles.HLG10 -> "HLG10"
            DynamicRangeProfiles.HDR10 -> "HDR10"
            DynamicRangeProfiles.HDR10_PLUS -> "HDR10_PLUS"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF -> "DOLBY_VISION_10B_HDR_REF"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO -> "DOLBY_VISION_10B_HDR_REF_PO"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM -> "DOLBY_VISION_10B_HDR_OEM"
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO -> "DOLBY_VISION_10B_HDR_OEM_PO"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF -> "DOLBY_VISION_8B_HDR_REF"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO -> "DOLBY_VISION_8B_HDR_REF_PO"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM -> "DOLBY_VISION_8B_HDR_OEM"
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO -> "DOLBY_VISION_8B_HDR_OEM_PO"
            else -> "UNKNOWN($profile)"
        }
    }

    private fun reportColorSpaces(chars: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            line("\nColor spaces:")
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
            listOf(ImageFormat.PRIVATE, ImageFormat.YUV_420_888, ImageFormat.JPEG).forEach { format ->
                // ColorSpaceProfiles.getSupportedColorSpaces(int format)
                try {
                    val method = profiles?.javaClass?.getMethod("getSupportedColorSpaces", Int::class.javaPrimitiveType)
                    val spaces = method?.invoke(profiles, format) as? Set<*>
                    if (!spaces.isNullOrEmpty()) {
                        line("- Format $format: ${spaces.joinToString()}")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun reportStreamUseCases(chars: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE)) {
                line("\nStream Use Cases: SUPPORTED")
            }
        }
    }
}
