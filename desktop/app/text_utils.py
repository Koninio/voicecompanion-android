"""Text helpers: streaming sentence chunking for TTS and TTS text cleanup.

Korean-aware: sentence enders (``다.`` ``요.`` ``까?`` ``~`` ``…``) are split
when followed by whitespace (or at flush); decimals like ``3.5`` and
``안녕~반가워`` are not split. Text inside ``*actions*`` / ``(stage directions)``
is never split, so clean_for_tts can remove those spans whole.
"""
from __future__ import annotations

import re

ENDERS = frozenset(".!?…~\n。！？")
CLOSERS = frozenset("\"'”’)]}」』）")
COMMAS = frozenset(",，、")
OPEN_PARENS = frozenset("(（")
CLOSE_PARENS = frozenset(")）")

_WORD = re.compile(r"\w")
_WS_RUN = re.compile(r"\s+")
# Fish Audio Drama 3 / S2 scoped tags (affect only the enclosed words)
SCOPED_TAGS = ("emphasis", "whisper")
_SCOPED_TAG = re.compile(r"<(/?)(?:" + "|".join(SCOPED_TAGS) + r")>", re.I)


def _has_word(s: str) -> bool:
    return bool(_WORD.search(s))


class SentenceChunker:
    """Feed LLM deltas, get speakable chunks (never empty/whitespace-only)."""

    def __init__(self, early_comma_chars: int = 25, max_chars: int = 220):
        self.early_comma_chars = early_comma_chars
        self.max_chars = max_chars
        self._buf = ""
        self._carry = ""      # punctuation-only fragments, prepended to the next chunk
        self._emitted = 0

    # -- public -------------------------------------------------------------
    def feed(self, delta: str) -> list[str]:
        if not delta:
            return []
        self._buf += delta
        return self._drain(final=False)

    def flush(self) -> list[str]:
        out = self._drain(final=True)
        rest = _WS_RUN.sub(" ", self._carry + self._buf).strip()
        self._buf = ""
        self._carry = ""
        if rest and _has_word(rest):
            out.append(rest)
            self._emitted += 1
        return out

    def reset(self) -> None:
        self._buf = ""
        self._carry = ""
        self._emitted = 0

    # -- internals ----------------------------------------------------------
    def _drain(self, final: bool) -> list[str]:
        out: list[str] = []
        while True:
            idx = self._find_split(self._buf, final)
            if idx is None:
                break
            piece, self._buf = self._buf[:idx], self._buf[idx:]
            chunk = _WS_RUN.sub(" ", self._carry + piece).strip()
            self._carry = ""
            if not chunk:
                continue
            if not _has_word(chunk):
                self._carry = chunk + " "
                continue
            out.append(chunk)
            self._emitted += 1
        return out

    def _find_split(self, buf: str, final: bool) -> int | None:
        n = len(buf)
        if n == 0:
            return None
        paren = 0
        bracket = 0
        scoped = False   # inside <emphasis>…</emphasis> / <whisper>…</whisper>
        star_open = False
        start = 0  # first non-space char
        while start < n and buf[start].isspace() and buf[start] != "\n":
            start += 1
        i = 0
        while i < n:
            ch = buf[i]
            if ch == "*":
                if buf.startswith("**", i):          # bold marker: ignore
                    i += 2
                    continue
                star_open = not star_open
            elif ch in OPEN_PARENS:
                paren += 1
            elif ch in CLOSE_PARENS and paren > 0:
                paren -= 1
            elif ch == "[":
                bracket += 1
            elif ch == "]" and bracket > 0:
                bracket -= 1
            elif ch == "<":
                m = _SCOPED_TAG.match(buf, i)
                if m:
                    scoped = not m.group(1)
                    i = m.end()
                    continue
            inside = star_open or paren > 0 or bracket > 0 or scoped

            if not inside and ch == "\n":
                return i + 1
            if not inside and ch in ENDERS:
                j = i + 1
                while j < n:
                    if buf[j] in ENDERS and buf[j] != "\n":
                        j += 1
                    elif buf[j] in CLOSERS:
                        j += 1
                    elif buf.startswith("**", j):
                        j += 2
                    else:
                        break
                segment = buf[start:i].strip()
                list_marker = ch == "." and segment.isdigit()
                if j >= n:
                    if final and not list_marker:
                        return n
                    return None  # need the next char to decide
                if buf[j].isspace() and not list_marker:
                    return j
                i = j
                continue
            if (not inside and ch in COMMAS and self._emitted == 0
                    and len(buf[start:i + 1].strip()) >= self.early_comma_chars):
                if i + 1 < n and buf[i + 1].isspace():
                    return i + 1
                if i + 1 >= n and not final:
                    return None
            i += 1

        if n > self.max_chars:
            cut = buf.rfind(" ", 0, self.max_chars)
            return cut if cut > 0 else self.max_chars
        return n if final else None


