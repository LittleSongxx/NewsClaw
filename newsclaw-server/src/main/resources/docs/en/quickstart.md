# Quick Start

Launch the NewsClaw AI-news operations workflow with Docker. Both local and
production configuration live in .env; real secrets are never placed in seed
data or committed to Git.

## 1. Get the code and configuration

~~~bash
git clone https://github.com/LittleSongxx/NewsClaw.git
cd NewsClaw
cp .env.example .env
chmod 600 .env
~~~

Replace these placeholders in .env:

- DB_PASSWORD and DB_ADMIN_PASSWORD
- JWT_SECRET
- NEWSCLAW_SETTING_KEY and NEWSCLAW_ENCRYPT_KEY
- SEARXNG_SECRET

Keep these values stable. In particular, losing either encryption key prevents
automatic recovery of existing encrypted settings or Skill secrets.

## 2. Configure the minimal model chain

The recommended production chain is:

~~~dotenv
NEWSCLAW_PRIMARY_MODEL_PROVIDER=bailian-team
NEWSCLAW_PRIMARY_MODEL=qwen3.7-plus
NEWSCLAW_FALLBACK_MODEL_CHAIN=deepseek::deepseek-v4-flash

BAILIAN_API_KEY=...
DEEPSEEK_API_KEY=...
~~~

BAILIAN_API_KEY and DASHSCOPE_API_KEY are aliases for the Bailian credential.
If only one provider is available, leave the fallback chain empty; do not use
a fake key.

The bundled SearXNG search fallback needs no API key. Serper, Tavily, and a
GitHub token are optional search enhancements, not first-boot requirements.

## 3. Configure Feishu

The mainline uses a Feishu long connection:

~~~dotenv
FEISHU_APP_ID=cli_xxx
FEISHU_APP_SECRET=xxx
FEISHU_CONNECTION_MODE=websocket
~~~

Create and enable a Feishu channel in the workbench as well. Receiving a bot
message does not by itself create a scheduled-delivery target: the daily radar
needs a valid recent conversation target. A failed delivery is recorded without
rolling back event and evidence persistence.

Xiaohongshu needs no API key. The current output is a card preview and ZIP
asset package for an operator to upload and publish in the creator console.

## 4. Start and check health

~~~bash
docker compose config
docker compose up -d --build
docker compose ps
curl -fsS http://localhost:18080/actuator/health
~~~

The health endpoint should return UP. Open <http://localhost:18080>; the
development bootstrap account is admin / admin123. Change that password first.

docker-compose.yml retains newsclaw as its Compose project name to preserve
verified container, network, and volume names. It is a deployment identifier,
not the product name. The product is NewsClaw.

## 5. Run a human-confirmed workflow

1. Send the Feishu bot: “Summarize today’s updates from DeepSeek, OpenAI, and Unitree.”
2. Inspect candidate events, official sources, claims, and conflicts in the AI-news workbench.
3. Confirm a topic and create a Team Run.
4. Review research, verification, editorial, and visual tasks.
5. Generate a Xiaohongshu preview and ZIP package, then upload and publish it manually.

Reproducible evidence for the flow is the event ID, Wiki evidence page ID, Team
Run ID, approval audit, and delivery artifacts. Do not substitute an offline
test run for real platform-delivery validation.

## 6. Offline regression

~~~bash
./scripts/eval-ai-news-ops.sh
~~~

The script never reads .env or calls real models or platforms, so it is safe
for CI and policy regression. See [AI News Operations](./ai-news-ops) for its
metric boundary.

## Common issues

- Compose exits immediately: a required database or encryption value in .env is blank.
- No model output: check provider keys, model catalog rows, and docker compose logs -f newsclaw-server.
- No scheduled Feishu message: check channel status, long-connection logs, and the recent conversation target.
- No automatic Xiaohongshu post: this is an intentional boundary, not missing configuration.

Continue with [Docker Deployment](./docker-deploy) for operations, backup, and
troubleshooting details.
