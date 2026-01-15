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

/** Model selection for audio processing. */
enum class ProcessorModel {
    GTCRN, // Lightweight (0.34MB), faster, spectrogram-based
    LEGACY // DeepFilterNet (~10MB), slower, waveform-based
}

/**
 * Audio pipeline that orchestrates:
 * 1. Capturing system audio via AudioPlaybackCapture
 * 2. Processing through GTCRN or legacy processor
 * 3. Playing back enhanced audio via AudioTrack
 */
class AudioPipeline(
        private val context: Context,
        private val model: ProcessorModel = ProcessorModel.GTCRN
) {
    companion object {
        private const val TAG = "AudioPipeline"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        // Frame sizes for each model
        private const val LEGACY_FRAME_SIZE = 480 // 10ms at 48kHz
        private const val GTCRN_FRAME_SIZE = 256 // 16ms at 16kHz
    }

    // Frame size depends on model
    private val frameSize: Int
        get() =
                when (model) {
                    ProcessorModel.GTCRN -> GTCRN_FRAME_SIZE
                    ProcessorModel.LEGACY -> LEGACY_FRAME_SIZE
                }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var legacyProcessor: PoiseProcessor? = null
    private var gtcrnProcessor: GTCRNProcessor? = null
    private var processingJob: Job? = null
    private var mediaProjection: MediaProjection? = null

    // Resampling buffer for GTCRN (48kHz -> 16kHz)
    private var resampleBuffer = FloatArray(0)
    private var resampleAccumulator = mutableListOf<Float>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _stats = MutableStateFlow<ProcessingStats?>(null)
    val stats: StateFlow<ProcessingStats?> = _stats.asStateFlow()

    private val _latestRtf = MutableStateFlow(0f)
    val latestRtf: StateFlow<Float> = _latestRtf.asStateFlow()

    /**
     * Start audio capture and processing.
     *
     * @param projection MediaProjection obtained from user permission
     */
    suspend fun start(projection: MediaProjection) =
            withContext(Dispatchers.IO) {
                if (_isRunning.value) {
                    Log.w(TAG, "Pipeline already running")
                    return@withContext
                }

                mediaProjection = projection

                try {
                    // Initialize processor based on model selection
                    when (model) {
                        ProcessorModel.GTCRN -> {
                            gtcrnProcessor = GTCRNProcessor(context)
                            Log.i(TAG, "Using GTCRN model (fast, 0.34MB)")
                        }
                        ProcessorModel.LEGACY -> {
                            legacyProcessor =
                                    PoiseProcessor(context).also {
                                        it.setupInputResampler(SAMPLE_RATE)
                                        it.setupOutputResampler(SAMPLE_RATE)
                                    }
                            Log.i(TAG, "Using legacy model (slower, ~10MB)")
                        }
                    }

                    // Setup audio capture
                    setupAudioCapture(projection)

                    // Setup audio output
                    setupAudioOutput()

                    // Start processing loop
                    _isRunning.value = true
                    processingJob = CoroutineScope(Dispatchers.IO).launch { processAudioLoop() }

                    Log.i(TAG, "Audio pipeline started")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start pipeline: ${e.message}", e)
                    stop()
                    throw e
                }
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
        val bufferSize = maxOf(minBufferSize, LEGACY_FRAME_SIZE * 4 * 4) // At least 4 frames

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
        val bufferSize = maxOf(minBufferSize, LEGACY_FRAME_SIZE * 4 * 4)

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
        // For GTCRN: read at 48kHz, resample to 16kHz, process 256 samples
        // For Legacy: read and process 480 samples at 48kHz directly
        val readSize =
                when (model) {
                    ProcessorModel.GTCRN -> 256 * 3 // ~768 samples at 48kHz = 256 at 16kHz
                    ProcessorModel.LEGACY -> LEGACY_FRAME_SIZE
                }
        val inputBuffer = FloatArray(readSize)
        var statsUpdateCounter = 0

        while (_isRunning.value && currentCoroutineContext().isActive) {
            try {
                // Read from capture
                val readResult =
                        audioRecord?.read(inputBuffer, 0, readSize, AudioRecord.READ_BLOCKING)
                                ?: continue

                if (readResult < 0) {
                    Log.e(TAG, "AudioRecord read error: $readResult")
                    continue
                }

                if (readResult < readSize) {
                    continue // Not enough samples
                }

                // Process based on model
                val processedAudio =
                        when (model) {
                            ProcessorModel.GTCRN -> processGTCRN(inputBuffer)
                            ProcessorModel.LEGACY -> legacyProcessor?.processFrame(inputBuffer)
                        }

                if (processedAudio != null && processedAudio.isNotEmpty()) {
                    // For GTCRN: output is at 16kHz, need to upsample to 48kHz
                    val outputAudio =
                            when (model) {
                                ProcessorModel.GTCRN -> upsample16kTo48k(processedAudio)
                                ProcessorModel.LEGACY -> processedAudio
                            }

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
                    val stats =
                            when (model) {
                                ProcessorModel.GTCRN -> gtcrnProcessor?.getStats()
                                ProcessorModel.LEGACY -> legacyProcessor?.getStats()
                            }
                    stats?.let {
                        _stats.value = it
                        _latestRtf.value = it.rtf
                        AudioServiceState.updateStats(it)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Processing error: ${e.message}")
            }
        }
    }

    /** Simple 48kHz to 16kHz downsampling (take every 3rd sample). */
    private fun downsample48kTo16k(input: FloatArray): FloatArray {
        val output = FloatArray(input.size / 3)
        for (i in output.indices) {
            output[i] = input[i * 3]
        }
        return output
    }

    /** Simple 16kHz to 48kHz upsampling (linear interpolation). */
    private fun upsample16kTo48k(input: FloatArray): FloatArray {
        val output = FloatArray(input.size * 3)
        for (i in input.indices) {
            val baseIdx = i * 3
            output[baseIdx] = input[i]
            if (i < input.size - 1) {
                val next = input[i + 1]
                output[baseIdx + 1] = input[i] + (next - input[i]) / 3f
                output[baseIdx + 2] = input[i] + (next - input[i]) * 2f / 3f
            } else {
                output[baseIdx + 1] = input[i]
                output[baseIdx + 2] = input[i]
            }
        }
        return output
    }

    /** Process audio through GTCRN model with downsampling. */
    private fun processGTCRN(input48k: FloatArray): FloatArray? {
        // Downsample 48kHz -> 16kHz
        val input16k = downsample48kTo16k(input48k)
        return gtcrnProcessor?.processFrame(input16k)
    }

    /** Stop audio capture and processing. */
    fun stop() {
        _isRunning.value = false

        processingJob?.cancel()
        processingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        gtcrnProcessor?.close()
        gtcrnProcessor = null

        legacyProcessor?.close()
        legacyProcessor = null

        mediaProjection?.stop()
        mediaProjection = null

        Log.i(TAG, "Audio pipeline stopped")
    }

    /** Reset processor state (clears ONNX model state). */
    fun resetProcessor() {
        when (model) {
            ProcessorModel.GTCRN -> gtcrnProcessor?.reset()
            ProcessorModel.LEGACY -> legacyProcessor?.reset()
        }
    }
}
