import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  AUTH_EXPIRED_EVENT,
  clearAccessToken,
  refreshAccessToken,
  setAccessToken,
  setLocalAuthMode,
} from "../auth";

describe("refreshAccessToken", () => {
  beforeEach(() => {
    const store = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => {
        store.set(key, value);
      },
      removeItem: (key: string) => {
        store.delete(key);
      },
      clear: () => store.clear(),
    });
    clearAccessToken();
    setLocalAuthMode(false);
  });

  afterEach(() => {
    clearAccessToken();
    vi.unstubAllGlobals();
  });

  it("does not treat a failed refresh as logout when the user never had a token", async () => {
    const expired = vi.fn();
    window.addEventListener(AUTH_EXPIRED_EVENT, expired);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("{}", { status: 401 })),
    );

    await expect(refreshAccessToken("")).resolves.toBeNull();
    expect(expired).not.toHaveBeenCalled();

    window.removeEventListener(AUTH_EXPIRED_EVENT, expired);
  });

  it("expires the session when a real token cannot be refreshed", async () => {
    setAccessToken("stale-token");
    const expired = vi.fn();
    window.addEventListener(AUTH_EXPIRED_EVENT, expired);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("{}", { status: 401 })),
    );

    await expect(refreshAccessToken("")).resolves.toBeNull();
    expect(expired).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem("newsclaw_access_token")).toBeNull();

    window.removeEventListener(AUTH_EXPIRED_EVENT, expired);
  });
});
