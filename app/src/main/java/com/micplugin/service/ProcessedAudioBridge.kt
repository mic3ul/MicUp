package com.micplugin.service

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Non-blocking fan-out from the native audio callback to one IPC reader.
 *
 * The audio callback only copies float samples into a bounded queue. PCM16
 * conversion and blocking pipe writes happen on a dedicated worker thread.
 */
object ProcessedAudioBridge {
    private const val TAG = "MicUp.PcmBridge"
    private const val MAX_PACKET_FRAMES = 2048
    private const val QUEUE_CAPACITY = 24
    private const val POOL_CAPACITY = QUEUE_CAPACITY + 8

    private data class AudioFrame(val samples: FloatArray, val frameCount: Int)

    private class Session(
        val queue: ArrayBlockingQueue<AudioFrame>,
        val output: ParcelFileDescriptor.AutoCloseOutputStream,
    ) {
        @Volatile var writerThread: Thread? = null
    }

    private val lock = Any()
    @Volatile private var activeSession: Session? = null
    @Volatile private var engineActive: Boolean = false
    @Volatile private var currentSampleRate: Int = 48_000

    private val bufferPool = ArrayBlockingQueue<FloatArray>(POOL_CAPACITY).apply {
        repeat(POOL_CAPACITY) { offer(FloatArray(MAX_PACKET_FRAMES)) }
    }

    val sampleRate: Int
        get() = currentSampleRate

    fun setEngineState(active: Boolean, sampleRate: Int = currentSampleRate) {
        if (sampleRate > 0) currentSampleRate = sampleRate
        engineActive = active
        if (!active) close()
    }

    /** Opens a new read-side pipe. A second client replaces the first. */
    fun openPcmStream(): ParcelFileDescriptor {
        check(engineActive) { "Start MicUp audio processing before opening the PCM stream." }

        val pipe = ParcelFileDescriptor.createPipe()
        val next = Session(
            queue = ArrayBlockingQueue(QUEUE_CAPACITY),
            output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]),
        )

        val previous = synchronized(lock) {
            val old = activeSession
            activeSession = next
            old
        }
        closeSessionResources(previous)

        val worker = Thread({ pump(next) }, "MicUp-Processed-PCM").apply {
            priority = Thread.NORM_PRIORITY
        }
        next.writerThread = worker
        worker.start()

        Log.i(TAG, "PCM client connected at ${currentSampleRate}Hz, mono PCM16")
        return pipe[0]
    }

    /**
     * Called from the JNI callback. It never waits for the consumer or pipe.
     * Oldest frames are dropped under backpressure to keep latency bounded.
     */
    fun writeProcessed(samples: FloatArray, size: Int) {
        val session = activeSession ?: return
        if (!engineActive || size <= 0) return

        var offset = 0
        val safeSize = minOf(size, samples.size)
        while (offset < safeSize) {
            val count = minOf(MAX_PACKET_FRAMES, safeSize - offset)
            val copy = bufferPool.poll() ?: FloatArray(MAX_PACKET_FRAMES)
            System.arraycopy(samples, offset, copy, 0, count)
            val frame = AudioFrame(copy, count)

            if (!session.queue.offer(frame)) {
                session.queue.poll()?.let { bufferPool.offer(it.samples) }
                if (!session.queue.offer(frame)) bufferPool.offer(copy)
            }
            offset += count
        }
    }

    fun close() {
        val previous = synchronized(lock) {
            val old = activeSession
            activeSession = null
            old
        }
        closeSessionResources(previous)
    }

    private fun pump(session: Session) {
        val packet = ByteArray(4 + MAX_PACKET_FRAMES * 2)
        try {
            while (activeSession === session && !Thread.currentThread().isInterrupted) {
                val frame = session.queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    val count = frame.frameCount.coerceIn(0, MAX_PACKET_FRAMES)
                    packet[0] = (count and 0xff).toByte()
                    packet[1] = ((count ushr 8) and 0xff).toByte()
                    packet[2] = ((count ushr 16) and 0xff).toByte()
                    packet[3] = ((count ushr 24) and 0xff).toByte()

                    for (i in 0 until count) {
                        val raw = frame.samples[i]
                        val finite = if (raw.isFinite()) raw else 0f
                        val pcm = when {
                            finite >= 1f -> 32767
                            finite <= -1f -> -32768
                            else -> (finite * 32767f).roundToInt().coerceIn(-32768, 32767)
                        }
                        packet[4 + i * 2] = (pcm and 0xff).toByte()
                        packet[5 + i * 2] = ((pcm shr 8) and 0xff).toByte()
                    }
                    session.output.write(packet, 0, 4 + count * 2)
                } finally {
                    bufferPool.offer(frame.samples)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            Log.i(TAG, "PCM reader disconnected: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "PCM bridge worker failed", e)
        } finally {
            synchronized(lock) {
                if (activeSession === session) activeSession = null
            }
            closeSessionResources(session)
        }
    }

    private fun closeSessionResources(session: Session?) {
        if (session == null) return
        session.writerThread?.let { if (it !== Thread.currentThread()) it.interrupt() }
        try {
            session.output.close()
        } catch (_: IOException) {
        }
        while (true) {
            val queued = session.queue.poll() ?: break
            bufferPool.offer(queued.samples)
        }
    }
}
