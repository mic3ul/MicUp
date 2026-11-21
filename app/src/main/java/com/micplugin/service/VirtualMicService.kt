package com.micplugin.service

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class VirtualMicTier {
    VOIP_STREAM, MEDIA_PROJECTION, SHIZUKU_ADB, ROOT_MAGISK;

    val displayName: String get() = when (this) {
        VOIP_STREAM      -> "Local DSP Monitor"
        MEDIA_PROJECTION -> "MediaProjection (not mic injection)"
        SHIZUKU_ADB      -> "Shizuku ALSA (not implemented)"
        ROOT_MAGISK      -> "Root / Magisk (not configured)"
    }
    val description: String get() = when (this) {
        VOIP_STREAM      -> "Processes the device microphone and plays the result through MicUp's local monitor. It does not replace another app's microphone."
        MEDIA_PROJECTION -> "MediaProjection captures eligible playback; it cannot replace WhatsApp's microphone input."
        SHIZUKU_ADB      -> "Experimental only. This build has no PCM writer connected to an ALSA capture device or Android audio input policy."
        ROOT_MAGISK      -> "Root alone does not create a virtual microphone. This fork has no verified Magisk module or AudioPolicy integration."
    }
    val badgeColorHex: Long get() = when (this) {
        VOIP_STREAM      -> 0xFF3DFCACL
        MEDIA_PROJECTION -> 0xFF7C5CFCL
        SHIZUKU_ADB      -> 0xFF00B4D8L
        ROOT_MAGISK      -> 0xFFFFD700L
    }
}

