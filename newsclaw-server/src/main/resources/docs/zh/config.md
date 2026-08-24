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
TAVILY_API_KEY=
SEARXNG_BASE_URL=http://newsclaw-searxng:8080

NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_ENABLED=true
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_MAX_BYTES=524288
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_TIMEOUT_SECONDS=15
NEWSCLAW_AI_NEWS_OFFICIAL_CAPTURE_MAX_REDIRECTS=5
~~~

Serper 和 Tavily 是可选搜索增强，未提供 Key 时系统可回退到 Docker 内置的 SearXNG。
官方证据抓取只允许受限的 GET、重定向检查、字节数和超时，不会自动把事件标为已核验。

## 飞书与每日雷达

~~~dotenv
FEISHU_APP_ID=
FEISHU_APP_SECRET=
FEISHU_CONNECTION_MODE=websocket
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
