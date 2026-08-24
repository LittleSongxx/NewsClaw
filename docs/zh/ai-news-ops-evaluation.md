# AI 动态内容运营评测

`scripts/eval-ai-news-ops.sh` 是本分支的离线、确定性回归评测入口。它不读取 `.env`，不调用真实模型、飞书、公众号或小红书，因此可以在 CI 和本地重复执行。

```bash
./scripts/eval-ai-news-ops.sh
```

固定样本位于 `newsclaw-server/src/test/resources/evals/ai-news/`：

- `verification-policy-cases.json`：官方来源优先、两个独立来源兜底、伪官方域名拦截、未解决冲突阻断。
- `canonical-url-cases.json`：事件 URL 指纹使用的规范化语义。

脚本还汇总以下真实代码路径的回归测试：官方页面只读抓取、长期 Memory 写入门禁、Skill proposal-first 审批、Cron 运行幂等、外部副作用账本，以及 Qwen 主模型到 DeepSeek 备用模型的路由轨迹。

评测输出中的 `verificationPolicyPass` 和 `canonicalUrlPass` 只表示固定策略样本通过率，不能被写成线上新闻发现准确率、模型幻觉率或真实投递成功率。线上指标必须从实际事件、Team Run、投递账本和审批审计记录统计。
