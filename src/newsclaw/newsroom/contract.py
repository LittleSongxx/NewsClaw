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

import enum
import json
import logging
import os
from dataclasses import asdict, dataclass, field
from datetime import date, datetime
from pathlib import Path
from typing import Any

from newsclaw.newsroom.items import (
    NewsItem,
    extract_urls_from_text,
    is_asset_url,
    normalize_url,
    parse_items,
)

logger = logging.getLogger(__name__)

MANIFEST_FILENAME = "manifest.json"
ARTIFACT_DAILY_BRIEF = "daily-brief.md"
ARTIFACT_XIAOHONGSHU = "xiaohongshu.md"
ARTIFACT_WECHAT = "wechat.md"

#: ready 产物去空白后的最低字符数，挡住「写了几个字就算完成」。
ARTIFACT_MIN_CHARS = 80
#: 允许代替外链的「无结果」整词标记（子串命中即可，须写进正文）。
NO_RESULT_MARKERS: tuple[str, ...] = ("无结果", "[NO_RESULT]", "NO_RESULT")
VALID_STATUSES: tuple[str, ...] = ("ready", "partial", "rejected")

#: 自评维度（顺序即 manifest 中的呈现顺序）。维度集合是契约的一部分，
#: 复盘任务按维度名聚合趋势——新增维度是兼容变更，改名是破坏性变更。
SCORE_DIMENSIONS: tuple[str, ...] = (
    "source_hit",  # 信源命中：素材是否来自清单且覆盖当日重要事件
    "dedup",  # 去重质量：与近几期及本期内部无明显重复
    "headline",  # 标题吸引力：两个平台稿的标题是否抓人且不标题党
    "structure",  # 结构可读性：分层、节奏、长度是否符合平台调性
)

_REQUIRED_ARTIFACTS = (ARTIFACT_DAILY_BRIEF, ARTIFACT_XIAOHONGSHU, ARTIFACT_WECHAT)


class IssueFailureReason(enum.StrEnum):
    """管线失败分类：不要把策略拒绝、预算耗尽、契约未过都叫「失败」。"""

    POLICY_DENIED = "policy_denied"
    BUDGET_EXCEEDED = "budget_exceeded"
    ARTIFACTS_INCOMPLETE = "artifacts_incomplete"
    VALIDATE_FAILED = "validate_failed"


def classify_issue_failure(
    *,
    exit_reason: str = "",
    validate_errors: list[str] | None = None,
) -> IssueFailureReason:
    """把调度/引擎退出原因与契约错误映射成稳定枚举。"""
    reason = (exit_reason or "").strip().lower()
    if reason in {"budget_exceeded", "budget_paused"}:
        return IssueFailureReason.BUDGET_EXCEEDED
    if reason in {"policy_denied", "denied", "security_denied"}:
        return IssueFailureReason.POLICY_DENIED
    errors = list(validate_errors or [])
    if any("missing artifact" in e or "too short" in e or "http" in e for e in errors):
        return IssueFailureReason.ARTIFACTS_INCOMPLETE
    if errors:
        return IssueFailureReason.VALIDATE_FAILED
    if reason in {"max_turns", "max_iterations", "error", "timeout"}:
        return IssueFailureReason.VALIDATE_FAILED
    return IssueFailureReason.VALIDATE_FAILED


def _allowed_source_names() -> set[str]:
    """当期 sources.yaml 的 name 集合；复用 sources.load_sources，不复制解析。"""
    from newsclaw.newsroom.sources import load_sources

    book = load_sources()
    return {source.name for source in book.sources if source.name.strip()}


def _feedback_blocks_ready(feedback: dict[str, Any]) -> bool:
    """人工点踩（rating < 0）存在时，禁止把期次标成 ready。

    反馈模型只有 -1/0/1 三档 rating（feedback.set_feedback 校验），没有
    分数/verdict 字段；旧代码里那两个分支从未有写入方，已删除。
    """
    if not feedback:
        return False
    rating = feedback.get("rating")
    try:
        return int(rating) < 0
    except (TypeError, ValueError):
        return False


