# Third-Party Notices

This file lists third-party components included in or used by this project,
along with their respective licenses.

---

## Anthropic Agent Skills

**Source:** https://github.com/anthropics/skills  
**License:** Apache License 2.0  
**Copyright:** © Anthropic, PBC

The following skills under `skills/` are imported from Anthropic's official
Agent Skills collection:

- `docx` — Word document creation, editing & analysis
- `pdf` — PDF manipulation toolkit
- `pptx` — PowerPoint presentation operations
- `xlsx` — Spreadsheet creation, editing & analysis

Each skill directory contains its own `LICENSE.txt` with the full Apache 2.0
license text. These skills are used in accordance with the Apache License 2.0
terms. No endorsement by Anthropic is implied.

---

## Browser Automation

The following optional dependencies power browser automation (declared in `pyproject.toml` extras and `requirements.txt`):

### Playwright

**Source:** https://github.com/Microsoft/playwright-python  
**License:** Apache License 2.0  
**Copyright:** © Microsoft Corporation

Playwright enables reliable browser automation via Chromium, Firefox, and WebKit.
The bundled package includes the Playwright Python bindings and Chromium browser binary.

### browser-use

**Source:** https://github.com/browser-use/browser-use  
**License:** MIT License  
**Copyright:** © browser-use contributors

browser-use provides AI-driven browser automation, enabling LLM agents to
interact with web pages autonomously.

### LangChain OpenAI / LangChain Core

**Source:** https://github.com/langchain-ai/langchain  
**License:** MIT License  
**Copyright:** © LangChain, Inc.

LangChain Core and LangChain OpenAI adapter are used for LLM integration
within the browser automation module.

---

## Community Skills

The following skills are inspired by or adapted from the
[Agent Skills](https://agentskills.io) community ecosystem:

- `content-research-writer` — Research-driven content writing assistant
- `chinese-writing` — Chinese editorial writing (adapted from miantiao-me/aigc-weekly, MIT)
- `obsidian-skills` — Obsidian vault authoring helpers (kepano/obsidian-skills)
- `wechat-article` — WeChat article publishing (iamzifei/wechat-article-publisher-skill)
- `xiaohongshu-creator` — Xiaohongshu post formatting (comeonzhj/auto-redbook-skills, MIT)

See `skills/ATTRIBUTION.md` for the canonical upstream/license table.

---

## NewsClaw Original Skills

All skills under `skills/system/` (71 skills) and the following curated skills
are original to this project, licensed under the same [AGPL-3.0-only](LICENSE)
terms as the main project:

- `newsroom-editor` — AI briefing editing workflow (collection → writing → review)
- `feishu-cli` — Feishu CLI wrapper for document/archive operations

---

## HTTP Framework

### FastAPI / Uvicorn

**Source:** https://github.com/tiangolo/fastapi / https://github.com/encode/uvicorn
**License:** MIT License
**Copyright:** © Sebastián Ramírez (FastAPI), © Encode OSS (Uvicorn)

FastAPI is used as the HTTP API framework, served by Uvicorn as the ASGI server.

---

## Desktop Runtime Toolchain

The desktop application may include or download runtime/toolchain components
for creating isolated NewsClaw-managed environments. Exact paths, versions,
hashes, ABI tags, and source metadata are recorded in
`resources/bootstrap/manifest.json` in packaged builds and in the exported
`runtime-env-summary.json` diagnostic bundle.

### uv

**Source:** https://github.com/astral-sh/uv
**License:** Apache License 2.0 OR MIT License
**Copyright:** © Astral Software Inc. and contributors

uv is used to create and install dependencies into managed Python virtual
environments.

### CPython / Python

**Source:** https://www.python.org/
**License:** Python Software Foundation License Version 2
**Copyright:** © Python Software Foundation

Desktop packages include a managed Python seed runtime sourced from
python-build-standalone. Seed runtimes are recorded in the bootstrap manifest
with version, ABI, source, license, and SHA-256 hash.

### Node.js

**Source:** https://nodejs.org/
**License:** MIT License
**Copyright:** © OpenJS Foundation and Node.js contributors

Future managed Node.js seed runtimes used by NewsClaw tools must be recorded
in the bootstrap manifest with source, license, version, and SHA-256 hash.
