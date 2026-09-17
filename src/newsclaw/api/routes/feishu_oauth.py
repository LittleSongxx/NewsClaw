"""飞书用户授权路由（NewsClaw 自研）。

    GET  /api/feishu/oauth/start     返回授权链接（浏览器打开并同意）
    GET  /api/feishu/oauth/callback  飞书回调：换 token 并落盘，返回结果页
    GET  /api/feishu/oauth/status    授权状态（是否已授权、剩余天数）
    POST /api/feishu/oauth/logout    清除本地令牌（需重新授权）

为什么需要用户身份：飞书知识库（Wiki）只允许用户身份新建空间与节点，
机器人 tenant token 会被拒。详见 integrations/feishu_oauth.py。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from ...integrations import feishu_oauth

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/feishu/oauth", tags=["飞书授权"])

_SUCCESS_HTML = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>授权成功 · NewsClaw</title>
<style>body{{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;display:flex;
align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f8fb}}
.card{{background:#fff;border:1px solid #e3e9f2;border-radius:12px;padding:32px 40px;
box-shadow:0 6px 24px rgba(31,66,135,.08);text-align:center;max-width:520px}}
h1{{font-size:18px;margin:0 0 10px}}p{{color:#5b6b85;font-size:13px;line-height:1.7;margin:6px 0}}
code{{background:#f1f5fb;padding:2px 6px;border-radius:4px}}</style></head><body>
<div class="card"><h1>✅ 飞书授权完成</h1>
<p>{detail}</p><p>可以关闭本页面，回到 NewsClaw 继续。</p></div></body></html>"""

_FAIL_HTML = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>授权失败 · NewsClaw</title></head><body style="font-family:system-ui;padding:40px">
<h2>❌ 飞书授权失败</h2><p>{detail}</p></body></html>"""


@router.get("/start")
async def oauth_start(raw: int = 0):
    """发起用户授权。

    浏览器直接访问即 **302 跳转**到飞书授权页（无需手动复制链接）；
    需要拿到链接文本时加 ``?raw=1``（脚本/前端用）。
    """
    if not (feishu_oauth.settings.feishu_app_id or "").strip():
        return JSONResponse(status_code=409, content={"error": "未配置 FEISHU_APP_ID"})
    url = feishu_oauth.build_authorize_url()
    if raw:
        return {
            "authorize_url": url,
            "redirect_uri": feishu_oauth.redirect_uri(),
            "scopes": list(feishu_oauth.REQUIRED_USER_SCOPES),
            "hint": "在浏览器打开 authorize_url 并同意授权；回调会自动完成配置。",
        }
    return RedirectResponse(url=url, status_code=302)


@router.get("/callback")
async def oauth_callback(code: str = "", state: str = "", error: str = ""):
    """飞书授权回调：换 token 落盘。返回 HTML 结果页（用户在浏览器里看到）。"""
    if error or not code:
        detail = f"飞书返回错误：{error or '缺少 code'}"
        logger.warning("[feishu_oauth] callback 失败: %s", detail)
        return HTMLResponse(_FAIL_HTML.format(detail=detail), status_code=400)
    try:
        status = await feishu_oauth.exchange_code(code)
    except Exception as exc:  # noqa: BLE001 — 用户可读的结果页优于裸栈
        logger.exception("[feishu_oauth] 换取 token 失败")
        return HTMLResponse(_FAIL_HTML.format(detail=str(exc)), status_code=500)
    detail = f"已授权账号：{status.get('user_name') or '（未知用户名）'}"
    return HTMLResponse(_SUCCESS_HTML.format(detail=detail))


@router.get("/status")
async def oauth_status():
    return feishu_oauth.authorization_status()


@router.post("/logout")
async def oauth_logout():
    path = feishu_oauth.token_store_path()
    try:
        path.unlink(missing_ok=True)
    except OSError as exc:
        return JSONResponse(status_code=500, content={"error": str(exc)})
    return {"status": "ok", "authorized": False}
