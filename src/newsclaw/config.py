"""
NewsClaw 配置模块
"""

import logging
import os
import threading
from pathlib import Path

os.environ.setdefault("NEWSCLAW", "1")


from pydantic import Field, model_validator
from pydantic_settings import BaseSettings

logger = logging.getLogger(__name__)


class Settings(BaseSettings):
    """应用配置"""

    # === HTTP API 网络绑定与访问控制（PR-L1: 默认仅本机, lan_mode 显式开启） ===
    api_host: str = Field(
        default="127.0.0.1",
        description=(
            "HTTP API 绑定地址。默认 127.0.0.1（仅本机访问）；"
            "若需被同网段其他机器访问，请改为 0.0.0.0 并同时开启 api_lan_mode。"
            "环境变量 API_HOST 仍可覆盖此值，但出于安全审计目的建议改在配置里显式设置。"
        ),
    )
    api_port: int = Field(default=18900, description="HTTP API 监听端口")
    api_lan_mode: bool = Field(
        default=False,
        description=(
            "是否暴露到局域网。默认 False=只监听 127.0.0.1。"
            "开启后会自动把 host 改成 0.0.0.0，同时强制要求设置 web_access 密码或 api_token，"
            "否则启动会失败（避免无密码裸奔）。"
        ),
    )
    api_token: str = Field(
        default="",
        description=(
            "API 共享访问令牌（可选）。设置后非本机请求必须在 Authorization: Bearer <token> "
            "或 X-NewsClaw-Token 头里携带它，作为 web_access 密码之外的二次校验。"
            "首次开启 lan_mode 时若未填写会自动生成一个 32 字符 token 并写入 .env。"
        ),
    )

    # === Sprint 14 / v31 Phase A 治根：graceful shutdown bounded timeouts ===
    # 详见 _v31_biz/_phase_a_shutdown_chain.md。
    # v23/v24/v26/v28/v29/v30 共 6 次稳定复现 13~20s 不退；
    # 主因是 MessageGateway.stop() 串行 await 各 IM adapter，wework_ws 等
    # 长链 websocket 没有 timeout 兜底。
    channels_gateway_stop_timeout_s: int = Field(
        default=8,
        ge=1,
        le=60,
        description=(
            "MessageGateway.stop() 单 adapter stop() 的硬超时秒数。"
            "超时即放弃该 adapter 的清理，logger.warning 后继续。"
            "默认 8s（足够 wework_ws/qqbot 走完正常 cancel；超过即视为已 hang）。"
        ),
    )
    channels_ws_force_close_after_s: float = Field(
        default=2.0,
        ge=0.1,
        le=10.0,
        description=(
            "Sprint 17 / v34 P1-A：长链 WS adapter（wework_ws / qqbot）stop() 内部"
            "单个 cooperative await（connection_task / ws.close / webhook.close）的硬超时秒数。"
            "超时后强制走 transport.close() 强关 socket，不再等待 close-frame ack。"
            "默认 2.0s（v33 实测 wework_ws ws.close() ~4s 是当前 IM drain p50 2.71s 头号瓶颈；"
            "改 2s 后预期单 adapter 砍 ~2s）。"
        ),
    )
    shutdown_force_exit_grace_s: int = Field(
        default=15,
        ge=0,
        le=120,
        description=(
            "POST /api/shutdown 收到后，等待 graceful 路径自退的最长秒数。"
            "超时即 os._exit(0) 兜底，避免 Phase A 13~20s 不退导致 Stop-Process 软杀。"
            "0 表示禁用兜底（仅用于诊断；生产不建议）。默认 15s。"
        ),
    )
    shutdown_force_exit_use_threading: bool = Field(
        default=True,
        description=(
            "Sprint 15 / v32 Phase B 修法：True 用 threading.Timer 实现 force-exit watchdog"
            "（不受 uvicorn lifespan teardown 取消影响）；False 回退到 v31 的 asyncio.Task"
            "实现（已知会被 lifespan 提前取消，4/4 PHASEA 轮 armed 但 0/4 fired）。"
            "仅当 threading 路径在生产环境出现回归时再切到 False，详见"
            "_v32_biz/_phase_b_watchdog_redesign.md。"
        ),
    )
    shutdown_diagnostics_enabled: bool = Field(
        default=True,
        description=(
            "Sprint 15 / v32 Phase B Task C：lifespan 退出后启动后台 daemon "
            "thread，每 shutdown_diagnostics_interval_s 秒 dump 一次 "
            "threading.enumerate() 到 data/logs/shutdown_diagnostics_*.log，"
            "外加 atexit 钩子做最终 dump。用于定位 lifespan 完成→process exit "
            "之间 ~13s hang 的根因（哪个 non-daemon thread / atexit / uvicorn "
            "keep-alive 在阻塞）。详见 _v32_biz/_phase_b_hang_rca.md。"
            "生产可关；默认 True 以便首批 v32 e2e 回归立刻产出数据。"
        ),
    )
    shutdown_diagnostics_interval_s: float = Field(
        default=1.0,
        ge=0.1,
        le=30.0,
        description=(
            "shutdown_diagnostics 后台 daemon thread 两次 dump 之间的间隔秒数，"
            "默认 1.0s（足够分辨 lifespan→exit 13s hang 的逐秒变化）。"
        ),
    )
    uvicorn_graceful_shutdown_timeout_s: float = Field(
        default=3.0,
        ge=0.0,
        le=60.0,
        description=(
            "uvicorn Server.shutdown() 等 keep-alive HTTP/WebSocket 连接 "
            "graceful close 的最长秒数（uvicorn 默认 None=无限等）。"
            "Sprint 15 / v32 Phase B Task C 假设根因之一即 uvicorn 默认无限 "
            "等代理 / 浏览器 / newsclaw stop CLI 自身的 keep-alive 连接 "
            "导致 lifespan 完成后 process 仍不退；3.0s 给浏览器 / SSE 客户端 "
            "一个干净窗口，超时即强 close。0 表示禁用此 cap（恢复 uvicorn 默认）。"
        ),
    )
    lifespan_stage_timeout_s: int = Field(
        default=8,
        ge=1,
        le=60,
        description=(
            "FastAPI lifespan shutdown 单阶段（gateway/runtime/reconcile_loop 等）的"
            "硬超时秒数。超时不阻塞下一阶段，仅 logger.warning。默认 8s。"
        ),
    )

    grep_timeout_sec: int = Field(
        default=30,
        ge=5,
        le=600,
        description="单次 grep（文件内容搜索）最大耗时（秒），超时返回提示以避免 worker 被大目录卡住。",
    )
    glob_timeout_sec: int = Field(
        default=30,
        ge=5,
        le=600,
        description="单次 glob（文件名搜索）最大耗时（秒），超时返回提示以避免 worker 被大目录卡住。",
    )

    # PR-R1: 系统 prompt / catalog header 的语言。
    # 取值 "zh"（中文，默认）/ "en"（英文）。tool_catalog header、AGENTS.md 段
    # 引导文本等会按此切换；工具自身的 description 仍按工具定义里的语言。
    prompt_lang: str = Field(
        default="zh",
        description="System prompt 主语言：'zh' 中文 / 'en' 英文。",
    )

    # PR-T1: 灰度开关（feature flags）配置入口。
    # core/feature_flags.py 会读取这里的 dict，作为持久化的 flag 覆盖源；
    # 优先级：runtime override > 环境变量 NEWSCLAW_FF_DISABLE/ENABLE >
    #         settings.feature_flags > 代码内默认值。
    # 在 .env / newsclaw.toml 里这样写就能关掉本批新行为之一：
    #   FEATURE_FLAGS={"text_replace_on_restart_v1": false}
    # 解析失败永不阻断启动；不在此处写白名单，未知 flag 直接被 is_enabled 当作 False。
    feature_flags: dict = Field(
        default_factory=dict,
        description=(
            "灰度开关 dict，用于覆盖 core/feature_flags.py 中的默认值。"
            "可在配置文件 / 环境变量 FEATURE_FLAGS（JSON）中按 flag_name=true/false 设置，"
            "便于一键回退某个治本修复到老路径。"
        ),
    )

    # Anthropic API
    anthropic_api_key: str = Field(default="", description="Anthropic API Key")
    anthropic_base_url: str = Field(
        default="https://api.anthropic.com",
        description="Anthropic API Base URL (支持云雾AI等转发服务)",
    )
    default_model: str = Field(
        default="claude-opus-4-5-20251101-thinking", description="默认使用的模型"
    )
    max_tokens: int = Field(
        default=0,
        description="最大输出 token 数 (0=不限制，使用模型默认上限；仅 Anthropic API 强制要求此参数时才会自动使用兜底值)",
    )

    # Agent 配置
    agent_name: str = Field(default="小小宋", description="Agent 名称（AI 自称；SOUL.md 的 {{agent_name}} 占位符与 CLI 显示均取此值）")
    max_iterations: int = Field(
        default=300,
        ge=5,
        description="ReAct 循环最大迭代次数（最终防死循环硬上限；默认 300，复杂任务可调到 500+）",
    )

    # Plan 模式建议阈值（ComplexitySignal.score 达到此值时建议用户使用 Plan 模式）
    plan_suggest_threshold: int = Field(
        default=5,
        ge=2,
        le=10,
        description="复杂度评分达到该阈值时建议 Plan 模式（2~10，越高越不容易触发建议）",
    )

    # 自检配置
    progress_timeout_seconds: int = Field(
        default=0,
        description="无进展超时阈值（秒），0=禁用（默认）。建议值 1200（20 分钟）",
    )
    hard_timeout_seconds: int = Field(
        default=0,
        description="硬超时上限（秒），0=禁用（默认）。仅作为最终兜底，避免无限任务",
    )

    # === Conversation Concurrency / Double-texting（v1.27.14, plan: conversation concurrency v1.28）===
    # 同一 conversation_id 上短时间内重发的语义。每 channel 一个策略：
    #   reject    — 旧任务在跑就 409 拒绝（不同 client 永远走这条）
    #   queue     — 排在旧任务后面串行执行（最稳；等待上限是 queue_wait_timeout_ms，
    #               默认 10 分钟，覆盖绝大多数长任务，不再是 6s 误杀）
    #   interrupt — cancel 旧任务再开新流（需要 double_texting_allow_interrupt=True）
    #   steer     — 把新消息注入到正在跑的 turn，不打断旧任务也不超时
    #               （需要 double_texting_allow_steer=True，desktop 默认）
    # desktop 默认 steer：长任务进行中用户追发消息时，注入到当前 turn 让
    # Agent 在下一个工具边界自然读取，而不是排队等待（对齐 Claude Code
    # 的 “边跑边追加指令” 体验）。STEER 由 HTTP 层（/api/chat）short-circuit 走
    # insert_user_message 注入；不经 HTTP 的 channel（cli/IM）仍默认 reject/queue，
    # 因为 agent 层没有 steer 注入入口（见 agent._preempt_or_queue_prev_task）。
    double_texting_default: str = Field(
        default="queue",
        description="默认 double-texting 策略（reject/queue/interrupt/steer），未配 per-channel 时使用。",
    )
    double_texting_per_channel: dict = Field(
        default_factory=lambda: {
            "feishu": "reject",
                                                "qqbot": "queue",
                        "wechat": "reject",
            "desktop": "steer",
            "cli": "queue",
        },
        description="按 channel 名维度的 double-texting 策略覆盖；缺省回落到 double_texting_default。",
    )
    double_texting_allow_interrupt: bool = Field(
        default=False,
        description=(
            "Feature flag：是否允许 INTERRUPT 策略真的 cancel 当前任务。"
            "默认 False，任何 INTERRUPT 请求在 caller 层降级为 QUEUE。"
            "S4（v1.28.2）完成工具 interrupt_behavior 标注后，可安全开启。"
        ),
    )
    double_texting_allow_steer: bool = Field(
        default=True,
        description=(
            "Feature flag：是否允许 STEER 策略把新消息注入到正在跑的 turn。"
            "默认 True（desktop/cli 默认走 steer）。STEER 不打断旧任务、不超时，"
            "新消息在下一个工具边界被 Agent 读取。设为 False 时任何 STEER 请求"
            "在 caller 层降级为 QUEUE（回退到 6s 排队等待的旧行为），用作紧急开关。"
        ),
    )
    preempt_settle_timeout_ms: int = Field(
        default=6000,
        ge=500,
        le=120000,
        description=(
            "preempt/queue 等旧任务 settled 的超时（毫秒）。"
            "超时后老协程标记 abandoned，新流继续。"
            "建议 > 最长工具 soft-timeout。"
        ),
    )
    preempt_block_tool_extension_ms: int = Field(
        default=24000,
        ge=0,
        le=600000,
        description=(
            "QUEUE wait 第一次 timeout 时，若老 task 仍有 block 类工具在跑"
            "（write_file / run_shell / browser_click 等会留下副作用的工具），"
            "再延长这么多毫秒等一次。覆盖大多数长写场景；第二次 timeout 才"
            "硬 cancel。设为 0 即关闭延长机制（保持 v1.28.2 之前的行为）。"
            "v1.28.2 FOLLOW-UP-S4-A。"
        ),
    )
    queue_wait_timeout_ms: int = Field(
        default=600000,
        ge=500,
        le=3600000,
        description=(
            "QUEUE 策略下，新消息等待上一轮 turn 自然跑完的最长时间（毫秒），"
            "默认 10 分钟。与 preempt_settle_timeout_ms 解耦：后者是「抢占/取消"
            "旧任务后等它 settle」的短超时（默认 6s），本项是「排队等旧任务自然"
            "结束」的长超时。二者语义不同——排队场景下旧任务通常是合法的长 Agent"
            "任务，用 6s 误杀会让 CLI/默认 queue 通道一追发就报 queue_timeout。"
            "等待期间 HTTP 层有 SSE keepalive ping，客户端断开会立即结束等待；"
            "agent 层等待期间被外层 cancel 也会立即退出。"
        ),
    )

    # === ForceToolCall（工具护栏）===
    # 默认信任模型自主判断是否需要工具；仅由意图分析或用户配置显式开启追问。
    force_tool_call_max_retries: int = Field(
        default=0,
        description="当模型未调用工具时，最多追问要求调用工具的次数（0=禁用，信任模型自主判断）",
    )
    force_tool_call_im_floor: int = Field(
        default=0,
        description="IM 通道的 ForceToolCall 最低重试次数（0=与全局一致，不强制下限）",
    )
    confirmation_text_max_retries: int = Field(
        default=1,
        description="工具执行后无可见文本时的最大追问次数（0=禁用）",
    )

    # === 工具并行执行 ===
    # 单轮模型返回多个 tool_use/tool_calls 时，Agent 可选择并行执行工具以提升吞吐。
    # 默认 1：保持现有串行语义（最安全，尤其是带“思维链连续性”的工具链）。
    tool_max_parallel: int = Field(
        default=1,
        description="单轮并行工具调用最大并发数（默认 1=串行；>1 启用并行）",
    )
    tool_hard_timeout_seconds: int = Field(
        default=0,
        description="普通工具调用硬超时（秒），0=不限时（默认，由用户/工具自身中断控制）",
    )
    long_running_tool_timeout_seconds: int = Field(
        default=0,
        description="长耗时工具（shell/browser/org 等）硬超时（秒），0=不限时（默认）",
    )
    tool_result_max_chars: int = Field(
        default=32000,
        ge=1000,
        description="单个工具结果进入模型前的兜底截断字符数；完整内容会保存到 overflow 文件",
    )
    tool_overflow_max_files: int = Field(
        default=200,
        ge=10,
        description="工具超长输出 overflow 目录保留的最大文件数",
    )
    run_shell_default_block_timeout_ms: int = Field(
        default=30000,
        ge=0,
        description="run_shell 未显式设置 block_timeout_ms/timeout 时的阻塞等待毫秒数；0=立即后台化",
    )
    run_shell_max_block_timeout_ms: int = Field(
        default=1800000,
        ge=0,
        description="run_shell 兼容 timeout 参数换算后的最大阻塞等待毫秒数；0=不额外钳制",
    )
    powershell_default_timeout_seconds: int = Field(
        default=120,
        ge=0,
        description="run_powershell 默认等待时间（秒）；0=不设置子进程超时",
    )
    powershell_max_timeout_seconds: int = Field(
        default=1800,
        ge=0,
        description="run_powershell 显式 timeout 的最大值（秒）；0=不额外钳制",
    )
    cli_command_timeout_seconds: int = Field(
        default=300,
        ge=0,
        description="CLI-Anything 普通命令默认等待时间（秒）；0=不设置子进程超时",
    )
    opencli_command_timeout_seconds: int = Field(
        default=300,
        ge=0,
        description="OpenCLI list/doctor 默认等待时间（秒）；0=不设置子进程超时",
    )
    opencli_task_timeout_seconds: int = Field(
        default=900,
        ge=0,
        description="OpenCLI run 默认等待时间（秒）；0=不设置子进程超时",
    )
    read_file_default_limit: int = Field(
        default=2000,
        ge=1,
        description="read_file 未指定 limit 时默认读取的行数",
    )
    web_search_attempt_timeout_seconds: int = Field(
        default=25,
        ge=0,
        description=(
            "web_search/news_search 单次外部搜索源等待上限（秒），0=不限。"
            "超时只跳过本次搜索等待并把结果交给模型继续决策，不判定整个任务失败"
        ),
    )

    # === 搜索源（Provider）配置 ===
    # 调度规则见 src/newsclaw/tools/web_search/runtime.py：
    #   - web_search_provider 留空 → 按 auto_detect_order 走可用源 fallback
    #   - 指定 id → 优先走该源；Agent 路径 allow_fallback=True 时仍可换源
    # 命名带 `_API_KEY` / `_BASE_URL` 的敏感字段由前端 password input 自行保护显示
    web_search_provider: str = Field(
        default="tavily",
        description=(
            "激活的搜索源 ID（tavily / bocha / serper / searxng / jina / duckduckgo）；"
            "默认 tavily；留空=按优先级自动检测可用源"
        ),
    )
    bocha_api_key: str = Field(
        default="",
        description="博查 Bocha 搜索 API Key（备用源，申请：https://api.bochaai.com）",
    )
    tavily_api_key: str = Field(
        default="",
        description=(
            "Tavily 搜索 API Key（默认搜索源，申请：https://app.tavily.com/home）；"
            "支持逗号/换行分隔的多 Key 号池（自动轮询与失效切换）"
        ),
    )
    jina_api_key: str = Field(
        default="",
        description="Jina 搜索 API Key（可选，无 Key 也能用但限速）",
    )
    serper_api_key: str = Field(
        default="",
        description=(
            "Serper (Google) 搜索 API Key；支持逗号/换行分隔的多 Key 号池（自动轮询与失效切换）"
        ),
    )
    searxng_base_url: str = Field(
        default="",
        description=(
            "SearXNG 自部署实例地址，例如 http://localhost:8080；"
            "需开启 server.formats.json 才能返回 JSON"
        ),
    )

    allow_parallel_tools_with_interrupt_checks: bool = Field(
        default=False,
        description="是否允许在启用“工具间中断检查”时也并行执行工具（会降低中断插入粒度，默认关闭）",
    )

    # === 工具常驻加载 ===
    always_load_tools: list = Field(
        default_factory=list,
        description="用户指定的常驻工具名列表，不会被 defer（如 browser_navigate, edit_notebook）",
    )
    always_load_categories: list = Field(
        default_factory=list,
        description="用户指定的常驻工具分类（如 Browser, MCP），该分类下所有工具不 defer",
    )
    # Thinking 模式配置
    thinking_mode: str = Field(
        default="auto",
        description="Thinking 模式: auto(自动判断), always(始终启用), never(从不启用)",
    )
    im_chain_push: bool = Field(
        default=False,
        description="IM 通道是否推送思维链进度（💭思考过程、工具调用等）给用户，关闭不影响内部保存。默认关闭以减少刷屏",
    )
    thinking_keywords: list = Field(
        default_factory=lambda: [
            "分析",
            "推理",
            "思考",
            "评估",
            "比较",
            "规划",
            "设计",
            "架构",
            "优化",
            "debug",
            "调试",
            "复杂",
            "困难",
            "analyze",
            "reason",
            "think",
            "evaluate",
            "compare",
            "plan",
            "design",
        ],
        description="触发 thinking 模式的关键词",
    )

    # 路径配置
    project_root: Path = Field(
        default_factory=lambda: Path.cwd(), description="项目根目录 (默认为当前工作目录)"
    )
    database_path: str = Field(default="data/agent.db", description="数据库路径")

    # === 日志配置 ===
    log_level: str = Field(default="INFO", description="日志级别")
    log_dir: str = Field(default="logs", description="日志目录")
    log_file_prefix: str = Field(default="newsclaw", description="日志文件前缀")
    log_max_size_mb: int = Field(default=10, description="单个日志文件最大大小（MB）")
    log_backup_count: int = Field(default=30, description="保留的日志文件数量")
    log_retention_days: int = Field(default=30, description="日志保留天数")
    log_format: str = Field(
        default="%(asctime)s - %(name)s - %(levelname)s - %(message)s", description="日志格式"
    )
    log_to_console: bool = Field(default=True, description="是否输出到控制台")
    log_to_file: bool = Field(default=True, description="是否输出到文件")
    llm_debug_enabled: bool = Field(default=True, description="是否保存 LLM 请求/响应调试快照")
    llm_debug_retention_days: int = Field(default=3, description="LLM 调试快照保留天数")
    llm_debug_max_size_mb: int = Field(default=512, description="LLM 调试快照目录最大体积（MB）")

    # === 全局代理配置 ===
    # 用于 LLM API 请求的代理（如果透明代理不生效）
    http_proxy: str = Field(default="", description="HTTP 代理地址 (如 http://127.0.0.1:7890)")
    https_proxy: str = Field(default="", description="HTTPS 代理地址 (如 http://127.0.0.1:7890)")
    all_proxy: str = Field(default="", description="全局代理地址（优先级高于 http/https proxy）")
    no_proxy: str = Field(
        default="",
        description="不走代理的地址（逗号分隔，支持 IP / CIDR / 域名后缀，如 192.168.0.0/16,.internal）",
    )

    # === IPv4 强制模式 ===
    # 某些 VPN（如 LetsTAP）不支持 IPv6，启用此选项强制使用 IPv4
    force_ipv4: bool = Field(
        default=False, description="强制使用 IPv4（解决某些 VPN 的 IPv6 兼容性问题）"
    )

    # === 模型下载源配置 ===
    # 本地 embedding 模型从 HuggingFace 下载，国内可能很慢
    # 支持: auto(自动选择) | huggingface(官方) | hf-mirror(国内镜像) | modelscope(魔搭社区)
    model_download_source: str = Field(
        default="auto",
        description="模型下载源: auto(自动选择最快源) | huggingface | hf-mirror | modelscope",
    )

    # === Embedding 模型配置 ===
    embedding_model: str = Field(
        default="shibing624/text2vec-base-chinese",
        description="Embedding 模型名称 (如 shibing624/text2vec-base-chinese)",
    )
    embedding_device: str = Field(
        default="cpu",
        description="Embedding 模型运行设备 (cpu 或 cuda)",
    )

    # === 搜索后端配置 (v2) ===
    search_backend: str = Field(
        default="fts5",
        description="记忆搜索后端: fts5(默认,零依赖) | chromadb(本地向量 ANN+BM25 混合) "
        "| api_embedding(在线 API 逐条比对,仅小库)",
    )
    embedding_source: str = Field(
        default="local",
        description="chromadb 后端的向量来源: local(本地模型,默认) | api(在线 embedding API,索引仍在本地)",
    )
    embedding_api_provider: str = Field(
        default="",
        description="在线 Embedding API 提供商: dashscope | openai (仅 search_backend=api_embedding 时需要)",
    )
    embedding_api_key: str = Field(
        default="",
        description="在线 Embedding API Key (仅 search_backend=api_embedding 时需要)",
    )
    embedding_api_model: str = Field(
        default="text-embedding-v3",
        description="在线 Embedding 模型名称 (如 text-embedding-v3, text-embedding-3-small)",
    )
    embedding_api_base_url: str = Field(
        default="",
        description="在线 Embedding API Base URL (留空用官方端点; 适配 OpenAI 兼容网关, 如百炼 compatible-mode)",
    )
    memory_rerank_enabled: bool = Field(
        default=False,
        description="记忆检索是否启用重排模型精排 (fail-open: 失败自动回退公式排序)",
    )
    memory_rerank_model: str = Field(
        default="qwen3.7-text-rerank",
        description="重排模型名称 (走 embedding API 同一网关的 DashScope 原生 rerank 端点)",
    )

    # === 记忆系统配置 ===
    memory_history_days: int = Field(default=30, description="记忆保留天数")
    memory_max_history_files: int = Field(default=1000, description="最大历史文件数")
    memory_max_history_size_mb: int = Field(default=500, description="历史文件最大总大小(MB)")

    # GitHub
    github_token: str = Field(default="", description="GitHub Token")

    # DashScope API Key (used by image generation tool)
    dashscope_api_key: str = Field(default="", description="DashScope API Key")

    # DashScope 图像生成 (Qwen-Image) - 同一 Key，不同接口
    dashscope_image_api_url: str = Field(
        default="https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
        description="DashScope Qwen-Image 同步接口 URL（默认北京地域）",
    )

    # === MCP 配置 ===
    mcp_enabled: bool = Field(default=True, description="是否启用 MCP (Model Context Protocol)")
    mcp_timeout: int = Field(
        default=0,
        ge=0,
        description="MCP 工具/资源/提示词调用超时时间（秒），0=不限制；连接超时单独由 mcp_connect_timeout 控制",
    )
    mcp_connect_timeout: int = Field(
        default=60,
        description=(
            "MCP 服务器连接超时时间（秒），默认 60 秒。"
            "stdio 服务器（如 chrome-devtools-mcp 通过 npx 启动）首次连接需要"
            "下载 npm 包并完成 JSON-RPC initialize 握手，过短的超时会导致用户"
            "在慢网络下看到误导性的连接失败。"
        ),
    )
    mcp_auto_connect: bool = Field(default=False, description="启动时是否自动连接所有 MCP 服务器")

    # === 调度器配置 ===
    scheduler_timezone: str = Field(default="Asia/Shanghai", description="调度器时区")
    scheduler_task_timeout: int = Field(
        default=1200, description="定时任务执行超时时间（秒），默认 1200 秒（20分钟）"
    )
    scheduler_background_token_budget: int = Field(
        default=120000,
        description="单次后台系统任务的 token 预算，达到后在安全检查点暂停（0=不限制）",
    )
    memory_consolidation_onboarding_days: int = Field(
        default=7,
        description="新用户适应期天数，期间记忆整理频率提高（默认 7 天）",
    )
    memory_consolidation_onboarding_interval_hours: int = Field(
        default=3,
        description="适应期内记忆整理间隔（小时，默认 3 小时）",
    )

    # === 记忆模式 ===
    # mode1: 碎片化记忆 — 基于实体-属性的语义记忆片段，适合简单偏好/事实存储，
    #         检索快但缺乏跨会话关联能力。
    # mode2: 关系型图谱 — 多维度(时间/因果/实体/动作/上下文)交织的图结构记忆，
    #         支持因果推理、时间线回溯、跨会话实体追踪，适合复杂长期交互。
    # auto:  自动选择 — 根据查询特征(是否涉及因果、时间线、跨会话、实体追踪)
    #         智能路由到 mode1 或 mode2，兼顾两者优势。
    memory_mode: str = Field(
        default="auto",
        description="记忆模式: mode1(碎片化) / mode2(关系型图谱) / auto(自动选择，推荐)",
    )
    mdrm_max_hops: int = Field(
        default=3,
        description="图遍历最大跳数",
    )
    mdrm_consolidation_enabled: bool = Field(
        default=True,
        description="是否启用关系型记忆整合",
    )
    mdrm_backfill_on_first_enable: bool = Field(
        default=True,
        description="首次启用 mode2/auto 时回填模式 1 历史数据",
    )

    # === 群聊响应策略 ===
    group_response_mode: str = Field(
        default="mention_only",
        description="群聊响应模式: always(全响应) / mention_only(仅@时响应，默认) / smart(AI判断)",
    )

    # === 通道配置 ===
    # 飞书
    feishu_enabled: bool = Field(default=False, description="是否启用飞书")
    feishu_app_id: str = Field(default="", description="飞书 App ID")
    feishu_app_secret: str = Field(default="", description="飞书 App Secret")
    feishu_oauth_extra_scopes: str = Field(
        default="",
        description=(
            "飞书用户授权时额外申请的 scope（空格或逗号分隔，默认只申请 "
            "wiki:wiki docx:document）。若后台能勾 offline_access 且需要长期用户令牌，"
            "可填 offline_access。"
        ),
    )
    feishu_oauth_redirect_uri: str = Field(
        default="http://127.0.0.1:18900/api/feishu/oauth/callback",
        description=(
            "飞书用户授权回调地址（需与开发者后台「安全设置 → 重定向 URL」完全一致）。"
            "用于知识库归档所需的用户身份授权。"
        ),
    )
    feishu_wiki_space_id: str = Field(
        default="",
        description=(
            "飞书 Wiki 空间 ID（可选）：feishu_doc 创建文档后自动挂到该空间实现知识库归档；"
            "留空则仅落在云空间。需应用开通 wiki:wiki 权限且空间对其可见。"
        ),
    )

    # QQ 官方机器人
    qqbot_enabled: bool = Field(default=False, description="是否启用 QQ 官方机器人")
    qqbot_app_id: str = Field(default="", description="QQ 机器人 AppID")
    qqbot_app_secret: str = Field(default="", description="QQ 机器人 AppSecret")
    qqbot_sandbox: bool = Field(default=False, description="是否使用沙箱环境")
    qqbot_mode: str = Field(
        default="websocket",
        description="QQ 机器人接入模式: websocket (默认，无需公网) 或 webhook (需要公网IP/域名)",
    )
    qqbot_webhook_port: int = Field(default=9890, description="QQ Webhook 回调服务端口")
    qqbot_webhook_path: str = Field(default="/qqbot/callback", description="QQ Webhook 回调路径")

    # 微信个人号 (iLink Bot API)
    wechat_enabled: bool = Field(default=False, description="是否启用微信个人号")
    wechat_token: str = Field(default="", description="微信 iLink Bot Token（扫码登录获取）")

    # 微信公众号（Official Account 服务端发布 API；与上面的个人号通道无关）
    wechat_mp_app_id: str = Field(default="", description="微信公众号 AppID（mp.weixin.qq.com）")
    wechat_mp_app_secret: str = Field(default="", description="微信公众号 AppSecret")
    wechat_mp_author: str = Field(
        default="", description="公众号文章默认作者署名（工具参数可覆盖）"
    )
    wechat_mp_auto_publish: bool = Field(
        default=True,
        description=(
            "每日早报公众号稿的发布方式：true=创建草稿后立即调发布接口；"
            "false=仅存草稿箱，人工在公众号后台确认后再发。"
        ),
    )

    # === 会话配置 ===
    session_storage_path: str = Field(default="data/sessions", description="会话存储路径")

    # === 多 Agent 模式 (Beta) ===
    multi_agent_enabled: bool = Field(
        default=True,
        description="多Agent模式 (Beta)，开启后支持多Agent协作、专用Agent、IM多Bot等",
    )
    coordinator_mode_enabled: bool = Field(
        default=True,
        description=(
            "协调者模式 (CC-3)：启用后，role=coordinator 的 Agent 仅能委派/规划，"
            "不能直接执行文件/命令操作。组织模式下的协调者节点（有下级的节点）"
            "始终启用协调者提示词，与本开关解耦。"
        ),
    )

    # IM 多 Bot 配置（多Agent模式下支持同一通道类型多个Bot实例）
    im_bots: list[dict] = Field(default_factory=list)

    # === 人格系统配置 ===
    persona_name: str = Field(
        default="default",
        description="当前激活的人格预设名称 (default，或用户自创)",
    )

    # === 记忆回顾（Memory Nudge）配置 ===
    memory_nudge_enabled: bool = Field(
        default=True,
        description="是否启用周期性记忆回顾（每 N 轮对话后用 LLM 审视对话并提取值得记忆的内容）",
    )
    memory_nudge_interval: int = Field(
        default=10,
        description="每隔多少轮对话触发一次记忆回顾（0 表示禁用）",
    )

    # === Plugin reseed drift detection ===
    # When True (default), PluginManager logs WARN at startup if any
    # ``plugins/<id>/<file>.py`` (git-tracked seed) is NEWER than its
    # ``data/plugins/<id>/<file>.py`` (runtime copy) counterpart -- a
    # signal that the runtime is executing stale plugin code.  Operators
    # who intentionally ship without the seed tree (e.g. pip-installed
    # distributions) can set this to False to silence the warning.
    plugins_drift_warn_enabled: bool = Field(
        default=True,
        description="启动时是否检测 plugins/ vs data/plugins/ 漂移并发出 WARN 日志",
    )

    # === Smart Approval 配置 ===
    smart_approval_enabled: bool = Field(
        default=False,
        description="是否启用 LLM 辅助风险评估（对 CONFIRM 级操作用 LLM 做预判）",
    )

    # === Docker 执行后端配置 ===
    docker_backend_enabled: bool = Field(
        default=False,
        description="是否启用 Docker 容器执行后端（需要本机安装 Docker）",
    )
    docker_image: str = Field(
        default="python:3.12-slim",
        description="Docker 执行后端使用的镜像",
    )
    docker_network: str = Field(
        default="none",
        description="Docker 网络模式: none(断网) | bridge(默认桥接) | host",
    )

    # === 活人感引擎配置 ===
    proactive_enabled: bool = Field(default=False, description="是否启用活人感模式")
    proactive_max_daily_messages: int = Field(default=3, description="每日最多主动消息数")
    proactive_min_interval_minutes: int = Field(
        default=120, description="两条主动消息最短间隔（分钟）"
    )
    proactive_quiet_hours_start: int = Field(default=23, description="安静时段开始（小时，0-23）")
    proactive_quiet_hours_end: int = Field(default=7, description="安静时段结束（小时，0-23）")
    proactive_idle_threshold_hours: int = Field(
        default=3, description="用户空闲多久后触发闲聊问候（小时），AI 会根据反馈动态调整"
    )

    # === UI 偏好配置 ===
    ui_theme: str = Field(
        default="system",
        description="桌面客户端主题: system(跟随系统) | light(浅色) | dark(深色)",
    )
    ui_language: str = Field(
        default="zh",
        description="桌面客户端语言: zh(中文) | en(英文)",
    )

    # === 桌面通知配置 ===
    desktop_notify_enabled: bool = Field(
        default=True,
        description="任务完成时是否弹出系统桌面通知（Windows Toast / macOS / Linux notify-send）",
    )
    desktop_notify_sound: bool = Field(
        default=True,
        description="桌面通知是否播放系统提示音",
    )

    # === 表情包配置 ===
    sticker_enabled: bool = Field(default=True, description="是否启用表情包功能")
    sticker_data_dir: str = Field(default="data/sticker", description="表情包数据目录")
    sticker_mirrors: list[str] = Field(
        default_factory=list,
        description=(
            "自定义表情包镜像 URL 列表，优先于内置镜像尝试。"
            "支持两种格式：1) CDN 镜像基址（追加相对路径），"
            "2) GitHub 代理前缀（追加完整原始 URL）。"
            "示例: ['https://ghp.ci/https://raw.githubusercontent.com/zhaoolee/ChineseBQB/master/']"
        ),
    )

    # === Bug Report / Feedback 配置 ===
    # 以下三个值是公开标识（类似 reCAPTCHA site key），不是密钥。
    # 官方发行版需要预填默认值以实现开箱即用；
    # fork 用户可通过 .env 覆盖为自己的值，留空则禁用对应功能。
    bug_report_endpoint: str = Field(
        default="",  # NewsClaw fork：默认不上报反馈云端；需要可自建端点后填写
        description="反馈上传端点 URL（阿里云 FC）。留空 = 禁用反馈功能。",
    )
    captcha_scene_id: str = Field(
        default="jkyrkj0w",
        description="阿里云人机验证 2.0 场景ID（公开标识，下发到前端）。留空 = 跳过验证码。",
    )
    captcha_prefix: str = Field(
        default="yiqg72",
        description="阿里云人机验证 2.0 prefix 身份标（公开标识，下发到前端）。",
    )

    # === Inbox / Update Push 配置 ===
    inbox_enabled: bool = Field(
        default=False,  # NewsClaw fork：默认不拉取官方广播、不注册客户端 token
        description="是否启用站内信拉取与本地缓存。关闭后不拉取、不注册客户端 token、不上报交互。",
    )
    inbox_broadcast_url: str = Field(
        default="",  # NewsClaw fork：默认无广播源
        description="站内信 L0 公开广播 JSON URL。",
    )
    inbox_api_url: str = Field(
        default="",
        description="可选的站内信 L1 API；本仓库没有官方云，留空除非你自建。",
    )
    inbox_poll_interval_sec: int = Field(
        default=1800,
        ge=60,
        description="站内信后台拉取间隔（秒）。",
    )
    inbox_register_enabled: bool = Field(
        default=True,
        description="是否启用 L1 challenge/register/renew/poll/ack；关闭后只走 L0 广播。",
    )
    inbox_channel: str = Field(
        default="release",
        description="站内信与升级策略使用的客户端渠道。",
    )
    inbox_minisign_public_key: str = Field(
        default="",
        description="站内信 L0 broadcast.json minisign 公钥。留空表示跳过验签。",
    )
    inbox_minisign_executable: str = Field(
        default="minisign",
        description="用于验证站内信广播签名的 minisign 可执行文件。",
    )
    telemetry_enabled: bool = Field(
        default=True,
        description="是否允许匿名遥测/升级事件上报。当前站内信 ack 仍受 inbox_* 开关控制。",
    )
    updater_policy_endpoint: str = Field(
        default="",
        description="可选的升级策略 URL；本仓库没有官方更新云，留空除非你自建。",
    )

    # === NewsClaw Platform (Agent Hub / Skill Store) ===
    hub_api_url: str = Field(
        default="",
        description="NewsClaw Platform API base URL for Agent Hub and Skill Store",
    )
    hub_api_key: str = Field(
        default="",
        description="NewsClaw Platform API Key (ak_live_...)",
    )
    hub_device_id: str = Field(
        default="",
        description="Local device identifier (auto-generated UUID)",
    )

    # === 上下文管理配置 ===
    context_max_window: int = Field(
        default=0,
        description="全局上下文最大输入长度 (tokens)。实际生效时取 min(此值, 端点 context_window)。0=不限制，直接使用端点上限",
    )
    context_compression_ratio: float = Field(
        default=0.25,
        description="上下文压缩目标比例，早期对话压缩到原文的该百分比 (0.05~0.5)",
    )
    context_compression_threshold: float = Field(
        default=0.85,
        description="触发压缩的软限比例——上下文 token 数超过硬上限的该比例时开始压缩 (0.5~0.95，越大越晚触发)",
    )
    context_boundary_compression_ratio: float = Field(
        default=0.25,
        description="跨话题边界压缩比例，旧话题压缩到该百分比 (0.05~0.5)",
    )
    context_min_recent_turns: int = Field(
        default=12,
        description="兼容配置：recent-tail 参与选择的对话组上限；token 预算优先 (4~20)",
    )
    context_recent_tail_ratio: float = Field(
        default=0.25,
        ge=0.05,
        le=0.5,
        description="压缩后最近原文可占消息硬预算的比例",
    )
    context_recent_tail_min_tokens: int = Field(
        default=2000,
        ge=256,
        description="压缩后最近原文的最小 token 预算",
    )
    context_recent_tail_max_tokens: int = Field(
        default=8000,
        ge=512,
        description="压缩后最近原文的最大 token 预算",
    )
    context_recent_tail_max_groups: int = Field(
        default=12,
        ge=1,
        le=50,
        description="参与 recent-tail 选择的最大工具交互组数（同时受兼容配置限制）",
    )
    context_enable_tool_compression: bool = Field(
        default=True,
        description="是否启用超长工具结果独立压缩",
    )
    context_large_tool_threshold: int = Field(
        default=5000,
        description="触发单条工具结果独立压缩的 token 阈值",
    )
    context_real_usage_decay: float = Field(
        default=0.9,
        ge=0.1,
        le=1.0,
        description="用上一轮真实 input_tokens 反向校准上下文压力时的衰减系数",
    )
    context_token_anomaly_threshold: int = Field(
        default=80000,
        description="单轮 LLM usage 触发强制压缩/降载的阈值（不是直接终止阈值）。值越大越宽松，长任务建议 ≥80000",
    )
    context_token_anomaly_max_recoveries: int = Field(
        default=3,
        ge=0,
        description="单任务内 token 异常触发后允许强制压缩恢复的次数，超过后才允许硬终止；长任务建议 3~5",
    )
    context_hard_terminate_ratio: float = Field(
        default=0.98,
        ge=0.5,
        le=0.99,
        description=(
            "硬终止比例：单轮 input+output tokens 占模型上下文窗口的此比例时，"
            "LoopBudgetGuard 才允许真正终止任务（0.5~0.99，越大越宽松）。"
            "如果当前压力安全且未到此比例，即使触发了 token 异常阈值也只压缩不终止"
        ),
    )
    context_cached_summary_chars: int = Field(
        default=2400,
        description="缓存/聚合工具结果摘要的默认字符预算",
    )
    context_tool_results_total_chars: int = Field(
        default=80000,
        description="单轮工具结果进入上下文前的总字符预算（后续会按上下文压力动态调整）",
    )
    same_tool_call_limit: int = Field(
        default=0,
        ge=0,
        description="同一工具同参数在单任务内允许执行的最大次数，0=不限（默认）。建议值 8~12",
    )
    readonly_stagnation_limit: int = Field(
        default=0,
        ge=0,
        description="只读探索连续无新信息的软提醒轮数，0=禁用（默认）。建议值 3",
    )
    readonly_stagnation_hard_limit: int = Field(
        default=0,
        ge=0,
        description="只读探索连续无新信息的硬终止轮数，0=禁用（默认）。建议值 10~15",
    )


    # === Harness 配置 ===
    # 桌面/交互式 CLI：Field 默认 0=不限。
    # 无人值守（session.is_unattended：newsclaw run / scheduler）在运行时把
    # 步数/时长/工具次数的 0 换成 80 / 1200s / 200，同参重复上限 8。
    # token/cost 有用户配置才设上限；有价表才 record_cost，不假装 0 元。
    supervisor_enabled: bool = Field(
        default=False,
        description="是否启用运行时监督器 (RuntimeSupervisor)，默认关闭。开启后会在工具抖动/编辑抖动/推理死循环等模式被检测到时主动干预",
    )
    task_budget_tokens: int = Field(
        default=0, description="单次任务最大 token 消耗，0=不限（默认）"
    )
    task_budget_cost: float = Field(default=0.0, description="单次任务最大成本 USD，0=不限（默认）")
    task_budget_duration: int = Field(
        default=0,
        description="单次任务最大时长（秒），0=不限（默认）。建议值 600~3600",
    )
    task_budget_iterations: int = Field(
        default=0,
        description="单次任务最大迭代次数，0=不限（默认）。max_iterations 仍是 ReAct 循环硬上限",
    )
    task_budget_tool_calls: int = Field(
        default=0,
        description="单次任务最大工具调用次数，0=不限（默认）。建议值 100~300",
    )

    # === 追踪配置 ===
    tracing_enabled: bool = Field(
        default=True, description="是否启用 Agent 追踪（轻量模式默认开启）"
    )
    tracing_export_dir: str = Field(default="data/traces", description="追踪导出目录")
    tracing_console_export: bool = Field(default=False, description="是否同时导出到控制台")

    # === 评估配置 ===
    org_reject_resubmit_after_accept: bool = Field(
        default=True,
        description="禁止在 chain 已 accepted/delivered 之后再次 submit_deliverable",
    )
    org_suppress_closed_chain_reactivation: bool = Field(
        default=True,
        description="chain 已关闭(accepted/rejected/cancelled)时抑制其消息触发 ReAct 重新激活",
    )
    org_post_task_notify_parent: bool = Field(
        default=False,
        description="任务完成时是否自动向父节点发送[通知]：False 表示不主动唤醒父级",
    )

    # === 组织编排 · 多层级指挥治理（org-orchestration-fix） ===
    # 这组开关用于治理"CEO -> CMO -> 多个执行者 -> CMO 汇总 -> CEO 回包"
    # 这类多层级指挥场景，解决以下根因：
    #   1) 子链 chain_id 默认 _now_iso()，导致父子链断裂、tracker 子树失明
    #   2) 协调者用 org_send_message(question) 派任务，绕过 chain 注册
    #   3) Supervisor 把合法 poll 当死循环 TERMINATE
    #   4) 缺少阻塞等待原语，协调者只能轮询
    #   5) 完成判定一次性 set，CEO 拿不到最终汇总
    # 默认全部开启；任一项设为 false 可一键回退到旧行为，旧代码路径保留。
    org_chain_parent_enforced: bool = Field(
        default=True,
        description=(
            "强制 chain 父子关系：delegate 时为子任务新建 chain 并挂到 caller "
            "current chain 之下；submit 强制复用 caller current chain；"
            "tracker 完成判定走整棵子树。关闭后回退到旧的'复用 caller chain'语义。"
        ),
    )
    org_question_task_guard: bool = Field(
        default=True,
        description=(
            "拦截协调者用 org_send_message(question) 派发任务的反模式："
            "若 sender 有下属且消息文本含'撰写/优化/产出/完成/给出/生成'等任务措辞，"
            "拒绝发送并提示改用 org_delegate_task。"
        ),
    )
    org_supervisor_poll_whitelist: bool = Field(
        default=True,
        description=(
            "Supervisor 对 org_list_delegated_tasks / org_wait_for_deliverable "
            "等合法轮询/等待工具，抬高重复阈值且最高仅 NUDGE，绝不 TERMINATE。"
        ),
    )
    org_wait_primitive_enabled: bool = Field(
        default=True,
        description=(
            "启用 org_wait_for_deliverable 工具：协调者派完任务后可阻塞等待"
            "下级交付，避免 org_list_delegated_tasks 轮询触发 Supervisor 死循环。"
        ),
    )
    org_root_post_summary: bool = Field(
        default=True,
        description=(
            "用户命令完成判定的两阶段状态机：所有子链关闭 + root IDLE 时，"
            "先 push 一条 task_complete 到 root inbox 唤醒 root 产出最终汇总，"
            "等 root 二次 IDLE 后再 set completed。关闭后退回到一阶段判定。"
        ),
    )

    # === 组织编排 · 用户命令生命周期看门狗 ===
    # 用户通过 send_command 下发一条顶层指令后，完成判定由事件驱动
    # （所有委派链 chain 关闭 + root IDLE + root inbox 空）。下列时间参数
    # 仅用于看门狗：防止组织真正卡死（LLM 挂起、死锁）时命令无限挂起。
    # 任一进度信号（token / 工具完成 / 节点状态切换 / chain 事件）到达
    # 都会让 warn/autostop 计时器归零，因此长时但持续产出的任务不会被误停。
    # 默认启用软看门狗：只看“连续无真实进展”，不按总时长硬杀。
    # 真正持续产出的长任务会不断刷新进度，不会被这些阈值中断。
    org_command_stuck_warn_secs: int = Field(
        default=900,
        description="连续无真实进度多久（秒）记录 stuck_warning，0=禁用。默认 900",
    )
    org_command_stuck_autostop_secs: int = Field(
        default=3600,
        description="连续无真实进度多久（秒）兜底 soft_stop 组织，0=禁用。默认 3600",
    )
    org_command_timeout_secs: int = Field(
        default=0,
        description="单条命令最长运行时间（秒）硬上限，0=不限时（默认，不用总时长限制长任务）",
    )
    # ── 死锁早停（独立于 stuck_warn / stuck_autostop） ──
    # 当组织"看似空跑"持续 N 秒后立即收口，不必等 autostop_secs 兜底。
    # 触发条件：没有忙节点 + 没有待处理 mailbox + root 节点 IDLE + 仍有未关闭 chain。
    # 这种状态下没有任何 agent 在工作，也没有消息会再来唤醒任何节点，再等下去
    # 100% 是空跑。比 autostop_secs（默认 3600s）更激进，但比 warn_secs（默认
    # 900s）更温和。
    org_command_deadlock_grace_secs: int = Field(
        default=90,
        description=(
            "全员 IDLE + 无消息 + 仍有 open chain 持续多久（秒）后判定为死锁并立即收口，"
            "0=禁用。默认 90"
        ),
    )

    @model_validator(mode="after")
    def _enforce_min_max_iterations(self) -> "Settings":
        MIN_ITERATIONS = 15
        if self.max_iterations < MIN_ITERATIONS:
            logger.warning(
                "[Config] max_iterations=%d is too low (minimum %d). "
                "Resetting to %d. Please update your .env file.",
                self.max_iterations,
                MIN_ITERATIONS,
                MIN_ITERATIONS,
            )
            self.max_iterations = MIN_ITERATIONS
        return self

    @model_validator(mode="before")
    @classmethod
    def _strip_inline_comments(cls, values: dict) -> dict:  # type: ignore[override]
        """Strip inline comments from env values before type coercion.

        .env files may contain lines like ``MAX_TOKENS=4096  # 常规推荐值``.
        If an external caller (e.g. Tauri bridge) passes the raw value including
        the comment as an OS env-var, Pydantic would fail to parse ``"4096 # ..."``
        as ``int``.  This validator runs *before* field-level coercion and removes
        everything after an unquoted `` #`` / ``\\t#`` pattern.
        """
        if not isinstance(values, dict):
            return values
        cleaned: dict = {}
        for k, v in values.items():
            if isinstance(v, str) and not (len(v) >= 2 and v[0] == v[-1] and v[0] in ('"', "'")):
                for sep in (" #", "\t#"):
                    idx = v.find(sep)
                    if idx != -1:
                        v = v[:idx].rstrip()
                        break
            cleaned[k] = v
        return cleaned

    model_config = {
        "env_file": ".env",
        "env_file_encoding": "utf-8",
        "extra": "ignore",
        # 关键：忽略空字符串环境变量（例如 .env 里写了 PROGRESS_TIMEOUT_SECONDS=）
        # 否则 pydantic 会尝试把 "" 解析成 int/bool，导致启动失败。
        "env_ignore_empty": True,
    }

    def reload(self) -> list[str]:
        """从 .env 文件重新加载配置，返回发生变更的字段名列表。

        创建一个新的 Settings 实例（会重新读取 .env），
        然后把所有字段值拷贝回当前单例。

        运行时持久化字段（``_PERSISTABLE_KEYS``）由 RuntimeState 管理，
        不从 .env 覆盖，避免 im_bots 等被重置。
        """
        _skip = set(_PERSISTABLE_KEYS)
        fresh = Settings()
        changed: list[str] = []
        for field_name in type(self).model_fields:
            if field_name in _skip:
                continue
            old_val = getattr(self, field_name)
            new_val = getattr(fresh, field_name)
            if old_val != new_val:
                setattr(self, field_name, new_val)
                changed.append(field_name)
        if changed:
            logger.info(f"[Settings] Reloaded from .env, changed: {changed}")
        else:
            logger.info("[Settings] Reloaded from .env, no changes detected")
        return changed

    @property
    def identity_path(self) -> Path:
        """身份配置目录路径"""
        return self.project_root / "identity"

    @property
    def soul_path(self) -> Path:
        """SOUL.md 路径"""
        return self.identity_path / "SOUL.md"

    @property
    def agent_path(self) -> Path:
        """AGENT.md 路径"""
        return self.identity_path / "AGENT.md"

    @property
    def user_path(self) -> Path:
        """USER.md 路径"""
        return self.identity_path / "USER.md"

    @property
    def memory_path(self) -> Path:
        """MEMORY.md 路径"""
        return self.identity_path / "MEMORY.md"

    @property
    def personas_path(self) -> Path:
        """人格预设目录路径"""
        return self.identity_path / "personas"

    @property
    def sticker_data_path(self) -> Path:
        """表情包数据目录路径"""
        return self.project_root / self.sticker_data_dir

    @property
    def newsclaw_home(self) -> Path:
        """用户数据根目录，优先使用 NEWSCLAW_ROOT 环境变量，默认 ~/.newsclaw。

        解析规则见 :func:`newsclaw.data_root.resolve_data_root`。
        """
        from .data_root import resolve_data_root

        return resolve_data_root()

    @property
    def user_workspace_path(self) -> Path:
        """当前用户工作区路径。

        如果 project_root 位于 newsclaw_home/workspaces/ 下（生产模式），
        直接使用 project_root 作为工作区路径；否则（开发模式）回退到 default。
        """
        ws_dir = self.newsclaw_home / "workspaces"
        try:
            self.project_root.resolve().relative_to(ws_dir.resolve())
            return self.project_root.resolve()
        except ValueError:
            return ws_dir / "default"

    @property
    def skills_path(self) -> Path:
        """用户技能安装目录 (~/.newsclaw/workspaces/default/skills)

        所有通过 install_skill / skill-creator 安装或创建的技能都存放在此目录。
        该目录位于用户 home 下，打包版本也有写权限。
        开发模式下项目级 skills/ 仍会被扫描（通过 SKILL_DIRECTORIES），但安装目标统一为此路径。
        """
        return self.user_workspace_path / "skills"

    @property
    def data_dir(self) -> Path:
        """数据存储目录 (project_root/data)"""
        return self.project_root / "data"

    @property
    def db_full_path(self) -> Path:
        """数据库完整路径"""
        return self.project_root / self.database_path

    @property
    def log_dir_path(self) -> Path:
        """日志目录完整路径"""
        return self.project_root / self.log_dir

    @property
    def log_file_path(self) -> Path:
        """主日志文件路径"""
        return self.log_dir_path / f"{self.log_file_prefix}.log"

    @property
    def error_log_path(self) -> Path:
        """错误日志文件路径（只记录 ERROR/CRITICAL）"""
        return self.log_dir_path / "error.log"

    @property
    def selfcheck_dir(self) -> Path:
        """自检报告目录"""
        return self.project_root / "data" / "selfcheck"

    @property
    def mcp_config_path(self) -> Path:
        """用户 MCP 配置目录（可写，打包模式安全）

        路径: {project_root}/data/mcp/servers/
        AI 通过工具添加的 MCP 服务器配置保存在此目录。
        启动时同时扫描内置 mcps/ 和此目录。
        """
        return self.project_root / "data" / "mcp" / "servers"

    @property
    def mcp_builtin_path(self) -> Path:
        """内置 MCP 配置目录（随项目分发，打包后可能只读）

        优先使用 project_root/mcps（开发模式），
        若不存在则回退到 wheel 打包位置 site-packages/newsclaw/builtin_mcps/。
        """
        dev_path = self.project_root / "mcps"
        if dev_path.exists():
            return dev_path
        pkg_path = Path(__file__).resolve().parent / "builtin_mcps"
        if pkg_path.exists():
            return pkg_path
        return dev_path


