"""编辑方针（data/newsroom/editorial-policy.md）的加载与原子保存。

复盘不得整文件重写本文件；只允许按 bullet id 追加 / 替换 / 删除。
每日管线由 prompts 在任务启动时注入全文，不靠模型自觉 read_file。
"""

from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

from .contract import newsroom_root

logger = logging.getLogger(__name__)

EDITORIAL_POLICY_FILENAME = "editorial-policy.md"
MAX_POLICY_BULLETS = 30

_OFFICIAL_BULLET = re.compile(r"^- \[([^\]]+)\]\s+(.*)$")
_PLAIN_BULLET = re.compile(r"^- (?!\[).+")

_DEFAULT_PREAMBLE = (
    "# 编辑方针\n\n"
    "每日管线按下列条目执行。只允许按 id 追加/替换/删除，禁止整文件重写。\n"
    f"条数上限 {MAX_POLICY_BULLETS}。\n"
)


@dataclass
class PolicyBullet:
    """一条可寻址的方针。id 稳定，apply 只动指定 id。"""

    id: str
    text: str


@dataclass
class EditorialPolicy:
    """方针文件：题头（人手写说明）+ 结构化条目。"""

    preamble: str = ""
    bullets: list[PolicyBullet] = field(default_factory=list)

    def to_text(self) -> str:
        lines: list[str] = []
        preamble = (self.preamble or "").rstrip()
        if preamble:
            lines.append(preamble)
            lines.append("")
        for bullet in self.bullets:
            lines.append(f"- [{bullet.id}] {bullet.text}")
        if not lines:
            return ""
        return "\n".join(lines) + "\n"


def editorial_policy_path() -> Path:
    return newsroom_root() / EDITORIAL_POLICY_FILENAME


def parse_editorial_policy(text: str) -> EditorialPolicy:
    """解析方针正文。官方形态是 ``- [id] 文本``；无 id 的 ``- 文本`` 记为 legacy-N。"""
    preamble_lines: list[str] = []
    bullets: list[PolicyBullet] = []
    seen: set[str] = set()
    legacy_n = 0
    for line in text.splitlines():
        stripped = line.strip()
        match = _OFFICIAL_BULLET.match(stripped)
        if match:
            bullet_id = match.group(1).strip()
            body = match.group(2).strip()
            if not bullet_id:
                preamble_lines.append(line)
                continue
            if bullet_id in seen:
                logger.warning("[Newsroom] duplicate policy bullet id %s; keeping first", bullet_id)
                continue
            seen.add(bullet_id)
            bullets.append(PolicyBullet(id=bullet_id, text=body))
            continue
        if _PLAIN_BULLET.match(stripped):
            legacy_n += 1
            bullet_id = f"legacy-{legacy_n}"
            while bullet_id in seen:
                legacy_n += 1
                bullet_id = f"legacy-{legacy_n}"
            seen.add(bullet_id)
            bullets.append(PolicyBullet(id=bullet_id, text=stripped[2:].strip()))
            continue
        preamble_lines.append(line)
    preamble = "\n".join(preamble_lines).rstrip()
    if preamble:
        preamble += "\n"
    return EditorialPolicy(preamble=preamble, bullets=bullets)


def ensure_editorial_policy_file() -> Path:
    """首期落盘默认方针，避免管线 read_file 去读一个还不存在的文件。"""
    path = editorial_policy_path()
    if path.is_file():
        return path
    return save_editorial_policy(EditorialPolicy(preamble=_DEFAULT_PREAMBLE, bullets=[]))


def load_editorial_policy() -> EditorialPolicy:
    """读取方针；文件缺失时返回带默认题头的空条目（不主动落盘）。

    文件存在但读不了时抛 ``ValueError``：方针由每日注入块全文携带，静默
    退回空方针等于管线在无方针状态下照跑。
    """
    path = editorial_policy_path()
    if not path.is_file():
        return EditorialPolicy(preamble=_DEFAULT_PREAMBLE, bullets=[])
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise ValueError(f"editorial-policy.md unreadable ({path}): {exc}") from exc
    parsed = parse_editorial_policy(text)
    if not parsed.preamble.strip() and not parsed.bullets:
        parsed.preamble = _DEFAULT_PREAMBLE
    return parsed


def save_editorial_policy(policy: EditorialPolicy) -> Path:
    """校验条数后原子写入（tmp + replace）。超上限或重复 id 抛 ValueError。"""
    ids = [b.id for b in policy.bullets]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate policy bullet ids")
    if len(policy.bullets) > MAX_POLICY_BULLETS:
        raise ValueError(f"policy bullets exceed cap {MAX_POLICY_BULLETS}")
    for bullet in policy.bullets:
        if not bullet.id.strip():
            raise ValueError("policy bullet id must not be empty")
        if not bullet.text.strip():
            raise ValueError(f"policy bullet {bullet.id!r} text must not be empty")
    path = editorial_policy_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".md.tmp")
    tmp.write_text(policy.to_text(), encoding="utf-8")
    os.replace(tmp, path)
    return path
