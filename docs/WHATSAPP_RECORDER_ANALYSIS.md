# WhatsApp voice-note recorder analysis

Status: static inspection of the user-provided WhatsApp 2.26.43.76 APK set and ARM64 Superpack extraction. This is an engineering record, not a completed WhatsApp patch.

## Confirmed DEX path

The push-to-talk voice-note path is:

`PttRecorderController` → recorder interface `LX/Tg3` → implementation `LX/TAo` → `OpusRecorder`.

The controller creates the implementation through `LX/Saw.A02(...)`, then calls `CSr()` (prepare) and `start()`. The implementation delegates these operations to the native recorder object:

- `prepare()`
- `start()`
- `pause()`
- `stop()`
- `isRecording()`
- `getPageNumber()`
- `getWaveformData()`
- `close()/freeNative()`

`OpusRecorderFactory.createOpusRecorder(...)` constructs `com.whatsapp.infra.media.util.OpusRecorder`; its constructor calls `allocateNative(filename, callback, config)`. The lifecycle methods are JNI-native declarations, not Java PCM-reading loops.

The controller also owns the user-visible PTT lifecycle and cleanup. That is the part we should preserve: start, pause/resume, stop, cancellation, output-file handling, and send/upload.

## Native audio path evidence

The extracted ARM64 `libwhatsapp.so` exports the JNI entry points for the `OpusRecorder` lifecycle. Its dynamic dependencies include Oboe and `libopus_mlow.so`; its strings identify Oboe/AAudio, OpenSL ES, and JNI recorder backends. This is consistent with capture being implemented inside the native layer.

The paired `libopus_mlow.so` exports the Opus encoder API, including `opus_encoder_create`, `opus_encode`, `opus_encoder_ctl`, and `opus_encoder_destroy`.

The native recorder configuration contains audio-source/engine choices, sample rate, Opus parameters, capture/processing flags, and `injectSyntheticPcm`. The latter is a configuration flag; static inspection does not establish it as a supported external PCM input interface. We must not treat it as one.

## Important exclusion

`com.whatsapp.infra.media.audioRecording.AudioRecordFactory.createAudioRecord(III)` exists in DEX and constructs Android `AudioRecord`, but the call-site scan across the supplied DEX files found only its own default wrapper. It is not the PTT recorder path identified above. Patching that factory would therefore be the wrong first move.

## Existing weirdo transport

weirdo already exposes `com.micplugin.service.ProcessedAudioStreamService` through AIDL:

- Interface: `com.micplugin.audio.IProcessedAudioStream`
- PCM: mono, signed PCM16 little-endian
- Sample rate: queried at runtime (the engine normally runs at 48 kHz)
- Packet framing: 4-byte unsigned little-endian frame count, followed by that many PCM16 samples
- One active reader; bounded queue; old packets are dropped under backpressure to keep latency bounded

The service checks that the caller UID belongs to `com.whatsapp`. The modified WhatsApp manifest must request `com.micplugin.permission.READ_PROCESSED_AUDIO`.

weirdo alone does not modify stock WhatsApp. The WhatsApp process must bind to this service and use this PCM instead of opening a competing microphone capture stream.

## The actual integration seam

The current `OpusRecorder` Java/JNI API exposes recorder lifecycle and metadata, but no method that accepts PCM frames. Therefore the AIDL stream cannot simply be passed to the existing native recorder through a public method.

There are two technically distinct options:

1. Add a PCM-input seam to the native capture backend and make it consume the AIDL frames, while retaining WhatsApp's existing Opus/Ogg writer and metadata path. This is the more invasive, version-specific native patch.
2. Replace the `OpusRecorder` backend behind the same Java-facing lifecycle contract with a custom input backend. It would read the weirdo PCM stream, encode with the already present Opus library, and write a valid Ogg Opus file while preserving waveform/page/resume behavior. This avoids a second microphone capture but requires matching WhatsApp's exact file/metadata expectations.

The second route is easier to isolate and test first, but it is not yet implemented.

## Required compatibility contract

Before an APK patch is considered complete, the replacement backend must:

- Consume PCM only while the PTT recorder is active; never open a second microphone.
- Negotiate and validate sample rate, channels, and PCM16 encoding.
- Preserve the existing prepared output path and `.opus` file semantics.
- Preserve start, pause, resume, stop, release, cancellation, and error behavior.
- Produce compatible Opus/Ogg pages and maintain page/granule state across resume.
- Return waveform samples in the range and cadence expected by WhatsApp's visualization path.
- Fall back to the original recorder if weirdo is not connected, if that fallback is explicitly enabled.

## Verification status

Verified by static inspection:
- DEX PTT-to-`OpusRecorder` lifecycle path.
- Native JNI lifecycle entry points.
- Native capture backend strings and Opus library exports.
- Existing weirdo AIDL stream contract.

Not yet verified:
- Exact PCM callback cadence and channel conversion inside the proprietary native recorder.
- The full output-file/Ogg page contract under all pause/resume cases.
- A patched WhatsApp APK.
- End-to-end voice-note recording and sending using weirdo PCM.

No WhatsApp binary has been modified and no successful end-to-end recording is claimed yet.
