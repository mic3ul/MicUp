package com.micplugin.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RmvpeMelFrontendTest {

    @Test
    fun oneSecondMonoWindowProducesExpectedRmvpShape() {
        val sampleRate = 16_000
        val frequency = 140.0
        val input = FloatArray(sampleRate) { i ->
            (0.25 * sin(2.0 * PI * frequency * i / sampleRate)).toFloat()
        }

        val result = RmvpeMelFrontend.compute(input)

        assertEquals(106, result.frames)
        assertEquals(128, result.paddedFrames)
        assertEquals(128 * 128, result.values.size)
        assertTrue(result.values.all { it.isFinite() })
    }

    @Test
    fun shortWindowIsRejected() {
        var failed = false
        try {
            RmvpeMelFrontend.compute(FloatArray(100))
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
