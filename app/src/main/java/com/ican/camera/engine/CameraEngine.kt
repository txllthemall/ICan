package com.ican.camera.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
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
import com.ican.camera.manual.ExposureBracketController
import com.ican.camera.manual.ExposureMode
import com.ican.camera.manual.FocusMode
import com.ican.camera.manual.ManualSensorController
import com.ican.camera.manual.ManualSensorState
import com.ican.camera.manual.ObservedSensorState
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraEngine(private val context: Context) {

    private val _state = MutableStateFlow(CameraState())
    val state = _state.asStateFlow()

    private val processingPipeline = ProcessingPipeline(context)
    private val cameraMapper = PhysicalCameraMapper(context)
    private val manualController = ManualSensorController(context)
    private val bracketController by lazy { ExposureBracketController(context, manualController, cameraExecutor) }
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

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(resolutionSelector)

            // Add CaptureResult observer
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val observed = ObservedSensorState(
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                        exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                        aeMode = result.get(CaptureResult.CONTROL_AE_MODE),
                        aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                        awbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
                        awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
                        afMode = result.get(CaptureResult.CONTROL_AF_MODE),
                        afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    )
                    
                    // Throttle state update
                    if (shouldUpdateObservedState(observed)) {
                        _state.update { it.copy(observedSensorState = observed) }
                    }
                    
                    manualController.onCaptureResult(observed)
                }
            }
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
            
            preview = previewBuilder.build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

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
                
                // 1. Resolve CameraInfo and RAW capabilities without binding yet
                val cameraInfo = cameraProvider!!.getCameraInfo(cameraSelector)
                val caps = ImageCapture.getImageCaptureCapabilities(cameraInfo)
                val supportedFormats = caps.supportedOutputFormats
                val rawSupported = supportedFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)
                val rawJpegSupported = supportedFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)

                val useRaw = _state.value.isRawCaptureEnabled && rawJpegSupported

                // 2. Build ImageCapture with appropriate format
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(resolutionSelector)
                    .setFlashMode(_state.value.flashMode)
                    .also {
                        if (useRaw) {
                            it.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                        }
                    }
                    .build()

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(_state.value.selectedQuality ?: Quality.FHD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                // Get candidate IDs for debugging
                val candidates = cameraProvider?.availableCameraInfos
                    ?.filter { it.lensFacing == lensFacing }
                    ?.map { Camera2CameraInfo.from(it).cameraId }
                    ?: emptyList()

                // 3. Single atomic bind for all use cases
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

                val limits = manualController.updateLimits(boundId)

                _state.update { it.copy(
                    isReady = true,
                    isRawSupported = rawSupported,
                    isRawJpegSupported = rawJpegSupported,
                    hasFlash = capabilities?.hasFlash ?: false,
                    hasTorch = camera?.cameraInfo?.hasFlashUnit() ?: false,
                    supportedQualities = supportedQualities,
                    selectedQuality = initialQuality,
                    minZoomRatio = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f,
                    maxZoomRatio = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1.0f,
                    manualLimits = limits
                ) }
                LogUtil.i("CAMERA_READY boundId=$boundId RAW_JPEG=$useRaw")
                
                if (_state.value.mode == CameraMode.PHOTO) {
                    manualController.applyState(camera!!.cameraControl, _state.value.manualSensorState, requestedLens)
                } else {
                    // Ensure manual overrides are cleared in VIDEO
                    Camera2CameraControl.from(camera!!.cameraControl).setCaptureRequestOptions(CaptureRequestOptions.Builder().build())
                }
                
                generateLensSelectionReport(requestedLens, targetInfo?.id, candidates, boundId, null)
                
            } catch (exc: Exception) {
                LogUtil.e("Use case binding failed", exc)
                _state.update { it.copy(error = exc.message) }
                generateLensSelectionReport(requestedLens, targetInfo?.id, emptyList(), "NONE", exc.message)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private var lastObservedUpdateTime = 0L
    private fun shouldUpdateObservedState(new: ObservedSensorState): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastObservedUpdateTime > 1000) { // Update state at most once per second
            lastObservedUpdateTime = now
            return true
        }
        return false
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

    @OptIn(ExperimentalCamera2Interop::class)
    fun setCameraMode(mode: CameraMode) {
        if (_state.value.mode == mode) return
        LogUtil.i("CAMERA_MODE_SWITCH to $mode")
        
        if (mode == CameraMode.VIDEO && camera != null) {
            // Clear manual overrides before entering VIDEO
            Camera2CameraControl.from(camera!!.cameraControl).setCaptureRequestOptions(CaptureRequestOptions.Builder().build())
        }
        
        _state.update { it.copy(mode = mode) }
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

    fun setRawCaptureEnabled(enabled: Boolean) {
        if (_state.value.isRawCaptureEnabled == enabled) return
        _state.update { it.copy(isRawCaptureEnabled = enabled, isReady = false) }
    }

    fun setManualSensorState(newState: ManualSensorState) {
        _state.update { it.copy(manualSensorState = newState) }
        if (_state.value.mode == CameraMode.PHOTO && camera != null) {
            manualController.applyState(camera!!.cameraControl, newState, _state.value.selectedRearLens)
        }
    }

    fun toggleManualControls() {
        _state.update { it.copy(isManualControlsVisible = !it.isManualControlsVisible) }
    }

    fun runBracket() {
        if (_state.value.bracketState != BracketState.IDLE) return
        val imageCapture = imageCapture ?: return
        val cameraControl = camera?.cameraControl ?: return
        val observed = _state.value.observedSensorState
        val lens = _state.value.selectedRearLens
        val previousState = _state.value.manualSensorState

        engineScope.launch {
            try {
                val success = bracketController.runBracket(
                    imageCapture = imageCapture,
                    cameraControl = cameraControl,
                    observed = observed,
                    semanticLens = lens,
                    onStateUpdate = { newState -> _state.update { it.copy(bracketState = newState) } },
                    onThumbnailUpdate = { uri -> _state.update { it.copy(lastThumbnailUri = uri, lastThumbnailMimeType = "image/jpeg") } }
                )
                
                _state.update { it.copy(bracketState = if (success) BracketState.COMPLETE else BracketState.FAILED) }
            } finally {
                // Restoration
                _state.update { it.copy(bracketState = BracketState.RESTORING) }
                manualController.applyState(cameraControl, previousState, lens)
                delay(500)
                _state.update { it.copy(bracketState = BracketState.IDLE) }
            }
        }
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

        if (_state.value.isRawCaptureEnabled && _state.value.isRawJpegSupported) {
            takeRawJpegPhoto(imageCapture, tRequested)
        } else if (_state.value.processingMode == ProcessingMode.NONE) {
            takeDirectPhoto(imageCapture, tRequested)
        } else {
            takeProcessedPhoto(imageCapture, tRequested)
        }
    }

    private fun takeRawJpegPhoto(imageCapture: ImageCapture, tRequested: Long) {
        LogUtil.i("RAW_JPEG_CAPTURE_REQUESTED")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        
        val rawValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ICAN_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ICan/RAW")
            }
        }
        val rawOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            rawValues
        ).build()

        val jpegValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ICAN_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ICan/RAW")
            }
        }
        val jpegOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            jpegValues
        ).build()

        var rawUri: Uri? = null
        var jpegUri: Uri? = null
        val isFinished = AtomicBoolean(false)

        imageCapture.takePicture(
            rawOptions,
            jpegOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() {
                    LogUtil.i("RAW_JPEG_CAPTURE_STARTED")
                    decrementInFlight()
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri
                    if (outputFileResults.imageFormat == ImageCapture.OUTPUT_FORMAT_RAW) {
                        rawUri = uri
                        LogUtil.i("RAW_SAVED: $rawUri")
                    } else {
                        jpegUri = uri
                        LogUtil.i("JPEG_SAVED: $jpegUri")
                        // Restore thumbnail behavior: use JPEG component
                        _state.update { it.copy(lastThumbnailUri = uri, lastThumbnailMimeType = "image/jpeg") }
                    }

                    if (rawUri != null && jpegUri != null) {
                        if (isFinished.compareAndSet(false, true)) {
                            LogUtil.i("RAW_JPEG_VALIDATED")
                            generateRawValidationReport(rawUri, jpegUri, null)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (isFinished.compareAndSet(false, true)) {
                        LogUtil.e("RAW_JPEG capture failed", exception)
                        generateRawValidationReport(null, null, exception.message)
                    }
                }
            }
        )
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
        _state.update { it.copy(lensFacing = newLensFacing, isReady = false, selectedRearLens = RearLens.MAIN, isRawCaptureEnabled = false) }
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

    private fun generateRawValidationReport(rawUri: Uri?, jpegUri: Uri?, error: String?) {
        val report = StringBuilder()
        report.append("=== ICAN RAW CAPTURE VALIDATION ===\n\n")
        report.append("Semantic Lens: ${_state.value.selectedRearLens}\n")
        report.append("RAW Supported: ${_state.value.isRawSupported}\n")
        report.append("RAW+JPEG Supported: ${_state.value.isRawJpegSupported}\n\n")
        
        if (error != null) {
            report.append("Capture Status: FAILED\n")
            report.append("Error: $error\n")
        } else {
            report.append("RAW Capture:\n")
            report.append("Status: SUCCESS\n")
            report.append("URI: $rawUri\n")
            
            if (jpegUri != null) {
                report.append("\nJPEG Companion:\n")
                report.append("Status: SUCCESS\n")
                report.append("URI: $jpegUri\n")
            }
        }
        report.append("\n=== END ===\n")
        
        try {
            val file = File(context.cacheDir, "ican_raw_capture_validation.txt")
            file.writeText(report.toString())
            LogUtil.i("RAW validation report written to: ${file.absolutePath}")
        } catch (e: Exception) {
            LogUtil.e("Failed to write RAW report", e)
        }
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
