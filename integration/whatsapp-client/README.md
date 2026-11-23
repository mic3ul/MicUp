# WhatsApp PCM client (integration source)

This folder contains the **consumer-side** AIDL client to be added to a modified WhatsApp build.

## Files

- `src/main/aidl/com/micplugin/audio/IProcessedAudioStream.aidl`: same interface as the weirdo provider.
- `src/main/java/com/whatsapp/weirdoaudio/ProcessedPcmClient.java`: binds to weirdo, validates the negotiated PCM format, reads the 4-byte little-endian frame count and PCM16LE payload, and forwards frames to a supplied callback.

## Manifest changes required in the modified WhatsApp APK

```xml
<uses-permission android:name="com.micplugin.permission.READ_PROCESSED_AUDIO" />
<queries>
    <package android:name="com.micplugin" />
</queries>
```

## Runtime contract

1. Start weirdo's microphone foreground service while weirdo is visible.
2. In WhatsApp's PTT recorder preparation path, bind with `ProcessedPcmClient.connect(...)`.
3. Use the callback to feed a replacement recorder backend. Do not call `AudioRecord`, Oboe, or OpenSL ES in parallel.
4. Close the client on pause/stop/cancel/release according to the recorder state machine.

The existing WhatsApp `OpusRecorder` native API has no PCM-frame input method. This client establishes the transport boundary only; it does **not** by itself modify Opus encoding or create a valid WhatsApp voice note. A replacement `OpusRecorder` backend still needs to preserve the `.opus` output, Ogg page/resume state, waveform metadata, and callbacks.