# ---------------------------------------------------------------------------
# clean_for_tts
# ---------------------------------------------------------------------------
_SPECIAL = re.compile(r"<\|?/?[A-Za-z_]+\|?>")
_CODE_BLOCK = re.compile(r"```.*?(?:```|$)", re.S)
_IMAGE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
_LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
_URL = re.compile(r"(?:https?://|www\.)\S+", re.I)
_INLINE_CODE = re.compile(r"`([^`]*)`")
_HTML = re.compile(r"</?[A-Za-z][^>]*>")
_HEADER = re.compile(r"^[ \t]{0,3}#{1,6}[ \t]*", re.M)
_BULLET = re.compile(r"^[ \t]*(?:[-*+•]|\d+[.)])[ \t]+", re.M)
_QUOTE = re.compile(r"^[ \t]*>[ \t]?", re.M)
_BOLD = re.compile(r"(\*\*|__)(.+?)\1", re.S)
_STRIKE = re.compile(r"~~(.+?)~~", re.S)
_ACTION = re.compile(r"\*[^*\n]{1,160}\*")
_PAREN = re.compile(r"[(（][^()（）]{0,200}[)）]")
_BRACKET = re.compile(r"\[[^\[\]]{0,200}\]")
_EMOTICON = re.compile(r"\^[_\-oO.]?\^+|(?<!\w)[:;]-?[()DPp](?!\w)")
_JAMO_RUN = re.compile(r"[ㄱ-ㅎㅏ-ㅣ]{2,}")
_EMOJI = re.compile(
    "["
    "\U0001F000-\U0001FAFF"
    "\U000E0020-\U000E007F"
    "←-⇿"
    "⌀-⏿"
    "■-➿"
    "⬀-⯿"
    "︀-️"
    "‍⃣〰〽㊗㊙™ℹ"
    "]+"
)
_STRAY = re.compile(r"[*#`|_]+")
_SPACE_BEFORE_PUNCT = re.compile(r"\s+([.,!?…~。！？])")
_WS = re.compile(r"\s+")


# ---------------------------------------------------------------------------
# Emotion / delivery tags (Fish Audio Drama 3 and S2 family)
#   [direction]                 point tag / direction, applies from its position on
#   <emphasis>…</emphasis>      scoped: only the enclosed words
#   <whisper>…</whisper>
# ---------------------------------------------------------------------------
EMOTION_TAG_MODELS = frozenset({"drama-3", "s2.1-pro", "s2.1-pro-free", "s2-pro"})
MAX_TAGS_PER_CHUNK = 3
_TAG = re.compile(r"\[([^\[\]\n]{1,60})\]")
_SCOPED_SPAN = re.compile(r"<(" + "|".join(SCOPED_TAGS) + r")>(.*?)</\1>", re.I | re.S)
_SCOPED_MARK = re.compile(r"</?(?:" + "|".join(SCOPED_TAGS) + r")>", re.I)
_ACTION_TO_TAG = re.compile(r"(?<!\*)\*([^*\n]{1,40})\*(?!\*)")      # *웃으며* -> [웃으며]
_PAREN_TO_TAG = re.compile(r"[(（]([^()（）\n\d]{1,30})[)）]")       # (한숨) -> [한숨]
_HOLD = re.compile("(\\d+)")
# <words> that are not HTML/scoped tags (models sometimes write <강조> for emphasis)
_ANGLE_WORDS = re.compile(r"<(?![A-Za-z/!|])([^<>\n]{1,40})>")
_STRAY_ANGLE = re.compile(r"[<>]")


