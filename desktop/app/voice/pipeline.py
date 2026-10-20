"""VoiceSession: mic -> VAD -> STT -> user_final, and pipelined TTS playback with barge-in."""
from __future__ import annotations

import logging
import math
import queue
import threading
import time
from typing import Callable, Iterable

import numpy as np

from . import cfg_get
from .audio_io import MicStream, Player
from .vad import StreamingVAD

log = logging.getLogger(__name__)

LEVEL_INTERVAL = 0.06  # ~15 Hz (mic frames may arrive in 2-frame bursts)


def level_from_frame(frame: bytes) -> float:
    """Perceptual mic level 0..1 from an int16 frame (RMS mapped from -60..-10 dBFS)."""
    a = np.frombuffer(frame[: len(frame) // 2 * 2], dtype=np.int16).astype(np.float32) / 32768.0
    if a.size == 0:
        return 0.0
    rms = float(np.sqrt(np.mean(a * a)))
    if rms <= 1e-6:
        return 0.0
    db = 20.0 * math.log10(rms)
    return max(0.0, min(1.0, (db + 60.0) / 50.0))


class VoiceSession:
    """See docs/INTERFACES.md. Extra keyword-only args are for tests / embedding:

    mic_factory(cfg) -> object with start(), stop(), read(timeout) -> bytes|None
    player         -> object with play(pcm_iter, sample_rate) -> bool, interrupt(), is_playing
    vad            -> StreamingVAD-like (process/reset/set_mode/configure/in_speech)
    """

    def __init__(self, cfg: dict, transcriber, tts, on_event: Callable[[dict], None], *,
                 mic_factory: Callable[[dict], object] | None = None,
                 player=None, vad=None):
        self.cfg = cfg
        self.transcriber = transcriber
        self.tts = tts
        self.on_event = on_event
        self._mic_factory = mic_factory or (lambda c: MicStream(c))
        self.player = player if player is not None else Player(cfg)
        self._vad = vad

        self._lock = threading.RLock()        # state + emit ordering
        self._speak_lock = threading.Lock()   # one speak() at a time
        self._active = False
        self._mic = None
        self._mic_thread: threading.Thread | None = None
        self._stt_thread: threading.Thread | None = None
        self._stt_q: "queue.Queue[bytes | None]" | None = None
        self._stop_ev = threading.Event()

        self._user_speaking = False
        self._transcribing = 0
        self._speaking = False
        self._barged = False
        self._cancel: threading.Event | None = None
        self._state = "idle"
        self._last_level = 0.0

    # ------------------------------------------------------------------ events
    def _emit(self, ev: dict) -> None:
        try:
            self.on_event(ev)
        except Exception:
            log.exception("on_event failed for %s", ev.get("type"))

    def _error(self, where: str, message: str) -> None:
        self._emit({"type": "error", "where": where, "message": message})

    def _compute_state(self) -> str:
        if self._user_speaking and self._active:
            return "user_speaking"
        if self._speaking:
            return "speaking"
        if self._transcribing and self._active:
            return "transcribing"
        if self._active:
            return "listening"
        return "idle"

    def _update_state(self) -> None:
        with self._lock:
            s = self._compute_state()
            if s != self._state:
                self._state = s
                self._emit({"type": "voice_state", "state": s})

    @property
    def state(self) -> str:
        return self._state

    @property
    def active(self) -> bool:
        return self._active

    def _barge_enabled(self) -> bool:
        return bool(cfg_get(self.cfg, "vad.barge_in", True))

    # ------------------------------------------------------------------ config
    def set_tts(self, tts) -> None:
        """Swap the TTS engine (takes effect at the next speak())."""
        self.tts = tts

    def update_config(self, cfg: dict) -> None:
        self.cfg = cfg
        if self._vad is not None and hasattr(self._vad, "configure"):
            self._vad.configure(cfg)

    def _get_vad(self):
        if self._vad is None:
            self._vad = StreamingVAD(self.cfg)
        return self._vad

    # ------------------------------------------------------------------ mic side
    def start(self) -> None:
        with self._lock:
            if self._active:
                return
            try:
                vad = self._get_vad()
                vad.reset()
                if self._speaking and self._barge_enabled():
                    vad.set_mode("barge_in")
            except Exception as e:
                self._error("mic", f"VAD 초기화 실패: {e}")
                return
            try:
                mic = self._mic_factory(self.cfg)
                mic.start()
            except Exception as e:
                self._error("mic", f"마이크를 열 수 없습니다: {e}")
                return
            self._mic = mic
            self._stop_ev = threading.Event()
            self._stt_q = queue.Queue()
            self._user_speaking = False
            self._active = True
            self._mic_thread = threading.Thread(target=self._mic_loop, args=(mic, self._stop_ev),
                                                name="voice-mic", daemon=True)
            self._stt_thread = threading.Thread(target=self._stt_loop, args=(self._stt_q,),
                                                name="voice-stt", daemon=True)
            self._mic_thread.start()
            self._stt_thread.start()
            self._update_state()
        if getattr(self.transcriber, "state", "") == "unloaded":
            threading.Thread(target=self._preload_stt, name="voice-stt-load", daemon=True).start()

    def _preload_stt(self) -> None:
        try:
            self.transcriber.load()
        except Exception as e:
            self._error("stt", f"음성 인식 모델 로드 실패: {e}")

    def stop(self) -> None:
        with self._lock:
            if not self._active:
                return
            self._active = False
            self._stop_ev.set()
            mic, self._mic = self._mic, None
            mt, self._mic_thread = self._mic_thread, None
            q, self._stt_q = self._stt_q, None
            self._user_speaking = False
        if mic is not None:
            try:
                mic.stop()
            except Exception:
                pass
        if mt is not None and mt is not threading.current_thread():
            mt.join(timeout=2.0)
        if q is not None:
            q.put(None)
        self.interrupt()
        if self._vad is not None:
            self._vad.reset()
        self._update_state()

    def _mic_loop(self, mic, stop_ev: threading.Event) -> None:
        started = time.monotonic()
        while not stop_ev.is_set():
            try:
                frame = mic.read(timeout=0.1)
            except Exception as e:
                if not stop_ev.is_set():
                    self._error("mic", f"마이크 읽기 오류: {e}")
                    threading.Thread(target=self.stop, daemon=True).start()
                return
            if frame is None:
                if (hasattr(mic, "active") and not mic.active and not stop_ev.is_set()
                        and time.monotonic() - started > 1.0):
                    self._error("mic", "마이크 입력이 중단되었습니다")
                    threading.Thread(target=self.stop, daemon=True).start()
                    return
                continue
            try:
                self.process_frame(frame)
            except Exception as e:
                log.exception("voice frame processing failed")
                self._error("mic", f"음성 처리 오류: {e}")

    def process_frame(self, frame: bytes) -> None:
        """Handle one mic frame (called by the mic thread; public for tests)."""
        now = time.monotonic()
        if now - self._last_level >= LEVEL_INTERVAL and self._state in ("listening", "user_speaking"):
            self._last_level = now
            self._emit({"type": "level", "rms": round(level_from_frame(frame), 3)})

        if self._speaking and not self._barge_enabled():
            return  # no barge-in: ignore the mic (and our own echo) while talking
        vad = self._get_vad()
        for ev in vad.process(frame):
            t = ev["type"]
            if t == "speech_start":
                if self._speaking and self._barge_enabled() and not self._barged:
                    with self._lock:
                        self._barged = True
                        self._user_speaking = True
                        self._speaking = False
                        self.interrupt()          # contract: already interrupted when barge_in arrives
                        vad.set_mode("normal")    # keep recording this utterance normally
                        self._emit({"type": "barge_in"})
                        self._update_state()
                else:
                    with self._lock:
                        self._user_speaking = True
                        self._update_state()
            elif t == "speech_end":
                q = self._stt_q
                with self._lock:
                    self._user_speaking = False
                    if q is not None:
                        self._transcribing += 1
                    self._update_state()
                if q is not None:
                    q.put(ev["audio"])
            elif t == "speech_cancel":
                with self._lock:
                    self._user_speaking = False
                    self._update_state()

    def _stt_loop(self, q: "queue.Queue[bytes | None]") -> None:
        while True:
            audio = q.get()
            if audio is None:
                return
            text = ""
            try:
                text = self.transcriber.transcribe(audio) or ""
            except Exception as e:
                log.exception("transcription failed")
                self._error("stt", f"음성 인식 실패: {e}")
            with self._lock:
                self._transcribing = max(0, self._transcribing - 1)
                if text.strip() and self._active and self._stt_q is q:
                    self._emit({"type": "user_final", "text": text.strip()})
                self._update_state()

    # ------------------------------------------------------------------ speaker side
    def interrupt(self) -> None:
        with self._lock:
            c = self._cancel
        if c is not None:
            c.set()
        try:
            self.player.interrupt()
        except Exception:
            pass

    def _begin_speaking(self) -> None:
        with self._lock:
            self._speaking = True
            self._barged = False
            if self._vad is not None and self._barge_enabled():
                self._vad.set_mode("barge_in")
            self._update_state()

    def _end_speaking(self) -> None:
        with self._lock:
            was = self._speaking or self._barged
            self._speaking = False
            vad = self._vad
            if was and vad is not None:
                vad.set_mode("normal")
                if not self._barged and not getattr(vad, "in_speech", False):
                    vad.reset()  # forget echo of our own voice
            self._barged = False
            self._update_state()

    def speak(self, chunks: Iterable[str], persona: dict) -> bool:
        """Blocking. Synthesizes chunk N+1 while chunk N plays. False if interrupted."""
        with self._speak_lock:
            cancel = threading.Event()
            with self._lock:
                self._cancel = cancel
            tts = self.tts
            q: "queue.Queue[tuple]" = queue.Queue()
            th = threading.Thread(target=self._synth_loop, args=(tts, chunks, persona, cancel, q),
                                  name="voice-tts", daemon=True)
            th.start()
            ctx = {"pending": None, "done": False}
            speaking = False
            try:
                while not ctx["done"] and not cancel.is_set():
                    item = ctx["pending"] or self._q_get(q, cancel)
                    ctx["pending"] = None
                    if item is None:  # cancelled while waiting
                        break
                    kind = item[0]
                    if kind == "audio":
                        if not speaking:
                            speaking = True
                            self._begin_speaking()
                        sr = item[1]
                        self.player.play(self._pcm_gen(q, sr, item[2], cancel, ctx), sr)
                    elif kind == "error":
                        # TTS failure is reported, not treated as an interruption;
                        # the remaining chunks are dropped (finally cancels synthesis).
                        self._error("tts", str(item[1]))
                        return True
                    elif kind == "end":
                        ctx["done"] = True
                return not cancel.is_set()
            finally:
                if not ctx["done"]:
                    cancel.set()
                if speaking:
                    self._end_speaking()
                with self._lock:
                    if self._cancel is cancel:
                        self._cancel = None

    @staticmethod
    def _q_get(q: "queue.Queue[tuple]", cancel: threading.Event):
        while not cancel.is_set():
            try:
                return q.get(timeout=0.05)
            except queue.Empty:
                continue
        return None

    def _pcm_gen(self, q, sr: int, first: bytes, cancel: threading.Event, ctx: dict):
        if cancel.is_set():
            return
        yield first
        while not cancel.is_set():
            item = self._q_get(q, cancel)
            if item is None:
                return
            if item[0] == "audio":
                if item[1] != sr:  # engine changed rate: re-open the player
                    ctx["pending"] = item
                    return
                if cancel.is_set():
                    return
                yield item[2]
            elif item[0] == "end":
                ctx["done"] = True
                return
            else:
                ctx["pending"] = item
                return

    @staticmethod
    def _synth_loop(tts, chunks: Iterable[str], persona: dict, cancel: threading.Event, q) -> None:
        it = iter(chunks)
        if getattr(tts, "text_streaming", False):
            # One live session for the whole reply; the engine drains `it` itself.
            try:
                for pcm in tts.synthesize_text_stream(it, persona, cancel):
                    if cancel.is_set():
                        return
                    if pcm:
                        q.put(("audio", int(tts.sample_rate), pcm))
            except Exception as e:
                log.warning("TTS failed: %s", e)
                q.put(("error", e))
                return
            q.put(("end",))
            return
        try:
            for text in it:
                if cancel.is_set():
                    return
                if not text or not str(text).strip():
                    continue
                for pcm in tts.synthesize_stream(str(text), persona, cancel):
                    if cancel.is_set():
                        return
                    if pcm:
                        q.put(("audio", int(tts.sample_rate), pcm))
        except Exception as e:
            log.warning("TTS failed: %s", e)
            q.put(("error", e))
            try:  # keep consuming so a producer feeding `chunks` never blocks
                for _ in it:
                    pass
            except Exception:
                pass
            return
        q.put(("end",))


__all__ = ["VoiceSession", "level_from_frame"]
