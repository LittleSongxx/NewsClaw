import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import "../../i18n";
import i18n from "../../i18n";
import { safeFetch } from "../../providers";
import { NewsroomView } from "../NewsroomView";

vi.mock("../../providers", () => ({ safeFetch: vi.fn() }));

const jsonResponse = (payload: object, status = 200) =>
  new Response(JSON.stringify(payload), { status });

const STATUS_PAYLOAD = {
  config: { enabled: true, error: "" },
  tasks: {
    daily: {
      present: true,
      enabled: true,
      cron: "0 8 * * *",
      silent: true,
      prompt_drift: false,
      prompt_version: 19,
      prompt_version_current: true,
    },
    review: { present: false },
  },
  last_issue: {
    date: "2026-09-18",
    status: "ready",
    title: "AI 早报 #9",
    delivered: true,
    feishu_doc_url: "",
  },
  proposal: { status: "pending", remaining_ops: 2 },
  feedback: { up: 3, down: 1, item_up: 0, item_down: 2 },
  dedup_pool: { window_days: 7, seen_urls: 21 },
};

beforeEach(async () => {
  vi.mocked(safeFetch).mockReset();
  await i18n.changeLanguage("zh");
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("NewsroomView mainline health card", () => {
  it("renders the aggregated status from /api/newsroom/status", async () => {
    vi.mocked(safeFetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/newsroom/status")) return jsonResponse(STATUS_PAYLOAD);
      if (url.endsWith("/api/newsroom/issues")) return jsonResponse({ issues: [] });
      if (url.endsWith("/api/newsroom/proposal")) return jsonResponse({ status: "none", ops: [] });
      if (url.endsWith("/api/newsroom/editorial-policy")) return jsonResponse({ text: "" });
      if (url.endsWith("/api/newsroom/config")) return jsonResponse({ enabled: true });
      if (url.endsWith("/api/newsroom/sources")) return jsonResponse({ yaml: "" });
      return jsonResponse({}, 200);
    });

    render(<NewsroomView serviceRunning apiBaseUrl="" />);

    await waitFor(() => {
      expect(screen.getByText(i18n.t("newsroom.statusTitle"))).toBeInTheDocument();
    });
    // 聚合数字来自 status 载荷，而不是期次列表
    expect(screen.getByText("2026-09-18 · ready")).toBeInTheDocument();
    expect(screen.getByText("pending · 2")).toBeInTheDocument();
    expect(screen.getByText("21 / 7d")).toBeInTheDocument();
  });

  it("flags prompt drift with a destructive badge", async () => {
    vi.mocked(safeFetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/newsroom/status")) {
        return jsonResponse({
          ...STATUS_PAYLOAD,
          tasks: {
            daily: { ...STATUS_PAYLOAD.tasks.daily, prompt_drift: true },
            review: { present: false },
          },
        });
      }
      if (url.endsWith("/api/newsroom/issues")) return jsonResponse({ issues: [] });
      if (url.endsWith("/api/newsroom/proposal")) return jsonResponse({ status: "none", ops: [] });
      if (url.endsWith("/api/newsroom/editorial-policy")) return jsonResponse({ text: "" });
      if (url.endsWith("/api/newsroom/config")) return jsonResponse({ enabled: true });
      if (url.endsWith("/api/newsroom/sources")) return jsonResponse({ yaml: "" });
      return jsonResponse({}, 200);
    });

    render(<NewsroomView serviceRunning apiBaseUrl="" />);

    await waitFor(() => {
      expect(screen.getByText(i18n.t("newsroom.statusPromptDrift"))).toBeInTheDocument();
    });
  });
});
