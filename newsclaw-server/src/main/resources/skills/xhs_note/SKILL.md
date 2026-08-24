---
name: xhs_note
description: '小红书图文创作 / AI 科技动态笔记 (xiaohongshu / red note) — 端到端：证据成文→配图(≥3 张竖版)→去AI化→在线预览打包交付。默认采用克制的科技媒体编辑风，配 3:4 竖版卡片，最少 3 张图。honors user persona & style memory.'
version: 1.6.0
tags:
- 小红书
- 图文
- 笔记
- 内容创作
- xiaohongshu
platforms:
  - macos
  - linux
  - windows
---

# 小红书图文创作

把已核验的 AI 科技事件做成可交付的小红书笔记：文案 + 竖版图文卡片。

> 🖼️ **小红书是「以图为主、文字辅助」的平台**。读者先滑图、再看字——首图（封面）决定点不点进来，图不够好、不够多，文案再好也没人看。
>
> **硬性要求：每篇笔记至少 3 张竖版图（1 封面 + ≥2 张内容图/照片）**。`xhs_package` 会强制校验，不足 3 张直接拒绝打包。图要成组、风格统一、信息落在图上（大标题/清单/对比都做进图里），正文只作补充。

## 开工前：读取共享人设记忆

先用 `recall_structured` 取回并全程遵守：

- `content_persona` — 人设 / 口吻
- `writing_style_xhs` — 小红书文风
- `topic_interests` — 选题方向
- `banned_words` — 禁用 / 敏感词
- `signature_blocks` — 固定开场 / 结尾段

取不到就用中性默认，不要编造。

## SOP

### 1. 成文（小红书文案公式）

**标题（≤20 字，四件套任选组合）**

- **数字**：「30天」「省了800块」「3个动作」
- **悬念**：「原来一直做错了……」
- **价值**：「为什么值得关注」「真正变化在哪里」
- **对比 / 反转**：「从烂脸到裸妆出门」

**正文**

- 短句和自然分段，必要时最多使用少量语义 emoji，不用 emoji 代替事实。
- 开头先给事件和变化，中段拆解事实、影响与边界。
- 关键数字、模型名、发布时间和来源必须与证据包一致。
- AI 动态内容只能引用事件已归档的 `sourceTitle`、来源域名或完整 `sourceUrl`。没有归档 `x.com`/`twitter.com` 证据时，不得写“官方 X/Twitter”；没有归档微博、微信或媒体证据时，也不得借“官方微博/公众号/媒体报道”补强结论。
- 不使用绝对化或排他性措辞：`首个`、`唯一`、`最强`、`领先`、`顶级`、`全网`、`史上`等。无法从证据包确认的内容必须写成待观察或不写。
- 全程遵守 `writing_style_xhs` + `content_persona`，但 AI 科技动态不得套用消费种草口吻。

**话题标签（3–8 个）**

大词 + 中词 + 长尾组合，例：`#护肤` `#敏感肌护肤` `#学生党平价护肤`。

### 2. 配图（以图为主，**≥3 张竖版**）

这是小红书的重头戏。**至少出 3 张 3:4 竖版图**，一组风格统一：

1. **封面（第 1 张，必出）** — 事件主体 + 核心变化 + 来源/日期提示，缩略图可读。
2. **内容图（≥2 张）** — 优先按“已确认事实 / 影响判断 / 能力或产品对比 / 后续观察”拆卡；一张只承担一个信息任务。
3. **结尾图（可选）** — 做后续观察清单或互动问题，不做空泛关注 CTA。

**AI 科技动态视觉规范（默认强制）**

- 使用 `references/` 中的 AI-tech editorial 模板：浅灰/白基底、深色正文，蓝、绿、珊瑚仅作语义强调。
- 禁止整页渐变、大 emoji、装饰圆球、超大胶囊标签和与事实无关的氛围图。
- 四周安全边距不少于 72px，标题和最长英文模型名必须完整落在 1080×1440 画布内。
- 同组卡片保持相同 masthead、色板、字号层级和 footer；footer 写来源类别、日期或页码。
- 长模型名、版本号和带连字符英文标识必须放在正文全宽流式区域或独占规格条，不能放到编号、徽标或其他窄列；优先换行展示，例如 `DeepSeek-V4-Flash-` / `Vision-Exp`，不得逐字竖排。
- 每张事实卡放 3–4 条短要点；信息超过一张卡容量时拆卡，不得通过压窄英文列、缩到不可读字号或侵占 footer 安全区硬塞。
- 模板的 `{{SOURCE}}`、`{{FOOTER}}` 只填事件 Evidence Packet 中真实存在的标题或域名。单一官方来源就如实写“来源：api-docs.deepseek.com”，不要虚构“官方 X”“媒体报道”等第二来源。
- 视觉层必须保留事实边界：已确认信息、分析判断和传闻不能使用相同确定性措辞。

两种出图方式，按需混用，凑够 ≥3 张：

- **HTML 卡片 → 图**：用下面的模板库填文案后 `render_html_image` 渲染。
- **AI 生成照片/背景**：`image_generate(action=generate, aspectRatio=portrait)`（3:4 竖版），做封面底图或实拍风内容图。

