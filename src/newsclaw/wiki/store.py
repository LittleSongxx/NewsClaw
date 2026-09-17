"""本地 Wiki 存储层（NewsClaw 自研）。

把每期调研结果沉淀成**结构化 Markdown 知识库**，目录本身就是一个合法的
Obsidian vault（纯 .md + YAML frontmatter + ``[[wikilink]]``），因此：

    · 项目前端可以直接渲染与检索（见 api/routes/wiki.py + WikiView）；
    · 想用 Obsidian 打开这个目录即可获得双链/图谱/MOC 体验，无需搬文件。

页面组织（与 newsroom 的 topics 对齐）：
    · ``type: topic``    主题页：大模型 / 公司动态 / 开源项目 / 研究与论文 / 政策监管
    · ``type: company``  重点公司页：OpenAI / 谷歌 / 英伟达 …
    · ``MOC.md``         索引页（自动维护，不由 Agent 手写）

写入是**幂等**的：同一天重复写同一页会「替换该日期章节」而不是追加重复内容，
这让管线重跑（幂等分支）不会污染知识库。
"""

from __future__ import annotations

import logging
import os
import re
import time
from dataclasses import dataclass, field
from pathlib import Path

import yaml

logger = logging.getLogger(__name__)


class WikiDisabledError(RuntimeError):
    """``obsidian_vault`` 为空时拒绝写入，避免悄悄落到 ``data/wiki``。"""


MOC_FILENAME = "MOC.md"
_MAX_PAGE_NAME = 60
_DATE_SECTION_RE = re.compile(r"^##\s+(\d{4}-\d{2}-\d{2})\s*$", re.M)
_WIKILINK_RE = re.compile(r"\[\[([^\[\]|]+)(?:\|[^\[\]]*)?\]\]")
_ILLEGAL_NAME_CHARS = set('/\\:*?"<>|')
_RESERVED_NAMES = {"moc", "index", "readme"}

#: 主题页默认名单（sources.yaml 缺 topics 时的回退；用于 MOC 分组与前端筛选）
TOPIC_PAGES = ("大模型", "公司动态", "开源项目", "研究与论文", "政策与监管")


def topic_pages() -> tuple[str, ...]:
    """当前主题列表：优先读 sources.yaml，读不到再用默认常量。"""
    try:
        from newsclaw.newsroom.sources import load_sources

        topics = [str(item).strip() for item in load_sources().topics if str(item).strip()]
        if topics:
            return tuple(topics)
    except Exception:
        logger.debug("[wiki] falling back to default topic pages", exc_info=True)
    return TOPIC_PAGES


@dataclass
class WikiEntry:
    """当日某页的一条要点。"""

    text: str
    source: str = ""


@dataclass
class PageInfo:
    """页面摘要（列表/树视图用）。"""

    name: str
    path: str  # 相对 wiki 根的路径，如 "主题/大模型.md"
    kind: str  # topic | company | index
    updated_at: str
    dates: list[str] = field(default_factory=list)
    links: list[str] = field(default_factory=list)
    excerpt: str = ""


# ── 根目录解析 ──────────────────────────────────────────────────────


def wiki_enabled() -> bool:
    """与 newsroom 配置同一扇门：空 vault = 整条 Wiki（prompt + API + 工具）关闭。"""
    try:
        from ..newsroom.config import load_config

        return load_config().wiki_enabled()
    except Exception:
        return False


def wiki_root() -> Path | None:
    """Wiki 根目录。

    只认 newsroom 配置里的 ``obsidian_vault``。为空则返回 ``None``，
    读写一律跳过——不再回落到 ``data/wiki`` 假装库还开着。
    """
    try:
        from ..newsroom.config import load_config

        vault = (load_config().obsidian_vault or "").strip()
        if vault:
            return Path(vault).expanduser()
    except Exception as exc:
        logger.debug("[wiki] 读取 obsidian_vault 失败，视为未启用: %s", exc)
    return None


def _require_wiki_root() -> Path:
    root = wiki_root()
    if root is None:
        raise WikiDisabledError("obsidian_vault 未配置，已跳过 Wiki 写入")
    return root


