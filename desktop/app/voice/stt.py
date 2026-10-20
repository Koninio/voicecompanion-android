"""Speech-to-text with faster-whisper (CUDA when available, CPU int8 fallback)."""
from __future__ import annotations

import glob
import logging
import os
import re
import sys
import threading
from typing import Callable

import numpy as np

from . import cfg_get, models_dir, resolve_path

log = logging.getLogger(__name__)

_dll_dirs_added = False
_dll_handles: list = []


def add_nvidia_dll_dirs() -> list[str]:
    """Make pip-installed cuBLAS/cuDNN DLLs (site-packages/nvidia/*/bin) loadable.

    Must run before ctranslate2 loads its CUDA backend. Also covers the frozen
    build (``sys._MEIPASS``). Returns the directories added.
    """
    global _dll_dirs_added
    if _dll_dirs_added or os.name != "nt":
        return []
    roots: list[str] = []
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        roots += [os.path.join(meipass, "nvidia"), meipass]
    for p in sys.path:
        if p and os.path.isdir(os.path.join(p, "nvidia")):
            roots.append(os.path.join(p, "nvidia"))
    try:
        import site

        for p in site.getsitepackages() + [site.getusersitepackages()]:
            if os.path.isdir(os.path.join(p, "nvidia")):
                roots.append(os.path.join(p, "nvidia"))
    except Exception:
        pass
    dirs: list[str] = []
    for root in dict.fromkeys(roots):
        for d in glob.glob(os.path.join(root, "*", "bin")) + glob.glob(os.path.join(root, "*", "lib")):
            if os.path.isdir(d) and glob.glob(os.path.join(d, "*.dll")) and d not in dirs:
                dirs.append(d)
        if root == meipass and glob.glob(os.path.join(root, "cublas*.dll")):
            dirs.append(root)
    for d in dirs:
        try:
            _dll_handles.append(os.add_dll_directory(d))
        except Exception:
            pass
    if dirs:  # ctranslate2 also LoadLibrary()s cuBLAS/cuDNN lazily by name -> PATH
        os.environ["PATH"] = os.pathsep.join(dirs + [os.environ.get("PATH", "")])
    _dll_dirs_added = True
    return dirs


def cuda_available() -> bool:
    add_nvidia_dll_dirs()
    try:
        import ctranslate2

        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


# ---------------------------------------------------------------------------
# hallucination filter
# ---------------------------------------------------------------------------
_HALLUCINATION_PATTERNS = [re.compile(p, re.IGNORECASE) for p in (
    r"시청\s*해\s*주\s*셔서\s*(정말\s*)?(감사|고맙)\s*합니다",
    r"시청\s*해\s*주셔서\s*(감사|고마)",
    r"시청\s*(감사|고맙)",
    r"끝까지\s*(시청|봐\s*주셔서)",
    r"구독\s*(과|와|이랑|,)?\s*좋아요",
    r"좋아요\s*(와|과|,)?\s*구독",
    r"구독\s*(부탁|해\s*주세요|하기)",
    r"알림\s*설정",
    r"(MBC|KBS|SBS|JTBC|YTN|TV조선|채널A|MBN|연합)\s*뉴스(\s*[가-힣]{2,4}\s*입니다)?",
    r"[가-힣]{2,4}\s*(기자|앵커)\s*입니다",
    r"자막\s*(제공|제작|협찬|편집)",
    r"(한글\s*)?자막\s*(by|BY|By)",
    r"한글\s*자막",
    r"다음\s*(영상|시간|편)에서\s*(만나요|뵙겠습니다|만나겠습니다|봬요)",
    r"thank(s| you)\s*(so much\s*)?for\s*watching",
    r"subtitles?\s*(by|provided)",
    r"amara\.org",
    r"ご視聴ありがとうございました",
)]
_FILLER_RE = re.compile(
    r"(부탁\s*(드립니다|드려요|드릴게요|해요|합니다)|눌러\s*주세요|해\s*주세요|"
    r"(?<![가-힣])(및|그리고|와|과|감사합니다|고맙습니다)(?![가-힣]))")
_PUNCT_RE = re.compile(r"[\s\.\,\!\?\~\-…·\"'`“”‘’()\[\]{}<>♪♫♬🎵*_/\\|:;。、！？]+")


def filter_hallucination(text: str) -> str:
    """Return "" for well-known Whisper hallucinations on (near-)silence,
    otherwise the text with those phrases removed."""
    t = (text or "").strip()
    if not t:
        return ""
    cleaned = t
    for pat in _HALLUCINATION_PATTERNS:
        cleaned = pat.sub(" ", cleaned)
    meaningful = _PUNCT_RE.sub("", cleaned)
    if not meaningful:
        return ""
    if cleaned != t:
        # a hallucination phrase was present; drop leftover filler, keep substantial remainders
        cleaned = _FILLER_RE.sub(" ", cleaned)
        meaningful = _PUNCT_RE.sub("", cleaned)
        if len(meaningful) < 4:
            return ""
        cleaned = re.sub(r"\s{2,}", " ", cleaned).strip(" ,.")
        return cleaned
    # lone repeated character / filler like "음..." or "아" is noise
    if len(set(meaningful)) == 1 and len(meaningful) <= 2 and meaningful in ("음", "어", "아", "으", "흠"):
        return ""
    return t


