"""账户单测必须显式给出 URL：仓库没有捆绑官方云。"""

import pytest


@pytest.fixture(autouse=True)
def explicit_account_origin(monkeypatch):
    monkeypatch.setenv("NEWSCLAW_ACCOUNT_BASE_URL", "https://accounts.example.com")
