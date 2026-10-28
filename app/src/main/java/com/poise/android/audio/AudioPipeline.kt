package com.poise.android.audio

import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import com.poise.android.service.AudioServiceState
import com.poise.android.service.PoiseSettings
import java.util.Locale
import java.util.concurrent.Executors
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

        // Room for 160 ms of float samples in capture and output. Only the audio actually waiting in
        // them is delay, so the room costs nothing until a slow stretch needs it
        private const val MIN_BUFFER_BYTES = SAMPLE_RATE * 160 / 1000 * Float.SIZE_BYTES

        private const val HEALTH_LOG_FRAMES = SAMPLE_RATE * 5 / FRAME_SIZE // every ~5 s
        private const val WARMUP_FRAMES = SAMPLE_RATE // the first second of output, while capture starts

        // How long a frame's work should take, for the CPU governor's performance hint: well inside
        // the 16 ms frame, so slow stretches still have room
        private const val HINT_TARGET_NS = 4_000_000L
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var gtcrnProcessor: GTCRNProcessor? = null
    private var processingJob: Job? = null
    private var mediaProjection: MediaProjection? = null

    // The output picked on Home, applied from the loop. Looked up again when the pick changes or a
    // device comes or goes: a reconnected device has a new id, and the track only knows the old one
    private var appliedOutput: PoiseSettings.Output? = null
    @Volatile private var outputsChanged = true
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val outputWatcher =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { outputsChanged = true }
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { outputsChanged = true }
            }
    private val routingListener = AudioRouting.OnRoutingChangedListener { AudioServiceState.setRoutedOutput(it.routedDevice) }

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

        // Each frame must be done within 16 ms, so the loop gets its own thread at audio priority
        // instead of sharing the IO pool at normal priority.
        val audioThread =
                Executors.newSingleThreadExecutor { task ->
                            Thread(
                                    {
                                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                                        task.run()
                                    },
                                    "PoiseAudio"
                            )
                        }
                        .asCoroutineDispatcher()

        processingJob =
                CoroutineScope(audioThread).launch {
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
                        audioThread.close() // lets the thread end once this job finishes
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
        // The accessibility stream plays at the media volume but isn't silenced by the media mute,
        // so STREAM_MUSIC can be muted while our processed output still plays. Unlike the voice-call
        // stream it goes to the main output, with a shorter path to the speaker.
        val audioAttributes =
                AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
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

        audioTrack?.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
        audioManager.registerAudioDeviceCallback(outputWatcher, Handler(Looper.getMainLooper()))
        applyOutput() // before it plays, so it starts where it should
        // The loop writes at capture's pace, so the audio queued in the track stays at what was put
        // there first: that's the cushion against slow frames, and all of it is delay. Start at two
        // frames of silence; the loop adds a frame each time the speaker actually runs dry. Set
        // after applyOutput: a picked output rebuilds the track at its full capacity (160 ms).
        audioTrack?.bufferSizeInFrames = 2 * FRAME_SIZE
        // Filled with silence before it plays rather than starting empty (on the Galaxy S9+ the
        // delay then measured about 30 ms lower at the same buffer size)
        audioTrack?.let { writeSilence(it, it.bufferSizeInFrames) }
        audioTrack?.play()
        Log.i(TAG, "AudioTrack started: $SAMPLE_RATE Hz, low-latency mode")
    }

    private suspend fun processAudioLoop() {
        // Capture already runs at the model's rate, so one read is one model frame
        val readSize = FRAME_SIZE
        val inputBuffer = FloatArray(readSize)
        val skipBuffer = FloatArray(audioRecord?.bufferSizeInFrames ?: 0)
        val timestamp = AudioTimestamp()
        var statsUpdateCounter = 0
        var healthLogCounter = 0
        var underruns = 0
        var warmupUnderruns = 0
        // Frames taken from capture (read, skipped or lost) and frames handed to the speaker
        var framesRead = 0L
        var framesWritten = audioTrack?.bufferSizeInFrames?.toLong() ?: 0L // the silence it starts with
        // Audio the track may hold before writes wait; grows by a frame per underrun
        var cushion = audioTrack?.bufferSizeInFrames ?: 0
        var lostFrames = 0L
        var skippedFrames = 0L
        // Work per frame (model, STFT, volume), for the health log
        var workNs = 0L
        var workMaxNs = 0L
        applySettings()
        // Android 12+: tells the CPU governor how long each frame's work took against the target,
        // so it can raise clocks before frames run late. Null where the device doesn't support it
        val hint =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(PerformanceHintManager::class.java)
                            ?.createHintSession(intArrayOf(Process.myTid()), HINT_TARGET_NS)
                } else null
        Log.i(TAG, "Audio thread pinned: ${gtcrnProcessor?.pinToFastCores()} fast cores, performance hint ${if (hint != null) "on" else "off"}")

        while (_isRunning.value && currentCoroutineContext().isActive) {
            try {
                // Read from capture
                val readResult =
                        audioRecord?.read(inputBuffer, 0, readSize, AudioRecord.READ_BLOCKING)
                                ?: continue

                if (readResult < 0) {
                    // Errors here do not clear (e.g. dead capture); retrying would spin the CPU
                    Log.e(TAG, "AudioRecord read error: $readResult, stopping")
                    AudioServiceState.noteStop("Capture failed")
                    break
                }

                if (readResult < readSize) {
                    continue // Not enough samples
                }
                framesRead += readSize
                val workStart = System.nanoTime()

                val outputAudio = gtcrnProcessor?.processFrame(inputBuffer)

                if (outputAudio != null && outputAudio.isNotEmpty()) {
                    // Apply output volume from UI slider
                    val volume = AudioServiceState.outputVolume.value
                    for (i in outputAudio.indices) {
                        outputAudio[i] = (outputAudio[i] * volume).coerceIn(-1f, 1f)
                    }
                    val work = System.nanoTime() - workStart
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hint?.reportActualWorkDuration(work)
                    workNs += work
                    workMaxNs = maxOf(workMaxNs, work)

                    audioTrack?.write(outputAudio, 0, outputAudio.size, AudioTrack.WRITE_BLOCKING)
                    framesWritten += outputAudio.size
                }

                // Update stats
                statsUpdateCounter++
                if (statsUpdateCounter >= 10) {
                    statsUpdateCounter = 0
                    gtcrnProcessor?.getStats()?.let { AudioServiceState.updateStats(it) }
                    applySettings()
                    // Again, since Android moving the app between foreground and background can
                    // reset it (older kernels do when changing a thread's cpuset)
                    gtcrnProcessor?.pinToFastCores()

                    val autoStopMinutes = PoiseSettings.autoStopMinutes.value
                    val quietFrames = gtcrnProcessor?.framesSinceActive ?: 0
                    if (autoStopMinutes > 0 && quietFrames >= autoStopMinutes * 60 * SAMPLE_RATE / FRAME_SIZE) {
                        Log.i(TAG, "Nothing played for $autoStopMinutes min, stopping")
                        AudioServiceState.noteStop(if (autoStopMinutes == 60) "Silent for 1 hour" else "Silent for $autoStopMinutes min")
                        break
                    }

                    // Each underrun is a click: add a frame of silence to the cushion, so the next
                    // slow stretch has 16 ms more to use up. Not while capture is still starting,
                    // though: the track runs dry then on silence nobody hears, and growing it for
                    // that only adds delay
                    val track = audioTrack
                    if (track != null) {
                        // Android rebuilds the track at its full capacity when the output changes
                        // mid-session (a new pick, a reconnect); keep the cushion we chose
                        if (track.bufferSizeInFrames > cushion) track.bufferSizeInFrames = cushion
                        if (framesWritten < WARMUP_FRAMES) {
                            underruns = track.underrunCount
                            warmupUnderruns = underruns
                        } else if (track.underrunCount > underruns) {
                            underruns = track.underrunCount
                            val before = track.bufferSizeInFrames
                            // Capped at the track's capacity; past that, more underruns change nothing
                            if (track.setBufferSizeInFrames(before + FRAME_SIZE) > before) {
                                cushion = track.bufferSizeInFrames
                                framesWritten += writeSilence(track, FRAME_SIZE)
                                Log.i(TAG, "Underrun, output cushion now ${ms(track.bufferSizeInFrames.toLong())} ms")
                            }
                        }
                    }

                    // Audio waiting in the capture buffer is delay too. After a slow stretch it holds
                    // what the cushion lent the speaker, and the loop catches up on it. Past what the
                    // cushion explains it is delay that never shrinks by itself (it builds up at
                    // startup), so skip that part (a short jump in the sound).
                    audioRecord?.let { record ->
                        val unread = (capturedFrames(record, timestamp) ?: return@let) - framesRead
                        // More than the buffer holds was already dropped by Android
                        val overflow = unread - record.bufferSizeInFrames
                        if (overflow > 0) {
                            framesRead += overflow
                            lostFrames += overflow
                        }
                        val waiting = unread - maxOf(overflow, 0)
                        val queued = track?.let { framesWritten - (it.playbackHeadPosition.toLong() and 0xffffffffL) } ?: 0
                        val excess = minOf(waiting, waiting + queued - cushion)
                        if (excess > 2 * FRAME_SIZE) {
                            val skipped = record.read(skipBuffer, 0, excess.toInt(), AudioRecord.READ_NON_BLOCKING)
                            if (skipped > 0) {
                                framesRead += skipped
                                skippedFrames += skipped
                            }
                        }
                    }
                }

                if (++healthLogCounter >= HEALTH_LOG_FRAMES) {
                    healthLogCounter = 0
                    val work = String.format(Locale.ROOT, "work=%.1f/%.1f ms", workNs / 1e6 / HEALTH_LOG_FRAMES, workMaxNs / 1e6)
                    logHealth(framesRead, framesWritten, lostFrames, skippedFrames, warmupUnderruns, work)
                    workNs = 0L
                    workMaxNs = 0L
                }
            } catch (e: Exception) {
                Log.e(TAG, "Processing error: ${e.message}")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hint?.close()
    }

    /** Queues [frames] of silence without waiting; returns how many were queued. */
    private fun writeSilence(track: AudioTrack, frames: Int): Int =
            maxOf(track.write(FloatArray(frames), 0, frames, AudioTrack.WRITE_NON_BLOCKING), 0)

    private fun applySettings() {
        val processor = gtcrnProcessor ?: return
        processor.silenceThresholdDb = PoiseSettings.silenceDb.value.toFloat()
        processor.residualGain =
                Math.pow(10.0, PoiseSettings.reductionDb(PoiseSettings.strength.value) / 20.0).toFloat()
        applyOutput()
    }

    /** Sends the track to the picked output. Without one, or while it's away, Android routes it. */
    private fun applyOutput() {
        val track = audioTrack ?: return
        val want = PoiseSettings.output.value
        if (!outputsChanged && want == appliedOutput) return
        outputsChanged = false
        appliedOutput = want
        track.setPreferredDevice(want?.let { w -> audioManager.pickableOutputs().firstOrNull { it.outputKey == w.key } })
    }

    /** Frames captured so far, extrapolated to now, or null while capture has no timestamp yet. */
    private fun capturedFrames(record: AudioRecord, timestamp: AudioTimestamp): Long? {
        if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) {
            return null
        }
        return timestamp.framePosition + (System.nanoTime() - timestamp.nanoTime) * SAMPLE_RATE / 1_000_000_000
    }

    /**
     * Logs what the listener hears. Underruns: the speaker ran out of audio (a click), leaving out
     * the [warmupUnderruns] on silence while capture started. Lost and
     * skipped: input dropped by Android or by us to catch up (a short jump). Delay: from capture
     * to speaker; it leaves out the playing app's own path to the capture point. [work]: average and
     * longest frame of work since the last log.
     */
    private fun logHealth(framesRead: Long, framesWritten: Long, lostFrames: Long, skippedFrames: Long, warmupUnderruns: Int, work: String) {
        val track = audioTrack ?: return
        val record = audioRecord ?: return
        val played = AudioTimestamp()
        val captured = capturedFrames(record, AudioTimestamp())
        val drops = "underruns=${track.underrunCount - warmupUnderruns} lost=${ms(lostFrames)} ms skipped=${ms(skippedFrames)} ms $work"
        if (captured == null || !track.getTimestamp(played)) {
            Log.i(TAG, "Health: $drops")
            return
        }
        val playedNow = played.framePosition + (System.nanoTime() - played.nanoTime) * SAMPLE_RATE / 1_000_000_000
        val input = captured - framesRead // waiting to be read
        val output = framesWritten - playedNow // written, not yet played
        // The STFT adds one hop between reading a sample and writing it
        Log.i(
                TAG,
                "Health: $drops delay=${ms(input + FRAME_SIZE + output)} ms " +
                        "(input ${ms(input)}, output ${ms(output)}) buffer=${ms(track.bufferSizeInFrames.toLong())} ms"
        )
    }

    private fun ms(frames: Long) = frames * 1000 / SAMPLE_RATE

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

        audioManager.unregisterAudioDeviceCallback(outputWatcher)
        audioTrack?.removeOnRoutingChangedListener(routingListener)
        audioTrack?.release()
        audioTrack = null

        gtcrnProcessor?.close()
        gtcrnProcessor = null

        Log.i(TAG, "Audio pipeline stopped")
    }
}
