# NewsClaw Setup Center

Desktop GUI and web configuration center for NewsClaw, built with Tauri 2.x + React 18.

## Tech Stack

- **UI**: React 18 + TypeScript (strict mode on), Tailwind CSS v4 + component-scoped CSS
- **Build**: Vite 6
- **Desktop Shell**: Tauri 2.x (Rust, in `src-tauri/`)
- **i18n**: i18next + react-i18next (en/zh)
- **Markdown**: react-markdown + rehype-highlight / rehype-sanitize + remark-math

## Dev Commands

```bash
npm run dev          # Vite dev server (Tauri mode)
npm run dev:web      # Vite dev server (web-only mode, no Tauri)
npm run build        # Production build (Tauri)
npm run build:web    # Production build (web, output: dist-web/)
npm run tauri dev    # Full Tauri desktop app
```

## Project Structure

```
src/
  App.tsx             # Main app with routing, multi-agent toggle
  views/              # Page views (ChatView, AgentManagerView, MCPView, etc.)
  components/         # Shared UI components
  hooks/              # React hooks
  i18n/               # Internationalization (en.json, zh.json)
  platform/           # Platform abstraction layer (Tauri vs Web)
src-tauri/            # Rust backend for Tauri
  src/main.rs         # Tauri entry point
  tauri.conf.json     # Tauri config
dist-web/             # Web build output (git-ignored; staged into the wheel when packaging)
```

## Key Conventions

- **Platform abstraction**: Use `platform/` APIs for filesystem, dialogs, and process operations — never import `@tauri-apps/*` directly in views.
- **i18n keys**: All user-facing text must use `t('key')` from react-i18next. Add keys to both `en.json` and `zh.json`.
- **API calls**: Backend API is at `http://localhost:{port}/api/`. Port comes from the workspace config or defaults to **18900**.
- **Web build**: `dist-web/` is a build output and is git-ignored. Run `npm run build:web`; packaging stages it into the wheel via `scripts/stage_package_assets.py`, so never commit it.
- **Mobile**: Capacitor scripts (`cap:android`, `cap:ios`, `cap:sync`) exist, but `android/`, `ios/` and `capacitor.config.ts` are local-only (git-ignored) — do not assume they exist on a clean clone.
