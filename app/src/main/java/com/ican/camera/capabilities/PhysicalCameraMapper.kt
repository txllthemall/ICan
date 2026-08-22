package com.ican.camera.capabilities

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import com.ican.camera.util.LogUtil
import java.io.File

class PhysicalCameraMapper(private val context: Context) {

    private val report = StringBuilder()

    fun mapCameras() {
        report.clear()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = cameraManager.cameraIdList
            val allInfo = mutableListOf<PhysicalCameraInfo>()

            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
                val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                val classification = if (facing == CameraMetadata.LENS_FACING_FRONT) {
                    CameraClassification.FRONT
                } else {
                    CameraClassification.UNKNOWN // Will refine below
                }

                allInfo.add(PhysicalCameraInfo(
                    id = id,
                    facing = facing,
                    focalLengths = focalLengths,
                    physicalSize = physicalSize,
                    activeArraySize = activeArray,
                    classification = classification
                ))
            }

            // Identify Logical Multi-Cameras and their children
            val mappedInfo = refineClassifications(cameraManager, allInfo)

            generateReport(mappedInfo)
            
            val finalReport = report.toString()
            LogUtil.i(finalReport)
            
            try {
                val file = File(context.cacheDir, "ican_physical_camera_map.txt")
                file.writeText(finalReport)
            } catch (e: Exception) {
                LogUtil.e("Failed to write camera map to file", e)
            }

        } catch (e: Exception) {
            LogUtil.e("Failed to map physical cameras", e)
        }
    }

    private fun refineClassifications(
        cameraManager: CameraManager,
        allInfo: List<PhysicalCameraInfo>
    ): List<PhysicalCameraInfo> {
        val result = allInfo.toMutableList()

        // 1. Identify physical children of logical cameras
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val logicalToPhysical = mutableMapOf<String, Set<String>>()
            for (info in allInfo) {
                val chars = cameraManager.getCameraCharacteristics(info.id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                    logicalToPhysical[info.id] = chars.physicalCameraIds
                }
            }

            // 2. Classify physical BACK cameras
            logicalToPhysical.forEach { (logicalId, physicalIds) ->
                val physicalBackCameras = result.filter { physicalIds.contains(it.id) && it.facing == CameraMetadata.LENS_FACING_BACK }
                
                if (physicalBackCameras.isNotEmpty()) {
                    // Sort by focal length (primary focal length is index 0)
                    val sortedByFocal = physicalBackCameras.sortedBy { it.focalLengths.firstOrNull() ?: 0f }
                    
                    if (sortedByFocal.size == 1) {
                        updateClassification(result, sortedByFocal[0].id, CameraClassification.MAIN, logicalId)
                    } else if (sortedByFocal.size == 2) {
                        updateClassification(result, sortedByFocal[0].id, CameraClassification.ULTRAWIDE, logicalId)
                        updateClassification(result, sortedByFocal[1].id, CameraClassification.MAIN, logicalId)
                    } else if (sortedByFocal.size >= 3) {
                        updateClassification(result, sortedByFocal[0].id, CameraClassification.ULTRAWIDE, logicalId)
                        // Middle ones are MAIN, if many, middle-ish or first after UW
                        // On OP15, it's 2.31 (UW), 5.59 (MAIN), 12.19 (TELE)
                        updateClassification(result, sortedByFocal[1].id, CameraClassification.MAIN, logicalId)
                        updateClassification(result, sortedByFocal.last().id, CameraClassification.TELEPHOTO, logicalId)
                        
                        // If there are more in between, they are UNKNOWN or also TELE/MAIN
                        for (i in 2 until sortedByFocal.size - 1) {
                             updateClassification(result, sortedByFocal[i].id, CameraClassification.UNKNOWN, logicalId)
                        }
                    }
                }
            }
        } else {
            // Fallback for older APIs: classify based on simple heuristics or just mark as MAIN
            result.forEachIndexed { index, info ->
                if (info.classification == CameraClassification.UNKNOWN && info.facing == CameraMetadata.LENS_FACING_BACK) {
                    result[index] = info.copy(classification = CameraClassification.MAIN)
                }
            }
        }

        return result
    }

    private fun updateClassification(list: MutableList<PhysicalCameraInfo>, id: String, classification: CameraClassification, parentId: String) {
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list[index] = list[index].copy(classification = classification, parentLogicalId = parentId)
        }
    }

    private fun generateReport(mappedInfo: List<PhysicalCameraInfo>) {
        line("=== ICAN PHYSICAL CAMERA MAP ===")
        
        val logicalRears = mappedInfo.filter { info ->
            info.facing == CameraMetadata.LENS_FACING_BACK && mappedInfo.any { it.parentLogicalId == info.id }
        }
        
        logicalRears.forEach { logical ->
            line("\nLogical rear: ${logical.id}")
            line("\nPhysical rear cameras:")
            mappedInfo.filter { it.parentLogicalId == logical.id }.forEach { physical ->
                line("ID ${physical.id} -> ${physical.classification}")
                line("Focal: ${physical.focalLengths.firstOrNull() ?: "UNKNOWN"} mm")
            }
        }

        val fronts = mappedInfo.filter { it.classification == CameraClassification.FRONT }
        if (fronts.isNotEmpty()) {
            line("\nFront:")
            fronts.forEach { front ->
                line("ID ${front.id} -> ${front.classification}")
            }
        }

        line("\n=== END CAMERA MAP ===")
    }

    private fun line(text: String) {
        report.append(text).append("\n")
    }
}
