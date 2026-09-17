"""Tests for delegation preamble injection and preset skill fixes.

Validates:
1. Delegation preamble injected for main agent (is_sub_agent=False)
2. Delegation preamble NOT injected for sub-agents
3. Preset skills: code-reviewer fixed, brand-guidelines removed
"""

from __future__ import annotations

DELEGATION_TOOLS = ("delegate_to_agent", "delegate_parallel")
NON_DELEGATION_TOOLS = ("read_file", "write_file", "web_search")


class _FakeToolCatalog:
    """ToolCatalog 替身：只有 ``_tools`` 参与委派门控。"""

    def __init__(self, names) -> None:
        self._tools = {name: {"name": name} for name in names}

    def get_progressive_catalog(self, expand_categories: bool = False) -> str:  # noqa: ARG002
        return ""


class TestDelegationPreambleInjection:
    """Test that the delegation preamble is correctly injected/omitted."""

    def _build_prompt(self, is_sub_agent: bool, tools=DELEGATION_TOOLS) -> str:
        """Build a system prompt with given sub_agent settings."""
        from newsclaw.config import settings
        from newsclaw.prompt.builder import build_system_prompt

        identity_dir = settings.identity_path

        return build_system_prompt(
            identity_dir=identity_dir,
            tools_enabled=True,
            is_sub_agent=is_sub_agent,
            tool_catalog=_FakeToolCatalog(tools),
        )

    def test_preamble_present_for_main_agent(self):
        """Main agent should get delegation preamble (multi-agent always on)."""
        prompt = self._build_prompt(is_sub_agent=False)
        assert "协作优先原则" in prompt
        assert "delegate_to_agent" in prompt

    def test_preamble_absent_for_sub_agent(self):
        """Sub-agents should NOT get delegation preamble."""
        prompt = self._build_prompt(is_sub_agent=True)
        assert "协作优先原则" not in prompt

    def test_preamble_absent_without_delegation_tools(self):
        """收敛后的门控：没有 delegate_* 工具时不得注入委派规则（白烧 token）。"""
        prompt = self._build_prompt(is_sub_agent=False, tools=NON_DELEGATION_TOOLS)
        assert "协作优先原则" not in prompt

    def test_preamble_before_identity(self):
        """Delegation preamble should appear BEFORE identity content."""
        prompt = self._build_prompt(is_sub_agent=False)
        preamble_pos = prompt.find("协作优先原则")
        identity_markers = ["ReAct", "核心执行原则", "三条铁律"]
        for marker in identity_markers:
            marker_pos = prompt.find(marker)
            if marker_pos >= 0:
                assert preamble_pos < marker_pos, f"Preamble should come before '{marker}'"

    def test_preamble_contains_priority_override(self):
        """Preamble must explicitly override solo-agent philosophy."""
        prompt = self._build_prompt(is_sub_agent=False)
        assert "立即委派" in prompt
        assert "才自己处理" in prompt

    def test_identity_still_present(self):
        """Identity layer should still be present even with preamble."""
        prompt = self._build_prompt(is_sub_agent=False)
        assert "协作优先原则" in prompt
        assert len(prompt) > 500


class TestPresetSkillFixes:
    """Test that preset agent skills are correctly named."""

    def test_no_code_reviewer_in_presets(self):
        from newsclaw.agents.presets import SYSTEM_PRESETS

        for p in SYSTEM_PRESETS:
            for skill in p.skills:
                assert "code-reviewer" not in skill, (
                    f"Preset {p.id} still has 'code-reviewer' (should be 'code-review')"
                )

    def test_no_brand_guidelines_in_presets(self):
        from newsclaw.agents.presets import SYSTEM_PRESETS

        for p in SYSTEM_PRESETS:
            for skill in p.skills:
                assert "brand-guidelines" not in skill, (
                    f"Preset {p.id} still has 'brand-guidelines' (doesn't exist)"
                )

    def test_preset_skills_all_resolve_to_shipped_skills(self):
        """预设引用的技能必须真的在 skills/ 里，否则 agent 会拿着空技能列表跑。"""
        from pathlib import Path

        from newsclaw.agents.presets import SYSTEM_PRESETS

        skills_root = Path(__file__).resolve().parents[2] / "skills"
        shipped = {path.name for path in skills_root.iterdir() if path.is_dir()}
        missing: list[str] = []
        for preset in SYSTEM_PRESETS:
            for reference in preset.skills:
                if "@" not in reference:
                    missing.append(f"{preset.id}: {reference} (缺少命名空间前缀)")
                    continue
                if reference.split("@", 1)[1] not in shipped:
                    missing.append(f"{preset.id}: {reference}")
        assert not missing, f"预设引用了不存在的技能: {missing}"

    def test_default_agent_has_all_skills_mode(self):
        from newsclaw.agents.presets import SYSTEM_PRESETS
        from newsclaw.agents.profile import SkillsMode

        default = next(p for p in SYSTEM_PRESETS if p.id == "default")
        assert default.skills == []
        assert default.skills_mode == SkillsMode.ALL


class TestPromptAssemblerParamPassing:
    """Test that is_sub_agent param is correctly passed through the chain."""

    def test_build_system_prompt_accepts_is_sub_agent(self):
        """build_system_prompt should accept is_sub_agent parameter."""
        import inspect

        from newsclaw.prompt.builder import build_system_prompt

        sig = inspect.signature(build_system_prompt)
        assert "is_sub_agent" in sig.parameters

    def test_assembler_compiled_accepts_is_sub_agent(self):
        """PromptAssembler methods should accept is_sub_agent."""
        import inspect

        from newsclaw.core.prompt_assembler import PromptAssembler

        for method_name in ("build_system_prompt_compiled", "_build_compiled_sync"):
            method = getattr(PromptAssembler, method_name)
            sig = inspect.signature(method)
            assert "is_sub_agent" in sig.parameters, f"{method_name} missing is_sub_agent param"
