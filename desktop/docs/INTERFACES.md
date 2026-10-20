# VoiceCompanion — Interface Contract

All three build agents code against THIS file. Do not change a signature here
without the orchestrator's approval; if you need something extra, add it in a
way that keeps these signatures working and say so in your final report.

Target: Windows 11, Python 3.11 (`.venv\Scripts\python.exe`), RTX 3060 Ti 8GB,
i5-12400F, 16GB RAM. Conversation language: Korean.

## Runtime layout (dev and frozen exe)

`app.paths.app_dir()` = folder of the exe when frozen (`sys.frozen`), else the
project root. Everything else is relative to it:

```
<app_dir>/
  data/config.json          user config (created from config.example.json)
  data/history/<persona>.json   chat history per persona
  personas/*.json           persona definitions (shipped)
  models/                   GGUF model + whisper models (models/whisper)
  bin/llama/llama-server.exe  llama.cpp prebuilt (CUDA) binary
  web/                      frontend static files (shipped)
```

`app/paths.py` (owner: A) exposes: `app_dir() -> Path`, `data_dir()`,
`models_dir()`, `personas_dir()`, `web_dir()`, `resolve(p: str) -> Path`
(absolute paths pass through, relative ones resolve against `app_dir()`).
In the frozen build, `personas/` and `web/` are bundled; `paths` must look
in `sys._MEIPASS` first for those two, then `app_dir()`.

## Config — `app/config.py` (owner: A)

Schema = `config.example.json`. API:

```python
def load_config() -> dict            # merges data/config.json over defaults; creates file if missing
def save_config(cfg: dict) -> None
def public_config(cfg: dict) -> dict # same but tts.cloud.api_key masked ("" or "****abcd")
```
Config is a plain nested dict (no pydantic models across module boundaries).

## Personas — `personas/<id>.json` (shipped) + `app/personas.py` (owner: A)

Fields: `id, name, name_ko, tagline, color, avatar_emoji, greeting,
system_prompt, sampling{temperature,top_p,top_k,repeat_penalty,max_tokens},
voice{cloud_reference_id, local_reference_id, speed}`.

```python
def list_personas() -> list[dict]
def get_persona(pid: str) -> dict    # KeyError if unknown
```

## LLM — `app/llm.py` (owner: A)

llama.cpp `llama-server.exe` child process, OpenAI-compatible API.

```python
class LlamaServer:
    def __init__(self, cfg: dict): ...
    def start(self) -> None          # non-blocking; spawns process unless cfg llm.external_url is set
    def wait_ready(self, timeout: float = 120) -> bool   # polls GET /health
    @property
    def state(self) -> str           # "stopped" | "starting" | "ready" | "error" | "missing"
    def stop(self) -> None
    def stream_chat(self, messages: list[dict], sampling: dict,
                    cancel: threading.Event | None = None) -> Iterator[str]  # yields text deltas
```
Spawn args: `-m <model> --port <port> -ngl <n_gpu_layers> -c <ctx_size> --jinja
--host 127.0.0.1` + `extra_args`. Gemma 4: thinking OFF (never put `<|think|>`
in the system prompt). Use CREATE_NO_WINDOW on Windows.

## Text helpers — `app/text_utils.py` (owner: A)

```python
class SentenceChunker:               # feed LLM deltas, get speakable chunks
    def feed(self, delta: str) -> list[str]
    def flush(self) -> list[str]
def clean_for_tts(text: str) -> str  # strip markdown, emoji, *actions*, (stage directions), urls
```
Split on `. ! ? … ~ \n` and Korean sentence enders followed by space/end
(e.g. `다.` `요.` `까?`). First chunk may be emitted early at a comma once it is
>= 25 chars, to cut first-audio latency. Never emit empty/whitespace chunks.

## Voice pipeline — `app/voice/` (owner: B)

PCM convention everywhere: **int16 mono little-endian bytes**.

