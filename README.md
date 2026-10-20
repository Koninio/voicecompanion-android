# VoiceCompanion desktop — reference for the Android app

`desktop/` is a read-only copy of the working Windows app (Python + web UI) that the
Android app should match in behaviour. No secrets, models or chat history are included.

What to reuse (port the logic, not the Python):
- `desktop/personas/*.json` — the 3 built-in characters (profile format, Korean).
- `desktop/app/characters.py` — system-prompt builder (`build_system_prompt`), the short
  per-turn reminder (`build_reminder`), validation and limits. Keep the prompt text.
- `desktop/app/conversation.py` — turn flow, history window that drops old messages in
  blocks (keeps the llama.cpp prompt cache valid), reminder on every user message.
- `desktop/app/text_utils.py` — sentence chunking for TTS and Drama 3 tag handling
  (`[direction]`, `<emphasis>`, `<whisper>`, malformed `<word>` → emphasis).
- `desktop/app/voice/tts.py` — Fish Audio cloud TTS: HTTP `/v1/tts` and the WebSocket
  `/v1/tts/live` msgpack protocol (start/text/flush/stop → audio/finish), PCM 44.1 kHz.
- `desktop/app/voice/vad.py`, `pipeline.py`, `stt.py` — Silero VAD end-of-turn after 1 s
  of silence, barge-in, Whisper hallucination filter for Korean.
- `desktop/docs/*.md` — interface contracts; `desktop/web/` — UI look and copy (Korean).
