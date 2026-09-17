"""Keep all installation probes inside the test's temporary workspace."""

import pytest

from newsclaw.config import settings
from newsclaw.skills import allowlist_io


@pytest.fixture(autouse=True)
def isolated_marketplace_workspace(tmp_path, monkeypatch):
    monkeypatch.setenv("NEWSCLAW_ROOT", str(tmp_path / "home"))
    monkeypatch.setenv("NEWSCLAW_MARKETPLACE_URL", "https://marketplace.newsclaw.cn")
    monkeypatch.setenv("NEWSCLAW_MARKETPLACE_ALLOWED_HOSTS", "marketplace.newsclaw.cn")
    monkeypatch.setattr(settings, "project_root", tmp_path / "project")
    monkeypatch.setattr(allowlist_io, "_skills_json_path", lambda: tmp_path / "skills.json")
