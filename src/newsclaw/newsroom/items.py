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

# URL 只含 ASCII 字符；不排除全角/中文会让稿面抽取带上尾随中文（如「…/news｜大模型」），
# 污染去重键。字符集取 RFC 3986 的保留 + 非保留字符（去掉引号）。
_URL_IN_TEXT = re.compile(r"https?://[0-9A-Za-z\-._~:/?#\[\]@!$&()*+,;=%]+", re.IGNORECASE)

# 标题去重键要剥掉的空白与中英文标点（不做模糊/向量匹配，只防同题换壳）。
_TITLE_STRIP_RE = re.compile(
    r"[\s\-—–·、，。！？：；「」『』《》〈〉（）()\[\]【】〖〗“”\"'‘’.,!?:;|/\\#*~`$%^&+=_<>=]+"
)


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


#: 平台稿「配图建议」常见的外链资源后缀：不是素材引用，不参与账本 ⊆ 机验。
_ASSET_URL_SUFFIXES = (
    ".jpg",
    ".jpeg",
    ".png",
    ".gif",
    ".webp",
    ".svg",
    ".mp4",
    ".mp3",
)


def is_asset_url(raw: str) -> bool:
    """URL 是否指向图片/音视频等静态资源（按路径后缀判断）。"""
    text = (raw or "").strip().lower()
    return any(text.endswith(suffix) for suffix in _ASSET_URL_SUFFIXES)


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


def normalize_title(raw: str) -> str:
    """标题去重键：去空白与中英文标点后 casefold。

    精确匹配（同一标题的排版差异收拢），不做模糊/相似度——连续报道的
    相近标题留给编辑判断，这里只防「同题换链接重发」。
    """
    text = (raw or "").strip().casefold()
    return _TITLE_STRIP_RE.sub("", text)


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
    """窗口内各期（ready / partial / rejected 一视同仁）的已见规范化 URL → 期次日期。

    优先读 ``manifest.items``；旧期没有账本、或根本没有 manifest（跑了一半的
    现场）时，从日报稿抽链接顶一下。只按 ready 收录会让点踩降级把整期链接
    放回去重池——负反馈反而放行重复采集。损坏 manifest 的期次退回稿面抽取。
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
        urls: list[str] = []
        manifest, _error = load_manifest(day, require_item_ledger=False)
        if manifest is not None:
            urls = [item.url for item in manifest.items if item.url]
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


def collect_seen_titles(*, before_date: str, days: int) -> dict[str, str]:
    """窗口内各期 manifest.items 的已见标题规范化键 → 期次日期。

    只走 items 账本：标题无法从稿面可靠抽取，不设 brief 兜底——没有
    账本的旧期对标题去重没有贡献（它们的链接已被 URL 去重覆盖）。
    """
    from newsclaw.newsroom.contract import load_manifest

    try:
        end = date.fromisoformat(before_date)
    except ValueError:
        return {}
    window = max(1, int(days))
    seen: dict[str, str] = {}
    for offset in range(1, window + 1):
        day = (end - timedelta(days=offset)).isoformat()
        manifest, _error = load_manifest(day, require_item_ledger=False)
        if manifest is None:
            continue
        for item in manifest.items:
            key = normalize_title(item.title)
            if key and key not in seen:
                seen[key] = day
    return seen


def format_seen_items_for_prompt(*, before_date: str, days: int, limit: int = 400) -> str:
    """给每日注入块用的「近 N 天已见」清单。

    ``limit`` 必须容纳窗口全量（7 天 × 十余条 ≈ 百余）：机验对账用的是不截断
    的 :func:`collect_seen_urls`，注入端截断会让编辑在看不见的链接上撞车，
    直到写 manifest 才报错作废。
    """
    seen = collect_seen_urls(before_date=before_date, days=days)
    if not seen:
        return "（窗口内无已 ready 期次，或尚无条目账本）"
    lines: list[str] = []
    for key, day in list(seen.items())[:limit]:
        lines.append(f"- {day} | {key}")
    if len(seen) > limit:
        lines.append(f"- … 另有 {len(seen) - limit} 条已省略")
    return "\n".join(lines)