# ---------------------------------------------------------------------------
# 运行时状态持久化
# ---------------------------------------------------------------------------
# 用于保存用户通过对话动态修改的设置（角色、活人感开关等），
# 使其在 Agent 重启后依然生效。
# 存储位置: data/runtime_state.json
# ---------------------------------------------------------------------------

# 需要持久化的 settings 字段名
_PERSISTABLE_KEYS: list[str] = [
    "persona_name",
    "memory_nudge_enabled",
    "memory_nudge_interval",
    "proactive_enabled",
    "proactive_max_daily_messages",
    "proactive_min_interval_minutes",
    "proactive_quiet_hours_start",
    "proactive_quiet_hours_end",
    "ui_theme",
    "ui_language",
    "im_bots",
    "force_tool_call_max_retries",
    "force_tool_call_im_floor",
    "confirmation_text_max_retries",
    "tool_hard_timeout_seconds",
    "long_running_tool_timeout_seconds",
    "tool_result_max_chars",
    "tool_overflow_max_files",
    "run_shell_default_block_timeout_ms",
    "run_shell_max_block_timeout_ms",
    "powershell_default_timeout_seconds",
    "powershell_max_timeout_seconds",
    "cli_command_timeout_seconds",
    "opencli_command_timeout_seconds",
    "opencli_task_timeout_seconds",
    "read_file_default_limit",
    "web_search_attempt_timeout_seconds",
    "always_load_tools",
    "always_load_categories",
]


