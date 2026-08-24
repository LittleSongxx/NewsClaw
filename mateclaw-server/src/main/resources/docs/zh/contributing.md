# 贡献与开发

NewsClaw 的目标是维护 AI 动态内容运营闭环，而不是继续扩大为通用平台。新增代码应
优先增强事件发现、证据核验、Team Run、飞书、小红书、Memory、Skill 治理或运行
可靠性；无关场景不要默认暴露到导航、seed 或 Agent 工具箱。

## 获取代码

~~~bash
git clone https://github.com/LittleSongxx/NewsClaw.git
cd NewsClaw
git switch -c feat/your-change
~~~

本仓库的对外主分支是 main。不要配置或引用原项目的 upstream remote。

## 本地开发

~~~bash
# 后端
cd mateclaw-server
mvn spring-boot:run

# 另一个终端：前端
cd mateclaw-ui
corepack pnpm install
corepack pnpm dev
~~~

后端默认监听 18088，前端开发服务器默认监听 5173。Docker 集成环境使用 18080。

## 变更原则

- 事件领域要保持 workspace 隔离；URL/hash 去重不能跨 workspace 泄漏数据。
- 事实、偏好和流程要分层：证据留在事件/Wiki，稳定偏好才进入长期 Memory，方法模板才是 Skill。
- 未核验或冲突事件不得进入对外交付；外部副作用需有幂等键与审计记录。
- Reflection 和 Routine Mining 只能生成待审 proposal，不能静默改生产 Skill。
- 真实小红书发布、账号登录、验证码和平台风控不属于当前自动化范围。
- 新配置项必须加入 .env.example 的中文分区说明，不得把真实 Key 写入测试、seed 或文档。

## 验证

至少运行与改动相关的测试。AI 动态主线的确定性回归入口：

~~~bash
./scripts/eval-ai-news-ops.sh
~~~

涉及前端时：

~~~bash
cd mateclaw-ui
corepack pnpm typecheck
corepack pnpm test
corepack pnpm build
~~~

离线评测不能替代真实飞书和小红书交付验证。需要说明线上行为时，提供可脱敏的
事件 ID、证据页 ID、Team Run ID、审批记录与交付物引用。

## 提交

使用小而完整的 Conventional Commit，例如：

~~~text
feat(ai-news): block conflicted events from delivery
fix(feishu): preserve completed progress cards on stream updates
test(memory): cover news-body write rejection
docs(newsclaw): document the manual Xiaohongshu boundary
~~~

提交前确认 .env、数据库导出、生成素材、日志和本地 node_modules 未被暂存。
Apache 2.0 许可证、既有版权声明和第三方许可证文件必须保留。
