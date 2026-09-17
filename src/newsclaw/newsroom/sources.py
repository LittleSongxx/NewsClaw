"""信源清单（data/newsroom/sources.yaml）——自进化的主要落点。

清单同时服务三个读者：
- 管线 Agent：按 ``kind`` 采集（search 走搜索工具，site 走浏览器直采）；
- 每周复盘任务：依据自评与人工反馈调整 weight / keywords / topics，改写本文件；
- WebUI 工作台：直接编辑并保存（PUT /api/newsroom/sources）。

清单的默认值唯一来源是 :class:`SourceBook` 的字段缺省（首次读取时落盘）。
weight 为 1–5 的整数，采集预算（时间与条数）按权重分配。
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from .contract import newsroom_root

logger = logging.getLogger(__name__)

SOURCES_FILENAME = "sources.yaml"

_VALID_KINDS = ("search", "site")

_DEFAULT_TOPICS = ("大模型", "公司动态", "开源项目", "研究与论文", "政策与监管")


def _default_sources() -> list[NewsSource]:
    """开箱即用的默认信源（全部走搜索工具，零配置可用；站点直采由用户按需添加）。"""
    return [
        NewsSource(
            name="AI 综合搜索",
            kind="search",
            query="AI 人工智能 大模型 最新新闻",
            weight=4,
            topics=["大模型", "公司动态"],
        ),
        NewsSource(
            name="前沿模型动态",
            kind="search",
            query="LLM frontier model release",
            weight=3,
            topics=["大模型", "研究与论文"],
        ),
        NewsSource(
            name="公司与融资",
            kind="search",
            query="AI 公司 融资 发布",
            weight=3,
            topics=["公司动态"],
        ),
        NewsSource(
            name="开源项目热点",
            kind="search",
            query="GitHub trending AI open source",
            weight=2,
            topics=["开源项目"],
        ),
    ]


@dataclass
class NewsSource:
    """单个信源。

    Attributes:
        name: 唯一标识（同时用于 manifest.sources_used 对账），中文即可。
        kind: ``search`` = 用 query 调 web_search/news_search；
              ``site``  = 用 url 浏览器直采（适合无搜索收录的站点）。
        query / url: 与 kind 对应的取数参数。
        weight: 1–5，采集预算分配权重。
        topics: 归属主题（与 SourceBook.topics 对齐，Wiki 沉淀按主题建页）。
        note: 给编辑 Agent 的人工备注（如"付费墙，只取标题"）。
    """

    name: str
    kind: str
    query: str = ""
    url: str = ""
    weight: int = 3
    topics: list[str] = field(default_factory=list)
    note: str = ""

    def validate(self) -> list[str]:
        errors: list[str] = []
        if not self.name.strip():
            errors.append("source name must not be empty")
        if self.kind not in _VALID_KINDS:
            errors.append(f"source {self.name!r}: kind must be one of {_VALID_KINDS}")
        if self.kind == "search" and not self.query.strip():
            errors.append(f"source {self.name!r}: search source requires query")
        if self.kind == "site" and not self.url.strip():
            errors.append(f"source {self.name!r}: site source requires url")
        if not 1 <= self.weight <= 5:
            errors.append(f"source {self.name!r}: weight must be 1-5")
        return errors


@dataclass
class SourceBook:
    """信源清单整体：信源 + 主题分类 + 排除词 + 编辑备注。"""

    sources: list[NewsSource] = field(default_factory=_default_sources)
    topics: list[str] = field(default_factory=lambda: list(_DEFAULT_TOPICS))
    excluded_keywords: list[str] = field(default_factory=list)
    notes: str = ""

    def validate(self) -> list[str]:
        errors: list[str] = []
        names = [s.name for s in self.sources]
        if len(names) != len(set(names)):
            errors.append("duplicate source names")
        for source in self.sources:
            errors.extend(source.validate())
        return errors

    # ── 序列化 ────────────────────────────────────────────────────

    @classmethod
    def from_dict(cls, data: dict) -> SourceBook:
        sources = [
            NewsSource(
                name=str(item.get("name", "")),
                kind=str(item.get("kind", "search")),
                query=str(item.get("query", "")),
                url=str(item.get("url", "")),
                weight=int(item.get("weight", 3)),
                topics=[str(t) for t in item.get("topics") or []],
                note=str(item.get("note", "")),
            )
            for item in data.get("sources") or []
            if isinstance(item, dict)
        ]
        return cls(
            sources=sources,
            topics=[str(t) for t in data.get("topics") or []] or list(_DEFAULT_TOPICS),
            excluded_keywords=[str(k) for k in data.get("excluded_keywords") or []],
            notes=str(data.get("notes", "")),
        )

    def to_dict(self) -> dict:
        return {
            "topics": self.topics,
            "excluded_keywords": self.excluded_keywords,
            "notes": self.notes,
            "sources": [
                {
                    "name": s.name,
                    "kind": s.kind,
                    "query": s.query,
                    "url": s.url,
                    "weight": s.weight,
                    "topics": s.topics,
                    "note": s.note,
                }
                for s in self.sources
            ],
        }


_SOURCES_HEADER = """\
# AI 早报信源清单。weight(1-5) 决定采集预算分配；topics 对齐文件内主题列表。
# kind: search = query 走搜索工具；site = url 浏览器直采（适合搜索收录差的站点）。
# 每周复盘任务会依据自评与人工反馈自动调整本文件；也可在 WebUI 工作台手动编辑。
"""


def sources_path() -> Path:
    return newsroom_root() / SOURCES_FILENAME


def load_sources() -> SourceBook:
    """读取信源清单；文件缺失时写入默认清单（首次使用即有可用配置）。"""
    path = sources_path()
    if not path.is_file():
        save_sources(SourceBook())
        return SourceBook()
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except (yaml.YAMLError, OSError) as e:
        logger.warning("[Newsroom] sources.yaml unreadable (%s); using defaults", e)
        return SourceBook()
    if not isinstance(data, dict):
        logger.warning("[Newsroom] sources.yaml is not a mapping; using defaults")
        return SourceBook()
    return SourceBook.from_dict(data)


def save_sources(book: SourceBook) -> Path:
    """校验并原子写入信源清单（统一带说明注释头）。"""
    errors = book.validate()
    if errors:
        raise ValueError("; ".join(errors))
    path = sources_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    text = _SOURCES_HEADER + yaml.safe_dump(book.to_dict(), allow_unicode=True, sort_keys=False)
    tmp = path.with_suffix(".yaml.tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)
    return path
