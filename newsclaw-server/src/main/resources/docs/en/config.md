# Configuration Reference

The root .env.example is the complete NewsClaw deployment template. Copy it to
.env. A nonblank environment variable overrides its corresponding database/UI
value; a blank value keeps the existing database/UI configuration. This lets
deployment secrets and the model chain live in .env without committing real
keys.

## Required security values

| Variable | Purpose |
| --- | --- |
| DB_PASSWORD | PostgreSQL application-role password |
| DB_ADMIN_PASSWORD | PostgreSQL bootstrap and administration password |
| JWT_SECRET | JWT signing key; generate with openssl rand -base64 48 |
| NEWSCLAW_SETTING_KEY | AES-GCM key for sensitive system settings |
| NEWSCLAW_ENCRYPT_KEY | Encryption key for Skill secrets and datasource passwords |
| SEARXNG_SECRET | SearXNG sidecar session secret |

Do not casually rotate the two encryption keys on a running deployment. If
their old values are lost, encrypted settings must be entered again.

## Recommended model chain

~~~dotenv
NEWSCLAW_ENV_CONFIG_ENABLED=true
NEWSCLAW_PRIMARY_MODEL_PROVIDER=bailian-team
NEWSCLAW_PRIMARY_MODEL=qwen3.7-plus
NEWSCLAW_FALLBACK_MODEL_CHAIN=deepseek::deepseek-v4-flash

BAILIAN_API_KEY=
DEEPSEEK_API_KEY=
~~~

BAILIAN_API_KEY and DASHSCOPE_API_KEY are aliases for the Bailian primary
credential. The fallback list may contain multiple comma-separated
provider::model entries. With only one provider, leave the fallback list blank.

Model-selection variables define the deployment default chain; provider catalog
rows and model enablement remain manageable in the workbench. Runtime records
provider health, cooldown, retries, and routing traces.

## Search and official evidence

~~~dotenv
NEWSCLAW_SEARCH_ENABLED=true
NEWSCLAW_SEARCH_FALLBACK_ENABLED=true
NEWSCLAW_SEARCH_PROVIDER=serper
SERPER_API_KEY=
TAVILY_API_KEY=
SEARXNG_BASE_URL=http://newsclaw-searxng:8080

NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_ENABLED=true
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_MAX_BYTES=524288
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_TIMEOUT_SECONDS=15
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_MAX_REDIRECTS=5
~~~

Serper and Tavily are optional search enhancements. Without their keys,
NewsClaw can fall back to bundled SearXNG. Official capture is a bounded GET
operation with redirect, byte-size, and timeout checks; it never marks an event
verified on its own.

## Feishu and daily radar

~~~dotenv
FEISHU_APP_ID=
FEISHU_APP_SECRET=
FEISHU_CONNECTION_MODE=websocket
NEWSCLAW_AI_NEWS_RADAR_ENABLED=true
~~~

Enable the Feishu channel in the workbench as well. The daily radar discovers,
initially deduplicates, and notifies at 08:00 Asia/Shanghai; it does not publish
outward automatically. Proactive delivery uses a recent-conversation target,
and a delivery failure does not roll back event or evidence writes.

## Xiaohongshu and WeChat

Xiaohongshu currently needs no API key. It produces a 3-18 card preview and ZIP
asset package for manual upload and publish in the creator console.

WeChat Official Account is not a default path. Its legacy draft capability is
activated only when WEIXINOA_APP_ID and WEIXINOA_APP_SECRET are explicitly
provided. Never place those credentials in seed data, tests, or a README.

## Memory and Skill governance

~~~dotenv
NEWSCLAW_MEMORY_GOVERNANCE_ENABLED=true
NEWSCLAW_MEMORY_WRITE_MAX_TOKENS=320
NEWSCLAW_MEMORY_LONG_TERM_TOKEN_BUDGET=2400
NEWSCLAW_MEMORY_REJECT_NEWS_BODY=true
NEWSCLAW_MEMORY_REQUIRE_SOURCE_REF=true

NEWSCLAW_SKILL_REFLECTION_ENABLED=false
NEWSCLAW_SKILL_REFLECTION_AUTO_APPLY=false
NEWSCLAW_SKILL_ROUTINE_ENABLED=false
NEWSCLAW_SKILL_ROUTINE_AUTO_PROMOTE=false
~~~

Article bodies and raw evidence do not belong in long-term Memory; they belong
to the event and Wiki evidence layers. Even when Reflection and Routine Mining
are enabled, they create reviewable proposals only and cannot automatically
modify a production Skill.

## Precedence and troubleshooting

1. Nonblank environment variables win.
2. Database/UI configuration is next.
3. Application defaults are last.

Use docker compose config to validate Compose expansion, but never post its full
output to a public issue or repository because it can contain secret values.
For diagnosis, start with docker compose logs -f newsclaw-server and the health
endpoint.
