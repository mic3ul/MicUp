# MicUp

Real-time microphone processing for Android, built with Oboe, C++17, and Jetpack Compose.

## Current architecture

MicUp captures the device microphone, processes the signal through a native DSP chain, and sends the processed signal to its **local monitor**. The app also exposes a protected Binder/AIDL stream for a specifically integrated client to read the processed samples.

**MicUp is not a system-wide virtual microphone in this build.** An ordinary Android app cannot replace the microphone input used by another app through a public API. Selecting a `VOICE_COMMUNICATION` playback stream or granting Shizuku permission does not make another app consume MicUp's output as its microphone.

The current implementation distinguishes these paths:

- **Local monitor:** app-local playback of the processed signal.
- **Processed PCM bridge:** a bounded, low-latency IPC stream for an explicitly modified/compatible client.
- **System microphone injection:** not implemented by this fork. A genuine system-wide route requires device-specific audio HAL/AudioPolicy integration and suitable privileged/system support.

## Features

- Native DSP chain: noise gate, 10-band EQ, compressor, reverb, and pitch shifter.
- Native plugin hosting for LV2, CLAP, and experimental VST3.
- Local monitor with output-device selection and live input/output meters.
- Processed PCM Binder service for a whitelisted integration client.
- Presets, plugin management, and crash reporting.

## WhatsApp voice-note integration

The PCM bridge is the foundation for a **modified WhatsApp recorder** to consume MicUp's processed microphone signal.

It does **not** redirect the microphone of the unmodified WhatsApp APK. To make a WhatsApp voice message use the processed signal, the WhatsApp recording path itself must be integrated with the bridge and feed those samples into its normal voice-message encoder. That integration has not yet been made or tested in this repository.

See [the integration contract](docs/WHATSAPP_INTEGRATION.md) for the AIDL component, permission, stream framing, and client responsibilities.

The bridge emits mono PCM16 little-endian samples. Each packet is framed as a 4-byte unsigned little-endian frame count followed by that many 16-bit samples. The sample rate is reported through AIDL and normally requests 48 kHz; clients must query it rather than hard-code it.

## Building from source

Requirements: JDK 17, Android SDK, Android NDK 26.3.11579264, CMake 3.22.1, and Gradle 8.6.

Build a debug APK:

```bash
gradle :app:assembleDebug
```

Or open the project in Android Studio.

## Android requirements

- Android 8.0+ (API 26)
- Microphone permission
- Foreground microphone service permission on supported Android versions

## Important limitations

- Stock WhatsApp will continue using Android's selected microphone; it does not discover or consume MicUp's PCM bridge automatically.
- MediaProjection captures eligible playback; it is not a microphone-input injection API.
- Shizuku/ADB permission alone does not register a virtual input device with Android's audio framework.
- Root alone is not sufficient: a working implementation needs a compatible kernel/audio route, a PCM writer, and Android audio-policy/device registration. This repository does not currently provide a verified end-to-end system route.

## License

MIT License. See [LICENSE](LICENSE).
