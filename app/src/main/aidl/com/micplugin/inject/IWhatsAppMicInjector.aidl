package com.micplugin.inject;

interface IWhatsAppMicInjector {
    String start(int targetUid, int sampleRate);
    byte[] pullInputPcm(int waitMs);
    void pushOutputPcm(in byte[] pcm16le);
    String getStatus();
    void stop();
    void destroy();
}
