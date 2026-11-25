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

`OggOpusMuxer.java` writes an Ogg Opus stream from **already encoded Opus packets**. It emits `OpusHead` and `OpusTags`, page sequence numbers, granule positions, lacing values, and Ogg CRC checksums. It derives packet duration from the Opus TOC and rejects caller-supplied durations that disagree. Its finalization API can trim padded samples from the final encoded packet by setting the EOS granule position to the exact number of captured samples. It holds the final packet until `finish()` so the EOS flag is applied to the final audio page.

The writer was validated on the development host with actual Opus packets: `ffprobe` identified the output as mono 48 kHz Opus in Ogg, and `ffmpeg` decoded the complete file successfully. The source is Java 8 language/API compatible.

**Important:** this is a container writer, not an Opus encoder or a complete WhatsApp recorder. PCM must still be packetized into supported Opus frame sizes, encoded, and routed here. WhatsApp's exact pause/resume, waveform and file-metadata contract still needs to be matched before this can replace `OpusRecorder`.


## Native Opus encoder and processed voice-note pipeline

The client AAR now builds a separate JNI Opus encoder from the official Xiph Opus 1.6.1 source archive. The download is pinned with the published SHA-256 value so a future upstream branch change cannot silently alter the codec source.

- `OpusPcmEncoder` encodes signed PCM16 mono at 48 kHz in 20 ms (960-sample) frames. It uses the Opus voice application mode, 20 kb/s default target bitrate, complexity 5, and queries the encoder's actual lookahead for the Ogg pre-skip.
- `PcmFrameAccumulator` joins variable-sized AIDL chunks, retains incomplete frames across pauses, and pads only the last partial frame.
- `ProcessedOpusRecorder` encodes the frames, writes Ogg Opus packets, and sets the EOS granule to the real captured sample count so final-frame padding is not played.
- `ProcessedVoiceNoteSession` coordinates the AIDL client and recorder lifecycle. It waits in READY until the PTT hook calls `start()`; pause/resume toggle forwarding without resetting the Opus encoder; `finish()` finalizes the file; `cancel()` deletes an unfinished file.

The encoder API requires complete supported Opus frames; at 48 kHz, 960 samples is a 20 ms frame. The implementation keeps encoding off the real-time audio callback and processes PCM on the dedicated pipe reader thread. See the [Opus encoder API](https://opus-codec.org/docs/html_api/group__opusencoder.html).

**Integration boundary:** these classes form a compiled PCM-to-Opus/Ogg pipeline, but they are not yet injected into the WhatsApp PTT controller or substituted for WhatsApp's `OpusRecorder`. The target-version PTT class must call `ProcessedVoiceNoteSession` at prepare/start/pause/resume/stop/cancel and must preserve WhatsApp's waveform/page callbacks and file metadata. No end-to-end WhatsApp send test has been claimed.

The muxer unit tests check page boundaries, sequence numbers, granule positions, BOS/EOS flags, the exact-255-byte lacing edge case, TOC duration parsing, and CRCs. CI runs `:whatsapp-client:testDebugUnitTest` alongside the Android builds.
