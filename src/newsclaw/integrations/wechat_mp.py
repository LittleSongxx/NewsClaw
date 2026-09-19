"""微信公众号（Official Account）发布集成。

与 ``channels/adapters/wechat.py``（微信**个人号** iLink Bot）无关：本模块走
公众号服务端 API（api.weixin.qq.com，AppID/AppSecret 鉴权），把 Markdown 稿
发布成公众号图文：

    access_token → 正文图片上传(media/uploadimg) → 封面永久素材
    (material/add_material) → 新建草稿(draft/add) → 发布(freepublish/submit)

排版遵循 skills/wechat-article/SKILL.md 的内联样式规范（公众号编辑器不认
<style>/class，只认 style 属性）。失败语义与 feishu_doc 一致：返回带指引的
中文错误（IP 白名单 / 未认证账号无接口权限等），让上层 Agent 如实报告而不是
静默重试。
"""

from __future__ import annotations

import html
import logging
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

API_BASE = "https://api.weixin.qq.com"

#: token 提前视为过期的安全窗口（秒）
_TOKEN_SAFETY_WINDOW = 120

#: 公众号封面建议尺寸 2.35:1（900×383）
COVER_WIDTH = 900
COVER_HEIGHT = 383

_TITLE_MAX_CHARS = 64  # 草稿接口标题上限
_DIGEST_MAX_CHARS = 120  # 摘要上限

# ---------------------------------------------------------------------------
# 错误与指引
# ---------------------------------------------------------------------------

#: 常见 errcode → 人话指引（缺省给通用排查建议）
_ERRCODE_GUIDANCE: dict[int, str] = {
    40001: "access_token 无效或已过期（多为 AppSecret 不正确，或该 secret 刚被重置）",
    40013: "AppID 无效：请核对公众号后台「设置与开发 → 基本配置」里的 AppID",
    40125: "AppSecret 无效：请核对或重置公众号后台的 AppSecret",
    40164: (
        "本机出口 IP 不在公众号 IP 白名单：请到 mp.weixin.qq.com「设置与开发 → "
        "安全中心 → IP 白名单」添加错误信息里提示的 IP，保存后约 5 分钟生效"
    ),
    42001: "access_token 已过期（客户端应刷新后重试）",
    45009: "调用频率超限（素材/发布接口有每日配额），请稍后重试",
    40007: "media_id 无效（素材可能已被删除或属于其它账号）",
    47001: "请求体不合法：多为缺少 thumb_media_id 或正文超出限制",
    48001: (
        "公众号无该接口权限：草稿箱/发布接口要求**已认证**的公众号；"
        "个人订阅号无法调用（可在公众平台「接口权限」页确认）"
    ),
    53401: "草稿数超出上限（默认 1000 篇），请清理草稿箱",
    53404: "账号已发布内容超出当日限额，请次日再试",
}


class WeChatMPError(RuntimeError):
    """公众号 API 业务错误，带 errcode 与可执行指引。"""

    def __init__(self, errcode: int, errmsg: str) -> None:
        guidance = _ERRCODE_GUIDANCE.get(
            errcode, "通用排查：检查 AppID/AppSecret、网络与公众号接口权限"
        )
        if errcode == 40164:
            # 官方 msg 形如 "invalid ip 1.2.3.4 ipv6 ..., not in whitelist"，
            # 把出口 IP 摘出来方便用户直接抄进白名单
            match = re.search(r"invalid ip\s+([0-9a-fA-F.:]+)", errmsg)
            if match:
                guidance += f"（本机出口 IP：{match.group(1)}）"
        super().__init__(f"微信公众号接口失败（errcode={errcode}）：{errmsg}。{guidance}")
        self.errcode = errcode
        self.errmsg = errmsg


# ---------------------------------------------------------------------------
# API 客户端
# ---------------------------------------------------------------------------