```python
# app/voice/tts.py
class TTSEngine(Protocol):
    sample_rate: int
    def synthesize_stream(self, text: str, persona: dict,
                          cancel: threading.Event | None = None) -> Iterator[bytes]
    def check(self) -> tuple[bool, str]          # (ok, message) — cheap health check
class FishCloudTTS: ...   # POST https://api.fish.audio/v1/tts, header model=<cfg>, format=pcm, chunked
class FishLocalTTS: ...   # POST <local.url>/v1/tts (msgpack, fish-speech api_server), format=wav/pcm
class NullTTS: ...        # mode "off": yields nothing
def create_tts(cfg: dict) -> TTSEngine

# app/voice/stt.py
class Transcriber:
    def __init__(self, cfg: dict): ...           # lazy-loads faster-whisper on first use / load()
    def load(self) -> None
    def transcribe(self, pcm16k: bytes) -> str   # language from cfg stt.language
    @property
    def state(self) -> str                       # "unloaded"|"loading"|"ready"|"error"
    device: str                                  # actual device used ("cuda"/"cpu")

# app/voice/vad.py
class StreamingVAD:                              # Silero VAD ONNX (asset shipped inside faster_whisper)
    def __init__(self, cfg: dict, sample_rate: int = 16000): ...
    def process(self, frame: bytes) -> list[dict]
        # events: {"type":"speech_start"} / {"type":"speech_end","audio": bytes}
        # speech_end fires after cfg vad.end_silence_ms (default 1000) of silence,
        # carries the whole utterance incl. ~300ms pre-roll; drops utterances < min_speech_ms
    def reset(self) -> None

# app/voice/audio_io.py
class MicStream:   # sounddevice InputStream 16k mono, delivers 512-sample frames to a callback/queue
class Player:      # sounddevice OutputStream; play(pcm_iter, sample_rate) blocking; interrupt(); is_playing

# app/voice/pipeline.py
class VoiceSession:
    def __init__(self, cfg: dict, transcriber: Transcriber, tts: TTSEngine,
                 on_event: Callable[[dict], None]): ...
    def start(self) -> None          # opens mic, runs VAD loop in a daemon thread
    def stop(self) -> None
    @property
    def active(self) -> bool
    def speak(self, chunks: Iterable[str], persona: dict) -> bool
        # blocking. Synthesizes chunk N+1 while chunk N plays. Returns False if interrupted.
    def interrupt(self) -> None      # stop playback + cancel synthesis immediately
```
`on_event` payloads (called from worker threads — consumer must be thread-safe):
- `{"type":"voice_state","state":"idle|listening|user_speaking|transcribing|speaking"}`
- `{"type":"level","rms":0..1}` throttled to ~15 Hz while listening
- `{"type":"user_final","text":"..."}` — after end_silence_ms of silence + STT; empty text is NOT emitted
- `{"type":"barge_in"}` — user started speaking while bot was speaking (if vad.barge_in); session has already interrupted playback
- `{"type":"error","where":"stt|tts|mic","message":"..."}`

While speaking, the mic stays open; to avoid hearing itself, VAD during playback
uses `vad.barge_in_threshold` (higher) and requires >= 300ms of speech.

## Server — `app/server.py`, `app/conversation.py` (owner: A)

FastAPI on `127.0.0.1:<cfg server.port, default 8765>`, serves `web/` at `/`.

Entry points used by `app/main.py` (owner C):

```python
class Runtime:                        # app/server.py — owns LlamaServer, Transcriber, TTS, VoiceSession, Conversation
    def __init__(self, cfg: dict): ...
    def start(self) -> None           # non-blocking: starts llama-server, preloads STT in a background thread
    def shutdown(self) -> None        # stops voice, llama-server; safe to call twice
def create_app(runtime: Runtime) -> FastAPI
```
`main.py` does: `cfg = load_config(); rt = Runtime(cfg); app = create_app(rt)`,
runs uvicorn in a thread, `rt.start()`, opens the window, `rt.shutdown()` on close.
`/api/audio/devices` uses `app.voice.audio_io.list_devices() -> {"input":[{"index","name"}],"output":[...]}` (owner B).

Whisper model storage: `WhisperModel(cfg stt.model, download_root=<models_dir>/whisper)`;
`scripts/download_models.py` pre-fetches with `faster_whisper.utils.download_model(name, cache_dir=<models_dir>/whisper)` so both use the same layout.

