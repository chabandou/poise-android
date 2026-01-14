package com.poise.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.poise.android.MainActivity
import com.poise.android.R
import com.poise.android.audio.AudioPipeline
import com.poise.android.audio.ProcessingStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

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
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private var instance: AudioCaptureService? = null

        fun getStats(): StateFlow<ProcessingStats?>? = instance?.audioPipeline?.stats
        fun getIsRunning(): StateFlow<Boolean>? = instance?.audioPipeline?.isRunning
        fun getRtf(): StateFlow<Float>? = instance?.audioPipeline?.latestRtf
    }

    private var audioPipeline: AudioPipeline? = null
    private var mediaProjection: MediaProjection? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var originalMusicVolume: Int = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

        // Get MediaProjection
        val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            stopSelf()
            return
        }

        // Request audio focus to make other apps duck/pause
        requestAudioFocus()

        // Mute STREAM_MUSIC - our output on STREAM_RING will still play
        muteSystemAudio()

        // Start audio pipeline
        audioPipeline = AudioPipeline(this)
        serviceScope.launch {
            try {
                audioPipeline?.start(mediaProjection!!)
                AudioServiceState.setRunning(true) // Update shared state
                Log.i(TAG, "Audio capture started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio pipeline: ${e.message}")
                AudioServiceState.reset() // Reset state on failure
                withContext(Dispatchers.Main) { stopCapture() }
            }
        }
    }

    private fun stopCapture() {
        // Reset shared state
        AudioServiceState.reset()

        // Restore system volume
        restoreSystemAudio()

        // Abandon audio focus
        abandonAudioFocus()

        // Stop pipeline
        audioPipeline?.stop()
        audioPipeline = null

        // Stop projection
        mediaProjection?.stop()
        mediaProjection = null

        // Stop service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "Audio capture stopped")
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes =
                    AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()

            audioFocusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                            .setAudioAttributes(audioAttributes)
                            .setAcceptsDelayedFocusGain(false)
                            .setOnAudioFocusChangeListener { focusChange ->
                                // Just log focus changes, don't stop - we want to keep processing
                                when (focusChange) {
                                    AudioManager.AUDIOFOCUS_LOSS -> {
                                        Log.w(TAG, "Audio focus lost (ignoring)")
                                    }
                                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                        Log.w(TAG, "Audio focus lost transient (ignoring)")
                                    }
                                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                        Log.w(TAG, "Audio focus duck request (ignoring)")
                                    }
                                }
                            }
                            .build()

            val result = audioManager?.requestAudioFocus(audioFocusRequest!!)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "Audio focus granted (exclusive)")
            } else {
                Log.w(TAG, "Audio focus request failed: $result")
            }
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager?.abandonAudioFocusRequest(it)
                Log.i(TAG, "Audio focus abandoned")
            }
            audioFocusRequest = null
        }
    }

    private fun muteSystemAudio() {
        audioManager?.let { am ->
            originalMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            Log.i(TAG, "STREAM_MUSIC muted (was: $originalMusicVolume)")
        }
    }

    private fun restoreSystemAudio() {
        audioManager?.let { am ->
            am.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
            Log.i(TAG, "STREAM_MUSIC restored to: $originalMusicVolume")
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
                        0,
                        Intent(this, AudioCaptureService::class.java).apply {
                            action = ACTION_STOP
                        },
                        PendingIntent.FLAG_IMMUTABLE
                )

        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Poise Voice Isolator")
                .setContentText("Processing audio...")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_stop, "Stop", stopIntent)
                .setOngoing(true)
                .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }
}