class WeChatMPClient:
    """公众号服务端 API 客户端（access_token 进程级缓存）。

    ``transport`` 供测试注入 httpx.MockTransport。
    """

    def __init__(self, app_id: str, app_secret: str, *, transport: Any = None) -> None:
        self.app_id = (app_id or "").strip()
        self.app_secret = (app_secret or "").strip()
        self._transport = transport
        self._token = ""
        self._token_expire_at = 0.0

    # ── 基础请求 ─────────────────────────────────────────────────

    def _require_credentials(self) -> None:
        if not self.app_id or not self.app_secret:
            raise RuntimeError(
                "微信公众号凭证未配置：请在 .env 设置 WECHAT_MP_APP_ID / WECHAT_MP_APP_SECRET"
            )

    async def _http(self, timeout: float = 30.0) -> Any:
        import httpx

        return httpx.AsyncClient(timeout=timeout, transport=self._transport, trust_env=False)

    async def access_token(self) -> str:
        """获取并缓存 access_token（有效期 7200s，提前 120s 刷新）。"""
        if self._token and time.monotonic() < self._token_expire_at:
            return self._token
        self._require_credentials()
        async with await self._http(timeout=15.0) as client:
            resp = await client.get(
                f"{API_BASE}/cgi-bin/token",
                params={
                    "grant_type": "client_credential",
                    "appid": self.app_id,
                    "secret": self.app_secret,
                },
            )
        data = resp.json()
        token = data.get("access_token")
        if not token:
            raise WeChatMPError(int(data.get("errcode") or -1), str(data.get("errmsg") or ""))
        self._token = str(token)
        self._token_expire_at = time.monotonic() + max(
            60, int(data.get("expires_in") or 7200) - _TOKEN_SAFETY_WINDOW
        )
        return self._token

    async def _post_json(self, path: str, payload: dict, *, with_token: bool = True) -> dict:
        """POST JSON 并检查 errcode（0/-1 之外按业务错误抛出）。"""
        params: dict[str, str] = {}
        if with_token:
            params["access_token"] = await self.access_token()
        async with await self._http() as client:
            resp = await client.post(f"{API_BASE}{path}", params=params, json=payload)
        data = resp.json()
        errcode = int(data.get("errcode") or 0)
        if errcode not in (0,):
            raise WeChatMPError(errcode, str(data.get("errmsg") or ""))
        return data

    async def _upload(
        self, path: str, field: str, filename: str, content: bytes, data_fields: dict | None = None
    ) -> dict:
        """multipart 上传（素材接口族），检查 errcode。"""
        params = {"access_token": await self.access_token()}
        async with await self._http(timeout=60.0) as client:
            resp = await client.post(
                f"{API_BASE}{path}",
                params=params,
                data=data_fields or {},
                files={field: (filename, content)},
            )
        data = resp.json()
        errcode = int(data.get("errcode") or 0)
        if errcode not in (0,):
            raise WeChatMPError(errcode, str(data.get("errmsg") or ""))
        return data

    # ── 素材 ─────────────────────────────────────────────────────

    async def upload_article_image(self, image_path: str | Path) -> str:
        """上传正文图片 → 返回微信站内 URL（media/uploadimg）。"""
        path = Path(image_path)
        data = await self._upload("/cgi-bin/media/uploadimg", "media", path.name, path.read_bytes())
        url = str(data.get("url") or "")
        if not url:
            raise WeChatMPError(-1, "media/uploadimg 未返回 url")
        return url

    async def upload_permanent_image(self, image_path: str | Path) -> str:
        """上传永久图片素材 → 返回 media_id（material/add_material，用作封面）。"""
        path = Path(image_path)
        data = await self._upload(
            "/cgi-bin/material/add_material", "media", path.name, path.read_bytes()
        )
        media_id = str(data.get("media_id") or "")
        if not media_id:
            raise WeChatMPError(-1, "material/add_material 未返回 media_id")
        return media_id

    # ── 草稿与发布 ────────────────────────────────────────────────

    async def add_draft(self, article: dict[str, Any]) -> str:
        """新建草稿（draft/add），返回草稿 media_id。"""
        data = await self._post_json("/cgi-bin/draft/add", {"articles": [article]})
        media_id = str(data.get("media_id") or "")
        if not media_id:
            raise WeChatMPError(-1, "draft/add 未返回 media_id")
        return media_id

    async def submit_publish(self, draft_media_id: str) -> str:
        """发布草稿（freepublish/submit），返回 publish_id。"""
        data = await self._post_json("/cgi-bin/freepublish/submit", {"media_id": draft_media_id})
        publish_id = str(data.get("publish_id") or "")
        if not publish_id:
            raise WeChatMPError(-1, "freepublish/submit 未返回 publish_id")
        return publish_id

    async def publish_status(self, publish_id: str) -> dict:
        """查询发布状态（freepublish/get）。

        publish_status：0=成功；1=发布中；2=发布中（原创审核）；3=失败；4=审核中。
        """
        return await self._post_json("/cgi-bin/freepublish/get", {"publish_id": publish_id})

    async def poll_publish(
        self, publish_id: str, *, attempts: int = 8, interval_s: float = 3.0
    ) -> dict:
        """轮询发布结果直到成功/失败/超时。

        返回 ``{"published": bool, "url": str, "publish_status": int, "pending": bool}``。
        """
        for _ in range(attempts):
            data = await self.publish_status(publish_id)
            raw_status = data.get("publish_status")
            # 注意 0（成功）是合法值，不能用 `x or -1` 兜底——会把成功误判成未知
            status = int(raw_status) if raw_status is not None else -1
            if status == 0:
                items = (data.get("article_detail") or {}).get("item") or []
                url = str(items[0].get("article_url") or "") if items else ""
                return {"published": True, "url": url, "publish_status": 0, "pending": False}
            if status in (1, 2, 4):  # 发布中 / 原创审核中 / 审核中
                await _sleep(interval_s)
                continue
            return {"published": False, "url": "", "publish_status": status, "pending": False}
        return {"published": False, "url": "", "publish_status": -1, "pending": True}


