# 配置参考

NewsClaw 的部署配置以仓库根目录的 .env.example 为唯一完整模板。复制为 .env 后，
非空环境变量会覆盖对应的数据库/UI 值；空值继续使用数据库/UI 中已有配置。
因此可以把部署密钥和模型链交给 .env 管理，而不把真实 Key 提交到仓库。

## 必填安全配置

| 变量 | 用途 |
| --- | --- |
| DB_PASSWORD | PostgreSQL 应用账号密码 |
| DB_ADMIN_PASSWORD | PostgreSQL 初始化与管理账号密码 |
| JWT_SECRET | JWT 签名密钥，建议使用 openssl rand -base64 48 生成 |
| NEWSCLAW_SETTING_KEY | 系统设置中的 AES-GCM 敏感值加密密钥 |
| NEWSCLAW_ENCRYPT_KEY | Skill Secret 与数据源密码加密密钥 |
| SEARXNG_SECRET | SearXNG sidecar 会话密钥 |

不要在运行中的生产环境随意更换两类加密 Key。原值遗失后，需要重新填写无法解密的
敏感配置。

## 推荐模型链

~~~dotenv
NEWSCLAW_ENV_CONFIG_ENABLED=true
NEWSCLAW_PRIMARY_MODEL_PROVIDER=bailian-team
NEWSCLAW_PRIMARY_MODEL=qwen3.7-plus
NEWSCLAW_FALLBACK_MODEL_CHAIN=deepseek::deepseek-v4-flash

BAILIAN_API_KEY=
DEEPSEEK_API_KEY=
~~~

BAILIAN_API_KEY 与 DASHSCOPE_API_KEY 均可作为百炼主模型凭证。备用链可填多个
provider::model，逗号分隔。只配置一家 Provider 时留空备用链即可。

模型选择变量改变的是部署默认链路；Provider 目录和模型启用状态仍可在工作台管理。
运行时会记录健康状态、冷却、失败重试和路由轨迹。

## 搜索与官方证据

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

# V213 shadow 候选流水线；扫描和外部正文抓取分别显式开启。
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
# 博查 Web Search 使用独立密钥，不复用模型或视频生成凭证。
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

Serper 和 Tavily 是可选搜索增强，未提供 Key 时系统可回退到 Docker 内置的 SearXNG。
`TAVILY_API_KEYS` 可填写逗号分隔的多个 Key；鉴权、限流或额度错误时会自动切换。
旧版 `TAVILY_API_KEY` 仍兼容，并在号池变量为空时生效。
官方证据抓取只允许受限的 GET、重定向检查、字节数和超时，不会自动把事件标为已核验。
默认最多接收 1 MiB 完整响应，超限会明确失败而不会截断后冒充证据；Trafilatura 正文少于
`MIN_TEXT_CHARS` 时同样不能成为成功 capture。可选 `PROXY_URL` 只接受无凭证、无路径的 HTTP
代理，并且只在直连发生 timeout/TLS/I/O 故障后回退；是否走过代理会写入 capture provenance。

`NEWSCLAW_AI_NEWS_RSS_FEEDS` 接受部署方审核过的逗号分隔 RSS/Atom URL，轮询会复用
发布方提供的 ETag 与 Last-Modified。`NEWSCLAW_AI_NEWS_SITEMAPS` 接受 Google News
Sitemap 或 sitemap index；没有新闻发布时间元数据的普通 URL 条目会被忽略。已配置的
feed 和 sitemap 会作为不消耗 Web 搜索额度的结构化通道参与候选融合；标题、摘要和
发布时间默认都只是排序线索，选中的文章必须继续通过来源抓取。仅当版本化目录把 endpoint
标为 `evidence_eligible: true`，并把 rights/robots 状态填写为代码允许的已审核值时，发布方
结构化时间才能在规范 URL、发布者身份、完整响应摘要均一致的条件下补充文章 capture 的时间。
上线前需逐个审核发布方对聚合、转载和商业使用的许可条款；仅启用 endpoint id 不会自动授予
证据资格。

`NEWSCLAW_AI_NEWS_SOURCE_ENDPOINT_IDS` 用于启用版本化 `source_catalog.yml` 中的
endpoint id；内置 endpoint 默认全部关闭，未知 id 会使启动失败。原始 URL 变量继续作为
兼容入口，由部署方自行负责其来源身份和条款审核。结构化时间证言采用 fail-closed allowlist：
rights 只接受 `approved/licensed/publisher_authorized/public_metadata`，robots 只接受
`allowed/not_applicable/publisher_authorized`；多来源精确时间冲突时不选择多数值，而是拒绝准入。

