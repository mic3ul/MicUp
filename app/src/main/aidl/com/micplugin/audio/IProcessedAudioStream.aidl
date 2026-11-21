package com.micplugin.audio;

import android.os.ParcelFileDescriptor;

/**
 * Read-only stream of MicUp's processed microphone signal.
 *
 * Stream packet format: uint32 little-endian frame count, followed by
 * frameCount signed PCM16 little-endian mono samples.
 */
interface IProcessedAudioStream {
    ParcelFileDescriptor openPcmStream();
    int getSampleRate();
    int getChannelCount();
    int getEncoding();
}