# ---------------------------------------------------------------------------
class Transcriber:
    """Lazy, thread-safe faster-whisper wrapper.

    ``model_factory(model_name_or_path, device=, compute_type=, download_root=)``
    is injectable for tests (defaults to ``faster_whisper.WhisperModel``).
    """

    MIN_RMS = 0.002  # utterances quieter than this are treated as silence

    def __init__(self, cfg: dict, model_factory: Callable | None = None):
        self.cfg = cfg
        self._factory = model_factory
        self._model = None
        self._state = "unloaded"
        self._lock = threading.RLock()
        self.device: str = "cpu"
        self.compute_type: str = ""
        self.error: str = ""

    @property
    def state(self) -> str:
        return self._state

    # ------------------------------------------------------------- loading
    def _model_ref(self) -> tuple[str, str]:
        name = str(cfg_get(self.cfg, "stt.model", "large-v3-turbo"))
        mdir = cfg_get(self.cfg, "stt.model_dir")
        root = resolve_path(mdir) if mdir else models_dir() / "whisper"
        # a local directory containing a converted model can be given directly
        cand = resolve_path(name) if ("/" in name or "\\" in name) else None
        if cand is not None and cand.is_dir():
            return str(cand), str(root)
        return name, str(root)

    def _plan(self) -> list[tuple[str, str]]:
        dev = str(cfg_get(self.cfg, "stt.device", "auto")).lower()
        ct = str(cfg_get(self.cfg, "stt.compute_type", "auto")).lower()
        cpu = ("cpu", "int8" if ct in ("auto", "", "float16", "int8_float16") else ct)
        if dev == "cpu":
            return [cpu]
        if dev in ("auto", "cuda", "gpu"):
            if self._factory is None and not cuda_available():
                return [cpu]
            # int8_float16 keeps VRAM low (~1 GB for large-v3-turbo) next to the LLM
            gpu_ct = "int8_float16" if ct in ("auto", "") else ct
            return [("cuda", gpu_ct), cpu]
        return [cpu]

    def _create(self, device: str, compute_type: str):
        if device == "cuda":
            add_nvidia_dll_dirs()
        factory = self._factory
        if factory is None:
            add_nvidia_dll_dirs()
            from faster_whisper import WhisperModel

            factory = WhisperModel
        name, root = self._model_ref()
        os.makedirs(root, exist_ok=True)
        model = factory(name, device=device, compute_type=compute_type, download_root=root,
                        cpu_threads=max(1, min(6, (os.cpu_count() or 4) // 2)))
        # first inference surfaces missing cuBLAS/cuDNN DLLs and OOM errors
        self._run(model, np.zeros(16000, dtype=np.float32))
        return model

    def load(self) -> None:
        with self._lock:
            if self._model is not None:
                return
            self._state = "loading"
            errors = []
            for device, ct in self._plan():
                try:
                    self._model = self._create(device, ct)
                    self.device, self.compute_type = device, ct
                    self._state = "ready"
                    self.error = ""
                    log.info("whisper loaded on %s (%s)", device, ct)
                    return
                except Exception as e:  # any load/first-inference error -> next option
                    log.warning("whisper load failed on %s/%s: %s", device, ct, e)
                    errors.append(f"{device}/{ct}: {e}")
                    self._model = None
            self._state = "error"
            self.error = "; ".join(errors)
            raise RuntimeError(f"STT model load failed: {self.error}")

    def unload(self) -> None:
        with self._lock:
            self._model = None
            self._state = "unloaded"

    # ------------------------------------------------------------- inference
    def _run(self, model, audio: np.ndarray) -> str:
        segments, _info = model.transcribe(
            audio,
            language=str(cfg_get(self.cfg, "stt.language", "ko")) or None,
            beam_size=int(cfg_get(self.cfg, "stt.beam_size", 1)),
            vad_filter=False,
            condition_on_previous_text=False,
            without_timestamps=True,
            temperature=0.0,
        )
        parts = []
        for s in segments:  # generator: decoding happens here
            nsp = getattr(s, "no_speech_prob", 0.0) or 0.0
            alp = getattr(s, "avg_logprob", 0.0) or 0.0
            cr = getattr(s, "compression_ratio", 0.0) or 0.0
            if nsp > 0.6 and alp < -1.0:
                continue
            if cr > 2.4:  # degenerate repetition loop
                continue
            parts.append(s.text)
        return "".join(parts).strip()

    def transcribe(self, pcm16k: bytes) -> str:
        if not pcm16k or len(pcm16k) < 3200:  # < 100 ms
            return ""
        audio = np.frombuffer(pcm16k[: len(pcm16k) // 2 * 2], dtype=np.int16).astype(np.float32) / 32768.0
        if float(np.sqrt(np.mean(audio * audio))) < self.MIN_RMS:
            return ""
        with self._lock:
            if self._model is None:
                self.load()
            try:
                text = self._run(self._model, audio)
            except Exception as e:
                if self.device != "cuda":
                    raise
                # e.g. CUDA OOM after the LLM grew: fall back to CPU and retry once
                log.warning("whisper CUDA inference failed (%s); switching to CPU", e)
                self._model = None
                self._model = self._create("cpu", "int8")
                self.device, self.compute_type = "cpu", "int8"
                text = self._run(self._model, audio)
        return filter_hallucination(text)


__all__ = ["Transcriber", "filter_hallucination", "add_nvidia_dll_dirs", "cuda_available"]
