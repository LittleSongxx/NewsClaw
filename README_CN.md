<p align="center">
  <img src="docs/assets/logo.png" alt="NewsClaw" width="120" />
</p>

<h1 align="center">NewsClaw</h1>

<p align="center">
  <b>A daily AI briefing pipeline that rewrites itself</b><br/>
  Collect → draft → archive → review, running as a long-lived background job
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Python-3.11+-3776ab?style=flat-square&logo=python&logoColor=white" />
  <img src="https://img.shields.io/badge/FastAPI-asyncio-009688?style=flat-square&logo=fastapi&logoColor=white" />
  <img src="https://img.shields.io/badge/React-18-61dafb?style=flat-square&logo=react&logoColor=white" />
  <img src="https://img.shields.io/badge/Tauri-2-ffc131?style=flat-square&logo=tauri&logoColor=white" />
  <img src="https://img.shields.io/badge/115_tools-82_skills-8b5cf6?style=flat-square" />
  <img src="https://img.shields.io/badge/tests-6000%2B_passing-2ea44f?style=flat-square" />
  <img src="https://img.shields.io/badge/license-AGPL--3.0--only-blue?style=flat-square" />
</p>

<p align="center">
  <img src="docs/assets/screenshots/newsroom.png" alt="NewsClaw newsroom" width="100%" />
</p>

> Chinese (primary): [README.md](README.md) · This file is the English edition.

---

**NewsClaw is a local, multi-agent AI assistant with a single main line of work: turning the AI-news firehose into publishable copy.** It does not wait to be asked. A scheduled task collects the day's stories, writes them up, deposits the research into a local knowledge base, archives it to Feishu, and once a week reviews its own output and proposes editorial-policy changes (applied only after human approval).

Everything stays on your machine (`~/.newsclaw` plus the workspace `data/`), and models run on your own keys (32 provider presets built in). Desktop and web clients share one backend (the Capacitor mobile shell is kept local and is not published with this repository).

---

## What it does every day

| | | |
|---|---|---|
| **① Collect** | **② Draft** | **③ Deposit & archive** |
| Four default source families (AI search, frontier-model news, funding, open source; extendable via weekly proposals) fetched concurrently, deduplicated, scored, then narrowed to 7–12 items (contract floor: at least 3 with links; fewer is acceptable on a thin day). | One agent produces three deliverables per issue: an internal brief, a WeChat article draft, and a Xiaohongshu post with hashtags and image suggestions. | Research lands in a local Markdown knowledge base (topic/company pages, backlinks, index) and is archived to Feishu cloud docs. |

<p align="center">
  <img src="docs/assets/screenshots/wiki.png" alt="Local knowledge base" width="100%" />
  <em>Local Markdown knowledge base: browsable and searchable in the app, and a valid Obsidian vault at the same time — one set of files, two professional forms.</em>
</p>

**④ Review (self-evolution).** Every Sunday a review agent reads the last seven issues' scores (looking back up to 14 when needed) and your per-issue feedback, and produces structured proposals for three things: `sources.yaml` (source quality), `editorial-policy.md` (topic taste, voice, structure templates) and memory (rules and experience), delivering the proposal files to Feishu. It never edits the config directly — you approve the ops in the web UI and code applies them; the next day's pipeline picks the changes up, which is where "self-evolving" actually lands.

```yaml
# data/newsroom/config.yaml (field names match the code)
enabled: true
daily_cron: "0 8 * * *"      # daily pipeline, local timezone
review_cron: "0 20 * * 0"    # Sunday 20:00 review
# obsidian_vault: ""         # empty = skip Wiki; set an absolute vault path to write
```

---

## Architecture

