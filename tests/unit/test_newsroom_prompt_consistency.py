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

    def test_manifest_schema_matches_dataclass_fields(self):
        """提示词里的 manifest schema 与 IssueManifest 字段一一对应。

        Agent 按旧 schema 写、代码按新字段读的事故靠这个测试拦住：
        加字段必须同步 _MANIFEST_SCHEMA，反之亦然。
        """
        import json
        from dataclasses import fields

        from newsclaw.newsroom.contract import IssueManifest
        from newsclaw.newsroom.prompts import _MANIFEST_SCHEMA

        schema_keys = set(json.loads(_MANIFEST_SCHEMA))
        dataclass_fields = {f.name for f in fields(IssueManifest)}
        assert not schema_keys - dataclass_fields, (
            f"schema 提示词里有多余字段（数据类没有）: {schema_keys - dataclass_fields}"
        )
        assert not dataclass_fields - schema_keys, (
            f"IssueManifest 字段未写进 schema 提示词: {dataclass_fields - schema_keys}"
        )

    def test_platform_section_pins_match_skill_templates(self):
        """契约格式钉的小节名与两份平台技能的输出模板保持同名。"""
        from pathlib import Path

        skills_root = Path(__file__).resolve().parents[2] / "skills"
        xhs = (skills_root / "xiaohongshu-creator" / "SKILL.md").read_text(encoding="utf-8")
        wechat = (skills_root / "wechat-article" / "SKILL.md").read_text(encoding="utf-8")
        assert "标题方案" in xhs, "契约机验钉住的小节『标题方案』在技能模板里消失了"
        assert "基础信息" in wechat, "契约机验钉住的小节『基础信息』在技能模板里消失了"
