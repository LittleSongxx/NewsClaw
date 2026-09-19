"""微信公众号发布集成（integrations/wechat_mp + handler）的单元测试。

覆盖：MD→公众号内联样式 HTML 转换、wechat.md 结构解析、API 客户端
（access_token 缓存 / errcode 指引 / 草稿与发布轮询，经 httpx.MockTransport）、
handler 的 ready 投递门与草稿/发布 receipt。
"""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from newsclaw.config import settings
from newsclaw.integrations.wechat_mp import (
    MPArticle,
    WeChatMPClient,
    WeChatMPError,
    extract_wechat_article,
    generate_cover,
    markdown_to_mp_html,
    md_to_plain_text,
)
from newsclaw.tools.handlers.wechat_mp import WeChatMPPublishHandler

# ---------------------------------------------------------------------------
# Markdown → 公众号 HTML
# ---------------------------------------------------------------------------


class TestMarkdownToMpHtml:
    def test_headings_use_inline_styles(self):
        html = markdown_to_mp_html("# 大标题\n\n## 小标题")
        assert '<h1 style="' in html and "大标题" in html
        assert '<h2 style="' in html and "小标题" in html

    def test_bold_and_code_and_link(self):
        html = markdown_to_mp_html("**重点** 和 `code` 与 [文本](https://a.com)")
        assert '<strong style="' in html and "重点" in html
        assert "<code" in html and "code" in html
        assert '<a href="https://a.com"' in html and "文本" in html

    def test_blockquote_golden_box(self):
        html = markdown_to_mp_html("> 引用一行")
        assert "<blockquote" in html and "#f0b849" in html and "引用一行" in html

    def test_lists_and_hr(self):
        html = markdown_to_mp_html("- 甲\n- 乙\n\n1. 一\n2. 二\n\n---")
        assert "<ul" in html and "<li" in html and "甲" in html
        assert "<ol" in html and "一" in html
        assert "<hr" in html

    def test_code_fence_escapes_and_preserves_newlines(self):
        html = markdown_to_mp_html("```\n<b>&</b>\n```")
        assert "<pre" in html
        assert "&lt;b&gt;&amp;&lt;/b&gt;" in html

    def test_table_renders_with_inline_styles(self):
        html = markdown_to_mp_html("| A | B |\n| --- | --- |\n| 1 | 2 |")
        assert "<table" in html and "<th" in html and "<td" in html

    def test_html_is_escaped_in_paragraph(self):
        html = markdown_to_mp_html("比较 a<b 与 c>d")
        assert "a&lt;b" in html

    def test_plain_text_digest_helper(self):
        text = md_to_plain_text("# 标题\n\n**加粗** 正文 [链接](https://a.com)")
        assert "#" not in text and "**" not in text and "](https" not in text
        assert "加粗" in text


# ---------------------------------------------------------------------------
# wechat.md 结构解析
# ---------------------------------------------------------------------------


_SAMPLE_ARTICLE = """\
### 基础信息

- 标题：AI 早报 #12｜OpenAI 发布新模型
- 摘要：三分钟看完今天的大事
- 作者：NewsClaw

## 今日头条

OpenAI 发布了 **新模型**，详见 [官网](https://openai.com)。

### 封面图建议

- 橙色渐变 + 机器人元素
"""


class TestExtractWechatArticle:
    def test_parses_meta_and_strips_meta_and_cover_sections(self):
        article = extract_wechat_article(_SAMPLE_ARTICLE)
        assert article.title == "AI 早报 #12｜OpenAI 发布新模型"
        assert article.digest == "三分钟看完今天的大事"
        assert article.author == "NewsClaw"
        assert "基础信息" not in article.body_md
        assert "封面图建议" not in article.body_md
        assert "今日头条" in article.body_md
        assert "新模型" in article.body_md

    def test_fallback_to_first_heading_title(self):
        article = extract_wechat_article("# 兜底标题\n\n正文内容")
        assert article.title == "兜底标题"
        assert "兜底标题" not in article.body_md
        assert "正文内容" in article.body_md

    def test_plain_markdown_passes_through(self):
        article = extract_wechat_article("没有标题的普通文本")
        assert article.body_md == "没有标题的普通文本"


