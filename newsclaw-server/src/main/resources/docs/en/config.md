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
TAVILY_API_KEYS=
TAVILY_API_KEY=
SEARXNG_BASE_URL=http://newsclaw-searxng:8080
NEWSCLAW_AI_NEWS_RSS_FEEDS=
NEWSCLAW_AI_NEWS_SITEMAPS=
NEWSCLAW_AI_NEWS_SOURCE_ENDPOINT_IDS=
NEWSCLAW_AI_NEWS_SEARXNG_BASE_URL=
NEWSCLAW_AI_NEWS_SEARXNG_ALLOW_PRIVATE_ENDPOINT=true

NEWSCLAW_AI_NEWS_INGESTION_ENABLED=true
NEWSCLAW_AI_NEWS_INGESTION_SCAN_INTERVAL_MS=60000
NEWSCLAW_AI_NEWS_INGESTION_INITIAL_DELAY_MS=15000
NEWSCLAW_AI_NEWS_INGESTION_ON_DEMAND_REFRESH_IF_EMPTY=false
NEWSCLAW_AI_NEWS_INGESTION_MAX_POLLS_PER_CYCLE=50
NEWSCLAW_AI_NEWS_INGESTION_STALE_RUN_MINUTES=30
NEWSCLAW_AI_NEWS_INGESTION_CANDIDATE_LOOKBACK_DAYS=31

# V213 shadow candidate pipeline; scanning and outbound capture are separate opt-ins.
NEWSCLAW_AI_NEWS_CANDIDATE_PIPELINE_ENABLED=false
NEWSCLAW_AI_NEWS_CANDIDATE_CAPTURE_ENABLED=false
NEWSCLAW_AI_NEWS_CANDIDATE_SCAN_INTERVAL_MS=900000
NEWSCLAW_AI_NEWS_CANDIDATE_CAPTURE_INTERVAL_MS=60000
NEWSCLAW_AI_NEWS_CANDIDATE_LOOKBACK_HOURS=24
NEWSCLAW_AI_NEWS_CANDIDATE_MAX_CANDIDATES=30
NEWSCLAW_AI_NEWS_CANDIDATE_MAX_CAPTURES_PER_SCAN=10
NEWSCLAW_AI_NEWS_CANDIDATE_MAX_CAPTURE_ATTEMPTS=3
NEWSCLAW_AI_NEWS_CANDIDATE_CAPTURE_RETRY_MINUTES=15
NEWSCLAW_AI_NEWS_CANDIDATE_STALE_CAPTURE_MINUTES=30
NEWSCLAW_AI_NEWS_CANDIDATE_CONFIG_VERSION=candidate-pipeline-v2-bocha
# Bocha Web Search uses a dedicated credential, never a model or video key.
NEWSCLAW_AI_NEWS_CHINA_SEARCH_ENABLED=false
NEWSCLAW_AI_NEWS_CHINA_SEARCH_API_KEY=
NEWSCLAW_AI_NEWS_CHINA_SEARCH_BASE_URL=https://api.bochaai.com/v1/web-search
NEWSCLAW_AI_NEWS_CHINA_SEARCH_COUNT=20
NEWSCLAW_AI_NEWS_CHINA_SEARCH_TIMEOUT_SECONDS=15

NEWSCLAW_AI_NEWS_DISCOVERY_MAX_CANDIDATES_PER_HOST=4
NEWSCLAW_AI_NEWS_DISCOVERY_MAX_CANDIDATES_PER_STORY=2
NEWSCLAW_AI_NEWS_DISCOVERY_CURRENT_OPEN_WEB_PERCENT=20
NEWSCLAW_AI_NEWS_DISCOVERY_MAX_UNKNOWN_PERCENT=0
NEWSCLAW_AI_NEWS_DISCOVERY_UNKNOWN_OFFICIAL_PERCENT=20
NEWSCLAW_AI_NEWS_DISCOVERY_UNKNOWN_MEDIA_PERCENT=20
NEWSCLAW_AI_NEWS_DISCOVERY_UNKNOWN_OPEN_WEB_PERCENT=0

NEWSCLAW_OTLP_METRICS_ENABLED=false
NEWSCLAW_OTLP_METRICS_URL=http://otel-collector:4318/v1/metrics
NEWSCLAW_OTLP_METRICS_STEP=60s

NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_ENABLED=true
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_MAX_BYTES=1048576
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_MIN_TEXT_CHARS=200
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_TIMEOUT_SECONDS=15
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_MAX_REDIRECTS=5
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_MAX_ATTEMPTS=2
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_RETRY_BASE_DELAY_MILLIS=250
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_RETRY_MAX_DELAY_MILLIS=5000
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_PROXY_URL=

NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_ENABLED=true
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_REQUIRED=true
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_ENDPOINT=http://newsclaw-content-extractor:8090
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_EXPECTED_NAME=trafilatura
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_EXPECTED_VERSION=2.2.0
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_EXPECTED_CONFIG_HASH=0235b7bf49c3c80ea6a52aee9f413fa2d4e4e1f5196af87278c48e558c7d0400
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_TIMEOUT_MILLIS=5000
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_MAX_REQUEST_BYTES=1048576
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_MAX_RESPONSE_BYTES=1572864
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_MAX_OUTPUT_CHARS=1048576
NEWSCLAW_AI_NEWS_CONTENT_EXTRACTION_MAX_CONCURRENCY=4
~~~

