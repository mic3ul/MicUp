# WhatsApp voice-note recorder analysis

Status: static inspection of the user-provided WhatsApp 2.26.43.76 APK set. This is an analysis record, not a completed WhatsApp patch.

## Verified package layout

- The base APK contains multidex DEX files, including the recorder-facing Java/Kotlin classes.
- The ARM64 split contains `lib/arm64-v8a/libs.so` and `libsuperpack.so`.
- `libs.so` is an ELF64 AArch64 container, not a normal application library. Its symbol table exposes `_superpack_archive_start`, `_superpack_archive_end`, and `_superpack_archive_size`.
- The paired `libsuperpack.so` is the runtime component used to unpack the embedded native payload. The native recorder implementation therefore cannot be treated as an ordinary loose `.so` in the APK.

## Recorder symbols confirmed in DEX

The DEX class tables contain:

- `com.whatsapp.infra.media.audioRecording.OpusRecorderFactory`
  - `createOpusRecorder(String, PttNativeMetricsCallback, OpusRecorderConfig): OpusRecorder`
- `com.whatsapp.infra.media.util.OpusRecorder`
  - constructor with the same configuration/callback inputs
  - `prepare()`, `start()`, `pause()`, `stop()`, `close()`
  - `isRecording()`, `getPageNumber()`, `getWaveformData()`
  - native allocation/release entry points

The listed recorder lifecycle methods have no DEX code body (native declarations). This is strong evidence that the capture/encode lifecycle is implemented in the Superpack-loaded native layer, rather than being a simple Kotlin `AudioRecord.read()` loop.

A separate `AudioRecordFactory.createAudioRecord(III)` exists in the DEX. Its relationship to the PTT `OpusRecorder` path is not yet proven; do not assume it is the voice-note capture hook.

## What this means for MicUp

MicUp already exposes a mono PCM16 AIDL stream from its processed-audio engine. However, the WhatsApp side does not yet bind to this service or consume the stream.

More importantly, running MicUp's microphone capture alongside WhatsApp's own recorder is not a valid final architecture: Android concurrent microphone-capture policy can silence or deprioritize one capture client. The modified WhatsApp recorder must consume MicUp's PCM as its input and must not continue opening a competing microphone capture path for that recording.

## Next implementation sequence

1. Reconstruct the exact native recorder input boundary from the matching Superpack payload and its JNI/native registration path.
2. Determine the expected PCM format, sample rate, channel count, buffer cadence, pause/resume behavior, and waveform/page metadata contract.
3. Add a narrowly scoped PCM-input adapter for voice-note recording. Keep WhatsApp's existing Opus encoding, Ogg/page writing, waveform generation, and send/upload pipeline intact wherever possible.
4. Keep the normal microphone path as a fallback when MicUp is disconnected or the feature is disabled.
5. Test short/long notes, hold-to-record, pause/resume, cancel, screen-off, route changes, Bluetooth/headset input, and low-buffer conditions.

## Current limitations

- No WhatsApp DEX or native library has been modified.
- No patched WhatsApp APK has been built or signed.
- No end-to-end voice note has been recorded or sent using MicUp PCM.
- The current MicUp CI success proves compilation only; device audio quality, latency, and the WhatsApp handoff still require runtime testing.