# ---------------------------------------------------------------------------
# API 客户端（MockTransport）
# ---------------------------------------------------------------------------


def _client_with(handler) -> tuple[WeChatMPClient, list[httpx.Request]]:
    requests: list[httpx.Request] = []

    def _wrapped(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return handler(request)

    return WeChatMPClient(
        "appid-test", "secret-test", transport=httpx.MockTransport(_wrapped)
    ), requests


class TestWeChatMPClient:
    @pytest.mark.asyncio
    async def test_token_cached_across_calls(self):
        token_calls = 0

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal token_calls
            if "/token" in str(request.url):
                token_calls += 1
                return httpx.Response(200, json={"access_token": "T0", "expires_in": 7200})
            return httpx.Response(200, json={"errcode": 0, "media_id": "M1"})

        client, requests = _client_with(handler)
        assert await client.access_token() == "T0"
        await client.add_draft({"title": "t", "content": "c", "thumb_media_id": "x"})
        assert await client.access_token() == "T0"
        assert token_calls == 1
        # 业务请求把 token 拼在 query 上
        assert "access_token=T0" in str(requests[-1].url)

    @pytest.mark.asyncio
    async def test_ip_whitelist_error_includes_ip_and_guidance(self):
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                json={
                    "errcode": 40164,
                    "errmsg": "invalid ip 203.0.113.7 ipv6 ::ffff:203.0.113.7, not in whitelist",
                },
            )

        client, _ = _client_with(handler)
        with pytest.raises(WeChatMPError) as exc_info:
            await client.access_token()
        assert "203.0.113.7" in str(exc_info.value)
        assert "IP 白名单" in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_unauthorized_account_error_mentions_verification(self):
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json={"errcode": 48001, "errmsg": "api unauthorized"})

        client, _ = _client_with(handler)

        async def fake_token() -> str:
            return "T"

        client.access_token = fake_token  # type: ignore[method-assign]
        with pytest.raises(WeChatMPError) as exc_info:
            await client.add_draft({"title": "t"})
        assert "48001" in str(exc_info.value)
        assert "已认证" in str(exc_info.value)

    @pytest.mark.asyncio
    async def test_upload_article_image_returns_url(self, tmp_path):
        def handler(request: httpx.Request) -> httpx.Response:
            assert "media/uploadimg" in str(request.url)
            assert "multipart/form-data" in request.headers["content-type"]
            return httpx.Response(200, json={"url": "https://mmbiz.qpic.cn/1.png"})

        client, _ = _client_with(handler)
        client.access_token = lambda: _async("T")  # type: ignore[method-assign]
        url = await client.upload_article_image(_tmp_image(tmp_path))
        assert url == "https://mmbiz.qpic.cn/1.png"

    @pytest.mark.asyncio
    async def test_publish_flow_polls_until_success(self):
        state = {"polls": 0}

        def handler(request: httpx.Request) -> httpx.Response:
            path = request.url.path
            if path == "/cgi-bin/token":
                return httpx.Response(200, json={"access_token": "T", "expires_in": 7200})
            body = json.loads(request.content.decode())
            if path == "/cgi-bin/draft/add":
                assert body["articles"][0]["thumb_media_id"] == "thumb-1"
                return httpx.Response(200, json={"errcode": 0, "media_id": "draft-9"})
            if path == "/cgi-bin/freepublish/submit":
                return httpx.Response(200, json={"errcode": 0, "publish_id": "pub-1"})
            if path == "/cgi-bin/freepublish/get":
                state["polls"] += 1
                if state["polls"] < 2:  # 第一次仍在发布中
                    return httpx.Response(200, json={"errcode": 0, "publish_status": 1})
                return httpx.Response(
                    200,
                    json={
                        "errcode": 0,
                        "publish_status": 0,
                        "article_detail": {
                            "item": [{"idx": 1, "article_url": "https://mp.weixin.qq.com/s/xyz"}]
                        },
                    },
                )
            raise AssertionError(f"unexpected url {request.url}")

        client, _ = _client_with(handler)
        draft_id = await client.add_draft({"title": "t", "thumb_media_id": "thumb-1"})
        assert draft_id == "draft-9"
        publish_id = await client.submit_publish(draft_id)
        assert publish_id == "pub-1"
        status = await client.poll_publish(publish_id, attempts=3, interval_s=0.01)
        assert status["published"] is True
        assert status["url"] == "https://mp.weixin.qq.com/s/xyz"
        assert state["polls"] == 2


