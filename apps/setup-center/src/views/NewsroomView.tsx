import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Loader2, RefreshCw, Settings2, Sparkles, ThumbsDown, ThumbsUp } from "lucide-react";
import { Checkbox } from "@/components/ui/checkbox";
import { safeFetch } from "../providers";
import { encodePathSegment } from "../platform/apiUrl";
import { MarkdownContent } from "./chat/components/MarkdownContent";
import { useMdModules } from "./chat/hooks/useMdModules";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { Sheet, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { toast } from "sonner";

/**
 * AI 早报工作台（/api/newsroom/*）。
 *
 * 布局：左侧期次列表 + 右侧本期详情，两栏等高对齐；运行设置与信源清单
 * 收进头部「设置」抽屉，不再与正文并排（避免不等高卡片错位）。
 *
 * 数据对应关系：
 *  - 期次列表 GET /issues，详情 GET /issues/{date}（三产物 + manifest）
 *  - 反馈 POST /feedback（点赞/点踩 + 短评）——每周复盘任务的输入信号
 *  - 设置 GET/PUT /config，信源 GET/PUT /sources（YAML 直编）
 *  - 本周提案 GET /proposal，勾选后 POST /proposal/apply 或 reject
 *  - 手动触发 POST /generate（复用调度器后台执行，前端轮询 execution）
 */

type ScoreEntry = { score: number; rationale: string };

type IssueSummary = {
  issue_date: string;
  title: string;
  status: "ready" | "partial" | string;
  manifest_error?: string | null;
  sources_used: string[];
  wiki_entries: string[];
  feishu_doc_url?: string;
  scores: Record<string, ScoreEntry>;
  feedback: { rating?: number; comment?: string };
  artifacts: Record<string, boolean>;
  created_at: string;
};

type IssueDetail = {
  manifest: IssueSummary | null;
  content: { "daily-brief": string | null; xiaohongshu: string | null; wechat: string | null };
  manifest_error?: string | null;
};

type NewsroomConfigDto = {
  enabled: boolean;
  daily_cron: string;
  review_cron: string;
  obsidian_vault: string;
  issue_history_days: number;
  task_timeout_seconds: number;
  load_error?: string;
};

type ProposalEvidence = { issue_date?: string; dimension?: string; note?: string };

type ProposalOp = {
  id: string;
  kind: "source" | "policy_bullet" | "memory_rule" | string;
  action: string;
  name?: string;
  summary: string;
  requires_explicit_select?: boolean;
  evidence?: ProposalEvidence | null;
};

type ProposalDto = {
  status: "none" | "invalid" | "pending" | "applied" | "rejected" | string;
  parse_error: string | null;
  fingerprint?: string;
  applied_op_ids?: string[];
  remaining_op_ids?: string[];
  last_memory_error?: string | null;
  ops: ProposalOp[];
};

const ARTIFACT_TABS = [
  { key: "daily-brief", labelKey: "newsroom.tabDaily" },
  { key: "xiaohongshu", labelKey: "newsroom.tabXhs" },
  { key: "wechat", labelKey: "newsroom.tabWechat" },
] as const;

export function NewsroomView({ serviceRunning, apiBaseUrl = "" }: { serviceRunning: boolean; apiBaseUrl?: string }) {
  const API_BASE = apiBaseUrl;
  const { t } = useTranslation();
  // 与 ChatView 一致：MarkdownContent 需要渲染模块（懒加载），缺省时退化为纯文本
  const mdModules = useMdModules();

  const [issues, setIssues] = useState<IssueSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [detail, setDetail] = useState<IssueDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [activeTab, setActiveTab] = useState<(typeof ARTIFACT_TABS)[number]["key"]>("daily-brief");
  const [generating, setGenerating] = useState(false);

  const [settingsOpen, setSettingsOpen] = useState(false);
  const [config, setConfig] = useState<NewsroomConfigDto | null>(null);
  const [sourcesYaml, setSourcesYaml] = useState("");
  const [policyText, setPolicyText] = useState("");
  const [proposal, setProposal] = useState<ProposalDto | null>(null);
  const [selectedOpIds, setSelectedOpIds] = useState<string[]>([]);
  const [savingConfig, setSavingConfig] = useState(false);
  const [savingSources, setSavingSources] = useState(false);
  const [applyingProposal, setApplyingProposal] = useState(false);

  // 反馈表单状态跟随选中期（manifest.feedback 是既有值）
  const [fbRating, setFbRating] = useState<number | null>(null);
  const [fbComment, setFbComment] = useState("");

  const fetchIssues = useCallback(async (autoSelect = false) => {
    setLoading(true);
    try {
      const res = await safeFetch(`${API_BASE}/api/newsroom/issues`);
      const data = await res.json();
      const list: IssueSummary[] = data.issues || [];
      setIssues(list);
      if (autoSelect && list.length > 0) {
        setSelectedDate((prev) => prev ?? list[0].issue_date);
      }
    } catch {
      setIssues([]);
    } finally {
      setLoading(false);
    }
  }, [API_BASE]);

  const fetchDetail = useCallback(async (date: string) => {
    setDetailLoading(true);
    try {
      const res = await safeFetch(`${API_BASE}/api/newsroom/issues/${encodePathSegment(date)}`);
      if (!res.ok) {
        setDetail(null);
        return;
      }
      const data: IssueDetail = await res.json();
      setDetail(data);
      const fb = data.manifest?.feedback || {};
      setFbRating(typeof fb.rating === "number" ? fb.rating : null);
      setFbComment(fb.comment || "");
      // 默认落在第一个可用产物 Tab
      const available = ARTIFACT_TABS.find((tab) => data.content?.[tab.key])?.key;
      if (available) setActiveTab(available);
    } catch {
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }, [API_BASE]);

  const fetchProposal = useCallback(async () => {
    try {
      const [propRes, policyRes] = await Promise.all([
        safeFetch(`${API_BASE}/api/newsroom/proposal`),
        safeFetch(`${API_BASE}/api/newsroom/editorial-policy`),
      ]);
      if (propRes.ok) {
        const data = (await propRes.json()) as ProposalDto;
        setProposal(data);
        if (data.status === "pending") {
          const applied = new Set(data.applied_op_ids || []);
          setSelectedOpIds(
            (data.ops || [])
              .filter(
                (op) =>
                  applied.has(op.id) || (op.kind !== "source" && op.kind !== "memory_rule"),
              )
              .map((op) => op.id),
          );
        } else if (data.status === "applied") {
          setSelectedOpIds(data.applied_op_ids || []);
        } else {
          setSelectedOpIds([]);
        }
      }
      if (policyRes.ok) {
        const data = await policyRes.json();
        setPolicyText(typeof data.text === "string" ? data.text : "");
      }
    } catch {
      /* 服务未就绪时保持空态 */
    }
  }, [API_BASE]);

  const fetchSettings = useCallback(async () => {
    try {
      const [cfgRes, srcRes] = await Promise.all([
        safeFetch(`${API_BASE}/api/newsroom/config`),
        safeFetch(`${API_BASE}/api/newsroom/sources`),
      ]);
      if (cfgRes.ok) setConfig(await cfgRes.json());
      if (srcRes.ok) {
        const data = await srcRes.json();
        // 信源以 YAML 文本直编（与磁盘 sources.yaml 同构，后端解析校验）
        setSourcesYaml(typeof data.yaml === "string" ? data.yaml : "");
      }
      await fetchProposal();
    } catch {
      /* 服务未就绪时保持空态 */
    }
  }, [API_BASE, fetchProposal]);

  useEffect(() => {
    if (!serviceRunning) return;
    void fetchIssues(true);
    void fetchSettings();
  }, [serviceRunning, fetchIssues, fetchSettings]);

  useEffect(() => {
    if (selectedDate) void fetchDetail(selectedDate);
  }, [selectedDate, fetchDetail]);

  const generateNow = useCallback(async () => {
    setGenerating(true);
    try {
      const res = await safeFetch(`${API_BASE}/api/newsroom/generate`, { method: "POST" });
      if (res.status !== 202) {
        const data = await res.json().catch(() => ({}));
        toast.error(data.error || t("newsroom.loadFailed"));
        return;
      }
      const accepted = await res.json().catch(() => ({}));
      const executionId = typeof accepted.execution_id === "string" ? accepted.execution_id : "";
      toast.success(t("newsroom.generating"));
      const deadline = Date.now() + 2 * 60 * 60 * 1000;
      while (Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 3000));
        const exRes = await safeFetch(
          `${API_BASE}/api/scheduler/tasks/newsroom_daily_pipeline/executions?limit=20`,
        );
        if (!exRes.ok) continue;
        const exData = await exRes.json().catch(() => ({}));
        const rows = Array.isArray(exData.executions) ? exData.executions : [];
        const found = executionId
          ? rows.find((row: { id?: string }) => row.id === executionId)
          : rows[0];
        if (!found || found.status === "running") continue;
        if (found.status === "success") {
          toast.success(t("newsroom.generateDone"));
        } else {
          toast.error(found.error || t("newsroom.generateFailed"));
        }
        await fetchIssues(true);
        return;
      }
      toast.error(t("newsroom.generateTimeout"));
    } catch {
      toast.error(t("newsroom.loadFailed"));
    } finally {
      setGenerating(false);
    }
  }, [API_BASE, fetchIssues, t]);

  const submitFeedback = useCallback(async () => {
    if (!selectedDate || fbRating === null) return;
    try {
      const res = await safeFetch(`${API_BASE}/api/newsroom/feedback`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ issue_date: selectedDate, rating: fbRating, comment: fbComment }),
      });
      if (res.ok) {
        toast.success(t("newsroom.feedbackDone"));
        await fetchDetail(selectedDate);
        await fetchIssues();
      } else {
        const data = await res.json().catch(() => ({}));
        toast.error(data.error || t("newsroom.loadFailed"));
      }
    } catch {
      toast.error(t("newsroom.loadFailed"));
    }
  }, [API_BASE, selectedDate, fbRating, fbComment, fetchDetail, fetchIssues, t]);

  const saveConfig = useCallback(async () => {
    if (!config) return;
    setSavingConfig(true);
    try {
      const res = await safeFetch(`${API_BASE}/api/newsroom/config`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          enabled: config.enabled,
          daily_cron: config.daily_cron,
          review_cron: config.review_cron,
          obsidian_vault: config.obsidian_vault,
        }),
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok) {
        setConfig(data);
        toast.success(t("newsroom.saved"));
      } else {
        toast.error(data.reconcile_error || data.error || t("newsroom.loadFailed"));
      }
    } finally {
      setSavingConfig(false);
    }
  }, [API_BASE, config, t]);

  const toggleOp = useCallback((opId: string, checked: boolean) => {
    setSelectedOpIds((prev) => {
      if (checked) return prev.includes(opId) ? prev : [...prev, opId];
      return prev.filter((id) => id !== opId);
    });
  }, []);

  const applySelectedProposal = useCallback(async () => {
    if (!proposal || proposal.status !== "pending") return;
    const already = new Set(proposal.applied_op_ids || []);
    const freshIds = selectedOpIds.filter((id) => !already.has(id));
    if (freshIds.length === 0) return;
    setApplyingProposal(true);
    try {
      const applyMemory = freshIds.includes("memory_rule");
      const res = await safeFetch(`${API_BASE}/api/newsroom/proposal/apply`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          op_ids: freshIds,
          apply_memory: applyMemory,
          actor: "webui",
        }),
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok) {
        if (data.memory_error) {
          toast.error(data.memory_error);
        } else if (Array.isArray(data.remaining_op_ids) && data.remaining_op_ids.length > 0) {
          toast.success(t("newsroom.proposalPartialOk"));
        } else {
          toast.success(t("newsroom.proposalAppliedOk"));
        }
        await fetchProposal();
        await fetchSettings();
      } else {
        toast.error(data.error || t("newsroom.loadFailed"));
      }
    } catch {
      toast.error(t("newsroom.loadFailed"));
    } finally {
      setApplyingProposal(false);
    }
  }, [API_BASE, proposal, selectedOpIds, fetchProposal, fetchSettings, t]);

  const rejectCurrentProposal = useCallback(async () => {
    if (!proposal || proposal.status !== "pending") return;
    setApplyingProposal(true);
    try {
      const res = await safeFetch(`${API_BASE}/api/newsroom/proposal/reject`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ actor: "webui" }),
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok) {
        toast.success(t("newsroom.proposalRejectedOk"));
        await fetchProposal();
      } else {
        toast.error(data.error || t("newsroom.loadFailed"));
      }
    } catch {
      toast.error(t("newsroom.loadFailed"));
    } finally {
      setApplyingProposal(false);
    }
  }, [API_BASE, proposal, fetchProposal, t]);

  const saveSources = useCallback(async () => {
    setSavingSources(true);
    try {
      const res = await safeFetch(`${API_BASE}/api/newsroom/sources`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ yaml: sourcesYaml }),
      });
      if (res.ok) {
        const saved = await res.json();
        setSourcesYaml(typeof saved.yaml === "string" ? saved.yaml : sourcesYaml);
        toast.success(t("newsroom.saved"));
      } else {
        const data = await res.json().catch(() => ({}));
        toast.error(data.error || t("newsroom.loadFailed"));
      }
    } finally {
      setSavingSources(false);
    }
  }, [API_BASE, sourcesYaml, t]);

  const selected = useMemo(
    () => issues.find((i) => i.issue_date === selectedDate) || null,
    [issues, selectedDate],
  );

  if (!serviceRunning) {
    return (
      <div className="mx-auto max-w-[1200px] px-6 py-5">
        <Card className="border-border/50 shadow-sm">
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            {t("newsroom.serviceDown")}
          </CardContent>
        </Card>
      </div>
    );
  }

  // 两栏等高：列表与详情共享同一可视高度（视口高度减去头部与留白），
  // 内部各自滚动——这是修复"四块矩形高低错位"的关键。
  const paneHeight = "lg:h-[calc(100vh-240px)] lg:min-h-[520px]";

  return (
    <div className="mx-auto max-w-[1200px] space-y-5 px-6 py-5">
      {/* ── Header：标题 + 操作 ── */}
      <div className="flex items-start justify-between gap-4">
        <div className="space-y-1.5 min-w-0">
          <h2 className="truncate text-lg font-bold tracking-tight" title={t("newsroom.title")}>
            {t("newsroom.title")}
          </h2>
          <p className="truncate text-xs text-muted-foreground leading-relaxed" title={t("newsroom.subtitle")}>
            {t("newsroom.subtitle")}
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0 pt-0.5">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setSettingsOpen(true);
              void fetchSettings();
            }}
          >
            <Settings2 size={14} className="mr-1" /> {t("newsroom.settings")}
          </Button>
          <Button variant="outline" size="sm" onClick={() => { void fetchIssues(); void fetchSettings(); }}>
            <RefreshCw size={14} className="mr-1" /> {t("common.refresh", "刷新")}
          </Button>
          <Button size="sm" disabled={generating} onClick={() => void generateNow()}>
            {generating ? <Loader2 size={14} className="mr-1 animate-spin" /> : <Sparkles size={14} className="mr-1" />}
            {t("newsroom.generateNow")}
          </Button>
        </div>
      </div>

      <Card className="border-border/50 shadow-sm">
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2 pt-4 px-5">
          <CardTitle className="text-sm font-semibold">{t("newsroom.proposalTitle")}</CardTitle>
          {proposal && proposal.status !== "none" && (
            <Badge variant={proposal.status === "pending" ? "default" : "secondary"} className="text-[10px]">
              {proposal.status === "pending"
                ? t("newsroom.proposalPending")
                : proposal.status === "applied"
                  ? t("newsroom.proposalApplied")
                  : proposal.status === "rejected"
                    ? t("newsroom.proposalRejected")
                    : t("newsroom.proposalInvalid")}
            </Badge>
          )}
        </CardHeader>
        <CardContent className="px-5 pb-4 space-y-3">
          {!proposal || proposal.status === "none" ? (
            <p className="text-xs text-muted-foreground leading-relaxed">{t("newsroom.proposalEmpty")}</p>
          ) : proposal.status === "invalid" ? (
            <p className="text-xs text-destructive leading-relaxed">
              {proposal.parse_error || t("newsroom.proposalInvalid")}
            </p>
          ) : (proposal.ops || []).length === 0 ? (
            <p className="text-xs text-muted-foreground">{t("newsroom.proposalNoOps")}</p>
          ) : (
            <ul className="space-y-2">
              {proposal.ops.map((op) => {
                const pending = proposal.status === "pending";
                const alreadyApplied = (proposal.applied_op_ids || []).includes(op.id);
                const checked = selectedOpIds.includes(op.id) || alreadyApplied;
                const evidenceBits = [
                  op.evidence?.issue_date,
                  op.evidence?.dimension,
                  op.evidence?.note,
                ].filter(Boolean);
                return (
                  <li key={op.id} className="flex items-start gap-2 text-sm">
                    <Checkbox
                      className="mt-0.5"
                      checked={checked}
                      disabled={!pending || applyingProposal || alreadyApplied}
                      onCheckedChange={(value) => toggleOp(op.id, !!value)}
                    />
                    <div className="min-w-0 space-y-0.5">
                      <div className="leading-relaxed">
                        <span className="text-[10px] uppercase text-muted-foreground mr-1.5">{op.kind}</span>
                        {op.summary}
                      </div>
                      {evidenceBits.length > 0 && (
                        <div className="text-[11px] text-muted-foreground">
                          {t("newsroom.proposalEvidence")}: {evidenceBits.join(" · ")}
                        </div>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
          {proposal?.last_memory_error && proposal.status === "pending" && (
            <p className="text-xs text-destructive leading-relaxed">{proposal.last_memory_error}</p>
          )}
          {proposal?.status === "pending" && (
            <div className="flex flex-wrap items-center gap-2 pt-1">
              <p className="text-[11px] text-muted-foreground mr-auto">{t("newsroom.proposalSelectSources")}</p>
              <Button
                size="sm"
                disabled={
                  applyingProposal ||
                  selectedOpIds.filter((id) => !(proposal.applied_op_ids || []).includes(id)).length === 0
                }
                onClick={() => void applySelectedProposal()}
              >
                {applyingProposal && <Loader2 size={13} className="mr-1 animate-spin" />}
                {t("newsroom.proposalApply")}
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={applyingProposal}
                onClick={() => void rejectCurrentProposal()}
              >
                {t("newsroom.proposalReject")}
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 lg:grid-cols-[320px_1fr] gap-4 items-stretch">
        {/* ── 左列：期次列表 ── */}
        <Card className={`p-0 gap-0 border-border/50 shadow-sm flex flex-col ${paneHeight}`}>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2 pt-4 px-5 shrink-0">
            <CardTitle className="text-xs font-medium text-muted-foreground">{t("newsroom.issueList")}</CardTitle>
            <span className="text-[11px] text-muted-foreground/70 tabular-nums">{issues.length}</span>
          </CardHeader>
          <CardContent className="px-3 pb-3 pt-0 flex-1 overflow-y-auto custom-scrollbar">
            {loading ? (
              <div className="flex justify-center py-8"><Loader2 className="animate-spin" size={18} /></div>
            ) : issues.length === 0 ? (
              <p className="text-xs text-muted-foreground p-4 text-center leading-relaxed">
                {t("newsroom.noIssues")}
              </p>
            ) : (
              issues.map((issue) => (
                <button
                  key={issue.issue_date}
                  onClick={() => setSelectedDate(issue.issue_date)}
                  className={`w-full text-left px-3 py-2.5 rounded-md text-sm transition-colors ${
                    issue.issue_date === selectedDate ? "bg-accent" : "hover:bg-accent/50"
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-medium tabular-nums">{issue.issue_date}</span>
                    <span className="flex items-center gap-1.5">
                      {issue.feedback.rating === 1 && <ThumbsUp size={11} className="text-green-600" />}
                      {issue.feedback.rating === -1 && <ThumbsDown size={11} className="text-red-500" />}
                      <Badge variant={issue.status === "ready" ? "default" : "secondary"} className="text-[10px] px-1.5">
                        {issue.status === "ready"
                          ? t("newsroom.statusReady")
                          : issue.status === "rejected"
                            ? t("newsroom.statusRejected")
                            : issue.status === "invalid"
                              ? t("newsroom.statusInvalid")
                              : t("newsroom.statusPartial")}
                      </Badge>
                    </span>
                  </div>
                  {issue.title && (
                    <div className="text-xs text-muted-foreground truncate mt-0.5">{issue.title}</div>
                  )}
                </button>
              ))
            )}
          </CardContent>
        </Card>

        {/* ── 右列：期次详情 ── */}
        <Card className={`p-0 gap-0 border-border/50 shadow-sm flex flex-col ${paneHeight}`}>
          {!selected ? (
            // 无期次时保持右栏留白（空态提示只在左侧列表出现，避免同文案重复）；
            // 有期次但未选中时给一句引导。
            <CardContent className="flex-1 flex items-center justify-center text-sm text-muted-foreground">
              {issues.length > 0 ? t("newsroom.issueList") : "\u00a0"}
            </CardContent>
          ) : detailLoading ? (
            <CardContent className="flex-1 flex items-center justify-center">
              <Loader2 className="animate-spin" size={18} />
            </CardContent>
          ) : (
            <>
              <CardHeader className="space-y-2 pb-3 pt-4 px-5 shrink-0">
                {(detail?.manifest_error || selected.manifest_error) && (
                  <p className="text-xs text-destructive leading-relaxed">
                    {detail?.manifest_error || selected.manifest_error}
                  </p>
                )}
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <CardTitle className="text-sm font-semibold truncate">
                    {detail?.manifest?.title || selected.title || selectedDate}
                  </CardTitle>
                  {selected.scores && Object.keys(selected.scores).length > 0 && (
                    <div
                      className="flex items-center gap-1.5 shrink-0"
                      title={Object.entries(selected.scores)
                        .map(([dim, s]) => `${dim}: ${s.score}/5 ${s.rationale}`)
                        .join("\n")}
                    >
                      <span className="text-xs text-muted-foreground">{t("newsroom.scores")}</span>
                      <Badge variant="outline" className="text-[10px] font-mono">
                        {Object.values(selected.scores).reduce((acc, s) => acc + s.score, 0)}/
                        {Object.keys(selected.scores).length * 5}
                      </Badge>
                    </div>
                  )}
                </div>
                {(selected.sources_used?.length > 0 || selected.wiki_entries?.length > 0) && (
                  <div className="flex flex-wrap gap-1.5">
                    {selected.sources_used.slice(0, 6).map((s) => (
                      <Badge key={s} variant="secondary" className="text-[10px] font-normal">{s}</Badge>
                    ))}
                    {selected.wiki_entries.length > 0 && (
                      <button
                        onClick={() => { window.location.hash = "/wiki"; }}
                        title={selected.wiki_entries.join("\n")}
                      >
                        <Badge variant="outline" className="text-[10px] cursor-pointer">
                          📓 {t("newsroom.wikiPages")} ×{selected.wiki_entries.length}
                        </Badge>
                      </button>
                    )}
                    {selected.feishu_doc_url && (
                      <a href={selected.feishu_doc_url} target="_blank" rel="noreferrer" title={selected.feishu_doc_url}>
                        <Badge variant="outline" className="text-[10px] cursor-pointer">
                          ☁️ {t("newsroom.feishuDoc")} ↗
                        </Badge>
                      </a>
                    )}
                  </div>
                )}
                {/* 产物 Tab */}
                <div className="flex gap-1 border-b">
                  {ARTIFACT_TABS.map((tab) => (
                    <button
                      key={tab.key}
                      onClick={() => setActiveTab(tab.key)}
                      className={`px-3 py-1.5 text-sm border-b-2 -mb-px transition-colors ${
                        activeTab === tab.key
                          ? "border-primary text-foreground font-medium"
                          : "border-transparent text-muted-foreground hover:text-foreground"
                      }`}
                    >
                      {t(tab.labelKey)}
                    </button>
                  ))}
                </div>
              </CardHeader>
              <CardContent className="px-5 pb-0 flex-1 overflow-y-auto custom-scrollbar">
                {detail?.content?.[activeTab] ? (
                  <div className="docMdContent">
                    <MarkdownContent content={detail.content[activeTab] || ""} mdModules={mdModules} apiBaseUrl={API_BASE} />
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground py-8 text-center">∅</p>
                )}
              </CardContent>
              {/* 反馈条固定在详情卡底部 */}
              <div className="shrink-0 flex flex-wrap items-center gap-2 px-5 py-3 border-t border-border/60">
                <Button
                  size="sm"
                  variant={fbRating === 1 ? "default" : "outline"}
                  onClick={() => setFbRating(1)}
                >
                  <ThumbsUp size={13} className="mr-1" /> {t("newsroom.feedbackUp")}
                </Button>
                <Button
                  size="sm"
                  variant={fbRating === -1 ? "destructive" : "outline"}
                  onClick={() => setFbRating(-1)}
                >
                  <ThumbsDown size={13} className="mr-1" /> {t("newsroom.feedbackDown")}
                </Button>
                <Input
                  className="flex-1 min-w-[180px] h-8 text-sm"
                  placeholder={t("newsroom.feedbackComment")}
                  value={fbComment}
                  maxLength={500}
                  onChange={(e) => setFbComment(e.target.value)}
                />
                <Button size="sm" disabled={fbRating === null} onClick={() => void submitFeedback()}>
                  {t("newsroom.feedbackSubmit")}
                </Button>
              </div>
            </>
          )}
        </Card>
      </div>

      {/* ── 设置抽屉：运行设置 + 信源清单 ── */}
      <Sheet open={settingsOpen} onOpenChange={setSettingsOpen}>
        <SheetContent side="right" className="w-[520px] max-w-[92vw] flex flex-col gap-0 p-0">
          <SheetHeader className="px-6 py-4 border-b border-border/60">
            <SheetTitle className="text-base">{t("newsroom.settings")}</SheetTitle>
          </SheetHeader>
          <div className="flex-1 overflow-y-auto custom-scrollbar px-6 py-5 space-y-6">
            {/* 运行设置 */}
            <section className="space-y-3">
              {config ? (
                <>
                  {config.load_error && (
                    <p className="text-xs text-destructive leading-relaxed">
                      {t("newsroom.configLoadError")}: {config.load_error}
                    </p>
                  )}
                  <div className="flex items-center justify-between">
                    <Label className="text-sm" htmlFor="newsroom-enabled">{t("newsroom.configEnabled")}</Label>
                    <Switch
                      id="newsroom-enabled"
                      checked={config.enabled}
                      onCheckedChange={(v) => setConfig({ ...config, enabled: v })}
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <Label className="text-xs" htmlFor="newsroom-cron">{t("newsroom.configCron")}</Label>
                      <Input
                        id="newsroom-cron"
                        className="h-8 mt-1 text-sm font-mono"
                        value={config.daily_cron}
                        onChange={(e) => setConfig({ ...config, daily_cron: e.target.value })}
                      />
                    </div>
                    <div>
                      <Label className="text-xs" htmlFor="newsroom-review-cron">{t("newsroom.configReviewCron")}</Label>
                      <Input
                        id="newsroom-review-cron"
                        className="h-8 mt-1 text-sm font-mono"
                        value={config.review_cron}
                        onChange={(e) => setConfig({ ...config, review_cron: e.target.value })}
                      />
                    </div>
                  </div>
                  <div>
                    <Label className="text-xs" htmlFor="newsroom-vault">{t("newsroom.configVault")}</Label>
                    <Input
                      id="newsroom-vault"
                      className="h-8 mt-1 text-sm font-mono"
                      placeholder="/path/to/vault"
                      value={config.obsidian_vault}
                      onChange={(e) => setConfig({ ...config, obsidian_vault: e.target.value })}
                    />
                  </div>
                  <div className="flex justify-end">
                    <Button size="sm" disabled={savingConfig} onClick={() => void saveConfig()}>
                      {savingConfig && <Loader2 size={13} className="mr-1 animate-spin" />}
                      {t("newsroom.save")}
                    </Button>
                  </div>
                </>
              ) : (
                <p className="text-xs text-muted-foreground py-4 text-center">{t("newsroom.loadFailed")}</p>
              )}
            </section>

            <section className="space-y-2 pt-5 border-t border-border/60">
              <div className="space-y-1">
                <div className="text-sm font-semibold">{t("newsroom.editorialPolicy")}</div>
                <p className="text-xs text-muted-foreground">{t("newsroom.editorialPolicyHint")}</p>
              </div>
              <Textarea
                className="font-mono text-xs min-h-[160px] bg-muted/40"
                value={policyText}
                readOnly
                spellCheck={false}
              />
            </section>

            {/* 信源清单 */}
            <section className="space-y-2 pt-5 border-t border-border/60">
              <div className="space-y-1">
                <div className="text-sm font-semibold">{t("newsroom.sources")}</div>
                <p className="text-xs text-muted-foreground">{t("newsroom.sourcesHint")}</p>
              </div>
              <Textarea
                className="font-mono text-xs min-h-[320px]"
                value={sourcesYaml}
                onChange={(e) => setSourcesYaml(e.target.value)}
                spellCheck={false}
              />
              <div className="flex justify-end">
                <Button size="sm" disabled={savingSources} onClick={() => void saveSources()}>
                  {savingSources && <Loader2 size={13} className="mr-1 animate-spin" />}
                  {t("newsroom.save")}
                </Button>
              </div>
            </section>
          </div>
        </SheetContent>
      </Sheet>
    </div>
  );
}
