"""早报管线的全部提示词。

两条定时任务的 prompt 由本模块的工厂函数按当前工作区路径动态构建——
路径、cron、Obsidian 配置都随环境变化，静态常量会悄悄失效。

进化载体的分层约定（复盘任务只改前者，管线两者都读）::

    data/newsroom/editorial-policy.md   编辑方针（品味：选题偏好/文风/结构模板，
                                        每周复盘依据反馈迭代——自进化的核心落点）
    skills/newsroom-editor/SKILL.md     操作手册（流程：怎么跑管线，保持稳定）

prompt 版本号 ``PROMPT_VERSION`` 递增时，seed 模块会在下次启动时把已存在
任务的 prompt 刷新到新版（v19：注入块印记本期 issue_date，跨午夜运行的
目录名以注入为准，不要用 Agent 自己的「今天」）
（用户在 GUI 里改排期不受影响，见 seed 模块说明）。
"""

from __future__ import annotations

from datetime import date

from newsclaw.newsroom.config import NewsroomConfig, load_config
from newsclaw.newsroom.contract import newsroom_root
from newsclaw.newsroom.editorial import load_editorial_policy
from newsclaw.newsroom.items import format_seen_items_for_prompt
from newsclaw.newsroom.sources import load_sources, sources_path

PROMPT_VERSION = 19

#: 每日任务运行时注入块的起止标记。播种缓存的 prompt 可能含旧块，
#: 调度触发时会剥掉再拼当期 sources / 方针。
INJECTION_BEGIN = "【当期强制上下文 · 代码注入 · 开始】"
INJECTION_END = "【当期强制上下文 · 代码注入 · 结束】"

_RUBRIC = """\
评分维度（每项 1–5 分 + 一句话理由，写进 manifest.scores）：
- source_hit 信源命中：素材是否主要来自 sources.yaml、覆盖当日各主题的重要事件；
- dedup 去重质量：与近几期重复的条目是否已剔除，本期内部无同事件重复；
- headline 标题吸引力：两个平台稿的标题抓人、信息密度高、不标题党；
- structure 结构可读性：分层清晰、节奏适合平台调性、长度合规。
打分要诚实：4 分代表"可直接发布"，5 分留给明显亮点；达不到就给低分并把
短板写进理由——低分理由是每周复盘最值钱的原料。"""

_MANIFEST_SCHEMA = """\
{
  "issue_date": "YYYY-MM-DD（取注入块的 issue_date，与目录名一致）",
  "title": "本期标题（如「AI 早报 #12｜xxx」）",
  "status": "ready（契约机验：信源⊆清单 + 三产物过结构 + items 账本与稿件 URL 对齐且不与近窗重复；否则 partial）",
  "sources_used": ["实际用到的信源 name 列表，与 sources.yaml 对账"],
  "items": [
    {
      "title": "入选标题",
      "url": "https://...（必须出现在三份稿里）",
      "source_name": "sources.yaml 里的 name",
      "one_liner": "一句话要点"
    }
  ],
  "wiki_entries": ["本期更新/新建的本地 Wiki 页面相对路径（如 主题/大模型.md）"],
  "feishu_doc_url": "飞书云文档归档链接（第 6 步产出；失败则空字符串）",
  "scores": {
    "source_hit": {"score": 1-5, "rationale": "一句话"},
    "dedup":      {"score": 1-5, "rationale": "一句话"},
    "headline":   {"score": 1-5, "rationale": "一句话"},
    "structure":  {"score": 1-5, "rationale": "一句话"}
  },
  "generator": "ai-news-editor"
}"""


