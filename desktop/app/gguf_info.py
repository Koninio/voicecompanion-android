"""Minimal GGUF header reader: just enough metadata to configure llama-server.

GGUF layout (little endian): b"GGUF", u32 version, u64 tensor_count, u64 kv_count,
then kv_count × (string key, u32 type, value). Strings are u64 length + UTF-8 bytes;
arrays are u32 element type + u64 count + elements. Only the header is read — the
tokenizer arrays are skipped, never loaded.
"""
from __future__ import annotations

import logging
import struct
from functools import lru_cache
from pathlib import Path

log = logging.getLogger(__name__)

_FIXED = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
_INT_FMT = {0: "<B", 1: "<b", 2: "<H", 3: "<h", 4: "<I", 5: "<i", 10: "<Q", 11: "<q"}
_STRING, _ARRAY = 8, 9
_WANTED = ("general.architecture", "general.name")


class _Reader:
    def __init__(self, fh):
        self.fh = fh

    def read(self, n: int) -> bytes:
        b = self.fh.read(n)
        if len(b) != n:
            raise EOFError("truncated GGUF header")
        return b

    def u32(self) -> int:
        return struct.unpack("<I", self.read(4))[0]

    def u64(self) -> int:
        return struct.unpack("<Q", self.read(8))[0]

    def string(self) -> str:
        return self.read(self.u64()).decode("utf-8", "replace")

    def skip(self, vtype: int) -> None:
        if vtype in _FIXED:
            self.fh.seek(_FIXED[vtype], 1)
        elif vtype == _STRING:
            self.fh.seek(self.u64(), 1)
        elif vtype == _ARRAY:
            etype, count = self.u32(), self.u64()
            if etype in _FIXED:
                self.fh.seek(_FIXED[etype] * count, 1)
            else:
                for _ in range(count):
                    self.skip(etype)
        else:
            raise ValueError(f"unknown GGUF value type {vtype}")


def read_metadata(path: str | Path) -> dict:
    """Return {"architecture", "name", "expert_count", "expert_used_count", "block_count"}
    (missing keys omitted). Raises on files that are not GGUF."""
    out: dict = {}
    with open(path, "rb") as fh:
        r = _Reader(fh)
        if r.read(4) != b"GGUF":
            raise ValueError("not a GGUF file")
        version = r.u32()
        if version < 2:
            raise ValueError(f"unsupported GGUF version {version}")
        r.u64()                      # tensor count
        for _ in range(r.u64()):     # kv count
            key = r.string()
            vtype = r.u32()
            suffix = key.rsplit(".", 1)[-1]
            if key in _WANTED and vtype == _STRING:
                out[key.split(".", 1)[1]] = r.string()
            elif suffix in ("expert_count", "expert_used_count", "block_count") and vtype in _INT_FMT:
                out[suffix] = struct.unpack(_INT_FMT[vtype], r.read(struct.calcsize(_INT_FMT[vtype])))[0]
            else:
                r.skip(vtype)
    return out


@lru_cache(maxsize=32)
def _cached(path: str, mtime: float, size: int) -> dict:
    return read_metadata(path)


def model_info(path: str | Path) -> dict:
    """Cached metadata plus ``moe`` (bool). Unreadable/missing file → {} (moe unknown)."""
    p = Path(path)
    try:
        st = p.stat()
        info = dict(_cached(str(p), st.st_mtime, st.st_size))
    except (OSError, ValueError, EOFError) as e:
        log.debug("GGUF metadata unavailable for %s: %s", p, e)
        return {}
    info["moe"] = int(info.get("expert_count") or 0) > 0
    return info


def is_moe(path: str | Path) -> bool:
    return bool(model_info(path).get("moe"))
