package com.poise.android.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.poise.android.MainActivity
import com.poise.android.R
import com.poise.android.audio.AudioPipeline
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/**
 * Foreground service for audio capture and processing. Required for MediaProjection to keep running
 * in background.
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "poise_audio_capture"

        const val ACTION_START = "com.poise.android.START_CAPTURE"
        const val ACTION_STOP = "com.poise.android.STOP_CAPTURE"
        const val ACTION_VOLUME_UP = "com.poise.android.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.poise.android.VOLUME_DOWN"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val VOLUME_STEP = 0.1f // 10% per tap
        private const val PREFS = "poise"
        private const val KEY_MUSIC_MUTED = "music_muted_by_poise"

        // System broadcasts for media volume and mute changes; the names are hidden API constants
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_SHOW_UI = "android.media.EXTRA_VOLUME_SHOW_UI"
        private const val EXTRA_STREAM_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED"

        /**
         * Unmutes media if Poise muted it. The flag is on disk, so this also repairs a mute left
         * behind when the process was killed before it could stop cleanly.
         */
        fun restoreSystemAudio(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_MUSIC_MUTED, false)) return
            context.getSystemService(AudioManager::class.java)
                    .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            prefs.edit().putBoolean(KEY_MUSIC_MUTED, false).commit()
            Log.i(TAG, "STREAM_MUSIC unmuted")
        }
    }

    private var audioPipeline: AudioPipeline? = null
    private var mediaProjection: MediaProjection? = null
    private var audioManager: AudioManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var notificationJob: Job? = null

    // Volume keys still act on media during a session, and volume up unmutes it, which brings the
    // original audio back under ours. So keep media muted and turn each press into a step of
    // Poise's own volume. While media is muted Android reports its new level as 0, so the
    // direction comes from the mute instead: volume up unmutes first, volume down leaves it muted.
    private var watchingMediaVolume = false
    private var lastRaiseMs = 0L
    private val mediaVolumeReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                    when (intent.action) {
                        ACTION_STREAM_MUTE_CHANGED -> {
                            if (intent.getBooleanExtra(EXTRA_STREAM_MUTED, true)) return
                            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                            stepOutputVolume(+1)
                            lastRaiseMs = SystemClock.uptimeMillis()
                            Log.i(TAG, "Volume up: media muted again")
                        }
                        // A key press shows the volume panel; the headphones' own echoes of it do not
                        ACTION_VOLUME_CHANGED -> {
                            if (!intent.getBooleanExtra(EXTRA_SHOW_UI, false)) return
                            // Volume up was already counted by its unmute just before
                            if (SystemClock.uptimeMillis() - lastRaiseMs > 500) stepOutputVolume(-1)
                            lastRaiseMs = 0
                        }
                    }
                }
            }

    // Unplugging headphones or a speaker would move our output to the phone's speaker. Not when
    // the user picked the phone's speaker: then our output wasn't on what went away
    private val unplugReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!PoiseSettings.stopOnUnplug.value || audioPipeline == null) return
                    if (PoiseSettings.output.value?.key?.startsWith("${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}:") == true) return
                    Log.i(TAG, "Output disconnected, stopping")
                    AudioServiceState.noteStop("Output disconnected")
                    stopCapture()
                }
            }
    private var watchingUnplug = false

    private fun stepOutputVolume(steps: Int) {
        AudioServiceState.setOutputVolume(AudioServiceState.outputVolume.value + steps * VOLUME_STEP)
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        PoiseSettings.init(this)
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                // Use type-safe API for Android 13+
                val resultData =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                        }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    try {
                        startCapture(resultCode, resultData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start capture: ${e.message}", e)
                        stopSelf()
                    }
                } else {
                    Log.e(
                            TAG,
                            "Invalid MediaProjection result: resultCode=$resultCode, resultData=$resultData"
                    )
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
            }
            ACTION_VOLUME_UP -> stepOutputVolume(+1)
            ACTION_VOLUME_DOWN -> stepOutputVolume(-1)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        // Start as foreground service
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Keep the notification's volume in step with the in-app slider and the +/- buttons.
        // collectLatest + delay: a slider drag posts only its final value.
        notificationJob =
                serviceScope.launch(Dispatchers.Main) {
                    AudioServiceState.outputVolume.drop(1).collectLatest {
                        delay(250)
                        updateNotification()
                    }
                }

        // Get MediaProjection
        val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            stopSelf()
            return
        }

        // The user can end casting from the system UI; clean up as if Stop was tapped.
        // Our own stop() also triggers this, by which point audioPipeline is already null.
        mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (audioPipeline != null) {
                            Log.i(TAG, "Projection stopped by the system")
                            AudioServiceState.noteStop("Sharing ended")
                            stopCapture()
                        }
                    }
                },
                Handler(Looper.getMainLooper())
        )

        ContextCompat.registerReceiver(
                this,
                unplugReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
        )
        watchingUnplug = true

        // Mute STREAM_MUSIC - our output on the accessibility stream still plays
        muteSystemAudio()

        // Start audio pipeline
        val pipeline = AudioPipeline(this)
        audioPipeline = pipeline
        val started = pipeline.start(mediaProjection!!)
        serviceScope.launch(Dispatchers.Main) {
            try {
                started.await()
                // Stop may have been tapped while starting; then this pipeline is already gone.
                if (audioPipeline !== pipeline) return@launch
                Log.i(TAG, "Audio capture started")

                // The loop can also end by itself (e.g. capture died); then stop the service too.
                pipeline.isRunning.first { !it }
                if (audioPipeline === pipeline) {
                    Log.w(TAG, "Pipeline ended on its own")
                    AudioServiceState.noteStop("Capture ended")
                    stopCapture()
                }
            } catch (e: CancellationException) {
                throw e // service destroyed; onDestroy already cleaned up
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio pipeline: ${e.message}")
                AudioServiceState.reset() // Reset state on failure
                stopCapture()
            }
        }
    }

    private fun stopCapture() {
        // Reset shared state
        AudioServiceState.reset()

        // Restore system volume, after we stop re-muting it
        if (watchingMediaVolume) {
            unregisterReceiver(mediaVolumeReceiver)
            watchingMediaVolume = false
        }
        restoreSystemAudio(this)
        if (watchingUnplug) {
            unregisterReceiver(unplugReceiver)
            watchingUnplug = false
        }

        // Stop pipeline
        audioPipeline?.stop()
        audioPipeline = null

        // Stop projection
        mediaProjection?.stop()
        mediaProjection = null

        // Stop updating the notification before it is removed
        notificationJob?.cancel()
        notificationJob = null

        // Stop service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "Audio capture stopped")
    }

    private fun muteSystemAudio() {
        val am = audioManager ?: return
        // Mute, not volume 0: a mute covers every output (speaker, headphones, Bluetooth) and keeps
        // each output's own volume, so switching output mid-session cannot bring the original back.
        // Media the user muted themselves stays theirs: we neither keep it muted nor unmute it.
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
            // Written before muting, so a crash in between still gets repaired
            prefs.edit().putBoolean(KEY_MUSIC_MUTED, true).commit()
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            Log.i(TAG, "STREAM_MUSIC muted")
        }
        if (prefs.getBoolean(KEY_MUSIC_MUTED, false) && !watchingMediaVolume) {
            val filter = IntentFilter(ACTION_VOLUME_CHANGED).apply { addAction(ACTION_STREAM_MUTE_CHANGED) }
            ContextCompat.registerReceiver(this, mediaVolumeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            watchingMediaVolume = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                                    CHANNEL_ID,
                                    "Audio Processing",
                                    NotificationManager.IMPORTANCE_LOW
                            )
                            .apply {
                                description = "Shows when Poise is processing audio"
                                setShowBadge(false)
                            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                )

        val stopIntent =
                PendingIntent.getService(
                        this,
                        1,
                        Intent(this, AudioCaptureService::class.java).apply {
                            action = ACTION_STOP
                        },
                        PendingIntent.FLAG_IMMUTABLE
                )

        val volumeUpIntent =
                PendingIntent.getService(
                        this,
                        2,
                        Intent(this, AudioCaptureService::class.java).apply {
                            action = ACTION_VOLUME_UP
                        },
                        PendingIntent.FLAG_IMMUTABLE
                )

        val volumeDownIntent =
                PendingIntent.getService(
                        this,
                        3,
                        Intent(this, AudioCaptureService::class.java).apply {
                            action = ACTION_VOLUME_DOWN
                        },
                        PendingIntent.FLAG_IMMUTABLE
                )

        val volumePercent = Math.round(AudioServiceState.outputVolume.value * 100)

        // A draggable slider isn't allowed in notifications, so the bar shows the volume and
        // the buttons change it.
        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Isolating voices")
                .setContentText("Volume $volumePercent%")
                .setProgress(100, volumePercent, false)
                .setSmallIcon(R.drawable.ic_wave)
                .setColor(ContextCompat.getColor(this, R.color.poise_cyan))
                .setColorized(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_volume_down, "Volume −", volumeDownIntent)
                .addAction(R.drawable.ic_volume_up, "Volume +", volumeUpIntent)
                .addAction(R.drawable.ic_stop, "Stop", stopIntent)
                .setOngoing(true)
                .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }
}
