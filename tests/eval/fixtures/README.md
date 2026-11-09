# tests/eval fixtures

两个期次样本，**只读**，由 `tests/eval/test_contract_evals.py` 加载。

- `issue-2026-09-17/`：从 `data/newsroom/issues/2026-09-17/` 原样复制的真实
  旧期次（manifest status=partial、无 items）。产物里保留了历史写法：小红书
  标题候选带反引号与「（推荐，9 字）」注解，公众号稿用 `## 标题` 小节。E1/E2
  用它们钉住「旧产物向前兼容、不被读成 invalid」。
- `golden-issue/`：合成期次，代表「按当前契约写就该 ready」的样本。manifest
  的 issue_date 为 ISO 日期，items 的 title/url/source_name 与 sources.yaml
  默认信源（AI 综合搜索 / 前沿模型动态 / 公司与融资 / 开源项目热点）对齐；
  三产物按技能模板的格式钉写（xhs `### 标题方案` 3 个 ≤20 字候选 + 话题标签，
  wechat `### 基础信息` + `- 标题：` + 摘要/正文，daily-brief 只引用账本内链接）。

改动 fixture 等于改 eval 基线：先确认契约真的变了，再同步这里。
