"""Text-to-speech engines (Fish Audio cloud, self-hosted fish-speech, off).

Every engine yields int16 mono little-endian PCM at ``engine.sample_rate``.
For FishLocalTTS the real rate is read from the streamed WAV header, so read
``sample_rate`` after the first chunk has been yielded.

Fish Audio cloud (https://docs.fish.audio, agent-skill summary at
https://docs.fish.audio/.well-known/agent-skills/fish-audio-api/SKILL.md):
  POST {base_url}/v1/tts
  headers: Authorization: Bearer <key>, model: <s2.1-pro|s2.1-pro-free|s2-pro|...>,
           Content-Type: application/json
  body:    {text, reference_id?, format:"pcm", sample_rate, latency, normalize, prosody:{speed}}
  -> chunked raw audio bytes (16-bit mono PCM for format=pcm); errors are JSON
     {status, message} (401 bad key, 402 no credit, 404 voice not found, 422 validation).

fish-speech local server (tools/api_server.py, fish_speech/utils/schema.py ServeTTSRequest):
  POST {url}/v1/tts, Content-Type: application/msgpack
  body:    {text, chunk_length, format:"wav", references:[], reference_id, seed:None,
            use_memory_cache:"on", normalize:True, streaming:True, latency}
  -> streaming=True only supports wav: a WAV header (wav_chunk_header, data size 0)
     followed by raw int16 PCM chunks.
  GET {url}/v1/health -> {"status":"ok"}
"""
from __future__ import annotations

import logging
import struct
import threading
from typing import Iterable, Iterator, Protocol, runtime_checkable

import httpx

from . import cfg_get

log = logging.getLogger(__name__)


class TTSError(RuntimeError):
    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


@runtime_checkable
class TTSEngine(Protocol):
    sample_rate: int

    def synthesize_stream(self, text: str, persona: dict,
                          cancel: threading.Event | None = None) -> Iterator[bytes]: ...

    def check(self) -> tuple[bool, str]: ...


def _voice(persona: dict | None) -> dict:
    return (persona or {}).get("voice") or {}


def _error_text(resp: httpx.Response) -> str:
    try:
        resp.read()
        data = resp.json()
        if isinstance(data, dict):
            msg = data.get("message") or data.get("detail") or data
            return str(msg)
        return str(data)[:500]
    except Exception:
        try:
            return resp.text[:500]
        except Exception:
            return ""


def _drain_async(it: Iterator) -> None:
    """Consume ``it`` on a daemon thread so whoever feeds it never blocks."""
    def run() -> None:
        try:
            for _ in it:
                pass
        except Exception:  # noqa: BLE001
            pass
    threading.Thread(target=run, name="tts-drain", daemon=True).start()


def _aligned(chunks: Iterator[bytes], cancel: threading.Event | None) -> Iterator[bytes]:
    """Re-chunk a byte stream so every yielded piece holds whole int16 samples."""
    carry = b""
    for chunk in chunks:
        if cancel is not None and cancel.is_set():
            return
        if not chunk:
            continue
        data = carry + chunk
        cut = len(data) - (len(data) % 2)
        carry = data[cut:]
        if cut:
            yield data[:cut]


