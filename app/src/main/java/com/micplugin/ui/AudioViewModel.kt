package com.micplugin.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.micplugin.ai.RvcOnnxConverter
import com.micplugin.ai.RvcWavIo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micplugin.audio.AudioEngine
import com.micplugin.audio.AudioLevels
import com.micplugin.plugin.*
import com.micplugin.preset.EffectState
import com.micplugin.preset.Preset
import com.micplugin.preset.PresetManager
import com.micplugin.plugin.PluginPathPrefs
import com.micplugin.service.ShizukuManager
import com.micplugin.service.SoftwareLoopback
import com.micplugin.service.ShizukuState
import com.micplugin.service.VirtualMicService
import com.micplugin.service.VirtualMicTier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import java.io.File
import java.io.IOException
import javax.inject.Inject

data class GateState(
    val enabled: Boolean = true,
    val thresholdDb: Float = -50f,
    val attackMs: Float = 5f,
    val releaseMs: Float = 150f,
)

data class EqState(
    val enabled: Boolean = true,
    val bands: List<Float> = List(10) { 0f },
)

data class CompState(
    val enabled: Boolean = true,
    val thresholdDb: Float = -18f,
    val ratio: Float = 4f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 100f,
    val makeupDb: Float = 0f,
)

data class ReverbState(
    val enabled: Boolean = false,
    val mix: Float = 0f,
    val roomSize: Float = 0.5f,
    val damping: Float = 0.5f,
)

data class PitchState(
    val enabled: Boolean = false,
    val semitones: Float = 0f,
)

enum class RvcModelSlot { SYNTHESIZER, CONTENT_ENCODER, PITCH_EXTRACTOR }

data class RvcStudioState(
    val synthUri: Uri? = null,
    val embedderUri: Uri? = null,
    val rmvpeUri: Uri? = null,
    val inputUri: Uri? = null,
    val isConverting: Boolean = false,
    val progress: Float = 0f,
    val isPreviewing: Boolean = false,
    val outputClip: RvcOnnxConverter.ConvertedAudio? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val modelsReady: Boolean get() = synthUri != null && embedderUri != null && rmvpeUri != null
}

