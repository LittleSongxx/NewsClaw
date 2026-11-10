"""Endpoint status must fall back to process env (Docker deployments)."""

from __future__ import annotations

import json

from newsclaw.llm.endpoint_manager import EndpointManager


def _make_manager(tmp_path):
    config = tmp_path / "data" / "llm_endpoints.json"
    config.parent.mkdir(parents=True, exist_ok=True)
    config.write_text(
        json.dumps(
            {
                "endpoints": [
                    {
                        "name": "deepseek-flash",
                        "provider": "deepseek",
                        "api_key_env": "DEEPSEEK_API_KEY",
                    }
                ]
            }
        ),
        encoding="utf-8",
    )
    return EndpointManager(tmp_path, config_path=config)


def test_endpoint_status_reads_process_env_when_workspace_env_is_absent(tmp_path, monkeypatch):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    manager = _make_manager(tmp_path)
    assert manager.get_endpoint_status()[0]["key_present"] is False

    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-process-env")
    assert manager.get_endpoint_status()[0]["key_present"] is True


def test_endpoint_status_prefers_workspace_env_file_over_process_env(tmp_path, monkeypatch):
    manager = _make_manager(tmp_path)
    (tmp_path / ".env").write_text("DEEPSEEK_API_KEY=sk-workspace\n", encoding="utf-8")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-process-env")

    status = manager.get_endpoint_status()[0]
    assert status["key_present"] is True
