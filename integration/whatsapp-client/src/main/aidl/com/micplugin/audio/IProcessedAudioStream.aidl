package com.micplugin.audio;

import android.os.ParcelFileDescriptor;

interface IProcessedAudioStream {
    ParcelFileDescriptor openPcmStream();
    int getSampleRate();
    int getChannelCount();
    int getEncoding();
}
