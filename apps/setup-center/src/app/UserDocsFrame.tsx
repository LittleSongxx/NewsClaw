import { useEffect, useState } from "react";

export function UserDocsFrame({
  docsBase,
  docsVersion,
  title,
}: {
  docsBase: string;
  docsVersion?: string | null;
  title: string;
}) {
  const [available, setAvailable] = useState<"checking" | "yes" | "no">("checking");
  const docsCacheKey = docsVersion || "current";
  const docsUrl = docsVersion
    ? `${docsBase}/user-docs/v${encodeURIComponent(docsVersion)}/?ov=${encodeURIComponent(docsCacheKey)}`
    : `${docsBase}/user-docs/?ov=${encodeURIComponent(docsCacheKey)}`;

  useEffect(() => {
    let cancelled = false;
    fetch(docsUrl, { method: "GET", cache: "no-store", signal: AbortSignal.timeout(5_000) })
      .then((res) => {
        if (!cancelled) setAvailable(res.ok ? "yes" : "no");
      })
      .catch(() => {
        if (!cancelled) setAvailable("no");
      });
    return () => {
      cancelled = true;
    };
  }, [docsUrl]);

  if (available === "yes") {
    return (
      <iframe
        src={docsUrl}
        style={{ flex: 1, border: "none", width: "100%", height: "100%", borderRadius: 8, background: "var(--bg, #fff)" }}
        title={title}
      />
    );
  }

  return (
    <div className="card" style={{ margin: 16, padding: 32, textAlign: "center" }}>
      <h2 className="cardTitle">用户文档暂不可用</h2>
      <p style={{ color: "var(--muted)", fontSize: 13, lineHeight: 1.7, margin: "8px auto 16px", maxWidth: 520 }}>
        当前安装包未包含本地文档资源，后端没有挂载 <code>/user-docs/</code>。核心功能不受影响。
        本仓库没有独立文档站，请看仓库 README。
      </p>
      {available === "checking" && (
        <div style={{ marginTop: 12, fontSize: 12, color: "var(--muted)" }}>正在检查本地文档...</div>
      )}
    </div>
  );
}
