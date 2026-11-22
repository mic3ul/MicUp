package com.whatsapp.superpack;

/**
 * Minimal JNI registration contract copied from the method descriptors in the
 * user-selected WhatsApp APK. This is not WhatsApp application code.
 */
public final class WhatsAppOpenboxArchive {
    private WhatsAppOpenboxArchive() {}

    public static native void closeNative(long handle);
    public static native int getFileCountNative(long handle);
    public static native long getFilePtrNative(long handle, int index);
    public static native long getFileSizeNative(long handle, int index);
    public static native long openNative(int fileDescriptor, long startOffset, long length);
}
