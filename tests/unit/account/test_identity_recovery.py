import asyncio
import hashlib

import httpx
import pytest

from newsclaw.account.config import AccountFeatureConfig
from newsclaw.account.oidc import AccountOIDCManager, KeyringTokenStore
from newsclaw.account.status_store import AccountStatusStore
from tests.fixtures.account import MemoryTokenStore, mock_account_transport


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def provider(calls):
    def handle(request):
        calls.append(request.url.path)
        if request.url.path == "/oauth/token":
            return httpx.Response(
                200,
                json={
                    "access_token": "access",
                    "refresh_token": "rotated",
                    "expires_in": 3600,
                },
            )
        if request.url.path == "/oauth/userinfo":
            return httpx.Response(200, json={"sub": "current", "name": "Current account"})
        if request.url.path == "/oauth/desktop-handoff":
            return httpx.Response(200, json={"ticket": "t" * 64})
        if request.url.path == "/oauth/desktop-install-proof":
            return httpx.Response(200, json={"proof": "p" * 64})
        return httpx.Response(200, json={})

    return handle


@pytest.mark.asyncio
async def test_two_native_managers_serialize_shared_vault_recovery(tmp_path, monkeypatch):
    monkeypatch.setattr("pathlib.Path.home", lambda: tmp_path)
    tokens = MemoryTokenStore("legacy-refresh")
    monkeypatch.setattr(
        KeyringTokenStore, "load_refresh_token", lambda _: tokens.load_refresh_token()
    )
    monkeypatch.setattr(
        KeyringTokenStore, "save_refresh_token", lambda _, v: tokens.save_refresh_token(v)
    )
    calls = []
    handler = provider(calls)

    async def handle(request):
        await asyncio.sleep(0.02)
        return handler(request)

    mock_account_transport(monkeypatch, handle)
    managers = [
        AccountOIDCManager(store=AccountStatusStore(tmp_path / "identity")) for _ in range(2)
    ]
    snapshots = await asyncio.gather(*(m.snapshot() for m in managers))
    assert all(s["account_user_id"] == "current" for s in snapshots)
    assert calls.count("/oauth/token") == 1
    assert calls.count("/oauth/userinfo") == 1


def test_identity_scope_follows_os_user_and_provider_not_working_directory(tmp_path, monkeypatch):
    monkeypatch.setattr("pathlib.Path.home", lambda: tmp_path)
    official = {
        "NEWSCLAW_ACCOUNT_MODE": "newsclaw",
        "NEWSCLAW_ACCOUNT_BASE_URL": "https://accounts.example.com",
    }
    config = AccountFeatureConfig.from_env(official)
    expected = config.identity_data_dir()
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("NEWSCLAW_ROOT", str(tmp_path / "different-workspace"))
    same_provider = AccountFeatureConfig.from_env(official)
    assert same_provider.identity_data_dir() == expected
    other = AccountFeatureConfig.from_env(
        {"NEWSCLAW_ACCOUNT_MODE": "newsclaw", "NEWSCLAW_ACCOUNT_BASE_URL": "https://other.example"}
    )
    assert other.identity_data_dir() != expected


@pytest.mark.asyncio
async def test_logout_during_recovery_cannot_publish_identity(tmp_path, monkeypatch):
    started, resume = asyncio.Event(), asyncio.Event()
    handler = provider([])

    async def handle(request):
        if request.url.path == "/oauth/userinfo":
            started.set()
            await resume.wait()
        return handler(request)

    mock_account_transport(monkeypatch, handle)
    tokens, store = MemoryTokenStore("legacy-refresh"), AccountStatusStore(tmp_path)
    subject = AccountOIDCManager(store=store, token_store=tokens)
    recovery = asyncio.create_task(subject.snapshot())
    await started.wait()
    logout = asyncio.create_task(subject.logout())
    await asyncio.sleep(0)
    resume.set()
    await asyncio.gather(recovery, logout)
    assert tokens.value is None
    assert await store.snapshot() is None
    assert await subject.snapshot() == {"status": "signed_out"}
