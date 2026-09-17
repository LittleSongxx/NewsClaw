"""newsroom 运行配置（data/newsroom/config.yaml）。

把本主线的全部开关收敛在一个用户可编辑的文件里，而不是往全局 settings
里加字段——newsroom 的增删只动这个目录，符合"做加法、可整体移除"的原则。
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from .contract import newsroom_root

logger = logging.getLogger(__name__)

CONFIG_FILENAME = "config.yaml"

#: 播种任务的默认 cron（本地时区，Asia/Shanghai 由调度器统一处理）
DEFAULT_DAILY_CRON = "0 8 * * *"
DEFAULT_REVIEW_CRON = "0 20 * * 0"  # 每周日 20:00


@dataclass
class NewsroomConfig:
    """newsroom 主线配置。

    Attributes:
        enabled: 总开关；False 时播种任务会停用已有任务且 API 的手动触发被拒。
        daily_cron / review_cron: 两条任务的 cron 表达式（5 段，本地时区）。
        obsidian_vault: Obsidian 库根目录绝对路径；为空表示暂不做 Wiki 沉淀，
            管线 prompt 中的 Wiki 步骤会据此自动跳过。
        issue_history_days: 采集阶段回看的往期天数（去重窗口，默认 7，对齐周复盘）。
        task_timeout_seconds: 单次管线执行的 task 级超时（透传给调度器 metadata）。
    """

    enabled: bool = True
    daily_cron: str = DEFAULT_DAILY_CRON
    review_cron: str = DEFAULT_REVIEW_CRON
    obsidian_vault: str = ""
    issue_history_days: int = 7
    task_timeout_seconds: int = 3600
    extra: dict = field(default_factory=dict)

    def wiki_enabled(self) -> bool:
        """``obsidian_vault`` 非空才做 Wiki 沉淀；空字符串 = 整条 Wiki 管线关闭。"""
        return bool((self.obsidian_vault or "").strip())

    @property
    def load_error(self) -> str:
        return str(self.extra.get("_load_error") or "")

    def to_dict(self) -> dict:
        extra = {k: v for k, v in self.extra.items() if not str(k).startswith("_")}
        return {
            "enabled": self.enabled,
            "daily_cron": self.daily_cron,
            "review_cron": self.review_cron,
            "obsidian_vault": self.obsidian_vault,
            "issue_history_days": self.issue_history_days,
            "task_timeout_seconds": self.task_timeout_seconds,
            **extra,
        }

    @classmethod
    def from_mapping(cls, data: dict) -> NewsroomConfig:
        """从映射构造；未知键进 extra，``_`` 前缀键不落盘。"""
        if not isinstance(data, dict):
            return cls(enabled=False, extra={"_load_error": "config is not a mapping"})
        known_keys = {
            "enabled",
            "daily_cron",
            "review_cron",
            "obsidian_vault",
            "issue_history_days",
            "task_timeout_seconds",
        }
        extra = {
            k: v for k, v in data.items() if k not in known_keys and not str(k).startswith("_")
        }
        try:
            history = max(1, int(data.get("issue_history_days", 7)))
        except (TypeError, ValueError):
            history = 7
        try:
            timeout = max(300, int(data.get("task_timeout_seconds", 3600)))
        except (TypeError, ValueError):
            timeout = 3600
        enabled = data.get("enabled", True)
        return cls(
            enabled=bool(enabled),
            daily_cron=str(data.get("daily_cron", DEFAULT_DAILY_CRON)),
            review_cron=str(data.get("review_cron", DEFAULT_REVIEW_CRON)),
            obsidian_vault=str(data.get("obsidian_vault", "")),
            issue_history_days=history,
            task_timeout_seconds=timeout,
            extra=extra,
        )


_DEFAULT_CONFIG = NewsroomConfig()


def config_path() -> Path:
    return newsroom_root() / CONFIG_FILENAME


def load_config() -> NewsroomConfig:
    """读取配置；文件缺失时返回默认值（不主动落盘，首次保存时才写出）。"""
    path = config_path()
    if not path.is_file():
        return NewsroomConfig()
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except (yaml.YAMLError, OSError) as e:
        logger.warning("[Newsroom] config.yaml unreadable (%s); disabling until fixed", e)
        return NewsroomConfig(enabled=False, extra={"_load_error": str(e)})
    if not isinstance(data, dict):
        logger.warning("[Newsroom] config.yaml is not a mapping; disabling until fixed")
        return NewsroomConfig(enabled=False, extra={"_load_error": "config.yaml is not a mapping"})
    return NewsroomConfig.from_mapping(data)


def save_config(config: NewsroomConfig) -> Path:
    """原子写入配置，附使用说明注释。"""
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    body = yaml.safe_dump(config.to_dict(), allow_unicode=True, sort_keys=False)
    header = (
        "# AI 早报主线配置。修改后重启服务生效（或在工作台保存，保存即触发任务契约刷新）。\n"
        "# obsidian_vault 留空 = 暂不做 Wiki 沉淀。\n"
    )
    tmp = path.with_suffix(".yaml.tmp")
    tmp.write_text(header + body, encoding="utf-8")
    os.replace(tmp, path)
    return path
