# NewsClaw Core Backend

Python backend for the NewsClaw multi-agent AI assistant.

## Module Overview

| Module | Purpose |
|--------|---------|
| `newsroom/` | AI briefing main line: issue contract & invariants, sources, config, prompts, seeding, feedback, proposal apply |
| `wiki/` | Local Markdown knowledge base: topic/company pages, backlinks, MOC/index |
| `agent/core.py` | Main Agent class — orchestrates brain, tools, identity, memory |
| `agent/brain.py` | LLM interaction layer (streaming, tool calls, retries) |
| `agent/reasoning.py` | ReAct reasoning engine (stream-based) |
| `agent/identity.py` | Loads SOUL.md, AGENT.md, USER.md, MEMORY.md |
| `prompt/builder.py` | Assembles system prompt in layers (identity → runtime → catalogs → memory) |
| `prompt/compiler.py` | Compiles identity files to `identity/runtime/` optimized fragments |
| `agents/orchestrator.py` | Multi-agent message routing, delegation, timeout, health monitoring |
| `agents/factory.py` | Creates Agent instances from AgentProfile, manages instance pool |
| `agents/profile.py` | AgentProfile dataclass, ProfileStore persistence |
| `tools/handlers/` | Tool implementations (one file per tool or tool group) |
| `tools/definitions/` | Tool definitions as Python modules (`TOOL_*_TOOLS` lists, aggregated in `__init__.py`; 115 tools total) |
| `channels/gateway.py` | MessageGateway — unified IM message routing |
| `scheduler/` | Cron scheduler, task executor, system jobs (backup, memory consolidation) |
| `channels/adapters/` | IM adapters (feishu, qq_official, wechat — the three real adapters) |
| `memory/unified_store.py` | Three-layer memory: core, semantic, conversation traces |
| `skills/loader.py` | Discovers and loads SKILL.md files from multiple directories |
| `api/server.py` | FastAPI app setup |
| `api/routes/` | API endpoints (chat, agents, config, mcp, etc.) |

## Adding a New Tool

1. Create handler in `tools/handlers/your_tool.py` — implement the async function
2. Create/extend a definitions module in `tools/definitions/` (Python `ToolDefinition` objects exported as `YOUR_TOOLS`) and register it in `tools/definitions/__init__.py` (`_ALL_TOOLS`)
3. Register the handler so the executor can resolve the tool name (see `tools/handlers/__init__.py`)
4. Optional: add `skills/system/your-tool/SKILL.md` for progressive disclosure

## Adding a New API Route

1. Create route file in `api/routes/your_route.py`
2. Use `APIRouter` with appropriate prefix and tags
3. Register in `api/server.py` via `app.include_router()`

## Adding a New IM Channel

1. Create adapter in `channels/adapters/your_channel.py`
2. Implement the `ChannelAdapter` interface
3. Register in `channels/gateway.py`

## Multi-Agent System

- **Preset agents** are defined in `agents/presets.py`: default (小小宋), ai-news-editor (早报编辑), news-collector (早报采集员), content-creator (自媒体达人), office-doc (文助)
- Sub-agents get an empty context (isolated session `:sub:<run_id>`) plus the task package
- Sub-agents get a "子 Agent 工作模式" prompt section that disables delegation tools
- Agent profiles are persisted as JSON in `data/agents/profiles/`
- Instance pool key: `{session_id}::{profile_id}`, idle timeout 30 min

## Async Conventions

- All I/O operations must be async (use `aiosqlite`, `httpx`, `aiofiles`)
- Use `asyncio.Queue` for inter-agent communication
- Never call blocking I/O in the event loop — use `asyncio.to_thread()` if unavoidable
