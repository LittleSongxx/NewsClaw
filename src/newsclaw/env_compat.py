"""Pre-rename environment compatibility.

``OPENAKITA_*`` variables were the public config surface before this project was
renamed. Existing ``.env`` files, launch scripts and service units still set
them, so every legacy name is mirrored onto its current ``NEWSCLAW_*`` spelling
once at startup. The new name always wins when both are present.
"""

from __future__ import annotations

import os
from collections.abc import Mapping, MutableMapping

ENV_PREFIX = "NEWSCLAW_"
LEGACY_ENV_PREFIX = "OPENAKITA_"


def alias_legacy_env(environ: MutableMapping[str, str] | None = None) -> int:
    """Mirror legacy ``OPENAKITA_*`` variables onto ``NEWSCLAW_*`` names.

    Returns the number of variables that were mirrored. Never raises: a
    malformed key is simply skipped so startup cannot be blocked by config.
    """
    env: MutableMapping[str, str] | Mapping[str, str] = os.environ if environ is None else environ
    if not hasattr(env, "__setitem__"):
        return 0

    mirrored = 0
    try:
        items = list(env.items())
    except Exception:
        return 0

    for key, value in items:
        if not isinstance(key, str) or not key.startswith(LEGACY_ENV_PREFIX):
            continue
        suffix = key[len(LEGACY_ENV_PREFIX) :]
        if not suffix:
            continue
        current = ENV_PREFIX + suffix
        if env.get(current):
            continue
        try:
            env[current] = value  # type: ignore[index]
            mirrored += 1
        except Exception:
            continue
    return mirrored
