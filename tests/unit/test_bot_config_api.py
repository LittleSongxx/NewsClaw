import asyncio
import sys
from types import ModuleType, SimpleNamespace

import pytest
from fastapi import HTTPException

from newsclaw.api.routes.agents import (
    BotCreateRequest,
    BotToggleRequest,
    BotUpdateRequest,
    _bot_apply_tasks,
    _runtime_bot_view,
    _validate_bot_credentials,
    create_bot,
    toggle_bot,
    update_bot,
)


@pytest.fixture(autouse=True)
def _default_profile_store(monkeypatch):
    """Keep bot lifecycle tests independent from profiles persisted on the host."""
    from newsclaw.agents import profile as profile_module

    default_profile = SimpleNamespace(id="default")
    store = SimpleNamespace(
        get=lambda profile_id: default_profile if profile_id == "default" else None
    )
    monkeypatch.setattr(profile_module, "get_profile_store", lambda: store)



def test_runtime_status_reports_missing_credentials() -> None:
    from newsclaw.channels.status import collect_effective_im_status

    settings = SimpleNamespace(
        feishu_enabled=False,
        qqbot_enabled=False,
        wechat_enabled=False,
        im_bots=[
            {
                "id": "warehouse",
                "type": "qqbot",
                "enabled": True,
                "credentials": {},
            }
        ],
    )

    status = collect_effective_im_status(settings)
    detail = next(item for item in status["details"] if item["source"] == "im_bots")

    assert detail["configured"] is False
    assert detail["missing"] == ["app_id", "app_secret"]
    assert detail["runtime_status"] == "unknown"


def test_runtime_bot_view_exposes_dependency_install_state(monkeypatch) -> None:
    monkeypatch.setattr(
        "newsclaw.channels.runtime_status.resolve_bot_runtime_state",
        lambda _channel, _gateway=None: {
            "status": "installing_dependencies",
            "error": None,
            "progress": {"phase": "downloading", "percent": 64.0},
        },
    )

    view = _runtime_bot_view(
        {
            "id": "feishu-main",
            "type": "feishu",
            "enabled": True,
            "credentials": {"app_id": "cli_xxx", "app_secret": "secret"},
        },
        {
            "configured": True,
            "missing": [],
            "runtime_seen": False,
            "runtime_status": "unknown",
        },
    )

    assert view["runtime_status"] == "installing_dependencies"
    assert view["runtime_seen"] is True
    assert view["runtime_error"] is None
    assert view["runtime_progress"] == {"phase": "downloading", "percent": 64.0}
