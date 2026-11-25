package com.micplugin.service

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue

data class OutputDevice(val id: Int, val name: String, val type: Int)

object SoftwareLoopback {

    private const val TAG         = "MicPlugin.Loopback"
    private const val SAMPLE_RATE = 48000
    private const val FORMAT_FLOAT = AudioFormat.ENCODING_PCM_FLOAT
    private const val FORMAT_PCM16 = AudioFormat.ENCODING_PCM_16BIT
    private const val QUEUE_CAP   = 16  // max buffered frames before dropping

    private var monitorTrack:  AudioTrack?   = null
    private var monitorEncoding: Int = FORMAT_FLOAT
    private var workerThread:  Thread?       = null
    private var audioManager:  AudioManager? = null
    private var selectedDeviceId: Int        = -1
    private var monitorEnabled:   Boolean    = true
    private var running = false
    private var focusRequest: AudioFocusRequest? = null

    // Bounded hand-off queue. Keep the valid sample count with each pooled buffer;
    // pooled arrays can be larger than the current audio burst.
    private data class MonitorBlock(val samples: FloatArray, val sampleCount: Int)
    private val queue = ArrayBlockingQueue<MonitorBlock>(QUEUE_CAP)

    // Buffer pool — reuse allocations to avoid GC pressure every frame
    private val pool  = ArrayBlockingQueue<FloatArray>(QUEUE_CAP * 2)

    val isRunning: Boolean get() = running

    // ── Start / Stop ──────────────────────────────────────────────────────────

    fun start(context: Context) {
        if (running) return
        audioManager = context.getSystemService(AudioManager::class.java)

        // Respect the persisted monitor on/off choice on (re)start, even before the UI loads.
        monitorEnabled = context.getSharedPreferences("micup_prefs", Context.MODE_PRIVATE)
            .getBoolean("monitor_enabled", true)

        monitorTrack = buildMonitorTrack()
        if (monitorTrack == null) {
            Log.e(TAG, "AudioTrack init failed for PCM_FLOAT and PCM_16BIT")
            return
        }

        // Route before playback starts. In auto mode this prefers connected
        // headphones (including classic Bluetooth A2DP), then the phone speaker.
        applyOutputDevice()
        try {
            monitorTrack!!.setVolume(if (monitorEnabled) 1f else 0f)
            monitorTrack!!.play()
            if (monitorTrack!!.playState != AudioTrack.PLAYSTATE_PLAYING) {
                throw IllegalStateException("AudioTrack did not enter PLAYING state")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback start failed: $e")
            try { monitorTrack?.release() } catch (_: Exception) {}
            monitorTrack = null
            return
        }

        // This is a local sidetone monitor, not a phone-call route. Do not force
        // MODE_IN_COMMUNICATION or speakerphone state: that can send media audio
        // to the earpiece and make Bluetooth A2DP appear silent.
        running = true
        queue.clear()

        // Worker thread drains queue → writes to AudioTrack (blocking OK here)
        workerThread = Thread({
            Log.i(TAG, "Worker thread started encoding=$monitorEncoding")
            val pcm16 = ShortArray(1024)
            while (running) {
                try {
                    val block = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: continue
                    try {
                        if (monitorEnabled) {
                            val track = monitorTrack ?: continue
                            val written = if (monitorEncoding == FORMAT_FLOAT) {
                                track.write(block.samples, 0, block.sampleCount, AudioTrack.WRITE_BLOCKING)
                            } else {
                                for (i in 0 until block.sampleCount) {
                                    pcm16[i] = (block.samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                }
                                track.write(pcm16, 0, block.sampleCount, AudioTrack.WRITE_BLOCKING)
                            }
                            if (written < 0) Log.e(TAG, "AudioTrack write failed code=$written")
                        }
                    } finally {
                        pool.offer(block.samples)
                    }
                } catch (_: InterruptedException) { break }
                  catch (e: Exception) { Log.e(TAG, "Worker write error: $e") }
            }
            Log.i(TAG, "Worker thread stopped")
        }, "weirdo-Monitor-Worker")
        workerThread!!.priority = Thread.MAX_PRIORITY
        workerThread!!.start()

        requestAudioFocus()
        Log.i(TAG, "SoftwareLoopback started monitor=$monitorEnabled")
    }

    fun stop() {
        running = false
        queue.clear()
        pool.clear()
        workerThread?.interrupt()
        workerThread?.join(500)
        workerThread = null
        try { monitorTrack?.stop(); monitorTrack?.release() } catch (_: Exception) {}
        monitorTrack = null
        abandonAudioFocus()
        Log.i(TAG, "SoftwareLoopback stopped")
    }

    // ── Called from JNI callback (audio thread) ───────────────────────────────
    // Fast path: grab pooled buffer, copy, offer. Never alloc if pool has one.
    fun writeProcessed(buf: FloatArray, size: Int) {
        if (!running || !monitorEnabled) return
        // Reuse only buffers large enough for this burst. Never truncate a burst.
        var copy = pool.poll()
        if (copy == null || copy.size < size) {
            copy?.let { pool.offer(it) }
            copy = FloatArray(size)
        }
        System.arraycopy(buf, 0, copy, 0, size)

        val block = MonitorBlock(copy, size)
        // Drop-oldest: if queue full, evict its storage buffer and recycle it.
        if (!queue.offer(block)) {
            queue.poll()?.let { pool.offer(it.samples) }
            queue.offer(block)
        }
    }

    // ── Monitor toggle ────────────────────────────────────────────────────────

    fun setMonitorEnabled(enabled: Boolean) {
        monitorEnabled = enabled
        monitorTrack?.setVolume(if (enabled) 1f else 0f)
        if (!enabled) queue.clear()
        Log.i(TAG, "Monitor ${if (enabled) "ON" else "OFF"}")
    }

    fun setMicrophoneSidetone(mute: Boolean) {}
    fun onVoipCallStateChanged(active: Boolean) {}

    // ── Output device ─────────────────────────────────────────────────────────

    fun getOutputDevices(context: Context): List<OutputDevice> {
        val am = context.getSystemService(AudioManager::class.java)
        val list = mutableListOf(OutputDevice(-1, "Auto (best available)", -1))
        if (Build.VERSION.SDK_INT >= 23) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { d ->
                val name = when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE  -> "Earpiece"
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER   -> "Speaker"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET     -> "Wired Headset"
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES  -> "Wired Headphones"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO     -> "Bluetooth SCO"
                    AudioDeviceInfo.TYPE_BLE_HEADSET       -> "BLE Headset"
                    AudioDeviceInfo.TYPE_USB_HEADSET       -> "USB Headset"
                    else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                }
                list.add(OutputDevice(d.id, name, d.type))
            }
        }
        return list
    }

