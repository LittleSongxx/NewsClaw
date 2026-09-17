import { describe, expect, it } from "vitest";

import tauriConfig from "../../../src-tauri/tauri.conf.json";

describe("desktop updater configuration", () => {
  it("ships no static updater endpoint and keeps the signing key for future use", () => {
    // NewsClaw 是本地分支：不再指向任何上游更新服务（否则会把上游的发行版
    // 当作本产品的新版本推给用户）。运行时更新检查回退到本仓库的
    // GitHub Releases，见 hooks/useVersionCheck.ts 的 GITHUB_REPO。
    expect(tauriConfig.plugins.updater.endpoints).toEqual([]);
    expect(tauriConfig.plugins.updater.pubkey).toBe(
      "dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IDQ1RTQ1NjM2RkMxQ0Y4MDMKUldRRCtCejhObGJrUmR2VWdtbDMwSmhqdlE2RURSYTJKUTIxV25wRE1mcFA0Sy82Vi9zbUo3YWQK",
    );
  });
});
