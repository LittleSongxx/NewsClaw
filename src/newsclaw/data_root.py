"""NewsClaw 用户数据根目录（~/.newsclaw）的单一解析入口。

为什么要单独一个模块：``config`` 依赖大量运行时组件，而 ``runtime_env`` 等
底层模块刻意不 import ``config``（会形成循环导入）。两边曾经各自硬编码
``~/.openakita``，导致“配置里的数据根目录”和“运行时 venv / 模块安装目录”
落在不同位置，桌面端与管理 CLI 互相看不见对方的数据。任何需要数据根目录的
模块都应该走这里。
"""

from __future__ import annotations

import os
from pathlib import Path

#: 当前名称环境变量
DATA_ROOT_ENV = "NEWSCLAW_ROOT"
#: rename 之前的旧名称环境变量；桌面端与既有部署脚本仍在设置
LEGACY_DATA_ROOT_ENV = "OPENAKITA_ROOT"

DATA_ROOT_DIRNAME = ".newsclaw"
LEGACY_DATA_ROOT_DIRNAME = ".openakita"


def resolve_data_root() -> Path:
    """解析用户数据根目录。

    优先级：
      1. ``NEWSCLAW_ROOT``
      2. ``OPENAKITA_ROOT``（旧名称，保持兼容）
      3. ``~/.openakita`` —— rename 之前创建的既有安装。原地继续使用，
         而不是把整个目录改名：里面有 venv、Playwright 浏览器、node_modules
         等带绝对路径的产物，改名会让桌面端和托管运行时指向不存在的路径。
      4. ``~/.newsclaw`` —— 全新安装
    """
    for name in (DATA_ROOT_ENV, LEGACY_DATA_ROOT_ENV):
        value = os.environ.get(name, "").strip()
        if value:
            return Path(value).expanduser()

    home = Path.home()
    new_root = home / DATA_ROOT_DIRNAME
    legacy_root = home / LEGACY_DATA_ROOT_DIRNAME
    if legacy_root.exists():
        return legacy_root
    return new_root


def resolve_home_root() -> Path:
    """数据主目录，**忽略** ``NEWSCLAW_ROOT`` / ``OPENAKITA_ROOT`` 覆盖。

    账号凭据这类文件必须绑定到 OS 用户身份：换一个工作区（自定义数据根目录）
    不应该搬走或重新生成它们，否则同一个用户在两个工作区间会被识别成两个人。
    解析顺序：``~/.newsclaw``（已存在）→ ``~/.openakita``（rename 前的既有
    安装，凭据留在原地）→ ``~/.newsclaw``。
    """
    home = Path.home()
    new_root = home / DATA_ROOT_DIRNAME
    legacy_root = home / LEGACY_DATA_ROOT_DIRNAME
    if new_root.exists():
        return new_root
    return legacy_root if legacy_root.exists() else new_root
