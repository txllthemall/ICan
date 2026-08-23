package com.ican.camera.capabilities

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.ican.camera.util.LogUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class VideoConfig(
    val key: String,
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val isHighSpeed: Boolean,
    val dynamicRange: Long = 1L // DynamicRangeProfiles.STANDARD
)

data class ValidationResult(
    val advertised: Boolean = false,
    val sessionCreatable: String = "NOT TESTED",
    val failureMessage: String? = null
)

class VideoConfigurationValidator(private val context: Context) {

    private val cameraMapper = PhysicalCameraMapper(context)
    private val results = mutableMapOf<String, ValidationResult>()

    private val targetConfigs = listOf(
        VideoConfig("1080p60_STD", "1080p60 STANDARD", 1920, 1080, 60, false),
        VideoConfig("4K30_STD", "4K30 STANDARD", 3840, 2160, 30, false),
        VideoConfig("1080p120_HS", "1080p120 HIGH_SPEED", 1920, 1080, 120, true),
        VideoConfig("1080p240_HS", "1080p240 HIGH_SPEED", 1920, 1080, 240, true),
        VideoConfig("4K120_HS", "4K120 HIGH_SPEED", 3840, 2160, 120, true),
    )

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("VideoValidator").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            LogUtil.e("Thread interrupted", e)
        }
    }

    fun runInitialDiscovery(): Map<String, ValidationResult> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraMap = cameraMapper.getCameraMap()
        val mainCamera = cameraMap[RearLens.MAIN]
        
        if (mainCamera == null) {
            LogUtil.e("MAIN camera not found for validation")
            return emptyMap()
        }

        val id = mainCamera.id
        val chars = cameraManager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        targetConfigs.forEach { config ->
            var isAdvertised = false
            if (config.isHighSpeed) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val hsSizes = map?.highSpeedVideoSizes ?: emptyArray()
                    if (hsSizes.any { it.width == config.width && it.height == config.height }) {
                        val ranges = map?.getHighSpeedVideoFpsRangesFor(Size(config.width, config.height))
                        if (ranges?.any { it.lower >= config.fps || it.upper >= config.fps } == true) {
                            isAdvertised = true
                        }
                    }
                }
            } else {
                val sizes = map?.getOutputSizes(ImageFormat.PRIVATE) ?: emptyArray()
                if (sizes.any { it.width == config.width && it.height == config.height }) {
                    val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    if (ranges?.any { it.upper >= config.fps } == true) {
                        isAdvertised = true
                    }
                }
            }
            results[config.key] = ValidationResult(advertised = isAdvertised)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            val hasHlg = profiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
            if (hasHlg) {
                results["HLG10"] = ValidationResult(advertised = true)
            }
        }

        writeReport()
        return results
    }

    @SuppressLint("MissingPermission")
    suspend fun validateSessions(): Map<String, ValidationResult> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraMap = cameraMapper.getCameraMap()
        val mainCamera = cameraMap[RearLens.MAIN] ?: return results
        val id = mainCamera.id

        startBackgroundThread()
        try {
            val configsToTest = mutableListOf<VideoConfig>()
            for (config in targetConfigs) {
                if (results[config.key]?.advertised == true) {
                    configsToTest.add(config)
                }
            }
            if (results["HLG10"]?.advertised == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    configsToTest.add(VideoConfig("HLG10", "HLG10", 1920, 1080, 30, false, DynamicRangeProfiles.HLG10))
                }
            }

            for (config in configsToTest) {
                val device = openCameraStandalone(cameraManager, id)
                if (device != null) {
                    validateConfig(device, config)
                    device.close()
                } else {
                    val oldRes = results[config.key] ?: ValidationResult()
                    results[config.key] = oldRes.copy(sessionCreatable = "NO", failureMessage = "Failed to open camera")
                    writeReport()
                }
            }
        } finally {
            stopBackgroundThread()
        }
        return results
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraStandalone(cameraManager: CameraManager, id: String): CameraDevice? {
        val cameraDeviceDeferred = CompletableDeferred<CameraDevice?>()
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { cameraDeviceDeferred.complete(camera) }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDeviceDeferred.complete(null) }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDeviceDeferred.complete(null) }
        }, backgroundHandler)

        return withTimeoutOrNull(3000L) { cameraDeviceDeferred.await() }
    }

    @SuppressLint("WrongConstant")
    private suspend fun validateConfig(cameraDevice: CameraDevice, config: VideoConfig) {
        val surfaceTexture = SurfaceTexture(10)
        surfaceTexture.setDefaultBufferSize(config.width, config.height)
        val surface = Surface(surfaceTexture)
        val sessionDeferred = CompletableDeferred<Boolean>()
        var failure: String? = null

        try {
            if (config.isHighSpeed) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("DEPRECATION")
                    cameraDevice.createConstrainedHighSpeedCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.close()
                                sessionDeferred.complete(true)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                sessionDeferred.complete(false)
                            }
                        },
                        backgroundHandler
                    )
                } else {
                    sessionDeferred.complete(false)
                    failure = "Constrained HS not supported"
                }
            } else if (config.dynamicRange != 1L) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val outputConfig = OutputConfiguration(surface)
                    outputConfig.setDynamicRangeProfile(config.dynamicRange)
                    val sessionConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(outputConfig),
                        context.mainExecutor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.close()
                                sessionDeferred.complete(true)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                sessionDeferred.complete(false)
                            }
                        }
                    )
                    cameraDevice.createCaptureSession(sessionConfig)
                } else {
                    sessionDeferred.complete(false)
                    failure = "10-bit not supported"
                }
            } else {
                @Suppress("DEPRECATION")
                cameraDevice.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.close()
                            sessionDeferred.complete(true)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            sessionDeferred.complete(false)
                        }
                    },
                    backgroundHandler
                )
            }

            val success = withTimeoutOrNull(5000L) { sessionDeferred.await() } ?: false
            val oldRes = results[config.key] ?: ValidationResult()
            results[config.key] = oldRes.copy(
                sessionCreatable = if (success) "YES" else "NO",
                failureMessage = failure ?: if (!success) "Configuration failed or timed out" else null
            )

        } catch (e: Exception) {
            val oldRes = results[config.key] ?: ValidationResult()
            results[config.key] = oldRes.copy(sessionCreatable = "NO", failureMessage = e.message)
        } finally {
            surface.release()
            surfaceTexture.release()
            writeReport()
        }
    }

    private fun writeReport() {
        val report = StringBuilder()
        report.append("=== ICAN VIDEO CONFIGURATION VALIDATION ===\n\n")
        report.append("MAIN / ID 2\n\n")

        val allConfigs = targetConfigs.toMutableList()
        if (results.containsKey("HLG10")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                allConfigs.add(VideoConfig("HLG10", "HLG10", 1920, 1080, 30, false, DynamicRangeProfiles.HLG10))
            }
        }

        allConfigs.forEach { config ->
            val res = results[config.key] ?: ValidationResult()
            report.append("${config.name}\n")
            report.append("Advertised: ${if (res.advertised) "YES" else "NO"}\n")
            report.append("Session Creatable: ${res.sessionCreatable}\n")
            if (res.failureMessage != null) {
                report.append("Failure: ${res.failureMessage}\n")
            }
            report.append("\n")
        }

        report.append("=== END ===\n")
        
        try {
            val file = File(context.cacheDir, "ican_video_configuration_validation.txt")
            file.writeText(report.toString())
            LogUtil.i("Validation report updated: ${file.absolutePath}")
        } catch (e: Exception) {
            LogUtil.e("Failed to write validation report", e)
        }
    }
}
