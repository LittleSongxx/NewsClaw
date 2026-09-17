"""NewsClaw 用户数据根目录（~/.newsclaw）的单一解析入口。

为什么要单独一个模块：``config`` 依赖大量运行时组件，而 ``runtime_env`` 等
底层模块刻意不 import ``config``（会形成循环导入）。任何需要数据根目录的
模块都应该走这里，避免桌面端、CLI 与安装脚本各写各的目录。
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
