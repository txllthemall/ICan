package com.ican.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ican.camera.capabilities.CameraCapabilityProbe
import com.ican.camera.capabilities.PhysicalCameraMapper
import com.ican.camera.capabilities.StreamCapabilityProbe
import com.ican.camera.capabilities.VideoConfigurationValidator
import com.ican.camera.engine.CameraEngine
import com.ican.camera.ui.CameraScreen
import com.ican.camera.ui.theme.ICanTheme
import com.ican.camera.util.LogUtil

class MainActivity : ComponentActivity() {
    
    private lateinit var cameraEngine: CameraEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogUtil.i("APP_CAMERA_START")
        
        // 1. Device Identity & Basic Probe
        CameraCapabilityProbe(this).runProbe()
        
        // 2. Physical Lens Mapping
        PhysicalCameraMapper(this).mapCameras()
        
        // 3. Stream & FPS Capability Probe
        StreamCapabilityProbe(this).runProbe()
        
        // 4. Video Configuration Initial Discovery (Advertised only)
        VideoConfigurationValidator(this).runInitialDiscovery()
        
        cameraEngine = CameraEngine(this)
        
        enableEdgeToEdge()
        setContent {
            ICanTheme {
                val lifecycleOwner = LocalLifecycleOwner.current
                var hasCameraPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasCameraPermission = ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                var shouldShowRationale by remember {
                    mutableStateOf(
                        shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    hasCameraPermission = isGranted
                    shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                }

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ ->
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (hasCameraPermission) {
                            CameraScreen(
                                engine = cameraEngine,
                                onMicPermissionRequest = {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            )
                        } else {
                            PermissionScreen(
                                isPermanentlyDenied = !shouldShowRationale,
                                onPermissionRequest = {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                onOpenSettings = {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", packageName, null)
                                    }
                                    startActivity(intent)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraEngine.release()
    }
}

@Composable
fun PermissionScreen(
    isPermanentlyDenied: Boolean,
    onPermissionRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Camera permission is required to use this app.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (isPermanentlyDenied) {
                Text(
                    text = "Permission has been permanently denied. Please enable it in settings.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenSettings) {
                    Text("Open Settings")
                }
            } else {
                Button(onClick = onPermissionRequest) {
                    Text("Grant Permission")
                }
            }
        }
    }
}
