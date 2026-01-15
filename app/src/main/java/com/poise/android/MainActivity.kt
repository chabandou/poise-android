package com.poise.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.poise.android.service.AudioCaptureService
import com.poise.android.service.AudioServiceState
import com.poise.android.ui.MainScreen
import com.poise.android.ui.theme.PoiseAndroidTheme

class MainActivity : ComponentActivity() {

    private var pendingStart = false

    private val mediaProjectionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    startAudioCaptureService(result.resultCode, result.data!!)
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }

    private val recordAudioPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted && pendingStart) {
                    requestMediaProjection()
                } else if (!isGranted) {
                    Toast.makeText(this, "Audio permission required", Toast.LENGTH_SHORT).show()
                }
                pendingStart = false
            }

    private val notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                // Continue regardless of notification permission
                if (pendingStart) {
                    checkRecordAudioPermission()
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        setContent {
            PoiseAndroidTheme {
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                ) {
                    // Use shared state that persists across service lifecycle
                    val isRunning by AudioServiceState.isRunning.collectAsState()
                    val isStarting by AudioServiceState.isStarting.collectAsState()
                    val stats by AudioServiceState.stats.collectAsState()
                    val outputVolume by AudioServiceState.outputVolume.collectAsState()

                    MainScreen(
                            isProcessing = isRunning || isStarting,
                            stats = stats,
                            outputVolume = outputVolume,
                            onVolumeChange = { AudioServiceState.setOutputVolume(it) },
                            onToggleProcessing = { shouldStart ->
                                if (shouldStart) {
                                    startProcessing()
                                } else {
                                    stopProcessing()
                                }
                            }
                    )
                }
            }
        }
    }

    private fun startProcessing() {
        pendingStart = true
        AudioServiceState.setStarting(true) // Immediate UI feedback

        // Check notification permission first (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        checkRecordAudioPermission()
    }

    private fun checkRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun startAudioCaptureService(resultCode: Int, resultData: Intent) {
        val serviceIntent =
                Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_START
                    putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(AudioCaptureService.EXTRA_RESULT_DATA, resultData)
                }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopProcessing() {
        val serviceIntent =
                Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_STOP
                }
        startService(serviceIntent)
    }
}
