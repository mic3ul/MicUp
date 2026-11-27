package com.micplugin.inject;

interface IWhatsAppMicInjector {
    void exit() = 1;
    String start(int targetUid, int sampleRate) = 2;
    byte[] pullInputPcm(int waitMs) = 3;
    void pushOutputPcm(in byte[] pcm16le) = 4;
    String getStatus() = 5;
    void stop() = 6;
    void destroy() = 16777114;
}
