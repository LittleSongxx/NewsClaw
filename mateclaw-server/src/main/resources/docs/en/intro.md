---
title: NewsClaw Introduction
description: An AI industry event discovery, evidence verification, and content operations system for global and Chinese AI news.
head:
  - - meta
    - name: keywords
      content: NewsClaw,AI news,evidence verification,multi-Agent,Team Run,Feishu,Xiaohongshu,memory,Skill governance
---

# NewsClaw

**AI industry updates should be discovered, verified, produced, delivered, and
fed back into operations - not merely summarized.**

NewsClaw is a vertical content-operations system for global and Chinese AI
news. It tracks model releases, embodied AI and robotics, chips and
infrastructure, major-company products, open-source releases, funding,
partnerships, and policy, turning “what matters today?” into an auditable
workflow:

```text
discovery -> evidence verification -> topic confirmation -> multi-Agent production
-> compliance / human approval -> Feishu notification / Xiaohongshu package
-> Memory and Skill feedback
```

## Why it is not a generic news assistant

Time-sensitive industry content commonly fails in three ways:

- search results mix rumors, reposts, and official announcements while the
  final copy presents them all as facts;
- long-running research, editing, review, and delivery lose their links and
  cannot recover after a failure;
- recurring human edits to topic choice, tone, and headlines do not improve the
  next production run.

NewsClaw addresses these with an event-and-evidence domain, persistent Team
Runs, and governed Memory / Skill proposals. Completion means events, evidence,
runs, approvals, and artifacts are mutually traceable, not simply that an
answer sounds plausible.

## Current mainline

### 1. Official-source-first verification

Every candidate event records its canonical URL/hash, source tier, claims,
excerpts, publication time, confidence, and conflicts. An event cannot reach
outward delivery without valid evidence and no unresolved conflict. Full
evidence is archived in the Wiki; article bodies are never treated as
long-term memory.

### 2. Team Run control plane

A confirmed topic creates one `runId`. Discovery, fact checking, editing,
visual production, and compliance delivery form a dependency-aware task DAG.
Parallel execution, conditional claims, leases, heartbeats, retries,
cancellation, stale recovery, and SSE projections make long work observable
and recoverable.

### 3. Governed operational feedback

Feishu uses a long connection for operating instructions, stage progress, and
daily radar delivery. Xiaohongshu receives evidence- and compliance-bound
previews plus a ZIP material package. A human confirms every irreversible
outward action.

Long-term memory retains stable preferences, editorial constraints, and
feedback. Reflection and Routine Mining are off by default; when enabled they
create reviewable Skill proposals rather than silently changing production
capabilities.

## Explicit boundaries

- The daily radar discovers candidates and notifies at `08:00 Asia/Shanghai`;
  it does not automatically publish content.
- Xiaohongshu delivery is a 3-18-card preview and ZIP package. An operator
  uploads and publishes it in the creator console.
- WeChat Official Account delivery is not the default path; legacy draft
  tooling requires explicit credentials.
- Offline regression tests validate policies and code paths, not online
  discovery accuracy, delivery rate, or model quality.

## Start here

- [AI News Operations](./ai-news-ops) for events, evidence, Team Runs, and
  delivery boundaries.
- [Quick Start](./quickstart) to launch Docker and configure models and Feishu.
- [Content Production](./content-studio) for artifacts, compliance, and the
  manual-publish boundary.
- [Docker Deployment](./docker-deploy) for operations and health checks.
