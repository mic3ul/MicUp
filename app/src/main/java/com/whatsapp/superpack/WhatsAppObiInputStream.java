package com.whatsapp.superpack;

import java.io.InputStream;

/**
 * Minimal JNI registration contract copied from the method descriptors in the
 * user-selected WhatsApp APK. This is not WhatsApp application code.
 */
public final class WhatsAppObiInputStream extends InputStream {
    private WhatsAppObiInputStream() {}

    public static native void closeNative(long handle, byte[] backingData);
    public static native long openBytesNative(byte[] data, int offset, int length);
    public static native long openInputStreamNative(InputStream input, int flags);
    public static native long openObArchiveBytesNative(long archiveHandle, long offset, int flags);
    public static native long openRawBytesNative(long handle, int flags);
    public static native int readNative(long handle, byte[] destination, int offset, int length);

    @Override
    public int read() {
        throw new UnsupportedOperationException("Registration stub only");
    }
}
