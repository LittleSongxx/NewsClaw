# NewsClaw

面向全球与中国 AI 圈的动态发现、证据核验与内容运营 Agent。

[![仓库](https://img.shields.io/badge/GitHub-NewsClaw-181717?logo=github)](https://github.com/LittleSongxx/NewsClaw)
[![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs)](https://vuejs.org/)
[![许可证](https://img.shields.io/badge/license-Apache--2.0-red)](LICENSE)

[English](README.md)

NewsClaw 聚焦具有时效性的 AI 行业事件，包括大模型与研究、具身智能与
机器人、芯片与基础设施、互联网大厂 AI 产品、开源发布、融资合作和政策动态。
它不是通用聊天平台，而是一条可核验、可恢复、可审计的垂类内容运营闭环：

```text
定时发现 / 飞书指令
  -> 事件 URL 与指纹去重
  -> 官方来源优先核验
  -> claims 对齐、冲突与置信度判断
  -> Wiki 证据页归档
  -> 持久化多 Agent Team Run
  -> 合规扫描与人工审批
  -> 飞书通知 + 小红书素材包
  -> Memory 与待审 Skill 提案反哺下一轮
```

## 三条核心闭环

### AI 动态发现与证据核验

- 来源注册表区分官方主证据与媒体发现线索。
- canonical URL 与 hash 将多次转载合并为同一事件。
- 每条 claim 保留来源 URL、引用片段、发布时间、置信度和冲突信息。
- 缺少标题、canonical URL 或有效证据的事件不能进入 `verified`。
- 未解决冲突会阻断对外交付；只读抓取不会自动把事件标成已核验。
- 新闻正文和完整证据进入事件域与内部 Wiki，不污染长期记忆。

### 多 Agent 长任务生产

一次请求对应一个持久化 `runId`。热点发现、事实核查、内容编辑、视觉编辑和
合规交付构成带 `blockedBy` 依赖的任务 DAG，可以并行执行。数据库条件抢占、
lease、heartbeat、retry、cancel、stale recovery、SSE 投影和交付物关联共同
保证长任务可观察、可恢复。

外部副作用写入幂等账本，人工审批仍是对外交付前的闸门。

### 长期记忆与 Skill 能力运营

长期记忆只保存稳定偏好、编辑约束、团队上下文和历史反馈，并要求来源指针、
token 预算、冲突版本与 workspace 隔离。新闻正文、证据原文会被长期记忆门禁
拒绝。

Reflection 与 Routine Mining 默认保守关闭。开启后，重复工作先经过脱敏、
归一化和聚类，最多生成待审 Skill proposal；晋升、绑定、归档与恢复均保留
审计记录，不会静默改写生产 Skill。

## 当前交付边界

| 平台 | 当前真实能力 |
| --- | --- |
| 飞书 | WebSocket 长连接入站、执行进度卡片、回复与定时雷达主动推送 |
| 小红书 | 证据约束的手机预览与 ZIP 素材包，硬校验 3-18 张卡片，人工在创作中心上传 |
| 微信公众号 | 不属于 NewsClaw 默认交付主线；历史草稿工具仍可选，只有显式配置凭证才使用 |

NewsClaw **没有**声称接入不存在的通用小红书官方发布 API，也没有通过无人值守
浏览器绕过登录、验证码或平台风控。当前闭环有意停在不可逆发布动作之前。

预置「每日 AI 动态雷达」在 `Asia/Shanghai` 时区每天 `08:00` 运行。
飞书主动推送需要已启用渠道以及有效的最近会话目标；IM 投递失败不会回滚已经
落库的事件。

## 模型链

推荐模型顺序完全由 `.env` 控制：

```dotenv
MATECLAW_PRIMARY_MODEL_PROVIDER=bailian-team
MATECLAW_PRIMARY_MODEL=qwen3.7-plus
MATECLAW_FALLBACK_MODEL_CHAIN=deepseek::deepseek-v4-flash
```

主模型填写 `DASHSCOPE_API_KEY` 或 `BAILIAN_API_KEY`，备用模型填写
`DEEPSEEK_API_KEY`。运行时会记录 Provider 健康状态、冷却、重试和路由轨迹。

## Docker 启动

前置条件：Docker Engine 与 Compose v2。本地二次开发再安装 JDK 21 和 Node。

```bash
git clone https://github.com/LittleSongxx/NewsClaw.git
cd NewsClaw
cp .env.example .env
```

编辑 `.env`，先替换数据库、JWT、加密和 SearXNG 的占位密钥，再填写至少一家
模型 Provider 的 Key。使用主线飞书闭环时，还需填写 `FEISHU_APP_ID` 与
`FEISHU_APP_SECRET`。

```bash
docker compose config
docker compose up -d --build
docker compose ps
```

访问 <http://localhost:18080>。开发引导账号为 `admin / admin123`，持久化或
可被外部访问的部署必须立即修改密码。

真实密钥只能放在已被忽略的 `.env`，禁止提交该文件。

## 验证

运行 AI 动态策略样本与相关确定性回归：

```bash
./scripts/eval-ai-news-ops.sh
```

前端验证：

```bash
cd mateclaw-ui
corepack pnpm install
corepack pnpm typecheck
corepack pnpm build
```

离线评测不会调用真实模型、飞书或小红书，不能被描述为线上发现准确率或真实
投递成功率。线上证据必须来自事件 ID、Wiki 证据页 ID、Team Run ID、投递账本、
审批审计以及实际生成的飞书消息和小红书素材。

指标边界与固定样本见
[AI 动态内容运营评测](docs/zh/ai-news-ops-evaluation.md)。

## 技术架构

| 层次 | 技术与职责 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5、Spring AI Alibaba、MyBatis Plus、Flyway |
| Agent | StateGraph、ReAct / Plan-and-Execute、Team Run 任务 DAG |
| 数据 | Docker 默认 PostgreSQL 16，保留 H2 与 MySQL 兼容迁移 |
| 检索 | 默认 SearXNG，可选 Serper、Tavily 及回退策略 |
| 证据 | AI 动态事件域、来源注册、官方页面只读抓取、内部 Wiki |
| 交付 | 飞书渠道、内容日历、小红书预览与 ZIP 素材包 |
| 治理 | Workspace RBAC、Tool Guard、审批审计、Memory 与 Skill proposal |
| 前端 | Vue 3、TypeScript、Vite、Element Plus |

为避免破坏已验证的运行与部署兼容性，内部模块目录和环境变量仍保留
`mateclaw-*` / `MATECLAW_*` 技术前缀；产品、仓库、对外文档和新的 Git
历史统一为 NewsClaw。

## 目录

```text
mateclaw-server/          Spring Boot 后端与内置文档
mateclaw-ui/              Vue 管理台与 AI 动态工作台
mateclaw-plugin-api/      可选 Java 能力扩展 API
scripts/                  确定性评测与运维脚本
docs/zh/                  NewsClaw 主线设计与评测说明
docker-compose.yml        PostgreSQL、SearXNG 与应用编排
.env.example              中文分区的完整配置模板
```

## 许可证

项目使用 [Apache License 2.0](LICENSE)。原有版权声明和第三方许可证文件会依法
保留。
