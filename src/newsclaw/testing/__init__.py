"""
NewsClaw 内置 demo cases（字符串包含判定，不是质量门）。

不是 300 例评测套件。真实计数以 ``cases.get_test_count()`` 为准
（当前 99 条：问答 39、工具 38 含 browser、搜索 22）。
"""

from .fixer import CodeFixer
from .judge import Judge
from .runner import TestRunner

__all__ = ["TestRunner", "Judge", "CodeFixer"]
