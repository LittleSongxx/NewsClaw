"""飞书用户身份授权（OAuth）——为 Wiki 归档提供 user_access_token。

为什么需要它：飞书的知识库（Wiki）要求**用户身份**才能新建空间与节点，
机器人（tenant token）会被拒（99991663）。用户完成一次浏览器授权后，本模块
持有 refresh_token（默认 7 天有效，可自动续期），之后归档全程无人工。

令牌存储：``data/feishu_oauth.json``（0600 权限，data/ 已在 .gitignore 内）。
不依赖 keyring——headless 服务器上没有可用的 keyring 后端，之前的账户模块
已因此踩过坑。

对外只暴露三个能力：
    build_authorize_url(...)   生成授权链接（给用户点）
    exchange_code(...)         用回调 code 换 token 并落盘
    user_access_token(...)     取有效 access_token（过期自动 refresh）
"""

from __future__ import annotations

import json
import logging
import os
import time
from pathlib import Path
from urllib.parse import urlencode

from ..config import settings

logger = logging.getLogger(__name__)

AUTHORIZE_URL = "https://accounts.feishu.cn/open-apis/authen/v1/authorize"
TOKEN_URL = "https://open.feishu.cn/open-apis/authen/v2/oauth/token"
USER_INFO_URL = "https://open.feishu.cn/open-apis/authen/v1/user_info"

#: 归档所需的最小用户权限：知识库 + 文档读写。
#: 刻意**不**默认请求 offline_access——把机器人加为知识空间管理员后，长期归档
#: 走应用身份（tenant token），不需要 refresh token；而部分租户后台没有
#: offline_access 可勾，带着它请求会话会被飞书直接拒绝。
#: 需要长期用户令牌的场景（如无成员权限时退回用户身份），
#: 可在 .env 设 FEISHU_OAUTH_EXTRA_SCOPES=offline_access 追加。
REQUIRED_USER_SCOPES = ("wiki:wiki", "docx:document")

_ACCESS_SAFETY_WINDOW = 120  # 提前 2 分钟视为过期


def token_store_path() -> Path:
    try:
        root = Path(settings.data_dir)
    except Exception:
        root = Path.cwd() / "data"
    return root / "feishu_oauth.json"


