"""Boundary between the local desktop credential vault and web/remote clients."""

import os
import secrets

from fastapi import HTTPException, Request

from newsclaw.account.native_credential import load_native_account_token
from newsclaw.api.auth import is_trusted_local

#: 当前 header 名 + rename 前的旧名。桌面外壳与 Web 前端资源各自独立发布，
#: 后端因此必须同时接受新旧两种拼写，否则前端版本落后一格就会全线 403。
DESKTOP_TOKEN_HEADERS = ("X-NewsClaw-Desktop-Token", "X-OpenNewsClaw-Desktop-Token")


def desktop_session_token(request: Request) -> str:
    """Read the desktop session token, tolerating the pre-rename header name."""
    for name in DESKTOP_TOKEN_HEADERS:
        value = request.headers.get(name, "")
        if value:
            return value
    return ""


def require_desktop_account(request: Request) -> None:
    supplied = desktop_session_token(request)
    if (
        not supplied
        or not is_trusted_local(request)
        or any(name in request.headers for name in ("forwarded", "x-forwarded-for"))
    ):
        raise HTTPException(status_code=403, detail="desktop_account_access_required")
    # Keep compatibility with already-running desktop-spawned backends. The
    # persistent native key also permits same-user standalone/reused backends.
    inherited = os.environ.get("NEWSCLAW_DESKTOP_SESSION_TOKEN", "")
    if inherited and secrets.compare_digest(inherited, supplied):
        return
    native = load_native_account_token()
    if not native or not secrets.compare_digest(native, supplied):
        raise HTTPException(status_code=403, detail="desktop_account_access_required")


