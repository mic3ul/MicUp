package com.micplugin.ai

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/**
 * Fixed-window streaming adapter for a TailStreamingSynth ONNX model.
 *
 * Audio is buffered at 48 kHz. A 1 s rolling context is converted to the most
 * recent 350 ms (300 ms stride) or 550 ms (500 ms stride). A 50 ms overlap is
 * crossfaded between adjacent windows. If inference falls behind, old pending
 * windows are replaced by the newest one instead of allowing latency to grow.
 *
 * This is intentionally a worker-thread component. Never call ONNX Runtime or
 * allocate these buffers from an Oboe real-time callback.
 */
class RvcLiveStreamProcessor(
    private val converter: RvcOnnxConverter,
    private val onOutput: (FloatArray) -> Unit,
    private val onStatus: (Stats) -> Unit = {},
) : Closeable {

    data class Stats(
        val ready: Boolean,
        val inferenceMs: Long,
        val averageInferenceMs: Long,
        val windowsConverted: Long,
        val windowsSkipped: Long,
        val lastError: String?,
    )

    companion object {
        const val INPUT_SAMPLE_RATE = 48_000
        const val CONTEXT_MS = 1_000
        const val DEFAULT_OVERLAP_FRAMES = 5
        private const val MIN_WINDOW_SAMPLES = INPUT_SAMPLE_RATE * CONTEXT_MS / 1_000
    }

    private val running = AtomicBoolean(false)
    private val lock = Any()
    private val ring = FloatArray(MIN_WINDOW_SAMPLES)
    private var writeIndex = 0
    private var totalSamples = 0L
    private var samplesSinceWindow = 0
    private var pendingWindow: FloatArray? = null
    private var workerActive = false
    private var previousTail: FloatArray? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "weirdo-rvc-live").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private val convertedWindows = AtomicLong(0)
    private val skippedWindows = AtomicLong(0)
    private val inferenceTotalMs = AtomicLong(0)
    @Volatile private var lastInferenceMs = 0L
    @Volatile private var lastError: String? = null

    private val outputSampleRate = converter.outputSampleRate
    private val outputFrames = converter.streamingOutputFrames
    private val stepFrames = converter.streamingStepFrames
    private val samplesPerFrame = outputSampleRate / 100
    private val overlapSamples = DEFAULT_OVERLAP_FRAMES * samplesPerFrame
    private val stepSamples = stepFrames * samplesPerFrame
    private val outputSamples = outputFrames * samplesPerFrame

    init {
        require(converter.isStreamingModel) {
            "Selected RVC model is not a fixed-window streaming synthesizer."
        }
        require(outputSampleRate == INPUT_SAMPLE_RATE) {
            "Live RVC requires a 48 kHz synthesizer, got $outputSampleRate."
        }
        require(converter.streamingOutputFrames > DEFAULT_OVERLAP_FRAMES) {
            "Streaming model output is shorter than the crossfade."
        }
        require(stepFrames > 0 && outputFrames - stepFrames == DEFAULT_OVERLAP_FRAMES) {
            "Streaming model must declare a $DEFAULT_OVERLAP_FRAMES-frame overlap."
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastError = null
        synchronized(lock) {
            writeIndex = 0
            totalSamples = 0
            samplesSinceWindow = 0
            pendingWindow = null
            workerActive = false
            previousTail = null
            ring.fill(0f)
        }
        publishStats(ready = false)
    }

    /**
     * Feed mono 48 kHz float PCM from the 20 ms Shizuku capture blocks.
     * The method only copies to the rolling ring and occasionally snapshots it.
     */
    fun pushInput(samples: FloatArray) {
        if (!running.get() || samples.isEmpty()) return
        var snapshot: FloatArray? = null
        synchronized(lock) {
            for (sample in samples) {
                ring[writeIndex] = sample.coerceIn(-1f, 1f)
                writeIndex++
                if (writeIndex == ring.size) writeIndex = 0
            }
            totalSamples += samples.size
            samplesSinceWindow += samples.size

            if (totalSamples >= ring.size && samplesSinceWindow >= stepSamples) {
                samplesSinceWindow %= stepSamples
                snapshot = copyChronologicalWindow()
            }
        }
        snapshot?.let(::queueSnapshot)
    }

    private fun copyChronologicalWindow(): FloatArray {
        val result = FloatArray(ring.size)
        val firstLength = ring.size - writeIndex
        System.arraycopy(ring, writeIndex, result, 0, firstLength)
        if (writeIndex > 0) {
            System.arraycopy(ring, 0, result, firstLength, writeIndex)
        }
        return result
    }

    private fun queueSnapshot(window: FloatArray) {
        var startWorker = false
        synchronized(lock) {
            if (pendingWindow != null) skippedWindows.incrementAndGet()
            pendingWindow = window
            if (!workerActive) {
                workerActive = true
                startWorker = true
            }
        }
        if (startWorker) {
            executor.execute(::drainPendingWindows)
        }
    }

    private fun drainPendingWindows() {
        while (running.get()) {
            val window = synchronized(lock) {
                val next = pendingWindow
                pendingWindow = null
                if (next == null) workerActive = false
                next
            } ?: return

            val startedAt = System.nanoTime()
            try {
                val converted = converter.convert(window, INPUT_SAMPLE_RATE)
                require(converted.sampleRate == outputSampleRate) {
                    "RVC returned \${converted.sampleRate} Hz, expected $outputSampleRate Hz."
                }
                require(converted.samples.size == outputSamples) {
                    "RVC returned \${converted.samples.size} samples; model metadata requires $outputSamples."
                }
                val output = crossfadeAndAdvance(converted.samples)
                onOutput(output)
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                lastInferenceMs = elapsed
                inferenceTotalMs.addAndGet(elapsed)
                convertedWindows.incrementAndGet()
                lastError = null
                publishStats(ready = true)
            } catch (error: Throwable) {
                lastError = error.message ?: error.javaClass.simpleName
                publishStats(ready = previousTail != null)
            }
        }
        synchronized(lock) { workerActive = false }
    }

    /**
     * First output: send 300/500 ms and retain its final 50 ms.
     * Later outputs: crossfade the retained tail with the new head, send
     * exactly one stride, and retain the new output's final 50 ms.
     */
    private fun crossfadeAndAdvance(converted: FloatArray): FloatArray {
        val tailStart = stepSamples
        val tailEnd = stepSamples + overlapSamples
        val currentTail = converted.copyOfRange(tailStart, tailEnd)
        val previous = previousTail

        if (previous == null) {
            previousTail = currentTail
            return converted.copyOfRange(0, stepSamples)
        }

        val emitted = FloatArray(stepSamples)
        for (i in 0 until overlapSamples) {
            val t = i.toFloat() / max(1, overlapSamples - 1)
            // A linear crossfade preserves a stable amplitude for correlated
            // neighboring RVC frames and avoids a loudness bump at joins.
            emitted[i] = previous[i] * (1f - t) + converted[i] * t
        }
        val remainderCount = stepSamples - overlapSamples
        System.arraycopy(
            converted,
            overlapSamples,
            emitted,
            overlapSamples,
            remainderCount,
        )
        previousTail = currentTail
        return emitted
    }

    private fun publishStats(ready: Boolean) {
        val count = convertedWindows.get()
        val average = if (count == 0L) 0L else inferenceTotalMs.get() / count
        runCatching {
            onStatus(
                Stats(
                    ready = ready,
                    inferenceMs = lastInferenceMs,
                    averageInferenceMs = average,
                    windowsConverted = count,
                    windowsSkipped = skippedWindows.get(),
                    lastError = lastError,
                )
            )
        }
    }

    override fun close() {
        running.set(false)
        synchronized(lock) {
            pendingWindow = null
            previousTail = null
            ring.fill(0f)
        }
        // Let an in-flight ONNX call leave its worker before the owner closes
        // the three sessions. This prevents use-after-close during route stop.
        executor.shutdown()
        try {
            if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow()
                executor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
