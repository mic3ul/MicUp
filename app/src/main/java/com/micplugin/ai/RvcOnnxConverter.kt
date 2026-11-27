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
    private val embedderInputName: String,
    private val outputName: String,
    private val rmvpeInputName: String,
    private val sessionOptions: OrtSession.SessionOptions,
) : Closeable {

    data class ModelUris(val synth: Uri, val embedder: Uri, val rmvpe: Uri)

    data class Metadata(
        val sampleRate: Int,
        val usesPitch: Boolean,
        val embeddingChannels: Int,
        val embedderName: String,
        val outputLayer: Int,
        val useFinalProjection: Boolean,
        val streamInputFrames: Int = 0,
        val streamOutputFrames: Int = 0,
        val streamStepFrames: Int = 0,
    )

    data class ConvertedAudio(val samples: FloatArray, val sampleRate: Int)

    val isStreamingModel: Boolean get() = metadata.streamInputFrames > 0 && metadata.streamOutputFrames > 0
    val streamingOutputFrames: Int get() = metadata.streamOutputFrames
    val streamingStepFrames: Int get() = metadata.streamStepFrames

    companion object {
        const val MAX_INPUT_SECONDS = 15
        private const val MAX_ONNX_BYTES = 450L * 1024L * 1024L
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

                val embeddingInput = when {
                    "input_values" in embedSession.inputInfo -> "input_values"
                    "audio" in embedSession.inputInfo -> "audio"
                    "source" in embedSession.inputInfo -> "source"
                    else -> error("Unsupported ContentVec input: ${embedSession.inputInfo.keys}")
                }
                val pitchInput = when {
                    "input" in pitchSession.inputInfo -> "input"
                    "waveform" in pitchSession.inputInfo && "threshold" in pitchSession.inputInfo -> "waveform"
                    else -> error("Unsupported RMVPE input: ${pitchSession.inputInfo.keys}")
                }
                require("feats" in synthSession.inputInfo && "p_len" in synthSession.inputInfo &&
                    "sid" in synthSession.inputInfo && "pitch" in synthSession.inputInfo &&
                    "pitchf" in synthSession.inputInfo && "audio" in synthSession.outputInfo) {
                    "The RVC synthesizer must expose feats/p_len/sid/pitch/pitchf inputs and audio output."
                }

                val output = when {
                    "unit12" in embedSession.outputInfo && meta.outputLayer == 12 && !meta.useFinalProjection -> "unit12"
                    "units9" in embedSession.outputInfo && meta.outputLayer == 9 && meta.useFinalProjection -> "units9"
                    "unit12s" in embedSession.outputInfo && meta.outputLayer == 12 && meta.useFinalProjection -> "unit12s"
                    "hidden_states" in embedSession.outputInfo -> "hidden_states"
                    "embed" in embedSession.outputInfo -> "embed"
                    else -> error("Unsupported ContentVec outputs: ${embedSession.outputInfo.keys}")
                }

                return RvcOnnxConverter(
                    synthSession, embedSession, pitchSession, meta, embeddingInput, output, pitchInput, options
                )
            } catch (error: Throwable) {
                runCatching { pitchSession?.close() }
                runCatching { embedSession?.close() }
                runCatching { synthSession?.close() }
                runCatching { options.close() }
                throw error
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
                streamInputFrames = json.optInt("streamInputFrames", 0),
                streamOutputFrames = json.optInt("streamOutputFrames", 0),
                streamStepFrames = json.optInt("streamStepFrames", 0),
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
            val reportedSize = context.contentResolver.openFileDescriptor(uri, "r")
                ?.use { it.statSize } ?: -1L
            require(reportedSize <= MAX_ONNX_BYTES) {
                "$prefix model exceeds the 450 MiB safety limit."
            }

            val key = Integer.toHexString(uri.toString().hashCode())
            val file = File(context.filesDir, "ai-models/$prefix-$key.onnx")
            if (file.exists() && file.length() > 0L &&
                (reportedSize <= 0L || file.length() == reportedSize)
            ) return file

            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".part")
            var copied = 0L
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= MAX_ONNX_BYTES) {
                                "$prefix model exceeds the 450 MiB safety limit."
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Cannot read selected model file: $uri")

                require(copied > 0L) { "Selected model file is empty." }
                if (reportedSize > 0L) {
                    require(copied == reportedSize) {
                        "Model copy is incomplete: expected $reportedSize bytes, copied $copied."
                    }
                }

                if (!temporary.renameTo(file)) {
                    temporary.copyTo(file, overwrite = true)
                    temporary.delete()
                }
                return file
            } catch (error: Throwable) {
                runCatching { temporary.delete() }
                throw error
            }
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

        val frames2x = features.frames * 2
        val targetFrames = if (metadata.streamInputFrames > 0) metadata.streamInputFrames else frames2x
        val pitch = runPitch(source16k, pitchShiftSemitones, targetFrames)
        progress(0.62f)

        val expanded = FloatArray(frames2x * features.channels)
        for (frame in 0 until features.frames) {
            val srcOffset = frame * features.channels
            val dstOffset = frame * 2 * features.channels
            System.arraycopy(features.values, srcOffset, expanded, dstOffset, features.channels)
            System.arraycopy(features.values, srcOffset, expanded, dstOffset + features.channels, features.channels)
        }

        val alignedFrames = min(targetFrames, pitch.first.size)
        require(alignedFrames > 0) { "RVC models returned no aligned frames." }
        val alignedFeats = alignFeatureFrames(
            expanded, frames2x, alignedFrames, features.channels
        )
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

    private fun alignFeatureFrames(
        source: FloatArray,
        sourceFrames: Int,
        targetFrames: Int,
        channels: Int,
    ): FloatArray {
        require(sourceFrames > 0 && targetFrames > 0)
        if (sourceFrames == targetFrames) return source
        val output = FloatArray(targetFrames * channels)
        if (sourceFrames > targetFrames) {
            val start = (sourceFrames - targetFrames) * channels
            System.arraycopy(source, start, output, 0, output.size)
        } else {
            System.arraycopy(source, 0, output, 0, source.size)
            val last = (sourceFrames - 1) * channels
            for (frame in sourceFrames until targetFrames) {
                System.arraycopy(source, last, output, frame * channels, channels)
            }
        }
        return output
    }

    private data class Features(val values: FloatArray, val frames: Int, val channels: Int)

    private fun runEmbedder(audio16k: FloatArray): Features {
        val inputShape = if (embedderInputName == "source") {
            longArrayOf(1, 1, audio16k.size.toLong())
        } else {
            longArrayOf(1, audio16k.size.toLong())
        }
        val input = floatTensor(audio16k, inputShape)
        val inputMap = linkedMapOf<String, OnnxTensor>(embedderInputName to input)
        if ("attention_mask" in embedder.inputInfo) {
            inputMap["attention_mask"] = longTensor(
                LongArray(audio16k.size) { 1L }, longArrayOf(1, audio16k.size.toLong())
            )
        }
        try {
            embedder.run(inputMap, setOf(outputName)).use { result ->
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
            inputMap.values.forEach { runCatching { it.close() } }
        }
    }

    private fun runPitch(
        audio16k: FloatArray,
        shift: Int,
        targetFrames: Int,
    ): Pair<FloatArray, LongArray> {
        val session = requireNotNull(rmvpe)
        val rawF0 = if (rmvpeInputName == "input") {
            runRmvpeLogMel(session, audio16k)
        } else {
            runRmvpeWaveform(session, audio16k)
        }
        val aligned = resamplePitchFrames(rawF0, targetFrames)
        val factor = 2.0.pow(shift / 12.0).toFloat()
        val fine = if (shift == 0) aligned else FloatArray(aligned.size) { aligned[it] * factor }
        return fine to quantizePitch(fine)
    }

    private fun runRmvpeWaveform(session: OrtSession, audio16k: FloatArray): FloatArray {
        val waveform = floatTensor(audio16k, longArrayOf(1, audio16k.size.toLong()))
        val threshold = floatTensor(floatArrayOf(0.3f), longArrayOf(1))
        try {
            session.run(mapOf("waveform" to waveform, "threshold" to threshold)).use { result ->
                val outputName = session.outputInfo.keys.first()
                val tensor = result.get(outputName).orElseThrow { IllegalStateException("RMVPE output is missing.") } as OnnxTensor
                return tensor.copyFloatValues()
            }
        } finally {
            waveform.close()
            threshold.close()
        }
    }

    private fun runRmvpeLogMel(session: OrtSession, audio16k: FloatArray): FloatArray {
        val mel = RmvpeMelFrontend.compute(audio16k)
        val input = floatTensor(
            mel.values,
            longArrayOf(1, 128, mel.paddedFrames.toLong())
        )
        try {
            session.run(mapOf("input" to input)).use { result ->
                val outputName = session.outputInfo.keys.first()
                val tensor = result.get(outputName).orElseThrow { IllegalStateException("RMVPE output is missing.") } as OnnxTensor
                val shape = (tensor.info as TensorInfo).shape
                require(shape.size == 3 && shape[0] == 1L && shape[2] == 360L) {
                    "RMVPE output must be [1,T,360], got ${shape.contentToString()}."
                }
                val flat = tensor.copyFloatValues()
                val validFrames = min(mel.frames, shape[1].toInt())
                val f0 = FloatArray(validFrames)
                for (frame in 0 until validFrames) {
                    var maximum = 0f
                    var weightedBins = 0.0
                    val base = frame * 360
                    for (bin in 0 until 360) {
                        val probability = flat[base + bin]
                        if (probability > maximum) maximum = probability
                        weightedBins += probability * bin
                    }
                    f0[frame] = if (maximum > 0.003f) {
                        (32.7 * 2.0.pow((weightedBins * 20.0) / 1200.0)).toFloat()
                    } else 0f
                }
                interpolateUnvoiced(f0)
                return f0
            }
        } finally {
            input.close()
        }
    }

    private fun interpolateUnvoiced(values: FloatArray) {
        val voiced = values.indices.filter { values[it] > 0f }
        if (voiced.isEmpty()) return
        val first = voiced.first()
        for (i in 0 until first) values[i] = values[first]
        for (pair in 0 until voiced.lastIndex) {
            val start = voiced[pair]
            val end = voiced[pair + 1]
            if (end - start <= 1) continue
            val a = values[start]
            val b = values[end]
            for (i in start + 1 until end) {
                val t = (i - start).toFloat() / (end - start)
                values[i] = a + (b - a) * t
            }
        }
        val last = voiced.last()
        for (i in last + 1 until values.size) values[i] = values[last]
    }

    private fun resamplePitchFrames(source: FloatArray, targetFrames: Int): FloatArray {
        require(source.isNotEmpty() && targetFrames > 0)
        if (source.size == targetFrames) return source
        val result = FloatArray(targetFrames)
        for (i in 0 until targetFrames) {
            val position = i.toDouble() * source.size / targetFrames
            val lower = position.toInt().coerceIn(0, source.lastIndex)
            val upper = min(lower + 1, source.lastIndex)
            val fraction = (position - lower).toFloat()
            result[i] = source[lower] + (source[upper] - source[lower]) * fraction
        }
        return result
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
        runCatching { sessionOptions.close() }
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
