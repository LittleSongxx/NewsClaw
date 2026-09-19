"""微信公众号发布工具处理器（NewsClaw 自研）。

流程：读稿（ready 投递门）→ 抽 标题/摘要/作者 → 正文图片转存微信站内 →
封面（参数 > assets/ > 自动生成）→ 新建草稿 →（可选）立即发布并轮询文章链接。

风险等级与 feishu_doc 同理：公众号是**外部服务的出站写入**，必须显式声明
NETWORK_OUT——不声明会落到 UNKNOWN → 矩阵 CONFIRM，无人值守的定时任务对
CONFIRM 默认拒绝，发布会静默失败。未 ready 的早报产物在工具层同样拒绝，
与 deliver_artifacts 共用一道投递门（newsroom.delivery）。
"""

from __future__ import annotations

import json
import logging
import re
import tempfile
from pathlib import Path
from typing import Any

from ...config import settings
from ...core.policy_v2.enums import ApprovalClass
from ...integrations.wechat_mp import (
    MPArticle,
    WeChatMPClient,
    generate_cover,
    markdown_to_mp_html,
    md_to_plain_text,
)

logger = logging.getLogger(__name__)

_TITLE_MAX_CHARS = 64
_DIGEST_MAX_CHARS = 120
_IMAGE_RE = re.compile(r"!\[([^\]]*)\]\(([^)\s]+)\)")
_ASSET_EXTS = {".png", ".jpg", ".jpeg"}


