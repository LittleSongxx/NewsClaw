// Vitest setup: import jest-dom matchers so React component tests can use
// .toBeInTheDocument() / .toHaveTextContent() / etc. on jsdom nodes.
import "@testing-library/jest-dom/vitest";

// 运行时设计是「拒绝臆造官方 marketplace 源」（navigation.ts 未配置即抛
// marketplace_origin_unconfigured）；但 marketplace 相关组件测试假定源存在。
// 测试环境统一给一个确定占位源——navigation.test.ts 用 vi.stubEnv("") 显式
// 覆盖为空的用例仍然照常验证「未配置即抛」。
import.meta.env.VITE_MARKETPLACE_URL ||= "https://marketplace.test.local";

// 本 vitest 环境里 localStorage 会被依赖副作用替换成裸对象（原型是
// Object、无 setItem/getItem），auth.getAccessToken 及其下游（marketplace、
// Sidebar 账号态、useVersionCheck、AttachmentPreview）全部炸在它上面。
// setup 阶段补一个完整的内存 Storage 垫片，恢复真实存储 API 形状。
if (typeof localStorage === "undefined" || typeof localStorage.setItem !== "function") {
  const mem = new Map<string, string>();
  const shim: Storage = {
    get length() {
      return mem.size;
    },
    clear: () => mem.clear(),
    getItem: (key: string) => (mem.has(key) ? mem.get(key)! : null),
    key: (index: number) => Array.from(mem.keys())[index] ?? null,
    removeItem: (key: string) => void mem.delete(key),
    setItem: (key: string, value: string) => void mem.set(key, String(value)),
  };
  Object.defineProperty(globalThis, "localStorage", {
    value: shim,
    configurable: true,
    writable: true,
  });
  if (typeof window !== "undefined") {
    Object.defineProperty(window, "localStorage", {
      value: shim,
      configurable: true,
      writable: true,
    });
  }
}
