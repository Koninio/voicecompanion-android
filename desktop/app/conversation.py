"""Turn orchestrator: history per persona, LLM streaming, sentence chunking → TTS.

One reply at a time. A new user turn, ``barge_in`` or ``interrupt()`` cancels the
current reply (cancel event + ``voice.interrupt()``); interrupted replies are
saved with the partial text (``"interrupted": true`` in the history entry).

Events are emitted through ``emit(dict)``, called from worker threads.
"""
from __future__ import annotations

import json
import logging
import os
import queue
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Callable, Iterator

from . import paths
from .characters import build_reminder
from .personas import apply_voice_overrides, get_persona
from .text_utils import SentenceChunker, clean_for_tts, speakable, supports_emotion_tags

log = logging.getLogger(__name__)

MAX_STORED_MESSAGES = 400      # persisted per persona (UI scrollback)
SYNTHETIC_OPENER = "안녕"       # keeps user/assistant alternation when history starts with the greeting


def _now() -> float:
    return round(time.time(), 3)


def _new_id() -> str:
    return uuid.uuid4().hex[:12]


class Turn:
    def __init__(self, persona: dict, text: str, source: str):
        self.id = _new_id()
        self.persona = persona
        self.text = text
        self.source = source
        self.cancel = threading.Event()
        self.voice: Any = None
        self.thread: threading.Thread | None = None


def _iter_queue(q: "queue.Queue[str | None]", cancel: threading.Event) -> Iterator[str]:
    while True:
        try:
            item = q.get(timeout=0.1)
        except queue.Empty:
            if cancel.is_set():
                return
            continue
        if item is None or cancel.is_set():
            return
        yield item