def _wiki_step_block(cfg: NewsroomConfig) -> str:
    """第 3 步：有 vault 才写 Wiki，否则明确跳过，避免模型仍去调 wiki_upsert。"""
    if not cfg.wiki_enabled():
        return """\
【第 3 步 · 跳过本地 Wiki】
obsidian_vault 未配置，**不要**调用 wiki_upsert，也不要落到默认 data/wiki。
manifest.wiki_entries 写空数组即可。飞书云文档归档（第 6 步）与 Wiki 相互独立，照常执行。"""
    return """\
【第 3 步 · 沉淀到本地 Wiki（知识库）】
用 **wiki_upsert** 把本期要点写成**本地知识库页面**（目录即配置的 Obsidian 库；
页面结构由工具统一维护，你只提供要点与来源）：
- 主题页（kind=topic）：从 sources.yaml 的 topics 里挑**本期涉及的**建/更新，
  常用的有「大模型」「公司动态」「开源项目」「研究与论文」「政策与监管」；
- 公司页（kind=company）：只给**本期反复出现的头部公司**开页（如 OpenAI、谷歌、
  英伟达）；只出现一次的公司不要开页，避免知识库碎片化；
- 每页调用一次：entries 每条给 text（一句话要点）+ source（可点开的原始链接）；
  links 填相关页面名（会写成 [[双链]]）；summary 给该页当日一句话概述；
- day 用运行当天日期；**同一天重复写会替换该日章节**，所以重跑是安全的；
- 把写入的页面相对路径收集起来写进 manifest.wiki_entries（本地页面数组）；
  某页当天若无新增内容就不要调用它。"""


def build_daily_injection_block() -> str:
    """拼当期信源摘要 + 方针全文。每次触发现读磁盘，不依赖播种缓存。"""
    book = load_sources()
    lines = [
        INJECTION_BEGIN,
        f"本期 issue_date：{date.today().isoformat()}（期次目录名与 manifest.issue_date "
        "以此为准，不要用你自己的「今天」——跨午夜运行时两者可能不同）",
        "以下由 Python 在每次任务启动时读取磁盘现态注入，优先于你对 read_file",
        "的记忆。子 Agent 看不到本段；delegate_parallel 的每个任务包必须自带",
        "当期信源摘要（name / kind / query 或 url / weight）以及本段的已见 URL，",
        "禁止写「按上面的清单」「如上所述」「见上文」。",
        "",
        "## 当期信源",
    ]
    for source in book.sources:
        key = "query" if source.kind == "search" else "url"
        extra = source.query if source.kind == "search" else source.url
        topics = ",".join(source.topics)
        lines.append(
            f"- name={source.name} | kind={source.kind} | weight={source.weight} | "
            f"{key}={extra} | topics={topics}"
        )
    if not book.sources:
        lines.append("- （空清单）")
    excluded = ", ".join(book.excluded_keywords) if book.excluded_keywords else "（无）"
    lines.append(f"excluded_keywords: {excluded}")
    lines.append(f"topics: {', '.join(book.topics)}")
    lines.append("")
    cfg = load_config()
    today = date.today().isoformat()
    lines.append(f"## 近 {cfg.issue_history_days} 天已见 URL（代码注入，精确去重用）")
    lines.append(
        format_seen_items_for_prompt(before_date=today, days=cfg.issue_history_days)
    )
    lines.append("")
    lines.append("## 编辑方针全文")
    policy_text = load_editorial_policy().to_text().strip()
    lines.append(policy_text if policy_text else "（尚未制定，按默认品味）")
    lines.append(INJECTION_END)
    return "\n".join(lines)


def strip_runtime_injection(prompt: str) -> str:
    """去掉旧注入块，避免播种缓存与运行时块叠两份。"""
    begin = prompt.find(INJECTION_BEGIN)
    if begin < 0:
        return prompt.rstrip()
    end = prompt.find(INJECTION_END, begin)
    if end < 0:
        return prompt[:begin].rstrip()
    after = prompt[end + len(INJECTION_END) :].strip()
    before = prompt[:begin].rstrip()
    if after:
        return f"{before}\n\n{after}".rstrip()
    return before


def with_runtime_injection(prompt: str) -> str:
    """剥掉旧注入块后，把当期 sources / 方针拼到任务消息最前。"""
    base = strip_runtime_injection(prompt)
    return f"{build_daily_injection_block()}\n\n{base}"


