package com.micplugin.ai

import android.content.Context
import android.net.Uri
import android.util.Half
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Offline RVC ONNX conversion.
 *
 * This implementation consumes the standard Voice Changer ONNX export trio:
 * a speaker synthesizer, a ContentVec/HuBERT embedder, and (for F0 models)
 * an RMVPE pitch extractor. No third-party application source is bundled.
 *
 * Inference is clip-based, not real-time. Keep inputs short on phones with
 * limited RAM; 30 seconds is the supported upper bound for this mobile build.
 */
class RvcOnnxConverter private constructor(
    private val synth: OrtSession,
    private val embedder: OrtSession,
    private val rmvpe: OrtSession?,
    private val metadata: Metadata,
    private val outputName: String,
) : Closeable {

    data class ModelUris(val synth: Uri, val embedder: Uri, val rmvpe: Uri)

    data class Metadata(
        val sampleRate: Int,
        val usesPitch: Boolean,
        val embeddingChannels: Int,
        val embedderName: String,
        val outputLayer: Int,
        val useFinalProjection: Boolean,
    )

    data class ConvertedAudio(val samples: FloatArray, val sampleRate: Int)

    companion object {
        const val MAX_INPUT_SECONDS = 30
        private const val MAX_ONNX_BYTES = 800L * 1024L * 1024L
        private const val TAG = "WeirdoRVC"

        fun load(context: Context, uris: ModelUris): RvcOnnxConverter {
            val app = context.applicationContext
            val synthFile = cacheModel(app, uris.synth, "rvc-synth")
            val embedFile = cacheModel(app, uris.embedder, "rvc-embedder")
            val pitchFile = cacheModel(app, uris.rmvpe, "rvc-rmvpe")

            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }

            var synthSession: OrtSession? = null
            var embedSession: OrtSession? = null
            var pitchSession: OrtSession? = null
            try {
                synthSession = env.createSession(synthFile.absolutePath, options)
                embedSession = env.createSession(embedFile.absolutePath, options)
                val meta = readMetadata(synthSession)
                require(meta.usesPitch) {
                    "This build currently requires an F0-guided RVC voice model."
                }
                pitchSession = env.createSession(pitchFile.absolutePath, options)

                require("audio" in embedSession.inputInfo) {
                    "The ContentVec model must expose an 'audio' input."
                }
                require("waveform" in pitchSession.inputInfo && "threshold" in pitchSession.inputInfo) {
                    "The RMVPE model must expose 'waveform' and 'threshold' inputs."
                }
                require("feats" in synthSession.inputInfo && "audio" in synthSession.outputInfo) {
                    "The RVC synthesizer must expose 'feats' input and 'audio' output."
                }

                val output = when {
                    meta.outputLayer == 12 && !meta.useFinalProjection -> "unit12"
                    meta.outputLayer == 9 && meta.useFinalProjection -> "units9"
                    meta.outputLayer == 12 && meta.useFinalProjection -> "unit12s"
                    else -> error(
                        "Unsupported embedder layer settings: layer=${meta.outputLayer}, " +
                            "finalProjection=${meta.useFinalProjection}"
                    )
                }
                require(output in embedSession.outputInfo) {
                    "ContentVec is missing '$output' output. Available: ${embedSession.outputInfo.keys}"
                }

                return RvcOnnxConverter(
                    synthSession, embedSession, pitchSession, meta, output
                )
            } catch (error: Throwable) {
                runCatching { pitchSession?.close() }
                runCatching { embedSession?.close() }
                runCatching { synthSession?.close() }
                throw error
            } finally {
                options.close()
            }
        }

        private fun readMetadata(session: OrtSession): Metadata {
            val raw = session.metadata.customMetadata["metadata"]
                ?: error("The synthesizer has no embedded RVC metadata. Export it with compatible Voice Changer tooling.")
            val json = org.json.JSONObject(raw)
            val result = Metadata(
                sampleRate = json.getInt("samplingRate"),
                usesPitch = json.getBoolean("f0"),
                embeddingChannels = json.getInt("embChannels"),
                embedderName = json.getString("embedder"),
                outputLayer = json.getInt("embOutputLayer"),
                useFinalProjection = json.optBoolean("useFinalProj", false),
            )
            require(result.sampleRate in 32000..48000) {
                "Unsupported synthesizer sample rate: ${result.sampleRate}"
            }
            require(result.embeddingChannels in 1..1024) {
                "Invalid embedding size: ${result.embeddingChannels}"
            }
            return result
        }

        private fun cacheModel(context: Context, uri: Uri, prefix: String): File {
            val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            require(size in 1..MAX_ONNX_BYTES) {
                "$prefix model size is unavailable or exceeds the 800 MiB safety limit."
            }
            val key = Integer.toHexString(uri.toString().hashCode())
            val file = File(context.filesDir, "ai-models/$prefix-$key.onnx")
            if (file.exists() && file.length() == size) return file
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".part")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output, 128 * 1024) }
            } ?: error("Cannot read selected model file: $uri")
            require(temporary.length() == size) { "Model copy was incomplete." }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            return file
        }
    }

    /**
     * Convert mono or downmixed audio to the target RVC voice.
     * pitchShiftSemitones is deliberately clamped to a small range; large
     * transpositions can damage singing formants and consonant timing.
     */
    fun convert(
        input: FloatArray,
        inputSampleRate: Int,
        pitchShiftSemitones: Int = 0,
        speakerId: Long = 0L,
        progress: (Float) -> Unit = {},
    ): ConvertedAudio {
        require(input.isNotEmpty()) { "The selected audio is empty." }
        require(input.size <= inputSampleRate * MAX_INPUT_SECONDS) {
            "This mobile build accepts clips up to $MAX_INPUT_SECONDS seconds."
        }
        require(inputSampleRate in 8000..96000) { "Unsupported input sample rate." }
        require(pitchShiftSemitones in -5..5) { "Pitch adjustment must be between -5 and +5 semitones." }

        val source16k = WindowedSincResampler.resample(input, inputSampleRate, 16000)
        require(source16k.size >= 16000) { "Recordings must be at least one second long." }
        progress(0.05f)

        val features = runEmbedder(source16k)
        progress(0.42f)

        val pitch = runPitch(source16k, pitchShiftSemitones)
        progress(0.62f)

        val frames2x = features.frames * 2
        val expanded = FloatArray(frames2x * features.channels)
        for (frame in 0 until features.frames) {
            val srcOffset = frame * features.channels
            val dstOffset = frame * 2 * features.channels
            System.arraycopy(features.values, srcOffset, expanded, dstOffset, features.channels)
            System.arraycopy(features.values, srcOffset, expanded, dstOffset + features.channels, features.channels)
        }

        val alignedFrames = min(frames2x, pitch.first.size)
        require(alignedFrames > 0) { "RVC models returned no aligned frames." }
        val alignedFeats = if (alignedFrames == frames2x) expanded else expanded.copyOf(alignedFrames * features.channels)
        val pitchCoarse = pitch.second.copyOf(alignedFrames)
        val pitchFine = pitch.first.copyOf(alignedFrames)
        progress(0.72f)

        val output = runSynthesizer(
            alignedFeats,
            alignedFrames,
            features.channels,
            pitchCoarse,
            pitchFine,
            speakerId,
        )
        progress(1f)
        return ConvertedAudio(output, metadata.sampleRate)
    }

    private data class Features(val values: FloatArray, val frames: Int, val channels: Int)

    private fun runEmbedder(audio16k: FloatArray): Features {
        val input = floatTensor(audio16k, longArrayOf(1, audio16k.size.toLong()))
        try {
            embedder.run(mapOf("audio" to input), setOf(outputName)).use { result ->
                val tensor = result.get(outputName).orElseThrow { IllegalStateException("ContentVec output is missing.") } as OnnxTensor
                val shape = (tensor.info as TensorInfo).shape
                require(shape.size == 3 && shape[0] == 1L) {
                    "Expected ContentVec output [1,T,C], got ${shape.contentToString()}."
                }
                val frames = shape[1].toInt()
                val channels = shape[2].toInt()
                require(channels == metadata.embeddingChannels) {
                    "Embedding dimension mismatch: model expects ${metadata.embeddingChannels}, got $channels."
                }
                return Features(tensor.copyFloatValues(), frames, channels)
            }
        } finally {
            input.close()
        }
    }

    private fun runPitch(audio16k: FloatArray, shift: Int): Pair<FloatArray, LongArray> {
        val session = requireNotNull(rmvpe)
        val waveform = floatTensor(audio16k, longArrayOf(1, audio16k.size.toLong()))
        val threshold = floatTensor(floatArrayOf(0.3f), longArrayOf(1))
        try {
            session.run(mapOf("waveform" to waveform, "threshold" to threshold)).use { result ->
                val outputName = session.outputInfo.keys.first()
                val tensor = result.get(outputName).orElseThrow { IllegalStateException("RMVPE output is missing.") } as OnnxTensor
                val raw = tensor.copyFloatValues()
                val factor = 2.0.pow(shift / 12.0).toFloat()
                val fine = if (shift == 0) raw else FloatArray(raw.size) { raw[it] * factor }
                return fine to quantizePitch(fine)
            }
        } finally {
            waveform.close()
            threshold.close()
        }
    }

    private fun runSynthesizer(
        features: FloatArray,
        frameCount: Int,
        channels: Int,
        pitchCoarse: LongArray,
        pitchFine: FloatArray,
        speakerId: Long,
    ): FloatArray {
        require("p_len" in synth.inputInfo && "sid" in synth.inputInfo &&
            "pitch" in synth.inputInfo && "pitchf" in synth.inputInfo) {
            "The selected RVC model is missing one or more required F0 inputs."
        }

        val shape3d = longArrayOf(1, frameCount.toLong(), channels.toLong())
        val shape2d = longArrayOf(1, frameCount.toLong())
        val inputs = linkedMapOf<String, OnnxTensor>()
        try {
            val featsType = ((synth.inputInfo["feats"]?.info) as? TensorInfo)?.type
            inputs["feats"] = if (featsType == OnnxJavaType.FLOAT16) {
                float16Tensor(features, shape3d)
            } else {
                floatTensor(features, shape3d)
            }
            inputs["p_len"] = longTensor(longArrayOf(frameCount.toLong()), longArrayOf(1))
            inputs["sid"] = longTensor(longArrayOf(speakerId), longArrayOf(1))
            inputs["pitch"] = longTensor(pitchCoarse, shape2d)
            inputs["pitchf"] = floatTensor(pitchFine, shape2d)

            synth.run(inputs, setOf("audio")).use { result ->
                val tensor = result.get("audio").orElseThrow { IllegalStateException("Synthesizer audio output is missing.") } as OnnxTensor
                val output = tensor.copyFloatValues()
                require(output.isNotEmpty()) { "The AI synthesizer returned empty audio." }
                for (i in output.indices) output[i] = output[i].coerceIn(-1f, 1f)
                return output
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun floatTensor(values: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(values), shape)

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), LongBuffer.wrap(values), shape)

    private fun float16Tensor(values: FloatArray, shape: LongArray): OnnxTensor {
        val shorts = ShortArray(values.size) { Half.toHalf(values[it]) }
        return OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            ShortBuffer.wrap(shorts),
            shape,
            OnnxJavaType.FLOAT16,
        )
    }

    private fun quantizePitch(f0: FloatArray): LongArray {
        val minMel = 1127.0 * ln(1.0 + 50.0 / 700.0)
        val maxMel = 1127.0 * ln(1.0 + 1100.0 / 700.0)
        val span = maxMel - minMel
        return LongArray(f0.size) { i ->
            val value = f0[i]
            if (value <= 0f) 1L else {
                val mel = 1127.0 * ln(1.0 + value / 700.0)
                (((mel - minMel) * 254.0 / span + 1.0).roundToInt()).coerceIn(1, 255).toLong()
            }
        }
    }

    override fun close() {
        runCatching { rmvpe?.close() }
        runCatching { embedder.close() }
        runCatching { synth.close() }
    }
}

