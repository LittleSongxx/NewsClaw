"""本地 Wiki 存储层单元测试。

锁定三类契约：
  1. **幂等**：同一天重复写入同一页 = 替换该日期章节，不产生重复内容
     （管线重跑时不会污染知识库）；
  2. **结构**：frontmatter / 标题 / 日期章节 / 要点 / 相关链接 / MOC 的固定形态
     （前端与 Obsidian 都依赖它稳定）；
  3. **安全**：路径穿越、保留名、超长名一律被挡在文件名之外。
"""

from __future__ import annotations

from pathlib import Path

import pytest

from newsclaw.newsroom.config import NewsroomConfig, save_config
from newsclaw.wiki import store


@pytest.fixture(autouse=True)
def isolated_wiki(tmp_path, monkeypatch):
    """把 wiki 根指到临时 vault；空 vault 不再回落到 data/wiki。"""
    from newsclaw.config import settings

    monkeypatch.setattr(settings, "project_root", tmp_path)
    vault = tmp_path / "vault"
    vault.mkdir()
    save_config(NewsroomConfig(obsidian_vault=str(vault)))
    return vault


class TestSanitize:
    def test_path_separators_and_traversal_are_neutralized(self):
        assert "/" not in store.sanitize_page_name("a/b")
        assert ".." not in store.sanitize_page_name("../../etc/passwd")
        assert "\\" not in store.sanitize_page_name("a\\b")

    def test_reserved_and_empty_names_rejected(self):
        with pytest.raises(ValueError):
            store.sanitize_page_name("MOC")
        with pytest.raises(ValueError):
            store.sanitize_page_name("   ")

    def test_long_names_truncated(self):
        assert len(store.sanitize_page_name("x" * 200)) == store._MAX_PAGE_NAME


class TestUpsert:
    def test_creates_page_with_frontmatter_and_section(self, isolated_wiki):
        result = store.upsert_daily_section(
            "大模型",
            kind="topic",
            day="2026-09-17",
            summary="语音模型进入边说边想阶段",
            entries=[
                store.WikiEntry(text="Google 发布 Gemini 3.8 Live", source="https://blog.google/x"),
                store.WikiEntry(text="阶跃星辰发布 StepAudio 3"),
            ],
            links=["OpenAI"],
        )
        assert result["created"] is True
        text = (isolated_wiki / "主题" / "大模型.md").read_text(encoding="utf-8")
        assert text.startswith("---\ntitle: 大模型\ntype: topic\n")
        assert "last_updated:" in text and "2026-09-17" in text
        assert "# 大模型" in text
        assert "## 2026-09-17" in text
        assert "> 语音模型进入边说边想阶段" in text
        assert "（[来源](https://blog.google/x)）" in text
        assert "相关：[[OpenAI]]" in text

    def test_same_day_rewrite_replaces_section(self, isolated_wiki):
        store.upsert_daily_section(
            "大模型", kind="topic", day="2026-09-17", entries=[store.WikiEntry("旧要点")]
        )
        store.upsert_daily_section(
            "大模型", kind="topic", day="2026-09-17", entries=[store.WikiEntry("新要点")]
        )
        text = (isolated_wiki / "主题" / "大模型.md").read_text(encoding="utf-8")
        assert text.count("## 2026-09-17") == 1  # 不重复
        assert "新要点" in text and "旧要点" not in text  # 内容被替换

    def test_multiple_days_accumulate_newest_last(self, isolated_wiki):
        store.upsert_daily_section("OpenAI", kind="company", day="2026-09-16", entries=[store.WikiEntry("第一天")])
        store.upsert_daily_section("OpenAI", kind="company", day="2026-09-17", entries=[store.WikiEntry("第二天")])
        text = (isolated_wiki / "公司" / "OpenAI.md").read_text(encoding="utf-8")
        assert text.index("## 2026-09-16") < text.index("## 2026-09-17")
        assert "第一天" in text and "第二天" in text


class TestListReadBacklinks:
    def _seed(self):
        store.upsert_daily_section(
            "大模型", kind="topic", day="2026-09-17",
            entries=[store.WikiEntry("要点")], links=["OpenAI"],
        )
        store.upsert_daily_section(
            "OpenAI", kind="company", day="2026-09-17",
            entries=[store.WikiEntry("要点")],
        )
        store.rebuild_moc()

    def test_list_pages_reports_metadata(self, isolated_wiki):
        self._seed()
        pages = {p.name: p for p in store.list_pages()}
        assert set(pages) >= {"大模型", "OpenAI", "索引"}
        assert pages["大模型"].kind == "topic"
        assert pages["大模型"].dates == ["2026-09-17"]
        assert pages["大模型"].links == ["OpenAI"]
        assert pages["OpenAI"].kind == "company"
        assert pages["索引"].kind == "index"

    def test_read_page_by_name_and_path(self, isolated_wiki):
        self._seed()
        assert "# 大模型" in (store.read_page("大模型") or "")
        assert "# 大模型" in (store.read_page("主题/大模型.md") or "")
        assert store.read_page("不存在") is None

    def test_backlinks(self, isolated_wiki):
        self._seed()
        assert store.backlinks("OpenAI") == ["大模型"]

    def test_search_matches_lines(self, isolated_wiki):
        self._seed()
        assert store.search("要点")[0]["page"] in {"大模型", "OpenAI"}
        assert store.search("") == []


