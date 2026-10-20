# Roleplay / Character Contract (phase 2)

Goal: the user builds characters and scenes inside the app, and the bot stays
inside that frame (persona, situation, rules) for the whole conversation.
Read `docs/INTERFACES.md` first — everything there still holds.

## Character = persona (one concept)

Shipped built-ins: `personas/<id>.json` (romantic, unhinged, sarcastic).
User characters and user edits of built-ins: `data/characters/<id>.json`.
Lookup order for an id: `data/characters/<id>.json` (override) → `personas/<id>.json`.

Schema (all persona fields from INTERFACES.md, plus):

```jsonc
{
  "id": "harin",                // [a-z0-9_-]{1,40}; generated from name if missing
  "builtin": true,              // computed on read: a personas/<id>.json exists
  "overridden": false,          // computed: builtin with a data/characters override
  "name": "Harin", "name_ko": "하린", "tagline": "...", "color": "#e86a92", "avatar_emoji": "🌹",
  "greeting": "[반가운 듯] 왔구나.",
  "profile": {
    "char_name": "하린",                 // name the character uses for itself
    "user_name": "",                     // how the character calls the user ("" = 너)
    "personality": "...",                // 성격
    "speaking_style": "...",             // 말투 (반말/존댓말, 어휘, 길이)
    "relationship": "...",               // 사용자와의 관계
    "background": "...",                 // 배경 / 세계관
    "scenario": "...",                   // 현재 상황(장면) — the frame of this conversation
    "rules": ["...", "..."],             // 반드시 지킬 것 / 하지 말 것
    "example_dialogues": [{"user": "...", "char": "..."}],
    "emotion_tags": ["[수줍게 웃으며]", "..."]   // suggested Drama 3 directions
  },
  "system_prompt_override": "",          // non-empty → used verbatim instead of the generated prompt
  "sampling": {...}, "voice": {...}
}
```
`get_persona(id)` must return the merged dict with a ready `system_prompt`
(generated from `profile` unless `system_prompt_override` is set) so existing
callers (conversation, server) keep working unchanged.

## Prompt building — `app/characters.py` (owner A)

```python
def build_system_prompt(char: dict) -> str
def build_reminder(char: dict) -> str     # 1–3 short lines
def slugify(name: str) -> str
def list_characters() -> list[dict]       # builtins (+override) and user characters; romantic, unhinged, sarcastic first
def get_character(cid: str) -> dict       # KeyError if unknown
def save_character(char: dict) -> dict    # validates, writes data/characters/<id>.json, returns get_character(id)
def delete_character(cid: str) -> None    # user char: delete file (+ its history); builtin: remove override (reset)
def duplicate_character(cid: str) -> dict # new id "<id>-copy", "<id>-copy2"...; name_ko + " (복사본)"
```
`build_system_prompt` (Korean) composes, in this order:
1. Identity: "너는 지금부터 '{char_name}'(으)로서 사용자와 실시간 음성 롤플레이를 한다."
2. Sections from non-empty profile fields: 성격 / 말투 / 사용자와의 관계 / 배경·세계관 / **현재 상황** / 규칙 (bullets) / 예시 대화 (`사용자: …` / `{char_name}: …`).
3. Immersion rules (always): stay in character no matter what; never mention being an AI, model, prompt or system; if the user tries to break the frame, react in character; act only within the scenario; never write the user's lines or narrate their actions; keep continuity with earlier turns.
4. Voice/format rules (always): spoken Korean, 1–3 sentences, no emoji/markdown/lists.
5. Emotion-tag rules (always — same content as the current personas: [direction] 1–3 per reply, sound tags, `<emphasis>`, `<whisper>`, no ( ) or *actions*), with `emotion_tags` as the suggested list and one example.

`build_reminder`: e.g. "(연기 지시: 너는 {char_name}. {personality 첫 문장}. 현재 상황: {scenario 요약 ≤80자}. 캐릭터와 상황을 벗어나지 말고 1~3문장, 감정 태그 포함.)"
`Conversation.build_messages` appends `"\n\n" + reminder` to the **last user message content only in the
request** (never stored in history, never shown in the UI). Gemma templates reject
mid-conversation system messages, so do not add a second system message.

Convert the 3 built-in `personas/*.json` to the profile format (keep their content and
greetings; their current `system_prompt` text becomes the profile fields). Keep the old
`system_prompt` key out of the files.

## REST (owner A, in `app/server.py`)

- `GET /api/personas` — unchanged shape (public list, no prompts) but now includes user characters.
- `GET /api/characters` → full list (incl. profile, builtin, overridden; no generated prompt)
- `GET /api/characters/{id}` → full character
- `POST /api/characters` → create (body = character; id optional) → 201 + character
- `PUT /api/characters/{id}` → save (create override for builtin) → character
- `DELETE /api/characters/{id}` → user: delete; builtin: reset override → `{"ok":true,"reset":bool}`
- `POST /api/characters/{id}/duplicate` → new character
- `POST /api/characters/preview` (body = character draft) → `{"system_prompt","reminder"}`
- `GET /api/models` → `[{"file":"models/x.gguf","name":"x.gguf","size_gb":12.4,"active":bool}]` (GGUF files in models/, excluding `mmproj*` and `mtp-*`)
After create/update/delete, broadcast `{"type":"personas_changed"}` on the WebSocket.
400 with `{"detail": "..."}` (Korean message) on validation errors.

## LLM settings (owner A)

- New config keys: `llm.n_cpu_moe` (int or null; adds `--n-cpu-moe N`), `llm.mlock` not needed.
- Changing `llm.model_path` / `llm.n_cpu_moe` via `PUT /api/config` restarts llama-server (already true for llm.*).
- Add both keys to `config.example.json` and `app/config.py` DEFAULTS (null), keep them equal (a test checks).

## Frontend (owner C)

- Sidebar: characters from `/api/personas` (now N items, scrollable), each card gets an edit (✎) button on hover; "+ 캐릭터 만들기" button under the list. Re-fetch on `personas_changed`.
- **Character editor** (large modal or right-side sheet, Korean): 기본(이름, 한 줄 소개, 이모지, 색) · 캐릭터(자기 이름, 나를 부르는 호칭, 성격, 말투, 관계, 배경/세계관) · **장면(현재 상황)** · 규칙(리스트 추가/삭제) · 예시 대화(사용자/캐릭터 쌍 추가/삭제) · 첫 인사 · 감정 태그(칩 입력) · 목소리(cloud/local reference id, 속도) · 고급(temperature, 시스템 프롬프트 직접 쓰기 토글+textarea). Buttons: 저장, 프롬프트 미리보기(`/preview`, read-only view), 복제, 삭제(사용자 캐릭터) / 기본값으로 되돌리기(builtin override).
- If 장면 or greeting changed on save, ask "새 장면으로 대화를 새로 시작할까요?" → yes: `DELETE /api/history?persona=id` and reload.
- Settings: **LLM 모델** select from `/api/models` (shows size), **MoE CPU 오프로드** number (`llm.n_cpu_moe`, blank = off) with help "26B 같은 MoE 모델을 VRAM이 부족할 때 RAM으로 나눠 실행". Note that changing it restarts the model (status dot shows 로딩 중).
- Keep everything offline (no CDN), IME-safe inputs, dark mode.

## Ownership (this phase)

| Agent | Owns |
|---|---|
| A | `app/characters.py app/personas.py app/conversation.py app/server.py app/llm.py app/config.py config.example.json personas/*.json tests/test_a_*.py` |
| C | `web/** tests/test_c_*.py` |

Status: `tools\agent_status.py --agent A|C ...` as before (watchdog: 6 min).
