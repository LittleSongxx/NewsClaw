# Example Plugins

本仓库**不发布**示例插件：`examples/plugins/` 已被 `.gitignore` 排除，本地开发副本可能保留示例插件集（echo-channel / hello-tool / message-logger 等）。

仓库里只保留 `examples/.env.example` 作为环境变量参考。

插件系统（Plugin 2.0）支持 Channel / LLM Provider / MCP / Tool / Hook / Knowledge Base / Memory / Skill 等类型，入口在 `src/newsclaw/plugins/`。自己写插件：

```bash
newsclaw plugin-scaffold <目录>   # 生成插件骨架
newsclaw plugin-validate <目录>   # 校验 manifest
# 通过校验后，把插件目录拷进 data/plugins/ 即完成本地安装
```

插件 manifest 与 API 细节以 `newsclaw plugins --help`、`src/newsclaw/plugins/` 为准。