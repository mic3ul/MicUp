package com.facebook.superpack;

import android.content.res.AssetManager;
import java.io.InputStream;

/**
 * JNI contract mirrored from the matching WhatsApp split's DEX.
 * This class only declares native entry points; the implementation is loaded
 * at runtime from the user-selected APK split and is never bundled in MicUp.
 */
public final class AssetDecompressor {
    private AssetDecompressor() {}

    public static native String[] decompress(
            AssetManager assets, String assetName, String outputDirectory, String format);

    public static native String[] decompress_from_so(
            String soPath, String outputDirectory, String format);

    public static native String[] decompress_legacy(
            InputStream input, String outputDirectory, String format);

    public static native String[] decompress_range(
            AssetManager assets, String assetName, String outputDirectory,
            String format, int start, int count);

    public static native String[] decompress_range_from_so(
            String soPath, String outputDirectory, String format, int start, int count);

    public static native String[] decompress_with_ref(
            String reference, String input, String outputDirectory, String format);

    public static native int get_architecture();

    public static native int sync_directory_to_disk(String path);

    public static native int sync_file_to_disk(String path);

    public static native void testDecompressorLibraryUsable(byte[] data);
}
