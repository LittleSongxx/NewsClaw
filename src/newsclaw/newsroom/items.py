"""早报素材账本：规范化 URL、条目模型、往期已见链接。

去重只做**精确 URL**（去掉跟踪参数后相同即重复）。同一事件换链接仍留给
编辑判断，不在这里用向量相似度误伤连续报道。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

# 常见分享/广告跟踪参数，去重时丢掉，避免同一篇文章带不同 utm 算两条。
_TRACKING_QUERY_PREFIXES = ("utm_",)
_TRACKING_QUERY_KEYS = frozenset(
    {
        "fbclid",
        "gclid",
        "mc_cid",
        "mc_eid",
        "ref",
        "spm",
        "from",
        "source",
        "share_from",
        "share_token",
    }
)

_URL_IN_TEXT = re.compile(r"https?://[^\s)\]>\"']+", re.IGNORECASE)


@dataclass
class NewsItem:
    """一条入选素材。ready 期次用它代替「稿子里随便出现一个 http」。"""

    title: str
    url: str
    source_name: str = ""
    one_liner: str = ""

    def validate(self) -> list[str]:
        errors: list[str] = []
        if not self.title.strip():
            errors.append("item title is empty")
        if not _is_http_url(self.url):
            errors.append(f"item url is not http(s): {self.url!r}")
        return errors

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> NewsItem:
        return cls(
            title=str(data.get("title", "")).strip(),
            url=str(data.get("url", "")).strip(),
            source_name=str(data.get("source_name", "")).strip(),
            one_liner=str(data.get("one_liner", "")).strip(),
        )

    def to_dict(self) -> dict[str, str]:
        return {
            "title": self.title,
            "url": self.url,
            "source_name": self.source_name,
            "one_liner": self.one_liner,
        }


def _is_http_url(raw: str) -> bool:
    try:
        parsed = urlparse(raw.strip())
    except ValueError:
        return False
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def normalize_url(raw: str) -> str:
    """把同一篇文章的不同分享链接收成同一把钥匙。失败则返回去空白原串。"""
    text = (raw or "").strip()
    if not text:
        return ""
    try:
        parsed = urlparse(text)
    except ValueError:
        return text
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return text
    host = parsed.netloc.lower()
    if host.startswith("www."):
        host = host[4:]
    query_pairs = []
    for key, value in parse_qsl(parsed.query, keep_blank_values=True):
        lowered = key.lower()
        if lowered in _TRACKING_QUERY_KEYS or any(
            lowered.startswith(prefix) for prefix in _TRACKING_QUERY_PREFIXES
        ):
            continue
        query_pairs.append((key, value))
    path = parsed.path.rstrip("/") or ""
    return urlunparse(("https", host, path, "", urlencode(query_pairs), ""))


def extract_urls_from_text(text: str) -> list[str]:
    """从稿件里抽出 http(s) 链接（去标点尾巴）。"""
    found: list[str] = []
    seen: set[str] = set()
    for match in _URL_IN_TEXT.findall(text or ""):
        cleaned = match.rstrip(".,;:。，；")
        key = normalize_url(cleaned)
        if key and key not in seen:
            seen.add(key)
            found.append(cleaned)
    return found


def parse_items(raw: Any) -> list[NewsItem]:
    if not raw:
        return []
    if not isinstance(raw, list):
        raise ValueError("items must be a list")
    items: list[NewsItem] = []
    for index, row in enumerate(raw):
        if not isinstance(row, dict):
            raise ValueError(f"items[{index}] must be an object")
        items.append(NewsItem.from_dict(row))
    return items


def collect_seen_urls(*, before_date: str, days: int) -> dict[str, str]:
    """窗口内其它 ready 期次的已见规范化 URL → 期次日期。

    优先读 ``manifest.items``；旧期没有账本时，从日报稿抽链接顶一下，
    避免历史期次完全帮不上忙。
    """
    from newsclaw.newsroom.contract import (
        ARTIFACT_DAILY_BRIEF,
        issue_dir,
        load_manifest,
    )

    try:
        end = date.fromisoformat(before_date)
    except ValueError:
        return {}
    window = max(1, int(days))
    seen: dict[str, str] = {}
    for offset in range(1, window + 1):
        day = (end - timedelta(days=offset)).isoformat()
        manifest, error = load_manifest(day, require_item_ledger=False)
        if manifest is None or error or manifest.status != "ready":
            continue
        urls: list[str] = [item.url for item in manifest.items if item.url]
        if not urls:
            brief = issue_dir(day) / ARTIFACT_DAILY_BRIEF
            if brief.is_file():
                try:
                    urls = extract_urls_from_text(brief.read_text(encoding="utf-8"))
                except OSError:
                    urls = []
        for url in urls:
            key = normalize_url(url)
            if key and key not in seen:
                seen[key] = day
    return seen


def format_seen_items_for_prompt(*, before_date: str, days: int, limit: int = 40) -> str:
    """给每日注入块用的「近 N 天已见」清单。"""
    seen = collect_seen_urls(before_date=before_date, days=days)
    if not seen:
        return "（窗口内无已 ready 期次，或尚无条目账本）"
    lines: list[str] = []
    for key, day in list(seen.items())[:limit]:
        lines.append(f"- {day} | {key}")
    if len(seen) > limit:
        lines.append(f"- … 另有 {len(seen) - limit} 条已省略")
    return "\n".join(lines)