    fun setOutputDevice(context: Context, deviceId: Int) {
        selectedDeviceId = deviceId
        audioManager = audioManager ?: context.getSystemService(AudioManager::class.java)
        applyOutputDevice()
    }

    // ── Input device enumeration (fixes #5 — external sound cards had no way to
    //    be picked as the mic source; only the built-in mic was ever used) ─────

    fun getInputDevices(context: Context): List<OutputDevice> {
        val am = context.getSystemService(AudioManager::class.java)
        val list = mutableListOf(OutputDevice(-1, "Auto (system default)", -1))
        if (Build.VERSION.SDK_INT >= 23) {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d ->
                val name = when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC       -> "Built-in Mic"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET     -> "Wired Headset Mic"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO     -> "Bluetooth SCO Mic"
                    AudioDeviceInfo.TYPE_BLE_HEADSET       -> "BLE Headset Mic"
                    AudioDeviceInfo.TYPE_USB_HEADSET       -> "USB Headset Mic"
                    AudioDeviceInfo.TYPE_USB_DEVICE        -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB Audio Device"
                    AudioDeviceInfo.TYPE_LINE_ANALOG,
                    AudioDeviceInfo.TYPE_LINE_DIGITAL      -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Line-in"
                    else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                }
                list.add(OutputDevice(d.id, name, d.type))
            }
        }
        return list
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun applyOutputDevice() {
        if (Build.VERSION.SDK_INT < 23) return
        val am = audioManager ?: return
        try {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var dev: AudioDeviceInfo? = null
            var alreadySelected = false

            if (selectedDeviceId != -1) {
                dev = outputs.firstOrNull { it.id == selectedDeviceId }
            } else {
                // Try each compatible sink until AudioTrack accepts the preferred route.
                // A2DP is preferred over SCO for a media/sidetone monitor.
                val preferredTypes = intArrayOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                )
                for (type in preferredTypes) {
                    val candidate = outputs.firstOrNull { it.type == type } ?: continue
                    if (monitorTrack?.setPreferredDevice(candidate) == true) {
                        dev = candidate
                        alreadySelected = true
                        break
                    }
                }
            }

            // Prefer the loudspeaker for local testing when no headset route is usable.
            if (dev == null) {
                dev = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }

            if (dev != null) {
                val routed = alreadySelected || (monitorTrack?.setPreferredDevice(dev) ?: false)
                Log.i(TAG, "Monitor output route: ${dev.productName} type=${dev.type} selected=$routed")
                if (!routed) {
                    monitorTrack?.preferredDevice = null
                    Log.w(TAG, "Preferred route rejected; restored system audio routing")
                }
            } else {
                monitorTrack?.preferredDevice = null
                Log.w(TAG, "No preferred output device found; using system routing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyOutputDevice: $e")
        }
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT          -> monitorTrack?.setVolume(0f)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> monitorTrack?.setVolume(0.2f)
                AudioManager.AUDIOFOCUS_GAIN                    -> monitorTrack?.setVolume(if (monitorEnabled) 1f else 0f)
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(false).build()
            am.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26)
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") audioManager?.abandonAudioFocus(null)
        } catch (_: Exception) {}
    }

    /**
     * Prefer float playback, then fall back to PCM16 if the device rejects it.
     * AudioTrack.getMinBufferSize() already returns bytes.
     */
    private fun buildMonitorTrack(): AudioTrack? {
        val candidates = if (Build.VERSION.SDK_INT >= 26) {
            intArrayOf(FORMAT_FLOAT, FORMAT_PCM16)
        } else intArrayOf(FORMAT_PCM16)

        for (encoding in candidates) {
            val minBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, encoding
            )
            if (minBytes <= 0) {
                Log.w(TAG, "Monitor encoding=$encoding unsupported (buffer=$minBytes)")
                continue
            }

            val track = try {
                if (Build.VERSION.SDK_INT >= 26) {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(encoding)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(minBytes.coerceAtLeast(4096))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, FORMAT_PCM16,
                        minBytes.coerceAtLeast(4096), AudioTrack.MODE_STREAM)
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack construction failed encoding=$encoding: $e")
                null
            }

            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                monitorEncoding = if (Build.VERSION.SDK_INT < 26) FORMAT_PCM16 else encoding
                Log.i(TAG, "Monitor track initialized encoding=$monitorEncoding bytes=$minBytes")
                return track
            }
            try { track?.release() } catch (_: Exception) {}
            Log.w(TAG, "AudioTrack did not initialize for encoding=$encoding; trying fallback")
        }
        return null
    }
}
