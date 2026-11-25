package com.whatsapp.weirdoaudio;

import android.content.Context;
import android.media.AudioFormat;

import java.io.File;
import java.io.IOException;

/**
 * Joins weirdo's processed-PCM transport with the native Opus/Ogg writer.
 *
 * This is the adapter intended for a PTT recorder lifecycle hook. The caller
 * still has to invoke start/pause/resume/finish from the matching WhatsApp
 * PTT callbacks in the target WhatsApp build.
 */
public final class ProcessedVoiceNoteSession implements AutoCloseable {
    public enum State { PREPARED, CONNECTING, READY, RECORDING, PAUSED, FINISHED, CANCELLED, FAILED }

    public interface Listener {
        void onReady(ProcessedVoiceNoteSession session);
        void onFinished(File voiceNoteFile, long capturedSamples, long encodedPackets);
        void onFailure(Throwable error);
    }

    private final ProcessedPcmClient pcmClient;
    private final ProcessedOpusRecorder recorder;
    private final Listener listener;
    private volatile State state = State.PREPARED;
    // PTT can transition to RECORDING before Android delivers onServiceConnected.
    // Queue that transition instead of throwing while the AIDL pipe is binding.
    private boolean startRequested;
    private boolean pauseRequested;

    public ProcessedVoiceNoteSession(Context context, File outputFile, Listener listener)
            throws IOException {
        if (context == null) throw new IllegalArgumentException("context == null");
        this.listener = listener;
        this.recorder = new ProcessedOpusRecorder(outputFile);
        this.pcmClient = new ProcessedPcmClient(context.getApplicationContext());
    }

    public synchronized State getState() {
        return state;
    }

    /**
     * Starts the AIDL connection. The weirdo processed-audio foreground
     * service must already be active; onReady arrives asynchronously.
     */
    public void connect() {
        synchronized (this) {
            requireState(State.PREPARED);
            state = State.CONNECTING;
        }

        try {
            pcmClient.connect(new ProcessedPcmClient.Listener() {
                @Override
                public void onReady(int sampleRate, int channels, int encoding) {
                    handleReady(sampleRate, channels, encoding);
                }

                @Override
                public void onPcm(short[] samples, int frameCount, int sampleRate) {
                    handlePcm(samples, frameCount, sampleRate);
                }

                @Override
                public void onFailure(Throwable error) {
                    fail(error);
                }
            });
        } catch (RuntimeException error) {
            fail(error);
            throw error;
        }
    }

    /**
     * Called by the WhatsApp PTT controller after its record/start transition.
     * If binding is still in progress, the start is queued and applied as soon
     * as the PCM format has been validated on the binder callback thread.
     */
    public synchronized void start() {
        if (state == State.CONNECTING) {
            startRequested = true;
            return;
        }
        requireState(State.READY);
        beginRecording();
    }

    private void beginRecording() {
        recorder.start();
        state = State.RECORDING;
        pcmClient.setForwardingEnabled(true);
    }

    /** Stops forwarding PCM without flushing or resetting the Opus encoder. */
    public synchronized void pause() {
        if (state == State.CONNECTING && startRequested) {
            pauseRequested = true;
            return;
        }
        requireState(State.RECORDING);
        pcmClient.setForwardingEnabled(false);
        recorder.pause();
        state = State.PAUSED;
    }

    /** Resumes from the existing encoder state and any pending partial frame. */
    public synchronized void resume() {
        requireState(State.PAUSED);
        recorder.resume();
        state = State.RECORDING;
        pcmClient.setForwardingEnabled(true);
    }

    /** Writes and finalizes the normal Ogg/Opus voice-note file. */
    public void finish() throws IOException {
        final long samples;
        final long packets;

        synchronized (this) {
            if (state != State.RECORDING && state != State.PAUSED) {
                throw new IllegalStateException("Cannot finish PTT session in state " + state);
            }
            pcmClient.setForwardingEnabled(false);
            state = State.FINISHED;
            pcmClient.close();
            try {
                recorder.finish();
            } catch (IOException | RuntimeException error) {
                state = State.FAILED;
                try { recorder.cancel(); } catch (IOException ignored) {}
                notifyFailure(error);
                if (error instanceof IOException) throw (IOException) error;
                throw error;
            }
            samples = recorder.getCapturedSampleCount();
            packets = recorder.getEncodedPacketCount();
        }

        if (listener != null) {
            try {
                listener.onFinished(recorder.getOutputFile(), samples, packets);
            } catch (RuntimeException ignored) {
                // Listener/UI errors must not invalidate an already finalized file.
            }
        }
    }

    /** Cancels the PTT note and removes the unfinished file. */
    public synchronized void cancel() throws IOException {
        if (state == State.FINISHED || state == State.CANCELLED) return;
        state = State.CANCELLED;
        pcmClient.close();
        recorder.cancel();
    }

    private void handleReady(int sampleRate, int channels, int encoding) {
        try {
            if (sampleRate != OpusPcmEncoder.SAMPLE_RATE || channels != 1 ||
                    encoding != AudioFormat.ENCODING_PCM_16BIT) {
                throw new IOException("Expected 48 kHz mono PCM16 from weirdo");
            }

            synchronized (this) {
                if (state != State.CONNECTING) {
                    pcmClient.close();
                    return;
                }
                state = State.READY;
                if (startRequested) {
                    beginRecording();
                    startRequested = false;
                    if (pauseRequested) {
                        pcmClient.setForwardingEnabled(false);
                        recorder.pause();
                        state = State.PAUSED;
                        pauseRequested = false;
                    }
                }
            }

            if (listener != null) listener.onReady(this);
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void handlePcm(short[] samples, int frameCount, int sampleRate) {
        try {
            synchronized (this) {
                if (state != State.RECORDING) return;
                if (sampleRate != OpusPcmEncoder.SAMPLE_RATE) {
                    throw new IOException("PCM sample rate changed during a voice note");
                }
                recorder.writePcm(samples, frameCount);
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void fail(Throwable error) {
        synchronized (this) {
            if (state == State.FINISHED || state == State.CANCELLED || state == State.FAILED) {
                return;
            }
            state = State.FAILED;
            pcmClient.close();
            try { recorder.cancel(); } catch (IOException ignored) {}
        }
        notifyFailure(error);
    }

    private void notifyFailure(Throwable error) {
        if (listener != null) {
            try { listener.onFailure(error); } catch (RuntimeException ignored) {}
        }
    }

    public File getOutputFile() {
        return recorder.getOutputFile();
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected " + expected + " but session is " + state);
        }
    }

    @Override
    public void close() throws IOException {
        cancel();
    }
}
