# weirdo audio test build

This branch contains a standalone audio-engine test build. It does not patch WhatsApp.

## Local monitoring test

1. Install the debug APK and allow microphone access.
2. Connect headphones before starting monitoring. Do not monitor through the loudspeaker near the microphone.
3. Select **Warm Deep Voice**, keep **LIVE AUDIO MONITOR** enabled, and start the engine with the power button.
4. Speak for ten seconds at a consistent distance from the microphone.
5. Stop speaking for five seconds, then make a few light handling noises away from the microphone.
6. Listen for voice clarity, low-frequency handling thumps, background hiss, and any gated word endings.

## Processing notes

- A 72 Hz high-pass stage reduces subsonic and low-frequency handling rumble.
- A conservative isolated-impulse suppressor can reduce some digital clicks. It is not a general transient remover.
- Android's platform NoiseSuppressor is attached only when the device exposes it and an audio session is available. Behavior depends on the handset audio implementation.
- The Warm Deep Voice preset uses gentle EQ, compression and a one-semitone pitch adjustment. SoundTouch is not formant-preserving and is not an AI voice-conversion model.
- Footsteps or impacts overlapping speech may remain. This build does not include RNNoise or another neural denoising model.

## WhatsApp integration status

The standalone application and its WhatsApp PCM client library are separate deliverables. The official WhatsApp PTT/OpusRecorder implementation is not patched by this branch, and an end-to-end WhatsApp voice-note test has not yet been completed.
