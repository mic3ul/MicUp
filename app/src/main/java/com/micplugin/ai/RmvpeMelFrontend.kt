package com.micplugin.ai

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin front-end for the MIT RMVPE ONNX export distributed by
 * TigreGotico/voiceclonnx-rvc. Input is mono 16 kHz float PCM; output is
 * the log-mel tensor [1, 128, T] expected by the ONNX model.
 */
internal object RmvpeMelFrontend {
    private const val FFT_SIZE = 1024
    private const val HOP_SIZE = 160
    private const val MEL_BINS = 128
    private const val SAMPLE_RATE = 16_000
    private const val F_MIN = 30.0
    private const val F_MAX = 8_000.0

    data class Features(val values: FloatArray, val frames: Int, val paddedFrames: Int)

    fun compute(audio: FloatArray): Features {
        require(audio.size >= HOP_SIZE * 4) { "RMVPE input is too short." }
        val padLeft = (FFT_SIZE - HOP_SIZE) / 2
        val padRight = padLeft + FFT_SIZE
        val padded = reflectPad(audio, padLeft, padRight)
        val frameCount = 1 + (padded.size - FFT_SIZE) / HOP_SIZE
        val window = DoubleArray(FFT_SIZE) { i ->
            0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))
        }
        val filterBank = buildMelFilterBank()
        val output = FloatArray(MEL_BINS * frameCount)
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        val magnitude = DoubleArray(FFT_SIZE / 2 + 1)

        for (frame in 0 until frameCount) {
            val offset = frame * HOP_SIZE
            for (i in 0 until FFT_SIZE) {
                real[i] = padded[offset + i].toDouble() * window[i]
                imag[i] = 0.0
            }
            fftInPlace(real, imag)
            for (bin in magnitude.indices) {
                magnitude[bin] = hypot(real[bin], imag[bin])
            }
            for (mel in 0 until MEL_BINS) {
                val row = mel * (FFT_SIZE / 2 + 1)
                var energy = 0.0
                for (bin in magnitude.indices) {
                    energy += filterBank[row + bin] * magnitude[bin]
                }
                output[mel * frameCount + frame] =
                    ln(energy.coerceAtLeast(1e-5)).toFloat()
            }
        }

        val paddedFrames = ((frameCount + 31) / 32) * 32
        if (paddedFrames == frameCount) {
            return Features(output, frameCount, paddedFrames)
        }

        val expanded = FloatArray(MEL_BINS * paddedFrames)
        val padFrames = paddedFrames - frameCount
        for (mel in 0 until MEL_BINS) {
            val src = mel * frameCount
            val dst = mel * paddedFrames
            System.arraycopy(output, src, expanded, dst, frameCount)
            for (p in 0 until padFrames) {
                val reflected = reflectIndex(frameCount + p, frameCount)
                expanded[dst + frameCount + p] = output[src + reflected]
            }
        }
        return Features(expanded, frameCount, paddedFrames)
    }

    private fun buildMelFilterBank(): DoubleArray {
        val bins = FFT_SIZE / 2 + 1
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melMin = hzToMel(F_MIN)
        val melMax = hzToMel(F_MAX)
        val points = DoubleArray(MEL_BINS + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (MEL_BINS + 1))
        }
        val frequencies = DoubleArray(bins) { it.toDouble() * SAMPLE_RATE / FFT_SIZE }
        val filters = DoubleArray(MEL_BINS * bins)

        for (mel in 0 until MEL_BINS) {
            val low = points[mel]
            val center = points[mel + 1]
            val high = points[mel + 2]
            for (bin in 0 until bins) {
                val hz = frequencies[bin]
                val weight = when {
                    hz >= low && hz <= center && center > low -> (hz - low) / (center - low)
                    hz > center && hz <= high && high > center -> (high - hz) / (high - center)
                    else -> 0.0
                }
                filters[mel * bins + bin] = abs(weight)
            }
        }
        return filters
    }

    private fun reflectPad(input: FloatArray, left: Int, right: Int): FloatArray {
        val output = FloatArray(input.size + left + right)
        for (i in output.indices) {
            val sourceIndex = when {
                i < left -> reflectIndex(i - left, input.size)
                i >= left + input.size -> reflectIndex(i - left, input.size)
                else -> i - left
            }
            output[i] = input[sourceIndex]
        }
        return output
    }

    private fun reflectIndex(index: Int, size: Int): Int {
        require(size > 1)
        val period = 2 * (size - 1)
        var value = index % period
        if (value < 0) value += period
        return if (value < size) value else period - value
    }

    private fun fftInPlace(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two." }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenReal = cos(angle)
            val wLenImag = sin(angle)
            val half = length shr 1
            var base = 0
            while (base < n) {
                var wReal = 1.0
                var wImag = 0.0
                for (k in 0 until half) {
                    val even = base + k
                    val odd = even + half
                    val oddReal = real[odd] * wReal - imag[odd] * wImag
                    val oddImag = real[odd] * wImag + imag[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag
                    val nextWReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextWReal
                }
                base += length
            }
            length = length shl 1
        }
    }

    private fun Double.pow(exponent: Double): Double = kotlin.math.pow(this, exponent)
}