def build_daily_prompt(config: NewsroomConfig | None = None) -> str:
    """构建每日管线任务 prompt（含当期注入块；调度触发时还会再拼一次）。"""
    cfg = config or load_config()
    root = newsroom_root()
    wiki_step = _wiki_step_block(cfg)
    body = f"""\
你是「AI 早报」主线的值班编辑。按以下流程产出今天的早报。产物**只推送给 owner
本人审核**（飞书私聊，第 5 步），不向任何公开平台发布、不群发。

工作目录（全部使用绝对路径）：{root}

【第 0 步 · 读取上下文】
- 当期信源摘要与方针全文已由代码注入（见消息最前的「当期强制上下文」）。
  以注入块为准，不要用过期的 read_file 印象覆盖它。
- 信源清单磁盘路径：{sources_path()}（weight 决定采集预算，excluded_keywords
  命中即丢）。方针已注入，不要再 read_file editorial-policy.md。
- 可选文件先 list_directory 确认存在再读，不存在就跳过——不要对缺失路径调用
  read_file：
  · feedback-export.json —— 往期人工反馈（空文件或缺失都视为「还没有反馈」）。
  · issues/今天/ 下的产物 —— 还没写出来就不要读。
- 调 search_memory 检索用户关于早报/内容偏好的记忆（关键词如 早报/选题/文风）。
- 近 {cfg.issue_history_days} 天已见 URL 已在注入块里，不要再靠翻目录「回忆」去重。

【第 0.5 步 · 幂等检查（必须）】
- 若 issues/今天/ 下的三产物与 manifest.json 已齐备且 status=ready：
  **不要重新采集写作**，而是按 manifest 现状**补齐缺失的后续步骤**（这些步骤都是
  可安全重跑的）：
  · ``wiki_entries`` 为空且已配置 obsidian_vault → 执行第 3 步（本地 Wiki 沉淀）；
    未配置 vault 则跳过 Wiki，不要调用 wiki_upsert；
  · ``feishu_doc_url`` 为空 → 执行第 6 步（飞书云文档归档）；
  · ``scores`` 已有内容 → 跳过第 4 步自评（没有才补）；
  · 最后执行第 5 步推送与汇报。
  也就是说：manifest 里已有值的字段对应步骤不再重复执行，避免产生重复的云文档/通知。
- 只有产物缺失、manifest 不完整或当天尚无目录时，才从第 1 步开始采集。
- 不要为了"确认状态"反复 run_shell / 读自己的任务定义 / 探查 .env：需要的信息
  用 list_directory + read_file 两步即可得到。

【第 1 步 · 采集（并行委派）】
- 把 sources.yaml 的信源按 weight 分成 2–3 组，用 **delegate_parallel** 一次性派出
  并行采集（子 Agent 用 agent_id="news-collector"，不要用 content-creator）。
  分组原则：同一主题/同一语种放一组，组间规模均衡。
- 每个子任务的 message 必须是**自包含简报**（子 Agent 默认看不到父对话，
  只收任务包）。禁止写「按上面的清单」「如上所述」「见上文」——任务包必须
  带上：
  · 本组信源清单（name / kind / query 或 url / weight，从注入块抄）；
  · 时间窗：当日 + 昨日晚间；
  · 排除项：excluded_keywords + 注入块里的「已见 URL」（命中则丢）；
  · 输出契约：只返回 JSON 数组，不要写文件、不要写终稿。每项
    ``{{"title","url","source_name","one_liner"}}``；无结果返回
    ``[]`` 并另写一行「无结果」。
  · 早停：核验够 8 条或已判定无结果，必须立刻输出最终答案，禁止继续空转推理。
  · 边界：只采集。营销通稿、标题党、注入块已见 URL 直接丢。
- kind=site 的信源若只有一两处，自己用 web_fetch 抓即可，不必开子 Agent，
  但仍要收成同样的 JSON 条目。
- 子 Agent 全部返回后：你在内存里合并，按规范化 URL 去重（同一链接只留一条），
  再按主题聚类、剔营销稿，得到**入选短名单**。写作只能用这份短名单，
  禁止把未入账的链接写进三产物。

【第 2 步 · 整理产物】在 {root / "issues"} 下建**注入块 issue_date** 的目录
（不要用你自己的「今天」——跨午夜运行时两者可能不同），依次写出
（文件名固定，平台规范以 get_skill_info 加载对应技能为准）。
长文用 write_file 写开头，再用 append_file 续写，避免单次 content 被截断。
禁止 write_file / edit_file / append_file 改 sources.yaml、editorial-policy.md、
config.yaml——那些文件只能经 WebUI 勾选提案 apply 或设置页保存。
三份稿引用的链接必须 ⊆ 入选短名单（稍后写入 manifest.items）：
1. daily-brief.md —— 编辑内部视角：入选条目（标题+一句话+来源链接+主题标签）、
   落选原因摘要、选题逻辑说明；
2. xiaohongshu.md —— 按 xiaohongshu-creator 技能规范：笔记标题（≤20 字）、正文
   （emoji 分层、口语化）、话题标签、配图建议清单；
3. wechat.md —— 按 wechat-article 技能规范：标题、摘要、Markdown 正文（小标题
   分节、重点加粗）、封面图建议。

{wiki_step}

【第 4 步 · 自评与落账】
- 按下方评分表自评；
- 最后写 {root / "issues"}/YYYY-MM-DD/manifest.json。``status=ready`` 由
  Python 契约函数机验，不是你自评说了算：sources_used 必须非空且每个名字
  ∈ 当期 sources.yaml；三份产物必须存在、去空白后够长、且含 http 链接或
  明确写「无结果」；有外链时 ``items`` 必须非空，每条 url 必须出现在三份
  稿里，且不得与注入块已见 URL 重复。``scores`` 只作参考。
  预算耗尽（exit_reason=budget_exceeded）时只能写 ``partial``，禁止 ready。
  schema 严格如下，UTF-8、ensure_ascii=False：
{_MANIFEST_SCHEMA}
- 用 add_memory 记一条 experience 记忆（scope=global）：本期选题/写作中
  可复用的经验或踩的坑，一句话即可。

【第 5 步 · 推送 owner（飞书私聊）】
仅当当天 manifest ``status=ready`` 之后，才用 **deliver_artifacts** 推送。
未 ready（partial / 契约失败）禁止投递——工具也会拒绝。
三产物与 ready 的 manifest 写完后，把产物推送给 owner 的飞书私聊：
- artifacts 传三个文件（daily-brief.md / xiaohongshu.md / wechat.md）各自的
  {{"type": "file", "path": <绝对路径>, "caption": <一句话说明>}}；
- target_channel="feishu"，prefer_chat_type="private"；
- 如果返回的 receipt 里有 failed / missing_context（说明 owner 尚未与机器人建立会话），
  不要重试、不要改用其它通道，在最终回复里如实说明"推送未送达+原因"即可。

【第 6 步 · 归档到飞书云文档（对外分享；与第 3 步的本地 Wiki 相互独立）】
用 **feishu_doc** 把本期内容沉淀成云文档，供日后检索与对外分享：
- action="create"，**title 必须"日期在最前"**，固定格式：
  `<运行当天日期>｜AI 早报 #<期号>｜<一句话要点，≤30 字>`
  例：`2026-09-17｜AI 早报 #1｜OpenAI 万亿估值、GPT-5.5 下线、量子 AI 开源`
  （知识库侧边栏会截断长标题，日期前置才能一眼看清是哪一期；期号可取本期标题里的
  #N，无法确定时省略该段）
- **markdown 第一行固定为归档元信息**，其后接公众号稿（wechat.md）全文：
  `> 归档时间：<运行当天日期>（NewsClaw AI 早报管线自动生成）`
- 返回的 url 写入当天 manifest.json 的 ``feishu_doc_url`` 字段
  （**不要**写进 wiki_entries——那是本地 Wiki 页面路径，两者语义不同）；
- 若返回 ``ok=false``（例如应用未开通 wiki:wiki 权限），不要重试、不要换手段，
  在最终回复里如实说明"归档未完成 + 原因"，其余流程照常收尾。

- 最终回复只需简短交代：本期标题、入选条数、三个产物路径、自评总分、
  本地 Wiki 更新页数、飞书推送结果、云文档归档链接（或失败原因）。"""
    return with_runtime_injection(body)