def supports_emotion_tags(cfg: dict | None) -> bool:
    """True when the configured TTS understands [direction] tags."""
    tts = (cfg or {}).get("tts") or {}
    if not tts.get("emotion_tags", True) or str(tts.get("mode", "")).lower() != "cloud":
        return False
    return str((tts.get("cloud") or {}).get("model", "")).lower() in EMOTION_TAG_MODELS


def strip_tags(text: str) -> str:
    """Remove [direction] tags and scoped-tag markers, keeping the spoken words."""
    t = _TAG.sub(" ", text or "")
    t = _SCOPED_MARK.sub("", t)
    return _WS.sub(" ", t).strip()


def speakable(text: str) -> bool:
    """True when ``text`` contains words to say (tags alone are not speakable)."""
    return _has_word(strip_tags(text))


def _clean_tagged(text: str) -> str:
    keep: list[str] = []

    def hold(s: str) -> str:
        keep.append(s)
        return f" {len(keep) - 1} "

    t = _ACTION_TO_TAG.sub(r"[\1]", text)
    t = _PAREN_TO_TAG.sub(r"[\1]", t)

    def scoped(m: re.Match) -> str:
        inner = _clean_core(m.group(2))
        if not _has_word(inner):
            return " "
        name = m.group(1).lower()
        return hold(f"<{name}>{inner}</{name}>")

    t = _SCOPED_SPAN.sub(scoped, t)
    t = _SCOPED_MARK.sub(" ", t)          # unbalanced leftovers

    def angle(m: re.Match) -> str:        # model wrote <단어> meaning emphasis
        inner = _clean_core(m.group(1))
        return hold(f"<emphasis>{inner}</emphasis>") if _has_word(inner) else " "

    t = _ANGLE_WORDS.sub(angle, t)
    count = 0

    def tag(m: re.Match) -> str:
        nonlocal count
        inner = _WS.sub(" ", _EMOJI.sub(" ", m.group(1))).strip(" \"'.,")
        if not inner or count >= MAX_TAGS_PER_CHUNK:
            return " "
        count += 1
        return hold(f"[{inner}]")

    t = _TAG.sub(tag, t)
    t = _clean_core(t)
    t = _HOLD.sub(lambda m: keep[int(m.group(1))], t)
    t = _WS.sub(" ", t)
    return _SPACE_BEFORE_PUNCT.sub(r"\1", t).strip()


def clean_for_tts(text: str, keep_tags: bool = False) -> str:
    """Strip markdown, emoji, *actions*, (stage directions), URLs → plain speakable text.

    With ``keep_tags`` (TTS model understands them), [direction] tags and
    <emphasis>/<whisper> spans are kept (max 3 tags per chunk) and *actions* /
    short (stage directions) are turned into [tags] instead of being dropped.
    The result may then consist of tags only — check it with ``speakable()``.
    Returns "" when nothing remains.
    """
    if not text:
        return ""
    if keep_tags:
        t = _clean_tagged(text)
        return t if (_has_word(t)) else ""
    t = _clean_core(text)
    if not _has_word(t):
        return ""
    return t


def _clean_core(text: str) -> str:
    t = _SPECIAL.sub(" ", text)
    t = _CODE_BLOCK.sub(" ", t)
    t = _IMAGE.sub(" ", t)
    t = _LINK.sub(r"\1", t)
    t = _URL.sub(" ", t)
    t = _INLINE_CODE.sub(r"\1", t)
    t = _HTML.sub(" ", t)
    t = _ANGLE_WORDS.sub(r"\1", t)
    t = _STRAY_ANGLE.sub(" ", t)
    t = _HEADER.sub("", t)
    t = _BULLET.sub("", t)
    t = _QUOTE.sub("", t)
    t = _BOLD.sub(r"\2", t)
    t = _STRIKE.sub(r"\1", t)
    t = _ACTION.sub(" ", t)
    prev = None
    while prev != t:                     # nested parentheses
        prev = t
        t = _PAREN.sub(" ", t)
    t = _BRACKET.sub(" ", t)
    t = _EMOJI.sub(" ", t)
    t = _EMOTICON.sub(" ", t)
    t = _JAMO_RUN.sub(" ", t)
    t = _STRAY.sub(" ", t)
    t = _WS.sub(" ", t)
    return _SPACE_BEFORE_PUNCT.sub(r"\1", t).strip()