@HiltViewModel
class AudioViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
    private val pluginManager: PluginManager,
    private val presetManager: PresetManager,
    private val virtualMicService: VirtualMicService,
    private val shizukuManager: ShizukuManager,
    private val pluginPathPrefs: PluginPathPrefs,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val levels:      StateFlow<AudioLevels>    = audioEngine.levels
    val engineStatus = audioEngine.status
    val pluginSlots: StateFlow<List<PluginSlot>> = audioEngine.pluginChain.slots
    val discovered:  StateFlow<List<PluginDescriptor>> = pluginManager.discovered
    val presets:     StateFlow<List<Preset>>   = presetManager.presets
    val virtualMicTier: StateFlow<VirtualMicTier> = virtualMicService.activeTier
    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state

    private val _gateState   = MutableStateFlow(GateState())
    private val _eqState     = MutableStateFlow(EqState())
    private val _compState   = MutableStateFlow(CompState())
    private val _reverbState = MutableStateFlow(ReverbState())
    private val _pitchState  = MutableStateFlow(PitchState())
    private val _masterBypass = MutableStateFlow(false)
    private val _vocalShine = MutableStateFlow(0f)

    val gateState:    StateFlow<GateState>    = _gateState
    val eqState:      StateFlow<EqState>      = _eqState
    val compState:    StateFlow<CompState>    = _compState
    val reverbState:  StateFlow<ReverbState>  = _reverbState
    val pitchState:   StateFlow<PitchState>   = _pitchState
    val masterBypass: StateFlow<Boolean>      = _masterBypass
    val vocalShine: StateFlow<Float> = _vocalShine

    private val _selectedPreset = MutableStateFlow<Preset?>(null)
    val selectedPreset: StateFlow<Preset?> = _selectedPreset

    private val aiPrefs = appContext.getSharedPreferences("weirdo_ai_voice", Context.MODE_PRIVATE)
    private val _rvcStudio = MutableStateFlow(
        RvcStudioState(
            synthUri = aiPrefs.getString("synth_uri", null)?.let(Uri::parse),
            embedderUri = aiPrefs.getString("embedder_uri", null)?.let(Uri::parse),
            rmvpeUri = aiPrefs.getString("rmvpe_uri", null)?.let(Uri::parse),
        )
    )
    val rvcStudio: StateFlow<RvcStudioState> = _rvcStudio
    private var rvcConverter: RvcOnnxConverter? = null
    private var loadedRvcModelKey: List<String>? = null
    private var rvcPreviewPlayer: MediaPlayer? = null

    init {
        viewModelScope.launch { presetManager.loadAll() }
        viewModelScope.launch { pluginManager.scanAll() }
    }

    // ── Neural RVC voice conversion (offline clips, not live mic processing) ──
    fun setRvcModel(slot: RvcModelSlot, uri: Uri) {
        if (_rvcStudio.value.isConverting) return
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val key = when (slot) {
            RvcModelSlot.SYNTHESIZER -> "synth_uri"
            RvcModelSlot.CONTENT_ENCODER -> "embedder_uri"
            RvcModelSlot.PITCH_EXTRACTOR -> "rmvpe_uri"
        }
        aiPrefs.edit().putString(key, uri.toString()).apply()
        closeRvcConverter()
        _rvcStudio.value = when (slot) {
            RvcModelSlot.SYNTHESIZER -> _rvcStudio.value.copy(synthUri = uri, outputClip = null, error = null, message = "Synthesizer model selected.")
            RvcModelSlot.CONTENT_ENCODER -> _rvcStudio.value.copy(embedderUri = uri, outputClip = null, error = null, message = "Content encoder selected.")
            RvcModelSlot.PITCH_EXTRACTOR -> _rvcStudio.value.copy(rmvpeUri = uri, outputClip = null, error = null, message = "Pitch extractor selected.")
        }
    }

    fun setRvcInput(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        _rvcStudio.value = _rvcStudio.value.copy(
            inputUri = uri, outputClip = null, error = null, message = "Source audio selected."
        )
    }

    fun convertRvcClip(pitchShiftSemitones: Int = 0) {
        val current = _rvcStudio.value
        if (current.isConverting) return
        val synthUri = current.synthUri
        val embedderUri = current.embedderUri
        val rmvpeUri = current.rmvpeUri
        val inputUri = current.inputUri
        if (synthUri == null || embedderUri == null || rmvpeUri == null || inputUri == null) {
            _rvcStudio.value = current.copy(error = "Choose the three RVC ONNX models and a source WAV first.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _rvcStudio.value = _rvcStudio.value.copy(
                isConverting = true, progress = 0f, outputClip = null,
                error = null, message = "Loading neural models…"
            )
            try {
                val clip = appContext.contentResolver.openInputStream(inputUri)?.use {
                    RvcWavIo.readPcm16(it)
                } ?: throw IOException("Cannot open the selected WAV file.")
                val modelKey = listOf(synthUri.toString(), embedderUri.toString(), rmvpeUri.toString())
                val converter = if (rvcConverter == null || loadedRvcModelKey != modelKey) {
                    closeRvcConverter()
                    RvcOnnxConverter.load(
                        appContext,
                        RvcOnnxConverter.ModelUris(synthUri, embedderUri, rmvpeUri)
                    ).also {
                        rvcConverter = it
                        loadedRvcModelKey = modelKey
                    }
                } else rvcConverter!!

                _rvcStudio.value = _rvcStudio.value.copy(message = "Converting voice with RVC…")
                val converted = withContext(Dispatchers.Default) {
                    converter.convert(clip.samples, clip.sampleRate, pitchShiftSemitones) { value ->
                        _rvcStudio.value = _rvcStudio.value.copy(progress = value)
                    }
                }
                _rvcStudio.value = _rvcStudio.value.copy(
                    isConverting = false,
                    progress = 1f,
                    outputClip = converted,
                    message = "AI voice conversion complete. Preview or save the WAV."
                )
            } catch (error: Throwable) {
                closeRvcConverter()
                _rvcStudio.value = _rvcStudio.value.copy(
                    isConverting = false,
                    progress = 0f,
                    outputClip = null,
                    error = error.message ?: error.javaClass.simpleName,
                    message = null
                )
            }
        }
    }

    // ── Preview the converted result locally before exporting it ─────────────
    fun previewRvcOutput() {
        val clip = _rvcStudio.value.outputClip ?: return
        viewModelScope.launch {
            try {
                stopRvcPreview()
                val previewFile = withContext(Dispatchers.IO) {
                    val file = File(appContext.cacheDir, "weirdo-ai-preview.wav")
                    file.outputStream().buffered().use { output ->
                        RvcWavIo.writePcm16Mono(output, clip)
                    }
                    file
                }
                val player = MediaPlayer()
                rvcPreviewPlayer = player
                player.setOnPreparedListener { ready ->
                    ready.start()
                    _rvcStudio.value = _rvcStudio.value.copy(isPreviewing = true, error = null)
                }
                player.setOnCompletionListener { completed ->
                    runCatching { completed.release() }
                    if (rvcPreviewPlayer === completed) rvcPreviewPlayer = null
                    _rvcStudio.value = _rvcStudio.value.copy(isPreviewing = false)
                }
                player.setOnErrorListener { failed, what, extra ->
                    runCatching { failed.release() }
                    if (rvcPreviewPlayer === failed) rvcPreviewPlayer = null
                    _rvcStudio.value = _rvcStudio.value.copy(
                        isPreviewing = false,
                        error = "Preview playback failed ($what/$extra)."
                    )
                    true
                }
                player.setDataSource(previewFile.absolutePath)
                player.prepareAsync()
            } catch (error: Throwable) {
                stopRvcPreview()
                _rvcStudio.value = _rvcStudio.value.copy(
                    isPreviewing = false,
                    error = error.message ?: "Could not preview converted audio."
                )
            }
        }
    }

    fun stopRvcPreview() {
        val player = rvcPreviewPlayer
        rvcPreviewPlayer = null
        if (player != null) {
            runCatching {
                if (player.isPlaying) player.stop()
            }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        if (_rvcStudio.value.isPreviewing) {
            _rvcStudio.value = _rvcStudio.value.copy(isPreviewing = false)
        }
    }

    fun saveRvcOutput(uri: Uri) {
        val clip = _rvcStudio.value.outputClip ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appContext.contentResolver.openOutputStream(uri, "w")?.use {
                    RvcWavIo.writePcm16Mono(it, clip)
                } ?: throw IOException("Cannot create the destination WAV file.")
                _rvcStudio.value = _rvcStudio.value.copy(message = "Converted WAV saved successfully.", error = null)
            } catch (error: Throwable) {
                _rvcStudio.value = _rvcStudio.value.copy(error = error.message ?: "Save failed.")
            }
        }
    }

    private fun closeRvcConverter() {
        runCatching { rvcConverter?.close() }
        rvcConverter = null
        loadedRvcModelKey = null
    }

    override fun onCleared() {
        stopRvcPreview()
        closeRvcConverter()
        super.onCleared()
    }

    // ── Engine control ─────────────────────────────────────────────────────────
    fun toggleEngine(context: android.content.Context) {
        if (engineStatus.value.isRunning) {
            com.micplugin.service.AudioProcessingService.stop(context)
        } else {
            com.micplugin.service.AudioProcessingService.start(context)
        }
    }

    fun setMasterBypass(bypass: Boolean) {
        _masterBypass.value = bypass
        audioEngine.setMasterBypass(bypass)
    }

    // ── Gate ──────────────────────────────────────────────────────────────────
    fun setGateEnabled(on: Boolean) {
        _gateState.value = _gateState.value.copy(enabled = on)
        audioEngine.setGateEnabled(on)
    }
    fun setGateThreshold(db: Float) {
        _gateState.value = _gateState.value.copy(thresholdDb = db)
        audioEngine.setGateThreshold(db)
    }
    fun setGateAttack(ms: Float) {
        _gateState.value = _gateState.value.copy(attackMs = ms)
        audioEngine.setGateAttack(ms)
    }
    fun setGateRelease(ms: Float) {
        _gateState.value = _gateState.value.copy(releaseMs = ms)
        audioEngine.setGateRelease(ms)
    }

    // ── EQ ────────────────────────────────────────────────────────────────────
    fun setEqEnabled(on: Boolean) {
        _eqState.value = _eqState.value.copy(enabled = on)
        audioEngine.setEqEnabled(on)
    }
    fun setEqBand(band: Int, db: Float) {
        val bands = _eqState.value.bands.toMutableList()
        bands[band] = db
        _eqState.value = _eqState.value.copy(bands = bands)
        audioEngine.setEqBand(band, db)
    }

    // ── Compressor ────────────────────────────────────────────────────────────
    fun setCompEnabled(on: Boolean) {
        _compState.value = _compState.value.copy(enabled = on)
        audioEngine.setCompEnabled(on)
    }
    fun setCompThreshold(db: Float) {
        _compState.value = _compState.value.copy(thresholdDb = db)
        audioEngine.setCompThreshold(db)
    }
    fun setCompRatio(ratio: Float) {
        _compState.value = _compState.value.copy(ratio = ratio)
        audioEngine.setCompRatio(ratio)
    }
    fun setCompAttack(ms: Float) {
        _compState.value = _compState.value.copy(attackMs = ms)
        audioEngine.setCompAttack(ms)
    }
    fun setCompRelease(ms: Float) {
        _compState.value = _compState.value.copy(releaseMs = ms)
        audioEngine.setCompRelease(ms)
    }
    fun setCompMakeup(db: Float) {
        _compState.value = _compState.value.copy(makeupDb = db)
        audioEngine.setCompMakeup(db)
    }

    // ── Reverb ────────────────────────────────────────────────────────────────
    fun setReverbEnabled(on: Boolean) {
        _reverbState.value = _reverbState.value.copy(enabled = on)
        audioEngine.setReverbEnabled(on)
    }
    fun setReverbMix(mix: Float) {
        _reverbState.value = _reverbState.value.copy(mix = mix)
        audioEngine.setReverbMix(mix)
    }
    fun setReverbRoom(size: Float) {
        _reverbState.value = _reverbState.value.copy(roomSize = size)
        audioEngine.setReverbRoom(size)
    }
    fun setReverbDamp(d: Float) {
        _reverbState.value = _reverbState.value.copy(damping = d)
        audioEngine.setReverbDamp(d)
    }

    // ── Pitch ─────────────────────────────────────────────────────────────────
    fun setPitchEnabled(on: Boolean) {
        _pitchState.value = _pitchState.value.copy(enabled = on)
        audioEngine.setPitchEnabled(on)
    }
    fun setPitchSemitones(st: Float) {
        _pitchState.value = _pitchState.value.copy(semitones = st)
        audioEngine.setPitchSemitones(st)
    }

    // Singing-oriented harmonic sheen; zero is a transparent bypass.
    fun setVocalShine(amount: Float) {
        val safeAmount = amount.coerceIn(0f, 1f)
        _vocalShine.value = safeAmount
        audioEngine.setVocalShine(safeAmount)
    }

    // ── Plugin chain ──────────────────────────────────────────────────────────
    fun addPlugin(descriptor: PluginDescriptor) {
        viewModelScope.launch {
            val handle = if (descriptor.format != PluginFormat.APK)
                audioEngine.loadNativePlugin(descriptor.path, descriptor.format) else 0L
            val slot = PluginSlot(descriptor = descriptor, nativeHandle = handle)
            audioEngine.pluginChain.addPlugin(slot)
        }
    }

    fun removePlugin(id: UUID) {
        val slot = audioEngine.pluginChain.getSlot(id) ?: return
        if (slot.nativeHandle != 0L) audioEngine.unloadNativePlugin(slot.nativeHandle)
        audioEngine.pluginChain.removePlugin(id)
    }

    fun togglePlugin(id: UUID) = audioEngine.pluginChain.toggleEnabled(id)

    fun reorderPlugin(from: Int, to: Int) = audioEngine.pluginChain.reorder(from, to)

    fun setPluginParam(slotId: UUID, paramId: Int, value: Float) {
        val slot = audioEngine.pluginChain.getSlot(slotId) ?: return
        if (slot.nativeHandle != 0L)
            audioEngine.setNativePluginParam(slot.nativeHandle, paramId, value)
        audioEngine.pluginChain.updateParam(slotId, paramId, value)
    }

    // ── Presets ───────────────────────────────────────────────────────────────
    fun applyPreset(preset: Preset) {
        _selectedPreset.value = preset
        val e = preset.effects
        setGateEnabled(e.gateEnabled);     setGateThreshold(e.gateThreshold)
        setGateAttack(e.gateAttack);       setGateRelease(e.gateRelease)
        setEqEnabled(e.eqEnabled);         e.eqBands.forEachIndexed { i, db -> setEqBand(i, db) }
        setCompEnabled(e.compEnabled);     setCompThreshold(e.compThreshold)
        setCompRatio(e.compRatio);         setCompAttack(e.compAttack)
        setCompRelease(e.compRelease);     setCompMakeup(e.compMakeup)
        setReverbEnabled(e.reverbEnabled); setReverbMix(e.reverbMix)
        setReverbRoom(e.reverbRoom);       setReverbDamp(e.reverbDamp)
        setPitchEnabled(e.pitchEnabled);   setPitchSemitones(e.pitchSemitones)
        setVocalShine(e.vocalShine)
        _gateState.value   = GateState(e.gateEnabled, e.gateThreshold, e.gateAttack, e.gateRelease)
        _eqState.value     = EqState(e.eqEnabled, e.eqBands)
        _compState.value   = CompState(e.compEnabled, e.compThreshold, e.compRatio, e.compAttack, e.compRelease, e.compMakeup)
        _reverbState.value = ReverbState(e.reverbEnabled, e.reverbMix, e.reverbRoom, e.reverbDamp)
        _pitchState.value  = PitchState(e.pitchEnabled, e.pitchSemitones)
    }

    fun savePreset(name: String) {
        val preset = Preset(
            name    = name,
            effects = EffectState(
                gateEnabled   = _gateState.value.enabled,
                gateThreshold = _gateState.value.thresholdDb,
                gateAttack    = _gateState.value.attackMs,
                gateRelease   = _gateState.value.releaseMs,
                eqEnabled     = _eqState.value.enabled,
                eqBands       = _eqState.value.bands,
                compEnabled   = _compState.value.enabled,
                compThreshold = _compState.value.thresholdDb,
                compRatio     = _compState.value.ratio,
                compAttack    = _compState.value.attackMs,
                compRelease   = _compState.value.releaseMs,
                compMakeup    = _compState.value.makeupDb,
                reverbEnabled = _reverbState.value.enabled,
                reverbMix     = _reverbState.value.mix,
                reverbRoom    = _reverbState.value.roomSize,
                reverbDamp    = _reverbState.value.damping,
                pitchEnabled  = _pitchState.value.enabled,
                pitchSemitones = _pitchState.value.semitones,
                vocalShine = _vocalShine.value,
            ),
        )
        viewModelScope.launch { presetManager.save(preset) }
    }

    fun rescan() { viewModelScope.launch { pluginManager.scanAll() } }

    fun requestShizukuPermission() { shizukuManager.requestPermission() }

    /** Returns null if available, or a reason string if not. */
    fun checkTierAvailability(tier: com.micplugin.service.VirtualMicTier): String? =
        virtualMicService.checkTierAvailability(tier)

    /** Manually switch to a tier. Returns unavailability reason or null on success. */
    fun setTier(tier: com.micplugin.service.VirtualMicTier): String? =
        virtualMicService.setTier(tier)

    val lv2Paths  = pluginPathPrefs.lv2Paths
    val clapPaths = pluginPathPrefs.clapPaths
    val vst3Paths = pluginPathPrefs.vst3Paths

    fun addPluginPath(context: android.content.Context, format: com.micplugin.plugin.PluginFormat, path: String) {
        pluginPathPrefs.addPath(context, format, path)
    }
    fun removePluginPath(context: android.content.Context, format: com.micplugin.plugin.PluginFormat, path: String) {
        pluginPathPrefs.removePath(context, format, path)
    }
    fun allPluginPaths(context: android.content.Context, format: com.micplugin.plugin.PluginFormat) =
        pluginPathPrefs.allPaths(context, format)


    private val _monitoringEnabled = MutableStateFlow(true)
    val monitoringEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> = _monitoringEnabled

    fun setMonitoring(context: android.content.Context, enabled: Boolean) {
        _monitoringEnabled.value = enabled
        SoftwareLoopback.setMonitorEnabled(enabled)
        // Remember the choice so it survives app/process restarts.
        context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("monitor_enabled", enabled).apply()
    }

    fun loadMonitoring(context: android.content.Context) {
        val saved = context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("monitor_enabled", true)
        _monitoringEnabled.value = saved
        SoftwareLoopback.setMonitorEnabled(saved)
    }


    fun getPluginParamsJson(nativeHandle: Long): String =
        audioEngine.getPluginParamsJson(nativeHandle)


    private val _outputDevices = MutableStateFlow<List<com.micplugin.service.OutputDevice>>(emptyList())
    val outputDevices: kotlinx.coroutines.flow.StateFlow<List<com.micplugin.service.OutputDevice>> = _outputDevices

    private val _selectedOutputDeviceId = MutableStateFlow(-1)
    val selectedOutputDeviceId: kotlinx.coroutines.flow.StateFlow<Int> = _selectedOutputDeviceId

    fun loadOutputDevices(context: android.content.Context) {
        _outputDevices.value = SoftwareLoopback.getOutputDevices(context)
        // Restore saved selection
        val saved = context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("output_device_id", -1)
        _selectedOutputDeviceId.value = saved
        SoftwareLoopback.setOutputDevice(context, saved)
    }

    fun setOutputDevice(context: android.content.Context, deviceId: Int) {
        _selectedOutputDeviceId.value = deviceId
        SoftwareLoopback.setOutputDevice(context, deviceId)
        // Persist selection
        context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putInt("output_device_id", deviceId).apply()
    }

    // ── Input device (fixes #5 — external sound cards were stuck on the built-in mic) ──

    private val _inputDevices = MutableStateFlow<List<com.micplugin.service.OutputDevice>>(emptyList())
    val inputDevices: kotlinx.coroutines.flow.StateFlow<List<com.micplugin.service.OutputDevice>> = _inputDevices

    private val _selectedInputDeviceId = MutableStateFlow(-1)
    val selectedInputDeviceId: kotlinx.coroutines.flow.StateFlow<Int> = _selectedInputDeviceId

    fun loadInputDevices(context: android.content.Context) {
        _inputDevices.value = SoftwareLoopback.getInputDevices(context)
        val saved = context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("input_device_id", -1)
        _selectedInputDeviceId.value = saved
        audioEngine.setInputDevice(saved)
    }

    fun setInputDevice(context: android.content.Context, deviceId: Int) {
        _selectedInputDeviceId.value = deviceId
        audioEngine.setInputDevice(deviceId)
        context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putInt("input_device_id", deviceId).apply()
    }

}