def _artifact_structure_errors(name: str, path: Path) -> list[str]:
    """最低结构：够长，且有 http 链接或明确的无结果标记。"""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        return [f"status=ready cannot read artifact {name}: {exc}"]
    stripped = "".join(text.split())
    if len(stripped) < ARTIFACT_MIN_CHARS:
        return [
            f"status=ready artifact too short: {name} "
            f"(need {ARTIFACT_MIN_CHARS} non-whitespace chars)"
        ]
    has_http = "http://" in text or "https://" in text
    has_no_result = any(marker in text for marker in NO_RESULT_MARKERS)
    if not has_http and not has_no_result:
        return [
            f"status=ready artifact {name} needs an http link or a no-result marker "
            f"({', '.join(NO_RESULT_MARKERS)})"
        ]
    return []


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

    ``status`` 取值：``ready``（契约机验通过）/ ``partial``（部分产物）/
    ``rejected``（人工负反馈，禁止当 ready）。前端按 status 决定是否允许反馈。
    ``scores`` 可以写，但**不是** ready 条件。
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
    #: 入选素材账本。写 ready 且稿件含外链时必填；读旧期次可不强制。
    items: list[NewsItem] = field(default_factory=list)

    def validate(
        self,
        *,
        artifact_dir: Path | None = None,
        require_item_ledger: bool = True,
    ) -> list[str]:
        """校验契约，返回错误列表（空列表 = 合法）。

        ``status=ready`` 必须同时满足：信源集合合法、三份产物存在且过最低结构。
        写盘时（``require_item_ledger=True``）还要求：有外链的期次必须带
        ``items``，条目 URL 互不重复，且不与窗口内其它 ready 期次撞链。
        ``scores`` 只做字段合法性检查，不参与 ready 判定。
        """
        errors: list[str] = []
        try:
            date.fromisoformat(self.issue_date)
        except ValueError:
            errors.append(f"issue_date not ISO format YYYY-MM-DD: {self.issue_date!r}")
        if self.status not in VALID_STATUSES:
            errors.append(f"unknown status: {self.status!r}")
        if self.status == "ready":
            allowed: set[str] | None = None
            if not self.sources_used:
                errors.append("status=ready requires non-empty sources_used")
            else:
                try:
                    allowed = _allowed_source_names()
                except ValueError as exc:
                    # sources.yaml 损坏：fail-closed 成校验错误，而不是让
                    # load_manifest/list_issues 在读列表时直接崩掉。
                    errors.append(f"status=ready cannot verify sources_used: {exc}")
                if allowed is not None:
                    for name in self.sources_used:
                        if name not in allowed:
                            errors.append(f"status=ready sources_used unknown: {name!r}")
            if _feedback_blocks_ready(self.feedback):
                errors.append("status=ready forbidden: human feedback is reject/low")
            day_dir = artifact_dir if artifact_dir is not None else issue_dir(self.issue_date)
            artifact_texts: dict[str, str] = {}
            for name in _REQUIRED_ARTIFACTS:
                path = day_dir / name
                if not path.is_file():
                    errors.append(f"status=ready but missing artifact: {name}")
                    continue
                errors.extend(_artifact_structure_errors(name, path))
                try:
                    artifact_texts[name] = path.read_text(encoding="utf-8")
                except OSError:
                    artifact_texts[name] = ""
            if require_item_ledger:
                errors.extend(self._item_ledger_errors(artifact_texts, allowed))
        for index, item in enumerate(self.items):
            errors.extend(f"items[{index}]: {msg}" for msg in item.validate())
        for dim, entry in self.scores.items():
            errors.extend(f"scores[{dim}]: {msg}" for msg in entry.validate())
        return errors

    def _item_ledger_errors(
        self, artifact_texts: dict[str, str], allowed: set[str] | None = None
    ) -> list[str]:
        """写 ready 时的素材账本门。无结果日允许 items 为空。

        ``allowed`` 为 None 表示 sources.yaml 损坏（validate 已记一条错误），
        这里跳过 source_name 对账，其余检查照常。
        """
        errors: list[str] = []
        has_http = any(
            "http://" in text or "https://" in text for text in artifact_texts.values()
        )
        all_no_result = bool(artifact_texts) and all(
            any(marker in text for marker in NO_RESULT_MARKERS)
            for text in artifact_texts.values()
        )
        if has_http:
            if not self.items:
                errors.append("status=ready with outbound links requires non-empty items")
            keys: list[str] = []
            for index, item in enumerate(self.items):
                key = normalize_url(item.url)
                if key in keys:
                    errors.append(f"status=ready duplicate item url: {item.url}")
                elif key:
                    keys.append(key)
                if (
                    allowed is not None
                    and item.source_name
                    and item.source_name not in allowed
                ):
                    errors.append(
                        f"status=ready items[{index}] source_name unknown: "
                        f"{item.source_name!r}"
                    )
            for name, text in artifact_texts.items():
                if any(marker in text for marker in NO_RESULT_MARKERS):
                    continue
                if not any(item.url and item.url in text for item in self.items):
                    errors.append(
                        f"status=ready artifact {name} must cite at least one items[].url"
                    )
            # 反向：稿中出现的素材链接必须都已入账，否则它们永久游离在去重池外
            #（prompt 承诺的 ⊆ 这里机验）。平台稿的配图建议资源外链豁免。
            item_keys = {normalize_url(item.url) for item in self.items if item.url}
            item_keys.discard("")
            for name, text in artifact_texts.items():
                for url in extract_urls_from_text(text):
                    key = normalize_url(url)
                    if key in item_keys:
                        continue
                    if name != ARTIFACT_DAILY_BRIEF and is_asset_url(url):
                        continue
                    errors.append(
                        f"status=ready artifact {name} cites a URL outside items[]: {url} "
                        "(add it to items or remove it from the artifact)"
                    )
            if keys:
                from newsclaw.newsroom.config import load_config
                from newsclaw.newsroom.items import collect_seen_urls

                window = load_config().issue_history_days
                seen = collect_seen_urls(before_date=self.issue_date, days=window)
                for item in self.items:
                    prev = seen.get(normalize_url(item.url))
                    if prev:
                        errors.append(
                            f"status=ready item url already used on {prev}: {item.url}"
                        )
        elif all_no_result and self.items:
            errors.append("status=ready no-result day should not list leftover items")
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
            items=parse_items(data.get("items")),
        )

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["items"] = [item.to_dict() if hasattr(item, "to_dict") else item for item in self.items]
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


