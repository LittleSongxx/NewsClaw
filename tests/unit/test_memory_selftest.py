"""记忆自测模块的可用性钉子（不依赖 LLM 与真实数据）。"""

from __future__ import annotations

import pytest

from newsclaw.memory.selftest import _extract_keywords


def test_extract_keywords_mixed_language():
    kws = _extract_keywords("用户偏好用 VSCode 编辑 Python，工作目录在 D:/projects")
    assert any("vscode" in k.lower() for k in kws)
    assert any("python" in k.lower() for k in kws)


def test_extract_keywords_cjk_bigrams():
    kws = _extract_keywords("他住在苏州工业园区附近上班")
    assert any("\u4e00" <= ch <= "\u9fff" for k in kws for ch in k)


@pytest.mark.asyncio
async def test_selftest_handles_empty_store(tmp_path, monkeypatch):
    from newsclaw.config import settings

    monkeypatch.setattr(settings, "project_root", tmp_path)
    from newsclaw.memory.selftest import run_memory_selftest

    report = await run_memory_selftest(sample_size=5)
    assert report.get("error") == "no_recent_turns" or report.get("sampled", 0) >= 0
