package com.whatsapp.weirdoaudio;

/**
 * Native Opus encoder for the mono, 48 kHz processed PCM stream.
 *
 * This class is packaged in the modified WhatsApp APK through the client AAR.
 * It is deliberately independent of WhatsApp's private native symbols.
 */
public final class OpusPcmEncoder implements AutoCloseable {
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 1;
    public static final int FRAME_SAMPLES = 960; // 20 ms at 48 kHz.
    public static final int DEFAULT_BITRATE = 20_000;

    static {
        System.loadLibrary("weirdo_opus_jni");
    }

    private long nativeHandle;

    public OpusPcmEncoder() {
        this(DEFAULT_BITRATE);
    }

    public OpusPcmEncoder(int bitrate) {
        nativeHandle = nativeCreate(SAMPLE_RATE, CHANNELS, bitrate);
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Native Opus encoder returned a null handle");
        }
    }

    public synchronized int getLookaheadSamples() {
        ensureOpen();
        return nativeGetLookahead(nativeHandle);
    }

    /**
     * Encodes exactly 960 mono signed-PCM16 samples into one Opus packet.
     * This method is intended for the dedicated PCM reader/encoder thread,
     * never for an audio callback.
     */
    public synchronized byte[] encodeFrame(short[] pcm, int offset) {
        ensureOpen();
        if (pcm == null || offset < 0 ||
                offset > pcm.length - FRAME_SAMPLES) {
            throw new IllegalArgumentException("Expected a complete 960-sample PCM frame");
        }
        byte[] packet = nativeEncodeFrame(nativeHandle, pcm, offset);
        if (packet == null || packet.length == 0) {
            throw new IllegalStateException("Opus encoder returned an empty packet");
        }
        return packet;
    }

    private void ensureOpen() {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Opus encoder is closed");
        }
    }

    @Override
    public synchronized void close() {
        long handle = nativeHandle;
        nativeHandle = 0L;
        if (handle != 0L) nativeDestroy(handle);
    }

    private static native long nativeCreate(int sampleRate, int channels, int bitrate);
    private static native int nativeGetLookahead(long handle);
    private static native byte[] nativeEncodeFrame(long handle, short[] pcm, int offset);
    private static native void nativeDestroy(long handle);
}