def describe_issue_artifacts(issue_date: str | None = None) -> dict[str, Any]:
    """预算耗尽时的部分结果：已落盘文件、缺什么、当前 status。"""
    day = issue_date or date.today().isoformat()
    day_dir = issue_dir(day)
    present: list[str] = []
    missing: list[str] = []
    for name in _REQUIRED_ARTIFACTS:
        if _artifact_present(day_dir, name):
            present.append(name)
        else:
            missing.append(name)
    manifest_path = day_dir / MANIFEST_FILENAME
    status = ""
    if manifest_path.is_file():
        manifest, _error = load_manifest(day)
        if manifest is not None:
            status = manifest.status
    return {
        "issue_date": day,
        "present": present,
        "missing": missing,
        "manifest_status": status,
        "has_manifest": manifest_path.is_file(),
    }


def demote_ready_on_budget_exceeded(issue_date: str | None = None) -> bool:
    """预算耗尽后禁止期次保持 ready；已是 ready 则降为 partial。"""
    day = issue_date or date.today().isoformat()
    manifest, _error = load_manifest(day)
    if manifest is None or manifest.status != "ready":
        return False
    manifest.status = "partial"
    write_manifest(manifest)
    return True


def write_manifest(manifest: IssueManifest) -> Path:
    """原子写入 manifest（tmp + rename），并保证目录存在。"""
    errors = manifest.validate(artifact_dir=issue_dir(manifest.issue_date))
    if errors:
        reason = classify_issue_failure(validate_errors=errors)
        raise ValueError(
            f"invalid manifest for {manifest.issue_date} [{reason}]: {'; '.join(errors)}"
        )
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
    """读取某期 manifest；目录或文件不存在 / 损坏时返回 None。"""
    manifest, _error = load_manifest(issue_date)
    return manifest