class WeChatMPPublishHandler:
    """公众号发布处理器。"""

    TOOLS = ["wechat_mp_publish"]
    TOOL_CLASSES = {"wechat_mp_publish": ApprovalClass.NETWORK_OUT}

    def __init__(self, agent: Any = None) -> None:
        self.agent = agent

    # ── 依赖装配 ────────────────────────────────────────────────

    def _make_client(self) -> WeChatMPClient:
        return WeChatMPClient(
            (settings.wechat_mp_app_id or "").strip(),
            (settings.wechat_mp_app_secret or "").strip(),
        )

    # ── 素材准备 ─────────────────────────────────────────────────

    @staticmethod
    def _read_source(params: dict) -> tuple[str, str]:
        """返回 (markdown, content_path)。content_path 优先。"""
        path_text = str(params.get("content_path") or "").strip()
        if path_text:
            path = Path(path_text).expanduser()
            if not path.is_file():
                raise RuntimeError(f"稿件不存在：{path}")
            return path.read_text(encoding="utf-8"), str(path.resolve())
        content = str(params.get("content") or "").strip()
        if content:
            return content, ""
        raise RuntimeError("content_path 与 content 至少给一个")

    @staticmethod
    def _ready_gate(content_path: str) -> None:
        """早报产物只认 ready（与 deliver_artifacts 同一道门）。"""
        if not content_path:
            return
        from ...newsroom.delivery import newsroom_delivery_block_reason

        reason = newsroom_delivery_block_reason([content_path])
        if reason:
            raise RuntimeError(reason)

    @staticmethod
    def _fallback_cover(article: MPArticle, content_path: str, params: dict) -> str:
        """封面路径解析：参数 > 稿件同目录 assets/ > 同目录 > 自动生成。"""
        explicit = str(params.get("cover_path") or "").strip()
        if explicit:
            if not Path(explicit).is_file():
                raise RuntimeError(f"cover_path 不存在：{explicit}")
            return explicit
        if content_path:
            issue_dir = Path(content_path).parent
            for candidate_dir in (issue_dir / "assets", issue_dir):
                if candidate_dir.is_dir():
                    for path in sorted(candidate_dir.iterdir()):
                        if path.suffix.lower() in _ASSET_EXTS:
                            return str(path)
            # 自动生成封面就近落盘，便于人工在期次目录里看到
            target = issue_dir / "assets" / "cover-auto.png"
            date_text = issue_dir.name if _is_issue_dir(issue_dir) else ""
            generate_cover(target, date_text)
            return str(target)
        generated = Path(tempfile.gettempdir()) / "newsclaw_mp_cover.png"
        generate_cover(generated)
        return str(generated)

    async def _inline_body_images(
        self, client: WeChatMPClient, body_md: str, warnings: list[str]
    ) -> str:
        """正文里的 本地图/外链图 转存为微信站内 URL（外链图在公众号内不显示）。"""
        if "![" not in body_md:
            return body_md

        async def _to_mp_url(src: str) -> str | None:
            try:
                local = Path(src)
                if local.is_file():
                    return await client.upload_article_image(local)
                if src.startswith(("http://", "https://")):
                    import tempfile

                    import httpx

                    async with httpx.AsyncClient(timeout=30.0, trust_env=False) as http:
                        resp = await http.get(src)
                        resp.raise_for_status()
                    suffix = Path(src.split("?")[0]).suffix or ".png"
                    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
                        tmp.write(resp.content)
                        tmp_path = Path(tmp.name)
                    try:
                        return await client.upload_article_image(tmp_path)
                    finally:
                        tmp_path.unlink(missing_ok=True)
            except Exception as exc:  # noqa: BLE001 — 单图失败不阻断发布
                warnings.append(f"正文图片转存失败（保留原链接）：{src} → {exc}")
            return None

        out = body_md
        for match in _IMAGE_RE.finditer(body_md):
            src = match.group(2)
            mp_url = await _to_mp_url(src)
            if mp_url:
                out = out.replace(match.group(0), f"![{match.group(1)}]({mp_url})", 1)
        return out

    # ── 入口 ────────────────────────────────────────────────────

    async def handle(self, tool_name: str, params: dict) -> str:
        warnings: list[str] = []
        try:
            markdown, content_path = self._read_source(params)
            self._ready_gate(content_path)
            article = extract_with_fallback(markdown, params, warnings)

            client = self._make_client()

            body_md = await self._inline_body_images(client, article.body_md, warnings)
            content_html = markdown_to_mp_html(body_md)

            thumb_media_id = str(params.get("thumb_media_id") or "").strip()
            if not thumb_media_id:
                cover_path = self._fallback_cover(article, content_path, params)
                thumb_media_id = await client.upload_permanent_image(cover_path)

            draft_media_id = await client.add_draft(
                {
                    "title": article.title[:_TITLE_MAX_CHARS],
                    "author": article.author,
                    "digest": article.digest[:_DIGEST_MAX_CHARS],
                    "content": content_html,
                    "thumb_media_id": thumb_media_id,
                    "need_open_comment": 1,
                    "only_fans_can_comment": 0,
                }
            )

            publish_flag = params.get("publish")
            auto_publish = (
                bool(publish_flag)
                if isinstance(publish_flag, bool)
                else bool(getattr(settings, "wechat_mp_auto_publish", True))
            )

            result: dict[str, Any] = {
                "ok": True,
                "mode": "publish" if auto_publish else "draft",
                "title": article.title[:_TITLE_MAX_CHARS],
                "draft_media_id": draft_media_id,
                "thumb_media_id": thumb_media_id,
                "url": "",
            }
            if warnings:
                result["warnings"] = warnings

            if auto_publish:
                try:
                    publish_id = await client.submit_publish(draft_media_id)
                except RuntimeError as exc:
                    # 发布接口被拒（常见：未认证订阅号无 freepublish 权限）时
                    # 降级为仅草稿——草稿已建成，如实带出原因，不整体报错，
                    # 交给人工在公众号后台点发布。
                    result["mode"] = "draft"
                    result["publish_error"] = str(exc)
                    result["note"] = (
                        "草稿已创建，但 API 发布未成功（多为账号无发布接口权限，"
                        "如未认证订阅号）。请到公众号后台「草稿箱」人工确认发布。"
                    )
                    return json.dumps(result, ensure_ascii=False)
                result["publish_id"] = publish_id
                status = await client.poll_publish(publish_id)
                result["published"] = status["published"]
                result["url"] = status.get("url") or ""
                if status.get("pending"):
                    result["note"] = (
                        "发布已提交，平台仍在处理（publish_status=-1 轮询超时），"
                        "可稍后在公众号后台或凭 publish_id 查询结果。"
                    )
                elif not status["published"]:
                    result["ok"] = False
                    result["error"] = (
                        f"草稿已创建但发布失败（publish_status={status['publish_status']}），"
                        "请在公众号后台「草稿箱/发表记录」查看原因。"
                    )
            return json.dumps(result, ensure_ascii=False)
        except RuntimeError as exc:
            logger.warning("[wechat_mp_publish] %s", exc)
            return json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)
        except Exception as exc:  # noqa: BLE001 — 统一转结构化错误，避免裸栈
            logger.exception("[wechat_mp_publish] unexpected failure")
            return json.dumps(
                {"ok": False, "error": f"未预期错误：{type(exc).__name__}: {exc}"},
                ensure_ascii=False,
            )


def _is_issue_dir(path: Path) -> bool:
    return bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}", path.name))


def extract_with_fallback(markdown: str, params: dict, warnings: list[str]) -> MPArticle:
    """解析稿件结构，再叠加参数与配置的兜底值。"""
    from ...integrations.wechat_mp import extract_wechat_article

    article = extract_wechat_article(markdown)

    param_title = str(params.get("title") or "").strip()
    if param_title:
        article.title = param_title
    if not article.title:
        article.title = md_to_plain_text(markdown, 40) or "AI 早报"
        warnings.append("标题缺失：已从正文首行兜底，建议在「基础信息」里明确 - 标题：")

    param_digest = str(params.get("digest") or "").strip()
    if param_digest:
        article.digest = param_digest
    if not article.digest:
        article.digest = md_to_plain_text(article.body_md or markdown)

    param_author = str(params.get("author") or "").strip()
    if param_author:
        article.author = param_author
    if not article.author:
        article.author = str(getattr(settings, "wechat_mp_author", "") or "")
    return article


def create_handler(agent: Any = None):
    """返回 **bound method**（注册表经 ``handler.__self__`` 读 TOOLS/TOOL_CLASSES）。"""
    return WeChatMPPublishHandler(agent).handle
