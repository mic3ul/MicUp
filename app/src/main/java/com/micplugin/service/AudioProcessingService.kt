package com.micplugin.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.audiofx.NoiseSuppressor
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.micplugin.MainActivity
import com.micplugin.R
import com.micplugin.audio.AudioEngine
import com.micplugin.ai.RvcLiveStreamProcessor
import com.micplugin.ai.RvcOnnxConverter
import android.net.Uri
import com.micplugin.inject.WhatsAppAudioInjector
import com.micplugin.inject.WhatsAppLiveState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class AudioProcessingService : Service() {

    companion object {
        const val CHANNEL_ID   = "weirdo_audio"
        const val NOTIF_ID     = 1001
        const val ACTION_STOP  = "com.micplugin.STOP"
        const val ACTION_WHATSAPP_LIVE_START = "com.micplugin.WHATSAPP_LIVE_START"

        fun start(context: Context) {
            val intent = Intent(context, AudioProcessingService::class.java)
            context.startForegroundService(intent)
        }
        fun startWhatsAppLive(context: Context) {
            val intent = Intent(context, AudioProcessingService::class.java).apply {
                action = ACTION_WHATSAPP_LIVE_START
            }
            context.startForegroundService(intent)
        }
        fun stopWhatsAppLive(context: Context) {
            context.stopService(Intent(context, AudioProcessingService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, AudioProcessingService::class.java))
        }
    }

    @Inject lateinit var audioEngine: AudioEngine
    @Inject lateinit var whatsAppAudioInjector: WhatsAppAudioInjector

    private var wakeLock: PowerManager.WakeLock? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun startStatusUpdates() {
        scope.launch {
            var failCount = 0
            while (isActive) {
                try {
                    val status = audioEngine.status.value
                    val pluginCount = audioEngine.pluginChain.slots.value.size
                    updateNotification(
                        if (whatsAppLiveActive) "WhatsApp live mic · UID restricted" else "Active · $pluginCount plugins",
                        pluginCount,
                        status.latencyMs,
                    )
                    failCount = 0
                } catch (e: Exception) {
                    failCount++
                    android.util.Log.e("weirdo.Service", "Status update failed #$failCount: $e")
                    // Re-pin notification so OEM can't kill it
                    try {
                        startForeground(NOTIF_ID, buildNotification("Active", 0, 0f))
                    } catch (_: Exception) {}
                }
                delay(5000)
            }
        }
    }

    private fun updateNotification(text: String, plugins: Int, latencyMs: Float) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, plugins, latencyMs))
    }

    private fun buildNotification(text: String, plugins: Int, latencyMs: Float): Notification {
        val mainIntent  = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stopIntent  = PendingIntent.getService(
            this, 1, Intent(this, AudioProcessingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)

        val latencyStr = if (latencyMs > 0f) " · ${latencyMs.toInt()}ms" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("weirdo Active")
            .setContentText("$text$latencyStr")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(mainIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "weirdo Audio",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Real-time audio processing service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "weirdo::AudioWakeLock"
        ).apply { acquire() } // No timeout — released explicitly in onDestroy
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Use the device's own supported voice denoiser when available.
     * This is optional platform DSP, not an assumed neural model.
     */
    private fun attachPlatformNoiseSuppressor() {
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null

            if (!NoiseSuppressor.isAvailable()) {
                android.util.Log.i("weirdo.Audio", "Platform NoiseSuppressor is not available")
                return
            }

            val sessionId = audioEngine.getInputSessionId()
            if (sessionId <= 0) {
                android.util.Log.w("weirdo.Audio", "No allocated input session for NoiseSuppressor")
                return
            }

            val effect = NoiseSuppressor.create(sessionId)
            if (effect == null) {
                android.util.Log.w("weirdo.Audio", "Platform NoiseSuppressor could not be created")
                return
            }

            val result = effect.setEnabled(true)
            noiseSuppressor = effect
            android.util.Log.i(
                "weirdo.Audio",
                "Platform NoiseSuppressor enabled result=$result hasControl=${effect.hasControl()}"
            )
        } catch (e: Exception) {
            android.util.Log.w("weirdo.Audio", "Platform NoiseSuppressor setup failed: $e")
            try { noiseSuppressor?.release() } catch (_: Exception) {}
            noiseSuppressor = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification("Starting…", 0, 0f),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, buildNotification("Starting…", 0, 0f))
            }
        } catch (e: Exception) {
            // Android 14+ throws SecurityException / ForegroundServiceStartNotAllowedException
            // when a microphone foreground service is started while the app is not in an
            // eligible (foreground) state. Fail gracefully instead of crashing the whole app;
            // MainActivity will (re)start the service from onResume() while in the foreground.
            android.util.Log.e("weirdo.Service", "startForeground(microphone) not allowed: $e")
            stopSelf()
            return
        }
        acquireWakeLock()
    }

    private var updatesStarted = false
    @Volatile private var audioPumpRunning = false
    private var audioPumpThread: Thread? = null

    @Volatile private var whatsAppLiveActive = false
    @Volatile private var whatsAppPumpRunning = false
    private var whatsAppPumpThread: Thread? = null
    @Volatile private var liveRvcProcessor: RvcLiveStreamProcessor? = null
    @Volatile private var liveRvcConverter: RvcOnnxConverter? = null

    private fun createLiveRvcProcessor(converter: RvcOnnxConverter): RvcLiveStreamProcessor {
        return RvcLiveStreamProcessor(
            converter = converter,
            onOutput = { chunk ->
                // A 300/500 ms neural chunk is emitted in one worker callback.
                // The native DSP and the Shizuku AIDL bridge consume 20 ms blocks.
                var offset = 0
                while (offset + 960 <= chunk.size) {
                    val frame = chunk.copyOfRange(offset, offset + 960)
                    audioEngine.processExternalPcm(frame)
                    val bytes = ByteArray(frame.size * 2)
                    for (i in frame.indices) {
                        val value = (frame[i].coerceIn(-1f, 1f) * 32767f).toInt()
                        bytes[i * 2] = value.toByte()
                        bytes[i * 2 + 1] = (value shr 8).toByte()
                    }
                    whatsAppAudioInjector.pushProcessedPcm(bytes)
                    offset += 960
                }
            },
            onStatus = { stats ->
                val averageBudget = if (converter.streamingStepFrames == 30) 300L else 500L
                val status = when {
                    stats.lastError != null -> "Live RVC error: ${stats.lastError}"
                    !stats.ready -> "Live RVC warming up · capturing 1 s of context"
                    stats.averageInferenceMs > averageBudget ->
                        "RVC is slower than the ${averageBudget} ms audio step · avg ${stats.averageInferenceMs} ms · " +
                            "${stats.windowsSkipped} windows skipped · this phone may stutter"
                    else -> "Live RVC · ${stats.inferenceMs} ms inference · avg ${stats.averageInferenceMs} ms · " +
                        "target ${averageBudget} ms · skipped ${stats.windowsSkipped}"
                }
                WhatsAppLiveState.setActive(true, status)
            },
        )
    }

    private fun startWhatsAppPcmPump() {
        if (whatsAppPumpThread?.isAlive == true) return
        whatsAppPumpRunning = true
        whatsAppPumpThread = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            var processedBlocks = 0L
            var lastNotice = System.nanoTime()
            while (whatsAppPumpRunning && !Thread.currentThread().isInterrupted) {
                val input = whatsAppAudioInjector.pullInputPcm(25)
                if (input.size < 2) {
                    try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                    continue
                }

                val sampleCount = input.size / 2
                val samples = FloatArray(sampleCount)
                for (i in 0 until sampleCount) {
                    val value = (input[i * 2].toInt() and 0xff) or (input[i * 2 + 1].toInt() shl 8)
                    samples[i] = value.toShort().toInt() / 32768f
                }

                val processor = liveRvcProcessor
                if (processor != null) {
                    // RVC inference runs on its own worker. This capture pump
                    // never waits for ONNX and therefore does not block AudioRecord.
                    processor.pushInput(samples)
                } else {
                    // Diagnostic fallback only; normal live mode requires RVC.
                    audioEngine.processExternalPcm(samples)
                    val output = ByteArray(sampleCount * 2)
                    for (i in 0 until sampleCount) {
                        val value = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                        output[i * 2] = value.toByte()
                        output[i * 2 + 1] = (value shr 8).toByte()
                    }
                    whatsAppAudioInjector.pushProcessedPcm(output)
                }
                processedBlocks++

                val now = System.nanoTime()
                if (now - lastNotice >= 5_000_000_000L) {
                    lastNotice = now
                    val remoteStatus = whatsAppAudioInjector.remoteStatus()
                    WhatsAppLiveState.setActive(
                        true,
                        "Live WhatsApp mic · ${processedBlocks * 20 / 1000}s captured\n$remoteStatus"
                    )
                    updateNotification(
                        "WhatsApp live mic · ${processedBlocks * 20 / 1000}s captured",
                        0,
                        20f,
                    )
                    android.util.Log.i("weirdo.WhatsAppLive", remoteStatus)
                }
            }
        }, "weirdo-WhatsApp-PCM-Pump").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopWhatsAppPcmPump() {
        whatsAppPumpRunning = false
        val thread = whatsAppPumpThread
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(350) }
        }
        whatsAppPumpThread = null
    }

    private suspend fun startWhatsAppLiveMode() {
        if (whatsAppLiveActive) return
        stopProcessedAudioPump()
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        audioEngine.stop()
        ProcessedAudioBridge.setEngineState(active = false)
        SoftwareLoopback.stop()

        // Load the selected RVC stream synthesizer and shared base models before
        // opening the WhatsApp route, so a model error cannot leave capture active.
        val prefs = getSharedPreferences("weirdo_ai_voice", MODE_PRIVATE)
        val synthUri = prefs.getString("synth_uri", null)?.let(Uri::parse)
        val embedderUri = prefs.getString("embedder_uri", null)?.let(Uri::parse)
        val rmvpeUri = prefs.getString("rmvpe_uri", null)?.let(Uri::parse)
        if (synthUri == null || embedderUri == null || rmvpeUri == null) {
            val error = "Select the T02 300/500 ms streaming Synth, ContentVec INT8 and RMVPE INT8 models in AI Voice Studio first."
            WhatsAppLiveState.setActive(false, error)
            updateNotification("Live RVC models not selected", 0, 0f)
            return
        }

        updateNotification("Loading live RVC models…", 0, 0f)
        val converter = try {
            RvcOnnxConverter.load(
                applicationContext,
                RvcOnnxConverter.ModelUris(synthUri, embedderUri, rmvpeUri)
            )
        } catch (error: Throwable) {
            val message = "RVC model load failed: ${error.message ?: error.javaClass.simpleName}"
            WhatsAppLiveState.setActive(false, message)
            updateNotification("Live RVC model error", 0, 0f)
            android.util.Log.e("weirdo.WhatsAppLive", message, error)
            return
        }

        if (!converter.isStreamingModel) {
            converter.close()
            val error = "The selected synthesizer is clip-only. Choose the Shadowww T02 300 ms or 500 ms streaming ONNX model."
            WhatsAppLiveState.setActive(false, error)
            updateNotification("Streaming RVC model required", 0, 0f)
            return
        }

        updateNotification("Connecting to Shizuku…", 0, 0f)
        val routeResult = whatsAppAudioInjector.startWhatsApp()
        if (!routeResult.startsWith("running:")) {
            converter.close()
            WhatsAppLiveState.setActive(false, routeResult)
            updateNotification(routeResult, 0, 0f)
            android.util.Log.e("weirdo.WhatsAppLive", routeResult)
            return
        }

        if (!audioEngine.startExternal()) {
            whatsAppAudioInjector.stopWhatsApp()
            converter.close()
            val error = "Native PCM processor could not start"
            WhatsAppLiveState.setActive(false, error)
            updateNotification(error, 0, 0f)
            return
        }

        val processor = try {
            createLiveRvcProcessor(converter).also { it.start() }
        } catch (error: Throwable) {
            whatsAppAudioInjector.stopWhatsApp()
            audioEngine.stop()
            converter.close()
            val message = "Live RVC stream setup failed: ${error.message ?: error.javaClass.simpleName}"
            WhatsAppLiveState.setActive(false, message)
            updateNotification("Live RVC setup failed", 0, 0f)
            android.util.Log.e("weirdo.WhatsAppLive", message, error)
            return
        }

        liveRvcConverter = converter
        liveRvcProcessor = processor
        whatsAppLiveActive = true
        WhatsAppLiveState.setActive(
            true,
            "Live RVC capturing · wait 1 second for the first converted window, then record a WhatsApp voice note."
        )
        startWhatsAppPcmPump()
        updateNotification("WhatsApp live RVC · UID restricted", 0, 20f)
        android.util.Log.i("weirdo.WhatsAppLive", "Live RVC route ready: $routeResult")
    }

    private suspend fun stopWhatsAppLiveMode() {
        stopWhatsAppPcmPump()
        whatsAppLiveActive = false
        runCatching { liveRvcProcessor?.close() }
        liveRvcProcessor = null
        runCatching { liveRvcConverter?.close() }
        liveRvcConverter = null
        runCatching { whatsAppAudioInjector.stopWhatsApp() }
        audioEngine.stop()
        WhatsAppLiveState.setActive(false, "WhatsApp live mic is off; physical microphone restored")
    }

    private suspend fun startStandardAudioMode() {
        if (whatsAppLiveActive) stopWhatsAppLiveMode()
        val started = audioEngine.start()
        if (started) attachPlatformNoiseSuppressor()
        else {
            try { noiseSuppressor?.release() } catch (_: Exception) {}
            noiseSuppressor = null
        }
        ProcessedAudioBridge.setEngineState(
            active = started,
            sampleRate = audioEngine.status.value.sampleRate,
        )
        if (!started) {
            android.util.Log.e("weirdo.Service", "Audio engine failed to start")
        } else {
            if (!SoftwareLoopback.isRunning) SoftwareLoopback.start(this)
            startProcessedAudioPump()
        }
    }

    private fun startProcessedAudioPump() {
        if (audioPumpThread?.isAlive == true) return
        audioPumpRunning = true
        audioPumpThread = Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            } catch (e: Exception) {
                android.util.Log.w("weirdo.Service", "Could not raise PCM pump priority: $e")
            }

            val samples = FloatArray(1024)
            while (audioPumpRunning && !Thread.currentThread().isInterrupted) {
                val frames = audioEngine.readProcessedAudio(samples)
                if (frames > 0) {
                    SoftwareLoopback.writeProcessed(samples, frames)
                    ProcessedAudioBridge.writeProcessed(samples, frames)
                } else {
                    try {
                        Thread.sleep(1)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "weirdo-Processed-Audio-Pump").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopProcessedAudioPump() {
        audioPumpRunning = false
        val thread = audioPumpThread
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        audioPumpThread = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!updatesStarted) {
            startStatusUpdates()
            updatesStarted = true
        }
        if (intent?.action == ACTION_WHATSAPP_LIVE_START) {
            scope.launch(Dispatchers.IO) { startWhatsAppLiveMode() }
            return START_STICKY
        }

        scope.launch(Dispatchers.IO) { startStandardAudioMode() }
        return START_STICKY
    }

    override fun onDestroy() {
        updatesStarted = false
        stopProcessedAudioPump()
        stopWhatsAppPcmPump()
        whatsAppLiveActive = false
        whatsAppAudioInjector.unbind()
        runCatching { liveRvcProcessor?.close() }
        liveRvcProcessor = null
        runCatching { liveRvcConverter?.close() }
        liveRvcConverter = null
        WhatsAppLiveState.setActive(false, "WhatsApp live mic is off; physical microphone restored")
        scope.cancel()
        ProcessedAudioBridge.setEngineState(active = false)
        try { noiseSuppressor?.release() } catch (e: Exception) {
            android.util.Log.w("weirdo.Audio", "NoiseSuppressor release failed: $e")
        }
        noiseSuppressor = null
        audioEngine.stop()
        SoftwareLoopback.stop()
        wakeLock?.release()
        super.onDestroy()
    }


}
