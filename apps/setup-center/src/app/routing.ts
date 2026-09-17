import type { StepId, ViewId } from "../types";

export const HASH_TO_VIEW: Record<string, ViewId> = {
  "chat": "chat", "im": "im", "skills": "skills", "mcp": "mcp", "knowledge": "knowledge",
  "scheduler": "scheduler", "memory": "memory", "status": "status",
  "newsroom": "newsroom", "wiki": "wiki",
  "token-stats": "token_stats", "skill-usage": "skill_usage", "identity": "identity",
  "dashboard": "dashboard", "org-editor": "org_editor",
  "pixel-office": "pixel_office",
  "agent-manager": "agent_manager", "agent-store": "agent_store",
  "skill-store": "skill_store", "wizard": "wizard", "docs": "docs",
  "security": "security", "pending-approvals": "pending_approvals",
  "plugins": "plugins", "my_feedback": "my_feedback",
};

export const VIEW_TO_HASH: Record<string, string> = Object.fromEntries(
  Object.entries(HASH_TO_VIEW).map(([k, v]) => [v, k]),
);

export const HASH_TO_STEP: Record<string, StepId> = {
  "llm": "llm", "im": "im", "tools": "tools", "agent": "agent", "advanced": "advanced",
};

export function parseHashRoute(hash: string): { view: ViewId; stepId?: StepId } | null {
  const path = hash.replace(/^#\/?/, "");
  if (path.startsWith("skills?")) return { view: "skills" };
  if (!path) return null;
  if (HASH_TO_VIEW[path]) return { view: HASH_TO_VIEW[path] };
  if (path.startsWith("config/")) {
    const step = path.slice(7);
    if (HASH_TO_STEP[step]) return { view: "wizard", stepId: HASH_TO_STEP[step] as StepId };
  }
  if (path.startsWith("app/")) {
    const pluginId = path.slice(4);
    if (pluginId) return { view: `plugin_app:${pluginId}` as ViewId };
  }
  return null;
}

export function viewToHash(view: string, stepId?: string): string {
  if (view === "wizard" && stepId) {
    return `#/config/${stepId}`;
  }
  if (view.startsWith("plugin_app:")) {
    return `#/app/${view.slice("plugin_app:".length)}`;
  }
  return VIEW_TO_HASH[view] ? `#/${VIEW_TO_HASH[view]}` : "";
}