# ---------------------------------------------------------------------------
class FishCloudTTS:
    """Fish Audio hosted API (format=pcm, streamed)."""

    def __init__(self, cfg: dict, transport: httpx.BaseTransport | None = None,
                 ws_connect=None):
        self.cfg = cfg
        self.api_key = str(cfg_get(cfg, "tts.cloud.api_key", "") or "").strip()
        self.model = str(cfg_get(cfg, "tts.cloud.model", "drama-3") or "drama-3")
        # "websocket": one /v1/tts/live session per reply (directions carry across
        # sentences, lowest latency); "http": one POST /v1/tts per sentence.
        self.transport = str(cfg_get(cfg, "tts.cloud.transport", "websocket") or "websocket").lower()
        self._ws_connect = ws_connect
        self.latency = str(cfg_get(cfg, "tts.cloud.latency", "low") or "low")
        self.base_url = str(cfg_get(cfg, "tts.cloud.base_url", "https://api.fish.audio")).rstrip("/")
        self.language = cfg_get(cfg, "tts.cloud.language")  # optional (e.g. "ko"), normalization only
        self.sample_rate = int(cfg_get(cfg, "tts.sample_rate", 44100))
        self._client = httpx.Client(
            transport=transport,
            timeout=httpx.Timeout(connect=10.0, read=30.0, write=10.0, pool=10.0))

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self.api_key}", "model": self.model,
                "Content-Type": "application/json"}

    def build_body(self, text: str, persona: dict) -> dict:
        v = _voice(persona)
        body: dict = {
            "text": text,
            "format": "pcm",
            "sample_rate": self.sample_rate,
            "latency": self.latency,
            "normalize": True,
            "prosody": {"speed": float(v.get("speed") or 1.0), "volume": 0},
        }
        ref = str(v.get("cloud_reference_id") or "").strip()
        if ref:
            body["reference_id"] = ref
        if self.language:
            body["language"] = str(self.language)
        return body

    def synthesize_stream(self, text: str, persona: dict,
                          cancel: threading.Event | None = None) -> Iterator[bytes]:
        text = (text or "").strip()
        if not text:
            return
        if not self.api_key:
            raise TTSError("Fish Audio API 키가 설정되지 않았습니다 (tts.cloud.api_key)", 401)
        try:
            with self._client.stream("POST", f"{self.base_url}/v1/tts", headers=self._headers(),
                                     json=self.build_body(text, persona)) as resp:
                if resp.status_code != 200:
                    detail = _error_text(resp)
                    hint = {401: "API 키가 올바르지 않습니다",
                            402: "Fish Audio 크레딧이 부족합니다",
                            404: "보이스(reference_id)를 찾을 수 없습니다",
                            422: "요청 형식 오류"}.get(resp.status_code, "TTS 요청 실패")
                    raise TTSError(f"Fish Audio {resp.status_code}: {hint} - {detail}", resp.status_code)
                yield from _aligned(resp.iter_bytes(), cancel)
        except httpx.HTTPError as e:
            raise TTSError(f"Fish Audio 연결 실패: {e}") from e

    # -- WebSocket streaming (wss://api.fish.audio/v1/tts/live) -------------------
    # msgpack frames. client: {"event":"start","request":{...}} → {"event":"text","text"}…
    # → {"event":"flush"} (optional) → {"event":"stop"}; server: {"event":"audio","audio":
    # bytes}… → {"event":"finish","reason":"stop"|"error"}, then closes.
    @property
    def text_streaming(self) -> bool:
        return self.transport == "websocket"

    def synthesize_text_stream(self, chunks: Iterable[str], persona: dict,
                               cancel: threading.Event | None = None) -> Iterator[bytes]:
        """Synthesize a whole reply in one live session while its text is still arriving.

        ``chunks`` is consumed to the end on a sender thread (even after an error), so
        the producer feeding it never blocks.
        """
        import ormsgpack
        from websockets.exceptions import ConnectionClosed, InvalidStatus, WebSocketException

        it = iter(chunks)
        if not self.api_key:
            _drain_async(it)
            raise TTSError("Fish Audio API 키가 설정되지 않았습니다 (tts.cloud.api_key)", 401)
        url = self.base_url.replace("https://", "wss://", 1).replace("http://", "ws://", 1)
        connect = self._ws_connect
        if connect is None:
            from websockets.sync.client import connect
        try:
            ws = connect(f"{url}/v1/tts/live", open_timeout=10, max_size=None,
                         additional_headers={"Authorization": f"Bearer {self.api_key}",
                                             "model": self.model})
        except InvalidStatus as e:
            _drain_async(it)
            code = e.response.status_code
            hint = {401: "API 키가 올바르지 않습니다", 402: "Fish Audio 크레딧이 부족합니다"}.get(
                code, "연결 거부")
            raise TTSError(f"Fish Audio {code}: {hint}", code) from e
        except (OSError, TimeoutError, WebSocketException) as e:
            _drain_async(it)
            raise TTSError(f"Fish Audio 연결 실패: {e}") from e

        pack = ormsgpack.packb
        request = self.build_body("", persona)
        failed: list[BaseException] = []

        def sender() -> None:
            sending = True
            try:
                ws.send(pack({"event": "start", "request": request}))
            except Exception as e:  # noqa: BLE001
                failed.append(e)
                sending = False
            first = True
            for text in it:  # always drain
                if not sending or (cancel is not None and cancel.is_set()):
                    continue
                text = str(text or "").strip()
                if not text:
                    continue
                try:
                    ws.send(pack({"event": "text", "text": text + " "}))
                    if first:  # speak the first sentence now instead of buffering
                        ws.send(pack({"event": "flush"}))
                        first = False
                except Exception as e:  # noqa: BLE001
                    failed.append(e)
                    sending = False
            if sending and not (cancel is not None and cancel.is_set()):
                try:
                    ws.send(pack({"event": "stop"}))
                except Exception as e:  # noqa: BLE001
                    failed.append(e)

        th = threading.Thread(target=sender, name="fish-ws-send", daemon=True)
        th.start()
        carry = b""
        try:
            while True:
                if cancel is not None and cancel.is_set():
                    return
                try:
                    msg = ws.recv(timeout=0.1)
                except TimeoutError:
                    if failed:
                        raise TTSError(f"Fish Audio 전송 실패: {failed[0]}")
                    continue
                except ConnectionClosed as e:
                    if failed:
                        raise TTSError(f"Fish Audio 연결 끊김: {failed[0]}") from e
                    raise TTSError(f"Fish Audio 연결이 끊겼습니다: {e}") from e
                if isinstance(msg, str):
                    continue
                data = ormsgpack.unpackb(msg)
                if not isinstance(data, dict):
                    continue
                ev = data.get("event")
                if ev == "audio":
                    chunk = carry + bytes(data.get("audio") or b"")
                    cut = len(chunk) - (len(chunk) % 2)
                    carry = chunk[cut:]
                    if cut:
                        yield chunk[:cut]
                elif ev == "finish":
                    if data.get("reason") == "error":
                        raise TTSError(f"Fish Audio 합성 실패: {data.get('message') or data}")
                    return
        finally:
            try:
                ws.close()
            except Exception:  # noqa: BLE001
                pass

    def check(self) -> tuple[bool, str]:
        if not self.api_key:
            return False, "Fish Audio API 키가 설정되지 않았습니다"
        try:
            r = self._client.get(f"{self.base_url}/model", params={"page_size": 1, "self": "true"},
                                 headers={"Authorization": f"Bearer {self.api_key}"}, timeout=5.0)
        except httpx.HTTPError as e:
            return False, f"Fish Audio 연결 실패: {e}"
        if r.status_code == 200:
            return True, f"Fish Audio 연결됨 ({self.model})"
        if r.status_code == 401:
            return False, "Fish Audio API 키가 올바르지 않습니다 (401)"
        if r.status_code == 402:
            return False, "Fish Audio 크레딧이 부족합니다 (402)"
        return False, f"Fish Audio 응답 오류 {r.status_code}: {_error_text(r)}"

    def close(self) -> None:
        self._client.close()


