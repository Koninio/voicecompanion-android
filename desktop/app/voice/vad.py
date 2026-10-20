"""Streaming end-of-utterance detection with Silero VAD v6 (ONNX, CPU).

The model file ships inside faster-whisper (``faster_whisper/assets/silero_vad_v6.onnx``).
Model I/O (same as ``faster_whisper.vad.SileroVADModel``):
  input  "input" float32 [seq, 64 + 512]   (64 samples of context from the previous frame + 512 new)
         "h", "c" float32 [1, 1, 128]      (LSTM state, carried across frames)
  output "speech_probs" [seq, 1], "hn", "cn"
"""
from __future__ import annotations

import logging
import math
import os
import sys
import threading
from collections import deque
from typing import Callable

import numpy as np

from . import cfg_get

log = logging.getLogger(__name__)

FRAME_SAMPLES = 512
CONTEXT_SAMPLES = 64
BARGE_IN_MIN_SPEECH_MS = 300


def silero_model_path() -> str:
    cands = []
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        cands.append(os.path.join(meipass, "faster_whisper", "assets", "silero_vad_v6.onnx"))
    try:
        from faster_whisper.utils import get_assets_path

        cands.append(os.path.join(get_assets_path(), "silero_vad_v6.onnx"))
    except Exception:
        pass
    for p in cands:
        if os.path.isfile(p):
            return p
    raise FileNotFoundError("silero_vad_v6.onnx not found (faster_whisper assets)")


class SileroStream:
    """Callable: float32 frame of 512 samples (16 kHz, -1..1) -> speech probability."""

    def __init__(self, path: str | None = None):
        import onnxruntime

        opts = onnxruntime.SessionOptions()
        opts.inter_op_num_threads = 1
        opts.intra_op_num_threads = 1
        opts.enable_cpu_mem_arena = False
        opts.log_severity_level = 4
        self.session = onnxruntime.InferenceSession(
            path or silero_model_path(), providers=["CPUExecutionProvider"], sess_options=opts)
        self.reset()

    def reset(self) -> None:
        self._h = np.zeros((1, 1, 128), dtype=np.float32)
        self._c = np.zeros((1, 1, 128), dtype=np.float32)
        self._context = np.zeros(CONTEXT_SAMPLES, dtype=np.float32)

    def __call__(self, frame: np.ndarray) -> float:
        frame = np.asarray(frame, dtype=np.float32).reshape(-1)
        if frame.shape[0] != FRAME_SAMPLES:
            raise ValueError("Silero expects 512-sample frames at 16 kHz")
        x = np.concatenate([self._context, frame])[None, :]
        out, self._h, self._c = self.session.run(None, {"input": x, "h": self._h, "c": self._c})
        self._context = frame[-CONTEXT_SAMPLES:].copy()
        return float(np.asarray(out).reshape(-1)[0])


