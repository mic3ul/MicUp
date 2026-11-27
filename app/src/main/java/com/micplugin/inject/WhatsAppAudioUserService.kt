package com.micplugin.inject

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Shizuku UserService running as shell UID.
 *
 * The AudioPolicy injector is deliberately restricted to the UID that owns
 * com.whatsapp. It captures the physical microphone in this shell process and
 * injects processed PCM only into that application's recording mix.
 *
 * This is not an acoustic speaker/microphone loop and never targets all apps.
 */
class WhatsAppAudioUserService(
    appContext: Context? = null
) : IWhatsAppMicInjector.Stub() {

    companion object {
        private const val TAG = "WeirdoWhatsAppInjector"
        private const val REQUIRED_PACKAGE = "com.whatsapp"
        private const val RATE = 48_000
        private const val BLOCK_SAMPLES = 960 // 20 ms at 48 kHz
        private const val MAX_CAPTURE_QUEUE_BLOCKS = 10
        // The RVC worker delivers 15 (300 ms) or 25 (500 ms) 20 ms blocks
        // in a burst; leave room for one full chunk without an artificial drop.
        private const val MAX_OUTPUT_QUEUE_BLOCKS = 32
        private const val PERMISSION_MODIFY_AUDIO_ROUTING =
            "android.permission.MODIFY_AUDIO_ROUTING"
    }

    private val running = AtomicBoolean(false)
    private val micQueue = ArrayBlockingQueue<ByteArray>(MAX_CAPTURE_QUEUE_BLOCKS)
    private val outputQueue = ArrayBlockingQueue<ShortArray>(MAX_OUTPUT_QUEUE_BLOCKS)

    @Volatile private var context: Context? = appContext?.applicationContext
    @Volatile private var shellContext: Context? = null
    @Volatile private var audioManager: AudioManager? = null
    @Volatile private var policy: Any? = null
    @Volatile private var mix: Any? = null
    @Volatile private var injectionTrack: AudioTrack? = null
    @Volatile private var microphone: AudioRecord? = null
    @Volatile private var aec: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var injectionThread: Thread? = null
    @Volatile private var policyHandlerThread: HandlerThread? = null
    @Volatile private var status = "idle"
    @Volatile private var captureSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    @Volatile private var droppedCaptureBlocks = 0L
    @Volatile private var droppedOutputBlocks = 0L
    @Volatile private var captureBlocks = 0L
    @Volatile private var captureRmsDb = -120f
    @Volatile private var targetRecordingActive = false
    @Volatile private var shellInputSilenced = false
    @Volatile private var targetRecordingSource = -1
    @Volatile private var targetUid = -1

    override fun start(targetUid: Int, sampleRate: Int): String {
        val token = Binder.clearCallingIdentity()
        return try {
            startInternal(targetUid, sampleRate)
        } catch (error: Throwable) {
            Log.e(TAG, "start failed", error)
            stopInternal()
            "ERROR: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}"
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun startInternal(uid: Int, requestedRate: Int): String {
        if (running.get()) return "Already running for uid=$targetUid"
        val ctx = context ?: return "ERROR: Shizuku did not supply a UserService Context"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "ERROR: AudioPolicy microphone injection requires Android 10 or newer"
        }
        if (requestedRate != RATE) return "ERROR: only 48000 Hz is supported"
        if (uid < 10_000) return "ERROR: invalid target UID"

        val packages = ctx.packageManager.getPackagesForUid(uid).orEmpty()
        if (REQUIRED_PACKAGE !in packages) {
            return "ERROR: target UID does not belong to $REQUIRED_PACKAGE"
        }
        val routingPermission = ctx.checkPermission(
            PERMISSION_MODIFY_AUDIO_ROUTING, Process.myPid(), Process.myUid()
        )
        if (routingPermission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "ERROR: shell UID has no MODIFY_AUDIO_ROUTING permission on this ROM"
        }

        targetUid = uid
        val systemContext = ShellContext(ctx)
        shellContext = systemContext
        audioManager = runCatching {
            AudioManager::class.java.getConstructor(Context::class.java)
                .newInstance(systemContext)
        }.getOrElse {
            systemContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } ?: return "ERROR: AudioManager unavailable"

        val policyReady = registerWhatsAppOnlyPolicy(systemContext, uid)
        if (!policyReady) {
            stopInternal()
            return "ERROR: $status"
        }

        val record = createMicrophone(ctx)
        if (record == null) {
            stopInternal()
            return "ERROR: could not open shell microphone on this ROM"
        }
        microphone = record

        running.set(true)
        droppedCaptureBlocks = 0
        droppedOutputBlocks = 0
        captureBlocks = 0
        captureRmsDb = -120f
        targetRecordingActive = false
        shellInputSilenced = false
        targetRecordingSource = -1
        micQueue.clear()
        outputQueue.clear()

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            stopInternal()
            return "ERROR: AudioRecord did not enter RECORDSTATE_RECORDING"
        }

        attachCaptureEffects(record.audioSessionId)
        startCaptureThread(record)
        startInjectionThread()

        status = "running: package=$REQUIRED_PACKAGE uid=$uid rate=$RATE source=$captureSource"
        Log.i(TAG, status)
        return status
    }

    private fun registerWhatsAppOnlyPolicy(ctx: Context, uid: Int): Boolean {
        try {
            // AudioPolicy and AudioMix are @SystemApi. Shizuku runs this
            // service under shell UID; exempt hidden API lookup in this shell
            // process only, before resolving the policy classes.
            runCatching {
                val vmRuntime = Class.forName("dalvik.system.VMRuntime")
                val runtime = vmRuntime.getMethod("getRuntime").invoke(null)
                vmRuntime.getMethod("setHiddenApiExemptions", Array<String>::class.java)
                    .invoke(runtime, arrayOf("L"))
            }

            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val intClass = Int::class.javaPrimitiveType!!

            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            val roleSet = runCatching {
                val injectorRole = ruleClass.getField("MIX_ROLE_INJECTOR").getInt(null)
                ruleBuilderClass.getMethod("setTargetMixRole", intClass)
                    .invoke(ruleBuilder, injectorRole)
            }.isSuccess
            if (!roleSet) {
                val recorderType = mixClass.getField("MIX_TYPE_RECORDERS").getInt(null)
                ruleBuilderClass.getMethod("setTargetMixType", intClass)
                    .invoke(ruleBuilder, recorderType)
            }

            val matchUid = ruleClass.getField("RULE_MATCH_UID").getInt(null)
            ruleBuilderClass.getMethod("addMixRule", intClass, Any::class.java)
                .invoke(ruleBuilder, matchUid, Integer.valueOf(uid))
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

            val format = AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
            val loopback = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            mixBuilderClass.getMethod("setRouteFlags", intClass).invoke(mixBuilder, loopback)
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            val newMix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

            val handlerThread = HandlerThread("weirdo-audio-policy")
            handlerThread.start()
            policyHandlerThread = handlerThread

            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(ctx)
            runCatching {
                policyBuilderClass.getMethod("setLooper", android.os.Looper::class.java)
                    .invoke(policyBuilder, handlerThread.looper)
            }
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, newMix)
            val newPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

            val manager = audioManager ?: error("AudioManager unavailable")
            val registered = AudioManager::class.java
                .getMethod("registerAudioPolicy", policyClass)
                .invoke(manager, newPolicy) as Int
            if (registered != 0) {
                status = "registerAudioPolicy returned $registered"
                return false
            }

            policy = newPolicy
            mix = newMix

            val sourceTrack = runCatching {
                policyClass.getMethod("createAudioTrackSource", mixClass)
                    .invoke(newPolicy, newMix) as? AudioTrack
            }.getOrNull()

            val track = if (sourceTrack != null &&
                sourceTrack.state == AudioTrack.STATE_INITIALIZED
            ) {
                sourceTrack
            } else {
                runCatching { sourceTrack?.release() }
                // Android 14+ may reject the hidden AudioPolicy factory because
                // it builds AudioTrack without a Context. The documented shell
                // injector fallback binds the track explicitly to this UserService
                // Context and the registered mix address.
                createManualInjectionTrack(ctx, newMix)
            }

            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track?.release() }
                status = "Could not create an initialized AudioPolicy injection track: " +
                    (lastTrackError ?: "AudioPolicy source and Context-bound fallback both failed")
                return false
            }
            injectionTrack = track
            track.play()
            return true
        } catch (error: Throwable) {
            val root = error.cause ?: error
            status = "AudioPolicy setup failed: ${root.javaClass.simpleName}: ${root.message}"
            Log.e(TAG, status, error)
            return false
        }
    }

    @Volatile private var lastTrackError: String? = null

    private fun createManualInjectionTrack(ctx: Context, audioMix: Any): AudioTrack? {
        return try {
            // The hidden VMRuntime exemption is scoped to this Shizuku shell
            // process only. It is needed for AudioMix.getRegistration().
            runCatching {
                val vmRuntime = Class.forName("dalvik.system.VMRuntime")
                val runtime = vmRuntime.getMethod("getRuntime").invoke(null)
                vmRuntime.getMethod("setHiddenApiExemptions", Array<String>::class.java)
                    .invoke(runtime, arrayOf("L"))
            }

            val registration = audioMix.javaClass.getMethod("getRegistration")
                .invoke(audioMix) as? String
                ?: error("AudioMix registration is null")
            require(registration.isNotBlank()) { "AudioMix registration is empty" }

            val mixFormat = audioMix.javaClass.getMethod("getFormat")
                .invoke(audioMix) as AudioFormat
            val outputMask = if (mixFormat.channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val trackFormat = AudioFormat.Builder()
                .setSampleRate(mixFormat.sampleRate)
                .setChannelMask(outputMask)
                .setEncoding(mixFormat.encoding)
                .build()

            val virtualSourceUsage = AudioAttributes::class.java
                .getField("USAGE_VIRTUAL_SOURCE").getInt(null)
            val attributesBuilder = AudioAttributes.Builder()
                .setUsage(virtualSourceUsage)
            AudioAttributes.Builder::class.java
                .getMethod("addTag", String::class.java)
                .invoke(attributesBuilder, if (registration.startsWith("addr=")) registration else "addr=$registration")

            val minBuffer = AudioTrack.getMinBufferSize(
                trackFormat.sampleRate,
                outputMask,
                trackFormat.encoding,
            )
            require(minBuffer > 0) { "AudioTrack minimum buffer size failed: $minBuffer" }
            val bytesPerSample = when (trackFormat.encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            val channelCount = if (outputMask == AudioFormat.CHANNEL_OUT_MONO) 1 else 2
            val safeBuffer = maxOf(
                minBuffer,
                trackFormat.sampleRate / 100 * channelCount * bytesPerSample * 2,
            )

            val builder = AudioTrack.Builder()
                .setAudioAttributes(attributesBuilder.build())
                .setAudioFormat(trackFormat)
                .setBufferSizeInBytes(safeBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)

            AudioTrack.Builder::class.java
                .getMethod("setContext", Context::class.java)
                .invoke(builder, ctx)

            (builder.build() as AudioTrack).takeIf {
                it.state == AudioTrack.STATE_INITIALIZED
            }
        } catch (error: Throwable) {
            lastTrackError = "${error.cause?.message ?: error.message ?: error.javaClass.simpleName}"
            Log.w(TAG, "Context-bound AudioTrack fallback failed: $lastTrackError", error)
            null
        }
    }

    private fun createMicrophone(ctx: Context): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) {
            status = "AudioRecord.getMinBufferSize returned $minimum"
            return null
        }
        val bytes = max(minimum, BLOCK_SAMPLES * 2 * 4)

        fun build(source: Int): AudioRecord? = runCatching {
            val builder = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
            if (Build.VERSION.SDK_INT >= 31) {
                runCatching { builder.setContext(shellContext ?: ctx) }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                // Keep shell capture non-privacy-sensitive so a foreground
                // WhatsApp PTT session does not automatically silence it.
                runCatching { builder.setPrivacySensitive(false) }
            }
            builder.build().takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: throw IllegalStateException("AudioRecord is not initialised")
        }.getOrElse {
            Log.w(TAG, "AudioRecord source=$source failed", it)
            null
        }

        // VOICE_PERFORMANCE is intended for real-time processed playback
        // (e.g. karaoke) and minimizes capture/playback coupling. It is the
        // preferred source for live transformation; fall back to VoIP/MIC
        // when a device does not implement it correctly.
        var result: AudioRecord? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result = build(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
            if (result != null) captureSource = MediaRecorder.AudioSource.VOICE_PERFORMANCE
        }
        if (result == null) {
            result = build(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            if (result != null) captureSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        if (result == null) {
            result = build(MediaRecorder.AudioSource.MIC)
            if (result != null) captureSource = MediaRecorder.AudioSource.MIC
        }
        if (result == null) {
            status = "AudioRecord failed for VOICE_COMMUNICATION, MIC and VOICE_PERFORMANCE sources"
        }
        return result
    }

    private fun attachCaptureEffects(sessionId: Int) {
        runCatching { aec?.release() }
        runCatching { noiseSuppressor?.release() }
        aec = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }
                .getOrNull()
        } else null
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }
                .getOrNull()
        } else null
        Log.i(
            TAG,
            "Capture effects: AEC=${aec?.enabled}, NS=${noiseSuppressor?.enabled}"
        )
    }

    /**
     * Android can silence one of two concurrent AudioRecord clients. Read the
     * active recording configurations so the app can distinguish a silent
     * shell capture from an injection/AudioTrack failure.
     */
    private fun refreshRecordingDiagnostics(record: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val configurations = runCatching {
            audioManager?.activeRecordingConfigurations.orEmpty()
        }.getOrDefault(emptyList())

        val shellUid = Process.myUid()
        var ownSilenced = false
        var ownSeen = false
        var targetSeen = false
        var targetSource = -1

        for (configuration in configurations) {
            val type = configuration.javaClass
            val uid = runCatching {
                type.getMethod("getClientUid").invoke(configuration) as Int
            }.getOrNull() ?: continue
            val sessionId = runCatching {
                type.getMethod("getClientAudioSessionId").invoke(configuration) as Int
            }.getOrNull() ?: -1

            if (uid == shellUid && sessionId == record.audioSessionId) {
                ownSeen = true
                ownSilenced = runCatching {
                    type.getMethod("isClientSilenced").invoke(configuration) as Boolean
                }.getOrDefault(false)
            }
            if (uid == targetUid) {
                targetSeen = true
                targetSource = runCatching {
                    type.getMethod("getClientAudioSource").invoke(configuration) as Int
                }.getOrDefault(-1)
            }
        }

        shellInputSilenced = ownSeen && ownSilenced
        targetRecordingActive = targetSeen
        targetRecordingSource = targetSource
    }

    private fun startCaptureThread(record: AudioRecord) {
        captureThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            val readBuffer = ShortArray(BLOCK_SAMPLES)
            val outputBlock = ShortArray(BLOCK_SAMPLES)
            var pending = 0
            var lastMeterNs = System.nanoTime()
            var sumSquares = 0.0
            var squareCount = 0L

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val count = record.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    var sourceOffset = 0
                    while (sourceOffset < count) {
                        val copyCount = minOf(BLOCK_SAMPLES - pending, count - sourceOffset)
                        System.arraycopy(readBuffer, sourceOffset, outputBlock, pending, copyCount)
                        for (i in sourceOffset until sourceOffset + copyCount) {
                            val normalized = readBuffer[i].toDouble() / 32768.0
                            sumSquares += normalized * normalized
                            squareCount++
                        }
                        pending += copyCount
                        sourceOffset += copyCount

                        if (pending == BLOCK_SAMPLES) {
                            val bytes = ByteArray(BLOCK_SAMPLES * 2)
                            for (i in 0 until BLOCK_SAMPLES) {
                                val value = outputBlock[i].toInt()
                                bytes[i * 2] = value.toByte()
                                bytes[i * 2 + 1] = (value shr 8).toByte()
                            }
                            if (!micQueue.offer(bytes)) {
                                micQueue.poll()
                                droppedCaptureBlocks++
                                if (!micQueue.offer(bytes)) droppedCaptureBlocks++
                            }
                            pending = 0
                        }
                    }

                    val now = System.nanoTime()
                    if (now - lastMeterNs >= 1_000_000_000L) {
                        if (squareCount > 0) {
                            val rms = kotlin.math.sqrt(sumSquares / squareCount)
                            captureRmsDb = if (rms <= 1e-6) -120f else (20.0 * kotlin.math.log10(rms)).toFloat()
                            captureBlocks += squareCount / BLOCK_SAMPLES
                        }
                        refreshRecordingDiagnostics(record)
                        lastMeterNs = now
                        sumSquares = 0.0
                        squareCount = 0
                    }
                } else if (count < 0) {
                    status = "AudioRecord.read failed: $count"
                    Log.e(TAG, status)
                    break
                }
            }
        }, "weirdo-whatsapp-capture").apply {
            isDaemon = true
            start()
        }
    }

    private fun startInjectionThread() {
        injectionThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            var noiseSeed = 0x13579b
            val stereo = ShortArray(BLOCK_SAMPLES * 2)
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val mono = try {
                    outputQueue.poll(10, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }

                java.util.Arrays.fill(stereo, 0)
                if (mono == null) {
                    // Very low level dither avoids a perfectly flat zero stream on
                    // ROMs whose recorder stops treating an all-zero source as active.
                    for (i in 0 until BLOCK_SAMPLES) {
                        noiseSeed = noiseSeed * 1103515245 + 12345
                        val value = (((noiseSeed ushr 16) and 3) - 1).toShort()
                        stereo[i * 2] = value
                        stereo[i * 2 + 1] = value
                    }
                } else {
                    val count = minOf(mono.size, BLOCK_SAMPLES)
                    for (i in 0 until count) {
                        stereo[i * 2] = mono[i]
                        stereo[i * 2 + 1] = mono[i]
                    }
                }

                val track = injectionTrack ?: break
                var offset = 0
                while (offset < stereo.size && running.get()) {
                    val written = try {
                        track.write(stereo, offset, stereo.size - offset, AudioTrack.WRITE_BLOCKING)
                    } catch (error: Throwable) {
                        status = "AudioTrack write threw: ${error.message}"
                        Log.e(TAG, status, error)
                        -1
                    }
                    if (written <= 0) {
                        status = "AudioTrack.write failed: $written"
                        Log.e(TAG, status)
                        running.set(false)
                        break
                    }
                    offset += written
                }
            }
        }, "weirdo-whatsapp-injection").apply {
            isDaemon = true
            start()
        }
    }

    override fun pullInputPcm(waitMs: Int): ByteArray {
        if (!running.get()) return ByteArray(0)
        return try {
            micQueue.poll(waitMs.coerceIn(0, 25).toLong(), TimeUnit.MILLISECONDS) ?: ByteArray(0)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ByteArray(0)
        }
    }

    override fun pushOutputPcm(pcm16le: ByteArray?) {
        if (!running.get() || pcm16le == null || pcm16le.isEmpty()) return
        val usableBytes = minOf(pcm16le.size - (pcm16le.size % 2), BLOCK_SAMPLES * 2)
        if (usableBytes <= 0) return
        val samples = ShortArray(usableBytes / 2)
        for (i in samples.indices) {
            samples[i] = (
                (pcm16le[i * 2].toInt() and 0xff) or
                    (pcm16le[i * 2 + 1].toInt() shl 8)
                ).toShort()
        }
        if (!outputQueue.offer(samples)) {
            outputQueue.poll()
            if (!outputQueue.offer(samples)) droppedOutputBlocks++
            else droppedOutputBlocks++
        }
    }

    override fun getStatus(): String =
        "$status; targetRecording=$targetRecordingActive(source=$targetRecordingSource); " +
            "shellMicSilenced=$shellInputSilenced; micRms=${"%.1f".format(java.util.Locale.US, captureRmsDb)}dB; " +
            "micQueue=${micQueue.size}; outQueue=${outputQueue.size}; blocks=$captureBlocks; " +
            "dropIn=$droppedCaptureBlocks; dropOut=$droppedOutputBlocks"

    override fun stop() {
        val token = Binder.clearCallingIdentity()
        try {
            stopInternal()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun stopInternal() {
        if (!running.getAndSet(false) && policy == null && microphone == null && injectionTrack == null) {
            status = "idle"
            return
        }
        status = "stopping"
        micQueue.clear()
        outputQueue.clear()

        val rec = microphone
        microphone = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }

        val track = injectionTrack
        injectionTrack = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.stop() }
        runCatching { track?.release() }

        captureThread?.interrupt()
        injectionThread?.interrupt()
        runCatching { captureThread?.join(350) }
        runCatching { injectionThread?.join(350) }
        captureThread = null
        injectionThread = null

        runCatching { aec?.release() }
        runCatching { noiseSuppressor?.release() }
        aec = null
        noiseSuppressor = null

        val manager = audioManager
        val activePolicy = policy
        policy = null
        mix = null
        if (manager != null && activePolicy != null) {
            runCatching {
                val type = Class.forName("android.media.audiopolicy.AudioPolicy")
                AudioManager::class.java.getMethod("unregisterAudioPolicy", type)
                    .invoke(manager, activePolicy)
            }.onFailure { Log.w(TAG, "unregisterAudioPolicy failed", it) }
        }

        runCatching { policyHandlerThread?.quitSafely() }
        policyHandlerThread = null
        audioManager = null
        shellContext = null
        targetUid = -1
        status = "stopped"
        Log.i(TAG, "WhatsApp injection stopped; true microphone restored")
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        private val isShell = Process.myUid() == 2000

        override fun getPackageName(): String =
            if (isShell) "com.android.shell" else super.getPackageName()

        override fun getOpPackageName(): String =
            if (isShell) "com.android.shell" else super.getOpPackageName()

        override fun getAttributionSource(): AttributionSource {
            if (!isShell || Build.VERSION.SDK_INT < 31) return super.getAttributionSource()
            return AttributionSource.Builder(Process.myUid())
                .setPackageName("com.android.shell")
                .build()
        }

        override fun getApplicationContext(): Context = this
    }

    override fun exit() {
        stopInternal()
    }

    override fun destroy() {
        stopInternal()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
