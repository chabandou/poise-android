package com.poise.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.poise.android.service.AudioCaptureService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import com.poise.android.service.AudioServiceState
import com.poise.android.service.PoiseSettings
import com.poise.android.service.PoiseTileService
import com.poise.android.service.UpdateCheckService
import com.poise.android.ui.MainScreen
import com.poise.android.ui.SystemStatus
import com.poise.android.ui.theme.Palettes
import com.poise.android.ui.theme.PoiseAndroidTheme

@OptIn(FlowPreview::class)
class MainActivity : ComponentActivity() {

    companion object {
        // Sent by the Quick Settings tile to start a session
        const val ACTION_START = "com.poise.android.START_FROM_TILE"

        // The window theme for each palette, in Palettes order (res/values/themes.xml)
        private val WINDOW_THEMES =
                intArrayOf(
                        R.style.Theme_PoiseAndroid,
                        R.style.Theme_PoiseAndroid_Verdant,
                        R.style.Theme_PoiseAndroid_Vigil,
                        R.style.Theme_PoiseAndroid_Ember,
                        R.style.Theme_PoiseAndroid_Eclipse,
                        R.style.Theme_PoiseAndroid_Orchid
                )
    }

    private var pendingStart = false
    private var systemStatus by mutableStateOf(SystemStatus(false, false))

    private val mediaProjectionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    startAudioCaptureService(result.resultCode, result.data!!)
                } else {
                    AudioServiceState.setActive(false)
                    Toast.makeText(this, getString(R.string.toast_permission_denied), Toast.LENGTH_SHORT).show()
                }
            }

    private val recordAudioPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted && pendingStart) {
                    requestMediaProjection()
                } else if (!isGranted) {
                    AudioServiceState.setActive(false)
                    Toast.makeText(this, getString(R.string.toast_audio_required), Toast.LENGTH_SHORT).show()
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
        PoiseSettings.init(this)
        setTheme(WINDOW_THEMES.getOrElse(PoiseSettings.theme.value) { WINDOW_THEMES[0] })
        enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        UpdateCheckService.schedule(this, PoiseSettings.updateChecks.value)
        // Repairs a media mute left by a crash. Skipped while a session runs, which owns the mute.
        if (!AudioServiceState.isActive.value) {
            AudioCaptureService.restoreSystemAudio(this)
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: ""

        setContent {
            val theme by PoiseSettings.theme.collectAsState()
            val palette = Palettes.getOrElse(theme) { Palettes[0] }
            LaunchedEffect(theme) {
                window.setBackgroundDrawable(ColorDrawable(palette.bg.toArgb()))
                // Android 13+ remembers it for the launch screen, so the next start opens in this theme
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    splashScreen.setSplashScreenTheme(WINDOW_THEMES.getOrElse(theme) { WINDOW_THEMES[0] })
                }
            }
            PoiseAndroidTheme(palette) {
                // Use shared state that persists across service lifecycle
                val isActive by AudioServiceState.isActive.collectAsState()
                // Read twice a second: each change redraws the window, and the numbers can't be read faster
                val stats by remember { AudioServiceState.stats.sample(500) }.collectAsState(null)
                val outputVolume by AudioServiceState.outputVolume.collectAsState()

                MainScreen(
                        isProcessing = isActive,
                        stats = stats,
                        outputVolume = outputVolume,
                        onVolumeChange = { AudioServiceState.setOutputVolume(it) },
                        onToggleProcessing = { shouldStart ->
                            if (shouldStart) {
                                startProcessing()
                            } else {
                                stopProcessing()
                            }
                        },
                        system = systemStatus,
                        onAllowBattery = ::requestBatteryExemption,
                        onOpenNotificationSettings = {
                            startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            )
                        },
                        onAddTile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ::requestAddTile else null,
                        versionName = versionName,
                        onOpenUrl = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                )
            }
        }

        // Not on recreation (e.g. rotation), which delivers the same intent again
        if (savedInstanceState == null) startIfAsked(intent)
    }

    override fun onResume() {
        super.onResume()
        // Either can change in system settings while Poise is in the background
        systemStatus =
                SystemStatus(
                        batteryUnrestricted =
                                getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
                        notificationsAllowed = NotificationManagerCompat.from(this).areNotificationsEnabled()
                )
    }

    // The direct request: Poise is a long-running audio app, which Android's policy allows
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    @SuppressLint("NewApi") // only offered on Android 13+
    private fun requestAddTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, PoiseTileService::class.java),
                getString(R.string.app_name),
                Icon.createWithResource(this, R.drawable.ic_wave),
                mainExecutor
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                Toast.makeText(this, getString(R.string.toast_tile_present), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startIfAsked(intent)
    }

    private fun startIfAsked(intent: Intent?) {
        if (intent?.action == ACTION_START && !AudioServiceState.isActive.value) startProcessing()
    }

    private fun startProcessing() {
        pendingStart = true
        AudioServiceState.setActive(true) // Immediate UI feedback

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
