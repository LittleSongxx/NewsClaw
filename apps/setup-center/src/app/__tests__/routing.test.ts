import { describe, expect, it } from "vitest";
import { parseHashRoute, viewToHash } from "../routing";

describe("app hash routing", () => {
  it("maps newsroom and wiki hashes", () => {
    expect(parseHashRoute("#/newsroom")).toEqual({ view: "newsroom" });
    expect(parseHashRoute("#/wiki")).toEqual({ view: "wiki" });
    expect(viewToHash("newsroom")).toBe("#/newsroom");
  });

  it("maps wizard config steps and plugin apps", () => {
    expect(parseHashRoute("#/config/llm")).toEqual({ view: "wizard", stepId: "llm" });
    expect(viewToHash("wizard", "im")).toBe("#/config/im");
    expect(parseHashRoute("#/app/demo")).toEqual({ view: "plugin_app:demo" });
    expect(viewToHash("plugin_app:demo")).toBe("#/app/demo");
  });
});
