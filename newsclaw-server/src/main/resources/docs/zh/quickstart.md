# 快速开始

通过 Docker 启动 NewsClaw 的 AI 动态内容运营主线。开发环境和生产部署都以
.env 为配置入口；真实密钥不进入数据库 seed，也不进入 Git。

## 1. 获取代码与配置

~~~bash
git clone https://github.com/LittleSongxx/NewsClaw.git
cd NewsClaw
cp .env.example .env
chmod 600 .env
~~~

在 .env 中先替换以下占位值：

- DB_PASSWORD、DB_ADMIN_PASSWORD
- JWT_SECRET
- NEWSCLAW_SETTING_KEY、NEWSCLAW_ENCRYPT_KEY
- SEARXNG_SECRET

这些值应稳定保存。尤其是两类加密 Key 丢失后，已有的加密设置或 Skill Secret
无法自动恢复。

## 2. 配置最小模型链

主线推荐：

~~~dotenv
NEWSCLAW_PRIMARY_MODEL_PROVIDER=bailian-team
NEWSCLAW_PRIMARY_MODEL=qwen3.7-plus
NEWSCLAW_FALLBACK_MODEL_CHAIN=deepseek::deepseek-v4-flash

BAILIAN_API_KEY=...
DEEPSEEK_API_KEY=...
~~~

BAILIAN_API_KEY 与 DASHSCOPE_API_KEY 可作为百炼主模型的凭证别名。若暂时
只配置一家模型，可将备用链留空；不要填假的 Key。

内置 SearXNG 可无 Key 搜索。Serper、Tavily 与 GitHub Token 是可选的检索增强，
不属于首次启动的硬依赖。

## 3. 配置飞书

主线默认使用飞书长连接：

~~~dotenv
FEISHU_APP_ID=cli_xxx
FEISHU_APP_SECRET=xxx
FEISHU_CONNECTION_MODE=websocket
~~~

还需要在工作台的「渠道」中创建并启用飞书渠道。机器人能接收消息，不等于定时
主动推送一定有目标：每日雷达依赖有效的最近会话目标；投递失败会被记录，但不影响
事件和证据落库。

小红书不需要 API Key。当前产物是卡片预览与 ZIP 素材包，由运营人员在创作中心
人工上传和发布。

## 4. 启动与健康检查

~~~bash
docker compose config
docker compose up -d --build
docker compose ps
curl -fsS http://localhost:18080/actuator/health
~~~

预期健康接口返回 UP。访问 <http://localhost:18080/showcase> 查看静态作品集；
控制台使用一次性 `NEWSCLAW_BOOTSTRAP_PASSWORD` 登录，登录后先改密码。

docker-compose.yml 的 Compose 项目名为 newsclaw，以保持已验证的容器、网络与
数据卷命名；这不是产品名。产品对外名称为 NewsClaw。

## 5. 跑通一个人工确认的闭环

1. 在飞书对机器人发送：“请汇总今天 DeepSeek、OpenAI 和宇树科技的最新动态”。
2. 在 AI 动态工作台检查候选事件、官方来源、claim 和冲突状态。
3. 确认选题，创建 Team Run。
4. 等待研究、核验、内容和视觉任务完成，审核产物。
5. 生成小红书预览与 ZIP 素材包；人工上传发布。

这个流程的可复现证据是事件 ID、Wiki 证据页 ID、Team Run ID、审批审计和交付物。
不要用离线测试结果代替真实平台投递验证。

## 6. 离线回归

~~~bash
./scripts/eval-ai-news-ops.sh
~~~

该脚本不读取 .env，不请求真实模型或平台，适用于 CI 与本地策略回归。详细的
指标边界见 [AI 动态内容运营](./ai-news-ops)。

## 常见问题

- Compose 直接退出：通常是 .env 中必填的数据库或加密变量仍是空值。
- 模型没有输出：检查 Provider Key、模型目录行及 docker compose logs -f newsclaw-server。
- 飞书未收到定时消息：检查渠道启用状态、长连接日志和最近会话目标；事件入库不受投递失败影响。
- 小红书没有自动发布：这是当前设计边界，不是配置缺失。系统只交付预览和素材 ZIP。

需要更详细的运行、备份和故障排查说明时，继续阅读 [Docker 部署](./docker-deploy)。
