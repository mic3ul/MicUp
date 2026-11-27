#!/usr/bin/env python3
"""Export the CC BY-NC Shadowww T02 RVC-v2 checkpoint as fixed-window streaming ONNX.

The converted model accepts 100-frame (1.0 s) ContentVec/F0 context and emits
only the final 35 or 55 frames. Runtime overlap-add uses a 5-frame (50 ms)
crossfade, giving 300 ms and 500 ms input strides respectively.
"""
from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

import torch
import onnx

SAMPLE_RATE = 48000
INPUT_FRAMES = 100
EMBED_CHANNELS = 768
OVERLAP_FRAMES = 5


class TailStreamingSynth(torch.nn.Module):
    def __init__(self, net: torch.nn.Module, output_frames: int):
        super().__init__()
        self.net = net
        self.output_frames = output_frames

    def forward(self, phone, phone_lengths, pitch, nsff0, sid):
        net = self.net
        g = net.emb_g(sid).unsqueeze(-1)
        m_p, logs_p, x_mask = net.enc_p(phone, pitch, phone_lengths)
        z_p = (m_p + torch.exp(logs_p) * torch.randn_like(m_p) * 0.66666) * x_mask
        z = net.flow(z_p, x_mask, g=g, reverse=True)
        # GeneratorNSF.infer_realtime keeps the convolutional receptive field
        # from the preceding context frames and emits only the requested tail.
        # Cropping z before the decoder would discard that receptive field and
        # create an audible boundary at every 300/500 ms update.
        convert_length = self.output_frames * (SAMPLE_RATE // 100)
        if getattr(net.dec, "realtime", False):
            audio = net.dec.infer_realtime(
                z * x_mask,
                nsff0,
                g=g,
                convert_length=convert_length,
            )
            audio = audio[0, 0]
        else:
            # Some RVC-v2 checkpoints use ResBlock1, for which upstream
            # GeneratorNSF disables its optimized infer_realtime path.
            # Keep the full context through the decoder, then retain only
            # the final fixed-duration tail. This is correct but more costly;
            # on-device latency must be measured before choosing this model.
            audio = net.dec(z * x_mask, nsff0, g=g)[0, 0, -convert_length:]
        if audio.numel() != convert_length:
            raise RuntimeError(
                f"Streaming generator returned {audio.numel()} samples; expected {convert_length}"
            )
        return torch.clip(audio, -1.0, 1.0)


def find_checkpoint(extract_dir: Path) -> Path:
    candidates = list(extract_dir.rglob("*.pth"))
    preferred = [
        p for p in candidates
        if "shadowww-t02" in p.name.lower()
        and "e120" not in p.name.lower()
        and "e140" not in p.name.lower()
    ]
    if preferred:
        return preferred[0]
    if len(candidates) == 1:
        return candidates[0]
    raise RuntimeError(
        "Could not identify the primary Shadowww T02 .pth checkpoint: "
        + ", ".join(str(p) for p in candidates)
    )


def load_checkpoint(path: Path):
    try:
        return torch.load(path, map_location="cpu", weights_only=False)
    except TypeError:
        return torch.load(path, map_location="cpu")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--voice-zip", type=Path, required=True)
    parser.add_argument("--voice-changer-server", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(args.voice_changer_server.resolve()))

    from voice_changer.RVC.onnxExporter.SynthesizerTrnMs768NSFsid_ONNX import (
        SynthesizerTrnMs768NSFsid_ONNX,
    )

    extract_dir = args.output_dir / "source"
    extract_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.voice_zip) as archive:
        archive.extractall(extract_dir)
    checkpoint_path = find_checkpoint(extract_dir)
    checkpoint = load_checkpoint(checkpoint_path)

    config = checkpoint.get("config")
    weights = checkpoint.get("weight")
    if not isinstance(config, (list, tuple)) or not isinstance(weights, dict):
        raise RuntimeError(
            "The T02 checkpoint is not a supported RVC-v2 checkpoint (expected config + weight)."
        )

    config_text = " ".join(str(value) for value in config)
    if "48k" not in config_text.lower() and str(checkpoint.get("sr", "")).lower() not in {
        "48000",
        "48k",
    }:
        raise RuntimeError(f"Expected a 48 kHz T02 checkpoint, got config tail: {config[-3:]}")

    torch.set_num_threads(2)
    torch.manual_seed(2026)
    net = SynthesizerTrnMs768NSFsid_ONNX(*config, is_half=False).eval()
    load_result = net.load_state_dict(weights, strict=False)
    required_prefixes = ("enc_p.", "dec.", "flow.", "emb_g.")
    bad_missing = [
        name for name in load_result.missing_keys if name.startswith(required_prefixes)
    ]
    if bad_missing:
        raise RuntimeError(
            "Required RVC weights are missing: " + ", ".join(bad_missing[:20])
        )
    print(f"Loaded checkpoint: {checkpoint_path.name}")
    print(f"RVC decoder realtime optimisation: {getattr(net.dec, 'realtime', False)}")
    if not getattr(net.dec, "realtime", False):
        print("WARNING: this RVC checkpoint will decode the full one-second window before tail cropping; measured mobile latency may be too high.")
    print(
        f"Missing non-core keys: {len(load_result.missing_keys)}; "
        f"unexpected keys: {len(load_result.unexpected_keys)}"
    )

    for output_frames in (35, 55):
        model = TailStreamingSynth(net, output_frames).eval()
        feats = torch.zeros(1, INPUT_FRAMES, EMBED_CHANNELS, dtype=torch.float32)
        p_len = torch.tensor([INPUT_FRAMES], dtype=torch.long)
        pitch = torch.full((1, INPUT_FRAMES), 128, dtype=torch.long)
        pitchf = torch.full((1, INPUT_FRAMES), 140.0, dtype=torch.float32)
        sid = torch.tensor([0], dtype=torch.long)

        with torch.inference_mode():
            example = model(feats, p_len, pitch, pitchf, sid)
        expected_samples = output_frames * (SAMPLE_RATE // 100)
        if example.numel() != expected_samples:
            raise RuntimeError(
                f"Unexpected stream output size: expected {expected_samples}, got {example.numel()}"
            )

        filename = f"shadowww_t02_stream_{(output_frames - OVERLAP_FRAMES) * 10}ms.onnx"
        output_path = args.output_dir / filename
        torch.onnx.export(
            model,
            (feats, p_len, pitch, pitchf, sid),
            str(output_path),
            input_names=["feats", "p_len", "pitch", "pitchf", "sid"],
            output_names=["audio"],
            opset_version=17,
            do_constant_folding=True,
            export_params=True,
            dynamo=False,
        )
        graph = onnx.load(str(output_path))
        onnx.checker.check_model(graph)
        metadata = {
            "samplingRate": SAMPLE_RATE,
            "f0": True,
            "embChannels": EMBED_CHANNELS,
            "embedder": "contentvec",
            "embOutputLayer": 12,
            "useFinalProj": False,
            "modelType": "RVC_V2_STREAM",
            "version": "v2",
            "streaming": True,
            "streamInputFrames": INPUT_FRAMES,
            "streamOutputFrames": output_frames,
            "streamOverlapFrames": OVERLAP_FRAMES,
            "streamStepFrames": output_frames - OVERLAP_FRAMES,
            "voiceModel": "Shadowww T02 Raw",
            "voiceModelAttribution": "Voice model: Shadowww",
            "voiceModelLicense": "CC BY-NC 4.0",
        }
        entry = graph.metadata_props.add()
        entry.key = "metadata"
        entry.value = json.dumps(metadata, separators=(",", ":"))
        onnx.save(graph, str(output_path))
        print(
            f"Exported {filename}: {output_path.stat().st_size / (1024 * 1024):.1f} MiB"
        )

    attribution = """WEIRDO RVC LIVE MODEL — SHADOWWW T02 RAW

Voice model: Shadowww
Source: https://huggingface.co/ShadowSH2306/shadowww-voices
License: CC BY-NC 4.0

Required attribution:
  Voice model: Shadowww

Restrictions:
- Non-commercial use only, unless separate permission is obtained from Shadowww.
- Do not impersonate Shadowww or present generated vocals as an authentic recording.
- Label AI-generated vocals as AI.
- The original voice model was trained using KLM 4.1 48k by SeungHyun Han
  (Seoul Streaming Station); retain that upstream credit when redistributing.

This ONNX export is a format conversion of the T02 Raw voice model. It does
not grant rights beyond the upstream license.
"""
    (args.output_dir / "ATTRIBUTION.txt").write_text(attribution, encoding="utf-8")
    (args.output_dir / "MODEL_README.md").write_text(
        "# Shadowww T02 Raw — WEIRDO Live RVC\n\n"
        "RVC v2, 48 kHz, low/raw/natural singing register.\n\n"
        "Two fixed-context streaming synthesizers are included: 300 ms stride "
        "(35 output frames, 50 ms crossfade) and 500 ms stride "
        "(55 output frames, 50 ms crossfade). Both use a 1.0 s ContentVec/F0 context.\n\n"
        "Required base models: contentvec_768l12_q8.onnx and rmvpe_q8.onnx "
        "from https://huggingface.co/TigreGotico/voiceclonnx-rvc (MIT).\n\n"
        "See ATTRIBUTION.txt for required credit and non-commercial terms.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
