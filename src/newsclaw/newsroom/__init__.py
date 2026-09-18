"""AI 早报主线（newsroom）。

在调度器上运行两条长期定时任务::

    newsroom_daily_pipeline   每日：采集 AI 圈新闻 → 三产物 → Wiki 沉淀 → 自评
    newsroom_weekly_review    每周：复盘近 7 期自评与反馈 → 结构化提案（人审 apply）

全部状态收敛在工作区 ``data/newsroom/`` 目录::

    data/newsroom/
        config.yaml          运行配置（开关、cron、Obsidian 库路径）
        sources.yaml         信源清单（人审 apply 后落盘；复盘只写提案）
        editorial-policy.md  编辑方针（按 bullet id 追加/替换，禁止整文件重写）
        issues/YYYY-MM-DD/   每期产物目录（manifest.json + 三个 Markdown 产物）
        issues/review-proposal.json  最新结构化提案（人审后 apply）
        audit/apply.jsonl    提案 apply / reject 审计
        feedback.db          逐期人工反馈（点赞/点踩 + 短评）
        feedback-export.json 反馈的只读导出，供管线 / 复盘任务用 read_file 消费

设计原则：管线主体由 ai-news-editor Agent 的 ReAct 循环执行，Python 只负责
契约（目录结构、manifest、任务播种、反馈存储、API、提案 apply）。自进化的
落点是「复盘产出可被代码应用的结构化提案 → apply 后下次每日管线强制读到
新状态」，不是让周复盘直接 write_file(sources.yaml)。

模块速览::

    contract   期次目录契约与 manifest 读写校验（唯一数据契约）
    config     config.yaml 读写（运行开关 / cron / Obsidian 库路径）
    sources    信源清单模型与 YAML 读写
    editorial  编辑方针加载 / 原子保存
    proposal   结构化提案解析、人审 apply、审计
    prompts    管线 prompt、复盘 prompt、运行时注入块
    seed       定时任务幂等播种 + 等待调度器的后台钩子
    feedback   逐期反馈的 aiosqlite 存储与只读导出
    status     主线健康总览（只读聚合，API 直出）
    delivery   投递门：未 ready 的产物不准走 deliver_artifacts

不变量（与 tests/unit/test_newsroom_invariants.py 的 TestI1–TestI9 一一
对应，两边必须一起改）：

- I1 契约唯一写入口：manifest 只能经契约函数落盘；ready 必过机验（结构、
  账本 ⊇⊆、URL/标题去重窗口、信源对账、无负反馈）。
- I2 投递门：未 ready 不可投递；送达必留 delivered_at；已出门的期次不被
  预算降级追改。
- I3 反馈否决：负反馈存续期间该期回不到 ready，解除只能由人改评；编辑
  Agent 重写 manifest 洗白不了 feedback。
- I4 去重池：窗口内一切状态（ready/partial/rejected/无 manifest 现场）的
  URL 都算已见；ready 不得撞 URL，也不得撞规范化标题。
- I5 进化载体单写入口：sources.yaml / editorial-policy.md / RULE 记忆只能
  经 apply_proposal 落盘——勾选制、快照回滚、jsonl 审计。
- I6 注入 = 磁盘现态：每日触发现读 sources / 方针 / 已见清单；载体损坏
  当日中止，而不是静默用默认值跑完。
- I7 单任务互斥：同一定时任务并发执行被调度器执行锁（O_EXCL + PID +
  心跳租约）挡住，崩溃后孤儿锁有人收尸。
- I8 质量下限只作用于写盘：条目数 / 信源多样性 / 标题撞窗 / 平台稿格式钉
  在 write_manifest 时严格机验；读历史期次按当期标准宽松加载，不翻旧账。
- I9 条目反馈仅咨询：只进复盘证据，不参与 ready 判定、不改变期次状态。

术语表::

    期次（issue）       一天一份的产物集合，目录 issues/YYYY-MM-DD/
    产物（artifact）    三份平台稿 daily-brief / xiaohongshu / wechat
    账本（items）       manifest.items 的入选素材清单；稿中链接必须 ⊆ 账本，
                        且每份稿至少引用一条账本 URL
    注入块（injection） 每次触发现读磁盘拼进任务消息最前的当期上下文
                        （issue_date / 信源 / 已见 URL / 方针全文），
                        以它为准而不是模型记忆
    提案（proposal）    周复盘产出的结构化 ops（信源/方针/记忆规则），
                        每条必带 evidence
    apply               人勾选提案 ops 后由代码落盘的唯一通道（回滚 + 审计）
    就绪门（gate）      任务正常结束后检查当期是否 ready，未达即向 owner
                        告警（silent 不豁免）
"""

from .contract import (
    ARTIFACT_DAILY_BRIEF,
    ARTIFACT_WECHAT,
    ARTIFACT_XIAOHONGSHU,
    MANIFEST_FILENAME,
    IssueManifest,
    ScoreEntry,
    list_issues,
    newsroom_root,
    read_manifest,
    write_manifest,
)
from .items import NewsItem
from .seed import (
    DAILY_TASK_ID,
    REVIEW_TASK_ID,
    ensure_newsroom_tasks,
    start_background_seeding,
)

__all__ = [
    "ARTIFACT_DAILY_BRIEF",
    "ARTIFACT_WECHAT",
    "ARTIFACT_XIAOHONGSHU",
    "DAILY_TASK_ID",
    "MANIFEST_FILENAME",
    "REVIEW_TASK_ID",
    "IssueManifest",
    "NewsItem",
    "ScoreEntry",
    "ensure_newsroom_tasks",
    "list_issues",
    "newsroom_root",
    "read_manifest",
    "start_background_seeding",
    "write_manifest",
]
