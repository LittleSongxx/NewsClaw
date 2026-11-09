"""早报每日管线的采集预算：运行时闸门，不靠 prompt 自觉。

背景（2026-11-09 实测）：prompt 已写明「先委派、委派后不得逐条复核」，但模型
仍在委派之后自己抓了 59 次，单期 69 轮 / 359 万输入 token。这类行为只能靠
运行时兜底，于是加一层工具预算（只作用于 ``newsroom == "daily"`` 的调度上下文）：

- 未委派时：``web_search`` 最多 8 次、``web_fetch`` 最多 10 次——够「探索一下」，
  但超限会要求先调 ``delegate_parallel``（把采集交给 news-collector 子 Agent）；
- 已委派时：``web_search`` 最多 2 次、``web_fetch`` 最多 6 次——只留给抽查与补采，
  超出直接返回可解释的拒绝结果，让主 Agent 进入写作/沉淀/投递。

状态只存在进程内、按调度任务 id 维度，任务开始时由 ``TaskExecutor`` 重置；
不写盘、不跨重启，宁可多放行一次也不让无人值守任务卡死。所有异常一律
fail-open（预算检查失败不能阻断任务）。
"""

from __future__ import annotations

import logging
import os
import threading
from dataclasses import dataclass

logger = logging.getLogger(__name__)

#: 未委派时的自采上限（搜索 / 抓页）。
SEARCH_BEFORE_DELEGATE = 8
FETCH_BEFORE_DELEGATE = 10
#: 已委派后的自采上限（只用于抽查与补采）。
SEARCH_AFTER_DELEGATE = 2
FETCH_AFTER_DELEGATE = 6


@dataclass
class _Budget:
    delegated: bool = False
    searches: int = 0
    fetches: int = 0


_lock = threading.Lock()
_budgets: dict[str, _Budget] = {}
#: 当前正在执行早报每日管线的任务 id（由 TaskExecutor 显式激活/清除）。
_active_task_id: str | None = None


def _env_int(name: str, default: int) -> int:
    try:
        return max(0, int(os.environ.get(name, "") or default))
    except (TypeError, ValueError):
        return default


def _limits() -> tuple[int, int, int, int]:
    """(委派前搜索, 委派前抓页, 委派后搜索, 委派后抓页)，可用环境变量覆盖。"""
    return (
        _env_int("NEWSCLAW_NEWSROOM_SEARCH_BUDGET_BEFORE", SEARCH_BEFORE_DELEGATE),
        _env_int("NEWSCLAW_NEWSROOM_FETCH_BUDGET_BEFORE", FETCH_BEFORE_DELEGATE),
        _env_int("NEWSCLAW_NEWSROOM_SEARCH_BUDGET_AFTER", SEARCH_AFTER_DELEGATE),
        _env_int("NEWSCLAW_NEWSROOM_FETCH_BUDGET_AFTER", FETCH_AFTER_DELEGATE),
    )


def activate(task_id: str) -> None:
    """TaskExecutor 在早报每日管线开始时调用：开启预算并清零计数。"""
    global _active_task_id
    with _lock:
        _budgets[str(task_id)] = _Budget()
        _active_task_id = str(task_id)
    logger.debug("[Budget] activated task=%s", task_id)


def deactivate(task_id: str | None = None) -> None:
    """任务结束时清除激活态；传 task_id 时只清匹配的激活。"""
    global _active_task_id
    with _lock:
        if task_id is None or _active_task_id == str(task_id):
            _active_task_id = None


def reset(task_id: str | None = None) -> None:
    """清空预算状态：传 task_id 清该任务，传 None 清全部（测试用）。"""
    with _lock:
        if task_id is None:
            _budgets.clear()
        else:
            _budgets.pop(str(task_id), None)


def consume(tool_name: str, session_id: str | None = None) -> str | None:
    """登记一次工具调用；返回 None 表示放行，返回字符串表示拒绝原因。

    只有 ``delegate_parallel`` / ``web_search`` / ``web_fetch`` 参与预算，
    其他工具原样放行。只看**主 Agent** 的调用：
    - 采集子 Agent 会话 id 带 ``:sub:``，不参与预算（继承父上下文时若也限流，
      采集员第一次搜索就被拒，委派路径直接死掉）；
    - 会话 id 明确不是调度器前缀（如聊天/IM）时也不参与。
    """
    if tool_name not in {"delegate_parallel", "web_search", "web_fetch"}:
        return None
    sid = str(session_id or "")
    # 主 Agent 的工具会话 id 形如 ``20261109_215928_266b6405``（无冒号）；
    # 子 Agent 会追加 ``:sub:<id>``；IM/聊天会话以 ``feishu:...`` 等通道前缀开头。
    # 只给「无通道前缀的主会话」计数，其余一律放行。
    if sid and (":sub:" in sid or ":" in sid):
        return None
    key = _active_task_id
    if key is None:
        return None
    search_before, fetch_before, search_after, fetch_after = _limits()
    with _lock:
        budget = _budgets.setdefault(key, _Budget())
        if tool_name == "delegate_parallel":
            # 委派是「尝试」就算：即使子 Agent 全失败，后续也只允许少量补采。
            # 两个阶段各自计数：委派前的探索不占用委派后的抽查额度。
            budget.delegated = True
            budget.searches = 0
            budget.fetches = 0
            return None
        if tool_name == "web_search":
            limit = search_after if budget.delegated else search_before
            if budget.searches >= limit:
                return _deny(tool_name, budget, limit)
            budget.searches += 1
            return None
        limit = fetch_after if budget.delegated else fetch_before
        if budget.fetches >= limit:
            return _deny(tool_name, budget, limit)
        budget.fetches += 1
        return None


def _deny(tool_name: str, budget: _Budget, limit: int) -> str:
    if budget.delegated:
        return (
            "⛔ 采集预算已用尽：本轮已完成 delegate_parallel 委派，主 Agent 不再"
            f"自行调用 {tool_name}（委派后上限 {limit} 次，仅用于抽查/补采）。"
            "请基于子 Agent 返回的条目继续：写三份产物 → wiki_upsert → manifest → 投递；"
            "确实缺关键事实时，在最终回复里如实说明缺口，不要反复重抓。"
        )
    return (
        "⛔ 采集预算已用尽：本轮还没有调用 delegate_parallel，自采 "
        f"{tool_name} 已达 {limit} 次上限。请先按任务第 1 步用 delegate_parallel "
        "把信源分给 news-collector 子 Agent；只有子 Agent 失败或返回空之后才允许少量补采。"
    )
