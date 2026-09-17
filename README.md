<p align="center">
  <img src="docs/assets/logo.png" alt="NewsClaw" width="120" />
</p>

<h1 align="center">NewsClaw</h1>

<p align="center">
  <b>一条会自己进化的 AI 早报流水线</b><br/>
  每天采集 → 成稿 → 归档 → 复盘，把"资讯生产"这件事跑成长期后台任务
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Python-3.11+-3776ab?style=flat-square&logo=python&logoColor=white" />
  <img src="https://img.shields.io/badge/FastAPI-asyncio-009688?style=flat-square&logo=fastapi&logoColor=white" />
  <img src="https://img.shields.io/badge/React-18-61dafb?style=flat-square&logo=react&logoColor=white" />
  <img src="https://img.shields.io/badge/Tauri-2-ffc131?style=flat-square&logo=tauri&logoColor=white" />
  <img src="https://img.shields.io/badge/134_tools-88_skills-8b5cf6?style=flat-square" />
  <img src="https://img.shields.io/badge/tests-5900%2B_passing-2ea44f?style=flat-square" />
  <img src="https://img.shields.io/badge/license-AGPL--3.0--only-blue?style=flat-square" />
</p>

<p align="center">
  <img src="docs/assets/screenshots/newsroom.png" alt="NewsClaw 早报工作台" width="100%" />
</p>

---

**NewsClaw 是一个跑在自己机器上的多 Agent AI 助手，主线只有一件事：把 AI 圈的信息变成可以直接发布的稿件。** 它不需要你每天去催——定时任务到点自己采集、自己写、自己归档、自己打分，每周再根据你的反馈改自己的选题口味。

所有数据都在本地（`~/.newsclaw` + 工作区 `data/`），模型走你自己的 Key（内置 32 家 provider）。桌面端、Web、移动端共用一个后端。

---

## 它每天在做什么

<table>
<tr>
<td width="33%" valign="top">

**① 采集**

四条信源（AI 综合搜索 / 前沿模型动态 / 公司与融资 / 开源项目热点）并发抓取，去重、打分、挑选当日 10+ 条。

</td>
<td width="33%" valign="top">

**② 成稿**

一个 Agent 一次产出三份产物：编辑内部简报、公众号稿、小红书稿（含话题标签与配图建议）。

</td>
<td width="33%" valign="top">

**③ 沉淀 + 归档**

调研结果写进本地 Markdown 知识库（主题页 / 公司页 / 互链 / 索引），再归档到飞书云文档。

</td>
</tr>
</table>

<p align="center">
  <img src="docs/assets/screenshots/wiki.png" alt="本地知识库 Wiki" width="100%" />
  <em>本地 Markdown 知识库：既是应用内的浏览 / 检索 / 关系图，也是标准 Obsidian 库（同一份文件，两种形态）。</em>
</p>

**④ 复盘（自进化）** 每周日，复盘 Agent 读最近 7 期的自评分和你的逐期反馈，然后去改三样东西：`sources.yaml`（信源质量）、`editorial-policy.md`（选题口味 / 文风 / 结构模板）、记忆（经验与规则）。第二天的管线立刻用上新配置——这才是"自进化"真正落地的地方。

```yaml
# data/newsroom/config.yaml
enabled: true
daily_cron: "0 7 * * *"      # 每日管线
weekly_cron: "0 10 * * 1"    # 每周复盘
```

---

## 架构