async def _sleep(seconds: float) -> None:
    import asyncio

    await asyncio.sleep(seconds)


# ---------------------------------------------------------------------------
# Markdown → 公众号 HTML（内联样式，遵循 skills/wechat-article 规范）
# ---------------------------------------------------------------------------

_H1_STYLE = (
    "font-size:22px;font-weight:bold;color:#333;"
    "border-bottom:2px solid #f0b849;padding-bottom:8px;margin:28px 0 16px;"
)
_H2_STYLE = "font-size:18px;font-weight:bold;color:#333;margin:24px 0 12px;"
_H3_STYLE = "font-size:16px;font-weight:bold;color:#f0b849;margin:20px 0 10px;"
_H4_STYLE = "font-size:15px;font-weight:bold;color:#555;margin:16px 0 8px;"
_P_STYLE = "font-size:15px;color:#333;line-height:1.8;margin:10px 0;letter-spacing:0.5px;"
_LI_STYLE = "font-size:15px;color:#333;line-height:1.8;margin:4px 0;"
_STRONG_STYLE = "color:#f0b849;"
_CODE_STYLE = "background:#f5f5f5;padding:2px 6px;border-radius:3px;font-size:14px;color:#c7254e;"
_PRE_STYLE = (
    "background:#f6f8fa;padding:12px 14px;border-radius:6px;overflow-x:auto;"
    "font-size:13px;line-height:1.6;white-space:pre-wrap;"
)
_QUOTE_STYLE = (
    "border-left:4px solid #f0b849;background:linear-gradient(to right,#fdf8e8,#ffffff);"
    "padding:14px 18px;margin:16px 0;border-radius:0 8px 8px 0;font-size:15px;color:#666;"
    "line-height:1.8;"
)
_HR_STYLE = "border:none;border-top:1px dashed #ddd;margin:24px 0;"
_LINK_STYLE = "color:#576b95;text-decoration:none;"
_IMG_STYLE = "max-width:100%;border-radius:6px;"
_TH_STYLE = "border:1px solid #e0e0e0;padding:8px 12px;background:#fdf8e8;font-size:14px;"
_TD_STYLE = "border:1px solid #e0e0e0;padding:8px 12px;font-size:14px;color:#333;"
_TABLE_STYLE = "border-collapse:collapse;width:100%;margin:14px 0;"


