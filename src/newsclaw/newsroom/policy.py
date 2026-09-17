"""早报主线的策略窄口：定时任务可写该写的文件，进化载体只能经 apply。

Policy V2 出厂是 protect + default：写文件 / 委派都是 CONFIRM，无人值守再
默认 deny。早报两条 cron 没有人点确认，必须在引擎里给**受控放行**，同时
把 sources.yaml / editorial-policy.md / config.yaml 从 Agent 写盘里拿掉。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from newsclaw.core.policy_v2.enums import DecisionAction

# 与 seed.py 同一对 ID；这里再写一份避免 policy → seed → scheduler 的重导入。
DAILY_TASK_ID = "newsroom_daily_pipeline"
REVIEW_TASK_ID = "newsroom_weekly_review"

_PROTECTED_NAMES = frozenset(
    {
        "sources.yaml",
        "editorial-policy.md",
        "config.yaml",
    }
)
_PATH_KEYS = (
    "path",
    "filepath",
    "file_path",
    "filename",
    "source",
    "destination",
    "target",
    "src",
    "dst",
)
_MANIFEST_RE = re.compile(r"issues[/\\](\d{4}-\d{2}-\d{2})[/\\]manifest\.json$", re.I)
_PROPOSAL_JSON_RE = re.compile(r"issues[/\\]review-proposal\.json$", re.I)

_DAILY_WRITE_TOOLS = frozenset({"write_file", "edit_file", "append_file"})
_DAILY_DELEGATE_TOOLS = frozenset({"delegate_parallel", "delegate_to_agent", "task_stop"})
_REVIEW_WRITE_TOOLS = frozenset({"write_file", "edit_file", "append_file"})


@dataclass(frozen=True)
class NewsroomPolicyDecision:
    action: DecisionAction
    step_name: str
    reason: str


def _newsroom_kind(ctx) -> str:
    meta = getattr(ctx, "metadata", None) or {}
    kind = str(meta.get("newsroom") or "").strip().lower()
    if kind in {"daily", "review"}:
        return kind
    task_id = str(meta.get("scheduled_task_id") or getattr(ctx, "session_id", "") or "")
    if task_id.endswith(DAILY_TASK_ID) or task_id == f"task:{DAILY_TASK_ID}":
        return "daily"
    if task_id.endswith(REVIEW_TASK_ID) or task_id == f"task:{REVIEW_TASK_ID}":
        return "review"
    return ""


def _extract_paths(params: dict[str, Any] | None) -> list[str]:
    raw = params or {}
    out: list[str] = []
    for key in _PATH_KEYS:
        value = raw.get(key)
        if isinstance(value, str) and value.strip():
            out.append(value.strip())
    return out


def newsroom_root_or_none() -> Path | None:
    try:
        from newsclaw.newsroom.contract import newsroom_root

        return newsroom_root()
    except Exception:
        return None


def resolve_candidate(raw: str) -> Path | None:
    text = (raw or "").strip()
    if not text:
        return None
    try:
        path = Path(text).expanduser()
        if not path.is_absolute():
            root = newsroom_root_or_none()
            if root is not None and (root / text).exists():
                path = root / text
        return path
    except (OSError, ValueError):
        return None


def is_protected_evolution_file(raw: str) -> bool:
    """信源 / 方针 / 运行配置只能由 apply 或 WebUI 写，Agent 工具一律拒绝。"""
    name = Path(raw).name.lower()
    if name not in _PROTECTED_NAMES:
        return False
    root = newsroom_root_or_none()
    path = resolve_candidate(raw)
    if root is None or path is None:
        return True
    try:
        resolved = path.expanduser()
        if not resolved.is_absolute():
            return True
        return _under(resolved, root)
    except (OSError, ValueError):
        return True


def _under(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except (ValueError, OSError):
        try:
            return root.resolve() in path.resolve().parents or path.resolve() == root.resolve()
        except (OSError, ValueError):
            return False


def guard_direct_evolution_write(raw: str) -> str | None:
    if not is_protected_evolution_file(raw):
        return None
    return (
        "❌ 早报进化载体不能由工具直接改写："
        f"{Path(raw).name} 只能经 WebUI 勾选提案 apply，或设置页保存。"
        "请把改动写进 issues/review-proposal.json。"
    )


def _is_newsroom_unattended(ctx) -> bool:
    return bool(getattr(ctx, "is_unattended", False)) and bool(_newsroom_kind(ctx))


def _path_allowed_for_kind(raw: str, kind: str) -> bool:
    if is_protected_evolution_file(raw):
        return False
    norm = raw.replace("\\", "/")
    if kind == "daily":
        shaped = ("/issues/" in f"/{norm}" or norm.startswith("issues/")) and (
            "review-proposal" not in Path(norm).name
        )
    else:
        shaped = (
            "/reviews/" in f"/{norm}"
            or norm.startswith("reviews/")
            or norm.endswith("review-proposal.json")
            or norm.endswith("review-proposal.md")
        )
    root = newsroom_root_or_none()
    path = resolve_candidate(raw)
    if path is None:
        return shaped
    try:
        resolved = path.expanduser()
        if not resolved.is_absolute():
            return shaped
        if root is None or not _under(resolved, root):
            return False
        rel = resolved.resolve().relative_to(root.resolve()).as_posix()
        if kind == "daily":
            return rel.startswith("issues/") and "review-proposal" not in rel
        return rel.startswith("reviews/") or rel in {
            "issues/review-proposal.json",
            "issues/review-proposal.md",
        }
    except (OSError, ValueError):
        return shaped


def deny_newsroom_side_effect(
    tool: str, params: dict[str, Any] | None, ctx
) -> NewsroomPolicyDecision | None:
    """任何入口都生效的拒绝：直接改进化载体；复盘任务禁止 add_memory。"""
    if tool in {"write_file", "edit_file", "append_file", "delete_file", "move_file"}:
        for raw in _extract_paths(params):
            if is_protected_evolution_file(raw):
                return NewsroomPolicyDecision(
                    action=DecisionAction.DENY,
                    step_name="newsroom_evolution_guard",
                    reason=f"refusing direct write to {Path(raw).name}; use apply_proposal",
                )
    if tool == "add_memory" and _newsroom_kind(ctx) == "review":
        return NewsroomPolicyDecision(
            action=DecisionAction.DENY,
            step_name="newsroom_review_memory_guard",
            reason="weekly review must put memory_rule in the proposal for human apply",
        )
    return None


def allow_newsroom_unattended(
    tool: str, params: dict[str, Any] | None, ctx
) -> NewsroomPolicyDecision | None:
    """无人值守早报任务：放行主线必需工具，且写盘必须落在允许的相对路径。"""
    if not _is_newsroom_unattended(ctx):
        return None
    kind = _newsroom_kind(ctx)
    if kind == "daily":
        if tool == "wiki_upsert" or tool in _DAILY_DELEGATE_TOOLS:
            return NewsroomPolicyDecision(
                action=DecisionAction.ALLOW,
                step_name="newsroom_scheduled_allow",
                reason=f"daily pipeline may use {tool}",
            )
        if tool in _DAILY_WRITE_TOOLS:
            paths = _extract_paths(params)
            if paths and all(_path_allowed_for_kind(p, "daily") for p in paths):
                return NewsroomPolicyDecision(
                    action=DecisionAction.ALLOW,
                    step_name="newsroom_scheduled_allow",
                    reason="daily pipeline write inside issues/",
                )
            return None
    if kind == "review":
        if tool in _REVIEW_WRITE_TOOLS:
            paths = _extract_paths(params)
            if paths and all(_path_allowed_for_kind(p, "review") for p in paths):
                return NewsroomPolicyDecision(
                    action=DecisionAction.ALLOW,
                    step_name="newsroom_scheduled_allow",
                    reason="weekly review may write proposal and reviews/",
                )
    return None


def is_issue_manifest_path(raw: str) -> str | None:
    """若路径是某期 manifest.json，返回 YYYY-MM-DD。"""
    norm = raw.replace("\\", "/")
    match = _MANIFEST_RE.search(norm)
    if match:
        return match.group(1)
    path = resolve_candidate(raw)
    if path is None:
        return None
    match = _MANIFEST_RE.search(path.as_posix())
    return match.group(1) if match else None


def is_review_proposal_json_path(raw: str) -> bool:
    return bool(_PROPOSAL_JSON_RE.search(raw.replace("\\", "/")))


def write_manifest_from_agent(issue_date: str, content: str) -> str:
    """Agent 写 manifest 必须走契约函数，不能直接落盘自封 ready。"""
    from newsclaw.newsroom.contract import IssueManifest, write_manifest

    try:
        data = json.loads(content)
    except json.JSONDecodeError as exc:
        raise ValueError(f"manifest 不是合法 JSON：{exc}") from exc
    if not isinstance(data, dict):
        raise ValueError("manifest 根节点必须是对象")
    data.setdefault("issue_date", issue_date)
    if str(data.get("issue_date") or "") != issue_date:
        raise ValueError(
            f"manifest.issue_date={data.get('issue_date')!r} 与目录 {issue_date} 不一致"
        )
    manifest = IssueManifest.from_dict(data)
    path = write_manifest(manifest)
    return f"文件已写入: {path}（已过契约机验，status={manifest.status}）"
