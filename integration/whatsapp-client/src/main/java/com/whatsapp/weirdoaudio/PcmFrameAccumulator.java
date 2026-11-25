package com.whatsapp.weirdoaudio;

import java.io.IOException;
import java.util.Arrays;

/**
 * Converts an arbitrary sequence of PCM chunks into fixed 20 ms Opus frames.
 * A short final frame is zero-padded, while its real sample count is retained
 * for Ogg EOS granule trimming. Partial data is preserved across pause/resume.
 */
final class PcmFrameAccumulator {
    static final int FRAME_SAMPLES = 960;

    interface FrameConsumer {
        void onFrame(short[] frame) throws IOException;
    }

    private final short[] frame = new short[FRAME_SAMPLES];
    private int fill;
    private long capturedSamples;

    void append(short[] samples, int frameCount, FrameConsumer consumer)
            throws IOException {
        if (samples == null) throw new IllegalArgumentException("samples == null");
        if (frameCount < 0 || frameCount > samples.length) {
            throw new IllegalArgumentException("frameCount is outside the PCM array");
        }
        if (consumer == null) throw new IllegalArgumentException("consumer == null");
        if (Long.MAX_VALUE - capturedSamples < frameCount) {
            throw new IllegalStateException("PCM sample counter overflow");
        }

        int offset = 0;
        while (offset < frameCount) {
            int count = Math.min(FRAME_SAMPLES - fill, frameCount - offset);
            System.arraycopy(samples, offset, frame, fill, count);
            fill += count;
            offset += count;

            if (fill == FRAME_SAMPLES) {
                consumer.onFrame(frame);
                fill = 0;
            }
        }
        capturedSamples += frameCount;
    }

    void flushPadded(FrameConsumer consumer) throws IOException {
        if (consumer == null) throw new IllegalArgumentException("consumer == null");
        if (fill == 0) return;
        Arrays.fill(frame, fill, FRAME_SAMPLES, (short) 0);
        consumer.onFrame(frame);
        fill = 0;
    }

    int getPendingSamples() {
        return fill;
    }

    long getCapturedSamples() {
        return capturedSamples;
    }
}
