package com.ican.camera.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.ican.camera.capabilities.PhysicalCameraMapper
import com.ican.camera.capabilities.RearLens
import com.ican.camera.processing.ProcessingMode
import com.ican.camera.processing.ProcessingPipeline
import com.ican.camera.util.LogUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CameraEngine(private val context: Context) {

    private val _state = MutableStateFlow(CameraState())
    val state = _state.asStateFlow()

    private val processingPipeline = ProcessingPipeline(context)
    private val cameraMapper = PhysicalCameraMapper(context)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var zoomObserver: Observer<ZoomState>? = null
    
    private val lensSelectionResults = mutableMapOf<RearLens, String>()

    private val inFlightCount = AtomicInteger(0)
    private val MAX_IN_FLIGHT = 3

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val requestedLens = _state.value.selectedRearLens
        LogUtil.i("CAMERA_BIND_START lens=$requestedLens")
        _state.update { it.copy(isReady = false) }
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resolutionSelector)
                .setFlashMode(_state.value.flashMode)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(_state.value.selectedQuality ?: Quality.FHD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val lensFacing = _state.value.lensFacing
            val cameraSelectorBuilder = CameraSelector.Builder()
                .requireLensFacing(lensFacing)

            val cameraMap = cameraMapper.getCameraMap()
            val targetInfo = if (lensFacing == CameraSelector.LENS_FACING_BACK) cameraMap[requestedLens] else null
            
            if (targetInfo != null) {
                val targetId = targetInfo.id
                LogUtil.d("Targeting physical camera ID: $targetId for $requestedLens")
                cameraSelectorBuilder.addCameraFilter { cameraInfos ->
                    cameraInfos.filter { info ->
                        Camera2CameraInfo.from(info).cameraId == targetId
                    }
                }
            }

            val cameraSelector = cameraSelectorBuilder.build()

            try {
                cameraProvider?.unbindAll()
                
                // Get candidate IDs for debugging
                val candidates = cameraProvider?.availableCameraInfos
                    ?.filter { it.lensFacing == lensFacing }
                    ?.map { Camera2CameraInfo.from(it).cameraId }
                    ?: emptyList()

                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    videoCapture
                )
                
                setupCameraObservers(lifecycleOwner)
                
                val capabilities = camera?.cameraInfo?.let { CameraCapabilities(it) }
                val supportedQualities = capabilities?.supportedQualities ?: emptyList()
                val initialQuality = if (supportedQualities.contains(Quality.FHD)) Quality.FHD 
                                     else supportedQualities.firstOrNull() ?: Quality.SD

                val boundId = camera?.cameraInfo?.let { Camera2CameraInfo.from(it).cameraId } ?: "UNKNOWN"

                _state.update { it.copy(
                    isReady = true,
                    hasFlash = capabilities?.hasFlash ?: false,
                    hasTorch = camera?.cameraInfo?.hasFlashUnit() ?: false,
                    supportedQualities = supportedQualities,
                    selectedQuality = initialQuality,
                    minZoomRatio = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f,
                    maxZoomRatio = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1.0f
                ) }
                LogUtil.i("CAMERA_READY boundId=$boundId")
                
                generateLensSelectionReport(requestedLens, targetInfo?.id, candidates, boundId, null)
                
            } catch (exc: Exception) {
                LogUtil.e("Use case binding failed", exc)
                _state.update { it.copy(error = exc.message) }
                generateLensSelectionReport(requestedLens, targetInfo?.id, emptyList(), "NONE", exc.message)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun setupCameraObservers(lifecycleOwner: LifecycleOwner) {
        zoomObserver?.let { camera?.cameraInfo?.zoomState?.removeObserver(it) }
        
        val observer = Observer<ZoomState> { zoomState ->
            _state.update { it.copy(
                zoomRatio = zoomState.zoomRatio,
                minZoomRatio = zoomState.minZoomRatio,
                maxZoomRatio = zoomState.maxZoomRatio
            ) }
        }
        zoomObserver = observer
        camera?.cameraInfo?.zoomState?.observe(lifecycleOwner, observer)
    }

    fun setCameraMode(mode: CameraMode) {
        if (_state.value.mode == mode) return
        LogUtil.i("VIDEO_MODE_ENTER_REQUESTED")
        _state.update { it.copy(mode = mode) }
        LogUtil.i("VIDEO_MODE_READY")
    }

    fun setProcessingMode(mode: ProcessingMode) {
        if (_state.value.processingMode == mode) return
        processingPipeline.setMode(mode)
        _state.update { it.copy(processingMode = mode) }
    }

    fun setRearLens(lens: RearLens) {
        if (_state.value.selectedRearLens == lens) return
        _state.update { it.copy(selectedRearLens = lens, isReady = false) }
    }

    fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val currentCount = inFlightCount.get()
        if (!_state.value.isReady || currentCount >= MAX_IN_FLIGHT) {
            LogUtil.d("Capture ignored: ready=${_state.value.isReady}, inFlight=$currentCount")
            return
        }

        val tRequested = SystemClock.elapsedRealtimeNanos()
        LogUtil.i("SHUTTER_REQUESTED")

        val newCount = inFlightCount.incrementAndGet()
        updateCapturingState(newCount)

        if (_state.value.processingMode == ProcessingMode.NONE) {
            takeDirectPhoto(imageCapture, tRequested)
        } else {
            takeProcessedPhoto(imageCapture, tRequested)
        }
    }

    private fun takeDirectPhoto(imageCapture: ImageCapture, tRequested: Long) {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ICan-Camera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() {
                    val tCaptureStarted = SystemClock.elapsedRealtimeNanos()
                    LogUtil.i("CAPTURE_STARTED (Delta Requested: ${(tCaptureStarted - tRequested) / 1_000_000}ms)")
                    decrementInFlight()
                    LogUtil.i("NEXT_CAPTURE_ACCEPTED (Delta Requested: ${(SystemClock.elapsedRealtimeNanos() - tRequested) / 1_000_000}ms)")
                }

                override fun onError(exc: ImageCaptureException) {
                    LogUtil.e("Photo capture failed: ${exc.message}", exc)
                    decrementInFlight()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val tSaved = SystemClock.elapsedRealtimeNanos()
                    LogUtil.i("IMAGE_SAVED (Total Latency: ${(tSaved - tRequested) / 1_000_000}ms)")
                    
                    _state.update { it.copy(
                        lastThumbnailUri = output.savedUri,
                        lastThumbnailMimeType = "image/jpeg"
                    ) }
                    LogUtil.i("NEXT_SHOT_READY")
                }
            }
        )
    }

    private fun takeProcessedPhoto(imageCapture: ImageCapture, tRequested: Long) {
        LogUtil.i("PROCESS_CAPTURE_REQUESTED mode=${_state.value.processingMode}")
        
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureStarted() {
                    val tCaptureStarted = SystemClock.elapsedRealtimeNanos()
                    LogUtil.i("CAPTURE_STARTED (Delta Requested: ${(tCaptureStarted - tRequested) / 1_000_000}ms)")
                    decrementInFlight()
                    LogUtil.i("NEXT_CAPTURE_ACCEPTED")
                }

                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    LogUtil.i("PROCESS_INPUT_READY")
                    val strategy = processingPipeline.getPhotoStrategy()
                    
                    engineScope.launch {
                        try {
                            val result = strategy.processPhoto(imageProxy)
                            if (result.success) {
                                _state.update { it.copy(
                                    lastThumbnailUri = result.outputUri,
                                    lastThumbnailMimeType = "image/jpeg"
                                ) }
                                LogUtil.i("NEXT_SHOT_READY")
                            } else {
                                LogUtil.e("Processing failed: ${result.error?.message}")
                            }
                        } catch (e: Exception) {
                            LogUtil.e("Unexpected error in processed path", e)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    LogUtil.e("Photo capture failed", exception)
                    decrementInFlight()
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val videoCapture = videoCapture ?: return
        if (_state.value.recordingState != RecordingState.IDLE) return

        LogUtil.i("RECORD_REQUESTED")
        _state.update { it.copy(recordingState = RecordingState.STARTING, recordingDurationMillis = 0) }

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ICan-Camera")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        val strategy = processingPipeline.getVideoStrategy()
        strategy.configurePipeline()

        val recording = videoCapture.output
            .prepareRecording(context, mediaStoreOutput)
            .apply {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(cameraExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        LogUtil.i("RECORDING_STARTED")
                        _state.update { it.copy(recordingState = RecordingState.RECORDING) }
                    }
                    is VideoRecordEvent.Finalize -> {
                        LogUtil.i("RECORDING_FINALIZED")
                        val uri = event.outputResults.outputUri
                        _state.update { it.copy(
                            recordingState = RecordingState.IDLE,
                            lastThumbnailUri = if (event.hasError().not()) uri else it.lastThumbnailUri,
                            lastThumbnailMimeType = if (event.hasError().not()) "video/mp4" else it.lastThumbnailMimeType,
                            recordingDurationMillis = 0
                        ) }
                        if (event.hasError()) {
                            LogUtil.e("Video recording error: ${event.error}")
                        }
                        activeRecording = null
                    }
                    is VideoRecordEvent.Status -> {
                        val duration = event.recordingStats.recordedDurationNanos / 1_000_000
                        _state.update { it.copy(recordingDurationMillis = duration) }
                    }
                }
            }

        activeRecording = recording
    }

    fun stopRecording() {
        if (_state.value.recordingState != RecordingState.RECORDING) return
        LogUtil.i("STOP_REQUESTED")
        _state.update { it.copy(recordingState = RecordingState.STOPPING) }
        activeRecording?.stop()
    }

    private fun updateCapturingState(count: Int) {
        _state.update { it.copy(isCapturing = count >= MAX_IN_FLIGHT) }
    }

    private fun decrementInFlight() {
        val countAfterSub = inFlightCount.decrementAndGet().coerceAtLeast(0)
        updateCapturingState(countAfterSub)
    }

    fun switchCamera() {
        if (_state.value.recordingState != RecordingState.IDLE) return
        
        val newLensFacing = if (_state.value.lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        _state.update { it.copy(lensFacing = newLensFacing, isReady = false, selectedRearLens = RearLens.MAIN) }
    }

    fun setFlashMode(flashMode: Int) {
        imageCapture?.flashMode = flashMode
        _state.update { it.copy(flashMode = flashMode) }
    }

    fun toggleTorch() {
        val newTorchState = !_state.value.isTorchOn
        camera?.cameraControl?.enableTorch(newTorchState)
        _state.update { it.copy(isTorchOn = newTorchState) }
    }

    fun setQuality(quality: Quality) {
        if (_state.value.selectedQuality == quality) return
        _state.update { it.copy(selectedQuality = quality, isReady = false) }
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun focus(x: Float, y: Float, factory: MeteringPointFactory) {
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun generateLensSelectionReport(
        requested: RearLens,
        resolvedId: String?,
        candidates: List<String>,
        boundId: String,
        exception: String?
    ) {
        // Only record results for rear camera bindings
        if (resolvedId != null) {
            val cameraMap = cameraMapper.getCameraMap()
            val mappedInfo = cameraMap.values.find { it.id == boundId }

            val entry = StringBuilder()
            entry.append("$requested:\n")
            entry.append("- Requested Semantic Lens: $requested\n")
            entry.append("- Target Physical ID: $resolvedId\n")
            entry.append("- Candidate IDs: ${candidates.joinToString()}\n")
            entry.append("- Actually Bound ID: $boundId\n")
            entry.append("- Focal Length: ${mappedInfo?.focalLengths?.firstOrNull() ?: "UNKNOWN"} mm\n")
            entry.append("- Sensor Physical Size: ${mappedInfo?.physicalSize ?: "UNKNOWN"}\n")
            entry.append("- Bind Success: ${exception == null}\n")
            if (exception != null) {
                entry.append("- Exception: $exception\n")
            }
            
            lensSelectionResults[requested] = entry.toString()
        }

        // Build the cumulative report
        val reportBuilder = StringBuilder()
        reportBuilder.append("=== ICAN PHYSICAL LENS SELECTION ===\n\n")
        
        RearLens.entries.forEach { lens ->
            val result = lensSelectionResults[lens]
            if (result != null) {
                reportBuilder.append(result).append("\n")
            } else {
                reportBuilder.append("$lens: NOT TESTED YET\n\n")
            }
        }
        
        reportBuilder.append("=== END ===\n")
        
        val finalReport = reportBuilder.toString()
        LogUtil.i(finalReport)
        
        try {
            val file = File(context.cacheDir, "ican_physical_lens_selection.txt")
            file.writeText(finalReport)
        } catch (e: Exception) {
            LogUtil.e("Failed to write lens selection report", e)
        }
    }

    fun release() {
        activeRecording?.stop()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        engineScope.cancel()
    }
}
