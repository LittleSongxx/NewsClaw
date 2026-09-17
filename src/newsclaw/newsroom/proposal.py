"""周复盘结构化提案：解析、校验、人审 apply、审计。

复盘 Agent 只写 ``issues/review-proposal.md`` 与 ``issues/review-proposal.json``。
真正改 ``sources.yaml`` / ``editorial-policy.md`` 必须走 :func:`apply_proposal`
（HTTP 勾选或等价确定性函数）。本模块不是第三条写 yaml 旁路。

fail-closed：坏 JSON、校验失败、apply 中途异常 → 不改磁盘。
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from datetime import date, datetime
from pathlib import Path
from typing import Any

from .contract import newsroom_root
from .editorial import (
    MAX_POLICY_BULLETS,
    EditorialPolicy,
    PolicyBullet,
    editorial_policy_path,
    load_editorial_policy,
    save_editorial_policy,
)
from .sources import NewsSource, SourceBook, load_sources, save_sources, sources_path

logger = logging.getLogger(__name__)

PROPOSAL_JSON_NAME = "review-proposal.json"
PROPOSAL_MD_NAME = "review-proposal.md"
PROPOSAL_STATE_NAME = "review-proposal.state.json"
AUDIT_DIRNAME = "audit"
AUDIT_FILENAME = "apply.jsonl"

MEMORY_OP_ID = "memory_rule"
MEMORY_SOURCE_TAG = "newsroom_review"

SOURCE_ACTIONS = frozenset({"add", "update", "remove", "set_weight", "set_query", "add_excluded"})
POLICY_ACTIONS = frozenset({"add", "replace", "remove"})
REWRITE_ACTIONS = frozenset({"rewrite", "replace_all", "set_content", "overwrite", "replace_file"})
REWRITE_ROOT_KEYS = frozenset(
    {"policy_text", "editorial_policy", "rewrite_policy", "full_policy", "policy_markdown"}
)

STATUS_NONE = "none"
STATUS_INVALID = "invalid"
STATUS_PENDING = "pending"
STATUS_APPLIED = "applied"
STATUS_REJECTED = "rejected"

MemoryWriter = Callable[[str, dict[str, Any]], str | None]


class ProposalError(ValueError):
    """提案解析或 apply 失败（调用方映射 422 / 409）。"""


@dataclass(frozen=True)
class Evidence:
    """每条 op 的证据：期次日期 + 分数维度或人工反馈说明。"""

    issue_date: str
    dimension: str = ""
    note: str = ""

    def to_dict(self) -> dict[str, str]:
        return {
            "issue_date": self.issue_date,
            "dimension": self.dimension,
            "note": self.note,
        }


@dataclass
class SourceOp:
    """对齐 NewsSource / SourceBook 的信源变更，不另造方言。"""

    id: str
    action: str
    name: str = ""
    kind: str = ""
    query: str = ""
    url: str = ""
    weight: int | None = None
    topics: list[str] = field(default_factory=list)
    note: str = ""
    keyword: str = ""
    evidence: Evidence | None = None

    def to_payload(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "kind": self.kind,
            "query": self.query,
            "url": self.url,
            "weight": self.weight,
            "topics": list(self.topics),
            "note": self.note,
            "keyword": self.keyword,
        }


@dataclass
class PolicyBulletOp:
    """追加型方针变更：只允许 add / replace / remove 指定 id。"""

    id: str
    action: str
    text: str = ""
    evidence: Evidence | None = None


@dataclass
class MemoryRule:
    """可选一条记忆规则；未勾选则不写入。"""

    text: str
    evidence: Evidence | None = None


@dataclass
class ReviewProposal:
    """结构化提案本体（与磁盘 JSON 对应）。"""

    version: int = 1
    created_at: str = ""
    sources: list[SourceOp] = field(default_factory=list)
    policy_bullets: list[PolicyBulletOp] = field(default_factory=list)
    memory_rule: MemoryRule | None = None

    def all_op_ids(self) -> list[str]:
        ids = [op.id for op in self.sources]
        ids.extend(op.id for op in self.policy_bullets)
        if self.memory_rule:
            ids.append(MEMORY_OP_ID)
        return ids


@dataclass
class ProposalSnapshot:
    """最新提案 + 状态机。坏 JSON 时 proposal 为空、status=invalid。"""

    status: str
    parse_error: str | None = None
    fingerprint: str = ""
    path: str = ""
    markdown_path: str = ""
    proposal: ReviewProposal | None = None
    updated_at: str = ""
    actor: str = ""
    applied_op_ids: list[str] = field(default_factory=list)
    last_memory_error: str = ""

    def remaining_op_ids(self) -> list[str]:
        if self.proposal is None:
            return []
        applied = set(self.applied_op_ids)
        return [op_id for op_id in self.proposal.all_op_ids() if op_id not in applied]

    def to_api_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "parse_error": self.parse_error,
            "fingerprint": self.fingerprint,
            "path": self.path,
            "markdown_path": self.markdown_path,
            "updated_at": self.updated_at,
            "actor": self.actor,
            "applied_op_ids": list(self.applied_op_ids),
            "remaining_op_ids": self.remaining_op_ids(),
            "last_memory_error": self.last_memory_error or None,
            "ops": ops_for_api(self.proposal) if self.proposal else [],
        }


@dataclass
class ApplyResult:
    """一次 apply 的摘要。yaml/方针已落盘；记忆失败不回滚，但该 op 保持可重试。"""

    applied_op_ids: list[str]
    remaining_op_ids: list[str] = field(default_factory=list)
    memory_written: bool = False
    memory_id: str | None = None
    memory_error: str | None = None


@dataclass
class _FileSnapshot:
    path: Path
    existed: bool
    data: bytes | None


def proposal_json_path() -> Path:
    return newsroom_root() / "issues" / PROPOSAL_JSON_NAME


def proposal_md_path() -> Path:
    return newsroom_root() / "issues" / PROPOSAL_MD_NAME


def proposal_state_path() -> Path:
    return newsroom_root() / "issues" / PROPOSAL_STATE_NAME


def audit_path() -> Path:
    return newsroom_root() / AUDIT_DIRNAME / AUDIT_FILENAME


def fingerprint_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _now_iso() -> str:
    return datetime.now().isoformat(timespec="seconds")


def _parse_evidence(raw: Any) -> Evidence | None:
    if not isinstance(raw, dict):
        return None
    issue_date = str(raw.get("issue_date") or "").strip()
    dimension = str(raw.get("dimension") or "").strip()
    note = str(raw.get("note") or raw.get("feedback") or "").strip()
    if not issue_date and not dimension and not note:
        return None
    return Evidence(issue_date=issue_date, dimension=dimension, note=note)


def _as_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    return int(value)


def _as_str_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value]


def parse_proposal_text(text: str) -> ReviewProposal:
    """解析提案 JSON。结构不合法或含整文件重写类字段时抛 ProposalError。"""
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ProposalError(f"invalid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ProposalError("proposal root must be an object")
    rewrite_keys = sorted(REWRITE_ROOT_KEYS.intersection(data))
    if rewrite_keys:
        raise ProposalError(f"whole-file policy rewrite is not allowed: {', '.join(rewrite_keys)}")

    sources: list[SourceOp] = []
    for index, item in enumerate(data.get("sources") or []):
        if not isinstance(item, dict):
            raise ProposalError(f"sources[{index}] must be an object")
        action = str(item.get("action") or "").strip()
        if action in REWRITE_ACTIONS:
            raise ProposalError(f"unsupported source action: {action}")
        if action not in SOURCE_ACTIONS:
            raise ProposalError(f"unsupported source action: {action or '(empty)'}")
        op_id = str(item.get("id") or "").strip() or f"src-{action}-{index + 1}"
        try:
            weight = _as_int(item.get("weight"))
        except (TypeError, ValueError):
            weight = 0
        sources.append(
            SourceOp(
                id=op_id,
                action=action,
                name=str(item.get("name") or "").strip(),
                kind=str(item.get("kind") or "").strip(),
                query=str(item.get("query") or ""),
                url=str(item.get("url") or ""),
                weight=weight,
                topics=_as_str_list(item.get("topics")),
                note=str(item.get("note") or ""),
                keyword=str(item.get("keyword") or item.get("excluded_keyword") or "").strip(),
                evidence=_parse_evidence(item.get("evidence")),
            )
        )

    policy_bullets: list[PolicyBulletOp] = []
    for index, item in enumerate(data.get("policy_bullets") or []):
        if not isinstance(item, dict):
            raise ProposalError(f"policy_bullets[{index}] must be an object")
        action = str(item.get("action") or "").strip()
        if action in REWRITE_ACTIONS:
            raise ProposalError(f"policy full-file rewrite is not allowed: {action}")
        if action not in POLICY_ACTIONS:
            raise ProposalError(f"unsupported policy action: {action or '(empty)'}")
        bullet_id = str(item.get("id") or "").strip()
        if not bullet_id:
            raise ProposalError(f"policy_bullets[{index}] missing id")
        policy_bullets.append(
            PolicyBulletOp(
                id=bullet_id,
                action=action,
                text=str(item.get("text") or ""),
                evidence=_parse_evidence(item.get("evidence")),
            )
        )

    memory_rule: MemoryRule | None = None
    raw_memory = data.get("memory_rule")
    if raw_memory:
        if not isinstance(raw_memory, dict):
            raise ProposalError("memory_rule must be an object")
        text_value = str(raw_memory.get("text") or "").strip()
        if text_value:
            memory_rule = MemoryRule(
                text=text_value,
                evidence=_parse_evidence(raw_memory.get("evidence")),
            )

    proposal = ReviewProposal(
        version=int(data.get("version") or 1),
        created_at=str(data.get("created_at") or ""),
        sources=sources,
        policy_bullets=policy_bullets,
        memory_rule=memory_rule,
    )
    ids = proposal.all_op_ids()
    if len(ids) != len(set(ids)):
        raise ProposalError("duplicate op ids")
    add_count = sum(1 for op in proposal.policy_bullets if op.action == "add")
    if add_count > MAX_POLICY_BULLETS:
        raise ProposalError(f"policy bullets exceed cap {MAX_POLICY_BULLETS}")
    return proposal


def _load_state() -> dict[str, Any]:
    path = proposal_state_path()
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        logger.warning("[Newsroom] proposal state unreadable (%s)", exc)
        return {}
    return data if isinstance(data, dict) else {}


def _write_state(payload: dict[str, Any]) -> None:
    path = proposal_state_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    os.replace(tmp, path)


def load_latest_proposal() -> ProposalSnapshot:
    """读取最新结构化提案。坏 JSON 不把半残当 pending。"""
    json_path = proposal_json_path()
    md_path = proposal_md_path()
    if not json_path.is_file():
        return ProposalSnapshot(
            status=STATUS_NONE,
            path=str(json_path),
            markdown_path=str(md_path),
        )
    try:
        raw = json_path.read_bytes()
        text = raw.decode("utf-8")
    except OSError as exc:
        return ProposalSnapshot(
            status=STATUS_INVALID,
            parse_error=f"unreadable proposal: {exc}",
            path=str(json_path),
            markdown_path=str(md_path),
        )
    digest = fingerprint_bytes(raw)
    try:
        proposal = parse_proposal_text(text)
    except ProposalError as exc:
        return ProposalSnapshot(
            status=STATUS_INVALID,
            parse_error=str(exc),
            fingerprint=digest,
            path=str(json_path),
            markdown_path=str(md_path),
        )
    state = _load_state()
    applied = [str(x) for x in state.get("applied_op_ids") or []]
    if state.get("fingerprint") != digest:
        applied = []
    status = STATUS_PENDING
    if state.get("fingerprint") == digest:
        stored = str(state.get("status") or "")
        if stored == STATUS_REJECTED:
            status = STATUS_REJECTED
        else:
            remaining = [op_id for op_id in proposal.all_op_ids() if op_id not in set(applied)]
            status = STATUS_APPLIED if not remaining else STATUS_PENDING
    return ProposalSnapshot(
        status=status,
        fingerprint=digest,
        path=str(json_path),
        markdown_path=str(md_path),
        proposal=proposal,
        updated_at=str(state.get("updated_at") or ""),
        actor=str(state.get("actor") or ""),
        applied_op_ids=applied,
        last_memory_error=str(state.get("last_memory_error") or ""),
    )


def _source_summary(op: SourceOp) -> str:
    if op.action == "add_excluded":
        return f"add_excluded {op.keyword or op.name}"
    bits = [op.action]
    if op.name:
        bits.append(op.name)
    if op.kind:
        bits.append(f"kind={op.kind}")
    if op.weight is not None:
        bits.append(f"weight={op.weight}")
    if op.query:
        bits.append(f"query={op.query}")
    if op.url:
        bits.append(f"url={op.url}")
    return " ".join(bits)


def ops_for_api(proposal: ReviewProposal | None) -> list[dict[str, Any]]:
    if proposal is None:
        return []
    rows: list[dict[str, Any]] = []
    for op in proposal.sources:
        rows.append(
            {
                "id": op.id,
                "kind": "source",
                "action": op.action,
                "name": op.name,
                "summary": _source_summary(op),
                "requires_explicit_select": True,
                "evidence": op.evidence.to_dict() if op.evidence else None,
                "payload": op.to_payload(),
            }
        )
    for op in proposal.policy_bullets:
        rows.append(
            {
                "id": op.id,
                "kind": "policy_bullet",
                "action": op.action,
                "name": op.id,
                "summary": f"{op.action} [{op.id}] {op.text}".strip(),
                "requires_explicit_select": True,
                "evidence": op.evidence.to_dict() if op.evidence else None,
                "payload": {"text": op.text},
            }
        )
    if proposal.memory_rule:
        rows.append(
            {
                "id": MEMORY_OP_ID,
                "kind": "memory_rule",
                "action": "add",
                "name": MEMORY_OP_ID,
                "summary": proposal.memory_rule.text,
                "requires_explicit_select": True,
                "evidence": (
                    proposal.memory_rule.evidence.to_dict()
                    if proposal.memory_rule.evidence
                    else None
                ),
                "payload": {"text": proposal.memory_rule.text},
            }
        )
    return rows


def _copy_book(book: SourceBook) -> SourceBook:
    return SourceBook(
        sources=[replace(src, topics=list(src.topics)) for src in book.sources],
        topics=list(book.topics),
        excluded_keywords=list(book.excluded_keywords),
        notes=book.notes,
    )


def _find_source(book: SourceBook, name: str) -> NewsSource | None:
    for source in book.sources:
        if source.name == name:
            return source
    return None


def _apply_source_ops(book: SourceBook, ops: list[SourceOp]) -> SourceBook:
    result = _copy_book(book)
    for op in ops:
        if op.action == "add_excluded":
            keyword = (op.keyword or op.name or op.query).strip()
            if not keyword:
                raise ProposalError("add_excluded requires keyword")
            if keyword not in result.excluded_keywords:
                result.excluded_keywords.append(keyword)
            continue
        if op.action == "add":
            result.sources.append(
                NewsSource(
                    name=op.name,
                    kind=op.kind or "search",
                    query=op.query,
                    url=op.url,
                    weight=3 if op.weight is None else op.weight,
                    topics=list(op.topics),
                    note=op.note,
                )
            )
            continue
        current = _find_source(result, op.name)
        if current is None:
            raise ProposalError(f"unknown source name: {op.name!r}")
        if op.action == "remove":
            result.sources = [src for src in result.sources if src.name != op.name]
            continue
        if op.action == "set_weight":
            if op.weight is None:
                raise ProposalError(f"set_weight {op.name!r} requires weight")
            current.weight = op.weight
            continue
        if op.action == "set_query":
            if not op.query.strip():
                raise ProposalError(f"set_query {op.name!r} requires query")
            current.query = op.query
            continue
        if op.action == "update":
            if op.kind:
                current.kind = op.kind
            if op.query:
                current.query = op.query
            if op.url:
                current.url = op.url
            if op.weight is not None:
                current.weight = op.weight
            if op.topics:
                current.topics = list(op.topics)
            if op.note:
                current.note = op.note
            continue
        raise ProposalError(f"unsupported source action: {op.action}")
    return result


def _apply_policy_ops(policy: EditorialPolicy, ops: list[PolicyBulletOp]) -> EditorialPolicy:
    bullets = [PolicyBullet(id=b.id, text=b.text) for b in policy.bullets]
    index = {b.id: i for i, b in enumerate(bullets)}
    for op in ops:
        if op.action in REWRITE_ACTIONS:
            raise ProposalError(f"policy full-file rewrite is not allowed: {op.action}")
        if op.action not in POLICY_ACTIONS:
            raise ProposalError(f"unsupported policy action: {op.action}")
        if op.action == "add":
            if not op.text.strip():
                raise ProposalError(f"policy add {op.id!r} requires text")
            if op.id in index:
                raise ProposalError(f"policy bullet id already exists: {op.id}")
            bullets.append(PolicyBullet(id=op.id, text=op.text.strip()))
            index[op.id] = len(bullets) - 1
        elif op.action == "replace":
            if op.id not in index:
                raise ProposalError(f"cannot replace unknown policy id: {op.id}")
            if not op.text.strip():
                raise ProposalError(f"policy replace {op.id!r} requires text")
            bullets[index[op.id]] = PolicyBullet(id=op.id, text=op.text.strip())
        elif op.action == "remove":
            if op.id not in index:
                raise ProposalError(f"cannot remove unknown policy id: {op.id}")
            bullets.pop(index[op.id])
            index = {b.id: i for i, b in enumerate(bullets)}
    if len(bullets) > MAX_POLICY_BULLETS:
        raise ProposalError(f"policy bullets exceed cap {MAX_POLICY_BULLETS}")
    return EditorialPolicy(preamble=policy.preamble, bullets=bullets)


def _snapshot_file(path: Path) -> _FileSnapshot:
    if path.is_file():
        return _FileSnapshot(path=path, existed=True, data=path.read_bytes())
    return _FileSnapshot(path=path, existed=False, data=None)


def _restore_file(snap: _FileSnapshot) -> None:
    if snap.existed:
        snap.path.parent.mkdir(parents=True, exist_ok=True)
        snap.path.write_bytes(snap.data or b"")
        return
    if snap.path.is_file():
        snap.path.unlink()


def append_audit(record: dict[str, Any]) -> None:
    """追加一行 apply 审计。不复用 evolution_decisions.jsonl。"""
    path = audit_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(record, ensure_ascii=False)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def _write_review_memory(
    text: str,
    evidence: Evidence | None,
    writer: MemoryWriter | None,
) -> str | None:
    if writer is None:
        return None
    metadata = {
        "source": MEMORY_SOURCE_TAG,
        "tags": [f"source={MEMORY_SOURCE_TAG}"],
        "evidence": evidence.to_dict() if evidence else None,
    }
    return writer(text, metadata)


def apply_proposal(
    *,
    selected_op_ids: list[str],
    apply_memory: bool = False,
    actor: str = "webui",
    memory_writer: MemoryWriter | None = None,
) -> ApplyResult:
    """只落盘调用方勾选且尚未落地的 ops。未勾选的信源绝不写 yaml。

    部分采纳后提案保持 pending，已落地的 op 记入 ``applied_op_ids``，
    不会因为勾了方针就把整单标成 applied。记忆写入失败不回滚 yaml，
    但 memory_rule 不计入已落地，下次可重试。
    """
    snapshot = load_latest_proposal()
    if snapshot.status == STATUS_NONE:
        raise ProposalError("no proposal")
    if snapshot.status == STATUS_INVALID or snapshot.proposal is None:
        raise ProposalError(snapshot.parse_error or "invalid proposal")
    if snapshot.status == STATUS_APPLIED:
        raise ProposalError("proposal already applied")
    if snapshot.status == STATUS_REJECTED:
        raise ProposalError("proposal already rejected")

    selected = [str(op_id).strip() for op_id in selected_op_ids if str(op_id).strip()]
    known = set(snapshot.proposal.all_op_ids())
    unknown = [op_id for op_id in selected if op_id not in known]
    if unknown:
        raise ProposalError(f"unknown op ids: {', '.join(unknown)}")

    already = set(snapshot.applied_op_ids)
    fresh = [op_id for op_id in selected if op_id not in already]
    want_memory = (apply_memory or MEMORY_OP_ID in selected) and MEMORY_OP_ID not in already
    source_ops = [op for op in snapshot.proposal.sources if op.id in fresh]
    policy_ops = [op for op in snapshot.proposal.policy_bullets if op.id in fresh]
    if not source_ops and not policy_ops and not (want_memory and snapshot.proposal.memory_rule):
        raise ProposalError(
            "already applied" if already.intersection(selected) else "no ops selected"
        )

    new_book: SourceBook | None = None
    if source_ops:
        new_book = _apply_source_ops(load_sources(), source_ops)
        errors = new_book.validate()
        if errors:
            raise ProposalError("; ".join(errors))

    new_policy: EditorialPolicy | None = None
    if policy_ops:
        new_policy = _apply_policy_ops(load_editorial_policy(), policy_ops)

    snaps = [_snapshot_file(proposal_state_path())]
    if source_ops:
        snaps.append(_snapshot_file(sources_path()))
    if policy_ops:
        snaps.append(_snapshot_file(editorial_policy_path()))

    newly_applied = [op.id for op in source_ops]
    newly_applied.extend(op.id for op in policy_ops)
    memory_id: str | None = None
    memory_written = False
    memory_error: str | None = None
    try:
        if new_book is not None:
            save_sources(new_book)
        if new_policy is not None:
            save_editorial_policy(new_policy)
        if want_memory and snapshot.proposal.memory_rule:
            try:
                memory_id = _write_review_memory(
                    snapshot.proposal.memory_rule.text,
                    snapshot.proposal.memory_rule.evidence,
                    memory_writer,
                )
                memory_written = bool(memory_id)
                if memory_id:
                    newly_applied.append(MEMORY_OP_ID)
                else:
                    memory_error = "memory writer returned empty id"
            except Exception as exc:
                memory_error = str(exc)
                logger.warning(
                    "[Newsroom] memory_rule write failed; yaml/policy still committed",
                    exc_info=True,
                )
        combined = list(dict.fromkeys([*snapshot.applied_op_ids, *newly_applied]))
        remaining = [
            op_id for op_id in snapshot.proposal.all_op_ids() if op_id not in set(combined)
        ]
        _write_state(
            {
                "fingerprint": snapshot.fingerprint,
                "status": STATUS_APPLIED if not remaining else STATUS_PENDING,
                "updated_at": _now_iso(),
                "actor": actor,
                "applied_op_ids": combined,
                "last_memory_error": memory_error or "",
            }
        )
    except Exception as exc:
        for snap in reversed(snaps):
            _restore_file(snap)
        try:
            append_audit(
                {
                    "ts": _now_iso(),
                    "actor": actor,
                    "action": "apply",
                    "fingerprint": snapshot.fingerprint,
                    "op_ids": list(selected),
                    "ok": False,
                    "error": str(exc),
                    "memory_written": False,
                }
            )
        except OSError:
            logger.warning("[Newsroom] failed-apply audit write failed", exc_info=True)
        if isinstance(exc, ProposalError):
            raise
        raise ProposalError(str(exc)) from exc

    combined = list(dict.fromkeys([*snapshot.applied_op_ids, *newly_applied]))
    remaining = [op_id for op_id in snapshot.proposal.all_op_ids() if op_id not in set(combined)]
    try:
        append_audit(
            {
                "ts": _now_iso(),
                "actor": actor,
                "action": "apply",
                "fingerprint": snapshot.fingerprint,
                "op_ids": list(newly_applied),
                "ok": True,
                "error": memory_error or "",
                "memory_written": memory_written,
                "remaining_op_ids": remaining,
            }
        )
    except OSError:
        logger.warning("[Newsroom] apply audit write failed", exc_info=True)

    return ApplyResult(
        applied_op_ids=newly_applied,
        remaining_op_ids=remaining,
        memory_written=memory_written,
        memory_id=memory_id,
        memory_error=memory_error,
    )


def reject_proposal(*, actor: str = "webui", reason: str = "") -> ProposalSnapshot:
    """拒绝提案：不改 yaml / 方针 / 记忆，写状态与审计。"""
    snapshot = load_latest_proposal()
    if snapshot.status == STATUS_NONE:
        raise ProposalError("no proposal")
    if snapshot.status == STATUS_INVALID or snapshot.proposal is None:
        raise ProposalError(snapshot.parse_error or "invalid proposal")
    if snapshot.status == STATUS_APPLIED:
        raise ProposalError("cannot reject an applied proposal")
    if snapshot.status == STATUS_REJECTED:
        return snapshot

    sources_before = sources_path().read_bytes() if sources_path().is_file() else None
    policy_before = (
        editorial_policy_path().read_bytes() if editorial_policy_path().is_file() else None
    )
    _write_state(
        {
            "fingerprint": snapshot.fingerprint,
            "status": STATUS_REJECTED,
            "updated_at": _now_iso(),
            "actor": actor,
            "reason": reason,
            "applied_op_ids": list(snapshot.applied_op_ids),
        }
    )
    sources_after = sources_path().read_bytes() if sources_path().is_file() else None
    policy_after = (
        editorial_policy_path().read_bytes() if editorial_policy_path().is_file() else None
    )
    if sources_before != sources_after or policy_before != policy_after:
        raise ProposalError("reject mutated files unexpectedly")

    try:
        append_audit(
            {
                "ts": _now_iso(),
                "actor": actor,
                "action": "reject",
                "fingerprint": snapshot.fingerprint,
                "op_ids": [],
                "ok": True,
                "error": "",
                "reason": reason,
                "memory_written": False,
            }
        )
    except OSError:
        logger.warning("[Newsroom] reject audit write failed", exc_info=True)
    return load_latest_proposal()


def archive_pending_proposal_if_needed() -> Path | None:
    """新提案覆盖前，把仍有未审 op 的旧 JSON/MD 拷到 issues/proposals/。"""
    json_path = proposal_json_path()
    if not json_path.is_file():
        return None
    snapshot = load_latest_proposal()
    if snapshot.status != STATUS_PENDING or not snapshot.fingerprint or snapshot.proposal is None:
        return None
    remaining = snapshot.remaining_op_ids()
    if not remaining:
        return None
    dest_dir = json_path.parent / "proposals"
    dest_dir.mkdir(parents=True, exist_ok=True)
    stamp = date.today().isoformat()
    dest = dest_dir / f"{stamp}-{snapshot.fingerprint[:10]}.json"
    if dest.exists():
        dest = (
            dest_dir / f"{stamp}-{snapshot.fingerprint[:10]}-{int(datetime.now().timestamp())}.json"
        )
    dest.write_bytes(json_path.read_bytes())
    md_path = proposal_md_path()
    if md_path.is_file():
        dest.with_suffix(".md").write_bytes(md_path.read_bytes())
    logger.warning(
        "[Newsroom] archived pending proposal %s (%d ops still open) → %s",
        snapshot.fingerprint[:10],
        len(remaining),
        dest,
    )
    return dest


def default_memory_writer_from_agent(agent: Any) -> MemoryWriter | None:
    """从运行中的 Agent 取出 memory_manager；没有则返回 None（apply 仍成功）。"""
    manager = getattr(agent, "memory_manager", None) if agent is not None else None
    if manager is None:
        return None

    def _write(text: str, metadata: dict[str, Any]) -> str | None:
        from newsclaw.memory.types import Memory, MemoryPriority, MemoryType

        memory = Memory(
            type=MemoryType.RULE,
            priority=MemoryPriority.LONG_TERM,
            content=text,
            source=str(metadata.get("source") or MEMORY_SOURCE_TAG),
            tags=list(metadata.get("tags") or [f"source={MEMORY_SOURCE_TAG}"]),
            importance_score=0.7,
        )
        memory_id = manager.add_memory(memory, scope="global")
        return str(memory_id) if memory_id else None

    return _write