async def _async(value: str) -> str:
    return value


def _tmp_image(tmp_path: Path) -> Path:
    from PIL import Image

    path = Path(tmp_path) / "img.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (8, 8), "#f0b849").save(path)
    return path


# ---------------------------------------------------------------------------
# 封面生成
# ---------------------------------------------------------------------------


class TestGenerateCover:
    def test_generates_png_at_official_ratio(self, tmp_path):
        out = generate_cover(tmp_path / "cover.png", "2026-09-19")
        assert out.is_file() and out.stat().st_size > 0
        from PIL import Image

        with Image.open(out) as img:
            assert img.size == (900, 383)


# ---------------------------------------------------------------------------
# Handler：ready 门 + receipt
# ---------------------------------------------------------------------------


class FakeMPClient:
    """替身客户端：记录调用，返回固定 receipt。"""

    def __init__(self):
        self.article = None
        self.cover_path = ""
        self.inline_uploads: list[str] = []

    async def upload_article_image(self, path) -> str:
        self.inline_uploads.append(str(path))
        return "https://mmbiz.qpic.cn/inline.png"

    async def upload_permanent_image(self, path) -> str:
        self.cover_path = str(path)
        return "thumb-1"

    async def add_draft(self, article: dict) -> str:
        self.article = article
        return "draft-1"

    async def submit_publish(self, draft_media_id: str) -> str:
        return "publish-1"

    async def poll_publish(self, publish_id: str, **kwargs) -> dict:
        return {
            "published": True,
            "url": "https://mp.weixin.qq.com/s/abc",
            "publish_status": 0,
            "pending": False,
        }


@pytest.fixture
def handler(monkeypatch) -> tuple[WeChatMPPublishHandler, FakeMPClient]:
    fake = FakeMPClient()
    h = WeChatMPPublishHandler()
    monkeypatch.setattr(h, "_make_client", lambda: fake)
    monkeypatch.setattr(settings, "wechat_mp_auto_publish", True)
    return h, fake