class RuntimeState:
    """
    轻量级运行时状态持久化。

    配置写入方应调用 save_updates()，在同一锁内更新 settings、生成快照并写入磁盘；
    save() 仅用于持久化已有 settings 快照，load() 用于启动时恢复。
    """

    def __init__(self, state_file: Path | None = None):
        # 延迟解析（settings 还没创建时不能访问 project_root）
        self._state_file = state_file
        self._lock = threading.RLock()

    @property
    def state_file(self) -> Path:
        if self._state_file is None:
            self._state_file = settings.project_root / "data" / "runtime_state.json"
        return self._state_file

    def save(self) -> None:
        """把当前 settings 中的可持久化字段写入 JSON 文件（原子写入 + 备份）。"""
        from .utils.atomic_io import path_transaction_lock

        with self._lock, path_transaction_lock(self.state_file):
            data = self._validate_snapshot(
                {key: getattr(settings, key) for key in _PERSISTABLE_KEYS}
            )
            self._save_locked(data)

    def _save_locked(self, data: dict) -> None:
        """Persist a complete snapshot while ``_lock`` is held."""
        from .utils.atomic_io import atomic_json_write
        from .utils.redaction import redact_value

        try:
            atomic_json_write(self.state_file, data)
            logger.info(f"[RuntimeState] Saved: {redact_value(data)}")
        except Exception as e:
            logger.error(f"[RuntimeState] Failed to save: {e}")
            raise

    @staticmethod
    def _validate_snapshot(data: dict) -> dict:
        """Validate all persisted fields together without mutating global settings."""
        if not isinstance(data, dict):
            raise ValueError("runtime state must be a JSON object")
        candidate = settings.model_dump()
        candidate.update({key: data[key] for key in _PERSISTABLE_KEYS if key in data})
        validated = Settings.model_validate(candidate)
        return {key: getattr(validated, key) for key in _PERSISTABLE_KEYS}

    def save_updates(self, **updates) -> None:
        """Persist merged state while applying only explicit updates locally."""
        unknown = set(updates) - set(_PERSISTABLE_KEYS)
        if unknown:
            raise KeyError(f"Settings are not runtime-persistable: {', '.join(sorted(unknown))}")

        from .utils.atomic_io import path_transaction_lock, read_json_safe

        with self._lock, path_transaction_lock(self.state_file):
            local = {key: getattr(settings, key) for key in _PERSISTABLE_KEYS}
            disk = read_json_safe(self.state_file)
            if disk is not None:
                local.update(self._validate_snapshot(disk))
            local.update(updates)
            validated = self._validate_snapshot(local)
            previous = {key: getattr(settings, key) for key in updates}
            for key in updates:
                setattr(settings, key, validated[key])
            try:
                self._save_locked(validated)
            except Exception:
                for key, value in previous.items():
                    setattr(settings, key, value)
                raise

    def load(self) -> None:
        """从 JSON 文件恢复设置到 settings 单例，仅覆盖可持久化字段（支持 .bak 回退）。"""
        from .utils.atomic_io import path_transaction_lock, read_json_safe
        from .utils.redaction import redact_value

        with self._lock, path_transaction_lock(self.state_file):
            raw = read_json_safe(self.state_file)
            if raw is None:
                logger.info("[RuntimeState] No saved state found, using defaults.")
                return
            try:
                data = self._validate_snapshot(raw)
            except Exception as exc:
                logger.error("[RuntimeState] Invalid saved state, ignoring it: %s", exc)
                return
            previous = {key: getattr(settings, key) for key in _PERSISTABLE_KEYS}
            applied = []
            try:
                for key, new_val in data.items():
                    old_val = previous[key]
                    if old_val != new_val:
                        setattr(settings, key, new_val)
                        applied.append(f"{key}: {redact_value(old_val)} -> {redact_value(new_val)}")
            except Exception as exc:
                for key, value in previous.items():
                    setattr(settings, key, value)
                logger.error("[RuntimeState] Failed to apply state, rolled back: %s", exc)
                return
            if applied:
                logger.info(f"[RuntimeState] Restored: {'; '.join(applied)}")
            else:
                logger.info("[RuntimeState] State loaded, no changes needed.")