class Conversation:
    """
    voice_provider(source) -> VoiceSession | None
        Called at the start of each reply; returns the session that should speak
        this reply (voice active, or speak_replies on and TTS usable), else None.
    """

    def __init__(self, cfg: dict, llm: Any, emit: Callable[[dict], None],
                 voice_provider: Callable[[str], Any] | None = None,
                 history_dir: Path | None = None):
        self.cfg = cfg
        self.llm = llm
        self._emit_cb = emit
        self._voice_provider = voice_provider or (lambda source: None)
        self._history_dir = Path(history_dir) if history_dir else None
        ui = cfg.get("ui", {}) or {}
        self.persona_id: str = ui.get("default_persona") or "romantic"
        self.speak_replies: bool = bool(ui.get("speak_replies", True))
        self._hist_lock = threading.RLock()
        self._turn_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._current: Turn | None = None

    # -- events ---------------------------------------------------------------
    def _emit(self, ev: dict) -> None:
        try:
            self._emit_cb(ev)
        except Exception:
            log.exception("emit failed")

    # -- history --------------------------------------------------------------
    def _hist_path(self, pid: str) -> Path:
        d = self._history_dir or paths.history_dir()
        safe = "".join(c for c in pid if c.isalnum() or c in "-_") or "default"
        return d / f"{safe}.json"

    def _read(self, pid: str) -> list[dict] | None:
        p = self._hist_path(pid)
        if not p.exists():
            return None
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                data = data.get("messages", [])
            return [m for m in data if isinstance(m, dict) and m.get("role") in ("user", "assistant")]
        except Exception as e:
            log.error("history %s unreadable: %s", p, e)
            return None

    def _write(self, pid: str, msgs: list[dict]) -> None:
        p = self._hist_path(pid)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(p.name + ".tmp")
        tmp.write_text(json.dumps(msgs[-MAX_STORED_MESSAGES:], ensure_ascii=False, indent=1),
                       encoding="utf-8")
        os.replace(tmp, p)

    def _load(self, pid: str) -> list[dict]:
        with self._hist_lock:
            msgs = self._read(pid)
            if not msgs:
                persona = get_persona(pid)
                msgs = []
                if persona.get("greeting"):
                    msgs.append({"role": "assistant", "content": persona["greeting"], "ts": _now()})
                try:
                    self._write(pid, msgs)
                except OSError as e:
                    log.error("cannot write history: %s", e)
            return msgs

    def _append(self, pid: str, entry: dict) -> None:
        with self._hist_lock:
            msgs = self._load(pid)
            msgs.append(entry)
            try:
                self._write(pid, msgs)
            except OSError as e:
                log.error("cannot write history: %s", e)

    def history(self, pid: str) -> list[dict]:
        """``[{"role","content","ts"(epoch seconds)[, "interrupted"]}]``; KeyError if unknown persona."""
        get_persona(pid)
        return [dict(m) for m in self._load(pid)]

    def clear_history(self, pid: str) -> None:
        get_persona(pid)
        with self._state_lock:
            cur = self._current
        if cur is not None and cur.persona["id"] == pid:
            self.interrupt()
        with self._hist_lock:
            try:
                self._hist_path(pid).unlink()
            except FileNotFoundError:
                pass

    def forget_persona(self, pid: str, fallback: str = "romantic") -> None:
        """A character is being deleted: stop its reply, delete its history and
        switch away from it (no get_persona check — it may already be gone)."""
        with self._state_lock:
            cur = self._current
        if cur is not None and cur.persona.get("id") == pid:
            self.interrupt()
            self.wait_idle(3.0)
        with self._hist_lock:
            try:
                self._hist_path(pid).unlink()
            except FileNotFoundError:
                pass
            except OSError as e:
                log.error("cannot delete history for %s: %s", pid, e)
        if self.persona_id == pid:
            self.persona_id = fallback

    def prewarm(self, pid: str | None = None) -> None:
        """Feed the persona's system prompt + history to the LLM in the background.

        llama-server caches the processed prompt prefix, so the first real reply
        skips it (26B with experts in RAM: ~30 s cold → ~1.5 s). Non-blocking."""
        pid = pid or self.persona_id
        if not getattr(self.llm, "caches_prompt", False):
            return  # only llama-server reuses a processed prefix

        def run() -> None:
            deadline = time.monotonic() + 900
            while getattr(self.llm, "state", "ready") != "ready":
                if (getattr(self.llm, "state", "") in ("error", "missing")
                        or time.monotonic() > deadline):
                    return
                time.sleep(1.0)
            if self.busy or pid != self.persona_id:
                return
            try:
                persona = get_persona(pid)
                msgs = self.build_messages(persona) + [{"role": "user", "content": "…"}]
                sampling = {**persona.get("sampling", {}), "max_tokens": 1}
                t0 = time.monotonic()
                for _ in self.llm.stream_chat(msgs, sampling):
                    pass
                log.info("prewarmed %s in %.1fs", pid, time.monotonic() - t0)
            except Exception as e:  # warm-up is best effort
                log.debug("prewarm %s failed: %s", pid, e)

        threading.Thread(target=run, name=f"prewarm-{pid}", daemon=True).start()

    def build_messages(self, persona: dict) -> list[dict]:
        hist = self._load(persona["id"])
        turns = int((self.cfg.get("llm", {}) or {}).get("history_turns", 20))
        n = max(turns, 1) * 2
        # Drop old messages in blocks of n/2 instead of one per turn: the request
        # prefix then stays identical between turns, so llama-server's prompt cache
        # only processes the new message (26B with experts in RAM: ~1.5 s instead
        # of re-reading the whole conversation every turn).
        step = max(n // 2, 2)
        drop = 0 if len(hist) <= n else -(-(len(hist) - n) // step) * step  # ceil to a block
        window = hist[drop:]
        from_start = drop == 0
        merged: list[dict] = []
        for m in window:
            content = (m.get("content") or "").strip()
            if not content:
                continue
            if merged and merged[-1]["role"] == m["role"]:
                merged[-1]["content"] += "\n" + content
            else:
                merged.append({"role": m["role"], "content": content})
        if merged and merged[0]["role"] == "assistant":
            if from_start:
                merged.insert(0, {"role": "user", "content": SYNTHETIC_OPENER})
            else:
                merged.pop(0)
        # Roleplay reminder: request-only (never stored / shown). Gemma templates
        # reject mid-conversation system messages, so it rides on the user turns —
        # on every one of them, so earlier turns look the same in each request and
        # stay in the prompt cache.
        try:
            reminder = build_reminder(persona)
        except Exception:
            log.exception("build_reminder failed")
            reminder = ""
        if reminder:
            for m in merged:
                if m["role"] == "user":
                    m["content"] += "\n\n" + reminder
        return [{"role": "system", "content": persona["system_prompt"]}] + merged

    # -- persona / flags ------------------------------------------------------
    def set_persona(self, pid: str) -> None:
        get_persona(pid)
        if pid != self.persona_id:
            with self._state_lock:
                cur = self._current
            if cur is not None:
                self.interrupt()
            self.persona_id = pid
            self.prewarm(pid)
        self.persona_id = pid

    @property
    def busy(self) -> bool:
        return self._current is not None

    # -- turns ----------------------------------------------------------------
    def submit_user(self, pid: str | None, text: str, source: str = "text") -> str | None:
        """Start a reply to ``text`` (non-blocking). Cancels any running reply.
        Returns the user message id, or None for empty text. KeyError if unknown persona."""
        text = (text or "").strip()
        if not text:
            return None
        pid = pid or self.persona_id
        persona = apply_voice_overrides(get_persona(pid), self.cfg)
        self.persona_id = pid
        turn = Turn(persona, text, source)
        with self._state_lock:
            prev = self._current
            if prev is not None:
                self._cancel_turn(prev)
            self._current = turn
        self._emit({"type": "user_message", "id": turn.id, "text": text, "source": source})
        turn.thread = threading.Thread(target=self._run_turn, args=(turn,),
                                       name=f"turn-{turn.id}", daemon=True)
        turn.thread.start()
        return turn.id

    def interrupt(self) -> None:
        with self._state_lock:
            cur = self._current
        if cur is not None:
            self._cancel_turn(cur)

    def _cancel_turn(self, turn: Turn) -> None:
        turn.cancel.set()
        v = turn.voice
        if v is not None:
            try:
                v.interrupt()
            except Exception:
                log.exception("voice.interrupt failed")

    def wait_idle(self, timeout: float = 10.0) -> bool:
        """Block until no reply is running (tests / shutdown)."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self._state_lock:
                cur = self._current
            if cur is None:
                return True
            if cur.thread is not None:
                cur.thread.join(0.05)
            else:
                time.sleep(0.05)
        return False

    def on_voice_event(self, ev: dict) -> None:
        """Consume VoiceSession events (any thread)."""
        t = ev.get("type")
        if t == "user_final":
            text = (ev.get("text") or "").strip()
            if text:
                try:
                    self.submit_user(self.persona_id, text, "voice")
                except KeyError:
                    self._emit({"type": "error", "message": f"unknown persona {self.persona_id}"})
        elif t == "barge_in":
            self.interrupt()
            self._emit({"type": "barge_in"})
        elif t == "error":
            where = ev.get("where", "")
            msg = ev.get("message", "")
            self._emit({"type": "error", "where": where,
                        "message": f"[{where}] {msg}" if where else msg})
        else:
            self._emit(ev)

    def _run_turn(self, turn: Turn) -> None:
        pid = turn.persona["id"]
        try:
            with self._turn_lock:
                self._append(pid, {"role": "user", "content": turn.text, "ts": _now()})
                if turn.cancel.is_set():
                    return  # superseded before it started
                self._reply(turn)
        except Exception as e:
            log.exception("turn failed")
            self._emit({"type": "error", "message": f"응답 생성 실패: {e}"})
        finally:
            with self._state_lock:
                if self._current is turn:
                    self._current = None

    def _reply(self, turn: Turn) -> None:
        persona = turn.persona
        pid = persona["id"]
        messages = self.build_messages(persona)
        self._emit({"type": "assistant_start", "id": turn.id, "persona": pid})

        speaker = None
        try:
            speaker = self._voice_provider(turn.source)
        except Exception as e:
            log.warning("voice provider failed: %s", e)
        q: "queue.Queue[str | None] | None" = None
        speak_thread: threading.Thread | None = None
        speak_result: dict = {}
        if speaker is not None:
            turn.voice = speaker
            if turn.cancel.is_set():  # cancelled between checks: make sure audio stops
                self._cancel_turn(turn)
            q = queue.Queue()
            speak_thread = threading.Thread(
                target=self._speak_worker, args=(speaker, q, turn, speak_result),
                name=f"speak-{turn.id}", daemon=True)
            speak_thread.start()

        chunker = SentenceChunker()
        parts: list[str] = []
        failed = False
        keep_tags = supports_emotion_tags(self.cfg)
        carry = ""  # tag-only chunk, prefixed to the next speakable one

        def push(chunks: list[str], final: bool = False) -> None:
            nonlocal carry
            if q is None:
                return
            for c in chunks:
                c = clean_for_tts(c, keep_tags=keep_tags)
                if not c:
                    continue
                if keep_tags and not speakable(c):
                    carry = f"{carry} {c}".strip()
                    continue
                if carry:
                    c, carry = f"{carry} {c}", ""
                q.put(c)
            if final:
                carry = ""

        try:
            sampling = persona.get("sampling", {})
            for delta in self.llm.stream_chat(messages, sampling, turn.cancel):
                if turn.cancel.is_set():
                    break
                if not delta:
                    continue
                parts.append(delta)
                self._emit({"type": "assistant_delta", "id": turn.id, "text": delta})
                push(chunker.feed(delta))
            if not turn.cancel.is_set():
                push(chunker.flush(), final=True)
        except Exception as e:
            failed = True
            log.error("LLM stream failed: %s", e)
            self._emit({"type": "error", "where": "llm", "message": str(e)})
        finally:
            if q is not None:
                q.put(None)

        if speak_thread is not None:
            while speak_thread.is_alive():
                speak_thread.join(0.1)
                if turn.cancel.is_set():
                    speak_thread.join(3.0)
                    break

        text = "".join(parts).strip()
        interrupted = turn.cancel.is_set() or speak_result.get("ok") is False or (failed and bool(text))
        if text:
            entry = {"role": "assistant", "content": text, "ts": _now()}
            if interrupted:
                entry["interrupted"] = True
            self._append(pid, entry)
        self._emit({"type": "assistant_end", "id": turn.id, "text": text,
                    "interrupted": bool(interrupted)})

    def _speak_worker(self, speaker: Any, q: "queue.Queue[str | None]", turn: Turn,
                      result: dict) -> None:
        try:
            if turn.cancel.is_set():
                result["ok"] = False
                return
            ok = speaker.speak(_iter_queue(q, turn.cancel), turn.persona)
            result["ok"] = bool(ok) if ok is not None else True
        except Exception as e:
            result["error"] = str(e)  # TTS failure != user interruption
            log.exception("speak failed")
            self._emit({"type": "error", "where": "tts", "message": f"[tts] {e}"})
