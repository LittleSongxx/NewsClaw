import asyncio
from unittest.mock import AsyncMock

import httpx
import pytest

from newsclaw.account.oidc import AccountOIDCError, AccountOIDCManager
from tests.fixtures.account import MemoryTokenStore, mock_account_transport


def manager(monkeypatch, handler, token="refresh-a"):
    mock_account_transport(monkeypatch, handler)
    store = AsyncMock()
    store.snapshot.return_value = {"status": "active", "account_user_id": "a"}
    store.session_is_active.return_value = True
    return AccountOIDCManager(store=store, token_store=MemoryTokenStore(token))


@pytest.mark.asyncio
async def test_concurrent_refresh_rotates_only_once(monkeypatch):
    calls = []

    async def handler(request):
        calls.append(request)
        await asyncio.sleep(0)
        return httpx.Response(
            200,
            json={
                "access_token": "access-b",
                "refresh_token": "refresh-b",
                "expires_in": 3600,
            },
        )

    subject = manager(monkeypatch, handler)
    result = await asyncio.gather(*[subject._valid_access_token() for _ in range(6)])
    assert result == ["access-b"] * 6
    assert len(calls) == 1
    assert subject._tokens.value == "refresh-b"


@pytest.mark.asyncio
async def test_logout_revokes_product_credential_without_browser_logout(monkeypatch):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(200)

    subject = manager(monkeypatch, handler)
    assert await subject.logout() == ""
    assert subject._tokens.value is None
    assert len(calls) == 1
    assert calls[0].url.path == "/oauth/revoke"
    assert b"token=refresh-a" in calls[0].content
    assert b"client_id=newsclaw-desktop" in calls[0].content


@pytest.mark.asyncio
async def test_failed_revocation_is_not_reported_as_success(monkeypatch):
    subject = manager(monkeypatch, lambda _: httpx.Response(503))
    with pytest.raises(AccountOIDCError):
        await subject.logout()
    assert subject._tokens.value == "refresh-a"
