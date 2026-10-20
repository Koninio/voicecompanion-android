# VoiceCompanion

Gemma 4 12B / 26B A4B(로컬, 무검열 GGUF) + faster-whisper(로컬 STT) + Fish Audio(TTS)로 만든 실시간 음성 대화 봇.
클로드 스타일 채팅 화면 + 오른쪽 보이스 패널. 페르소나 3종: 로맨틱 · 언힌지드 · 시니컬.

## 동작 방식

```
마이크 ─▶ Silero VAD ─(1초 침묵 = 턴 종료)─▶ faster-whisper(한국어) ─▶ Gemma 4 12B/26B (llama-server)
                                                                        │ 문장 단위 스트리밍
스피커 ◀─ 재생 ◀─ Fish Audio TTS (클라우드 API 또는 로컬 fish-speech) ◀──┘
```
- 말하는 도중 끼어들면 봇이 즉시 멈춤(barge-in). 스피커 대신 **이어폰/헤드셋 권장**(에코 제거 없음).
- 턴 종료 대기시간은 설정의 "말 끝 감지" 슬라이더(기본 1000ms).

## 처음 설정

1. 개발 환경: `scripts\setup.ps1` (uv로 Python 3.11 venv + 패키지)
2. 모델 받기: `.venv\Scripts\python.exe scripts\download_models.py` (기본: 무검열 12B, `--model standard`는 Google QAT 원본)
   (llama.cpp CUDA → `bin\llama\`, GGUF → `models\gemma-4-12b-uncensored-Q4_K_M.gguf`, Whisper → `models\whisper\`)
   26B(MoE)는 직접 받아 `models\`에 두고 설정 → 언어 모델에서 선택, "MoE CPU 오프로드"에 99 입력
3. 실행(개발): `.venv\Scripts\python.exe -m app.main`
4. exe 빌드: `scripts\build_exe.ps1` → `dist\VoiceCompanion\VoiceCompanion.exe` (실행 중이면 먼저 앱 종료)
   - 다시 빌드해도 `dist\VoiceCompanion\data`(설정·API 키·대화 기록·캐릭터)는 자동 보존
   - `models`, `bin`은 프로젝트 폴더로 연결(junction)되어 모델을 복사하지 않음 (`-NoLink`이면 빈 폴더)
   - 다른 PC로 옮길 때는 `models`, `bin`을 실제 폴더로 복사

## Fish Audio 목소리 연결

**클라우드 모드 (fish.audio 목소리 그대로)**
1. https://fish.audio 에서 API 키 발급 (API Keys 메뉴)
2. 원하는 목소리 페이지를 열고 URL 끝의 ID 복사
   예: `https://fish.audio/m/7f92f8afb8ec43bf81429cc1c9199cb1` → `7f92f8afb8ec43bf81429cc1c9199cb1`
   (내 목소리를 올리려면 fish.audio에서 보이스 모델 생성 후 같은 방식으로 ID 사용)
3. 앱 설정 → TTS 모드 "클라우드", API 키 입력, 페르소나별 "클라우드 보이스 ID"에 붙여넣기
4. 모델: `s2.1-pro`(유료, 고품질) / `s2.1-pro-free`(테스트용). 요금은 입력 텍스트 길이 기준.

API: `POST https://api.fish.audio/v1/tts`, 헤더 `Authorization: Bearer <키>`, `model: <모델>`,
본문 `{"text", "reference_id", "format":"pcm", "sample_rate":44100, "latency":"balanced"}` → PCM 스트림.

**로컬 모드 (fish-speech, 인터넷 불필요)**
- fish.audio의 보이스 라이브러리는 로컬에서 쓸 수 없음 → 원하는 목소리의 10~30초 깨끗한 음성 + 그 대본을 등록:
  ```
  curl -X POST http://127.0.0.1:8080/v1/references/add -F id=romantic -F "audio=@voice.wav;type=audio/wav" -F text="대본 내용"
  ```
  `id`를 페르소나의 "로컬 보이스 ID"(기본값 romantic / unhinged / sarcastic)와 맞추면 됨.
- fish-speech 서버는 별도 설치(https://speech.fish.audio/install/) 후 `python tools/api_server.py --listen 127.0.0.1:8080 ...`
- 주의: S2-Pro는 VRAM 약 10~12GB 필요 → RTX 3060 Ti(8GB)에서 Gemma와 동시 실행은 어려움. 로컬 TTS를 쓰려면
  설정에서 `llm.n_gpu_layers`를 줄여 LLM 일부를 CPU로 내리거나, 다른 GPU/PC에서 fish-speech 서버를 돌리고 URL만 지정.

## VRAM 예산 (RTX 3060 Ti 8GB)
| 구성 요소 | 위치 | 대략 |
|---|---|---|
| Gemma 4 12B Q4_K_M (dense) | GPU 자동 배치(`--fit`, 1GB 여유) | ~6.9GB — 다른 GPU 앱이 켜져 있으면 일부가 RAM으로 가서 매우 느려짐 |
| Gemma 4 26B A4B Q3_K_M (MoE, 전문가 RAM) | GPU ~2.5GB + RAM ~10GB | Unity 등을 켜둬도 첫 토큰 1.5~2.3초 |
| Whisper large-v3-turbo int8_float16 | GPU (실패 시 CPU 자동 전환) | ~1GB |
| Fish Audio 클라우드 TTS | 원격 | 0 |

## 폴더
- `app/` 서버·LLM·대화(`server.py`, `llm.py`, `conversation.py`), `app/voice/` 마이크·VAD·STT·TTS
- `web/` UI, `personas/` 페르소나 프롬프트(JSON, 수정 가능), `data/` 설정·대화기록·로그
- `tools/agent_monitor.py` 개발 에이전트 감시 엔진, `docs/INTERFACES.md` 모듈 계약서

## FluidAudio 관련
FluidAudio는 Apple 전용(CoreML/Neural Engine, macOS 14+/iOS 17+)이라 Windows에서 동작하지 않음.
같은 목적(완전 로컬 STT)을 Windows에서는 faster-whisper(CUDA/CPU) + Silero VAD(ONNX)로 구현.
