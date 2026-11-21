# Modified WhatsApp PCM integration contract

## What this bridge provides

MicUp exports a bound AIDL service:

- Component: `com.micplugin/.service.ProcessedAudioStreamService`
- Interface: `com.micplugin.audio.IProcessedAudioStream`
- Permission: `com.micplugin.permission.READ_PROCESSED_AUDIO`
- Allowed caller package: `com.whatsapp`
- Audio format: mono, signed PCM16 little-endian
- Sample rate: query `getSampleRate()` (the engine normally runs at 48 kHz)
- Encoding: `AudioFormat.ENCODING_PCM_16BIT`

The client must declare this permission in its manifest:

```xml
<uses-permission android:name="com.micplugin.permission.READ_PROCESSED_AUDIO" />
```

The service also checks the calling UID's installed package names and rejects clients other than `com.whatsapp`.

## Stream framing

The returned `ParcelFileDescriptor` is the read side of a pipe. It contains a continuous sequence of packets:

1. 4-byte unsigned little-endian integer: number of audio frames in this packet.
2. That many signed 16-bit little-endian mono samples.

There is no WAV header. Clients must read exactly `frameCount * 2` payload bytes before reading the next header. The bridge uses a bounded queue and drops the oldest packet when the client cannot keep up, favoring low latency over lossless archival capture.

The pipe is opened only while MicUp's microphone foreground service is active. Only one client stream is supported; opening another replaces the previous one.

## Client binding

Bind explicitly to:

```text
package: com.micplugin
class:   com.micplugin.service.ProcessedAudioStreamService
```

Call `openPcmStream()`, then consume the framed PCM data from the returned descriptor. Query `getSampleRate()`, `getChannelCount()`, and `getEncoding()` for the negotiated format.

## WhatsApp-specific work still required

This bridge does **not** alter the microphone used by an unmodified WhatsApp installation. A modified WhatsApp build must change its voice-message recording path to:

1. Bind to MicUp's exported AIDL service while the user records a voice message.
2. Read the framed PCM16 stream.
3. Feed those samples into the voice-message audio encoder used by that WhatsApp build.
4. Preserve WhatsApp's existing push-to-talk start/stop, cancellation, duration, and upload lifecycle.

The WhatsApp APK's recorder implementation must be inspected before writing that patch. This repository does not contain WhatsApp source code, and installing MicUp alone does not change stock WhatsApp recording.

## Security and privacy

The Binder service requires the declared normal permission and independently checks that the caller UID belongs to package `com.whatsapp`. The stream is local to the device and is not uploaded by MicUp. Keep the stream closed when no voice message is being recorded.