private object WindowedSincResampler {
    private const val RADIUS = 12

    fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (input.isEmpty() || sourceRate == targetRate) return input.copyOf()
        require(sourceRate > 0 && targetRate > 0)
        val length = (input.size.toLong() * targetRate / sourceRate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val output = FloatArray(length)
        val ratio = sourceRate.toDouble() / targetRate
        val cutoff = min(1.0, targetRate.toDouble() / sourceRate) * 0.94
        for (i in output.indices) {
            val position = i * ratio
            val center = position.toInt()
            var weighted = 0.0
            var weightSum = 0.0
            for (tap in -RADIUS + 1..RADIUS) {
                val index = center + tap
                if (index !in input.indices) continue
                val distance = position - index
                val x = PI * distance * cutoff
                val sinc = if (kotlin.math.abs(x) < 1e-10) 1.0 else kotlin.math.sin(x) / x
                val windowPosition = distance / RADIUS
                if (kotlin.math.abs(windowPosition) >= 1.0) continue
                val window = 0.5 + 0.5 * kotlin.math.cos(PI * windowPosition)
                val weight = sinc * window * cutoff
                weighted += input[index] * weight
                weightSum += weight
            }
            output[i] = if (kotlin.math.abs(weightSum) > 1e-9) {
                (weighted / weightSum).toFloat()
            } else {
                input[center.coerceIn(0, input.lastIndex)]
            }
        }
        return output
    }
}

private fun OnnxTensor.copyFloatValues(): FloatArray {
    val type = (info as TensorInfo).type
    return when (type) {
        OnnxJavaType.FLOAT -> {
            val buffer = floatBuffer ?: error("Expected FLOAT tensor.")
            buffer.rewind()
            FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        OnnxJavaType.FLOAT16 -> {
            val buffer: ShortBuffer = byteBuffer.asShortBuffer()
            buffer.rewind()
            FloatArray(buffer.remaining()) { Half.toFloat(buffer.get()) }
        }
        else -> error("Expected FLOAT/FLOAT16 tensor but received $type.")
    }
}
