package com.facebook.superpack;

/** Exception type referenced by the matching Superpack JNI implementation. */
public class AssetDecompressionException extends Exception {
    public AssetDecompressionException(String message) {
        super(message);
    }
}
