import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { FolderOpen, Loader2, RefreshCw, Search, Link2, Network, List } from "lucide-react";
import { safeFetch } from "../providers";
import { MarkdownContent } from "./chat/components/MarkdownContent";
import { WikiGraph, type GraphLink, type GraphNode } from "../components/WikiGraph";
import { useMdModules } from "./chat/hooks/useMdModules";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { toast } from "sonner";

/**
 * 本地 Wiki 知识库（/api/wiki/*）。
 *
 * 数据源是本地 Markdown 知识库（默认 data/wiki/，也可指向你的 Obsidian 库）：
 * 每期早报的要点由 wiki_upsert 工具沉淀为「主题 / 公司」原子页，页内按日期分节、
 * 页面之间用 [[双链]] 互链，MOC.md 是自动维护的索引页。
 *
 * 布局：左=页面树（按类型分组 + 过滤），右=正文渲染 + 反向链接 + 相对路径。
 */

type WikiPage = {
  name: string;
  path: string;
  kind: "topic" | "company" | "index" | string;
  updated_at: string;
  dates: string[];
  links: string[];
  excerpt: string;
};

type WikiInfo = {
  root: string;
  exists: boolean;
  page_count: number;
  topic_count: number;
  company_count: number;
};

type PageDetail = {
  name: string;
  path: string;
  content: string;
  backlinks: string[];
};


/** 把 Obsidian 风格的 [[双链]] 变成可点击的内部链接（wiki: 协议由容器拦截）。 */
function withWikiLinks(markdown: string): string {
  return markdown.replace(
    /\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]/g,
    (_match, target: string, label?: string) =>
      `[${(label || target).trim()}](wiki:${encodeURIComponent(target.trim())})`,
  );
}

