# AI 动态内容运营评测

`scripts/eval-ai-news-ops.sh` 是本分支的离线、确定性回归评测入口。它不读取 `.env`，不调用真实模型、飞书、公众号或小红书，因此可以在 CI 和本地重复执行。

```bash
./scripts/eval-ai-news-ops.sh
```

固定样本位于 `newsclaw-server/src/test/resources/evals/ai-news/`：

- `verification-policy-cases.json`：官方来源优先、两个独立来源兜底、伪官方域名拦截、未解决冲突阻断。
- `canonical-url-cases.json`：事件 URL 指纹使用的规范化语义。
- `quality-policy-v1.json`：30 个来源、核验、拒绝、引用边界和去重场景；由 `scripts/eval-ai-news-quality.sh` 输出 Precision/Recall/F1、切片和 badcase 工件。详见 [AI 动态质量评测](ai-news-quality-evaluation.md)。
- `live-agent-evidence-v1.json`：30 个冻结合成证据场景；由 `scripts/run-ai-news-live-agent-eval.sh` 驱动真实 SSE、模型和只读 `source_health` 工具，输出质量与运行时工件。详见 [受控在线 Agent 评测](ai-news-live-agent-evaluation.md)。

脚本还汇总以下真实代码路径的回归测试：官方页面只读抓取、长期 Memory 写入门禁、Skill proposal-first 审批、Cron 运行幂等、外部副作用账本，以及 Qwen 主模型到 DeepSeek 备用模型的路由轨迹。

评测输出中的 `verificationPolicyPass` 和 `canonicalUrlPass` 只表示固定策略样本通过率，不能被写成线上新闻发现准确率、模型幻觉率或真实投递成功率。线上指标必须从实际事件、Team Run、投递账本和审批审计记录统计。

质量基准同样遵守这个边界：离线策略分数只能证明规则未回归；受控在线基准只描述固定合成证据包上的真实调用链和一次运行时延；模型、Prompt 或真实 Agent 的业务质量必须使用脱敏、人工标注的采样 trace，经 `scripts/score-ai-news-traces.sh` 用相同评分器计算。