_REVIEW_JSON_SCHEMA = """\
{
  "version": 1,
  "created_at": "ISO8601",
  "sources": [
    {
      "id": "src-1",
      "action": "add | update | remove | set_weight | set_query | add_excluded",
      "name": "与 sources.yaml 的 name 对齐；add_excluded 可空",
      "kind": "search | site",
      "query": "",
      "url": "",
      "weight": 3,
      "topics": [],
      "note": "",
      "keyword": "仅 add_excluded：写入清单级 excluded_keywords",
      "evidence": {
        "issue_date": "YYYY-MM-DD",
        "dimension": "source_hit | dedup | headline | structure | feedback",
        "note": "一句话"
      }
    }
  ],
  "policy_bullets": [
    {
      "id": "stable-id",
      "action": "add | replace | remove",
      "text": "一条可执行规则（禁止整文件重写）",
      "evidence": {"issue_date": "YYYY-MM-DD", "dimension": "headline", "note": ""}
    }
  ],
  "memory_rule": {
    "text": "可选一条长期硬规则；人未勾选则不会写入记忆",
    "evidence": {"issue_date": "YYYY-MM-DD", "dimension": "feedback", "note": ""}
  }
}"""


def build_review_prompt(config: NewsroomConfig | None = None) -> str:
    """构建每周复盘任务 prompt。只写提案，不落盘信源/方针/记忆。"""
    root = newsroom_root()
    return f"""\
你是「AI 早报」主线的周刊复盘编辑。目标：让早报越做越准、越写越好看。
不要产出面向读者的内容，只产出**结构化提案**与复盘报告。
真正改信源/方针必须等人在 WebUI 勾选后由代码 apply，你自己 write_file
改 {sources_path()} 或整份覆盖 {root / "editorial-policy.md"} 会被拒绝。

工作目录：{root}

【第 1 步 · 收集证据】
- 列出 issues/ 下最近 7 期有 manifest.json 的期次，读取全部 manifest：
  关注 scores 各维度趋势、sources_used 与 sources.yaml 的对账（哪些信源
  从未被用、哪些高频却低分）；
- 读 {root / "feedback-export.json"}（若存在）：人工点赞/点踩与短评，
  逐条对应到当期的选题构成（主题/信源/文风）；
- 读 {sources_path()} 与 {root / "editorial-policy.md"}（若存在）。

【第 2 步 · 只写提案，不落盘】（每条 op 必须带 evidence：issue_date +
分数维度或 feedback）
1. 信源：**禁止直接改** {sources_path()}。把增删/改 weight/query/
   excluded_keywords 的提案同时写入：
   · {root / "issues"}/review-proposal.md —— 给人读的摘要；
   · {root / "issues"}/review-proposal.json —— 给代码 apply 的结构化提案
     （schema 如下，UTF-8、ensure_ascii=False）。坏 JSON 不会进入 pending。
     evidence 机验：issue_date 必须是 YYYY-MM-DD；dimension 只能取
     source_hit / dedup / headline / structure / feedback——写错任一处，
     整份提案判 invalid。
   自评 scores 只作参考，不能当改信源的闭环信号。
2. 方针：**禁止**整文件重写 {root / "editorial-policy.md"}。只在 JSON 的
   policy_bullets 里按 id 提交 add / replace / remove（全文上限 30 条）。
   人一键确认后由代码追加/替换指定 id。
3. 记忆：**不要**调用 add_memory。若有一条长期硬规则，写入 JSON 的
   memory_rule；人未勾选则不会写入记忆。

review-proposal.json schema::
{_REVIEW_JSON_SCHEMA}

【第 3 步 · 复盘报告】写入 {root / "reviews"}/YYYY-MM-DD.md（当日日期）：
- 近 7 期各维度分数趋势表（自评只作参考，ready 由契约函数决定）；
- 反馈与选题的对应分析；
- 本轮提案（review-proposal.md / .json）摘要与预期效果（尚未 apply）；
- 下周观察点（给下次复盘的问题清单）。

最终回复：两个提案路径 + 「待人审 apply」+ 报告路径。"""


#: 预设 Agent 的 custom_prompt（身份层，简短；具体流程都在任务 prompt 里）
EDITOR_DIRECTIVE = (
    "你是「AI 早报」主线的值班编辑，专注 AI 科技圈每日资讯的采集、筛选与多平台"
    "内容改写。你的产出物供人工审核后手动发布：小红书稿要口语化有钩子，公众号稿"
    "要结构清晰有信息密度，日报总览要交代选题逻辑。一切数字与事实必须有来源链接，"
    "不确定的不写。采集员用 news-collector，不要派 content-creator 去搜。"
)

COLLECTOR_DIRECTIVE = (
    "你是 AI 早报的采集员。只搜索和打开链接，把结果收成 JSON 条目交差。"
    "不要写小红书/公众号终稿，不要写文件，不要再委派别人。"
    "营销通稿、标题党和任务包里的已见 URL 直接丢弃。"
    "核验够数或确认无结果后立刻输出，禁止无工具调用的空转推理。"
)
