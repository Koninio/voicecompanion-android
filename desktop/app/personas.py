"""Persona API (phase-1 signatures) on top of ``app.characters``.

A persona is a character with a ready ``system_prompt`` (generated from its
profile unless ``system_prompt_override`` is set). See docs/ROLEPLAY.md.
"""
from __future__ import annotations

import logging

from .characters import (DEFAULT_SAMPLING, DEFAULT_VOICE, ORDER, build_system_prompt,
                         get_character, list_characters)

log = logging.getLogger(__name__)

__all__ = ["ORDER", "REQUIRED", "DEFAULT_SAMPLING", "DEFAULT_VOICE", "list_personas",
           "get_persona", "apply_voice_overrides", "public_persona"]

REQUIRED = ["id", "name", "name_ko", "tagline", "color", "avatar_emoji",
            "greeting", "system_prompt", "sampling", "voice"]

# never sent to the UI by /api/personas
_PRIVATE_KEYS = ("system_prompt", "system_prompt_override")


def _with_prompt(c: dict) -> dict:
    c["system_prompt"] = build_system_prompt(c)
    return c


def list_personas() -> list[dict]:
    """Built-ins (romantic, unhinged, sarcastic first) and user characters,
    each with ``system_prompt`` filled in."""
    out = []
    for c in list_characters():
        try:
            out.append(_with_prompt(c))
        except Exception as e:
            log.error("persona %s: cannot build prompt: %s", c.get("id"), e)
    return out


def get_persona(pid: str) -> dict:
    """Merged character dict with ``system_prompt``; KeyError if unknown."""
    if not isinstance(pid, str):
        raise KeyError(pid)
    return _with_prompt(get_character(pid))


def apply_voice_overrides(persona: dict, cfg: dict | None) -> dict:
    """Return a copy of ``persona`` whose ``voice`` is overlaid with
    ``cfg["personas_voice"][<id>]`` (per-persona reference IDs set in the UI)."""
    ov = ((cfg or {}).get("personas_voice") or {}).get(persona.get("id"), None)
    if not isinstance(ov, dict) or not ov:
        return persona
    p = dict(persona)
    voice = dict(p.get("voice") or {})
    for k, v in ov.items():
        if v not in (None, ""):
            voice[k] = v
    p["voice"] = voice
    return p


def public_persona(p: dict) -> dict:
    """Persona without prompts (for the UI)."""
    return {k: v for k, v in p.items() if k not in _PRIVATE_KEYS}
