"""T5：CORS 默认白名单收紧的回归测试。

历史默认值是 ``allow_origin_regex=r".*"``——任意网站都能在用户浏览器里带着
凭据（cookie / Authorization）跨域访问 ``127.0.0.1:18900`` 的本地 API，等于
把 loopback 鉴权豁免交给浏览器里的任意页面。这里钉住新默认值：只放行本机 /
桌面壳 / 移动壳来源；显式 ``CORS_ORIGINS`` 仍完全覆盖默认值。
"""

from __future__ import annotations

import re

import pytest

from newsclaw.api.server import (
    CAPACITOR_CORS_ORIGINS,
    DEFAULT_CORS_ORIGIN_REGEX,
    build_cors_kwargs,
    create_app,
)

ALLOWED_DEFAULT_ORIGINS = [
    "http://localhost",
    "http://localhost:5173",
    "https://localhost:18900",
    "http://127.0.0.1",
    "http://127.0.0.1:18900",
    "https://127.0.0.1",
    "http://[::1]:18900",
    "http://tauri.localhost",
    "https://tauri.localhost:443",
    "tauri://localhost",
    "capacitor://localhost",
]

DENIED_DEFAULT_ORIGINS = [
    "https://evil.example",
    "http://evil.example:18900",
    "http://localhost.evil.example",
    "http://127.0.0.1.evil.example",
    "http://[::1].evil.example",
    "file://localhost",
    "tauri://evil.example",
    "capacitor://evil.example",
    "null",
]


@pytest.mark.parametrize("origin", ALLOWED_DEFAULT_ORIGINS)
def test_default_regex_allows_local_and_shell_origins(origin: str) -> None:
    assert re.fullmatch(DEFAULT_CORS_ORIGIN_REGEX, origin), origin


@pytest.mark.parametrize("origin", DENIED_DEFAULT_ORIGINS)
def test_default_regex_rejects_third_party_origins(origin: str) -> None:
    assert not re.fullmatch(DEFAULT_CORS_ORIGIN_REGEX, origin), origin


def test_default_kwargs_use_allowlist_regex_with_credentials() -> None:
    kwargs = build_cors_kwargs("")
    assert kwargs["allow_origin_regex"] == DEFAULT_CORS_ORIGIN_REGEX
    assert "allow_origins" not in kwargs
    assert kwargs["allow_credentials"] is True
    assert kwargs["allow_methods"] == ["*"]
    assert kwargs["allow_headers"] == ["*"]


@pytest.mark.parametrize("value", ["", "   ", " , "])
def test_blank_origins_env_falls_back_to_default_regex(value: str) -> None:
    assert build_cors_kwargs(value)["allow_origin_regex"] == DEFAULT_CORS_ORIGIN_REGEX


def test_explicit_origins_override_default_regex() -> None:
    kwargs = build_cors_kwargs("https://app.example.com, http://192.168.1.10:8080")
    assert "allow_origin_regex" not in kwargs
    assert kwargs["allow_origins"] == [
        "https://app.example.com",
        "http://192.168.1.10:8080",
        *CAPACITOR_CORS_ORIGINS,
    ]


def test_explicit_origins_do_not_duplicate_capacitor_entries() -> None:
    explicit = "https://app.example.com, capacitor://localhost"
    kwargs = build_cors_kwargs(explicit)
    assert kwargs["allow_origins"] == [
        "https://app.example.com",
        "capacitor://localhost",
        "http://localhost",
    ]


def test_create_app_mounts_default_allowlist_when_env_unset(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("CORS_ORIGINS", raising=False)
    app = create_app(agent=None)
    cors_middlewares = [
        middleware
        for middleware in app.user_middleware
        if getattr(middleware.cls, "__name__", "") == "CORSMiddleware"
    ]
    assert len(cors_middlewares) == 1
    kwargs = cors_middlewares[0].kwargs
    assert kwargs["allow_origin_regex"] == DEFAULT_CORS_ORIGIN_REGEX
    assert "allow_origins" not in kwargs


def test_cors_middleware_honours_default_allowlist() -> None:
    """默认正则真的能让浏览器跨域读响应，同时不给第三方站点 CORS 头。"""
    from fastapi import FastAPI
    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.testclient import TestClient

    app = FastAPI()
    app.add_middleware(CORSMiddleware, **build_cors_kwargs(""))

    @app.get("/api/ping")
    async def ping() -> dict[str, bool]:
        return {"ok": True}

    client = TestClient(app)

    allowed = client.get("/api/ping", headers={"Origin": "http://localhost:5173"})
    assert allowed.status_code == 200
    assert allowed.headers["access-control-allow-origin"] == "http://localhost:5173"

    denied = client.get("/api/ping", headers={"Origin": "https://evil.example"})
    assert denied.status_code == 200  # 请求本身不拦，只是不返回 CORS 头
    assert "access-control-allow-origin" not in denied.headers