# ---------------------------------------------------------------------------
def parse_wav_header(data: bytes) -> tuple[int, int, int, int] | None:
    """Parse a (streaming) RIFF/WAVE header.

    Returns (sample_rate, channels, bits_per_sample, data_offset) or None if more
    bytes are needed. Raises TTSError if the data is not WAV.
    """
    if len(data) < 12:
        return None
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise TTSError("fish-speech 응답이 WAV 형식이 아닙니다")
    pos = 12
    fmt = None
    while True:
        if len(data) < pos + 8:
            return None
        cid = data[pos:pos + 4]
        size = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        body = pos + 8
        if cid == b"data":
            if fmt is None:
                raise TTSError("WAV header has no fmt chunk")
            return fmt[0], fmt[1], fmt[2], body
        if len(data) < body + size:
            return None
        if cid == b"fmt ":
            audio_fmt, channels, rate = struct.unpack("<HHI", data[body:body + 8])
            bits = struct.unpack("<H", data[body + 14:body + 16])[0]
            if audio_fmt not in (1, 0xFFFE):
                raise TTSError(f"unsupported WAV encoding {audio_fmt}")
            fmt = (rate, channels, bits)
        pos = body + size + (size & 1)


def _downmix(pcm: bytes, channels: int) -> bytes:
    if channels == 1:
        return pcm
    import numpy as np

    a = np.frombuffer(pcm, dtype=np.int16).reshape(-1, channels).astype(np.int32)
    return a.mean(axis=1).astype(np.int16).tobytes()


