import { afterEach, describe, expect, it, vi } from "vitest";
import {
  buildMarketplaceContextUrl,
  buildMarketplaceContextUrlFromDeepLink,
  buildMarketplaceHandoffUrl,
  hasMarketplaceClientVersion,
  marketplaceDeepLinkAction,
  normalizeMarketplaceClientVersion,
} from "../navigation";

describe("Marketplace navigation", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("normalizes the desktop version to the Marketplace contract", () => {
    expect(normalizeMarketplaceClientVersion("v01.027.004-beta.1")).toBe("1.27.4");
    expect(normalizeMarketplaceClientVersion("1.27")).toBeNull();
    expect(hasMarketplaceClientVersion("0.0.0")).toBe(false);
    expect(hasMarketplaceClientVersion("1.27.40")).toBe(true);
  });

  it("refuses to invent an official marketplace origin", () => {
    vi.stubEnv("VITE_MARKETPLACE_URL", "");
    expect(() => buildMarketplaceContextUrl("1.27.40")).toThrow("marketplace_origin_unconfigured");
  });

  it("opens the Marketplace home through the client context endpoint", () => {
    const origin = "https://market.example.com";
    const result = new URL(buildMarketplaceContextUrl("1.27.40", "/", origin));
    expect(result.origin).toBe(origin);
    expect(result.pathname).toBe("/newsclaw/context");
    expect(result.searchParams.get("version")).toBe("1.27.40");
    expect(result.searchParams.get("next")).toBe("/");
  });

  it("restores a clean Marketplace detail URL from an open deep link", () => {
    const origin = "https://market.example.com";
    const deepLink = "newsclaw://marketplace/open?return_url="
      + encodeURIComponent(`${origin}/resources/example?tab=versions#versions`);
    const result = new URL(buildMarketplaceContextUrlFromDeepLink(deepLink, "1.27.40", origin)!);

    expect(result.origin).toBe(origin);
    expect(result.pathname).toBe("/newsclaw/context");
    expect(result.searchParams.get("version")).toBe("1.27.40");
    expect(result.searchParams.get("next")).toBe("/resources/example?tab=versions#versions");
  });

  it("opens a restricted handoff before storing client context without leaking the ticket into it", () => {
    const origin = "https://market.example.com";
    const handoff = new URL(buildMarketplaceHandoffUrl("v1.27.40-beta.1", "t".repeat(64), "/", origin));
    expect(handoff.origin).toBe(origin);
    expect(handoff.pathname).toBe("/auth/desktop");
    expect(handoff.searchParams.get("ticket")).toBe("t".repeat(64));
    const context = new URL(handoff.searchParams.get("next")!, handoff.origin);
    expect(context.pathname).toBe("/newsclaw/context");
    expect(context.searchParams.get("version")).toBe("1.27.40");
    expect(context.searchParams.get("next")).toBe("/");
    expect(context.searchParams.has("ticket")).toBe(false);
  });

  it("preserves a detail destination through both redirects on a configured origin", () => {
    const next = "/resources/example?tab=versions&sort=newest#versions";
    const handoff = new URL(buildMarketplaceHandoffUrl("1.27.40", "t".repeat(64), next, "http://localhost:3001"));
    expect(handoff.origin).toBe("http://localhost:3001");
    const context = new URL(handoff.searchParams.get("next")!, handoff.origin);
    expect(context.searchParams.get("next")).toBe(next);
  });

  it.each(["https://evil.example/", "//evil.example/", "/\\evil.example/", "/newsclaw/context?version=1.0.0"])(
    "sanitizes the post-login destination %s",
    (next) => {
      const handoff = new URL(buildMarketplaceHandoffUrl("1.27.40", "t".repeat(64), next, "https://market.example.com"));
      const context = new URL(handoff.searchParams.get("next")!, handoff.origin);
      expect(context.searchParams.get("next")).toBe("/");
    },
  );

  it("allows a configured Marketplace origin and local development origins", () => {
    const configured = "https://market.example.com";
    const configuredLink = "newsclaw://marketplace/open?return_url="
      + encodeURIComponent("https://market.example.com/catalog");
    const localLink = "newsclaw://marketplace/open?return_url="
      + encodeURIComponent("http://localhost:3001/catalog");

    expect(buildMarketplaceContextUrlFromDeepLink(configuredLink, "1.27.40", configured))
      .toContain("https://market.example.com/newsclaw/context?");
    expect(buildMarketplaceContextUrlFromDeepLink(localLink, "1.27.40", configured))
      .toContain("http://localhost:3001/newsclaw/context?");
  });

  it("rejects untrusted return URLs and unsupported actions", () => {
    const malicious = "newsclaw://marketplace/open?return_url="
      + encodeURIComponent("https://evil.example/resources/example");

    expect(buildMarketplaceContextUrlFromDeepLink(malicious, "1.27.40")).toBeNull();
    expect(marketplaceDeepLinkAction("newsclaw://marketplace/open?return_url=x")).toBe("open");
    expect(marketplaceDeepLinkAction("newsclaw://marketplace/install?token=x")).toBe("install");
    expect(marketplaceDeepLinkAction("newsclaw://marketplace/unknown")).toBeNull();
  });

  it("prevents recursive client context targets", () => {
    const result = new URL(buildMarketplaceContextUrl(
      "1.27.40",
      "/newsclaw/context?version=0.0.0&next=/catalog",
      "https://market.example.com",
    ));
    expect(result.searchParams.get("next")).toBe("/");
  });
});