def _atomic_write_text(path: Path, text: str) -> None:
    """tmp + os.replace，避免写到一半崩溃留下半份 Markdown。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)


def sanitize_page_name(raw: str) -> str:
    """把任意输入收敛成安全的页面名（同时挡住路径穿越）。"""
    name = (raw or "").strip()
    name = name.replace("\n", " ").replace("\r", " ")
    for ch in _ILLEGAL_NAME_CHARS:
        name = name.replace(ch, "・")
    name = " ".join(name.split())  # 折叠空白
    name = name.strip(". ")  # 头尾的点/空格在文件系统上不安全
    name = name.replace("..", ".")  # 消除 .. 残留（虽不构成路径穿越，但易误判）
    if len(name) > _MAX_PAGE_NAME:
        name = name[:_MAX_PAGE_NAME]
    if not name:
        raise ValueError("页面名不能为空")
    if name.lower() in _RESERVED_NAMES:
        raise ValueError(f"页面名 {name!r} 为保留名（索引页由系统维护）")
    return name


def page_kind_dir(kind: str) -> str:
    return "公司" if kind == "company" else "主题"


def page_path(name: str, kind: str = "topic") -> Path:
    """页面文件路径（按 type 分子目录，便于在 Obsidian 里浏览）。"""
    safe = sanitize_page_name(name)
    root = _require_wiki_root()
    if safe == "MOC":
        return root / MOC_FILENAME
    return root / page_kind_dir(kind) / f"{safe}.md"


# ── frontmatter / 渲染 ─────────────────────────────────────────────


def _split_frontmatter(text: str) -> tuple[dict[str, str], str]:
    """解析 YAML frontmatter。``title: foo: bar`` 必须整段保留，不能按冒号切开。"""
    if not text.startswith("---\n"):
        return {}, text
    end = text.find("\n---\n", 3)
    if end < 0:
        return {}, text
    try:
        loaded = yaml.safe_load(text[4:end]) or {}
    except yaml.YAMLError:
        loaded = {}
    if not isinstance(loaded, dict):
        return {}, text[end + 5 :]
    meta = {str(k): "" if v is None else str(v) for k, v in loaded.items()}
    return meta, text[end + 5 :]


def _render_frontmatter(meta: dict[str, str]) -> str:
    dumped = yaml.safe_dump(
        dict(meta),
        allow_unicode=True,
        sort_keys=False,
        default_flow_style=False,
    )
    return f"---\n{dumped}---\n"


def _render_entries(entries: list[WikiEntry], links: list[str]) -> str:
    lines: list[str] = []
    for entry in entries:
        text = (entry.text or "").strip()
        if not text:
            continue
        if entry.source:
            lines.append(f"- {text}（[来源]({entry.source})）")
        else:
            lines.append(f"- {text}")
    if links:
        lines.append("")
        lines.append("相关：" + " ".join(f"[[{sanitize_page_name(n)}]]" for n in links))
    return "\n".join(lines)


def _render_section(day: str, summary: str, entries: list[WikiEntry], links: list[str]) -> str:
    body = _render_entries(entries, links)
    head = f"## {day}"
    if summary.strip():
        return f"{head}\n\n> {summary.strip()}\n\n{body}\n"
    return f"{head}\n\n{body}\n"


def _split_sections(body: str) -> list[tuple[str, str]]:
    """把正文切成 ``[(日期, 该日期章节的文本), ...]``（顺序即文档顺序）。

    图谱按时间过滤需要**逐期的链接**，而不是整页合并后的出链——否则
    "最近 7 期的关系变化"就无从谈起。
    """
    matches = list(_DATE_SECTION_RE.finditer(body))
    sections: list[tuple[str, str]] = []
    for i, m in enumerate(matches):
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        sections.append((m.group(1), body[start:end]))
    return sections


def _extract_section(text: str, day: str) -> tuple[int, int] | None:
    """返回 ``## <day>`` 章节在 body 中的 [start, end) 区间（end 为下一章节起点）。"""
    matches = list(_DATE_SECTION_RE.finditer(text))
    for i, m in enumerate(matches):
        if m.group(1) != day:
            continue
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        return start, end
    return None


# ── 写入 ────────────────────────────────────────────────────────────


