---
name: switch-persona
description: Switch Agent persona preset. Supports the default preset plus user-created personas (user_custom.md or custom Agent profiles). Use when user asks to change communication style or personality.
system: true
handler: persona
tool-name: switch_persona
category: Persona
---

# 切换人格预设

## 何时使用

- 用户要求切换角色/性格
- 用户说"正式一点"/"随意一点"/"温柔一点"等
- 用户希望改变 Agent 的沟通风格
- 首次使用时的角色选择引导

## 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| preset_name | string | 是 | 预设名称 |

## 可用预设

- `default` - 默认助手（专业友好）

## 示例

```
用户: "你能像个女朋友一样跟我聊天吗"
→ switch_persona(preset_name="default")

用户: "正式一点"
→ switch_persona(preset_name="business")

用户: "随意点，像朋友一样"
→ switch_persona(preset_name="default") + update_persona_trait(dimension="formality", preference="casual")
```

## 注意事项

- 预设只是起点，用户的实际偏好会通过对话不断叠加调整
- 切换后 Agent 应立即按新角色风格回复
- 可以配合 `update_persona_trait` 微调具体维度

