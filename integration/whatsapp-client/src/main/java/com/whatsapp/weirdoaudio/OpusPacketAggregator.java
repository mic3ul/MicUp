package com.whatsapp.weirdoaudio;

/** Repacketizes six 20 ms Opus frames into one 120 ms Opus packet. */
public final class OpusPacketAggregator implements AutoCloseable {
    private static final int OPUS_OK = 0;
    private static final int OPUS_INVALID_PACKET = -4;
    private static final int FRAMES_PER_PACKET = 6;
    private static final int MAX_INPUT_PACKET_BYTES = 1277;

    static { System.loadLibrary("weirdo_opus_jni"); }

    private long nativeHandle;
    private int pendingFrames;

    public OpusPacketAggregator() {
        nativeHandle = nativeCreate();
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Could not create Opus repacketizer");
        }
    }

    /** Returns a complete combined packet when a group is ready, otherwise null. */
    public synchronized byte[] add(byte[] packet) {
        ensureOpen();
        if (packet == null || packet.length == 0 ||
                packet.length > MAX_INPUT_PACKET_BYTES) {
            throw new IllegalArgumentException("Invalid 20 ms Opus frame packet");
        }

        int result = nativeAdd(nativeHandle, packet);
        if (result == OPUS_INVALID_PACKET && pendingFrames > 0) {
            // A mode/bandwidth boundary can make frames incompatible. Emit the
            // prior valid group and retry this frame as the start of a new one.
            byte[] previous = takePendingPacket();
            int retry = nativeAdd(nativeHandle, packet);
            if (retry != OPUS_OK) {
                throw new IllegalArgumentException(
                        "Opus frame could not start a new packet: " + retry);
            }
            pendingFrames = 1;
            return previous;
        }
        if (result != OPUS_OK) {
            throw new IllegalArgumentException("Opus repacketizer rejected frame: " + result);
        }

        pendingFrames++;
        if (pendingFrames == FRAMES_PER_PACKET) return takePendingPacket();
        return null;
    }

    /** Emits the final group of one to five frames, if present. */
    public synchronized byte[] flush() {
        ensureOpen();
        return pendingFrames == 0 ? null : takePendingPacket();
    }

    private byte[] takePendingPacket() {
        byte[] result = nativeGetPacket(nativeHandle);
        if (result == null || result.length == 0) {
            throw new IllegalStateException("Opus repacketizer returned an empty packet");
        }
        nativeReset(nativeHandle);
        pendingFrames = 0;
        return result;
    }

    private void ensureOpen() {
        if (nativeHandle == 0L) throw new IllegalStateException("Opus repacketizer is closed");
    }

    @Override
    public synchronized void close() {
        long handle = nativeHandle;
        nativeHandle = 0L;
        pendingFrames = 0;
        if (handle != 0L) nativeDestroy(handle);
    }

    private static native long nativeCreate();
    private static native int nativeAdd(long handle, byte[] packet);
    private static native byte[] nativeGetPacket(long handle);
    private static native void nativeReset(long handle);
    private static native void nativeDestroy(long handle);
}
