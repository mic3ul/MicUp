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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class AudioProcessingService : Service() {

    companion object {
        const val CHANNEL_ID   = "weirdo_audio"
        const val NOTIF_ID     = 1001
        const val ACTION_STOP  = "com.micplugin.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AudioProcessingService::class.java)
            context.startForegroundService(intent)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, AudioProcessingService::class.java))
        }
    }

    @Inject lateinit var audioEngine: AudioEngine

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
                        "Active · $pluginCount plugins",
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
        }
        if (!updatesStarted) {
            startStatusUpdates()
            updatesStarted = true
        }
        if (!SoftwareLoopback.isRunning) SoftwareLoopback.start(this)
        if (started) startProcessedAudioPump()
        return START_STICKY
    }

    override fun onDestroy() {
        updatesStarted = false
        stopProcessedAudioPump()
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
