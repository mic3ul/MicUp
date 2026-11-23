package com.whatsapp.weirdoaudio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import com.micplugin.audio.IProcessedAudioStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client side of weirdo's framed PCM AIDL stream.
 *
 * This class belongs in the modified WhatsApp process. It only transports PCM;
 * it deliberately does not create an AudioRecord or encode audio itself.
 */
public final class ProcessedPcmClient implements AutoCloseable {
    private static final String TAG = "WeirdoPcmClient";
    private static final String PROVIDER_PACKAGE = "com.micplugin";
    private static final String PROVIDER_SERVICE =
            "com.micplugin.service.ProcessedAudioStreamService";
    private static final int MAX_PACKET_FRAMES = 2048;

    public interface Listener {
        void onReady(int sampleRate, int channels, int encoding);
        void onPcm(short[] samples, int frameCount, int sampleRate);
        void onFailure(Throwable error);
    }

    private final Context appContext;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean failureReported = new AtomicBoolean(false);
    private volatile Listener listener;
    private volatile boolean bound;
    private volatile ParcelFileDescriptor readDescriptor;
    private volatile Thread readerThread;
    private volatile IProcessedAudioStream remote;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed.get()) return;
            IProcessedAudioStream api = IProcessedAudioStream.Stub.asInterface(service);
            remote = api;
            Thread connector = new Thread(() -> openAndRead(api), "WeirdoPcmReader");
            connector.setDaemon(true);
            readerThread = connector;
            connector.start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            fail(new IOException("weirdo PCM service disconnected"));
        }

        @Override
        public void onBindingDied(ComponentName name) {
            remote = null;
            fail(new IOException("weirdo PCM service binding died"));
        }

        @Override
        public void onNullBinding(ComponentName name) {
            fail(new IOException("weirdo PCM service returned a null binding"));
        }
    };

    public ProcessedPcmClient(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        appContext = context.getApplicationContext();
    }

    /**
     * Bind to weirdo. Start weirdo's microphone foreground service first;
     * openPcmStream() intentionally fails while its processed-audio engine is idle.
     */
    public void connect(Listener callback) {
        if (callback == null) throw new IllegalArgumentException("listener == null");
        if (bound || closed.get()) throw new IllegalStateException("client already used or closed");
        listener = callback;

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(PROVIDER_PACKAGE, PROVIDER_SERVICE));
        try {
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) fail(new IOException("Could not bind to weirdo PCM service"));
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private void openAndRead(IProcessedAudioStream api) {
        ParcelFileDescriptor descriptor = null;
        try {
            int rate = api.getSampleRate();
            int channels = api.getChannelCount();
            int encoding = api.getEncoding();

            if (rate < 8000 || rate > 192000) {
                throw new IOException("Unsupported PCM sample rate: " + rate);
            }
            if (channels != 1) {
                throw new IOException("Expected mono PCM, received " + channels + " channels");
            }
            if (encoding != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                throw new IOException("Expected PCM16 encoding, received " + encoding);
            }

            descriptor = api.openPcmStream();
            if (descriptor == null) throw new IOException("weirdo returned a null PCM pipe");
            if (closed.get()) {
                descriptor.close();
                return;
            }

            readDescriptor = descriptor;
            Listener target = listener;
            if (target != null) target.onReady(rate, channels, encoding);
            readLoop(descriptor, rate);
        } catch (Throwable error) {
            if (!closed.get()) fail(error);
        } finally {
            readDescriptor = null;
            if (descriptor != null) {
                try { descriptor.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void readLoop(ParcelFileDescriptor descriptor, int sampleRate) throws IOException {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            } catch (RuntimeException ignored) {
                // Priority is an optimization only.
            }

            byte[] header = new byte[4];
            while (!closed.get()) {
                readFully(input, header, 0, header.length);
                long framesLong = (header[0] & 0xffL)
                        | ((header[1] & 0xffL) << 8)
                        | ((header[2] & 0xffL) << 16)
                        | ((header[3] & 0xffL) << 24);

                if (framesLong < 1 || framesLong > MAX_PACKET_FRAMES) {
                    throw new IOException("Invalid PCM packet frame count: " + framesLong);
                }

                int frames = (int) framesLong;
                byte[] payload = new byte[frames * 2];
                readFully(input, payload, 0, payload.length);

                short[] samples = new short[frames];
                for (int i = 0, p = 0; i < frames; i++, p += 2) {
                    samples[i] = (short) ((payload[p] & 0xff) | (payload[p + 1] << 8));
                }

                Listener target = listener;
                if (target != null && !closed.get()) {
                    target.onPcm(samples, frames, sampleRate);
                }
            }
        }
    }

    private static void readFully(InputStream input, byte[] buffer, int offset, int length)
            throws IOException {
        int read = 0;
        while (read < length) {
            int count = input.read(buffer, offset + read, length - read);
            if (count < 0) throw new EOFException("PCM pipe ended mid-packet");
            if (count == 0) continue;
            read += count;
        }
    }

    private void fail(Throwable error) {
        if (!failureReported.compareAndSet(false, true) || closed.get()) return;
        Listener target = listener;
        try {
            Log.e(TAG, "PCM source failed", error);
            if (target != null) target.onFailure(error);
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        ParcelFileDescriptor descriptor = readDescriptor;
        if (descriptor != null) {
            try { descriptor.close(); } catch (IOException ignored) {}
        }

        Thread thread = readerThread;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();

        if (bound) {
            try {
                appContext.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Already unbound by the framework.
            }
            bound = false;
        }
        remote = null;
        listener = null;
    }
}