def _render_inline(text: str) -> str:
    """行内 Markdown → 内联样式 HTML（先整体转义再套标记）。"""
    out = html.escape(text, quote=True)

    def _sub_image(match: re.Match[str]) -> str:
        alt, src = match.group(1), match.group(2)
        return f'<img src="{src}" alt="{alt}" style="{_IMG_STYLE}"/>'

    out = re.sub(r"!\[([^\]]*)\]\(([^)\s]+)\)", _sub_image, out)
    out = re.sub(
        r"\[([^\]]+)\]\(([^)\s]+)\)",
        rf'<a href="\2" style="{_LINK_STYLE}">\1</a>',
        out,
    )
    out = re.sub(r"\*\*(.+?)\*\*", rf'<strong style="{_STRONG_STYLE}">\1</strong>', out)
    out = re.sub(r"__(.+?)__", rf'<strong style="{_STRONG_STYLE}">\1</strong>', out)
    out = re.sub(r"~~(.+?)~~", r"<s>\1</s>", out)
    out = re.sub(r"`([^`]+)`", rf'<code style="{_CODE_STYLE}">\1</code>', out)
    out = re.sub(r"(?<!\*)\*(?!\s)(.+?)(?<!\s)\*(?!\*)", r"<em>\1</em>", out)
    return out


def _heading_style(level: int) -> str:
    return {1: _H1_STYLE, 2: _H2_STYLE, 3: _H3_STYLE}.get(level, _H4_STYLE)


def markdown_to_mp_html(markdown: str) -> str:
    """Markdown → 公众号可粘贴的内联样式 HTML。

    支持标题/段落/加粗斜体/行内代码/代码块/引用/无序有序列表/表格/分割线/
    图片与链接。未识别语法按普通段落兜底——宁可样式朴素，不丢内容。
    """
    blocks: list[str] = []
    lines = markdown.replace("\r\n", "\n").split("\n")
    i = 0
    para: list[str] = []
    quote: list[str] = []
    ul: list[str] = []
    ol: list[str] = []

    def _flush_para() -> None:
        if para:
            blocks.append(f'<p style="{_P_STYLE}">{_render_inline("<br/>".join(para))}</p>')
            para.clear()

    def _flush_quote() -> None:
        if quote:
            inner = "<br/>".join(_render_inline(q) for q in quote)
            blocks.append(f'<blockquote style="{_QUOTE_STYLE}">{inner}</blockquote>')
            quote.clear()

    def _flush_lists() -> None:
        if ul:
            items = "".join(f'<li style="{_LI_STYLE}">{item}</li>' for item in ul)
            blocks.append(f'<ul style="padding-left:22px;margin:10px 0;">{items}</ul>')
            ul.clear()
        if ol:
            items = "".join(f'<li style="{_LI_STYLE}">{item}</li>' for item in ol)
            blocks.append(f'<ol style="padding-left:22px;margin:10px 0;">{items}</ol>')
            ol.clear()

    _UL_RE = re.compile(r"^\s*[-*+]\s+(.*)$")
    _OL_RE = re.compile(r"^\s*\d+[.、]\s+(.*)$")
    _H_RE = re.compile(r"^(#{1,6})\s+(.*)$")
    _HR_RE = re.compile(r"^\s*(-{3,}|\*{3,}|_{3,})\s*$")
    _TABLE_ROW_RE = re.compile(r"^\s*\|(.+)\|\s*$")

    while i < len(lines):
        line = lines[i]

        if line.strip().startswith("```"):  # 代码块
            _flush_all(para, quote, ul, ol, blocks)
            code_lines: list[str] = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code_lines.append(lines[i])
                i += 1
            i += 1  # 跳过收尾 ```
            code = html.escape("\n".join(code_lines), quote=False)
            blocks.append(f'<pre style="{_PRE_STYLE}"><code>{code}</code></pre>')
            continue

        if _HR_RE.match(line):
            _flush_all(para, quote, ul, ol, blocks)
            blocks.append(f'<hr style="{_HR_STYLE}"/>')
            i += 1
            continue

        head = _H_RE.match(line)
        if head:
            _flush_all(para, quote, ul, ol, blocks)
            level = len(head.group(1))
            blocks.append(
                f'<h{level} style="{_heading_style(level)}">'
                f"{_render_inline(head.group(2).strip())}</h{level}>"
            )
            i += 1
            continue

        if line.lstrip().startswith(">"):
            _flush_para()
            _flush_lists()
            quote.append(line.lstrip()[1:].lstrip())
            i += 1
            continue

        if _UL_RE.match(line):
            _flush_para()
            _flush_quote()
            if ol:
                _flush_lists()
            ul.append(_render_inline(_UL_RE.match(line).group(1)))  # type: ignore[union-attr]
            i += 1
            continue

        if _OL_RE.match(line):
            _flush_para()
            _flush_quote()
            if ul:
                _flush_lists()
            ol.append(_render_inline(_OL_RE.match(line).group(1)))  # type: ignore[union-attr]
            i += 1
            continue

        row = _TABLE_ROW_RE.match(line)
        if row:
            _flush_all(para, quote, ul, ol, blocks)
            rows: list[list[str]] = [[c.strip() for c in row.group(1).split("|")]]
            i += 1
            while i < len(lines):
                nxt = _TABLE_ROW_RE.match(lines[i])
                if not nxt:
                    break
                cells = [c.strip() for c in nxt.group(1).split("|")]
                if all(re.fullmatch(r":?-{2,}:?", c) for c in cells):  # 分隔行
                    i += 1
                    continue
                rows.append(cells)
                i += 1
            if rows:
                header, *body = rows
                thead = "".join(f'<th style="{_TH_STYLE}">{_render_inline(c)}</th>' for c in header)
                tbody = "".join(
                    "<tr>"
                    + "".join(f'<td style="{_TD_STYLE}">{_render_inline(c)}</td>' for c in r)
                    + "</tr>"
                    for r in body
                )
                blocks.append(
                    f'<table style="{_TABLE_STYLE}">'
                    f"<thead><tr>{thead}</tr></thead><tbody>{tbody}</tbody></table>"
                )
            continue

        if not line.strip():
            _flush_all(para, quote, ul, ol, blocks)
            i += 1
            continue

        # 普通段落行：收起其它开着的块
        _flush_quote()
        _flush_lists()
        para.append(line.strip())
        i += 1

    _flush_all(para, quote, ul, ol, blocks)
    return "\n".join(blocks)


