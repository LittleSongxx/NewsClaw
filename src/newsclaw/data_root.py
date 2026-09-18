"""NewsClaw 用户数据根目录（~/.newsclaw）的单一解析入口。

为什么要单独一个模块：``config`` 依赖大量运行时组件，而 ``runtime_env`` 等
底层模块刻意不 import ``config``（会形成循环导入）。任何需要数据根目录的
模块都应该走这里，避免桌面端、CLI 与安装脚本各写各的目录。

数据归属表（收敛后的唯一全景；每项标注：唯一写者 / 是否进备份 / 可否重建 / 可否清理）::

    ~/.newsclaw/                 凭据与账号（resolve_home_root；跨工作区恒定，不进备份）
    <workspace>/data/
      memory/newsclaw.db         记忆唯一真相（写入：MemoryManager/UnifiedStore；
                                 快照 24h×7；schema 迁移自动备份；不可重建，必进备份）
      memory/                    快照与迁移备份（可清理旧份）
      memory/conversation_history/  遗留 JSONL（已停写；可整体删除）
      sessions/sessions.json     会话正典（SessionManager 原子写；进备份；索引可重建）
      sessions/sessions.index.sqlite3  会话随机访问索引（可重建，可清理）
      scheduler/                 任务与执行记录（≤1000 条滚动；进备份）
      audit/policy_decisions.jsonl   策略哈希链账本（追加；进备份）
      newsroom/                  早报主线状态根（唯一写者见 newsroom 契约；进备份）
      memory_snapshots→memory/   同 memory
      transcripts/ orgs/ org_templates/  已退役目录（不再产生；可删除）
      react_traces/ traces/ llm_debug/ logs/ delegation_logs/
                                 诊断目录（可随时清理，不进备份）
      inbox/ plans/ retrospects/ reports/ research/ docs/
                                 低频产出（进备份）
"""

from __future__ import annotations

import os
from pathlib import Path

#: 数据根目录环境变量。未设置时使用 ``~/.newsclaw``。
DATA_ROOT_ENV = "NEWSCLAW_ROOT"
DATA_ROOT_DIRNAME = ".newsclaw"


def resolve_data_root() -> Path:
    """解析用户数据根目录。

    优先级：
      1. ``NEWSCLAW_ROOT``
      2. ``~/.newsclaw``
    """
    value = os.environ.get(DATA_ROOT_ENV, "").strip()
    if value:
        return Path(value).expanduser()
    return Path.home() / DATA_ROOT_DIRNAME


def resolve_home_root() -> Path:
    """数据主目录，**忽略** ``NEWSCLAW_ROOT`` 覆盖。

    账号凭据这类文件必须绑定到 OS 用户身份：换一个工作区（自定义数据根目录）
    不应该搬走或重新生成它们，否则同一个用户在两个工作区间会被识别成两个人。
    """
    return Path.home() / DATA_ROOT_DIRNAME
