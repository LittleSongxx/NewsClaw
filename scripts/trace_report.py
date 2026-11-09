"""Agent Trace → Markdown 报告生成器。

``newsclaw trace-report`` 的实现（``tests/unit/test_trace_report.py`` 直接测）。

输入是 ``tracing/exporter.py::FileExporter`` 落盘的目录结构::

    data/traces/YYYY-MM-DD/trace-<trace_id[:12]>.json

单个 JSON 的结构由 ``Trace.to_dict()`` 决定::

    {"trace_id", "session_id", "start_time", "end_time", "duration_ms",
     "metadata",
     "spans": [{"span_id", "name", "type", "start_time", "parent_id"?,
                "end_time"?, "duration_ms"?, "status", "attributes",
                "error"?}],
     "summary": {"llm_calls", "tool_calls", "tool_errors", "total_spans",
                 "total_input_tokens", "total_output_tokens", ...}}

报告只读 trace 目录，不写任何数据文件；``--out`` 才落盘。缺目录 / 空目录 /
坏 JSON 都返回可读的 Markdown 说明，而不是抛栈。
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date as _date
from pathlib import Path
from typing import Any

_TREE_INDENT = "  "
_TOP_SPANS = 10


def default_date() -> str:
    """返回本地时区的今天（``YYYY-MM-DD``）。"""
    return _date.today().isoformat()


def _fmt_ms(value: Any) -> str:
    if not isinstance(value, (int, float)):
        return "-"
    return f"{float(value):.1f}ms"


def _span_status(span: dict[str, Any]) -> str:
    return str(span.get("status") or "ok").upper()


def _is_error(span: dict[str, Any]) -> bool:
    return _span_status(span) == "ERROR"


def _duration(span: dict[str, Any]) -> float:
    """取耗时（ms）：优先 ``duration_ms``，缺失时用 start/end 现算。"""
    raw = span.get("duration_ms")
    if isinstance(raw, (int, float)):
        return float(raw)
    start, end = span.get("start_time"), span.get("end_time")
    if isinstance(start, (int, float)) and isinstance(end, (int, float)):
        return max(0.0, (float(end) - float(start)) * 1000.0)
    return 0.0


def _start_key(item: dict[str, Any]) -> float:
    value = item.get("start_time")
    return float(value) if isinstance(value, (int, float)) else 0.0


def _span_tree_lines(spans: list[dict[str, Any]]) -> list[str]:
    """把扁平 span 列表按 ``parent_id`` 还原成缩进树（深度优先）。"""
    by_id = {str(span.get("span_id", "")): span for span in spans}
    children: dict[str, list[dict[str, Any]]] = {}
    roots: list[dict[str, Any]] = []
    for span in spans:
        parent = span.get("parent_id")
        parent_key = str(parent) if parent else ""
        if parent_key and parent_key in by_id and parent_key != str(span.get("span_id")):
            children.setdefault(parent_key, []).append(span)
        else:
            roots.append(span)
    roots.sort(key=_start_key)
    for kids in children.values():
        kids.sort(key=_start_key)

    lines: list[str] = []
    visited: set[str] = set()

    def walk(span: dict[str, Any], depth: int) -> None:
        span_id = str(span.get("span_id", ""))
        if span_id in visited:
            return
        visited.add(span_id)
        marker = "[ERROR]" if _is_error(span) else "[OK]"
        line = (
            f"{_TREE_INDENT * depth}{marker} {span.get('name', '?')} "
            f"({span.get('type', '?')}) {_fmt_ms(_duration(span))}"
        )
        if span.get("error"):
            line += f" — {span['error']}"
        lines.append(line)
        for child in children.get(span_id, []):
            walk(child, depth + 1)

    for root in roots:
        walk(root, 0)
    # parent_id 指向不存在 span 的孤儿已在 roots 里；环里的节点补走一次防漏。
    for span in spans:
        walk(span, 0)
    return lines


def _summary_of(trace: dict[str, Any]) -> dict[str, Any]:
    """优先用文件里的 summary；缺失/残缺时按 spans 现算。"""
    raw = trace.get("summary")
    summary: dict[str, Any] = dict(raw) if isinstance(raw, dict) else {}
    spans = [s for s in (trace.get("spans") or []) if isinstance(s, dict)]

    summary.setdefault("total_spans", len(spans))
    if not isinstance(summary.get("llm_calls"), int):
        summary["llm_calls"] = sum(1 for s in spans if s.get("type") == "llm")
    if not isinstance(summary.get("tool_calls"), int):
        summary["tool_calls"] = sum(1 for s in spans if s.get("type") == "tool")
    if not isinstance(summary.get("tool_errors"), int):
        summary["tool_errors"] = sum(1 for s in spans if s.get("type") == "tool" and _is_error(s))
    for total_key, attr_key in (
        ("total_input_tokens", "input_tokens"),
        ("total_output_tokens", "output_tokens"),
    ):
        if isinstance(summary.get(total_key), int):
            continue
        tokens = 0
        for span in spans:
            if span.get("type") != "llm":
                continue
            attributes = span.get("attributes")
            if isinstance(attributes, dict):
                value = attributes.get(attr_key, 0)
                if isinstance(value, (int, float)):
                    tokens += int(value)
        summary[total_key] = tokens
    return summary


def load_traces(date: str, base_dir: Path | str) -> tuple[list[dict[str, Any]], list[str], Path]:
    """读取 ``<base_dir>/<date>`` 下全部 trace，返回 (traces, skipped, day_dir)。"""
    day_dir = Path(base_dir) / date
    skipped: list[str] = []
    if not day_dir.is_dir():
        return [], skipped, day_dir

    traces: list[dict[str, Any]] = []
    for path in sorted(day_dir.glob("trace-*.json")):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            skipped.append(f"{path.name}（{type(exc).__name__}: {exc}）")
            continue
        if not isinstance(payload, dict) or not isinstance(payload.get("spans"), list):
            skipped.append(f"{path.name}（缺少 spans 字段）")
            continue
        traces.append(payload)
    traces.sort(key=_start_key)
    return traces, skipped, day_dir


def _empty_report(date: str, base_dir: Path | str, reason: str) -> str:
    return (
        f"# Trace 报告 — {date}\n\n"
        f"{reason}\n\n"
        f"- 数据目录：`{Path(base_dir) / date}`\n"
        "- 提示：当天没有 trace 记录（服务未开 tracing，或还没有产生调用）。\n"
    )


def _overview_lines(traces: list[dict[str, Any]]) -> list[str]:
    summaries = [_summary_of(trace) for trace in traces]
    spans = [
        span for trace in traces for span in (trace.get("spans") or []) if isinstance(span, dict)
    ]
    total_ms = sum(_duration(trace) for trace in traces)
    error_spans = sum(1 for span in spans if _is_error(span))
    tool_errors = sum(int(summary.get("tool_errors") or 0) for summary in summaries)
    lines = [
        "## 总览",
        "",
        f"- Trace 数量：{len(traces)}",
        f"- Span 总数：{len(spans)}",
        f"- ERROR span：{error_spans}",
        f"- LLM 调用：{sum(int(s.get('llm_calls') or 0) for s in summaries)}",
        f"- 工具调用：{sum(int(s.get('tool_calls') or 0) for s in summaries)}"
        f"（错误 {tool_errors}）",
        f"- Token：输入 {sum(int(s.get('total_input_tokens') or 0) for s in summaries)}"
        f" / 输出 {sum(int(s.get('total_output_tokens') or 0) for s in summaries)}",
        f"- 总耗时：{_fmt_ms(total_ms)}",
    ]
    if traces:
        lines.append(f"- 平均耗时：{_fmt_ms(total_ms / len(traces))}")
    return lines


def _trace_lines(trace: dict[str, Any]) -> list[str]:
    trace_id = str(trace.get("trace_id") or "?")[:12]
    session = str(trace.get("session_id") or "-")
    summary = _summary_of(trace)
    spans = [s for s in (trace.get("spans") or []) if isinstance(s, dict)]
    lines = [
        f"### {trace_id} · session={session} · {_fmt_ms(_duration(trace))}"
        f" · spans={len(spans)} · LLM={int(summary.get('llm_calls') or 0)}"
        f" · tools={int(summary.get('tool_calls') or 0)}",
        "",
        "```text",
    ]
    tree = _span_tree_lines(spans)
    lines.extend(tree or ["(无 span)"])
    lines.append("```")
    return lines


def _slowest_lines(traces: list[dict[str, Any]]) -> list[str]:
    rows: list[tuple[float, str, dict[str, Any]]] = []
    for trace in traces:
        trace_id = str(trace.get("trace_id") or "?")[:12]
        for span in trace.get("spans") or []:
            if isinstance(span, dict):
                rows.append((_duration(span), trace_id, span))
    rows.sort(key=lambda row: row[0], reverse=True)

    lines = ["## Top 10 最慢 span", ""]
    if not rows:
        return [*lines, "（无 span）", ""]
    for rank, (duration, trace_id, span) in enumerate(rows[:_TOP_SPANS], start=1):
        lines.append(
            f"{rank}. `{_fmt_ms(duration)}` · {span.get('name', '?')}"
            f" ({span.get('type', '?')}) · {_span_status(span)} · trace={trace_id}"
        )
    lines.append("")
    return lines


def _error_lines(traces: list[dict[str, Any]]) -> list[str]:
    lines = ["## 错误列表", ""]
    errors: list[tuple[str, dict[str, Any]]] = []
    for trace in traces:
        trace_id = str(trace.get("trace_id") or "?")[:12]
        for span in trace.get("spans") or []:
            if isinstance(span, dict) and _is_error(span):
                errors.append((trace_id, span))
    if not errors:
        return [*lines, "无 ERROR span。", ""]
    for trace_id, span in errors:
        message = span.get("error") or "（无错误信息）"
        lines.append(
            f"- trace={trace_id} · {span.get('name', '?')} ({span.get('type', '?')})：{message}"
        )
    lines.append("")
    return lines


def build_report(date: str, base_dir: Path | str = Path("data/traces")) -> str:
    """生成 ``date`` 当天的 Trace Markdown 报告。"""
    base = Path(base_dir)
    traces, skipped, day_dir = load_traces(date, base)
    if not day_dir.is_dir():
        return _empty_report(date, base, f"未找到 Trace 目录：`{day_dir}`。")
    if not traces:
        reason = f"`{day_dir}` 下没有可解析的 `trace-*.json`。"
        if skipped:
            reason += "\n\n解析失败的条目：\n" + "\n".join(f"- {item}" for item in skipped)
        return _empty_report(date, base, reason)

    lines = [f"# Trace 报告 — {date}", ""]
    lines.extend(_overview_lines(traces))
    lines.extend(["", "## Trace 明细", ""])
    for trace in traces:
        lines.extend(_trace_lines(trace))
        lines.append("")
    lines.extend(_slowest_lines(traces))
    lines.extend(_error_lines(traces))
    if skipped:
        lines.extend(["## 解析失败的文件", ""])
        lines.extend(f"- {item}" for item in skipped)
        lines.append("")
    return "\n".join(lines)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="trace-report",
        description="把某天的 Agent Trace JSON 汇总成 Markdown 报告",
    )
    parser.add_argument("--date", default="", help="日期 YYYY-MM-DD（默认今天）")
    parser.add_argument("--dir", default="data/traces", help="trace 根目录（默认 data/traces）")
    parser.add_argument("--out", default="", help="输出 Markdown 文件（默认打印到 stdout）")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    report = build_report(args.date.strip() or default_date(), Path(args.dir))
    if args.out.strip():
        target = Path(args.out).expanduser()
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(report, encoding="utf-8", newline="\n")
        print(f"trace report written to {target}")
    else:
        print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
