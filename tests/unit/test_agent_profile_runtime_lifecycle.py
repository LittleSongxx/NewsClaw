from types import SimpleNamespace

import pytest
from fastapi import HTTPException


class _Pool:
    def __init__(self):
        self.invalidated: list[str] = []

    def invalidate_profile(self, profile_id: str) -> int:
        self.invalidated.append(profile_id)
        return 1


class _Store:
    def __init__(self):
        self.profile = SimpleNamespace(id="worker", is_system=False)
        self.deleted = False

    def get(self, profile_id: str):
        return self.profile if profile_id == "worker" else None

    def delete(self, profile_id: str) -> bool:
        self.deleted = profile_id == "worker"
        return self.deleted


def _empty_org_manager():
    return SimpleNamespace(
                get=lambda _org_id: None,
    )


@pytest.mark.asyncio
async def test_delete_profile_rejects_im_bot_reference(monkeypatch) -> None:
    from newsclaw.agents import profile as profile_module
    from newsclaw.api.routes.agents import delete_agent_profile
    from newsclaw.config import settings

    store = _Store()
    monkeypatch.setattr(profile_module, "get_profile_store", lambda: store)
    monkeypatch.setattr(
        settings,
        "im_bots",
        [{"id": "feishu-a", "name": "Support", "agent_profile_id": "worker"}],
    )
    request = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(org_manager=None)))

    with pytest.raises(HTTPException) as exc_info:
        await delete_agent_profile("worker", request)

    assert exc_info.value.status_code == 409
    assert exc_info.value.detail["references"][0]["id"] == "feishu-a"
    assert store.deleted is False






@pytest.mark.asyncio
async def test_delete_profile_invalidates_both_agent_pools(monkeypatch) -> None:
    from newsclaw.agents import profile as profile_module
    from newsclaw.api.routes.agents import delete_agent_profile
    from newsclaw.config import settings
    from newsclaw.prompt import builder

    store = _Store()
    monkeypatch.setattr(profile_module, "get_profile_store", lambda: store)
    monkeypatch.setattr(settings, "im_bots", [])
    monkeypatch.setattr(builder, "clear_prompt_section_cache", lambda: None)
    desktop = _Pool()
    orchestrator = _Pool()
    request = SimpleNamespace(
        app=SimpleNamespace(
            state=SimpleNamespace(
                org_manager=_empty_org_manager(),
                agent_pool=desktop,
                orchestrator=SimpleNamespace(_pool=orchestrator),
            )
        )
    )

    response = await delete_agent_profile("worker", request)

    assert response["status"] == "ok"
    assert store.deleted is True
    assert desktop.invalidated == ["worker"]
    assert orchestrator.invalidated == ["worker"]


@pytest.mark.asyncio
async def test_delete_profile_preserves_success_when_runtime_invalidation_fails(monkeypatch) -> None:
    from newsclaw.agents import profile as profile_module
    from newsclaw.api.routes.agents import delete_agent_profile
    from newsclaw.config import settings
    from newsclaw.prompt import builder

    class _FailingPool:
        def invalidate_profile(self, _profile_id: str) -> int:
            raise RuntimeError("pool unavailable")

    store = _Store()
    monkeypatch.setattr(profile_module, "get_profile_store", lambda: store)
    monkeypatch.setattr(settings, "im_bots", [])
    monkeypatch.setattr(builder, "clear_prompt_section_cache", lambda: None)
    request = SimpleNamespace(
        app=SimpleNamespace(
            state=SimpleNamespace(
                org_manager=_empty_org_manager(),
                agent_pool=_FailingPool(),
            )
        )
    )

    response = await delete_agent_profile("worker", request)

    assert response["operation_status"] == "ok"
    assert response["status"] == "partial"
    assert response["runtime"]["failed"]["agent_pool"] == "pool unavailable"
    assert store.deleted is True
