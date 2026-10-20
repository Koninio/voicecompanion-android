# VoiceCompanion for Android

A Korean real-time voice roleplay chat app: Android port of the Windows app in `desktop/`.
It has the same 3 built-in characters (로맨틱 · 언힌지드 · 시니컬), a character editor,
Fish Audio Drama 3 emotion tags, end-of-turn detection and barge-in.

## APK 받기

Every push builds an APK with GitHub Actions (`.github/workflows/build-apk.yml`).

- **Releases** page → the latest `VoiceCompanion build N` pre-release → `VoiceCompanion-N.apk`
  (easiest from a phone), or
- **Actions** → *Build APK* run → artifact `VoiceCompanion-apk-N` (a zip with the APK).

On the phone, allow "install unknown apps" for the browser / file manager, then open the APK.
Builds after the first one are signed with the same key (cached in Actions), so they install
over the previous version without losing chats. If the cache expired (7 days without a
build) Android refuses the update: uninstall and install again.

## 설정 (앱 → ⚙)

| 항목 | 내용 |
|---|---|
| 언어 모델 | OpenAI 호환 서버 주소. PC의 llama-server: `llama-server ... --host 0.0.0.0 --port 8081` 로 실행하고 `http://<PC IP>:8081` 입력 (같은 와이파이). OpenRouter 등 클라우드: `https://openrouter.ai/api` + API 키 + 모델 이름 |
| 목소리 (TTS) | Fish Audio 클라우드(API 키, 모델 `drama-3` 등, 캐릭터별 보이스 ID) · 로컬 fish-speech 서버 · 끄기 |
| 음성 인식 | **Android 기본**(설정 불필요) 또는 **Whisper API**(데스크톱과 같은 Silero VAD + Whisper; Groq/OpenAI 등 `/v1/audio/transcriptions`) |
| 대화 감지 | 말 끝 인식 대기 시간(기본 1000 ms), 끼어들기(barge-in). 스피커 대신 이어폰 권장 |

## 구조

```
app/src/main/java/com/voicecompanion/android/
  core/Characters.kt     characters.py  — 프롬프트 빌더, 검증, 저장 (내장 + 사용자 캐릭터)
  core/Conversation.kt   conversation.py — 턴 흐름, 기록 창(블록 단위 삭제), 연기 지시 리마인더
  core/TextUtils.kt      text_utils.py  — TTS 문장 분할, 감정 태그 정리
  llm/LlmClient.kt       OpenAI 호환 스트리밍 (llama-server 대체)
  voice/Tts.kt           tts.py — Fish Audio HTTP / WebSocket live(msgpack), fish-speech
  voice/Vad.kt           vad.py — Silero VAD v6 (onnxruntime), 말 끝 감지
  voice/Stt.kt           stt.py — Whisper API + 한국어 환각 필터
  voice/VoiceSession.kt  pipeline.py — 마이크 → 인식 → 답장, 재생, 끼어들기
  ui/                    web/ UI (Views, 라이트/다크)
```

The Kotlin port of the prompt builder, sentence chunker, TTS text cleanup, hallucination
filter and VAD state machine was checked against the Python code on the same inputs
(identical output).

## Reference: `desktop/`

`desktop/` is a read-only copy of the working Windows app (Python + web UI) that the
Android app matches in behaviour. No secrets, models or chat history are included.
