from types import SimpleNamespace

import pytest

import newsclaw.api.routes.config as config_routes
from newsclaw.api.routes.config import (
    _apply_permission_mode_defaults,
    _mode_from_security,
    _normalize_permission_mode,
    _PermissionModeBody,
    write_permission_mode,
)


def _policy_request():
    return SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace()))


def test_permission_mode_accepts_trust_alias():
    assert _normalize_permission_mode("trust") == "yolo"
    assert _normalize_permission_mode("yolo") == "yolo"
    assert _normalize_permission_mode("dont_ask") == "cautious"
    assert _normalize_permission_mode("") == "smart"
    assert _normalize_permission_mode("bogus") == "smart"


def test_yolo_mode_syncs_low_interrupt_defaults():
    """trust profile (=v1 yolo): confirmation=trust, sandbox off, but
    shell_risk / death_switch / checkpoint stay on for fail-safe."""
    sec: dict = {}

    _apply_permission_mode_defaults(sec, "trust")

    assert sec["confirmation"]["mode"] == "trust"
    assert sec["sandbox"]["enabled"] is False
    assert sec["shell_risk"]["enabled"] is True
    assert sec["death_switch"]["enabled"] is True
    assert sec["enabled"] is True
    assert sec["profile"]["current"] == "trust"
    assert _mode_from_security(sec) == "yolo"


def test_smart_mode_syncs_protection_defaults():
    """smart → protect profile: confirmation=default, all defenses on."""
    sec: dict = {}

    _apply_permission_mode_defaults(sec, "smart")

    assert sec["confirmation"]["mode"] == "default"
    assert sec["sandbox"]["enabled"] is True
    assert sec["shell_risk"]["enabled"] is True
    assert sec["death_switch"]["enabled"] is True
    assert sec["enabled"] is True
    assert sec["profile"]["current"] == "protect"


def test_cautious_mode_syncs_strict_defaults():
    """cautious → strict profile: confirmation=strict, defenses on."""
    sec: dict = {}

    _apply_permission_mode_defaults(sec, "cautious")

    assert sec["confirmation"]["mode"] == "strict"
    assert sec["sandbox"]["enabled"] is True
    assert sec["shell_risk"]["enabled"] is True
    assert sec["death_switch"]["enabled"] is True
    assert sec["enabled"] is True
    assert sec["profile"]["current"] == "strict"


def test_factory_default_security_is_protect():
    """Fresh install / empty POLICIES.yaml must report protect / smart end-to-end."""
    assert _mode_from_security(None) == "smart"
    assert _mode_from_security({}) == "smart"
    assert config_routes._normalize_security_profile("") == "protect"
    assert config_routes._normalize_security_profile("unknown") == "protect"


def test_schema_default_and_protect_bundle_agree_on_confirmation_mode():
    """schema 默认 (PolicyConfigV2()) 与出厂 protect bundle 在引擎真源上一致。"""
    from newsclaw.core.policy_v2 import PolicyConfigV2

    sec: dict = {}
    config_routes._apply_security_profile_defaults(sec, "protect")
    bundle_mode = sec["confirmation"]["mode"]

    schema_mode = PolicyConfigV2().confirmation.mode

    assert bundle_mode == schema_mode == "default", (
        f"schema 默认 confirmation.mode ({schema_mode!r}) 与 protect profile "
        f"bundle ({bundle_mode!r}) 必须一致。"
    )


@pytest.mark.asyncio
async def test_write_permission_mode_fails_when_yaml_unreadable(monkeypatch):
    monkeypatch.setattr(config_routes, "_read_policies_yaml", lambda: None)

    result = await write_permission_mode(_PermissionModeBody(mode="smart"), _policy_request())

    assert result["status"] == "error"


@pytest.mark.asyncio
async def test_write_permission_mode_fails_when_yaml_write_fails(monkeypatch):
    data = {"security": {}}
    monkeypatch.setattr(config_routes, "_read_policies_yaml", lambda: data)
    monkeypatch.setattr(config_routes, "_write_policies_yaml", lambda _data: False)

    result = await write_permission_mode(_PermissionModeBody(mode="smart"), _policy_request())

    assert result["status"] == "error"