**卡片模板库**（竖版 3:4，按同一套视觉语言组合）：
- `references/xhs_card_cover.html` — 已核验事件封面。
- `references/xhs_card_content.html` — 事实、影响、能力清单。
- `references/xhs_card_quote.html` — 关键判断或官方原话。
- `references/xhs_card_end.html` — 后续观察清单。

想要别的视觉风格时，直接生成一份新的自包含 HTML 卡片（可参考现有模板结构），不必局限于现成几款。`render_html_image` 渲染出的 PNG **本身就是预览**——先把图给用户看，满意再进入第 4 步打包。

**填充占位符**：每个模板里有 `{{TITLE}}` `{{SUBTITLE}}` `{{POINTS}}` `{{CTA}}` 等占位 token。把第 1 步的文案填进去——`{{POINTS}}` 是清单，按模板注释里的格式（每条一个 `<li>`）注入。

**渲染成图**：用 `render_html_image` 把填好的 HTML 渲染成 PNG。两种传法：

- 写入临时文件后传 `filePath`：
  ```
  render_html_image(filePath="<填好的卡片.html>", filename="xhs_cover",
                    width=1080, height=1440, fullPage=false)
  ```
- 或直接内联传 `html="<填好的完整HTML字符串>"`，其余参数同上。

竖版 3:4 用 `width=1080 height=1440`。`fullPage=false` 保证输出严格 3:4，不因内容溢出而拉长。

**可选封面底图**：想要更精致的封面，可先用 `image_generate`（`aspectRatio=portrait`）生成一张背景图，再把其链接填进封面模板的背景占位处。

### 3. 去 AI 化

`load_skill deai_humanize`，对正文跑"打分→改写→复检"循环，`platform=xhs`，目标 `score ≤ 55`。小红书口吻要碎、要有情绪，别写成公众号。

### 4. 打包交付（xhs_package —— 在线预览 + 素材下载）

**默认用 `xhs_package` 交付**。它产出小红书风的**在线预览**（手机版：图在上、可左右滑动，标题/正文/标签在下辅助）+ **素材 zip**（按 01、02… 编号的卡片图 + 文案.txt），并附手动上传步骤：

```
xhs_package(title="<标题>", body="<正文，含 emoji 与换行>",
            tags="标签1,标签2,标签3",
            images="<封面图链接>,<内容图1链接>,<内容图2链接>[,更多]")
```

`images` 按展示顺序传每张图的 `render_html_image` / `image_generate` 返回链接（**首图即封面**）。

> ⚠️ **`xhs_package` 强制 ≥3 张图**：解析到的图不足 3 张会被直接拒绝，并提示去补图。所以第 2 步务必先把 ≥3 张竖版图都出好，再来打包。

把返回的**在线预览链接**发给用户看；满意后由用户下载素材 zip，到创作平台手动上传。当前项目**未接入面向普通创作者笔记、可直接自助使用的公开发布 API**，因此**不自动上传、不通过浏览器绕过风控或人机验证**。发布属于对外动作，必须用户明确同意。

（旧的 `xhs_publish` 只出 zip、无在线预览，`xhs_package` 已覆盖并更完整；仅在用户只要发布包、不需要预览时才用它。）

打包前对照 `banned_words` 扫一遍正文和标题，命中即替换。`xhs_package` 还会在生成文件前执行确定性高危词门禁；被阻断时必须改写并重新调用，不能把阻断提示当成交付物。

## 定时 / 批量场景：内容日历 + 合规

长期投产（每日定时）时：

- **选题前查重（成文前）**：`content_item(action="check_recent", platform="xhs", topic="<选题>")`，命中就换角度。只计已打包/已发布。
- **交付即扫即记（自动）**：`xhs_package` 交付时会**自动**跑合规扫描并**自动记入内容日历**——你只需在 `xhs_package` 里传上 `topic="<选题>"`（与 `title` 区分）。个人 / 品牌禁用词可另调 `compliance_scan(text, extraBannedWords="<recall 到的 banned_words>")`。
- **上传后**：用户手动发布后 `content_item(action="mark_published", id)`，就能知道哪些已发、哪些还在待办。

## 保存自定义卡片模板 / 对话升级技能

用户满意某个自创卡片、想复用时，用 `skill_manage` 存成**自定义技能**（`builtin=false` 才能写）：
- `skill_manage(action="create", name="my_xhs_cards", content="<一份 SKILL.md>")`（首次）。
- `skill_manage(action="write_file", name="my_xhs_cards", filePath="references/<卡片名>.html", content="<HTML>")`。

> 本技能 `xhs_note` 是内置技能、不能被直接编辑；自定义卡片一律存到用户自己的自定义技能里。写入都会过安全扫描。

## 参考

- `references/xhs_card_cover.html` — AI 科技事件封面卡。
- `references/xhs_card_content.html` — 事实 / 影响清单卡。
- `references/xhs_card_quote.html` — 关键判断 / 官方原话卡。
- `references/xhs_card_end.html` — 后续观察清单卡。
