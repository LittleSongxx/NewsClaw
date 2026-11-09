"""T6：``scripts/trace_report.py`` 的 Markdown 报告结构。"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from scripts.trace_report import build_report, default_date, main

DATE = "2026-11-09"


def _span(
    span_id: str,
    name: str,
    span_type: str,
    start: float,
    duration_ms: float,
    *,
    parent_id: str | None = None,
    status: str = "ok",
    error: str | None = None,
    **attributes: Any,
) -> dict[str, Any]:
    span: dict[str, Any] = {
        "span_id": span_id,
        "name": name,
        "type": span_type,
        "start_time": start,
        "end_time": start + duration_ms / 1000.0,
        "duration_ms": duration_ms,
        "status": status,
        "attributes": attributes,
    }
    if parent_id:
        span["parent_id"] = parent_id
    if error:
        span["error"] = error
    return span


def _trace(
    trace_id: str, session_id: str, start: float, spans: list[dict[str, Any]]
) -> dict[str, Any]:
    return {
        "trace_id": trace_id,
        "session_id": session_id,
        "start_time": start,
        "end_time": start + 2.0,
        "duration_ms": 2000.0,
        "metadata": {},
        "spans": spans,
        "summary": {
            "trace_id": trace_id,
            "session_id": session_id,
            "duration_ms": 2000.0,
            "total_spans": len(spans),
            "llm_calls": sum(1 for span in spans if span["type"] == "llm"),
            "tool_calls": sum(1 for span in spans if span["type"] == "tool"),
            "tool_errors": sum(
                1 for span in spans if span["type"] == "tool" and span["status"] == "error"
            ),
            "total_input_tokens": 120,
            "total_output_tokens": 40,
            "metadata": {},
        },
    }


def _fixture_traces() -> list[dict[str, Any]]:
    task = _span("s1", "task: 生成早报", "task", 1.0, 2000.0)
    llm = _span("s2", "llm: deepseek", "llm", 1.1, 900.0, parent_id="s1")
    tool_ok = _span("s3", "tool: read_file", "tool", 1.2, 150.0, parent_id="s2")
    tool_err = _span(
        "s4",
        "tool: feishu_doc",
        "tool",
        1.3,
        800.0,
        parent_id="s2",
        status="error",
        error="403 no wiki permission",
    )
    return [_trace("trace-aaaaaaaaaaaa", "sess-1", 1.0, [task, llm, tool_ok, tool_err])]


def _write_day(tmp_path: Path, traces: list[dict[str, Any]]) -> Path:
    day = tmp_path / DATE
    day.mkdir(parents=True, exist_ok=True)
    for index, trace in enumerate(traces):
        (day / f"trace-{index:03d}.json").write_text(
            json.dumps(trace, ensure_ascii=False), encoding="utf-8"
        )
    return day


def test_report_overview_token_totals_and_span_tree(tmp_path: Path) -> None:
    _write_day(tmp_path, _fixture_traces())
    report = build_report(DATE, tmp_path)

    assert "# Trace 报告 — 2026-11-09" in report
    assert "## 总览" in report
    assert "- Trace 数量：1" in report
    assert "- Span 总数：4" in report
    assert "- ERROR span：1" in report
    assert "- LLM 调用：1" in report
    assert "工具调用：2" in report
    assert "输入 120" in report
    assert "输出 40" in report

    # span 树按 parent_id 缩进：task 根 → llm 缩进 2 空格 → tool 缩进 4 空格。
    assert "[OK] task: 生成早报 (task) 2000.0ms" in report
    assert "  [OK] llm: deepseek (llm) 900.0ms" in report
    assert "    [ERROR] tool: feishu_doc (tool) 800.0ms" in report
    assert "    [OK] tool: read_file (tool) 150.0ms" in report


def test_report_error_section_and_marker(tmp_path: Path) -> None:
    _write_day(tmp_path, _fixture_traces())
    report = build_report(DATE, tmp_path)

    assert "## 错误列表" in report
    assert "[ERROR]" in report
    error_section = report.split("## 错误列表")[1]
    assert "tool: feishu_doc" in error_section
    assert "403 no wiki permission" in error_section
    assert "read_file" not in error_section


def test_report_top_slow_spans_are_ordered_by_duration(tmp_path: Path) -> None:
    _write_day(tmp_path, _fixture_traces())
    report = build_report(DATE, tmp_path)

    top_section = report.split("## Top 10 最慢 span")[1].split("## 错误列表")[0]
    slowest_index = top_section.index("task: 生成早报")
    llm_index = top_section.index("llm: deepseek")
    error_tool_index = top_section.index("tool: feishu_doc")
    fast_tool_index = top_section.index("tool: read_file")
    assert slowest_index < llm_index < error_tool_index < fast_tool_index


def test_report_missing_directory_is_graceful(tmp_path: Path) -> None:
    report = build_report(DATE, tmp_path / "does-not-exist")
    assert "未找到 Trace 目录" in report
    assert "Trace 数量" not in report


def test_report_empty_day_directory_is_graceful(tmp_path: Path) -> None:
    (tmp_path / DATE).mkdir()
    report = build_report(DATE, tmp_path)
    assert "没有可解析的" in report
    assert "Trace 数量" not in report


def test_report_skips_corrupt_json(tmp_path: Path) -> None:
    day = tmp_path / DATE
    day.mkdir()
    (day / "trace-bad.json").write_text("{not json", encoding="utf-8")
    report = build_report(DATE, tmp_path)
    assert "trace-bad.json" in report
    assert "解析失败的条目" in report


def test_report_tolerates_missing_summary(tmp_path: Path) -> None:
    trace = _fixture_traces()[0]
    trace.pop("summary")
    _write_day(tmp_path, [trace])
    report = build_report(DATE, tmp_path)
    assert "- Span 总数：4" in report
    assert "输入 0" in report


def test_cli_writes_markdown_file(tmp_path: Path) -> None:
    _write_day(tmp_path, _fixture_traces())
    target = tmp_path / "out" / "trace-report.md"
    assert main(["--date", DATE, "--dir", str(tmp_path), "--out", str(target)]) == 0
    content = target.read_text(encoding="utf-8")
    assert "# Trace 报告 — 2026-11-09" in content
    assert "## 总览" in content


def test_cli_prints_to_stdout(tmp_path: Path, capsys) -> None:
    _write_day(tmp_path, _fixture_traces())
    assert main(["--date", DATE, "--dir", str(tmp_path)]) == 0
    assert "## 总览" in capsys.readouterr().out


def test_default_date_shape() -> None:
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", default_date())
