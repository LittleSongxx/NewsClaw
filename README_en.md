# NewsClaw

<p align="center">
  <img src="mateclaw-ui/public/logo/newsclaw-mark.svg" width="76" alt="NewsClaw mark" />
</p>

**An AI industry news discovery, evidence-verification, and content-operations Agent for global and Chinese AI developments.**

[Chinese portfolio README](README.md)

NewsClaw is a vertical operating workflow, not a generic chat assistant:

```text
Scheduled discovery / Feishu request
  -> canonical event deduplication
  -> official-source-first evidence capture
  -> claim alignment and conflict gate
  -> internal Wiki evidence archive
  -> durable multi-Agent Team Run
  -> compliance + human review
  -> Feishu review card + Xiaohongshu material ZIP
  -> governed Memory and reviewed Skill proposals
```

Its key technical boundaries are deliberate:

- An event cannot become `verified` without valid evidence, a title, and a canonical URL. Conflicted leads cannot silently become externally deliverable facts.
- Team Run is a durable long-task control plane with a persisted `runId`, task DAG, `blockedBy` dependencies, claims, leases, heartbeats, retries, cancellation, stale recovery, SSE projections, and deliverable links. It does not claim a full LangGraph checkpoint implementation or exactly-once external execution.
- Long-term memory keeps stable editorial preferences, team context, and feedback. Event bodies and raw evidence stay in the event/Wiki layer.
- Reflection and Routine Mining create reviewable Skill candidates only. Promotion, binding, archive, and restore remain audited human actions.
- Feishu supports WebSocket inbound messages and interactive review cards. Xiaohongshu delivery stops at a validated 3–18 image preview/ZIP package for human creator-console upload; NewsClaw makes no claim of an ordinary-creator publishing API or unattended browser posting.

## Real evidence

The Chinese README documents one real Feishu request received on 2026-08-24:

```text
请汇总今天 DeepSeek、OpenAI 和宇树科技的最新动态，并把候选事件发成复核卡
```

It resulted in one verified DeepSeek event and two conflicted media-only leads. A separate verified DeepSeek event then completed the downstream Team Run and Xiaohongshu packaging path. The full screenshots, audit IDs, capability boundaries, and reproducible validation notes are in the [Chinese portfolio README](README.md).

## Stack

Java 21, Spring Boot 3.5, Spring AI Alibaba, MyBatis Plus, Flyway, PostgreSQL 16, Vue 3, TypeScript, Vite, Element Plus, SearXNG, Feishu WebSocket channel, and Playwright-based rendering regression checks.

## Quick start

```bash
git clone https://github.com/LittleSongxx/NewsClaw.git
cd NewsClaw
cp .env.example .env
docker compose up -d --build
```

Open <http://localhost:18080>. Configure database/JWT/encryption/SearXNG secrets, model keys, and Feishu credentials only in the ignored `.env` file.

Licensed under the [Apache License 2.0](LICENSE).
