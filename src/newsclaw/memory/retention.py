"""Retention policy helpers for semantic memories."""

from __future__ import annotations

from datetime import datetime

from newsclaw.memory.type_policy import DURATION_TTL, PRIORITY_TTL  # noqa: F401 — 单一出处重导出

from .types import SemanticMemory


def apply_retention(memory: SemanticMemory, duration: str | None = None) -> None:
    """Set ``expires_at`` from an explicit duration or the memory priority."""
    if memory.expires_at is not None:
        return

    if duration and duration in DURATION_TTL:
        delta = DURATION_TTL[duration]
    else:
        delta = PRIORITY_TTL.get(memory.priority)

    memory.expires_at = (datetime.now() + delta) if delta else None
