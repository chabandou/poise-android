package com.poise.android.audio

import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.util.Log
import com.poise.android.service.AudioServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio pipeline that orchestrates:
 * 1. Capturing system audio via AudioPlaybackCapture
 * 2. Denoising through GTCRN
 * 3. Playing back enhanced audio via AudioTrack
 */
class AudioPipeline(private val context: Context) {
    companion object {
        private const val TAG = "AudioPipeline"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        // GTCRN runs at 16kHz; Android resamples capture and playback for us (with proper filtering)
        private const val SAMPLE_RATE = GTCRNProcessor.SAMPLE_RATE
        private const val FRAME_SIZE = GTCRNProcessor.FRAME_SIZE // 16ms at 16kHz

        // At least 40 ms of float samples
        private const val MIN_BUFFER_BYTES = SAMPLE_RATE / 25 * Float.SIZE_BYTES
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var gtcrnProcessor: GTCRNProcessor? = null
    private var processingJob: Job? = null
    private var mediaProjection: MediaProjection? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Start audio capture and processing on a background job. Call [start] and [stop] from the main
     * thread. The job owns every audio resource and releases them itself when it ends, so [stop]
     * never frees something the loop is still using.
     *
     * @param projection MediaProjection obtained from user permission
     * @return completes once audio is flowing, or fails with the setup error
     */
    fun start(projection: MediaProjection): Deferred<Unit> {
        val started = CompletableDeferred<Unit>()
        if (processingJob != null) {
            Log.w(TAG, "Pipeline already running")
            started.complete(Unit)
            return started
        }

        mediaProjection = projection

        processingJob =
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        gtcrnProcessor = GTCRNProcessor(context)

                        // Setup audio capture
                        setupAudioCapture(projection)

                        // Setup audio output
                        setupAudioOutput()

                        _isRunning.value = true
                        started.complete(Unit)
                        Log.i(TAG, "Audio pipeline started")

                        processAudioLoop()
                    } catch (e: Exception) {
                        if (!started.isCompleted) Log.e(TAG, "Failed to start pipeline: ${e.message}", e)
                        started.completeExceptionally(e)
                    } finally {
                        release()
                    }
                }
        return started
    }

    private fun setupAudioCapture(projection: MediaProjection) {
        // Configure which audio to capture
        val captureConfig =
                AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

        // Configure audio format
        val audioFormat =
                AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()

        // Calculate buffer size
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, MIN_BUFFER_BYTES)

        // Create AudioRecord with playback capture
        audioRecord =
                AudioRecord.Builder()
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }

        audioRecord?.startRecording()
        Log.i(TAG, "AudioRecord started: $SAMPLE_RATE Hz, buffer=$bufferSize bytes")
    }

    private fun setupAudioOutput() {
        // Use STREAM_VOICE_CALL (via USAGE_VOICE_COMMUNICATION) which routes to
        // headphones/Bluetooth
        // This allows us to mute STREAM_MUSIC while our processed output still plays
        val audioAttributes =
                AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

        val audioFormat =
                AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()

        val minBufferSize =
                AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, MIN_BUFFER_BYTES)

        audioTrack =
                AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build()

        audioTrack?.play()
        Log.i(TAG, "AudioTrack started: $SAMPLE_RATE Hz, low-latency mode")
    }

    private suspend fun processAudioLoop() {
        // Capture already runs at the model's rate, so one read is one model frame
        val readSize = FRAME_SIZE
        val inputBuffer = FloatArray(readSize)
        var statsUpdateCounter = 0

        while (_isRunning.value && currentCoroutineContext().isActive) {
            try {
                // Read from capture
                val readResult =
                        audioRecord?.read(inputBuffer, 0, readSize, AudioRecord.READ_BLOCKING)
                                ?: continue

                if (readResult < 0) {
                    // Errors here do not clear (e.g. dead capture); retrying would spin the CPU
                    Log.e(TAG, "AudioRecord read error: $readResult, stopping")
                    break
                }

                if (readResult < readSize) {
                    continue // Not enough samples
                }

                val outputAudio = gtcrnProcessor?.processFrame(inputBuffer)

                if (outputAudio != null && outputAudio.isNotEmpty()) {
                    // Apply output volume from UI slider
                    val volume = AudioServiceState.outputVolume.value
                    for (i in outputAudio.indices) {
                        outputAudio[i] = (outputAudio[i] * volume).coerceIn(-1f, 1f)
                    }

                    audioTrack?.write(outputAudio, 0, outputAudio.size, AudioTrack.WRITE_BLOCKING)
                }

                // Update stats
                statsUpdateCounter++
                if (statsUpdateCounter >= 10) {
                    statsUpdateCounter = 0
                    gtcrnProcessor?.getStats()?.let { AudioServiceState.updateStats(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Processing error: ${e.message}")
            }
        }
    }

    /** Stop audio capture and processing. The background job releases its resources as it ends. */
    fun stop() {
        _isRunning.value = false

        processingJob?.cancel()
        processingJob = null

        // Ends the capture, so a blocking read in the loop returns promptly.
        mediaProjection?.stop()
        mediaProjection = null
    }

    /** Runs on the processing job once it ends. release() also stops record and track. */
    private fun release() {
        _isRunning.value = false

        audioRecord?.release()
        audioRecord = null

        audioTrack?.release()
        audioTrack = null

        gtcrnProcessor?.close()
        gtcrnProcessor = null

        Log.i(TAG, "Audio pipeline stopped")
    }
}