发现层先把可解析发布时间分为窗口内、未知、窗口外；窗口外候选硬拒绝，未知时间候选只占
`MAX_UNKNOWN_PERCENT` 所定义的 Top-K 探索槽位，并继续要求正文抓取确认。当前但未注册的开放
Web 候选受 `CURRENT_OPEN_WEB_PERCENT` 限制。单一 host 最多保留
`MAX_CANDIDATES_PER_HOST` 条；高置信实体/产品/动作签名相同的报道最多保留
`MAX_CANDIDATES_PER_STORY` 个独立发布方，既避免同一事件挤满抓取队列，也保留交叉核验来源。
这些比例是精度门禁，不是相关性加分；达到配额后允许返回不足请求 Top-K 的结果。
未注册且时间未知的开放 Web lane 默认关闭；只有部署方明确接受更高陈旧率并安排逐条抓取复核时，
才应把 `UNKNOWN_OPEN_WEB_PERCENT` 调到大于 0。

启用 ingestion 后，RSS/Atom 与 News Sitemap 由集群单例调度器按 endpoint 独立轮询，
ETag/Last-Modified、每次 run、HTTP observation、item identity 和语义版本都会进入持久化
账本。数据库条件租约避免调度线程与请求线程重复领取同一 endpoint。发现请求只读取最新
持久化版本；`ON_DEMAND_REFRESH_IF_EMPTY` 默认关闭，只有明确接受请求延迟依赖发布方时才
应开启。`MAX_POLLS_PER_CYCLE`、stale run 和候选回看窗口均有后端硬上限。
摄取 run、耗时、item outcome、HTTP 状态族和接收字节使用低基数 Micrometer 指标；设置
`NEWSCLAW_OTLP_METRICS_ENABLED=true` 后由 Spring Boot 官方 OTLP registry 上报到指定
OpenTelemetry Collector。默认关闭 exporter，且 metric label 不包含 endpoint id、URL 或正文。

Compose 默认运行固定版本的 Trafilatura 正文抽取容器，并令来源证据 capture 在抽取器不可用、
拒绝输入或返回未知版本时 fail-closed。Java 服务仍是唯一 URL 抓取方；抽取容器只接收已经过
SSRF、重定向、大小和超时检查的 HTML，不会自行访问 `sourceUrl`。每份成功 capture 都保存
extractor 名称、版本、配置 SHA-256、fallback 标记和正文 SHA-256。单独运行 JAR 时默认关闭
主抽取器；部署方应运行同协议 sidecar 后显式启用，或接受带明确 provenance 的兼容 fallback。
生产服务只接受与三个 `EXPECTED_*` 值完全一致的 provenance，升级实现或配置必须显式改值并
重跑评测。生产环境不建议把 `REQUIRED` 设为 `false`，否则正文可能退回整页文本近似路径。

AI 动态来源 SPI 可选配置 RSS 列表或独立的 SearXNG endpoint。`SEARXNG_BASE_URL`
会作为新闻来源 adapter 的默认值；`NEWSCLAW_AI_NEWS_SEARXNG_BASE_URL` 可覆盖它。
仅在固定、由运维配置的 Compose sidecar 或受控内网 endpoint 上把
`NEWSCLAW_AI_NEWS_SEARXNG_ALLOW_PRIVATE_ENDPOINT` 设为 `true`。这个开关只放行
该固定搜索 endpoint，搜索结果 URL 和 `fetch_source` 仍执行严格 SSRF 检查。

## 飞书与每日雷达

~~~dotenv
FEISHU_APP_ID=
FEISHU_APP_SECRET=
FEISHU_CONNECTION_MODE=websocket
# 每日雷达还要求 NEWSCLAW_AI_NEWS_CANDIDATE_PIPELINE_ENABLED=true；否则旧兼容
# ai_news_event 仅可手动调用，不会被定时任务静默使用。
NEWSCLAW_AI_NEWS_RADAR_ENABLED=true
~~~

还需要在工作台中启用飞书渠道。每日雷达固定在 Asia/Shanghai 的 08:00 发现候选、
初步去重并通知；它不自动对外发布内容。主动投递使用最近会话目标，投递失败不回滚
事件和证据写入。

## 小红书与公众号

小红书当前不要求 API Key。它生成 3-18 张卡片预览和 ZIP 素材包，人工在创作中心
上传发布。

微信公众号不是默认路径。只有显式填写 WEIXINOA_APP_ID 和 WEIXINOA_APP_SECRET
才会启用历史草稿能力；不要把公众号凭证写进 seed、测试或 README。

## Memory 与 Skill 治理

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

新闻正文和证据原文不应进入长期 Memory；它们属于事件与 Wiki 证据层。Reflection
和 Routine Mining 开启后也只能生成待审 proposal，不能自动修改生产 Skill。

## 配置优先级与排障

1. 非空环境变量优先。
2. 数据库/UI 配置次之。
3. 应用默认值最后。

验证 Compose 展开结果时运行 docker compose config，但不要把命令输出贴到公共 Issue
或仓库，因为输出可能包含秘密值。排障时优先检查 docker compose logs -f
newsclaw-server 和健康接口。
