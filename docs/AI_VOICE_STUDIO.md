# WEIRDO AI Voice Studio

The AI Voice Studio is an **offline, clip-based RVC voice-conversion path** using ONNX Runtime. It is separate from the live Oboe effects chain and is not a real-time microphone voice changer.

## Supported input

- PCM WAV, 16-bit signed integer, mono or stereo
- 8 kHz to 96 kHz input; stereo is downmixed to mono
- Clips from 1 second through 30 seconds
- Output is mono PCM16 WAV at the synthesizer model's configured sample rate

The current UI deliberately limits clip length to reduce memory pressure on Android handsets.

## Required model files

Import three compatible ONNX files in the AI Voice Studio:

1. **Synthesizer / target voice** — an F0-guided RVC synthesizer exported to ONNX using compatible Voice Changer export tooling. The file must embed a JSON custom metadata entry named `metadata`, and expose the `feats`, `p_len`, `sid`, `pitch`, and `pitchf` inputs plus an `audio` output.
2. **Content encoder** — a ContentVec / HuBERT ONNX model. The currently supported exported output names are `unit12`, `units9`, and `unit12s`, selected from the synthesizer's metadata.
3. **Pitch extractor** — an RMVPE ONNX model exposing `waveform` and `threshold` inputs.

The synthesizer's metadata must specify a 32–48 kHz output rate and a supported 256- or 768-dimensional embedder channel count. The app currently handles F0-guided synthesizers only.

A generic `.onnx` file, a `.pth` checkpoint, or an arbitrary voice-changer model is not interchangeable with these three files. The app does not contain a bundled target-speaker model; the user supplies a compatible voice model.

## Model choice and voice quality

RVC changes timbre toward the speaker represented by the selected synthesizer. A low, textured target voice is needed to get a low, textured result; moving the pitch slider alone is not equivalent. For singing, prefer a target model trained on clean singing samples rather than speech-only material. Use only a voice model you own or are authorized to use, and do not present converted audio as an authentic recording of its target speaker.

## Performance and memory

- ONNX Runtime is configured for two intra-op threads and one inter-op thread.
- Model files are copied to the app's private model cache and loaded from file paths.
- Model size is capped at 800 MiB per file.
- Clip conversion runs off the UI thread; it is not wired into WhatsApp PTT or the live audio callback.
- Actual speed and memory use depend on the model exports and device. No real-device performance claim is made until tested on the target handset.

## Known limitations

- This is the first RVC integration stage. No default target voice is shipped.
- The input path currently accepts PCM16 WAV only.
- Retrieval-index blending, automatic de-noising of imported clips, and WhatsApp post-record conversion are not part of this stage.
- This feature does not create vibrato, melisma, pitch correction, or musical phrasing. Those require separate singing-processing tools or models.