class TestWeChatMPPublishHandler:
    @pytest.mark.asyncio
    async def test_draft_only_receipt(self, handler, tmp_path):
        h, fake = handler
        article = tmp_path / "wechat.md"
        article.write_text(_SAMPLE_ARTICLE, encoding="utf-8")
        receipt = json.loads(
            await h.handle("wechat_mp_publish", {"content_path": str(article), "publish": False})
        )
        assert receipt["ok"] is True
        assert receipt["mode"] == "draft"
        assert receipt["draft_media_id"] == "draft-1"
        assert receipt["url"] == ""
        assert "publish_id" not in receipt
        assert fake.article["title"] == "AI 早报 #12｜OpenAI 发布新模型"
        assert fake.article["digest"] == "三分钟看完今天的大事"

    @pytest.mark.asyncio
    async def test_auto_publish_receipt_with_url(self, handler, tmp_path):
        h, _fake = handler
        article = tmp_path / "wechat.md"
        article.write_text(_SAMPLE_ARTICLE, encoding="utf-8")
        receipt = json.loads(await h.handle("wechat_mp_publish", {"content_path": str(article)}))
        assert receipt["ok"] is True
        assert receipt["mode"] == "publish"
        assert receipt["published"] is True
        assert receipt["url"] == "https://mp.weixin.qq.com/s/abc"

    @pytest.mark.asyncio
    async def test_publish_permission_error_degrades_to_draft(self, handler, tmp_path):
        """发布接口无权限（如未认证订阅号 48001）时降级为草稿，草稿不丢。"""
        h, fake = handler
        article = tmp_path / "wechat.md"
        article.write_text(_SAMPLE_ARTICLE, encoding="utf-8")

        async def denied(media_id: str) -> str:
            raise WeChatMPError(48001, "api unauthorized")

        fake.submit_publish = denied  # type: ignore[method-assign]
        receipt = json.loads(await h.handle("wechat_mp_publish", {"content_path": str(article)}))
        assert receipt["ok"] is True
        assert receipt["mode"] == "draft"
        assert receipt["draft_media_id"] == "draft-1"
        assert "48001" in receipt["publish_error"]
        assert "草稿箱" in receipt["note"]

    @pytest.mark.asyncio
    async def test_inline_image_rewritten_to_mp_url(self, handler, tmp_path):
        h, fake = handler
        image = _tmp_image(tmp_path)
        md = f"### 基础信息\n\n- 标题：图测\n\n看图 ![配图]({image})\n"
        article = tmp_path / "wechat.md"
        article.write_text(md, encoding="utf-8")
        receipt = json.loads(
            await h.handle("wechat_mp_publish", {"content_path": str(article), "publish": False})
        )
        assert receipt["ok"] is True
        assert fake.inline_uploads == [str(image)]
        assert "https://mmbiz.qpic.cn/inline.png" in fake.article["content"]
        assert str(image) not in fake.article["content"]

    @pytest.mark.asyncio
    async def test_cover_falls_back_to_generated_in_issue_assets(self, handler, tmp_path):
        h, fake = handler
        article = tmp_path / "2026-09-19"
        article.mkdir()
        (article / "wechat.md").write_text(_SAMPLE_ARTICLE, encoding="utf-8")
        receipt = json.loads(
            await h.handle(
                "wechat_mp_publish", {"content_path": str(article / "wechat.md"), "publish": False}
            )
        )
        assert receipt["ok"] is True
        assert Path(fake.cover_path).name == "cover-auto.png"
        assert Path(fake.cover_path).parent.name == "assets"

    @pytest.mark.asyncio
    async def test_ready_gate_blocks_unready_issue(self, handler, monkeypatch, tmp_path):
        h, _fake = handler
        monkeypatch.setattr(settings, "project_root", tmp_path)
        issue_dir = tmp_path / "data" / "newsroom" / "issues" / "2026-09-19"
        issue_dir.mkdir(parents=True)
        (issue_dir / "wechat.md").write_text(_SAMPLE_ARTICLE, encoding="utf-8")
        (issue_dir / "manifest.json").write_text(
            json.dumps({"issue_date": "2026-09-19", "status": "partial"}), encoding="utf-8"
        )
        receipt = json.loads(
            await h.handle(
                "wechat_mp_publish",
                {"content_path": str(issue_dir / "wechat.md"), "publish": False},
            )
        )
        assert receipt["ok"] is False
        assert "ready" in receipt["error"]

    @pytest.mark.asyncio
    async def test_missing_content_param(self, handler):
        h, _fake = handler
        receipt = json.loads(await h.handle("wechat_mp_publish", {}))
        assert receipt["ok"] is False
        assert "content_path" in receipt["error"]

    @pytest.mark.asyncio
    async def test_title_fallback_warning(self, handler, tmp_path):
        h, _fake = handler
        article = tmp_path / "wechat.md"
        article.write_text("只有一段正文，没有基础信息。", encoding="utf-8")
        receipt = json.loads(
            await h.handle("wechat_mp_publish", {"content_path": str(article), "publish": False})
        )
        assert receipt["ok"] is True
        assert any("标题缺失" in w for w in receipt.get("warnings", []))


# ---------------------------------------------------------------------------
# MPArticle 数据类兜底
# ---------------------------------------------------------------------------


def test_mp_article_defaults():
    article = MPArticle()
    assert article.title == "" and article.body_md == ""
