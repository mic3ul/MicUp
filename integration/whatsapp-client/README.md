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
4. Call `setForwardingEnabled(true)` only after PTT `start()`; set it to `false` on pause and stop so preparation audio is drained but never written into the voice note. Close the client on cancel/release.

The existing WhatsApp `OpusRecorder` native API has no PCM-frame input method. This client establishes the transport boundary only; it does **not** by itself modify Opus encoding or create a valid WhatsApp voice note. A replacement `OpusRecorder` backend still needs to preserve the `.opus` output, Ogg page/resume state, waveform metadata, and callbacks.


## Ogg Opus muxer

`OggOpusMuxer.java` writes an Ogg Opus stream from **already encoded Opus packets**. It emits `OpusHead` and `OpusTags`, page sequence numbers, granule positions, lacing values, and Ogg CRC checksums. It derives packet duration from the Opus TOC and rejects caller-supplied durations that disagree. It holds the final packet until `finish()` so the EOS flag is applied to the final audio page.

The writer was validated on the development host with actual Opus packets: `ffprobe` identified the output as mono 48 kHz Opus in Ogg, and `ffmpeg` decoded the complete file successfully. The source is Java 8 language/API compatible.

**Important:** this is a container writer, not an Opus encoder or a complete WhatsApp recorder. PCM must still be packetized into supported Opus frame sizes, encoded, and routed here. WhatsApp's exact pause/resume, waveform and file-metadata contract still needs to be matched before this can replace `OpusRecorder`.


The muxer unit tests check page boundaries, sequence numbers, granule positions, BOS/EOS flags, the exact-255-byte lacing edge case, TOC duration parsing, and CRCs. CI runs `:whatsapp-client:testDebugUnitTest` alongside the Android builds.