```
                 ┌──────────────────── Desktop / Web / Mobile clients ────────────────────┐
                 │  React 18 + Tauri 2    newsroom · wiki · chat · skills · schedule · approvals
                 └───────────────────────────────┬────────────────────────────────────────┘
                                                 │  REST / WebSocket
┌────────────────────────────────────────────────▼────────────────────────────────────────┐
│                                  FastAPI (HTTP + event stream)                          │
│                                                                                         │
│   scheduler ──► agent runtime ──► tools → contract writes ──► REST API ──► clients      │
│   cron tasks     ReAct loop       115 tools   Markdown/SQLite                           │
│        │              │                                                                 │
│        │              ├── policy matrix (Policy V2): allow / confirm / deny             │
│        │              │   — unattended runs deny confirmations instead of hanging        │
│        ▼              ▼                                                                 │
│   newsroom line   wiki KB       memory (3 layers)  skills (82)   IM channels (3)        │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

Python owns **contracts and scheduling** (issue layout, manifest validation, task seeding, feedback storage, REST API); *how* to collect, write and revise is left to the agent's ReAct loop plus editable config, skill and memory files. That boundary is deliberate: behaviour only changes if files can change it, which is what gives the weekly review something to act on.

---

## Capabilities

<table>
<tr>
<td width="50%"><img src="docs/assets/screenshots/chat.png" /><br/><b>Chat</b> — multi-agent delegation, parallel subtasks, plan mode, interrupt & degrade</td>
<td width="50%"><img src="docs/assets/screenshots/skills.png" /><br/><b>Skills</b> — declarative SKILL.md with progressive disclosure; 11 curated + 71 system skills</td>
</tr>
<tr>
<td width="50%"><img src="docs/assets/screenshots/scheduler.png" /><br/><b>Scheduling</b> — long-lived cron tasks; the pipeline and the review both live here</td>
<td width="50%"><img src="docs/assets/screenshots/security.png" /><br/><b>Security</b> — policy matrix, path immunity, approval queue, deny-by-default when unattended</td>
</tr>
<tr>
<td width="50%"><img src="docs/assets/screenshots/memory.png" /><br/><b>Memory</b> — three layers with vector retrieval; remembers your preferences across sessions</td>
<td width="50%"><img src="docs/assets/screenshots/status.png" /><br/><b>Observability</b> — health checks, token stats, diagnostic export, feedback loop</td>
</tr>
</table>

---

## Stack

| | |
|---|---|
| **Backend** | Python 3.11+ · FastAPI · asyncio · aiosqlite · pydantic v2 |
| **Frontend** | React 18 · TypeScript · Vite 6 · Tailwind |
| **Desktop** | Tauri 2 (Rust shell with its own Python runtime bootstrap) |
| **Storage** | SQLite (sessions / memory / audit) + Markdown (wiki / memory / issue artifacts) |
| **Models** | 32 provider presets (Anthropic / OpenAI-compatible / regional / local ollama) |
| **Scale** | 115 tool definitions · 82 skills · 3 IM adapters · 34 frontend views |

```
src/newsclaw/
  newsroom/      briefing line: issue contract · sources · config · prompts · seeding · feedback
  wiki/          local Markdown knowledge base (topic/company pages, backlinks, index)
  integrations/  Feishu OAuth / document archival / WeChat article publishing / integration adapter scaffolding
  core/          agent runtime (ReAct loop) · ReasoningEngine · Policy V2
  agents/        multi-agent: orchestration · factory · presets · packaging
  tools/         tool system (handlers / definitions) and search key pools
  prompt/        layered prompt assembly (identity → persona → runtime → rules → catalogs → memory)
  memory/        three-layer memory (storage / vectors / retrieval)
  skills/        skill loading / registry / categories / i18n
  scheduler/     cron tasks        channels/  IM adapters       api/  REST + WebSocket
apps/setup-center/   desktop + web frontend (Tauri + React)
skills/              71 system skills + 11 curated external skills
identity/            agent identity (SOUL / AGENT / POLICIES / personas)
tests/               6,000+ tests (6,057 collected; unit / component / integration / e2e / eval)
```

---

## Quick start

```bash
# Backend
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -e ".[dev]"

newsclaw init                 # setup wizard: model keys, IM channels
newsclaw                      # interactive terminal session
newsclaw run "summarise today's AI news"    # single task; unattended CONFIRM defaults to deny
newsclaw serve                # service mode: IM channels + HTTP API (127.0.0.1:18900)

# Desktop
cd apps/setup-center && npm install && npm run tauri dev
```

## Quality

```bash
pytest                                  # full suite
pytest tests/unit                       # unit only
ruff check src/                         # lint
ruff check tests/ --select E9,F         # test-file syntax / undefined-name gate (CI parity)
python -m build --wheel                 # packaging; a force-include mismatch fails the build
```

CI runs version consistency, lint (including the test-file gate), tests with a coverage floor, the wheel build and frontend typecheck + unit tests; green is the bar for a change.

---

## License

**AGPL-3.0-only** — see [LICENSE](LICENSE). Do not relicense to MIT or strip LICENSE / NOTICE / TRADEMARK / copyright lines.

This project is an **unofficial fork** of [OpenAkita](https://github.com/openakita/openakita) (also AGPL-3.0-only). Product positioning, feature scope and code were reworked here; **upstream copyright, licence and attribution notices are preserved as required**: see [NOTICE](NOTICE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [identity/CREDITS.md](identity/CREDITS.md) and [TRADEMARK.md](TRADEMARK.md).

There is **no standalone NewsClaw cloud** and no official account / marketplace hostname. `NEWSCLAW_ACCOUNT_MODE` defaults to `disabled`; account or marketplace integrations need an explicit URL. The plugin UI SDK (`openakita_plugin_sdk`, upstream PyPI name kept so existing plugins keep importing) is a separate MIT-licensed companion that is **not shipped in this repository**: plugin authors may ship call-site code under MIT, but running it inside this repository remains bound by the host AGPL-3.0-only.

## What is not in this repository

To keep the main line readable, the following are deliberately not published here (the local development copy keeps them; see `.gitignore`): example plugins (`examples/plugins/`) and media plugin sets, the archive of skills moved off the main line (`attic/`), internal revamp plans and local specs (`specs/`, `research/`), and upstream's product documentation site (`docs-site/`). The release workflows and scripts (`.github/workflows/release*.yml`, `scripts/`) are in the repository, but signed installers, store packages and container images need private credentials and do not run by default.