def upsert_daily_section(
    name: str,
    *,
    kind: str = "topic",
    day: str,
    summary: str = "",
    entries: list[WikiEntry] | None = None,
    links: list[str] | None = None,
) -> dict:
    """把某天的要点写入/替换成该页的 "## <日期>" 章节（幂等）。

    Returns:
        ``{"path": 相对路径, "created": bool, "replaced": bool}``
    """
    entries = entries or []
    links = links or []
    try:
        from datetime import date as _date

        _date.fromisoformat(day)
    except ValueError as exc:
        raise ValueError(f"day 必须是 YYYY-MM-DD：{day!r}") from exc
    safe = sanitize_page_name(name)
    root = _require_wiki_root()
    path = page_path(safe, kind)
    path.parent.mkdir(parents=True, exist_ok=True)

    existed = path.is_file()
    if existed:
        raw = path.read_text(encoding="utf-8")
    else:
        raw = (
            _render_frontmatter({"title": safe, "type": kind, "created": day, "last_updated": day})
            + f"\n# {safe}\n"
        )

    meta, body = _split_frontmatter(raw)
    section = _render_section(day, summary, entries, links)
    span = _extract_section(body, day)
    replaced = span is not None
    if span:
        body = body[: span[0]] + section + body[span[1] :]
    else:
        body = body.rstrip("\n") + "\n\n" + section

    meta["title"] = safe
    meta["type"] = kind
    meta.setdefault("created", day)
    meta["last_updated"] = day
    _atomic_write_text(path, _render_frontmatter(meta) + body)
    logger.info(
        "[wiki] %s %s (%s%s)",
        "替换" if replaced else "写入",
        path.name,
        day,
        "" if existed else "，新建",
    )
    return {"path": str(path.relative_to(root)), "created": not existed, "replaced": replaced}


def rebuild_moc() -> Path:
    """重建索引页：按 主题 / 公司 分组列出所有页面（含期数）。"""
    root = _require_wiki_root()
    pages = [p for p in list_pages() if p.kind != "index"]
    groups: dict[str, list[PageInfo]] = {"topic": [], "company": []}
    for page in pages:
        groups.setdefault(page.kind, []).append(page)

    lines = [
        _render_frontmatter(
            {
                "title": "索引",
                "type": "index",
                "last_updated": time.strftime("%Y-%m-%d"),
            }
        ).rstrip("\n"),
        "",
        "# AI 早报知识库 · 索引",
        "",
        f"> 共 {len(pages)} 个页面，由 NewsClaw 管线自动维护（{len(pages)} 页自动统计）。",
    ]
    for kind, title in (("topic", "主题"), ("company", "公司")):
        items = sorted(groups.get(kind, []), key=lambda p: (-len(p.dates), p.name))
        if not items:
            continue
        lines += ["", f"## {title}", ""]
        lines += [f"- [[{p.name}]] —— {len(p.dates)} 期" for p in items]
    moc = root / MOC_FILENAME
    _atomic_write_text(moc, "\n".join(lines) + "\n")
    return moc


# ── 读取 ────────────────────────────────────────────────────────────


def list_pages() -> list[PageInfo]:
    """列出所有页面（含 frontmatter 元信息、日期章节、出链、摘要）。"""
    root = wiki_root()
    if root is None or not root.is_dir():
        return []
    pages: list[PageInfo] = []
    for path in sorted(root.rglob("*.md")):
        try:
            raw = path.read_text(encoding="utf-8")
        except OSError:
            continue
        meta, body = _split_frontmatter(raw)
        name = meta.get("title") or path.stem
        kind = meta.get("type") or ("index" if path.name == MOC_FILENAME else "topic")
        dates = [m.group(1) for m in _DATE_SECTION_RE.finditer(body)]
        excerpt = next(
            (
                line.strip("#> ").strip()
                for line in body.splitlines()
                if line.strip() and not line.startswith("#")
            ),
            "",
        )
        pages.append(
            PageInfo(
                name=name,
                path=str(path.relative_to(root)),
                kind=kind,
                updated_at=meta.get("last_updated", ""),
                dates=dates,
                links=[m.group(1).strip() for m in _WIKILINK_RE.finditer(body)],
                excerpt=excerpt[:120],
            )
        )
    return pages


