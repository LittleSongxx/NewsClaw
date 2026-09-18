# NewsClaw

本地优先的多 Agent AI 助手，主线是 **AI 资讯生产**：每日采集 → 三产物 → 本地 Wiki 沉淀 → 飞书归档 → 每周复盘自进化。详见 `README.md`。

## 技术栈

- **后端**：Python 3.11+（FastAPI、asyncio、aiosqlite、pydantic v2）
- **前端**：React 18 + TypeScript + Vite 6（`apps/setup-center/`）
- **桌面**：Tauri 2（Rust 外壳）
- **模型**：32 家 provider 预设（Anthropic、OpenAI 兼容、国内模型、本地 ollama）
- **IM**：Telegram、飞书、钉钉、企业微信（含 WS 模式）、QQ 官方、OneBot、微信

## 开发环境

```bash
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -e ".[dev]"

cd apps/setup-center && npm install                 # 只在改前端时
```

## 构建与运行

```bash
newsclaw                       # 交互式 CLI
newsclaw run "任务"             # 单次任务；无人值守对 CONFIRM 默认拒绝（deny），不会挂起等审批
newsclaw serve                 # 服务模式：IM 通道 + HTTP API（127.0.0.1:18900）
newsclaw plugin-validate <目录> # 校验插件 manifest
python -m newsclaw <子命令>      # 与 newsclaw 等价

cd apps/setup-center && npm run tauri dev   # 桌面端
```

## 测试与质量门

```bash
pytest                          # 全量（asyncio_mode=auto；testpaths=tests）
pytest tests/unit               # 单元
ruff check src/                 # lint（line-length=100，target py311）
mypy src/newsclaw              # 类型检查（宽松、尽力而为：pyproject ignore_errors=true，不守发布门）
python -m build --wheel         # 打包；技能清单与 pyproject force-include 不一致会直接失败
npx tsc --noEmit -p tsconfig.json && npx vitest run   # 前端（在 apps/setup-center）
```

`python -m build --wheel` 是打包契约的实际守门人：`skills/catalog.json`、`skills/*` 目录与 `pyproject.toml` 的 `force-include` 必须一一对应，否则报 `Forced file not found`。

## 代码风格

- Ruff 规则集：E、F、I、N、W、UP、B、C4、SIM（忽略项见 `pyproject.toml`）
- 行宽 100；格式化用 `ruff format src/`
- 注释/文档字符串用中文，标识符用英文

## 目录结构

```
src/newsclaw/
  newsroom/     AI 早报主线（契约 / 信源 / 配置 / 提示词 / 播种 / 反馈）
  wiki/         本地 Markdown 知识库（主题页 / 公司页 / 互链 / 索引）
  integrations/ 飞书 OAuth 与文档归档、市场安装器
  core/         Agent 运行时、Ralph 循环、ReasoningEngine、Policy V2
  agents/       多 Agent：编排、工厂、预设、打包
  tools/        工具系统（handlers/ + definitions/）与搜索号池
  prompt/       提示词编译与分层拼装
  memory/       三层记忆（存储 / 向量 / 检索）
  skills/       技能加载、注册、市场、i18n
  api/routes/   FastAPI 路由    scheduler/ 定时任务    channels/ IM 适配器
apps/setup-center/  桌面端与 Web 前端（Tauri + React）
skills/             系统技能 77 + 精选外部技能 11
identity/           Agent 身份（SOUL / AGENT / POLICIES / 人格库）
tests/              unit / component / integration / e2e
```

## 架构要点

- **数据根目录**：`src/newsclaw/data_root.py` 是唯一解析入口（`NEWSCLAW_ROOT` → `~/.newsclaw`）。Rust 桌面壳、安装脚本、Python 运行时、插件 bootstrap 都必须用它或复刻同一规则，否则桌面端与 CLI 会各写各的目录。账号凭据类文件用 `resolve_home_root()`（忽略 env 覆盖）：换工作区不该搬走身份。
- **环境变量**：统一使用 `NEWSCLAW_*`。旧产品前缀不再映射。
- **策略矩阵（Policy V2）**：每个工具调用前裁决放行 / 确认 / 拒绝。无人值守任务对 CONFIRM 默认**拒绝**，所以新工具必须声明 `TOOL_CLASSES`（有静态完整性测试）与中断行为（`core/tool_interrupt_behavior.py`，同样有完整性测试），否则会出现"模型决策了、工具从未执行"的静默失败。
- **newsroom 边界**：主线的权威描述（目录契约、不变量 I1–I9、术语表）在 `src/newsclaw/newsroom/__init__.py` 的模块 docstring，并由 `tests/unit/test_newsroom_invariants.py` 的同名测试钉住——**此处不复述细节**，避免双源漂移。一句话边界：Python 只管契约与 apply，采集/写作/复盘由 `ai-news-editor` Agent 执行；`sources.yaml` / `editorial-policy.md` / RULE 记忆只能经人审 `apply_proposal` 落盘。
- **提示词管线**：`prompt/compiler.py` 编译身份文件 → `prompt/builder.py` 分层拼装（身份 → 人格 → 运行时 → 会话规则 → AGENTS.md → 目录 → 记忆 → 用户）。identity 文件改动后需要重新编译。
- **多 Agent 委派**：主 Agent 通过 `delegate_to_agent` / `delegate_parallel` / `spawn_agent` 派生子 Agent；子 Agent 不再持有委派工具（单跳）。`delegate_to_pool` / `delegate_to_role` 不是真实工具，只是 orgs 运行时对模型幻觉调用的纠正别名。委派规则整章按**实际工具集**门控注入。
- **技能加载顺序**：`__builtin__`（随包分发）→ 用户工作区（`settings.skills_path`）→ 项目 `skills/`；同 id 先到先得。

## 提交约定

- **英文**：subject 与 body 都用英文；代码标识符与报错原文可保留原样。
- **描述代码改动，不写计划代号**：不要出现阶段/波次/修复编号（如 `S5-A`、`FIX-S4-1`、`P10`）。
- **body 写"为什么"**：动机、权衡、被否决的方案、防住的回归类型；diff 本身说明"改了什么"。
- **一个逻辑改动一个提交**。

## 已知坑

- Windows PowerShell 5.1 不支持 `&&` / `||` 串联命令；多行提交信息用 `git commit -F <文件>`（临时文件放 `tools-tmp/`）。
- 临时文件（diff、脚本、下载）一律放 `tools-tmp/`（已 gitignore），不要放仓库根目录；不要用 `git add -A`，按路径显式暂存。
- `identity/AGENT.md` 是本项目的 Agent 行为规范，不是行业标准的 `AGENTS.md`，别混淆。