def load_manifest(
    issue_date: str,
    *,
    require_item_ledger: bool = False,
) -> tuple[IssueManifest | None, str | None]:
    """读取并区分「没有文件」与「坏 manifest」。

    默认不强制素材账本，以免旧期次（没有 items）在列表里变成 invalid。
    写盘仍走 ``write_manifest`` → ``require_item_ledger=True``。

    Returns:
        ``(manifest, error)``：文件不存在 → ``(None, None)``；
        损坏或校验失败 → ``(None 或半成品, 错误说明)``，调用方必须把错误
        暴露给列表/API，不能假装这一期不存在。
    """
    path = issue_dir(issue_date) / MANIFEST_FILENAME
    if not path.is_file():
        return None, None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as e:
        logger.warning("[Newsroom] corrupt manifest %s: %s", path, e)
        return None, f"corrupt manifest: {e}"
    if not isinstance(data, dict):
        return None, "corrupt manifest: root is not an object"
    try:
        manifest = IssueManifest.from_dict(data)
    except (TypeError, ValueError) as e:
        logger.warning("[Newsroom] unreadable manifest %s: %s", path, e)
        return None, f"unreadable manifest: {e}"
    errors = manifest.validate(
        artifact_dir=issue_dir(issue_date),
        require_item_ledger=require_item_ledger,
    )
    if errors:
        return manifest, "; ".join(errors)
    return manifest, None


def _artifact_present(day_dir: Path, filename: str) -> bool:
    """只检查产物是否存在，不把整份 Markdown 读进内存。"""
    try:
        return (day_dir / filename).is_file()
    except OSError:
        return False


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

    跳过没有 manifest 的目录（如管线写到一半的现场），但把它们记入调试日志。
    坏 manifest / ``issue_date`` 与目录名不一致会以 ``status=invalid`` 出现在
    列表里（带 ``manifest_error``），不再静默当没有。
    """
    root = issues_root()
    if not root.is_dir():
        return []
    results: list[dict[str, Any]] = []
    for day_dir in sorted(root.iterdir(), reverse=True):
        if not day_dir.is_dir():
            continue
        artifacts = {
            name.removesuffix(".md"): _artifact_present(day_dir, name)
            for name in _REQUIRED_ARTIFACTS
        }
        manifest, error = load_manifest(day_dir.name)
        if manifest is None and error is None:
            logger.debug("[Newsroom] skipping issue dir without manifest: %s", day_dir.name)
            continue
        if manifest is None:
            results.append(
                {
                    "issue_date": day_dir.name,
                    "title": "",
                    "status": "invalid",
                    "manifest_error": error,
                    "failure_reason": classify_issue_failure(
                        validate_errors=[error or "corrupt manifest"]
                    ).value,
                    "sources_used": [],
                    "wiki_entries": [],
                    "feishu_doc_url": "",
                    "scores": {},
                    "feedback": {},
                    "artifacts": artifacts,
                    "created_at": "",
                }
            )
        else:
            row_error = error
            if manifest.issue_date != day_dir.name:
                mismatch = (
                    f"issue_date {manifest.issue_date!r} != directory {day_dir.name!r}"
                )
                row_error = f"{row_error}; {mismatch}" if row_error else mismatch
            results.append(
                {
                    "issue_date": day_dir.name,
                    "title": manifest.title,
                    "status": "invalid" if row_error else manifest.status,
                    "manifest_error": row_error,
                    "failure_reason": (
                        classify_issue_failure(validate_errors=[row_error]).value
                        if row_error
                        else None
                    ),
                    "sources_used": manifest.sources_used,
                    "items": [item.to_dict() for item in manifest.items],
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
