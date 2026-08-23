package com.ican.camera.manual

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.ican.camera.capabilities.RearLens
import com.ican.camera.engine.BracketState
import com.ican.camera.util.LogUtil
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

class ExposureBracketController(
    private val context: Context,
    private val manualController: ManualSensorController,
    private val cameraExecutor: Executor
) {
    private var currentSet: BracketSetMetadata? = null

    suspend fun runBracket(
        imageCapture: ImageCapture,
        cameraControl: CameraControl,
        observed: ObservedSensorState,
        semanticLens: RearLens,
        onStateUpdate: (BracketState) -> Unit,
        onThumbnailUpdate: (Uri) -> Unit
    ): Boolean {
        val baseIso = observed.iso ?: 100
        val baseExp = observed.exposureTimeNs ?: 33333333L
        val baseFocus = observed.focusDistanceDiopters ?: 0f
        
        val setId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentSet = BracketSetMetadata(setId, baseIso, baseExp, baseFocus)
        
        onStateUpdate(BracketState.PREPARING)
        
        val targets = listOf(
            Triple("-2 EV", -2f, BracketState.CAPTURING_MINUS),
            Triple("0 EV", 0f, BracketState.CAPTURING_BASE),
            Triple("+2 EV", 2f, BracketState.CAPTURING_PLUS)
        )

        val limits = manualController.updateLimits(manualController.getLastId() ?: "")

        try {
            for ((label, ev, state) in targets) {
                onStateUpdate(state)
                
                // Calculate target
                val multiplier = 2.0.pow(ev.toDouble())
                var targetExp = (baseExp * multiplier).toLong()
                var targetIso = baseIso
                
                // Clamp and compensate with ISO if needed
                if (targetExp < limits.exposureTimeRange.first) {
                    val remaining = targetExp.toDouble() / limits.exposureTimeRange.first
                    targetExp = limits.exposureTimeRange.first
                    targetIso = (targetIso.toDouble() * remaining).toInt().coerceIn(limits.isoRange)
                } else if (targetExp > limits.exposureTimeRange.last) {
                    val remaining = targetExp.toDouble() / limits.exposureTimeRange.last
                    targetExp = limits.exposureTimeRange.last
                    targetIso = (targetIso.toDouble() * remaining).toInt().coerceIn(limits.isoRange)
                }
                
                val actualEv = log2((targetExp.toDouble() / baseExp) * (targetIso.toDouble() / baseIso)).toFloat()

                val manualState = ManualSensorState(
                    exposureMode = ExposureMode.MANUAL,
                    focusMode = FocusMode.MANUAL,
                    iso = targetIso,
                    exposureTimeNs = targetExp,
                    focusDistanceDiopters = baseFocus,
                    aeLocked = true,
                    awbLocked = true
                )

                // 1. Apply
                manualController.applyState(cameraControl, manualState, semanticLens)
                
                // 2. Confirm (Wait for HAL to catch up)
                var confirmed = false
                withTimeoutOrNull(2000L) {
                    while (!confirmed) {
                        val currentObserved = manualController.getLastObserved()
                        if (currentObserved != null && 
                            currentObserved.iso != null &&
                            currentObserved.exposureTimeNs != null &&
                            abs(currentObserved.iso - targetIso) < 5 &&
                            abs(currentObserved.exposureTimeNs - targetExp) < (targetExp * 0.05).toLong()) {
                            confirmed = true
                        }
                        delay(50)
                    }
                }
                
                if (!confirmed) {
                    throw Exception("Hardware confirmation timed out for $label")
                }

                // 3. Capture
                val captureDeferred = CompletableDeferred<Uri?>()
                val filename = "ICAN_BRACKET_${setId}_${label.replace(" ", "").replace("-", "M").replace("+", "P")}"
                
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ICan/Brackets/$setId")
                    }
                }
                val outputOptions = ImageCapture.OutputFileOptions.Builder(
                    context.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ).build()

                imageCapture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        captureDeferred.complete(output.savedUri)
                    }
                    override fun onError(exc: ImageCaptureException) {
                        LogUtil.e("Bracket frame $label failed", exc)
                        captureDeferred.complete(null)
                    }
                })

                val uri = captureDeferred.await()
                
                // Record metadata
                val currentObserved = manualController.getLastObserved()
                val frameMeta = BracketFrameMetadata(
                    intendedEvOffset = ev,
                    actualEvOffset = actualEv,
                    requestedIso = targetIso,
                    requestedExposureTimeNs = targetExp,
                    observedIso = currentObserved?.iso,
                    observedExposureTimeNs = currentObserved?.exposureTimeNs,
                    observedFocusDistanceDiopters = currentObserved?.focusDistanceDiopters,
                    captureStatus = if (uri != null) "SUCCESS" else "FAILED",
                    outputUri = uri
                )
                
                currentSet = currentSet?.copy(frames = currentSet!!.frames + (label to frameMeta))
                uri?.let { onThumbnailUpdate(it) }
                
                if (uri == null) throw Exception("Frame capture failed")
            }
            
            currentSet = currentSet?.copy(result = "SUCCESS")
            writeReport()
            return true
        } catch (e: Exception) {
            LogUtil.e("Bracket sequence failed", e)
            currentSet = currentSet?.copy(result = "FAILED: ${e.message}")
            writeReport()
            return false
        }
    }

    private fun writeReport() {
        val set = currentSet ?: return
        val report = StringBuilder()
        report.append("=== ICAN EXPOSURE BRACKET VALIDATION ===\n\n")
        report.append("Set ID: ${set.setId}\n\n")
        report.append("Baseline:\n")
        report.append("Observed ISO: ${set.baselineIso}\n")
        report.append("Observed Exposure: ${set.baselineExposureTimeNs} ns\n")
        report.append("Focus: ${set.baselineFocusDistanceDiopters} D\n\n")

        listOf("-2 EV", "0 EV", "+2 EV").forEach { label ->
            val f = set.frames[label]
            report.append("FRAME $label\n")
            if (f != null) {
                report.append("Requested ISO: ${f.requestedIso}\n")
                report.append("Requested Exposure: ${f.requestedExposureTimeNs} ns\n")
                report.append("Observed ISO: ${f.observedIso}\n")
                report.append("Observed Exposure: ${f.observedExposureTimeNs} ns\n")
                report.append("Actual EV Offset: ${String.format(Locale.US, "%.2f", f.actualEvOffset)}\n")
                report.append("Capture: ${f.captureStatus}\n")
                report.append("URI: ${f.outputUri}\n")
            } else {
                report.append("NOT CAPTURED\n")
            }
            report.append("\n")
        }

        report.append("Result: ${set.result}\n")
        report.append("\n=== END ===\n")

        try {
            val file = File(context.cacheDir, "ican_bracket_validation.txt")
            file.writeText(report.toString())
        } catch (e: Exception) {
            LogUtil.e("Failed to write bracket report", e)
        }
    }
}
