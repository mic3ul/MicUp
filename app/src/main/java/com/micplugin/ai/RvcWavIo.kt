package com.micplugin.ai

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RvcAudioClip(val samples: FloatArray, val sampleRate: Int)

object RvcWavIo {
    fun readPcm16(input: InputStream): RvcAudioClip {
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 128L * 1024L * 1024L) { "Audio file exceeds the 128 MiB safety limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size >= 44) { "WAV file is incomplete." }
        fun id(offset: Int, value: String): Boolean {
            if (offset + 4 > bytes.size) return false
            for (i in 0..3) if (bytes[offset + i].toInt() != value[i].code) return false
            return true
        }
        require(id(0, "RIFF") && id(8, "WAVE")) {
            "AI Voice Studio currently accepts PCM WAV files. Convert your audio to WAV first."
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (cursor + 8 <= bytes.size) {
            val chunkSizeLong = buffer.getInt(cursor + 4).toLong() and 0xffffffffL
            val payload = cursor + 8
            require(chunkSizeLong <= Int.MAX_VALUE && payload.toLong() + chunkSizeLong <= bytes.size) {
                "WAV contains an invalid chunk size."
            }
            val chunkSize = chunkSizeLong.toInt()
            when {
                id(cursor, "fmt ") -> {
                    require(chunkSize >= 16) { "WAV format chunk is too short." }
                    format = buffer.getShort(payload).toInt() and 0xffff
                    channels = buffer.getShort(payload + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(payload + 4)
                    bitsPerSample = buffer.getShort(payload + 14).toInt() and 0xffff
                }
                id(cursor, "data") -> {
                    dataOffset = payload
                    dataSize = chunkSize
                }
            }
            cursor = payload + chunkSize + (chunkSize and 1)
        }

        require(format == 1 && bitsPerSample == 16) {
            "Only uncompressed 16-bit PCM WAV is supported in this AI build."
        }
        require(channels in 1..2) { "WAV must be mono or stereo." }
        require(sampleRate in 8000..96000) { "Unsupported WAV sample rate: $sampleRate." }
        require(dataOffset >= 0 && dataSize >= channels * 2) { "WAV contains no audio samples." }
        val frameBytes = channels * 2
        val frames = dataSize / frameBytes
        require(frames <= sampleRate * RvcOnnxConverter.MAX_INPUT_SECONDS) {
            "Choose a clip no longer than ${RvcOnnxConverter.MAX_INPUT_SECONDS} seconds."
        }
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            val offset = dataOffset + frame * frameBytes
            val left = buffer.getShort(offset).toInt()
            val sample = if (channels == 1) left else {
                val right = buffer.getShort(offset + 2).toInt()
                (left + right) / 2
            }
            mono[frame] = sample / 32768f
        }
        return RvcAudioClip(mono, sampleRate)
    }

    fun writePcm16Mono(output: OutputStream, clip: RvcOnnxConverter.ConvertedAudio) {
        require(clip.sampleRate > 0)
        val dataSize = clip.samples.size.toLong() * 2L
        require(dataSize <= Int.MAX_VALUE - 36L) { "Converted WAV is too large." }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(clip.sampleRate)
        header.putInt(clip.sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())
        output.write(header.array())

        val scratch = ByteBuffer.allocate(32 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in clip.samples) {
            if (scratch.remaining() < 2) {
                output.write(scratch.array(), 0, scratch.position())
                scratch.clear()
            }
            val pcm = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            scratch.putShort(pcm.toShort())
        }
        if (scratch.position() > 0) output.write(scratch.array(), 0, scratch.position())
        output.flush()
    }
}
