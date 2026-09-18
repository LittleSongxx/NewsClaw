"""
IM 通道适配器

各平台的具体实现:
- 飞书
- QQ 官方机器人
- 微信个人号（iLink Bot API）
"""

from .feishu import FeishuAdapter
from .qq_official import QQBotAdapter
from .wechat import WeChatAdapter

__all__ = [
    "FeishuAdapter",
    "QQBotAdapter",
    "WeChatAdapter",
]
