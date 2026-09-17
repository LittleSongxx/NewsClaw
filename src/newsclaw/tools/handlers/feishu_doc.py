"""Feishu 云文档工具处理器（NewsClaw 自研）。

用 .env 的机器人凭证（FEISHU_APP_ID / FEISHU_APP_SECRET）直接调飞书开放平台：
tenant_access_token → 新建 docx → markdown 转 docx 块 → 批量插入 →（可选）挂 Wiki 节点。

为什么不用 lark-cli：无人值守的定时管线无法完成交互式授权；机器人凭证是
已在飞的通道凭证，天然适合服务端归档。lark-cli 技能保留给交互式重操作。

失败语义：返回结构化中文错误（含缺失权限时的授权链接），让上层 Agent 如实
报告而不是静默重试。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

from ...config import settings
from ...core.policy_v2.enums import ApprovalClass

logger = logging.getLogger(__name__)

_API_BASE = "https://open.feishu.cn/open-apis"
_DOC_BATCH_LIMIT = 50  # 飞书 children 批量插入的单次上限
_TOKEN_SAFETY_WINDOW = 60  # token 提前 60s 视为过期


class _TokenCache:
    """进程级 tenant_access_token 缓存（有效期约 2 小时）。"""

    def __init__(self) -> None:
        self._token = ""
        self._expire_at = 0.0

    def get(self) -> str:
        if self._token and time.monotonic() < self._expire_at:
            return self._token
        return ""

    def put(self, token: str, expire_seconds: int) -> None:
        self._token = token
        self._expire_at = time.monotonic() + max(60, expire_seconds - _TOKEN_SAFETY_WINDOW)


class FeishuDocHandler:
    """云文档归档处理器。"""

    TOOLS = ["feishu_doc"]
    # 显式风险等级（C7 约定）：飞书云文档是**外部服务的出站写入**，
    # 语义等同 news_ 一类网络工具。不声明会落到 UNKNOWN → 矩阵一律 CONFIRM，
    # 而无人值守的定时任务对 CONFIRM 默认拒绝——归档会静默失败。
    TOOL_CLASSES = {"feishu_doc": ApprovalClass.NETWORK_OUT}

    def __init__(self, agent: Any = None) -> None:
        self.agent = agent
        self._tokens = _TokenCache()

    # ── HTTP 基础 ────────────────────────────────────────────────

    @staticmethod
    def _credentials() -> tuple[str, str]:
        return (
            (settings.feishu_app_id or "").strip(),
            (settings.feishu_app_secret or "").strip(),
        )

    async def _tenant_token(self) -> str:
        cached = self._tokens.get()
        if cached:
            return cached
        app_id, app_secret = self._credentials()
        if not app_id or not app_secret:
            raise RuntimeError(
                "飞书应用凭证未配置：请在 .env 设置 FEISHU_APP_ID / FEISHU_APP_SECRET"
            )

        import httpx

        async with httpx.AsyncClient(timeout=20.0) as client:
            resp = await client.post(
                f"{_API_BASE}/auth/v3/tenant_access_token/internal",
                json={"app_id": app_id, "app_secret": app_secret},
            )
        data = resp.json()
        if data.get("code") != 0:
            raise RuntimeError(f"获取 tenant_access_token 失败: {data.get('msg')}")
        self._tokens.put(
            str(data.get("tenant_access_token") or ""), int(data.get("expire") or 7200)
        )
        return self._tokens.get()

    async def _api(
        self, method: str, path: str, payload: dict | None = None, *, token: str = ""
    ) -> dict:
        """调一次开放平台 API，非 0 code 抛带指引的异常。

        ``token`` 显式传入时使用该令牌（如用户身份的 user_access_token）；
        未传则用应用身份 tenant_access_token。
        """
        import httpx

        token = token or await self._tenant_token()
        async with httpx.AsyncClient(timeout=45.0) as client:
            resp = await client.request(
                method,
                f"{_API_BASE}{path}",
                json=payload,
                headers={
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json; charset=utf-8",
                },
            )
        data = resp.json()
        if data.get("code") != 0:
            raise RuntimeError(self._format_error(data))
        return data.get("data") or {}

    @staticmethod
    def _format_error(data: dict) -> str:
        code = data.get("code")
        msg = data.get("msg")
        if code == 99991672:  # 权限不足：飞书会在 msg 里附授权链接
            return f"飞书接口权限不足（code={code}）：{msg}"
        return f"飞书接口调用失败（code={code}）：{msg}"

    # ── 文档操作 ────────────────────────────────────────────────

    async def _create_document(self, title: str) -> str:
        data = await self._api("POST", "/docx/v1/documents", {"title": title or "未命名文档"})
        return str((data.get("document") or {}).get("document_id") or "")

    async def _markdown_to_blocks(self, markdown: str, *, token: str = "") -> list[dict]:
        """Markdown → 飞书文档块。

        刻意**不使用**飞书的 ``blocks/convert`` 接口：实测它返回的数组顺序与
        文档顺序不一致（同一份稿子会被打乱且无从复原），并额外要求
        ``docx:document.block:convert`` 权限。本地转换（integrations/feishu_markdown）
        顺序确定、可测试、无额外权限依赖。``token`` 参数保留仅为调用方兼容。
        """
        from ...integrations.feishu_markdown import markdown_to_blocks

        return markdown_to_blocks(markdown)

    async def _append_blocks(self, document_id: str, blocks: list[dict], *, token: str = "") -> int:
        inserted = 0
        for start in range(0, len(blocks), _DOC_BATCH_LIMIT):
            batch = blocks[start : start + _DOC_BATCH_LIMIT]
            await self._api(
                "POST",
                f"/docx/v1/documents/{document_id}/blocks/{document_id}/children",
                {"children": batch, "index": -1},
                token=token,
            )
            inserted += len(batch)
        return inserted

    async def _create_wiki_node(self, space_id: str, title: str, *, token: str = "") -> dict:
        """在 Wiki 空间新建文档节点（返回 node_token / obj_token）。

        ``token`` 为空 = 应用身份（tenant token）。**推荐路径**：先用用户身份
        一次性把机器人加为该空间管理员（见 scripts/feishu_wiki_setup.py），
        之后应用身份即可长期写入——不依赖 refresh token，最适合无人值守。
        """
        data = await self._api(
            "POST",
            f"/wiki/v2/spaces/{space_id}/nodes",
            {
                "obj_type": "docx",
                "node_type": "origin",
                "title": title or "未命名文档",
            },
            token=token,
        )
        node = data.get("node") or {}
        return {
            "node_token": node.get("node_token"),
            "obj_token": node.get("obj_token"),
            "space_id": space_id,
            "identity": "user" if token else "app",
        }

    async def _create_wiki_node_with_fallback(self, space_id: str, title: str) -> dict:
        """应用身份建节点；被拒（机器人还不是空间成员）时退回用户身份。"""
        from ...integrations import feishu_oauth

        try:
            return await self._create_wiki_node(space_id, title)
        except RuntimeError as app_exc:
            if not feishu_oauth.is_authorized():
                raise RuntimeError(
                    f"{app_exc}\n提示：机器人尚不是该知识空间成员，且未完成用户授权。"
                    "请先运行 scripts/feishu_wiki_setup.py 完成一次性授权与成员授予。"
                ) from app_exc
            logger.info("[feishu_doc] 应用身份建节点失败，改用用户身份：%s", str(app_exc)[:120])
            token = await feishu_oauth.user_access_token()
            return await self._create_wiki_node(space_id, title, token=token)

    async def _attach_to_wiki(self, space_id: str, document_id: str, title: str) -> dict:
        data = await self._api(
            "POST",
            f"/wiki/v2/spaces/{space_id}/nodes",
            {
                "obj_type": "docx",
                "node_type": "origin",
                "obj_token": document_id,
                "title": title or "未命名文档",
            },
        )
        node = data.get("node") or {}
        return {"node_token": node.get("node_token"), "space_id": space_id}

    @staticmethod
    def _doc_url(document_id: str) -> str:
        return f"https://feishu.cn/docx/{document_id}"

    # ── 入口 ────────────────────────────────────────────────────

    async def handle(self, tool_name: str, params: dict) -> str:
        markdown = str(params.get("markdown") or "").strip()
        if not markdown:
            return json.dumps({"ok": False, "error": "markdown 不能为空"}, ensure_ascii=False)

        action = (str(params.get("action") or "create")).strip().lower()
        title = str(params.get("title") or "").strip()
        wiki_space_id = str(
            params.get("wiki_space_id") or getattr(settings, "feishu_wiki_space_id", "") or ""
        ).strip()

        try:
            if action == "append":
                document_id = str(params.get("document_id") or "").strip()
                if not document_id:
                    return json.dumps(
                        {"ok": False, "error": "action=append 需要 document_id"},
                        ensure_ascii=False,
                    )
                blocks = await self._markdown_to_blocks(markdown)
                inserted = await self._append_blocks(document_id, blocks)
                result = {
                    "ok": True,
                    "action": "append",
                    "document_id": document_id,
                    "url": self._doc_url(document_id),
                    "blocks_inserted": inserted,
                }
            else:
                if not title:
                    # 未给标题时用首行标题兜底，避免出现一堆"未命名文档"
                    first_line = next(
                        (
                            line.lstrip("# ").strip()
                            for line in markdown.splitlines()
                            if line.strip()
                        ),
                        "未命名文档",
                    )
                    title = first_line[:80]

                # Wiki 归档：应用身份优先（长期可用、不依赖 refresh token），
                # 机器人还不是空间成员时自动退回用户身份（一次性授权即可）。
                if wiki_space_id:
                    from ...integrations import feishu_oauth

                    node = await self._create_wiki_node_with_fallback(wiki_space_id, title)
                    document_id = str(node.get("obj_token") or "")
                    if not document_id:
                        return json.dumps(
                            {"ok": False, "error": "用户身份建 Wiki 节点失败：未返回 obj_token"},
                            ensure_ascii=False,
                        )
                    write_token = (
                        await feishu_oauth.user_access_token()
                        if node.get("identity") == "user"
                        else ""
                    )
                    # 转换接口（markdown→文档块）是纯内容变换，**始终用应用身份**：
                    # 用户身份需要额外的 docx:document.block:convert 子权限，而该
                    # 权限常不在默认授权范围内；应用身份此前已验证可用。
                    blocks = await self._markdown_to_blocks(markdown)
                    inserted = await self._append_blocks(document_id, blocks, token=write_token)
                    result = {
                        "ok": True,
                        "action": "create",
                        "identity": node.get("identity", "app"),
                        "document_id": document_id,
                        "url": self._doc_url(document_id),
                        "blocks_inserted": inserted,
                        "wiki_space_id": wiki_space_id,
                        "wiki_node_token": node.get("node_token"),
                    }
                else:
                    document_id = await self._create_document(title)
                    if not document_id:
                        return json.dumps(
                            {"ok": False, "error": "创建文档失败：未返回 document_id"},
                            ensure_ascii=False,
                        )
                    blocks = await self._markdown_to_blocks(markdown)
                    inserted = await self._append_blocks(document_id, blocks)
                    result = {
                        "ok": True,
                        "action": "create",
                        "identity": "app",
                        "document_id": document_id,
                        "url": self._doc_url(document_id),
                        "blocks_inserted": inserted,
                    }
                    if wiki_space_id:
                        if not feishu_oauth.is_authorized():
                            result["wiki_error"] = (
                                "已创建云文档，但未完成飞书用户授权，无法写入知识库。"
                                "请访问 /api/feishu/oauth/start 完成一次授权后重试。"
                            )
                        else:
                            try:
                                result.update(
                                    await self._attach_to_wiki(wiki_space_id, document_id, title)
                                )
                            except RuntimeError as exc:
                                # 文档已创建成功，Wiki 挂载失败单独标注，便于上层如实报告
                                result["wiki_error"] = str(exc)
        except RuntimeError as exc:
            logger.warning("[feishu_doc] %s", exc)
            return json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)
        except Exception as exc:  # noqa: BLE001 — 统一转结构化错误，避免裸栈
            logger.exception("[feishu_doc] unexpected failure")
            return json.dumps(
                {"ok": False, "error": f"未预期错误：{type(exc).__name__}: {exc}"},
                ensure_ascii=False,
            )

        logger.info(
            "[feishu_doc] %s ok: document_id=%s blocks=%s",
            result.get("action"),
            result.get("document_id"),
            result.get("blocks_inserted"),
        )
        return json.dumps(result, ensure_ascii=False)


def create_handler(agent: Any = None):
    """返回 **bound method**（与 im_channel 等既有 handler 一致）。

    注册表通过 ``handler.__self__`` 读取 ``TOOLS`` / ``TOOL_CLASSES``；
    返回实例本身会让注册表读不到工具列表与风险等级（曾导致 feishu_doc
    无法识别、无人值守下被 UNKNOWN → CONFIRM 拒绝）。
    """
    return FeishuDocHandler(agent).handle
