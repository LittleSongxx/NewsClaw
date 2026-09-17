"""期次目录契约：newsroom 唯一的数据交换格式。

每期产物固定落在一个日期目录里，``manifest.json`` 是 WebUI、复盘任务与
管线 Agent 三方共同读写的接口。契约集中在本模块，其他代码（含 prompt 里的
说明）一律引用这里的常量与函数，不自行拼路径。

目录结构::

    data/newsroom/issues/YYYY-MM-DD/
        manifest.json       期次元数据（本模块读写校验）
        daily-brief.md      日报总览（编辑内部视角，选题逻辑与信源链接）
        xiaohongshu.md      小红书笔记稿（标题/正文/话题标签/配图建议）
        wechat.md           公众号排版稿（Markdown，发布时按技能规范转富文本）
        assets/             可选：配图等二进制产物
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import asdict, dataclass, field
from datetime import date, datetime
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

MANIFEST_FILENAME = "manifest.json"
ARTIFACT_DAILY_BRIEF = "daily-brief.md"
ARTIFACT_XIAOHONGSHU = "xiaohongshu.md"
ARTIFACT_WECHAT = "wechat.md"

#: 自评维度（顺序即 manifest 中的呈现顺序）。维度集合是契约的一部分，
#: 复盘任务按维度名聚合趋势——新增维度是兼容变更，改名是破坏性变更。
SCORE_DIMENSIONS: tuple[str, ...] = (
    "source_hit",  # 信源命中：素材是否来自清单且覆盖当日重要事件
    "dedup",  # 去重质量：与近几期及本期内部无明显重复
    "headline",  # 标题吸引力：两个平台稿的标题是否抓人且不标题党
    "structure",  # 结构可读性：分层、节奏、长度是否符合平台调性
)

_REQUIRED_ARTIFACTS = (ARTIFACT_DAILY_BRIEF, ARTIFACT_XIAOHONGSHU, ARTIFACT_WECHAT)


@dataclass
class ScoreEntry:
    """单个自评维度的打分（1–5 分 + 一句话理由）。"""

    score: int
    rationale: str = ""

    def validate(self) -> list[str]:
        errors: list[str] = []
        if not 1 <= self.score <= 5:
            errors.append(f"score out of range 1-5: {self.score}")
        return errors


@dataclass
class IssueManifest:
    """一期早报的元数据。

    ``status`` 取值：``ready``（产物齐备，可展示）/ ``partial``（部分产物，
    管线中断后的兜底状态）。前端按 status 决定是否允许反馈。
    """

    issue_date: str  # YYYY-MM-DD，同时是目录名
    title: str = ""
    status: str = "partial"
    #: 实际用到的信源名列表（应来自 sources.yaml，复盘时对账）
    sources_used: list[str] = field(default_factory=list)
    #: 本期沉淀的本地 Wiki 页面（相对 wiki 根目录的路径，供 WebUI 直接跳转）
    wiki_entries: list[str] = field(default_factory=list)
    #: 飞书云文档归档链接（对外分享出口；与本地 Wiki 相互独立）
    feishu_doc_url: str = ""
    #: 人工反馈摘要（由 feedback 模块在写入时回填，管线不直接写）
    feedback: dict[str, Any] = field(default_factory=dict)
    #: 自评打分，键取 SCORE_DIMENSIONS
    scores: dict[str, ScoreEntry] = field(default_factory=dict)
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    generator: str = "ai-news-editor"

    def validate(self) -> list[str]:
        """校验契约，返回错误列表（空列表 = 合法）。"""
        errors: list[str] = []
        try:
            date.fromisoformat(self.issue_date)
        except ValueError:
            errors.append(f"issue_date not ISO format YYYY-MM-DD: {self.issue_date!r}")
        if self.status not in ("ready", "partial"):
            errors.append(f"unknown status: {self.status!r}")
        for dim, entry in self.scores.items():
            errors.extend(f"scores[{dim}]: {msg}" for msg in entry.validate())
        return errors

    # ── 序列化 ────────────────────────────────────────────────────

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> IssueManifest:
        scores_raw = data.get("scores") or {}
        scores = {
            str(dim): ScoreEntry(
                score=int(entry.get("score", 0)),
                rationale=str(entry.get("rationale", "")),
            )
            for dim, entry in scores_raw.items()
            if isinstance(entry, dict)
        }
        return cls(
            issue_date=str(data.get("issue_date", "")),
            title=str(data.get("title", "")),
            status=str(data.get("status", "partial")),
            sources_used=[str(s) for s in data.get("sources_used") or []],
            wiki_entries=[str(w) for w in data.get("wiki_entries") or []],
            feishu_doc_url=str(data.get("feishu_doc_url", "")),
            feedback=dict(data.get("feedback") or {}),
            scores=scores,
            created_at=str(data.get("created_at", "")),
            generator=str(data.get("generator", "ai-news-editor")),
        )

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        return data


# ── 目录解析 ────────────────────────────────────────────────────────


def newsroom_root() -> Path:
    """newsroom 根目录 ``<data_dir>/newsroom``（data_dir 跟随当前工作区）。"""
    try:
        from newsclaw.config import settings

        root = Path(settings.data_dir) / "newsroom"
    except Exception:  # pragma: no cover - settings 不可用时退回 CWD 布局
        root = Path.cwd() / "data" / "newsroom"
    return root


def issues_root() -> Path:
    return newsroom_root() / "issues"


def issue_dir(issue_date: str | date) -> Path:
    day = issue_date.isoformat() if isinstance(issue_date, date) else str(issue_date)
    return issues_root() / day


# ── manifest 读写 ───────────────────────────────────────────────────


def write_manifest(manifest: IssueManifest) -> Path:
    """原子写入 manifest（tmp + rename），并保证目录存在。"""
    errors = manifest.validate()
    if errors:
        raise ValueError(f"invalid manifest for {manifest.issue_date}: {'; '.join(errors)}")
    target = issue_dir(manifest.issue_date) / MANIFEST_FILENAME
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".json.tmp")
    tmp.write_text(
        json.dumps(manifest.to_dict(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(tmp, target)
    return target


def read_manifest(issue_date: str) -> IssueManifest | None:
    """读取某期 manifest；目录或文件不存在返回 None，损坏时记录日志并返回 None。"""
    path = issue_dir(issue_date) / MANIFEST_FILENAME
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return IssueManifest.from_dict(data)
    except (json.JSONDecodeError, ValueError, OSError) as e:
        logger.warning("[Newsroom] corrupt manifest %s: %s", path, e)
        return None


def _read_artifact(day_dir: Path, filename: str) -> str | None:
    path = day_dir / filename
    if not path.is_file():
        return None
    try:
        return path.read_text(encoding="utf-8")
    except OSError as e:  # pragma: no cover - 读失败按缺失处理
        logger.warning("[Newsroom] artifact read failed %s: %s", path, e)
        return None


def list_issues(limit: int = 60) -> list[dict[str, Any]]:
    """按日期倒序列出期次摘要（manifest + 产物就位情况），供 API 直接返回。

    跳过没有 manifest 的目录（如管线写到一半的现场），但把它们记入调试日志，
    避免半成品污染列表视图。
    """
    root = issues_root()
    if not root.is_dir():
        return []
    results: list[dict[str, Any]] = []
    for day_dir in sorted(root.iterdir(), reverse=True):
        if not day_dir.is_dir():
            continue
        manifest = read_manifest(day_dir.name)
        if manifest is None:
            logger.debug("[Newsroom] skipping issue dir without manifest: %s", day_dir.name)
            continue
        artifacts = {
            name.removesuffix(".md"): _read_artifact(day_dir, name) is not None
            for name in _REQUIRED_ARTIFACTS
        }
        results.append(
            {
                "issue_date": manifest.issue_date,
                "title": manifest.title,
                "status": manifest.status,
                "sources_used": manifest.sources_used,
                "wiki_entries": manifest.wiki_entries,
                "feishu_doc_url": manifest.feishu_doc_url,
                "scores": {dim: asdict(entry) for dim, entry in manifest.scores.items()},
                "feedback": manifest.feedback,
                "artifacts": artifacts,
                "created_at": manifest.created_at,
            }
        )
        if len(results) >= limit:
            break
    return results


def read_issue_content(issue_date: str) -> dict[str, str | None] | None:
    """读取某期三个产物的正文；目录不存在返回 None。"""
    day_dir = issue_dir(issue_date)
    if not day_dir.is_dir():
        return None
    return {name.removesuffix(".md"): _read_artifact(day_dir, name) for name in _REQUIRED_ARTIFACTS}
