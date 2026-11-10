## NewsClaw Setup Center

NewsClaw 的桌面端 / Web 前端（Tauri 2 + React 18）：首次安装向导 + 日常工作台。

工作台覆盖：早报工作台（期次 / 条目级反馈 / 提案人审）、知识库 Wiki、对话（多 Agent 委派）、技能、调度任务、审批队列、设置与诊断。

### 目录结构

- `src/`：前端源码（React 18 + TypeScript strict、Vite 6、Tailwind CSS v4、i18next）
- `src-tauri/`：Tauri 2 后端（Rust），负责文件 / 进程 / 网络等本地能力
- `src-tauri/resources/bootstrap/`：桌面端 Python 运行时引导资源（uv / CPython seed / wheel）
- 构建产物：`dist/`（桌面）、`dist-web/`（Web；git-ignore，打包 wheel 时由 `scripts/stage_package_assets.py` 暂存）

### 开发

在 `apps/setup-center/` 下：

```bash
npm install
npm run dev:web                     # 纯 Web 开发（Vite；后端另跑 newsclaw serve）
npm run tauri dev                   # 桌面端（Tauri 自动拉起后端）
npm run tauri:dev:external-backend  # 桌面端连接已经启动的后端
```

后端已通过 `newsclaw serve` 单独启动时优先用 external-backend 模式：它不会复制
`src-tauri/resources/bootstrap`，桌面端只连接 `http://127.0.0.1:18900`。

质量门：

```bash
npx tsc --noEmit -p tsconfig.json
npx vitest run
npm run lint
```

### 说明

- 后端默认端口 **18900**；Tauri 会按工作区配置注入端口。
- `dist-web/` 是构建产物（不提交）；发布打包时在仓库根目录用 `python scripts/stage_package_assets.py` 暂存进 wheel。
- 移动端：`cap:android` / `cap:ios` / `cap:sync` 脚本与 `android/`、`ios/`、`capacitor.config.ts` 只保留在本地（git-ignore），不随仓库发布。
- Windows 图标：`src-tauri/build.rs` 在缺 `src-tauri/icons/icon.ico` 时生成透明占位；正式发布用 `tauri icon` 生成完整图标集。