# Contributing and Development

NewsClaw maintains an AI-news content-operations workflow rather than expanding
into a generic platform. New work should improve event discovery, evidence
verification, Team Runs, Feishu, Xiaohongshu delivery, Memory, Skill governance,
or operational reliability. Unrelated scenarios should not be exposed by
default through navigation, seed data, or Agent toolboxes.

## Get the code

~~~bash
git clone https://github.com/LittleSongxx/NewsClaw.git
cd NewsClaw
git switch -c feat/your-change
~~~

The public primary branch is main. Do not configure or reference an upstream
remote from the original project.

## Local development

~~~bash
# Backend
cd newsclaw-server
mvn spring-boot:run

# In another terminal: frontend
cd newsclaw-ui
corepack pnpm install
corepack pnpm dev
~~~

The backend listens on 18088 by default, the frontend dev server on 5173, and
the Docker integration stack on 18080.

## Change rules

- Keep the event domain workspace-isolated; URL/hash deduplication must not leak across workspaces.
- Separate facts, preferences, and methods: evidence belongs in event/Wiki storage, stable preferences in long-term Memory, and reusable process templates in Skills.
- Unverified or conflicted events cannot reach outward delivery; external effects need idempotency keys and audit records.
- Reflection and Routine Mining may create reviewable proposals only; they may not silently rewrite production Skills.
- Automated Xiaohongshu account login, CAPTCHA handling, and platform risk-control bypass are outside the current scope.
- Document every new configuration field in the Chinese sections of .env.example. Never place real keys in tests, seed data, or docs.

## Verify

Run tests relevant to the change. The deterministic AI-news regression entry
point is:

~~~bash
./scripts/eval-ai-news-ops.sh
~~~

For frontend work:

~~~bash
cd newsclaw-ui
corepack pnpm typecheck
corepack pnpm test
corepack pnpm build
~~~

Offline evaluation does not replace real Feishu or Xiaohongshu delivery
verification. When documenting online behavior, provide sanitized event IDs,
evidence page IDs, Team Run IDs, approval records, and artifact references.

## Commit

Use small, complete Conventional Commits:

~~~text
feat(ai-news): block conflicted events from delivery
fix(feishu): preserve completed progress cards on stream updates
test(memory): cover news-body write rejection
docs(newsclaw): document the manual Xiaohongshu boundary
~~~

Before committing, ensure .env, database exports, generated material, logs, and
local node_modules are not staged. Keep the Apache 2.0 license, existing
copyright notices, and third-party license files.
