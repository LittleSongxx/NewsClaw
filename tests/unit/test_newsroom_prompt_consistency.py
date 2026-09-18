"""每日管线双流程真相的一致性测试。

管线的操作流程同时活在两处：``newsroom/prompts.py``（任务 prompt，随
PROMPT_VERSION 升级刷新）与 ``skills/newsroom-editor/SKILL.md``（技能手册，
实时加载、无版本管控）。没有任何机制强制两者同步——本测试把双方都必须
提及的关键契约词钉死，改一处忘另一处时直接红灯。
"""

from __future__ import annotations

from pathlib import Path

import pytest

from newsclaw.config import settings
from newsclaw.newsroom.prompts import build_daily_prompt

_SKILL_MD = (
    Path(__file__).resolve().parents[2] / "skills" / "newsroom-editor" / "SKILL.md"
)

#: 双方都必须出现的关键契约词：产物文件名、核心工具、子 Agent、账本。
_SHARED_TOKENS = (
    "daily-brief.md",
    "xiaohongshu.md",
    "wechat.md",
    "manifest.json",
    "wiki_upsert",
    "deliver_artifacts",
    "feishu_doc",
    "delegate_parallel",
    "news-collector",
)

#: 只要求 prompt 侧保证的机验语义（技能手册用自然语言转述，不逐字对应）。
_PROMPT_ONLY_TOKENS = (
    "status=ready",  # ready 由契约机验，不是自评说了算
    "items",  # 素材账本
)


@pytest.fixture(autouse=True)
def isolated_newsroom(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "project_root", tmp_path)
    return tmp_path / "data" / "newsroom"


class TestPromptSkillConsistency:
    def test_skill_manual_exists(self):
        assert _SKILL_MD.is_file(), f"missing newsroom-editor skill manual: {_SKILL_MD}"

    @pytest.mark.parametrize("token", _SHARED_TOKENS)
    def test_daily_prompt_mentions_token(self, token):
        assert token in build_daily_prompt(), (
            f"build_daily_prompt 不再提及 {token!r}——若流程变了，"
            "skills/newsroom-editor/SKILL.md 与 prompts.py 需要一起改"
        )

    @pytest.mark.parametrize("token", _SHARED_TOKENS)
    def test_skill_manual_mentions_token(self, token):
        text = _SKILL_MD.read_text(encoding="utf-8")
        assert token in text, (
            f"skills/newsroom-editor/SKILL.md 不再提及 {token!r}——"
            "技能手册与 newsroom/prompts.py 的流程真相漂移了"
        )

    @pytest.mark.parametrize("token", _PROMPT_ONLY_TOKENS)
    def test_prompt_keeps_machine_validated_semantics(self, token):
        assert token in build_daily_prompt()