```
                 ┌──────────────────────── 桌面端 / Web / 移动端 ────────────────────────┐
                 │  React 18 + Tauri 2      早报工作台 · 知识库 · 聊天 · 技能 · 调度 · 审批  │
                 └───────────────────────────────┬─────────────────────────────────────────┘
                                                 │  REST / WebSocket
┌────────────────────────────────────────────────▼─────────────────────────────────────────┐
│                                   FastAPI（HTTP + 事件流）                                │
│                                                                                          │
│   scheduler ──► Agent 运行时 ──► 工具 → 落盘契约 ──► REST API ──► 前端                    │
│   定时任务        ReAct 循环      134 个工具    Markdown/SQLite                           │
│        │               │                                                                 │
│        │               ├── 策略矩阵（Policy V2）—— 放行 / 确认 / 拒绝，无人值守默认拒绝     │
│        │               │                                                                 │
│        ▼               ▼                                                                 │
│   newsroom 主线    wiki 知识库     memory 三层记忆     skills 技能     channels IM 通道   │
│   期次契约/信源/    主题页/公司页   工作记忆/长期/向量   88 个技能      8 个 IM 适配器      │
│   提示词/反馈库     互链/索引      检索                 渐进式披露      扫码绑定            │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

Python 只负责**契约与调度**（期次目录、manifest 校验、任务播种、反馈存储、REST API），"怎么采集、怎么写、怎么改自己"全部交给 Agent 的 ReAct 循环 + 可编辑的配置/技能/记忆文件。这条边界是刻意画的：只有行为可被文件改变，复盘任务才有东西可改。

---

## 能力地图

<table>
<tr>
<td width="50%"><img src="docs/assets/screenshots/chat.png" /><br/><b>对话</b> — 多 Agent 委派、并行子任务、计划模式、中断与降级</td>
<td width="50%"><img src="docs/assets/screenshots/skills.png" /><br/><b>技能</b> — SKILL.md 声明式技能，渐进式披露；11 个精选 + 77 个系统技能</td>
</tr>
<tr>
<td width="50%"><img src="docs/assets/screenshots/scheduler.png" /><br/><b>调度</b> — 长期定时任务，管线与复盘都在这里跑</td>
<td width="50%"><img src="docs/assets/screenshots/security.png" /><br/><b>安全</b> — 策略矩阵、路径免疫、审批队列、无人值守默认拒绝</td>
</tr>
<tr>
<td width="50%"><img src="docs/assets/screenshots/memory.png" /><br/><b>记忆</b> — 三层记忆与向量检索，跨会话记住你的偏好</td>
<td width="50%"><img src="docs/assets/screenshots/status.png" /><br/><b>可观测</b> — 健康检查、Token 统计、诊断导出、反馈闭环</td>
</tr>
</table>

---

## 技术栈

| | |
|---|---|
| **后端** | Python 3.11+ · FastAPI · asyncio · aiosqlite · pydantic v2 |
| **前端** | React 18 · TypeScript · Vite 6 · Tailwind |
| **桌面** | Tauri 2（Rust 外壳，自带 Python 运行时引导） |
| **存储** | SQLite（会话 / 记忆 / 审计）+ Markdown（知识库 / 记忆 / 期次产物） |
| **模型** | 32 家 provider 预设（Anthropic / OpenAI 兼容 / 国内模型 / 本地 ollama） |
| **规模** | 134 个工具定义 · 88 个技能 · 8 个 IM 适配器 · 37 个前端视图 |

```
src/newsclaw/
  newsroom/      AI 早报主线：期次契约 · 信源 · 配置 · 提示词 · 播种 · 反馈
  wiki/          本地 Markdown 知识库（主题页 / 公司页 / 互链 / 索引）
  integrations/  飞书 OAuth 与文档归档 · 市场安装器
  core/          Agent 运行时 · Ralph 执行循环 · ReasoningEngine · Policy V2
  agents/        多 Agent：编排 · 工厂 · 预设 · 打包
  tools/         工具系统（handlers / definitions）与搜索号池
  prompt/        提示词分层拼装（身份 → 人格 → 运行时 → 规则 → 目录 → 记忆）
  memory/        三层记忆（存储 / 向量 / 检索）
  skills/        技能加载 · 注册 · 市场 · 国际化
  scheduler/     定时任务         channels/ IM 通道        api/ REST + WebSocket
apps/setup-center/   桌面端与 Web 前端（Tauri + React）
skills/              系统技能 77 + 精选外部技能 11
identity/            Agent 身份（SOUL / AGENT / POLICIES / 人格库）
tests/               5900+ 测试（unit / component / integration / e2e）
```

---

## 快速开始

```bash
# 后端
python -m venv .venv && source .venv/bin/activate    # Windows: .venv\Scripts\activate
pip install -e ".[dev]"

newsclaw init                 # 配置向导：模型 Key、IM 通道
newsclaw                      # 交互式终端会话
newsclaw run "帮我整理今天的 AI 新闻"    # 单次任务（无人值守语义）
newsclaw serve                # 服务模式：IM 通道 + HTTP API（127.0.0.1:18900）

# 桌面端
cd apps/setup-center && npm install && npm run tauri dev
```

## 质量

```bash
pytest                                  # 全量测试
pytest tests/unit                       # 单元
ruff check src/                         # lint
python -m build --wheel                 # 打包（同时校验技能清单与打包清单一一致）
```

仓库自带 CI（lint · 测试 · 打包 · 前端类型检查），绿色才算改动完成。

---

## 许可

**AGPL-3.0-only** — 见 [LICENSE](LICENSE)。

本项目基于 [OpenAkita](https://github.com/openakita/openakita) 二次开发（上游同为 AGPL-3.0-only）。产品定位、功能范围与代码由本人改造；**上游的版权、许可与归属声明按要求保留**：见 [NOTICE](NOTICE)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)、[identity/CREDITS.md](identity/CREDITS.md)、[TRADEMARK.md](TRADEMARK.md)。AGPL-3.0-only 是 OSI 认可的完全开源许可；作为衍生作品，本仓库整体继续以 AGPL-3.0-only 发布。

## 这个仓库里没有的东西

为了保持主线清晰，以下内容**不在**本仓库发布（本地开发副本仍保留，见 `.gitignore`）：上游的示例插件与媒体类插件集、已移出主线的技能归档、内部改版计划与发布流水线（签名安装包 / 应用商店 / 容器镜像等需要私有凭据的流程）、以及上游的产品文档站。
