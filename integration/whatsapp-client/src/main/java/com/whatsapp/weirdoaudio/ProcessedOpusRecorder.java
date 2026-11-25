package com.whatsapp.weirdoaudio;

import java.io.File;
import java.io.IOException;

/**
 * PCM16 mono -> Opus -> Ogg recorder core for a single WhatsApp PTT message.
 *
 * Call start/pause/resume from the PTT lifecycle and writePcm only while the
 * PTT capture is active. The PCM reader thread may call writePcm directly.
 * Pauses retain encoder and partial-frame state without inserting silence.
 */
public final class ProcessedOpusRecorder implements AutoCloseable {
    public enum State { PREPARED, RECORDING, PAUSED, FINISHED, CANCELLED, FAILED }

    private final File outputFile;
    private final OpusPcmEncoder encoder;
    private final OggOpusMuxer muxer;
    private final PcmFrameAccumulator accumulator = new PcmFrameAccumulator();
    private State state = State.PREPARED;
    private long encodedPackets;

    public ProcessedOpusRecorder(File outputFile) throws IOException {
        this(outputFile, OpusPcmEncoder.DEFAULT_BITRATE);
    }

    public ProcessedOpusRecorder(File outputFile, int bitrate) throws IOException {
        if (outputFile == null) throw new IllegalArgumentException("outputFile == null");
        this.outputFile = outputFile;

        OpusPcmEncoder createdEncoder = new OpusPcmEncoder(bitrate);
        OggOpusMuxer createdMuxer;
        try {
            int preSkip = createdEncoder.getLookaheadSamples();
            createdMuxer = new OggOpusMuxer(
                    outputFile, OpusPcmEncoder.SAMPLE_RATE,
                    OpusPcmEncoder.CHANNELS, preSkip, "weirdo");
        } catch (IOException | RuntimeException failure) {
            createdEncoder.close();
            throw failure;
        }
        this.encoder = createdEncoder;
        this.muxer = createdMuxer;
    }

    public File getOutputFile() {
        return outputFile;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized long getCapturedSampleCount() {
        return accumulator.getCapturedSamples();
    }

    public synchronized int getPendingPcmSamples() {
        return accumulator.getPendingSamples();
    }

    public synchronized long getEncodedPacketCount() {
        return encodedPackets;
    }

    public synchronized long getOggPageCount() {
        return muxer.getWrittenPageCount();
    }

    public synchronized void start() {
        requireState(State.PREPARED);
        state = State.RECORDING;
    }

    public synchronized void pause() {
        requireState(State.RECORDING);
        state = State.PAUSED;
    }

    public synchronized void resume() {
        requireState(State.PAUSED);
        state = State.RECORDING;
    }

    /**
     * Receives signed PCM16 mono samples from ProcessedPcmClient.
     * Calls made while paused or after finalization are deliberately ignored.
     */
    public synchronized void writePcm(short[] samples, int frameCount) throws IOException {
        if (state != State.RECORDING) return;

        try {
            accumulator.append(samples, frameCount, this::encodeFrame);
        } catch (IOException | RuntimeException failure) {
            state = State.FAILED;
            abortQuietly();
            encoder.close();
            throw failure;
        }
    }

    /**
     * Finalizes the voice message, padding a final partial 20 ms frame and
     * trimming that padding with the exact EOS granule position.
     */
    public synchronized void finish() throws IOException {
        if (state == State.FINISHED) return;
        if (state == State.CANCELLED || state == State.FAILED) {
            throw new IllegalStateException("Cannot finish recorder in state " + state);
        }

        try {
            if (accumulator.getCapturedSamples() == 0L) {
                throw new IllegalStateException("Cannot finalize an empty voice note");
            }
            accumulator.flushPadded(this::encodeFrame);
            long finalGranule = encoder.getLookaheadSamples()
                    + accumulator.getCapturedSamples();
            muxer.finish(finalGranule);
            state = State.FINISHED;
        } catch (IOException | RuntimeException failure) {
            state = State.FAILED;
            abortQuietly();
            throw failure;
        } finally {
            encoder.close();
        }
    }

    /** Cancels recording and deletes the incomplete output rather than sending it. */
    public synchronized void cancel() throws IOException {
        if (state == State.FINISHED || state == State.CANCELLED) return;
        state = State.CANCELLED;

        IOException closeFailure = null;
        try {
            muxer.abort();
        } catch (IOException failure) {
            closeFailure = failure;
        } finally {
            encoder.close();
        }

        if (outputFile.exists() && !outputFile.delete() && closeFailure == null) {
            closeFailure = new IOException("Could not delete cancelled voice-note file");
        }
        if (closeFailure != null) throw closeFailure;
    }

    private void encodeFrame(short[] pcmFrame) throws IOException {
        byte[] packet = encoder.encodeFrame(pcmFrame, 0);
        int duration = OggOpusMuxer.getPacketDurationSamplesAt48k(packet);
        muxer.writePacket(packet, duration);
        encodedPackets++;
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException(
                    "Expected " + expected + " but recorder is " + state);
        }
    }

    private void abortQuietly() {
        try {
            muxer.abort();
        } catch (IOException ignored) {
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (state != State.FINISHED && state != State.CANCELLED) {
            cancel();
        } else {
            encoder.close();
        }
    }
}
