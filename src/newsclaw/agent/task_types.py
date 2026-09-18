"""无头任务的数据类型（供 ``Agent.execute_task`` 与调度器使用）。

历史上这些类型住在 ``agent/ralph.py``（一个从未接线的任务级外循环草稿）；
类型搬到这里，RalphLoop 本体已删除——生产主循环是
``core/_reasoning_runtime.py`` 的 Reason→Act→Observe。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any


class TaskStatus(Enum):
    """任务状态"""

    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    BLOCKED = "blocked"


@dataclass
class Task:
    """任务定义"""

    id: str
    description: str
    session_id: str | None = None  # 关联的会话 ID，用于隔离不同会话的任务
    status: TaskStatus = TaskStatus.PENDING
    priority: int = 0
    attempts: int = 0
    max_attempts: int = 10
    created_at: datetime = field(default_factory=datetime.now)
    completed_at: datetime | None = None
    error: str | None = None
    result: Any = None
    subtasks: list["Task"] = field(default_factory=list)

    def mark_in_progress(self) -> None:
        """标记为进行中"""
        self.status = TaskStatus.IN_PROGRESS
        self.attempts += 1

    def mark_completed(self, result: Any = None) -> None:
        """标记为完成"""
        self.status = TaskStatus.COMPLETED
        self.completed_at = datetime.now()
        self.result = result

    def mark_failed(self, error: str) -> None:
        """标记为失败"""
        self.error = error
        if self.attempts >= self.max_attempts:
            self.status = TaskStatus.FAILED
        else:
            self.status = TaskStatus.PENDING


@dataclass
class TaskResult:
    """任务执行结果"""

    success: bool
    data: Any = None
    error: str | None = None
    iterations: int = 0
    duration_seconds: float = 0
