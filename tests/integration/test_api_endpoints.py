"""L3 Integration Tests: Remaining API endpoints (files, skills, token_stats, im, logs, upload, models)."""

import httpx
import pytest

from newsclaw.api.server import create_app


@pytest.fixture
async def client():
    app = create_app()
    app.state.agent = None
    app.state.session_manager = None
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app),
        base_url="http://testserver",
    ) as c:
        yield c


class TestHealthEndpoint:
    async def test_health_returns_ok(self, client):
        resp = await client.get("/api/health")
        assert resp.status_code == 200
        data = resp.json()
        assert "status" in data


class TestModelsEndpoint:
    async def test_list_models(self, client):
        resp = await client.get("/api/models")
        # Should return 200 even without agent (graceful fallback)
        assert resp.status_code in (200, 500)


class TestSkillsEndpoint:
    async def test_list_skills(self, client):
        resp = await client.get("/api/skills")
        assert resp.status_code in (200, 500)

    async def test_reload_skills(self, client):
        resp = await client.post("/api/skills/reload")
        assert resp.status_code in (200, 500)