def _create_settings_safe() -> Settings:
    """Create the global Settings instance with recovery for poisoned .env files.

    If a field in .env has an unparseable value (e.g. Python repr instead of JSON
    for complex types), remove that field from .env and retry. This handles the
    case where _PERSISTABLE_KEYS fields were incorrectly written to .env by older
    code — those fields are managed by RuntimeState, not .env.
    """
    import re

    max_retries = 3
    last_err: Exception | None = None

    for attempt in range(max_retries + 1):
        try:
            return Settings()
        except Exception as e:
            last_err = e
            if attempt == max_retries:
                break

            err_msg = str(e)
            logger.error(f"[Config] Settings init failed (attempt {attempt + 1}): {err_msg}")

            env_path = Path.cwd() / ".env"
            if not env_path.exists():
                break

            field_match = re.search(r'field "(\w+)"', err_msg)
            if not field_match:
                break

            bad_field = field_match.group(1).upper()
            logger.warning(f"[Config] Removing poisoned key '{bad_field}' from .env and retrying")

            try:
                from .utils.env_config import update_env_file

                update_env_file(env_path, entries={}, delete_keys={bad_field})
            except Exception as io_err:
                logger.error(f"[Config] Failed to repair .env: {io_err}")
                break

    raise last_err  # type: ignore[misc]


# 全局配置实例
settings = _create_settings_safe()

# 全局运行时状态管理器
runtime_state = RuntimeState()

# ---------------------------------------------------------------------------
# 重启信号标志
# ---------------------------------------------------------------------------
# 由 /api/config/restart 端点设置，main.py serve() 循环检测此标志决定是否重启。
_restart_requested: bool = False
