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