class StreamingVAD:
    """Feed int16 PCM (any length), get utterance events.

    Events:
      {"type": "speech_start"}
      {"type": "speech_end", "audio": bytes}   # whole utterance incl. pre-roll
      {"type": "speech_cancel"}                # (addition) started but shorter than min_speech_ms

    Hysteresis: speech starts at ``threshold``; a silence run starts at the first
    frame below ``threshold - 0.15`` and is only broken by a frame >= threshold
    (same rule as faster_whisper.vad). ``speech_end`` fires once the silence run
    reaches ``end_silence_ms``.

    Modes: "normal" and "barge_in" (higher threshold, speech must last
    >= 300 ms before ``speech_start`` is confirmed). Switching mode keeps an
    utterance in progress.

    ``prob_fn`` may be injected for tests: callable(float32[512]) -> float,
    optionally with a ``reset()`` method.
    """

    def __init__(self, cfg: dict, sample_rate: int = 16000,
                 prob_fn: Callable[[np.ndarray], float] | None = None):
        if sample_rate != 16000:
            raise ValueError("StreamingVAD only supports 16 kHz")
        self.sample_rate = sample_rate
        self.frame_ms = FRAME_SAMPLES * 1000.0 / sample_rate  # 32 ms
        self._prob_fn = prob_fn
        self._lock = threading.RLock()
        self.mode = "normal"
        self.configure(cfg)
        self.reset()

    # ------------------------------------------------------------- config
    def configure(self, cfg: dict) -> None:
        with self._lock:
            self.normal_threshold = float(cfg_get(cfg, "vad.threshold", 0.5))
            self.barge_threshold = float(cfg_get(cfg, "vad.barge_in_threshold", 0.8))
            self.end_silence_ms = float(cfg_get(cfg, "vad.end_silence_ms", 1000))
            self.min_speech_ms = float(cfg_get(cfg, "vad.min_speech_ms", 250))
            self.preroll_ms = float(cfg_get(cfg, "vad.preroll_ms", 300))
            self.max_speech_s = float(cfg_get(cfg, "vad.max_speech_s", 30))
            self.barge_min_speech_ms = float(cfg_get(cfg, "vad.barge_in_min_speech_ms",
                                                     BARGE_IN_MIN_SPEECH_MS))
            self._apply_mode()

    def _apply_mode(self) -> None:
        if self.mode == "barge_in":
            self.threshold = self.barge_threshold
            self.start_ms = self.barge_min_speech_ms
        else:
            self.threshold = self.normal_threshold
            self.start_ms = 0.0
        self.neg_threshold = max(self.threshold - 0.15, 0.01)
        self._end_frames = max(1, math.ceil(self.end_silence_ms / self.frame_ms))
        self._start_frames = max(1, math.ceil(self.start_ms / self.frame_ms))
        self._preroll_frames = max(0, math.ceil(self.preroll_ms / self.frame_ms))
        self._max_frames = max(1, int(self.max_speech_s * 1000 / self.frame_ms))

    def set_mode(self, mode: str) -> None:
        """"normal" or "barge_in"."""
        if mode not in ("normal", "barge_in"):
            raise ValueError(mode)
        with self._lock:
            self.mode = mode
            self._apply_mode()

    def set_threshold(self, threshold: float) -> None:
        """Override the start threshold of the current mode (end = threshold - 0.15)."""
        with self._lock:
            self.threshold = float(threshold)
            self.neg_threshold = max(self.threshold - 0.15, 0.01)

    # ------------------------------------------------------------- state
    def reset(self) -> None:
        """Drop any utterance in progress and the model state."""
        with self._lock:
            self._carry = np.zeros(0, dtype=np.int16)
            self._preroll: deque[bytes] = deque(maxlen=max(1, self._preroll_frames))
            self._state = "idle"  # idle | pending | speech
            self._frames: list[bytes] = []
            self._speech_frames = 0      # frames counted as voiced inside the utterance
            self._pending_silence = 0
            self._silence_start: int | None = None  # index into _frames
            self._last_voiced = -1
            self.last_prob = 0.0
            if self._prob_fn is not None and hasattr(self._prob_fn, "reset"):
                try:
                    self._prob_fn.reset()
                except Exception:
                    pass

    @property
    def in_speech(self) -> bool:
        return self._state == "speech"

    def _prob(self, frame_i16: np.ndarray) -> float:
        if self._prob_fn is None:
            self._prob_fn = SileroStream()
        return float(self._prob_fn(frame_i16.astype(np.float32) / 32768.0))

    # ------------------------------------------------------------- main
    def process(self, frame: bytes) -> list[dict]:
        events: list[dict] = []
        with self._lock:
            samples = np.frombuffer(frame, dtype=np.int16) if frame else np.zeros(0, np.int16)
            if len(self._carry):
                samples = np.concatenate([self._carry, samples])
            n = len(samples) // FRAME_SAMPLES
            for k in range(n):
                f = samples[k * FRAME_SAMPLES:(k + 1) * FRAME_SAMPLES]
                self._step(f, f.tobytes(), events)
            self._carry = samples[n * FRAME_SAMPLES:].copy()
        return events

    def _step(self, f: np.ndarray, fb: bytes, events: list[dict]) -> None:
        p = self._prob(f)
        self.last_prob = p
        if self._state == "idle":
            if p >= self.threshold:
                self._frames = list(self._preroll) if self._preroll_frames else []
                self._preroll.clear()
                self._utt_start = len(self._frames)
                self._frames.append(fb)
                self._speech_frames = 1
                self._pending_silence = 0
                self._silence_start = None
                self._last_voiced = len(self._frames) - 1
                self._state = "pending"
                if self._speech_frames >= self._start_frames:
                    self._state = "speech"
                    events.append({"type": "speech_start"})
            elif self._preroll_frames:
                self._preroll.append(fb)
            return

        self._frames.append(fb)
        idx = len(self._frames) - 1

        if self._state == "pending":
            if p >= self.neg_threshold:
                self._speech_frames += 1
                self._pending_silence = 0
                self._last_voiced = idx
            else:
                self._pending_silence += 1
                if self._pending_silence * self.frame_ms > 100:  # blip: give up
                    keep = self._frames[-self._preroll_frames:] if self._preroll_frames else []
                    self._state = "idle"
                    self._frames = []
                    self._preroll.clear()
                    self._preroll.extend(keep)
                    return
            if self._speech_frames >= self._start_frames:
                self._state = "speech"
                self._silence_start = None
                events.append({"type": "speech_start"})
            return

        # state == "speech"
        if p >= self.threshold:
            self._silence_start = None
        elif p < self.neg_threshold and self._silence_start is None:
            self._silence_start = idx
        if self._silence_start is None:
            self._speech_frames += 1
            self._last_voiced = idx

        utt_frames = idx - self._utt_start + 1
        silence_frames = 0 if self._silence_start is None else idx - self._silence_start + 1
        if silence_frames >= self._end_frames or utt_frames >= self._max_frames:
            self._finish(events)

    def _finish(self, events: list[dict]) -> None:
        voiced_ms = self._speech_frames * self.frame_ms
        if voiced_ms < self.min_speech_ms:
            events.append({"type": "speech_cancel"})
        else:
            # keep ~preroll_ms of trailing silence after the last voiced frame
            tail = max(self._preroll_frames, 3)
            end = min(len(self._frames), self._last_voiced + 1 + tail)
            events.append({"type": "speech_end", "audio": b"".join(self._frames[:end])})
        self._state = "idle"
        self._frames = []
        self._silence_start = None
        self._speech_frames = 0
        self._preroll.clear()


__all__ = ["StreamingVAD", "SileroStream", "silero_model_path", "FRAME_SAMPLES"]