def _write_private(path: Path, payload: dict) -> None:
    """落盘并收紧权限（含 token，绝不可被同机其他用户读到）。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)
    try:
        path.chmod(0o600)
    except OSError:  # Windows 等平台无 POSIX 权限语义，忽略
        pass


def load_tokens() -> dict:
    path = token_store_path()
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8")) or {}
    except (json.JSONDecodeError, OSError) as exc:
        logger.warning("[feishu_oauth] 令牌文件损坏，需重新授权: %s", exc)
        return {}


def is_authorized() -> bool:
    """是否有可用的用户令牌。

    注意：**不要求 refresh_token**——新方案（机器人被加为知识空间管理员后走
    应用身份）下，一次性的 2 小时 access_token 就足够完成配置；租户若勾不到
    offline_access，飞书本就不会下发 refresh_token。
    """
    data = load_tokens()
    if data.get("refresh_token"):
        return True
    return bool(data.get("access_token")) and float(data.get("access_expires_at", 0)) > time.time()


def authorization_status() -> dict:
    data = load_tokens()
    if not is_authorized():
        return {"authorized": False}
    now = time.time()
    has_refresh = bool(data.get("refresh_token"))
    return {
        "authorized": True,
        "user_name": data.get("user_name", ""),
        "open_id": data.get("open_id", ""),
        "scope": data.get("scope", ""),
        "access_minutes_left": max(0, round((float(data.get("access_expires_at", 0)) - now) / 60)),
        "has_refresh_token": has_refresh,
        "refresh_days_left": (
            max(0, round((float(data.get("refresh_expires_at", 0)) - now) / 86400, 1))
            if has_refresh
            else None
        ),
    }


def redirect_uri() -> str:
    return (
        getattr(settings, "feishu_oauth_redirect_uri", "")
        or "http://127.0.0.1:18900/api/feishu/oauth/callback"
    )


def build_authorize_url(state: str = "newsclaw") -> str:
    """生成用户授权链接（在飞书里打开并同意即可）。"""
    scopes = list(REQUIRED_USER_SCOPES)
    extra = (getattr(settings, "feishu_oauth_extra_scopes", "") or "").strip()
    scopes += [s for s in extra.replace(",", " ").split() if s and s not in scopes]
    params = {
        "client_id": (settings.feishu_app_id or "").strip(),
        "redirect_uri": redirect_uri(),
        "response_type": "code",
        "scope": " ".join(scopes),
        "state": state,
    }
    return f"{AUTHORIZE_URL}?{urlencode(params)}"


async def _post_token(payload: dict) -> dict:
    import httpx

    async with httpx.AsyncClient(timeout=30.0) as client:
        resp = await client.post(
            TOKEN_URL,
            json=payload,
            headers={"Content-Type": "application/json; charset=utf-8"},
        )
    data = resp.json()
    if data.get("code") not in (0, None):
        raise RuntimeError(f"飞书 OAuth 失败（code={data.get('code')}）：{data.get('msg')}")
    return data


async def _fetch_user_name(access_token: str) -> dict:
    import httpx

    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            resp = await client.get(
                USER_INFO_URL, headers={"Authorization": f"Bearer {access_token}"}
            )
        data = resp.json()
        if data.get("code") == 0:
            return {
                "user_name": (data.get("data") or {}).get("name", ""),
                "open_id": (data.get("data") or {}).get("open_id", ""),
            }
    except Exception as exc:  # 用户名只用于展示，失败不影响授权
        logger.debug("[feishu_oauth] 获取用户信息失败: %s", exc)
    return {}


async def exchange_code(code: str) -> dict:
    """用回调 code 换 token，落盘并返回状态摘要。"""
    app_id = (settings.feishu_app_id or "").strip()
    app_secret = (settings.feishu_app_secret or "").strip()
    data = await _post_token(
        {
            "grant_type": "authorization_code",
            "client_id": app_id,
            "client_secret": app_secret,
            "code": code,
            "redirect_uri": redirect_uri(),
        }
    )
    payload = {
        "access_token": data.get("access_token", ""),
        "access_expires_at": time.time() + float(data.get("expires_in") or 7200),
        "scope": data.get("scope", ""),
        "authorized_at": time.time(),
    }
    if data.get("refresh_token"):
        # 仅当租户勾了 offline_access 才会下发；没有就别写假的过期时间
        payload["refresh_token"] = data["refresh_token"]
        payload["refresh_expires_at"] = time.time() + float(
            data.get("refresh_token_expires_in") or 604800
        )
    payload.update(await _fetch_user_name(str(payload["access_token"])))
    _write_private(token_store_path(), payload)
    logger.info(
        "[feishu_oauth] 授权完成：user=%s open_id=%s",
        payload.get("user_name"),
        payload.get("open_id"),
    )
    return authorization_status()


async def user_access_token() -> str:
    """取有效用户 access_token；过期则用 refresh_token 续期（并轮换落盘）。"""
    data = load_tokens()
    if (
        data.get("access_token")
        and float(data.get("access_expires_at", 0)) - time.time() > _ACCESS_SAFETY_WINDOW
    ):
        return str(data["access_token"])

    if not data.get("refresh_token"):
        raise RuntimeError(
            "用户令牌已过期（本次授权未包含 offline_access，无法自动续期）。"
            "请重新访问 /api/feishu/oauth/start 授权一次；"
            "若机器人已是知识空间管理员，日常归档走应用身份、无需用户令牌。"
        )
    if float(data.get("refresh_expires_at", 0)) <= time.time():
        raise RuntimeError(
            "飞书授权已过期（refresh_token 失效），请重新访问 /api/feishu/oauth/start"
        )

    app_id = (settings.feishu_app_id or "").strip()
    app_secret = (settings.feishu_app_secret or "").strip()
    refreshed = await _post_token(
        {
            "grant_type": "refresh_token",
            "client_id": app_id,
            "client_secret": app_secret,
            "refresh_token": data["refresh_token"],
        }
    )
    data.update(
        {
            "access_token": refreshed.get("access_token", ""),
            "access_expires_at": time.time() + float(refreshed.get("expires_in") or 7200),
            "refresh_token": refreshed.get("refresh_token", data["refresh_token"]),
            "refresh_expires_at": time.time()
            + float(refreshed.get("refresh_token_expires_in") or 604800),
        }
    )
    _write_private(token_store_path(), data)
    logger.info("[feishu_oauth] access_token 已自动续期")
    return str(data["access_token"])