export function WikiView({ serviceRunning, apiBaseUrl = "" }: { serviceRunning: boolean; apiBaseUrl?: string }) {
  const API_BASE = apiBaseUrl;
  const { t } = useTranslation();
  const mdModules = useMdModules();

  const [info, setInfo] = useState<WikiInfo | null>(null);
  const [pages, setPages] = useState<WikiPage[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<string>("");
  const [detail, setDetail] = useState<PageDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [query, setQuery] = useState("");
  const [mode, setMode] = useState<"list" | "graph">("list");
  const [graph, setGraph] = useState<{ nodes: GraphNode[]; links: GraphLink[] } | null>(null);
  const [days, setDays] = useState(0); // 0 = 全部；7 / 30 = 只看最近 N 期内活跃的页面与关系

  const fetchIndex = useCallback(async () => {
    setLoading(true);
    try {
      const [infoRes, pagesRes] = await Promise.all([
        safeFetch(`${API_BASE}/api/wiki/root`),
        safeFetch(`${API_BASE}/api/wiki/pages`),
      ]);
      if (infoRes.ok) setInfo(await infoRes.json());
      if (pagesRes.ok) {
        const data = await pagesRes.json();
        setPages(data.pages || []);
      }
    } catch {
      setPages([]);
    } finally {
      setLoading(false);
    }
  }, [API_BASE]);

  const fetchPage = useCallback(
    async (name: string) => {
      setDetailLoading(true);
      try {
        const res = await safeFetch(`${API_BASE}/api/wiki/page?name=${encodeURIComponent(name)}`);
        if (!res.ok) {
          setDetail(null);
          return;
        }
        setDetail(await res.json());
      } catch {
        setDetail(null);
      } finally {
        setDetailLoading(false);
      }
    },
    [API_BASE],
  );

  const fetchGraph = useCallback(async (windowDays: number) => {
    try {
      const qs = windowDays > 0 ? `?days=${windowDays}` : "";
      const res = await safeFetch(`${API_BASE}/api/wiki/graph${qs}`);
      if (res.ok) setGraph(await res.json());
    } catch {
      setGraph({ nodes: [], links: [] });
    }
  }, [API_BASE]);

  useEffect(() => {
    if (!serviceRunning) return;
    void fetchIndex();
  }, [serviceRunning, fetchIndex]);

  useEffect(() => {
    if (serviceRunning && mode === "graph") void fetchGraph(days);
  }, [serviceRunning, mode, days, fetchGraph]);

  useEffect(() => {
    if (selected) void fetchPage(selected);
  }, [selected, fetchPage]);

  // 自动选中第一个内容页（索引页不作为默认）
  useEffect(() => {
    if (selected || loading) return;
    const first = pages.find((p) => p.kind !== "index") || pages[0];
    if (first) setSelected(first.name);
  }, [pages, selected, loading]);

  const groups = useMemo(() => {
    const kw = query.trim().toLowerCase();
    const visible = kw
      ? pages.filter(
          (p) =>
            p.name.toLowerCase().includes(kw) ||
            p.excerpt.toLowerCase().includes(kw) ||
            p.links.some((l) => l.toLowerCase().includes(kw)),
        )
      : pages;
    return {
      topic: visible.filter((p) => p.kind === "topic"),
      company: visible.filter((p) => p.kind === "company"),
      index: visible.filter((p) => p.kind === "index"),
    };
  }, [pages, query]);

  const copyRootPath = useCallback(() => {
    if (!info?.root) return;
    navigator.clipboard?.writeText(info.root).then(
      () => toast.success(t("wiki.pathCopied")),
      () => toast.error(t("wiki.loadFailed")),
    );
  }, [info?.root, t]);

  if (!serviceRunning) {
    return (
      <div className="mx-auto max-w-[1200px] px-6 py-5">
        <Card className="border-border/50 shadow-sm">
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            {t("wiki.serviceDown")}
          </CardContent>
        </Card>
      </div>
    );
  }

  const paneHeight = "lg:h-[calc(100vh-240px)] lg:min-h-[520px]";

  const renderGroup = (title: string, items: WikiPage[]) =>
    items.length > 0 && (
      <div className="mb-3">
        <div className="px-3 py-1 text-[11px] font-medium tracking-wide text-muted-foreground/70">
          {title}
        </div>
        {items.map((page) => (
          <button
            key={page.path}
            onClick={() => setSelected(page.name)}
            className={`w-full text-left px-3 py-2 rounded-md text-sm transition-colors ${
              page.name === selected ? "bg-accent" : "hover:bg-accent/50"
            }`}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium truncate">{page.name}</span>
              {page.dates.length > 0 && (
                <span className="text-[10px] text-muted-foreground tabular-nums shrink-0">
                  {t("wiki.issuesCount", { count: page.dates.length })}
                </span>
              )}
            </div>
            {page.excerpt && (
              <div className="text-xs text-muted-foreground truncate mt-0.5">{page.excerpt}</div>
            )}
          </button>
        ))}
      </div>
    );

  const totalShown = groups.topic.length + groups.company.length + groups.index.length;

  return (
    <div className="mx-auto max-w-[1200px] space-y-5 px-6 py-5">
      {/* ── 头部：标题 + 库路径 + 刷新 ── */}
      <div className="flex items-start justify-between gap-4">
        <div className="space-y-1.5 min-w-0">
          <h2 className="truncate text-lg font-bold tracking-tight" title={t("wiki.title")}>
            {t("wiki.title")}
          </h2>
          <p className="truncate text-xs text-muted-foreground leading-relaxed" title={info?.root}>
            {info
              ? t("wiki.subtitle", {
                  topics: info.topic_count,
                  companies: info.company_count,
                })
              : t("wiki.loadingText")}
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0 pt-0.5">
          <div className="flex rounded-md border border-border overflow-hidden">
            <button
              onClick={() => setMode("list")}
              className={`px-2.5 h-8 text-xs inline-flex items-center gap-1 ${mode === "list" ? "bg-accent" : "hover:bg-accent/50"}`}
            >
              <List size={13} /> {t("wiki.modeList")}
            </button>
            <button
              onClick={() => setMode("graph")}
              className={`px-2.5 h-8 text-xs inline-flex items-center gap-1 border-l border-border ${mode === "graph" ? "bg-accent" : "hover:bg-accent/50"}`}
            >
              <Network size={13} /> {t("wiki.modeGraph")}
            </button>
          </div>
          <Button variant="outline" size="sm" onClick={copyRootPath} disabled={!info?.root}>
            <FolderOpen size={14} className="mr-1" /> {t("wiki.copyPath")}
          </Button>
          <Button variant="outline" size="sm" onClick={() => void fetchIndex()}>
            <RefreshCw size={14} className="mr-1" /> {t("common.refresh", "刷新")}
          </Button>
        </div>
      </div>

      {mode === "graph" ? (
        <Card className="p-0 gap-0 border-border/50 shadow-sm">
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2 pt-3 px-4">
            <div className="flex items-center gap-1">
              {[
                { value: 0, labelKey: "wiki.rangeAll" },
                { value: 7, labelKey: "wiki.range7" },
                { value: 30, labelKey: "wiki.range30" },
              ].map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => setDays(opt.value)}
                  className={`px-2 h-7 text-xs rounded-md border transition-colors ${
                    days === opt.value
                      ? "border-primary bg-accent font-medium"
                      : "border-transparent text-muted-foreground hover:bg-accent/50"
                  }`}
                >
                  {t(opt.labelKey)}
                </button>
              ))}
            </div>
            {graph && (
              <span className="text-[11px] text-muted-foreground tabular-nums">
                {t("wiki.graphStats", { pages: graph.nodes.length, links: graph.links.length })}
              </span>
            )}
          </CardHeader>
          <CardContent className="p-3 pt-0">
            {graph && graph.nodes.length > 0 ? (
              <WikiGraph
                nodes={graph.nodes}
                links={graph.links}
                selected={selected}
                onSelect={(name) => {
                  setSelected(name);
                  setMode("list");
                }}
              />
            ) : (
              <p className="text-xs text-muted-foreground py-16 text-center">
                {t("wiki.empty")}
              </p>
            )}
          </CardContent>
        </Card>
      ) : (
      <div className="grid grid-cols-1 lg:grid-cols-[300px_1fr] gap-4 items-stretch">
        {/* ── 左列：页面树 ── */}
        <Card className={`p-0 gap-0 border-border/50 shadow-sm flex flex-col ${paneHeight}`}>
          <CardHeader className="space-y-2 pb-2 pt-4 px-5 shrink-0">
            <CardTitle className="text-xs font-medium text-muted-foreground">
              {t("wiki.pages")}
            </CardTitle>
            <div className="relative">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t("wiki.filterPlaceholder")}
                className="pl-9 h-8 text-sm"
              />
            </div>
          </CardHeader>
          <CardContent className="px-2 pb-3 pt-0 flex-1 overflow-y-auto custom-scrollbar">
            {loading ? (
              <div className="flex justify-center py-8">
                <Loader2 className="animate-spin" size={18} />
              </div>
            ) : pages.length === 0 ? (
              <p className="text-xs text-muted-foreground p-4 text-center leading-relaxed">
                {t("wiki.empty")}
              </p>
            ) : totalShown === 0 ? (
              <p className="text-xs text-muted-foreground p-4 text-center">{t("wiki.noMatch")}</p>
            ) : (
              <>
                {renderGroup(t("wiki.groupTopic"), groups.topic)}
                {renderGroup(t("wiki.groupCompany"), groups.company)}
                {renderGroup(t("wiki.groupIndex"), groups.index)}
              </>
            )}
          </CardContent>
        </Card>

        {/* ── 右列：正文 ── */}
        <Card className={`p-0 gap-0 border-border/50 shadow-sm flex flex-col ${paneHeight}`}>
          {!selected ? (
            <CardContent className="flex-1 flex items-center justify-center text-sm text-muted-foreground">
              {t("wiki.empty")}
            </CardContent>
          ) : detailLoading ? (
            <CardContent className="flex-1 flex items-center justify-center">
              <Loader2 className="animate-spin" size={18} />
            </CardContent>
          ) : (
            <>
              <CardHeader className="space-y-2 pb-3 pt-4 px-5 shrink-0">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <CardTitle className="text-sm font-semibold truncate">
                    {detail?.name || selected}
                  </CardTitle>
                  <span className="text-[11px] text-muted-foreground font-mono shrink-0">
                    {detail?.path}
                  </span>
                </div>
                {detail && detail.backlinks.length > 0 && (
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-[11px] text-muted-foreground inline-flex items-center gap-1">
                      <Link2 size={12} /> {t("wiki.backlinks")}
                    </span>
                    {detail.backlinks.map((name) => (
                      <button key={name} onClick={() => setSelected(name)}>
                        <Badge variant="secondary" className="text-[10px] font-normal cursor-pointer">
                          {name}
                        </Badge>
                      </button>
                    ))}
                  </div>
                )}
              </CardHeader>
              <CardContent className="px-5 pb-5 flex-1 overflow-y-auto custom-scrollbar">
                <div
                  className="docMdContent"
                  onClick={(e) => {
                    // [[双链]] 被渲染成 wiki:<页面名> 链接，这里拦截为视图内跳转
                    const anchor = (e.target as HTMLElement).closest("a");
                    const href = anchor?.getAttribute("href") || "";
                    if (href.startsWith("wiki:")) {
                      e.preventDefault();
                      setSelected(decodeURIComponent(href.slice(5)));
                    }
                  }}
                >
                  {/* frontmatter 由卡片头部呈现，正文里剥掉 */}
                  <MarkdownContent
                    content={withWikiLinks((detail?.content || "").replace(/^---\n[\s\S]*?\n---\n/, ""))}
                    mdModules={mdModules}
                    apiBaseUrl={API_BASE}
                  />
                </div>
              </CardContent>
            </>
          )}
        </Card>
      </div>
      )}
    </div>
  );
}