def _flush_all(
    para: list[str], quote: list[str], ul: list[str], ol: list[str], blocks: list[str]
) -> None:
    """收掉所有行内开启的块（闭包改写三个局部列表）。"""
    if para:
        blocks.append(f'<p style="{_P_STYLE}">{_render_inline("<br/>".join(para))}</p>')
        para.clear()
    if quote:
        inner = "<br/>".join(_render_inline(q) for q in quote)
        blocks.append(f'<blockquote style="{_QUOTE_STYLE}">{inner}</blockquote>')
        quote.clear()
    if ul:
        items = "".join(f'<li style="{_LI_STYLE}">{item}</li>' for item in ul)
        blocks.append(f'<ul style="padding-left:22px;margin:10px 0;">{items}</ul>')
        ul.clear()
    if ol:
        items = "".join(f'<li style="{_LI_STYLE}">{item}</li>' for item in ol)
        blocks.append(f'<ol style="padding-left:22px;margin:10px 0;">{items}</ol>')
        ol.clear()


def md_to_plain_text(markdown: str, limit: int = _DIGEST_MAX_CHARS) -> str:
    """粗粒度去 Markdown 取纯文本（摘要兜底用）。"""
    text = re.sub(r"```.*?```", "", markdown, flags=re.DOTALL)
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"[#>*`_\-\|]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:limit]


# ---------------------------------------------------------------------------
# 公众号稿（wechat.md）结构解析
# ---------------------------------------------------------------------------
#
# 契约钉住的格式（contract 机器校验）：`### 基础信息` 小节里必须有
# `- 标题：` 行；随后是 Markdown 正文；常以 `### 封面图建议` 收尾。
# 发布时基础信息进草稿字段，封面建议不上正文。


@dataclass
class MPArticle:
    title: str = ""
    digest: str = ""
    author: str = ""
    body_md: str = ""


_META_KEYS = {"标题", "题目", "title"}
_DIGEST_KEYS = {"摘要", "digest", "简介"}
_AUTHOR_KEYS = {"作者", "author", "署名"}


