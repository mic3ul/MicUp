package com.micplugin.service

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Non-blocking fan-out from the audio pump to one IPC reader.
 *
 * The producer copies into a fixed pool and never allocates or waits for the
 * pipe writer. PCM16 conversion and blocking writes stay on the worker thread.
 */
object ProcessedAudioBridge {
    private const val TAG = "weirdo.PcmBridge"
    private const val MAX_PACKET_FRAMES = 2048
    private const val QUEUE_CAPACITY = 24
    private const val POOL_CAPACITY = QUEUE_CAPACITY + 8

    private data class AudioFrame(val samples: FloatArray, val frameCount: Int)

    private class Session(
        val queue: ArrayBlockingQueue<AudioFrame>,
        val output: ParcelFileDescriptor.AutoCloseOutputStream,
    ) {
        @Volatile var writerThread: Thread? = null
        @Volatile var closed: Boolean = false
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

    /**
     * Changes engine state atomically with session creation. Stopping the
     * engine detaches the reader before its pipe is closed.
     */
    fun setEngineState(active: Boolean, sampleRate: Int = currentSampleRate) {
        val previous = synchronized(lock) {
            if (sampleRate > 0) currentSampleRate = sampleRate
            engineActive = active
            if (active) {
                null
            } else {
                activeSession.also { activeSession = null }
            }
        }
        closeSessionResources(previous)
    }

    /** Opens one read-side pipe. A second client replaces the previous one. */
    fun openPcmStream(): ParcelFileDescriptor {
        lateinit var reader: ParcelFileDescriptor
        var previous: Session? = null

        synchronized(lock) {
            check(engineActive) { "Start weirdo audio processing before opening the PCM stream." }

            val pipe = ParcelFileDescriptor.createPipe()
            val next = Session(
                queue = ArrayBlockingQueue(QUEUE_CAPACITY),
                output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]),
            )

            previous = activeSession
            activeSession = next

            val worker = Thread({ pump(next) }, "weirdo-Processed-PCM").apply {
                priority = Thread.NORM_PRIORITY
            }
            next.writerThread = worker

            try {
                worker.start()
            } catch (failure: Throwable) {
                activeSession = previous
                closeSessionResources(next)
                try { pipe[0].close() } catch (_: IOException) {}
                throw failure
            }

            reader = pipe[0]
        }

        closeSessionResources(previous)
        Log.i(TAG, "PCM client connected at ${currentSampleRate}Hz, mono PCM16")
        return reader
    }

    /**
     * Called by the audio pump. No blocking operations or heap allocation are
     * performed here; if the fixed pool is exhausted, this chunk is dropped.
     */
    fun writeProcessed(samples: FloatArray, size: Int) {
        val session = activeSession ?: return
        if (!engineActive || session.closed || size <= 0) return

        val safeSize = minOf(size, samples.size)
        var offset = 0

        while (offset < safeSize) {
            if (!engineActive || session.closed || activeSession !== session) return

            val count = minOf(MAX_PACKET_FRAMES, safeSize - offset)
            val copy = bufferPool.poll() ?: return
            System.arraycopy(samples, offset, copy, 0, count)
            val frame = AudioFrame(copy, count)

            if (session.closed || activeSession !== session || !engineActive) {
                bufferPool.offer(copy)
                return
            }

            if (!session.queue.offer(frame)) {
                session.queue.poll()?.let { bufferPool.offer(it.samples) }

                if (session.closed || activeSession !== session ||
                    !engineActive || !session.queue.offer(frame)
                ) {
                    bufferPool.offer(copy)
                }
            }

            // Close may race the enqueue. Remove and recycle only if the worker
            // has not already taken ownership of this exact frame.
            if (session.closed && session.queue.remove(frame)) {
                bufferPool.offer(copy)
            }

            offset += count
        }
    }

    /** Detaches the current client without changing the engine's running state. */
    fun close() {
        val previous = synchronized(lock) {
            activeSession.also { activeSession = null }
        }
        closeSessionResources(previous)
    }

    private fun pump(session: Session) {
        val packet = ByteArray(4 + MAX_PACKET_FRAMES * 2)

        try {
            while (activeSession === session &&
                !session.closed && !Thread.currentThread().isInterrupted
            ) {
                val frame = session.queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    val count = frame.frameCount.coerceIn(1, MAX_PACKET_FRAMES)
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
                        packet[5 + i * 2] = ((pcm ushr 8) and 0xff).toByte()
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
        session.closed = true

        try {
            session.output.close()
        } catch (_: IOException) {
        }

        session.writerThread?.let {
            if (it !== Thread.currentThread()) it.interrupt()
        }

        while (true) {
            val queued = session.queue.poll() ?: break
            bufferPool.offer(queued.samples)
        }
    }
}
