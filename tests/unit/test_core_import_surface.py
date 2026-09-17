"""Regression tests for the canonical agent import surface."""

from __future__ import annotations

import importlib.util


def test_prompt_compiler_import_surface() -> None:
    """``openakita.prompt.compiler.check_compiled_outdated`` must import.

    Pre-fix this raised ``ImportError`` because the ``prompt -> builder ->
    skills -> agent -> core._agent_runtime -> ..skills (re-entry)`` cycle
    aborted ``skills/__init__`` mid-load.
    """
    from newsclaw.prompt.compiler import check_compiled_outdated

    assert callable(check_compiled_outdated)


def test_agent_brain_export() -> None:
    """``from newsclaw.agent import Brain`` must succeed.

    The canonical package must load without re-entering the runtime module
    during package initialization.
    """
    from newsclaw.agent import Brain

    assert isinstance(Brain, type)
    assert Brain.__name__ == "Brain"


def test_agent_reasoning_engine_export() -> None:
    """``from newsclaw.agent import ReasoningEngine`` must succeed.

    The reasoning engine is exported only from the canonical agent package.
    """
    from newsclaw.agent import ReasoningEngine

    assert isinstance(ReasoningEngine, type)
    assert ReasoningEngine.__name__ == "ReasoningEngine"


def test_removed_core_agent_modules_do_not_resolve() -> None:
    removed = (
        "newsclaw.core.agent",
        "newsclaw.core.reasoning_engine",
        "newsclaw.core.tool_executor",
        "newsclaw.core.identity",
        "newsclaw.core.permission",
    )

    assert all(importlib.util.find_spec(module_name) is None for module_name in removed)


def test_removed_llm_adapter_does_not_resolve() -> None:
    assert importlib.util.find_spec("newsclaw.llm.adapter") is None


def test_core_package_does_not_export_agent_types() -> None:
    import newsclaw.core as core

    assert not hasattr(core, "Agent")
    assert not hasattr(core, "Brain")
    assert not hasattr(core, "ReasoningEngine")
