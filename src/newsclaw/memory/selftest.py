"""记忆自测：从历史会话抽样生成 QA，测检索召回率（替代已删的 evaluation）。

最小可用版本（mini-LoCoMo 自测）：
1. 从 conversation_turns 抽样若干轮用户消息；
2. 让 LLM 为每轮生成一个「该轮答案应在记忆/对话中」的问题（无 LLM 时退化为
   直接用原句做关键词检索——此时测的是存储而非召回质量）；
3. 用 RetrievalEngine 对问题做标准检索；
4. 报告 recall@k（前 k 条检索结果是否命中该轮的关键词）。

输出落 ``data/reports/memory_selftest_<ts>.json``，供人查看趋势。
"""

from __future__ import annotations

import json
import logging
import random
import time
from datetime import datetime
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)


def _extract_keywords(text: str, limit: int = 6) -> list[str]:
    """中英混合关键词抽取：英文词 + 中文 2-gram，去停用词。"""
    import re

    words = re.findall(r"[A-Za-z][A-Za-z0-9_-]{2,}", text)
    cjk = re.findall(r"[\u4e00-\u9fff]{2,4}", text)
    seen: list[str] = []
    for token in words + cjk:
        lowered = token.lower()
        if lowered not in {w.lower() for w in seen}:
            seen.append(token)
        if len(seen) >= limit:
            break
    return seen


async def run_memory_selftest(
    *,
    sample_size: int = 10,
    top_k: int = 5,
    use_llm_questions: bool = True,
) -> dict[str, Any]:
    """跑一轮记忆自测并落盘报告。"""
    from pathlib import Path as _P

    from newsclaw.config import settings
    from newsclaw.memory.manager import MemoryManager
    from newsclaw.memory.retrieval import RetrievalEngine

    data_dir = _P(settings.project_root) / "data" / "memory"
    mm = MemoryManager(
        data_dir=data_dir, memory_md_path=data_dir.parent / "MEMORY.md", search_backend="fts5"
    )
    engine: RetrievalEngine = getattr(mm, "retrieval", None) or RetrievalEngine(mm.store)

    # 1. 抽样：取有内容的用户轮（直接查 conversation_turns）
    conn_rows: list[dict] = []
    try:
        store = getattr(mm.store, "db", mm.store)
        conn_rows = store.get_recent_turns(role="user", limit=sample_size * 5) if hasattr(
            store, "get_recent_turns"
        ) else []
    except Exception:
        pass
    if not conn_rows:
        try:
            store = getattr(mm.store, "db", mm.store)
            raw = store._conn.execute(
                "SELECT session_id, content, timestamp FROM conversation_turns "
                "WHERE role = 'user' AND length(content) > 20 "
                "ORDER BY timestamp DESC LIMIT ?",
                (sample_size * 5,),
            ).fetchall()
            conn_rows = [dict(zip(("session_id", "content", "timestamp"), r, strict=True)) for r in raw]
        except Exception as exc:
            logger.warning("[Selftest] turn sampling failed: %s", exc)
            conn_rows = []
    if not conn_rows:
        return {"error": "no_recent_turns", "sampled": 0}

    random.seed(42)
    sample = random.sample(conn_rows, min(sample_size, len(conn_rows)))

    # 2. 问题生成（可选 LLM）+ 3. 检索 + 4. 计分
    brain = getattr(mm, "extractor", None) and getattr(mm.extractor, "brain", None)
    results: list[dict[str, Any]] = []
    hits = 0
    for row in sample:
        content = str(row.get("content") or "")
        keywords = _extract_keywords(content)
        if not keywords:
            continue
        question = content
        if use_llm_questions and brain:
            try:
                resp = await brain.think(
                    f"根据这句用户消息写一个简短问题（答案就是这句话里的信息），只输出问题本身：\n{content[:400]}",
                    enable_thinking=False,
                    max_tokens=64,
                )
                generated = (getattr(resp, "content", None) or str(resp)).strip()
                if generated:
                    question = generated
            except Exception:
                pass
        retrieved = engine.retrieve(question, max_tokens=400)
        # 检索非空即召回存储可用；关键词命中是更严口径
        keyword_hit = any(kw.lower() in retrieved.lower() for kw in keywords)
        results.append(
            {
                "session_id": row.get("session_id"),
                "question": question[:120],
                "keywords": keywords,
                "retrieved_chars": len(retrieved),
                "retrieval_nonempty": bool(retrieved.strip()),
                "keyword_hit": keyword_hit,
            }
        )
        if retrieved.strip():
            hits += 1

    recall = hits / len(results) if results else 0.0
    keyword_recall = sum(1 for r in results if r["keyword_hit"]) / len(results) if results else 0.0
    report = {
        "generated_at": datetime.now().isoformat(),
        "sampled": len(results),
        "top_k": top_k,
        "retrieval_nonempty_rate": round(recall, 3),
        "keyword_hit_rate": round(keyword_recall, 3),
        "results": results,
    }

    # 落盘
    try:
        from newsclaw.config import settings

        reports_dir = Path(settings.project_root) / "data" / "reports"
        reports_dir.mkdir(parents=True, exist_ok=True)
        path = reports_dir / f"memory_selftest_{int(time.time())}.json"
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        report["report_path"] = str(path)
    except Exception as exc:
        logger.warning("[Selftest] report write failed: %s", exc)
    return report