class TestMOC:
    def test_moc_groups_topics_and_companies(self, isolated_wiki):
        store.upsert_daily_section("大模型", kind="topic", day="2026-09-17", entries=[store.WikiEntry("a")])
        store.upsert_daily_section("OpenAI", kind="company", day="2026-09-17", entries=[store.WikiEntry("b")])
        moc = store.rebuild_moc()
        text = moc.read_text(encoding="utf-8")
        assert "## 主题" in text and "[[大模型]]" in text
        assert "## 公司" in text and "[[OpenAI]]" in text
        assert "type: index" in text


def test_empty_vault_skips_wiki_and_does_not_fallback(tmp_path, monkeypatch):
    from newsclaw.config import settings

    monkeypatch.setattr(settings, "project_root", tmp_path)
    save_config(NewsroomConfig(obsidian_vault=""))
    assert store.wiki_enabled() is False
    assert store.wiki_root() is None
    assert store.list_pages() == []
    with pytest.raises(store.WikiDisabledError):
        store.upsert_daily_section("大模型", kind="topic", day="2026-09-17", entries=[store.WikiEntry("x")])
    assert not (tmp_path / "data" / "wiki").exists()


def test_frontmatter_keeps_colon_in_title(isolated_wiki):
    rendered = store._render_frontmatter({"title": "foo: bar", "type": "topic"})
    meta, _ = store._split_frontmatter(rendered + "\n# x\n")
    assert meta["title"] == "foo: bar"


def test_obsidian_vault_config_wins_over_builtin(tmp_path, monkeypatch):
    """配置了 obsidian_vault 时，页面写进用户的 Obsidian 库。"""
    from newsclaw.config import settings

    monkeypatch.setattr(settings, "project_root", tmp_path)
    vault = tmp_path / "my-vault"
    save_config(NewsroomConfig(obsidian_vault=str(vault)))
    store.upsert_daily_section("大模型", kind="topic", day="2026-09-17", entries=[store.WikiEntry("x")])
    assert (vault / "主题" / "大模型.md").is_file()
    assert store.wiki_root() == vault


class TestGraphData:
    """知识网络图数据契约：去重无向边、跳过索引页与悬空链接。"""

    def test_graph_dedups_edges_and_skips_index_and_dangling(self, isolated_wiki):
        store.upsert_daily_section(
            "大模型", kind="topic", day="2026-09-17",
            entries=[store.WikiEntry("a")], links=["OpenAI", "不存在的页"],
        )
        store.upsert_daily_section(
            "OpenAI", kind="company", day="2026-09-17",
            entries=[store.WikiEntry("b")], links=["大模型"],  # 反向引用 → 应去重成一条边
        )
        store.rebuild_moc()

        graph = store.graph_data()
        names = {n["name"] for n in graph["nodes"]}
        assert names == {"大模型", "OpenAI"}  # 索引页不进图
        assert graph["links"] == [{"source": "OpenAI", "target": "大模型"}]  # 去重 + 悬空链接被丢

    def test_graph_empty_when_no_pages(self, isolated_wiki):
        assert store.graph_data() == {"nodes": [], "links": []}


class TestGraphTimeWindow:
    """图谱按时间过滤：只看最近 N 期活跃的页面与关系。"""

    def _seed_two_periods(self):
        # 8 月（旧期）：大模型 ↔ OpenAI
        store.upsert_daily_section(
            "大模型", kind="topic", day="2026-08-10",
            entries=[store.WikiEntry("旧")], links=["OpenAI"],
        )
        store.upsert_daily_section(
            "OpenAI", kind="company", day="2026-08-10", entries=[store.WikiEntry("旧")],
        )
        # 9 月（新期）：大模型 ↔ 开源项目，OpenAI 本期无更新
        store.upsert_daily_section(
            "大模型", kind="topic", day="2026-09-17",
            entries=[store.WikiEntry("新")], links=["开源项目"],
        )
        store.upsert_daily_section(
            "开源项目", kind="topic", day="2026-09-17", entries=[store.WikiEntry("新")],
        )
        store.rebuild_moc()

    def test_full_graph_keeps_all_periods(self, isolated_wiki):
        self._seed_two_periods()
        graph = store.graph_data(today="2026-09-17")
        assert {n["name"] for n in graph["nodes"]} == {"大模型", "开源项目", "OpenAI"}
        # 两期都有更新 → 大模型 dates=2
        assert next(n for n in graph["nodes"] if n["name"] == "大模型")["dates"] == 2

    def test_window_drops_stale_pages_and_edges(self, isolated_wiki):
        self._seed_two_periods()
        graph = store.graph_data(days=7, today="2026-09-17")
        # OpenAI 只在 8 月出现 → 窗口外，节点与其边都被剔除
        assert {n["name"] for n in graph["nodes"]} == {"大模型", "开源项目"}
        assert graph["links"] == [{"source": "大模型", "target": "开源项目"}]
        # 窗口内只看 9 月 → dates=1
        assert next(n for n in graph["nodes"] if n["name"] == "大模型")["dates"] == 1

    def test_window_covering_both_periods(self, isolated_wiki):
        self._seed_two_periods()
        graph = store.graph_data(days=90, today="2026-09-17")
        assert {n["name"] for n in graph["nodes"]} == {"大模型", "开源项目", "OpenAI"}