Serper and Tavily are optional search enhancements. Without their keys,
NewsClaw can fall back to bundled SearXNG. Official capture is a bounded GET
operation with redirect, byte-size, and timeout checks; it never marks an event
verified on its own.
The default requires a complete response of at most 1 MiB; an oversized body is
rejected instead of being silently truncated, and Trafilatura output below
`MIN_TEXT_CHARS` cannot become a successful capture. Optional `PROXY_URL` accepts
only a credential-free, path-free HTTP proxy and is used after a direct
timeout/TLS/I/O failure. The fallback route is retained in capture provenance.

`NEWSCLAW_AI_NEWS_RSS_FEEDS` accepts comma-separated RSS/Atom URLs approved by
the deployment operator. Polls reuse publisher ETag and Last-Modified validators.
`NEWSCLAW_AI_NEWS_SITEMAPS` accepts Google News Sitemap or sitemap-index URLs;
generic URL entries without News publication metadata are ignored. Configured
feeds and sitemaps are merged into discovery without using a web-search credit.
Their title, snippet and publication timestamp are ranking hints by default;
selected articles must still pass source capture. A publisher structured time
may supplement that capture only when the versioned catalog explicitly marks
the endpoint evidence-eligible with allowlisted reviewed rights/robots states,
and canonical URL, publisher owner, exact timestamp, and a complete digested
transport all agree. Review each publisher's syndication and commercial-use
terms before enabling a feed or sitemap.

`NEWSCLAW_AI_NEWS_SOURCE_ENDPOINT_IDS` enables endpoint ids from the versioned
`source_catalog.yml`; every bundled entry is disabled by default and unknown ids
fail startup. Ad-hoc URL variables remain available for backward-compatible,
operator-owned deployments.
Enabling an endpoint id alone never grants evidence eligibility. Structured
time accepts rights states `approved`, `licensed`, `publisher_authorized`, or
`public_metadata`, and robots states `allowed`, `not_applicable`, or
`publisher_authorized`; conflicting eligible publisher times are rejected.

Discovery resolves parseable publication hints into in-window, unknown, or
outside-window states. Outside-window rows are rejected; unknown-time rows only
use the bounded Top-K exploration allowance from `MAX_UNKNOWN_PERCENT` and still
require source capture. `CURRENT_OPEN_WEB_PERCENT` bounds current but unregistered
open-Web rows. A host contributes at most `MAX_CANDIDATES_PER_HOST` rows, while a
high-confidence entity/product/action story signature keeps at most
`MAX_CANDIDATES_PER_STORY` independent publishers. This prevents one story from
consuming the capture queue while preserving corroboration. These values are
precision gates, not relevance boosts, so discovery may return fewer than the
requested Top-K.
The unregistered, unknown-time open-Web lane is disabled by default. Set
`UNKNOWN_OPEN_WEB_PERCENT` above zero only when the deployment accepts the
higher stale-page risk and captures every admitted row for review.

When ingestion is enabled, the cluster-singleton scheduler polls each RSS/Atom
or News Sitemap endpoint independently. ETag/Last-Modified cursors, every run,
HTTP observation, item identity, and semantic version are written to the durable
ledger. A conditional database lease prevents scheduler and request threads from
claiming the same endpoint. Discovery reads only the latest persisted versions;
`ON_DEMAND_REFRESH_IF_EMPTY` is off by default and should be enabled only when
publisher-dependent request latency is acceptable. Cycle size, stale-run age,
and candidate lookback also have server-side hard bounds.
Ingestion runs, duration, item outcomes, HTTP status families, and received bytes
use low-cardinality Micrometer metrics. Set `NEWSCLAW_OTLP_METRICS_ENABLED=true`
to export them through Spring Boot's managed OTLP registry to an OpenTelemetry
Collector. Export is off by default, and labels contain no endpoint id, URL, or body.

Compose runs the version-pinned Trafilatura main-content adapter and fails an
evidence capture closed when the adapter is unavailable, rejects an input, or
returns unknown provenance. The Java service remains the only URL-fetching
authority; the adapter only receives HTML that already passed redirect, SSRF,
size, and timeout controls, and never dereferences `sourceUrl`. Every successful
capture records extractor name, version, configuration SHA-256, fallback flag,
and extracted-text SHA-256. A standalone JAR keeps the primary adapter disabled
by default. Operators should run the same protocol and explicitly enable it;
the server admits provenance only when all three `EXPECTED_*` values match.
Changing an implementation or extraction configuration therefore requires an
explicit config update and evaluation rerun. Setting `REQUIRED=false` permits
the clearly marked whole-document compatibility fallback and is not recommended
for production evidence capture.

`TAVILY_API_KEYS` accepts multiple comma-separated keys and automatically
fails over on authentication, rate-limit, or quota errors. The legacy
`TAVILY_API_KEY` remains supported when the pool variable is empty.

The AI-news source SPI can optionally configure RSS feeds or a dedicated
SearXNG endpoint. `SEARXNG_BASE_URL` is the default for the news-source adapter;
`NEWSCLAW_AI_NEWS_SEARXNG_BASE_URL` overrides it. Set
`NEWSCLAW_AI_NEWS_SEARXNG_ALLOW_PRIVATE_ENDPOINT=true` only for a fixed,
operator-configured Compose sidecar or controlled internal endpoint. The switch
only permits that fixed search endpoint; result URLs and `fetch_source` remain
under strict SSRF checks.

## Feishu and daily radar

~~~dotenv
FEISHU_APP_ID=
FEISHU_APP_SECRET=
FEISHU_CONNECTION_MODE=websocket
# The scheduled radar also requires NEWSCLAW_AI_NEWS_CANDIDATE_PIPELINE_ENABLED=true;
# legacy ai_news_event discovery is manual-only when the candidate mainline is off.
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
