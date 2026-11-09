"""T5：``NEWSCLAW_REQUIRE_LOCAL_AUTH`` 开关语义。

默认行为保持不变：loopback 直连（桌面 WebView / 同机 curl）免鉴权。把变量
置为 ``1/true/yes`` 后，loopback 也必须带 Bearer token 或 X-API-Key；
``is_trusted_local``（以及依赖它的 setup gate）不被这个开关影响。
"""

from __future__ import annotations

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from newsclaw.api.auth import (
    LOCAL_AUTH_ENV_VAR,
    WebAccessConfig,
    create_auth_middleware,
    is_trusted_local,
    local_auth_required,
)

PASSWORD = "hunter22"


def _build_app(tmp_path) -> tuple[FastAPI, WebAccessConfig]:
    config = WebAccessConfig(tmp_path)
    config.change_password(PASSWORD)
    app = FastAPI()
    app.middleware("http")(create_auth_middleware(config))

    @app.get("/api/protected")
    async def protected() -> dict[str, bool]:
        return {"ok": True}

    @app.get("/api/health")
    async def health() -> dict[str, bool]:
        return {"ok": True}

    return app, config


def _local_client(app: FastAPI) -> TestClient:
    """TestClient 默认 client 是 ``testclient``，这里伪装成 loopback。"""
    return TestClient(app, client=("127.0.0.1", 54321))


def _loopback_request() -> Request:
    return Request(
        {
            "type": "http",
            "method": "GET",
            "path": "/api/protected",
            "headers": [],
            "query_string": b"",
            "client": ("127.0.0.1", 54321),
            "scheme": "http",
            "server": ("127.0.0.1", 18900),
        }
    )


def test_local_auth_required_defaults_to_false(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(LOCAL_AUTH_ENV_VAR, raising=False)
    assert local_auth_required() is False


@pytest.mark.parametrize("value", ["1", "true", "TRUE", "yes", " True "])
def test_local_auth_required_accepts_truthy_values(
    value: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(LOCAL_AUTH_ENV_VAR, value)
    assert local_auth_required() is True


@pytest.mark.parametrize("value", ["", "0", "false", "no", "off", "enabled"])
def test_local_auth_required_rejects_other_values(
    value: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(LOCAL_AUTH_ENV_VAR, value)
    assert local_auth_required() is False


def test_loopback_bypasses_auth_by_default(tmp_path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(LOCAL_AUTH_ENV_VAR, raising=False)
    app, _ = _build_app(tmp_path)
    assert _local_client(app).get("/api/protected").status_code == 200


def test_loopback_requires_token_when_opted_in(tmp_path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(LOCAL_AUTH_ENV_VAR, "1")
    app, _ = _build_app(tmp_path)
    assert _local_client(app).get("/api/protected").status_code == 401


def test_opted_in_loopback_accepts_bearer_token(tmp_path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(LOCAL_AUTH_ENV_VAR, "true")
    app, config = _build_app(tmp_path)
    token = config.create_access_token()
    response = _local_client(app).get(
        "/api/protected", headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 200


def test_opted_in_loopback_accepts_api_key(tmp_path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(LOCAL_AUTH_ENV_VAR, "yes")
    app, _ = _build_app(tmp_path)
    response = _local_client(app).get("/api/protected", headers={"X-API-Key": PASSWORD})
    assert response.status_code == 200


def test_health_stays_exempt_when_opted_in(tmp_path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(LOCAL_AUTH_ENV_VAR, "1")
    app, _ = _build_app(tmp_path)
    assert _local_client(app).get("/api/health").status_code == 200


def test_opt_in_does_not_change_is_trusted_local(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(LOCAL_AUTH_ENV_VAR, "1")
    assert is_trusted_local(_loopback_request()) is True


def test_switch_is_read_per_request(tmp_path, monkeypatch: pytest.MonkeyPatch) -> None:
    app, _ = _build_app(tmp_path)
    client = _local_client(app)
    monkeypatch.delenv(LOCAL_AUTH_ENV_VAR, raising=False)
    assert client.get("/api/protected").status_code == 200
    monkeypatch.setenv(LOCAL_AUTH_ENV_VAR, "1")
    assert client.get("/api/protected").status_code == 401