def extract_wechat_article(markdown: str) -> MPArticle:
    """从 wechat.md 抽 标题/摘要/作者 与正文。

    - `### 基础信息` 小节整段剥离，键值行解析进草稿字段；
    - 以「封面图」开头的标题小节整段剥离（发布产物里是噪音）；
    - 无基础信息小节时退化为首个 `# ` 一级标题做 title（该行不再进正文）。
    """
    lines = markdown.replace("\r\n", "\n").split("\n")
    article = MPArticle()

    meta_start = None
    title_fallback = ""
    for idx, line in enumerate(lines):
        head = re.match(r"^#{1,6}\s+(.*)$", line)
        if not head:
            continue
        text = head.group(1).strip()
        if text == "基础信息":
            meta_start = idx
            break
        if text.startswith("封面图"):
            break
        if not title_fallback:
            title_fallback = text

    body_start = 0
    if meta_start is not None:
        # 基础信息小节：到下一个标题、或第一段正文（非空且不是键值列表行）为止
        meta_end = len(lines)
        for idx in range(meta_start + 1, len(lines)):
            line = lines[idx]
            if re.match(r"^#{1,6}\s+", line):
                meta_end = idx
                break
            stripped = line.strip()
            if stripped and not stripped.startswith(("-", "*", "+", ">")):
                meta_end = idx
                break
        for line in lines[meta_start + 1 : meta_end]:
            kv = re.match(r"^[-*+]\s*([^：:]+)[：:]\s*(.*)$", line.strip())
            if not kv:
                continue
            key, value = kv.group(1).strip(), kv.group(2).strip()
            if key in _META_KEYS and value:
                article.title = value
            elif key in _DIGEST_KEYS and value:
                article.digest = value
            elif key in _AUTHOR_KEYS and value:
                article.author = value
        body_start = meta_end
    elif title_fallback:
        # 无基础信息：首个标题行做 title，正文从其后开始
        for idx, line in enumerate(lines):
            if re.match(r"^#{1,6}\s+", line):
                article.title = title_fallback
                body_start = idx + 1
                break

    body_lines = lines[body_start:]
    # 剥掉「封面图…」小节
    for idx, line in enumerate(body_lines):
        head = re.match(r"^#{1,6}\s+(.*)$", line)
        if head and head.group(1).strip().startswith("封面图"):
            body_lines = body_lines[:idx]
            break
    article.body_md = "\n".join(body_lines).strip()
    return article


# ---------------------------------------------------------------------------
# 封面兜底：无素材时生成一张品牌渐变封面
# ---------------------------------------------------------------------------


def generate_cover(image_path: str | Path, date_text: str = "") -> Path:
    """生成 900×383 品牌渐变封面（纯图形 + ASCII 文字，避免中文字体依赖）。"""
    from PIL import Image, ImageDraw, ImageFont

    path = Path(image_path)
    width, height = COVER_WIDTH, COVER_HEIGHT
    img = Image.new("RGB", (width, height), "#1f2430")
    draw = ImageDraw.Draw(img)
    top = (232, 93, 61)  # NewsClaw 品牌橙 #E85D3D
    bottom = (31, 36, 48)
    for y in range(height):
        ratio = y / max(1, height - 1)
        color = tuple(int(top[c] + (bottom[c] - top[c]) * ratio) for c in range(3))
        draw.line([(0, y), (width, y)], fill=color)
    # 装饰：右上角色块 + 左下角细线
    draw.rectangle([width - 220, 0, width, 110], fill="#f0b849")
    draw.line([(60, height - 72), (320, height - 72)], fill="#f0b849", width=4)

    def _font(size: int) -> Any:
        for name in ("DejaVuSans-Bold.ttf", "DejaVuSans.ttf", "Arial Bold.ttf", "Arial.ttf"):
            try:
                return ImageFont.truetype(name, size)
            except OSError:
                continue
        try:
            return ImageFont.load_default(size)
        except TypeError:  # Pillow < 10.1 不支持 size 参数
            return ImageFont.load_default()

    draw.text((60, 92), "AI MORNING BRIEF", fill="#ffffff", font=_font(64))
    draw.text((60, 186), "NEWSCLAW NEWSROOM", fill="#f0b849", font=_font(26))
    if date_text:
        draw.text((60, height - 118), date_text[:40], fill="#e8e8e8", font=_font(24))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, format="PNG")
    return path
