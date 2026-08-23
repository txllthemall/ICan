package com.ican.camera.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ican.camera.engine.BracketState
import com.ican.camera.engine.CameraEngine
import com.ican.camera.engine.CameraMode
import com.ican.camera.engine.CameraState
import com.ican.camera.engine.RecordingState
import com.ican.camera.manual.ExposureMode
import com.ican.camera.manual.FocusMode
import com.ican.camera.manual.ManualSensorState
import com.ican.camera.processing.ProcessingMode
import com.ican.camera.capabilities.RearLens
import com.ican.camera.util.LogUtil
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun CameraScreen(
    engine: CameraEngine,
    onMicPermissionRequest: () -> Unit
) {
    val state by engine.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var focusPoint by remember { mutableStateOf<IntOffset?>(null) }
    var zoomScale by remember { mutableFloatStateOf(1f) }

    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        zoomScale = (state.zoomRatio * zoomChange).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        engine.setZoom(zoomScale)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .transformable(state = transformableState)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    focusPoint = IntOffset(offset.x.roundToInt(), offset.y.roundToInt())
                }
            }
    ) {
        var previewView by remember { mutableStateOf<androidx.camera.view.PreviewView?>(null) }

        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onPreviewViewCreated = {
                previewView = it
                engine.bindToLifecycle(lifecycleOwner, it)
            }
        )

        // Focus Indicator
        focusPoint?.let { point ->
            LaunchedEffect(point) {
                engine.focus(
                    point.x.toFloat(),
                    point.y.toFloat(),
                    previewView?.meteringPointFactory ?: return@LaunchedEffect
                )
                delay(2000)
                focusPoint = null
            }
            Box(
                modifier = Modifier
                    .offset { point - IntOffset(40.dp.roundToPx(), 40.dp.roundToPx()) }
                    .size(80.dp)
                    .border(1.dp, Color.Yellow)
            )
        }

        // Top UI: Flash/Torch and Quality
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.mode == CameraMode.PHOTO && state.hasFlash) {
                FlashControl(
                    currentMode = state.flashMode,
                    onModeChange = { engine.setFlashMode(it) }
                )
            } else if (state.mode == CameraMode.VIDEO && state.hasTorch) {
                TorchControl(
                    isOn = state.isTorchOn,
                    onToggle = { engine.toggleTorch() }
                )
            }
            
            // Developer Processing Mode Toggle
            if (state.mode == CameraMode.PHOTO && state.recordingState == RecordingState.IDLE) {
                ProcessingModeToggle(
                    currentMode = state.processingMode,
                    onModeChange = { engine.setProcessingMode(it) }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                RearLensSelector(
                    currentLens = state.selectedRearLens,
                    onLensChange = { engine.setRearLens(it) }
                )

                if (state.isRawJpegSupported) {
                    Spacer(modifier = Modifier.height(8.dp))
                    RawCaptureToggle(
                        enabled = state.isRawCaptureEnabled,
                        onToggle = { engine.setRawCaptureEnabled(it) }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                ManualControlsToggle(
                    visible = state.isManualControlsVisible,
                    onToggle = { engine.toggleManualControls() }
                )

                if (state.selectedRearLens == RearLens.MAIN && 
                    state.bracketState == BracketState.IDLE && 
                    !state.isRawCaptureEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { engine.runBracket() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text("BRACKET TEST", fontSize = 10.sp)
                    }
                } else if (state.bracketState != BracketState.IDLE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "BRACKET: ${state.bracketState}",
                        color = Color.Yellow,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Middle UI: Manual Controls Panel
        if (state.mode == CameraMode.PHOTO && state.isManualControlsVisible) {
            ManualControlsPanel(
                state = state.manualSensorState,
                limits = state.manualLimits,
                onStateChange = { engine.setManualSensorState(it) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            )
        }

        // Middle UI: Recording Timer
        if (state.mode == CameraMode.VIDEO && state.recordingState == RecordingState.RECORDING) {
            RecordingTimer(
                durationMillis = state.recordingDurationMillis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 120.dp)
            )
        }

        // Bottom UI: Controls and Mode Switcher
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ModeSwitcher(
                currentMode = state.mode,
                onModeChange = { 
                    engine.setCameraMode(it)
                    if (it == CameraMode.VIDEO) {
                        onMicPermissionRequest()
                    }
                },
                enabled = state.recordingState == RecordingState.IDLE
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            CameraControls(
                state = state,
                onCapture = { 
                    if (state.mode == CameraMode.PHOTO) {
                        engine.takePhoto()
                    } else {
                        if (state.recordingState == RecordingState.IDLE) {
                            engine.startRecording()
                        } else if (state.recordingState == RecordingState.RECORDING) {
                            engine.stopRecording()
                        }
                    }
                },
                onSwitchCamera = { engine.switchCamera() },
                onThumbnailClick = {
                    state.lastThumbnailUri?.let { uri ->
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, state.lastThumbnailMimeType ?: "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            LogUtil.e("No activity found to open media", e)
                        }
                    }
                }
            )
        }

        // Re-bind when lens, quality, rear lens, raw, or lifecycle changes
        var currentLensFacing by remember { mutableIntStateOf(state.lensFacing) }
        var currentQuality by remember { mutableStateOf(state.selectedQuality) }
        var currentRearLens by remember { mutableStateOf(state.selectedRearLens) }
        var currentRawEnabled by remember { mutableStateOf(state.isRawCaptureEnabled) }
        
        LaunchedEffect(state.lensFacing, state.selectedQuality, state.selectedRearLens, state.isRawCaptureEnabled, lifecycleOwner) {
            val facingChanged = currentLensFacing != state.lensFacing
            val qualityChanged = currentQuality != state.selectedQuality
            val lensChanged = currentRearLens != state.selectedRearLens
            val rawChanged = currentRawEnabled != state.isRawCaptureEnabled
            
            if (facingChanged || qualityChanged || lensChanged || rawChanged) {
                currentLensFacing = state.lensFacing
                currentQuality = state.selectedQuality
                currentRearLens = state.selectedRearLens
                currentRawEnabled = state.isRawCaptureEnabled
                
                previewView?.let { engine.bindToLifecycle(lifecycleOwner, it) }
            }
        }
    }
}

@Composable
fun ManualControlsToggle(visible: Boolean, onToggle: () -> Unit) {
    Button(
        onClick = onToggle,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (visible) Color.Cyan else Color.Gray.copy(alpha = 0.5f),
            contentColor = if (visible) Color.Black else Color.White
        ),
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    ) {
        Text("PRO CORE", fontSize = 10.sp)
    }
}

@Composable
fun ManualControlsPanel(
    state: ManualSensorState,
    limits: com.ican.camera.manual.ManualLimits,
    onStateChange: (ManualSensorState) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
        modifier = modifier.width(180.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Exposure Mode
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("EXP", color = Color.White, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(ExposureMode.AUTO, ExposureMode.MANUAL).forEach { mode ->
                        Text(
                            text = mode.name,
                            color = if (state.exposureMode == mode) Color.Yellow else Color.White,
                            modifier = Modifier.clickable { onStateChange(state.copy(exposureMode = mode)) },
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (state.exposureMode == ExposureMode.MANUAL) {
                // ISO Presets
                Column {
                    Text("ISO", color = Color.Gray, fontSize = 9.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100, 400, 1600, 3200).forEach { iso ->
                            val valid = iso in limits.isoRange
                            Text(
                                text = iso.toString(),
                                color = if (state.iso == iso) Color.Yellow else if (valid) Color.White else Color.DarkGray,
                                modifier = Modifier.clickable(enabled = valid) { onStateChange(state.copy(iso = iso)) },
                                fontSize = 10.sp
                            )
                        }
                    }
                }
                // Shutter Presets
                Column {
                    Text("SHUTTER", color = Color.Gray, fontSize = 9.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        mapOf("1/1000" to 1000000L, "1/100" to 10000000L, "1/10" to 100000000L, "1s" to 1000000000L).forEach { (label, ns) ->
                            val valid = ns in limits.exposureTimeRange
                            Text(
                                text = label,
                                color = if (state.exposureTimeNs == ns) Color.Yellow else if (valid) Color.White else Color.DarkGray,
                                modifier = Modifier.clickable(enabled = valid) { onStateChange(state.copy(exposureTimeNs = ns)) },
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            } else {
                // EV Comp
                Column {
                    Text("EV", color = Color.Gray, fontSize = 9.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(-2f, 0f, 2f).forEach { ev ->
                            Text(
                                text = if (ev > 0) "+$ev" else ev.toString(),
                                color = if (state.exposureCompensationEv == ev) Color.Yellow else Color.White,
                                modifier = Modifier.clickable { onStateChange(state.copy(exposureCompensationEv = ev)) },
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // Focus Mode
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("FOCUS", color = Color.White, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(FocusMode.AUTO, FocusMode.MANUAL).forEach { mode ->
                        Text(
                            text = mode.name,
                            color = if (state.focusMode == mode) Color.Yellow else Color.White,
                            modifier = Modifier.clickable { onStateChange(state.copy(focusMode = mode)) },
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (state.focusMode == FocusMode.MANUAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mapOf("INF" to 0f, "MID" to limits.minFocusDistance/2f, "NEAR" to limits.minFocusDistance).forEach { (label, d) ->
                        Text(
                            text = label,
                            color = if (state.focusDistanceDiopters == d) Color.Yellow else Color.White,
                            modifier = Modifier.clickable { onStateChange(state.copy(focusDistanceDiopters = d)) },
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Locks
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "AE L",
                    color = if (state.aeLocked) Color.Red else Color.White,
                    modifier = Modifier.clickable { onStateChange(state.copy(aeLocked = !state.aeLocked)) },
                    fontSize = 10.sp
                )
                Text(
                    "AWB L",
                    color = if (state.awbLocked) Color.Red else Color.White,
                    modifier = Modifier.clickable { onStateChange(state.copy(awbLocked = !state.awbLocked)) },
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun RawCaptureToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Button(
        onClick = { onToggle(!enabled) },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) Color.Yellow else Color.Gray.copy(alpha = 0.5f),
            contentColor = if (enabled) Color.Black else Color.White
        ),
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    ) {
        Text("RAW+JPEG TEST", fontSize = 10.sp)
    }
}

@Composable
fun ModeSwitcher(
    currentMode: CameraMode,
    onModeChange: (CameraMode) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CameraMode.entries.forEach { mode ->
            val isSelected = currentMode == mode
            Text(
                text = mode.name,
                color = if (isSelected) Color.Yellow else Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable(enabled = enabled) { onModeChange(mode) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 14.sp,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
fun RecordingTimer(durationMillis: Long, modifier: Modifier = Modifier) {
    val seconds = (durationMillis / 1000) % 60
    val minutes = (durationMillis / 60000) % 60
    val hours = durationMillis / 3600000
    
    val timeText = if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Red.copy(alpha = 0.8f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = timeText,
            color = Color.White,
            fontSize = 16.sp,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun CameraControls(
    state: CameraState,
    onCapture: () -> Unit,
    onSwitchCamera: () -> Unit,
    onThumbnailClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.DarkGray)
                .border(1.dp, Color.White, CircleShape)
                .clickable(enabled = state.lastThumbnailUri != null) { onThumbnailClick() }
        ) {
            AsyncImage(
                model = state.lastThumbnailUri,
                contentDescription = "Last captured media",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Shutter / Record Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .clickable(
                    enabled = state.isReady && state.recordingState != RecordingState.STARTING && state.recordingState != RecordingState.STOPPING,
                    onClick = onCapture
                )
        ) {
            val color = if (state.mode == CameraMode.PHOTO) Color.White else Color.Red
            val innerSize = if (state.recordingState == RecordingState.RECORDING) 32.dp else 72.dp
            val shape = if (state.recordingState == RecordingState.RECORDING) MaterialTheme.shapes.small else CircleShape
            
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
            )
            
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .clip(shape)
                    .background(color)
            )
        }

        // Switch Camera
        IconButton(
            onClick = onSwitchCamera,
            modifier = Modifier.size(64.dp),
            enabled = state.recordingState == RecordingState.IDLE
        ) {
            Icon(
                Icons.Default.FlipCameraAndroid,
                contentDescription = "Switch Camera",
                tint = Color.White
            )
        }
    }
}

@Composable
fun FlashControl(
    currentMode: Int,
    onModeChange: (Int) -> Unit
) {
    val modes = listOf<Pair<Int, ImageVector>>(
        ImageCapture.FLASH_MODE_OFF to Icons.Default.FlashOff,
        ImageCapture.FLASH_MODE_ON to Icons.Default.FlashOn,
        ImageCapture.FLASH_MODE_AUTO to Icons.Default.FlashAuto
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(4.dp)
    ) {
        modes.forEach { (mode, icon) ->
            IconButton(
                onClick = { onModeChange(mode) },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (currentMode == mode) Color.Yellow else Color.White
                )
            ) {
                Icon(icon, contentDescription = null)
            }
        }
    }
}

@Composable
fun TorchControl(isOn: Boolean, onToggle: () -> Unit) {
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (isOn) Color.Yellow else Color.White
        )
    ) {
        Icon(if (isOn) Icons.Default.FlashOn else Icons.Default.FlashOff, contentDescription = "Toggle Torch")
    }
}

@Composable
fun QualitySelector(
    qualities: List<androidx.camera.video.Quality>,
    selected: androidx.camera.video.Quality?,
    onQualityChange: (androidx.camera.video.Quality) -> Unit
) {
    if (qualities.isEmpty()) return
    
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        qualities.forEach { quality ->
            val isSelected = quality == selected
            val label = when(quality) {
                androidx.camera.video.Quality.SD -> "SD"
                androidx.camera.video.Quality.HD -> "HD"
                androidx.camera.video.Quality.FHD -> "FHD"
                androidx.camera.video.Quality.UHD -> "4K"
                else -> "QUAL"
            }
            Text(
                text = label, 
                color = if (isSelected) Color.Yellow else Color.White,
                modifier = Modifier.clickable { onQualityChange(quality) },
                fontSize = 12.sp,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun ProcessingModeToggle(
    currentMode: ProcessingMode,
    onModeChange: (ProcessingMode) -> Unit
) {
    Row(
        modifier = Modifier
            .padding(top = 8.dp)
            .clip(CircleShape)
            .background(Color.DarkGray.copy(alpha = 0.5f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ProcessingMode.entries.forEach { mode ->
            val isSelected = currentMode == mode
            Text(
                text = mode.name.replace("ICAN_", ""),
                color = if (isSelected) Color.Green else Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onModeChange(mode) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 10.sp,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun RearLensSelector(
    currentLens: RearLens,
    onLensChange: (RearLens) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RearLens.entries.forEach { lens ->
            val isSelected = currentLens == lens
            val label = when(lens) {
                RearLens.ULTRAWIDE -> "UW"
                RearLens.MAIN -> "MAIN"
                RearLens.TELEPHOTO -> "TELE"
            }
            Text(
                text = label,
                color = if (isSelected) Color.Yellow else Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onLensChange(lens) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 12.sp,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
