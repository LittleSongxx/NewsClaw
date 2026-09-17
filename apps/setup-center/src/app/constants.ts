/** 后端健康检查与本地服务启动等待（从 App.tsx 拆出，避免和路由/安装逻辑缠在一起）。 */

export const HEALTH_POLL_TIMEOUT_MS = 5_000;
export const DEFAULT_LOCAL_API_BASE = "http://127.0.0.1:18900";
export const LOCAL_SERVICE_READY_TIMEOUT_MS = 120_000;
export const ONBOARDING_HTTP_READY_TIMEOUT_MS = 180_000;
export const HTTP_READY_POLL_INTERVAL_MS = 2_000;
export const BACKEND_STARTUP_HOLD_MS = 180_000;
export const BACKEND_STARTUP_PROBE_HOLD_MS = 30_000;