@Singleton
class VirtualMicService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
) {
    companion object {
        private const val TAG = "VirtualMicService"
        private const val MAGISK_MODULE_ASSET = "micplugin_routing.zip"

        fun isRooted(): Boolean {
            val suPaths = listOf(
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/su/bin/su", "/magisk/.core/bin/su", "/data/local/xbin/su",
                "/data/adb/magisk/busybox", "/data/adb/ksu/bin/su",
            )
            if (suPaths.any { File(it).exists() }) return true
            // Try executing su — catches cases where file exists but path is hidden
            return try {
                val p = findSuBinary()?.let {
                    Runtime.getRuntime().exec(arrayOf(it, "-c", "id"))
                } ?: Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.contains("uid=0")
            } catch (_: Exception) { false }
        }

        /** Returns first working su binary path, tries Magisk/KernelSU/standard paths */
        fun findSuBinary(): String? {
            val candidates = listOf(
                "/data/adb/magisk/busybox",
                "/data/adb/ksu/bin/su",
                "/data/adb/magisk/magisk",
                "/sbin/.magisk/bin/busybox",
                "/system/xbin/su",
                "/system/bin/su",
                "su",
            )
            return candidates.firstOrNull { path ->
                try {
                    if (path == "su") {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).let { p ->
                            val ok = p.inputStream.bufferedReader().readText().contains("uid=0")
                            p.destroy(); ok
                        }
                    } else File(path).exists()
                } catch (_: Exception) { false }
            }
        }

        fun execRoot(cmd: String): String? {
            val su = findSuBinary() ?: return null
            return try {
                val args = if (su.contains("busybox")) arrayOf(su, "sh", "-c", cmd)
                           else arrayOf(su, "-c", cmd)
                val p = Runtime.getRuntime().exec(args)
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out
            } catch (_: Exception) { null }
        }

        // ── ALSA loopback support probe, root path (fixes #2) ───────────────────
        // Same rationale as ShizukuManager.hasAlsaLoopbackSupport(): being rooted and having
        // the Magisk module "installed" doesn't guarantee the kernel actually exposes a
        // loopback card (some MediaTek chipsets ship without snd-aloop support at all, and a
        // module install typically also needs a reboot to take effect). Check the ground
        // truth in /proc/asound/cards rather than trusting isRooted() alone.
        private var rootLoopbackCache: Boolean? = null
        private var rootLoopbackCacheAt: Long = 0L

        fun hasAlsaLoopbackSupportRoot(forceRecheck: Boolean = false): Boolean {
            val now = System.currentTimeMillis()
            if (!forceRecheck) {
                rootLoopbackCache?.let { if (now - rootLoopbackCacheAt < 5000) return it }
            }
            // Same rationale as ShizukuManager.hasAlsaLoopbackSupport(): try loading the
            // module first so a device that supports it but hasn't loaded it yet isn't
            // misreported as unsupported. Idempotent if already loaded.
            execRoot("modprobe snd-aloop pcm_substreams=2 2>&1 || insmod /system/lib/modules/snd-aloop.ko pcm_substreams=2 2>&1")
            val out = execRoot("cat /proc/asound/cards 2>/dev/null")
            val supported = out?.contains("Loopback", ignoreCase = true) == true
            rootLoopbackCache = supported
            rootLoopbackCacheAt = now
            return supported
        }
    }

    private val _activeTier = MutableStateFlow(VirtualMicTier.VOIP_STREAM)
    val activeTier: StateFlow<VirtualMicTier> = _activeTier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Initial best-effort detection (Shizuku may not be ready yet — see collector below)
        detectAndSetBestTier()
        // React whenever Shizuku state changes (fixes race: init runs before shizukuManager.init())
        scope.launch {
            shizukuManager.state.collect { state ->
                if (state == ShizukuState.READY &&
                    _activeTier.value != VirtualMicTier.ROOT_MAGISK &&
                    _activeTier.value != VirtualMicTier.SHIZUKU_ADB
                ) {
                    Log.i(TAG, "Shizuku became READY — upgrading tier to SHIZUKU_ADB")
                    onShizukuReady()
                }
            }
        }
    }

    private fun detectAndSetBestTier() {
        // Only the local monitor path is implemented and verified in this build.
        // Root/Shizuku presence does not prove an Android input route exists.
        _activeTier.value = VirtualMicTier.VOIP_STREAM
        Log.i(TAG, "Audio route: local processed monitor (no system mic injection)")
    }

    /** Called after Shizuku permission is granted so we re-evaluate tier */
    fun onShizukuReady() {
        // Shizuku is an execution/permission mechanism, not a virtual microphone.
        // Do not advertise an unverified ALSA path as active.
        Log.i(TAG, "Shizuku is ready; system microphone injection remains unavailable")
    }

    /**
     * Returns true if Shizuku-level ALSA loopback routing is actually working, false if this
     * device's kernel doesn't support it — in which case the tier is dropped back to
     * MEDIA_PROJECTION (Software Loopback), which works on every device. See #2: previously
     * this always "succeeded" from the app's point of view — the monitor kept working, so the
     * user had no way to know other apps were still hearing dry/unprocessed audio.
     */
    private fun activateShizukuRouting(): Boolean {
        // hasAlsaLoopbackSupport() attempts modprobe/insmod itself and verifies against
        // /proc/asound/cards — the actual ground truth, rather than trusting the modprobe
        // command's exit text the way the old code did.
        val working = shizukuManager.hasAlsaLoopbackSupport(forceRecheck = true)
        val routed = if (working) shizukuManager.routeToLoopback() else false
        shizukuManager.setAppOpsMicDefault(context.packageName)
        Log.i(TAG, "ALSA loopback kernel support=$working routed=$routed")
        if (!working || !routed) {
            Log.w(TAG, "ALSA loopback not available on this device's kernel (missing snd-aloop?) " +
                    "— falling back to Software Loopback")
            _activeTier.value = VirtualMicTier.MEDIA_PROJECTION
        }
        return working && routed
    }

    fun getActiveTier(): VirtualMicTier = _activeTier.value
    fun getStatusDescription(): String = _activeTier.value.description

    /**
     * Returns null if the tier is available, or a human-readable reason string if not.
     */
    fun checkTierAvailability(tier: VirtualMicTier): String? = when (tier) {
        VirtualMicTier.VOIP_STREAM -> null
        VirtualMicTier.MEDIA_PROJECTION ->
            "Unavailable: MediaProjection captures playback, not microphone input from MicUp."
        VirtualMicTier.SHIZUKU_ADB ->
            "Unavailable: this build has no end-to-end PCM writer to ALSA loopback and no Android input-device registration."
        VirtualMicTier.ROOT_MAGISK ->
            "Unavailable: this fork does not include a verified Magisk module or AudioPolicy/device integration."
    }

    /**
     * Manually select a tier. No-ops silently if the tier isn't available.
     * Returns the unavailability reason if blocked, null on success.
     */
    fun setTier(tier: VirtualMicTier): String? {
        val reason = checkTierAvailability(tier)
        if (reason != null) return reason
        _activeTier.value = tier
        if (tier == VirtualMicTier.SHIZUKU_ADB) activateShizukuRouting()
        Log.i(TAG, "Tier manually set to $tier")
        return null
    }

    fun requestUpgrade(activity: Activity) {
        Log.i(TAG, "Privileged audio routing is disabled until an end-to-end route is implemented and verified")
    }

    private fun installMagiskModule(context: Context) {
        try {
            val outFile = File(context.cacheDir, MAGISK_MODULE_ASSET)
            context.assets.open(MAGISK_MODULE_ASSET).use { i -> outFile.outputStream().use { o -> i.copyTo(o) } }
            execRoot("magisk --install-module ${outFile.absolutePath}") ?: execRoot("/data/adb/magisk/magisk --install-module ${outFile.absolutePath}")
            Log.i(TAG, "Magisk module extraction triggered")
        } catch (e: Exception) {
            Log.e(TAG, "Magisk module install failed: $e")
        }
    }
}