class FishLocalTTS:
    """Self-hosted fish-speech ``tools/api_server.py`` (msgpack request, streamed WAV)."""

    def __init__(self, cfg: dict, transport: httpx.BaseTransport | None = None):
        import ormsgpack  # noqa: F401  (fail early if missing)

        self.cfg = cfg
        self.url = str(cfg_get(cfg, "tts.local.url", "http://127.0.0.1:8080")).rstrip("/")
        self.latency = str(cfg_get(cfg, "tts.local.latency", "normal"))
        if self.latency not in ("normal", "balanced"):
            self.latency = "normal"
        self.chunk_length = int(cfg_get(cfg, "tts.local.chunk_length", 200))
        self.sample_rate = int(cfg_get(cfg, "tts.sample_rate", 44100))  # replaced by WAV header
        self._client = httpx.Client(
            transport=transport,
            timeout=httpx.Timeout(connect=5.0, read=60.0, write=10.0, pool=10.0))

    def build_body(self, text: str, persona: dict) -> dict:
        ref = str(_voice(persona).get("local_reference_id") or "").strip()
        return {
            "text": text,
            "chunk_length": self.chunk_length,
            "format": "wav",
            "references": [],
            "reference_id": ref or None,
            "seed": None,
            "use_memory_cache": "on",
            "normalize": True,
            "streaming": True,
            "latency": self.latency,
        }

    def synthesize_stream(self, text: str, persona: dict,
                          cancel: threading.Event | None = None) -> Iterator[bytes]:
        import ormsgpack

        text = (text or "").strip()
        if not text:
            return
        payload = ormsgpack.packb(self.build_body(text, persona))
        try:
            with self._client.stream("POST", f"{self.url}/v1/tts", content=payload,
                                     headers={"Content-Type": "application/msgpack"}) as resp:
                if resp.status_code != 200:
                    raise TTSError(f"fish-speech {resp.status_code}: {_error_text(resp)}",
                                   resp.status_code)
                head = b""
                channels = 1
                stream = resp.iter_bytes()
                for chunk in stream:
                    if cancel is not None and cancel.is_set():
                        return
                    head += chunk
                    if head[:4] != b"RIFF" and len(head) >= 4:
                        break  # raw PCM (no header): play at configured rate
                    parsed = parse_wav_header(head)
                    if parsed is None:
                        continue
                    rate, channels, bits, off = parsed
                    if bits != 16:
                        raise TTSError(f"fish-speech WAV는 16-bit 여야 합니다 (got {bits})")
                    self.sample_rate = int(rate)
                    head = head[off:]
                    break
                else:
                    if head[:4] == b"RIFF":
                        return  # header only, no audio

                def body():
                    if head:
                        yield head
                    yield from stream

                align = 2 * channels
                carry = b""
                for chunk in body():
                    if cancel is not None and cancel.is_set():
                        return
                    data = carry + chunk
                    cut = len(data) - (len(data) % align)
                    carry = data[cut:]
                    if cut:
                        yield _downmix(data[:cut], channels)
        except httpx.HTTPError as e:
            raise TTSError(f"fish-speech 서버 연결 실패 ({self.url}): {e}") from e

    def check(self) -> tuple[bool, str]:
        try:
            r = self._client.get(f"{self.url}/v1/health", timeout=3.0)
        except httpx.HTTPError as e:
            return False, f"fish-speech 서버에 연결할 수 없습니다 ({self.url}): {e}"
        if r.status_code == 200:
            return True, f"fish-speech 서버 연결됨 ({self.url})"
        return False, f"fish-speech 서버 응답 오류 {r.status_code}"

    def close(self) -> None:
        self._client.close()


# ---------------------------------------------------------------------------
class NullTTS:
    """mode "off": produces no audio."""

    sample_rate = 44100

    def __init__(self, cfg: dict | None = None):
        pass

    def synthesize_stream(self, text: str, persona: dict,
                          cancel: threading.Event | None = None) -> Iterator[bytes]:
        return iter(())

    def check(self) -> tuple[bool, str]:
        return True, "음성 출력 꺼짐"

    def close(self) -> None:
        pass


def create_tts(cfg: dict) -> TTSEngine:
    mode = str(cfg_get(cfg, "tts.mode", "cloud")).lower()
    if mode == "cloud":
        return FishCloudTTS(cfg)
    if mode == "local":
        return FishLocalTTS(cfg)
    return NullTTS(cfg)


__all__ = ["TTSEngine", "TTSError", "FishCloudTTS", "FishLocalTTS", "NullTTS", "create_tts",
           "parse_wav_header"]
