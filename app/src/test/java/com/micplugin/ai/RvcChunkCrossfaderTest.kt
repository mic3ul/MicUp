package com.micplugin.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RvcChunkCrossfaderTest {

    @Test
    fun firstWindowEmitsOneStepAndKeepsTail() {
        val crossfader = RvcChunkCrossfader(stepSamples = 10, overlapSamples = 2)
        val first = FloatArray(12) { 1f }

        val output = crossfader.emit(first)

        assertEquals(10, output.size)
        assertEquals(0f, output.first(), 0.0001f)
        assertEquals(1f, output.last(), 0.0001f)
        assertTrue(output.drop(1).all { it == 1f })
    }

    @Test
    fun laterWindowsCrossfadeAndEmitExactStepLength() {
        val crossfader = RvcChunkCrossfader(stepSamples = 10, overlapSamples = 2)
        crossfader.emit(FloatArray(12) { 1f })

        val second = crossfader.emit(FloatArray(12) { 3f })
        assertEquals(10, second.size)
        assertEquals(1f, second[0], 0.0001f)
        assertEquals(3f, second[1], 0.0001f)
        assertTrue(second.drop(2).all { it == 3f })

        val third = crossfader.emit(FloatArray(12) { 5f })
        assertEquals(10, third.size)
        assertEquals(3f, third[0], 0.0001f)
        assertEquals(5f, third[1], 0.0001f)
        assertTrue(third.drop(2).all { it == 5f })
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongModelWindowLengthIsRejected() {
        RvcChunkCrossfader(stepSamples = 10, overlapSamples = 2)
            .emit(FloatArray(11))
    }
}
