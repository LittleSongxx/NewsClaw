# NewsClaw

AI industry news discovery, evidence verification, and content operations Agent.

[![Repository](https://img.shields.io/badge/GitHub-NewsClaw-181717?logo=github)](https://github.com/LittleSongxx/NewsClaw)
[![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs)](https://vuejs.org/)
[![License](https://img.shields.io/badge/license-Apache--2.0-red)](LICENSE)

[中文说明](README_zh.md)

NewsClaw tracks time-sensitive developments across the global and Chinese AI
industry: foundation models, embodied intelligence and robotics, chips and
infrastructure, major-company AI products, open-source releases, funding,
partnerships, and policy.

It is a vertical workflow rather than a generic assistant:

```text
Scheduled discovery / IM request
  -> canonical event deduplication
  -> official-source evidence capture
  -> claim alignment and conflict gate
  -> internal Wiki evidence archive
  -> persistent multi-Agent Team Run
  -> compliance and human review
  -> Feishu notification + Xiaohongshu material package
  -> memory and reviewed Skill proposals
```

## Core loops

### Event discovery and evidence

- Source tiers distinguish official evidence from media discovery leads.
- Canonical URLs and hashes collapse repeated reports into one event.
- Claims retain source URLs, excerpts, timestamps, confidence, and conflicts.
- An event cannot become `verified` without a title, canonical URL, and valid
  evidence. Unresolved conflicts block external delivery.
- Evidence is stored in the event domain and internal Wiki, not copied into
  long-term user memory.

### Persistent multi-Agent production

One request owns one durable `runId`. A task DAG uses `blockedBy`
dependencies for parallel research, verification, writing, visual production,
and review. Database claims, leases, heartbeats, retries, cancellation, stale
recovery, SSE projections, and deliverable links make long-running work
observable and recoverable.

External side effects use idempotency records. Human approval remains the gate
before outward delivery.

### Memory and Skill governance

Long-term memory stores stable preferences, editorial constraints, and feedback
with source pointers, token budgets, conflict versions, and workspace
isolation. News bodies and raw evidence are rejected from long-term memory.

Reflection and Routine Mining are conservative by default. Repeated work can
produce a sanitized Skill proposal, but promotion, binding, archival, and
restore remain auditable review actions. A proposal never silently rewrites a
production Skill.

## Delivery boundaries

| Surface | Current behavior |
| --- | --- |
| Feishu | WebSocket channel for inbound requests, progress cards, replies, and scheduled radar notifications |
| Xiaohongshu | Evidence-bound phone preview and ZIP package with 3-18 rendered cards; a human uploads it in the creator console |
| WeChat Official Account | Not part of the default NewsClaw delivery path; legacy draft tooling remains optional and requires explicit credentials |

NewsClaw does **not** claim an official Xiaohongshu publishing API or unattended
browser posting. It deliberately stops before account login, CAPTCHA, platform
risk controls, and the irreversible publish action.

The seeded daily radar runs at `08:00 Asia/Shanghai`. A successful proactive
Feishu delivery requires an enabled Feishu channel and a valid recent
conversation target. Discovery persistence is not rolled back when an IM
delivery fails.

## Model routing

The recommended chain is configured entirely from `.env`:

```dotenv
MATECLAW_PRIMARY_MODEL_PROVIDER=bailian-team
MATECLAW_PRIMARY_MODEL=qwen3.7-plus
MATECLAW_FALLBACK_MODEL_CHAIN=deepseek::deepseek-v4-flash
```

Provide `DASHSCOPE_API_KEY` or `BAILIAN_API_KEY` for the primary model and
`DEEPSEEK_API_KEY` for fallback. Provider health, cooldown, retry policy, and
routing traces make failover observable.

## Quick start

Prerequisites: Docker Engine with Compose v2. Java and Node are only required
for local development.

```bash
git clone https://github.com/LittleSongxx/NewsClaw.git
cd NewsClaw
cp .env.example .env
```

Edit `.env` and replace the database, JWT, encryption, and SearXNG placeholder
secrets. Then add at least one model API key. For the primary workflow, also
configure `FEISHU_APP_ID` and `FEISHU_APP_SECRET`.

```bash
docker compose config
docker compose up -d --build
docker compose ps
```

Open <http://localhost:18080>. The development bootstrap account is
`admin / admin123`; change it immediately on a persistent or reachable
deployment.

Real secrets belong only in the ignored `.env`. Never commit that file.

## Verification

Run the deterministic AI-news policy and regression suite:

```bash
./scripts/eval-ai-news-ops.sh
```

Frontend checks:

```bash
cd mateclaw-ui
corepack pnpm install
corepack pnpm typecheck
corepack pnpm build
```

The offline evaluation does not call real models or external platforms. It
must not be presented as online discovery accuracy or delivery success.
Production evidence comes from event IDs, Wiki evidence pages, Team Run IDs,
delivery ledgers, approvals, and actual Feishu/Xiaohongshu artifacts.

See [AI news operations evaluation](docs/zh/ai-news-ops-evaluation.md) for the
metric boundary and fixed test cases.

## Architecture

| Layer | Technology and responsibility |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5, Spring AI Alibaba, MyBatis Plus, Flyway |
| Agent runtime | StateGraph, ReAct / Plan-and-Execute, Team Run task DAG |
| Data | PostgreSQL 16 in Docker; H2 and MySQL-compatible migrations retained |
| Search | SearXNG by default, with optional Serper and Tavily fallback |
| Evidence | AI-news event domain, source registry, official capture, internal Wiki |
| Delivery | Feishu channel, content calendar, Xiaohongshu preview and ZIP package |
| Governance | Workspace RBAC, Tool Guard, approvals, audit, memory and Skill proposals |
| Frontend | Vue 3, TypeScript, Vite, Element Plus |

Existing internal module and environment prefixes named `mateclaw-*` /
`MATECLAW_*` are retained for runtime and deployment compatibility. The
product, repository, documentation, and Git history are NewsClaw.

## Repository layout

```text
mateclaw-server/          Spring Boot backend and embedded documentation
mateclaw-ui/              Vue administration and AI-news workbench
mateclaw-plugin-api/      Optional Java capability extension API
scripts/                  Deterministic evaluation and operational scripts
docs/zh/                  NewsClaw-specific design and evaluation notes
docker-compose.yml        PostgreSQL, SearXNG, and application stack
.env.example              Fully annotated configuration template
```

## License

Licensed under the [Apache License 2.0](LICENSE). Existing copyright notices and
third-party license files are retained.
