---
name: newsclaw/skills@newsroom-editor
description: "AI 早报主线操作手册：每日采集 AI 圈新闻，产出日报总览/小红书/公众号三产物，沉淀 Obsidian Wiki 并自评落账。由定时任务 newsroom_daily_pipeline 与 newsroom_weekly_review 驱动，产物供人工审核后手动发布。"
license: MIT
metadata:
  author: openakita
  version: "1.0.0"
---

# AI 早报编辑手册

本技能是「AI 早报」主线的**操作手册**（流程稳定层）。选题偏好、文风等
**可进化内容**在 `data/newsroom/editorial-policy.md`，由每周复盘任务迭代——
两者冲突时以方针文件为准。

## 主线资产（data/newsroom/）

| 文件 / 目录 | 作用 |
|---|---|
| `sources.yaml` | 信源清单：search（query 走搜索）/ site（url 直采），weight 决定采集预算 |
| `editorial-policy.md` | 编辑方针（进化层）：选题偏好、标题与结构模板、平台差异要点 |
| `config.yaml` | 运行配置：开关、两条 cron、Obsidian 库路径 |
| `issues/YYYY-MM-DD/` | 每期产物：`daily-brief.md` / `xiaohongshu.md` / `wechat.md` + `manifest.json` |
| `feedback-export.json` | 人工反馈只读快照（点赞/点踩 + 短评） |
| `reviews/YYYY-MM-DD.md` | 每周复盘报告 |

## 每日管线（定时任务 prompt 已内置流程，此处为要点备忘）

1. **读上下文**：sources.yaml → editorial-policy.md（若存在）→ feedback-export.json
   （若存在）→ search_memory 用户偏好 → 近几期 manifest 做去重底账；
2. **采集**：按 weight 分组后用 `delegate_parallel` 并行采集（子 Agent 用 content-creator，
   简报必须自包含）；零散 site 源自己用 web_fetch 抓；只取当日/昨日信息；
3. **三产物**（平台规范用 get_skill_info 加载 xiaohongshu-creator / wechat-article）：
   - `daily-brief.md`：编辑内部视角——入选条目（标题+一句话+来源链接+主题）、
     落选原因、选题逻辑；
   - `xiaohongshu.md`：标题 ≤20 字、emoji 分层正文、话题标签、配图建议；
   - `wechat.md`：标题、摘要、Markdown 正文（小标题分节）、封面建议；
4. **Wiki 沉淀**（config.yaml 配置了 obsidian_vault 时）：在库内「AI早报」目录
   按主题/公司建原子页，追加当日要点，wikilink 互链，维护 MOC 索引页；
5. **自评与落账**：四个维度（source_hit / dedup / headline / structure）各 1–5
   分 + 理由写入 manifest.scores；写一条 experience 记忆。

## 硬规则

- 一切数字与事实必须带来源链接，不确定的不写；
- 产物用 `deliver_artifacts` 推送给 owner 的**飞书私聊**（target_channel=feishu,
  prefer_chat_type=private）供其审核；不向任何公开平台发布、不群发、不发群聊；
- 云文档归档（`feishu_doc`）三约定：① **标题日期前置**，格式
  `YYYY-MM-DD｜AI 早报 #N｜一句话要点（≤30 字）`；② 正文**首行**为
  `> 归档时间：YYYY-MM-DD（NewsClaw AI 早报管线自动生成）`；③ 归档链接写入
  当天 `manifest.wiki_entries`；
- manifest.json 最后写，三产物齐备才置 `status: ready`；
- 不改写历史期次目录与 Wiki 历史段落。