def read_page(name_or_path: str) -> str | None:
    """按页面名或相对路径读取正文（含 frontmatter）。"""
    root = wiki_root()
    if root is None:
        return None
    candidate = root / name_or_path
    if candidate.is_file() and candidate.suffix == ".md":
        try:
            return candidate.read_text(encoding="utf-8")
        except OSError:
            return None
    for page in list_pages():
        if page.name == name_or_path:
            path = root / page.path
            try:
                return path.read_text(encoding="utf-8")
            except OSError:
                return None
    return None


def backlinks(name: str) -> list[str]:
    """反向链接：哪些**内容页**链接到了本页。

    索引页（MOC）刻意排除——它链接到所有页面属于导航枢纽，不是内容引用；
    把它算进来会让每个页面的"反链"都恒等于索引，噪声大于信息。
    """
    target = sanitize_page_name(name)
    return [
        p.name for p in list_pages() if p.kind != "index" and target in p.links and p.name != target
    ]


def graph_data(*, days: int | None = None, today: str | None = None) -> dict:
    """知识网络图数据：节点（页面）+ 边（``[[双链]]``）。

    Args:
        days: ``None`` = 全量；``N`` = 只保留**最近 N 天内有更新的章节**及其关系
            （回答"最近几期的关系变化"）。节点集合同步收敛为窗口内活跃的页面。
        today: 覆盖"今天"（测试用），格式 ``YYYY-MM-DD``。

    规则：
        · 索引页（MOC）不参与——它链接所有页面，画进来就是星形噪声；
        · 边为无向去重；悬空链接（指向不存在的页面）丢弃；
        · 节点 ``dates`` 表示**窗口内**的更新期数（全量模式即总期数），
          前端据此决定节点大小。
    """
    from datetime import date as _date
    from datetime import timedelta

    pages = [p for p in list_pages() if p.kind != "index"]
    root = wiki_root()
    if root is None:
        return {"nodes": [], "links": []}

    # 每页的逐期出链：(日期, [目标页名...])
    per_page: dict[str, list[tuple[str, list[str]]]] = {}
    kinds: dict[str, str] = {}
    paths: dict[str, str] = {}
    for page in pages:
        try:
            raw = (root / page.path).read_text(encoding="utf-8")
        except OSError:
            continue
        _, body = _split_frontmatter(raw)
        per_page[page.name] = [
            (day, [m.group(1).strip() for m in _WIKILINK_RE.finditer(text)])
            for day, text in _split_sections(body)
        ]
        kinds[page.name] = page.kind
        paths[page.name] = page.path

    cutoff = ""
    if days:
        base = today or _date.today().isoformat()
        cutoff = (_date.fromisoformat(base) - timedelta(days=max(int(days), 1) - 1)).isoformat()

    def _in_window(day: str) -> bool:
        return not cutoff or day >= cutoff

    include = {
        name
        for name, sections in per_page.items()
        if not cutoff or any(_in_window(day) for day, _ in sections)
    }

    seen: set[tuple[str, str]] = set()
    links: list[dict] = []
    for name in sorted(include):
        for day, targets in per_page[name]:
            if not _in_window(day):
                continue
            for target in targets:
                if target not in include or target == name:
                    continue
                key = tuple(sorted((name, target)))
                if key in seen:
                    continue
                seen.add(key)
                links.append({"source": key[0], "target": key[1]})

    nodes = [
        {
            "name": name,
            "kind": kinds[name],
            "path": paths[name],
            "dates": sum(1 for day, _ in per_page[name] if _in_window(day)),
            "updated_at": max((day for day, _ in per_page[name] if _in_window(day)), default=""),
        }
        for name in sorted(include)
    ]
    return {"nodes": nodes, "links": links}


def search(keyword: str, limit: int = 30) -> list[dict]:
    """朴素全文检索（大小写不敏感的子串匹配；量级小，无需索引）。"""
    kw = (keyword or "").strip().lower()
    if not kw:
        return []
    hits: list[dict] = []
    root = wiki_root()
    if root is None:
        return []
    for page in list_pages():
        try:
            raw = (root / page.path).read_text(encoding="utf-8")
        except OSError:
            continue
        matched = [ln.strip() for ln in raw.splitlines() if kw in ln.lower()]
        if matched:
            hits.append({"page": page.name, "path": page.path, "matches": matched[:5]})
        if len(hits) >= limit:
            break
    return hits