REST:
- `GET  /api/personas` → list of persona dicts (without system_prompt)
- `GET  /api/history?persona=id` → `[{"role","content","ts"}]`
- `DELETE /api/history?persona=id`
- `GET  /api/config` → public_config; `PUT /api/config` (partial deep-merge, returns public_config; re-creates TTS engine)
- `GET  /api/status` → `{"llm":state,"stt":state,"tts":{"mode","ok","message"},"voice_active":bool}`
- `GET  /api/audio/devices` → `{"input":[{"index","name"}],"output":[...]}`

WebSocket `/ws` (JSON messages). Client → server:
- `{"type":"chat","persona":"romantic","text":"..."}`
- `{"type":"voice","action":"start"|"stop","persona":"romantic"}`
- `{"type":"set_persona","persona":"..."}`
- `{"type":"interrupt"}` — cancel generation and speech
- `{"type":"speak_replies","enabled":bool}` — speak replies to typed messages too

Server → client:
- `{"type":"user_message","id","text","source":"text|voice"}`
- `{"type":"assistant_start","id","persona"}`
- `{"type":"assistant_delta","id","text"}`
- `{"type":"assistant_end","id","text","interrupted":bool}`
- `{"type":"voice_state","state":...}`, `{"type":"level","rms"}`, `{"type":"barge_in"}`
- `{"type":"status", ...same as /api/status}` (on connect and on change)
- `{"type":"error","message"}`

Turn flow (voice): `user_final` → `user_message` → LLM stream → deltas to UI AND
SentenceChunker → `clean_for_tts` → `VoiceSession.speak(...)` concurrently
(producer/consumer queue) → `assistant_end`. A new `user_final` or `barge_in`
during a reply interrupts it. History keeps the last `llm.history_turns`
(default 20) turns; persona system prompt is always first.

## Frontend — `web/` (owner: C)

Plain HTML/CSS/JS, no build step, no CDN (must work offline inside the exe).
Claude-like layout: left sidebar (3 persona cards, new chat), center chat
(markdown-light rendering, streaming), bottom composer (textarea, send, mic
button). Mic button opens a right-side **Voice panel**: big animated orb driven
by `level`, state label (듣는 중 / 말하는 중 / 생각 중), end button, live
transcript. Settings modal: TTS mode (cloud/local/off), API key, cloud model,
per-persona reference IDs, local server URL, STT model/device, mic/speaker
devices, end-of-turn silence (ms). UI text in Korean.

## Desktop shell & packaging (owner: C)

- `app/main.py`: start uvicorn in a thread, start LlamaServer, open pywebview
  window (`VoiceCompanion`, 1280x820) at `http://127.0.0.1:<port>/`; on close
  stop everything. `--no-window` flag = server only (for tests).
- `VoiceCompanion.spec` (PyInstaller onedir, windowed, icon optional), bundles
  `web/`, `personas/`, faster_whisper assets, nvidia cublas/cudnn DLLs if present.
- `scripts/setup.ps1` (venv + deps), `scripts/download_models.py`
  (llama.cpp CUDA release zip → bin/llama, GGUF → models/, whisper → models/whisper;
  prints sizes, asks y/N unless `--yes`), `scripts/build_exe.ps1`.

## Agent monitor protocol (ALL agents)

Report progress with the helper — at start, at every milestone (at least every
~5 minutes of work), when blocked, and at the end:

```
.venv\Scripts\python.exe tools\agent_status.py --agent <A|B|C> --state working --step "what you're doing" --progress 40
.venv\Scripts\python.exe tools\agent_status.py --agent <A|B|C> --state done --step "summary" --progress 100
```
States: `working | blocked | done | failed`. The orchestrator's monitor
(`tools/agent_monitor.py`) flags an agent as STALLED after 6 min without an
update and runs syntax/test checks on the files it owns.

Only touch the files you own (see table). Tests go in `tests/test_<agent>_*.py`.

| Agent | Owns |
|---|---|
| A — core/LLM/server | `app/__init__.py app/paths.py app/config.py app/personas.py app/llm.py app/text_utils.py app/conversation.py app/server.py tests/test_a_*.py` |
| B — voice | `app/voice/** tests/test_b_*.py` |
| C — UI/shell/build | `web/** app/main.py VoiceCompanion.spec scripts/** tests/test_c_*.py` |
