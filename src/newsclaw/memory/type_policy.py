"""记忆类型策略表：类型 → 默认优先级 / TTL / 检索乘子的唯一出处。

此前同一份知识散在四处：retention.py 的 TTL 表、extractor/manager 各自
一份「importance≥0.9 且 persona 类才可 PERMANENT」晋升门、retrieval 的
零散乘子（fact 流水账惩罚等）。收敛成一张表，行为等价迁移、不调参——
调参只改这里。
"""

from __future__ import annotations

from datetime import timedelta

from newsclaw.memory.types import MemoryPriority, MemoryType

#: 类型 → 默认写入优先级（提取 LLM 未指定 duration 时使用）。
TYPE_DEFAULT_PRIORITY: dict[str, MemoryPriority] = {
    MemoryType.RULE.value: MemoryPriority.LONG_TERM,
    MemoryType.PREFERENCE.value: MemoryPriority.LONG_TERM,
    MemoryType.PERSONA_TRAIT.value: MemoryPriority.LONG_TERM,
    MemoryType.FACT.value: MemoryPriority.SHORT_TERM,
    MemoryType.EXPERIENCE.value: MemoryPriority.SHORT_TERM,
}

#: 允许晋升 PERMANENT 的类型（配合高置信度门槛，防任务流水账污染核心记忆）。
PERMANENT_ELIGIBLE_TYPES: frozenset[str] = frozenset(
    {
        MemoryType.RULE.value,
        MemoryType.PREFERENCE.value,
        MemoryType.PERSONA_TRAIT.value,
    }
)

#: PERMANENT 晋升的 importance 门槛。
PERMANENT_IMPORTANCE_FLOOR = 0.9

#: 优先级 → TTL（retention 的原表，语义不变）。
PRIORITY_TTL: dict[MemoryPriority, timedelta | None] = {
    MemoryPriority.TRANSIENT: timedelta(days=1),
    MemoryPriority.SHORT_TERM: timedelta(days=3),
    MemoryPriority.LONG_TERM: timedelta(days=30),
    MemoryPriority.PERMANENT: None,
}

#: 显式 duration → TTL。
DURATION_TTL: dict[str, timedelta | None] = {
    "permanent": None,
    "7d": timedelta(days=7),
    "24h": timedelta(hours=24),
    "session": timedelta(hours=2),
}


def can_be_permanent(mem_type: str, importance: float) -> bool:
    """类型在晋升白名单内且置信度达标。"""
    return mem_type in PERMANENT_ELIGIBLE_TYPES and importance >= PERMANENT_IMPORTANCE_FLOOR
