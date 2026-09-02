---
title: NewsClaw AI News Operations
description: The NewsClaw workflow for AI event discovery, evidence verification, multi-Agent production, Feishu notification, and Xiaohongshu material delivery.
---

# AI News Operations

NewsClaw tracks time-sensitive developments in the global and Chinese AI
industry: models and research, embodied intelligence and robotics, chips and
infrastructure, major-company products, open source, funding, partnerships,
and policy.

It is not a generic news Q&A product. Every externally delivered topic passes
this workflow:

```text
backend multi-source scan -> persisted candidates/observations -> governed capture
-> human acceptance -> explicit event promotion
-> claim verification / conflict gate -> Wiki evidence archive
-> parallel Team Run -> compliance and human review
-> Feishu notification / Xiaohongshu package -> memory and reviewed Skill proposal
```

## Events and evidence

Events move through:

```text
candidate -> researching -> verified | conflicted | rejected
                         -> in_production -> published | archived
```

- Official pages are primary factual evidence; media reports are discovery
  leads or supporting context.
- URLs identify sources; separate atomic claim/quote packets from the same page
  retain separate evidence identities and cannot overwrite one another.
- Claims retain URL, source tier, excerpt, publication time, confidence, and
  conflict reason.
- An event cannot become `verified` without a title, canonical URL, and valid
  evidence.
- An unresolved conflict prevents outward delivery.

Article bodies and full evidence live in the event domain and Wiki evidence
layer, never as long-term memory payloads.

## Structured sources

Agents use `ai_news_scan` to start the backend candidate pipeline,
`ai_news_query` to page through candidates and funnel metrics, and
`ai_news_review` to record authenticated human decisions. Configured RSS,
News Sitemap, SearXNG, search-provider, and official-API adapters retain
provider, lane, original/canonical URL, rank, fetch time, and failures.

Every valid result first becomes a candidate/observation; capture failure or
unknown time does not erase it. Only a same-window candidate that is selected,
human-accepted, and successfully captured may be explicitly promoted into a
`candidate/researching` event. Promotion is neither verification nor
publication. The legacy `ai_news_event` chain remains a manual compatibility
path and scheduled jobs cannot fall back to it. An unavailable provider does
not establish that no relevant news exists.

## Team Run

When an operator confirms a topic in Feishu or the workbench, NewsClaw creates
a durable `runId`. Discovery, fact checking, editing, visual production, and
delivery are a dependency-aware task DAG. Independent tasks can run in
parallel; dependent tasks wait for `blockedBy` prerequisites.

The control plane includes conditional claims, leases, heartbeats, retry,
review, cancel, stale recovery, SSE projections, and idempotent external-effect
records. A failed IM delivery therefore does not roll back the persisted event
or evidence.

## Feishu and scheduled radar

The daily AI radar is scheduled for `08:00 Asia/Shanghai`. It remains
unregistered by default and can be enabled only when both radar/candidate
flags, a model credential, and a domestic IM channel are ready. Seed,
scheduler, and runner checks fail closed when either mainline flag is off and
never fall back to legacy `ai_news_event` on a scheduled run. It does not
automatically publish content.

Feishu uses a WebSocket long connection for inbound requests and stage progress.
Proactive delivery requires an enabled channel and a valid recent conversation
target. Without a target, work may still finish, while delivery is recorded as
failed or absent.

## Xiaohongshu delivery

The Xiaohongshu path produces:

- evidence-bound and compliance-scanned copy, title, tags, and card metadata;
- a phone-style preview with 3 to 18 portrait cards;
- a downloadable ZIP asset package;
- traceable content-calendar, event, and Team Run links.

NewsClaw does not sign in to or auto-publish a Xiaohongshu account. An operator
uploads, reviews, and publishes from the creator console. This is both the
current platform boundary and an explicit safeguard against bypassing CAPTCHA
or platform risk controls.

## Memory and Skills

Long-term memory stores stable preferences, editorial constraints, and feedback
with source references, token budgets, workspace isolation, conflict versions,
and news-body rejection.

Reflection and Routine Mining are off by default. When enabled, they only
create sanitized, reviewable Skill proposals. Promotion, binding, archival, and
restore remain audited operator actions.

## Model chain and evidence

The recommended primary model is Bailian `qwen3.7-plus`, with
`deepseek-v4-flash` as fallback. Configure the chain through
`.env` using `NEWSCLAW_PRIMARY_*` and
`NEWSCLAW_FALLBACK_MODEL_CHAIN`. Provider health, cooldown, retries, and
routing traces make fallback observable.

Run `./scripts/eval-ai-news-ops.sh` for deterministic regression coverage of
verification policy, canonical URLs, Memory gating, Skill proposals, Cron
idempotency, external-effect records, and model routing. It is not an online
accuracy or platform-delivery benchmark; those metrics must come from real
events, evidence pages, Team Runs, approvals, and delivery records.
