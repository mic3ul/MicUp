package com.micplugin.ai

/**
 * Joins fixed-duration RVC tail windows. A 50 ms tail from the previous window
 * is crossfaded with the first 50 ms of the next window. Exactly one synthesis
 * step is emitted per window, so the output clock does not drift.
 */
internal class RvcChunkCrossfader(
    private val stepSamples: Int,
    private val overlapSamples: Int,
) {
    private var previousTail: FloatArray? = null

    init {
        require(stepSamples > 0)
        require(overlapSamples > 0 && overlapSamples < stepSamples)
    }

    fun reset() {
        previousTail = null
    }

    fun emit(converted: FloatArray): FloatArray {
        require(converted.size == stepSamples + overlapSamples) {
            "Expected \${stepSamples + overlapSamples} samples, got \${converted.size}."
        }
        val currentTail = converted.copyOfRange(stepSamples, stepSamples + overlapSamples)
        val previous = previousTail
        previousTail = currentTail

        if (previous == null) {
            val first = converted.copyOfRange(0, stepSamples)
            // Fade in the first 50 ms after startup or an inference gap to
            // prevent a discontinuity click at the boundary.
            for (i in 0 until overlapSamples) {
                val gain = i.toFloat() / (overlapSamples - 1).coerceAtLeast(1)
                first[i] *= gain
            }
            return first
        }

        val output = FloatArray(stepSamples)
        for (i in 0 until overlapSamples) {
            val t = i.toFloat() / (overlapSamples - 1).coerceAtLeast(1)
            output[i] = previous[i] * (1f - t) + converted[i] * t
        }
        System.arraycopy(
            converted,
            overlapSamples,
            output,
            overlapSamples,
            stepSamples - overlapSamples,
        )
        return output
    }
}
