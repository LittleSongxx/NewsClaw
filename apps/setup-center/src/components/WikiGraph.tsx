import { useCallback, useEffect, useMemo, useRef, useState } from "react";

/**
 * 知识网络图（本地 Wiki 的双链可视化）。
 *
 * 为什么自己画而不是复用 MemoryGraph3D：
 *   · 记忆图是 3D + 悬停调光，标签只在 hover 时出现——看"知识网络结构"时需要
 *     **标签常显**；且它与记忆 API / bloom 后期深度耦合，复用要改数据模型。
 *   · 这里用 Canvas 画 2D 力导向图：零新依赖、布局确定性（同一份数据每次一致）、
 *     标签常显、支持缩放与拖拽平移——接近 Obsidian 图谱的手感。
 *
 * 布局算法：弹簧-斥力（Fruchterman-Reingold 简化版），固定迭代次数后收敛；
 * 初值按节点序均匀分布在圆周上（无随机数 → 布局可复现）。
 */

export type GraphNode = { name: string; kind: string; path: string; dates: number };
export type GraphLink = { source: string; target: string };

const KIND_COLORS: Record<string, string> = {
  topic: "#3b82f6", // 主题页
  company: "#f59e0b", // 公司页
  index: "#94a3b8", // 索引页
};

const NODE_MIN_R = 7;
const NODE_MAX_R = 15;
const LABEL_FONT = 12;
const ITERATIONS = 260;
const MIN_ZOOM = 0.3;
const MAX_ZOOM = 3;

function layoutGraph(
  nodes: GraphNode[],
  links: GraphLink[],
  width: number,
  height: number,
): Map<string, { x: number; y: number }> {
  const cx = width / 2;
  const cy = height / 2;
  const count = Math.max(nodes.length, 1);
  const radius = Math.min(width, height) * 0.34 || 120;

  // 初值：均匀分布在圆周上（无随机数 → 同一份数据布局可复现）
  const points = nodes.map((node, index) => {
    const angle = (2 * Math.PI * index) / count - Math.PI / 2;
    return {
      id: node.name,
      x: cx + radius * Math.cos(angle),
      y: cy + radius * Math.sin(angle),
      dx: 0,
      dy: 0,
    };
  });
  const byId = new Map(points.map((p) => [p.id, p]));

  // 标准 Fruchterman-Reingold：k 是理想边长，温度线性退火
  const k = Math.sqrt((width * height) / count) * 0.6;
  let temperature = Math.min(width, height) * 0.14;

  for (let step = 0; step < ITERATIONS; step += 1) {
    for (const p of points) {
      p.dx = 0;
      p.dy = 0;
    }

    // 斥力：k²/d（任意两节点互相推开）
    for (let i = 0; i < points.length; i += 1) {
      for (let j = i + 1; j < points.length; j += 1) {
        const a = points[i];
        const b = points[j];
        let dx = a.x - b.x;
        let dy = a.y - b.y;
        let dist = Math.hypot(dx, dy);
        if (dist < 0.01) {
          // 完全重合时给一个确定性的微小偏移，避免除零
          dx = 0.01 * (i + 1);
          dy = 0.01 * (j + 1);
          dist = Math.hypot(dx, dy);
        }
        const force = (k * k) / dist;
        const ux = dx / dist;
        const uy = dy / dist;
        a.dx += ux * force;
        a.dy += uy * force;
        b.dx -= ux * force;
        b.dy -= uy * force;
      }
    }

    // 引力：d²/k（有双链的节点互相拉近）
    for (const link of links) {
      const a = byId.get(link.source);
      const b = byId.get(link.target);
      if (!a || !b) continue;
      let dx = a.x - b.x;
      let dy = a.y - b.y;
      let dist = Math.hypot(dx, dy);
      if (dist < 0.01) {
        dx = 0.01;
        dy = 0.01;
        dist = 0.014;
      }
      const force = (dist * dist) / k;
      const ux = dx / dist;
      const uy = dy / dist;
      a.dx -= ux * force;
      a.dy -= uy * force;
      b.dx += ux * force;
      b.dy += uy * force;
    }

    // 重力：向画布中心收拢（强度远小于斥力，只防漂移）
    for (const p of points) {
      p.dx += (cx - p.x) * k * 0.02;
      p.dy += (cy - p.y) * k * 0.02;
    }

    // 位移限幅 = 温度，并退火
    for (const p of points) {
      const disp = Math.hypot(p.dx, p.dy) || 0.0001;
      const limited = Math.min(disp, temperature);
      p.x += (p.dx / disp) * limited;
      p.y += (p.dy / disp) * limited;
    }
    temperature *= 0.97;
  }

  // 收进含标签余量的可视区
  const margin = 62;
  for (const p of points) {
    p.x = Math.max(margin, Math.min(width - margin, p.x));
    p.y = Math.max(30, Math.min(height - 42, p.y));
  }
  return new Map(points.map((p) => [p.id, { x: p.x, y: p.y }]));
}

export function WikiGraph({
  nodes,
  links,
  selected,
  onSelect,
  height = 520,
}: {
  nodes: GraphNode[];
  links: GraphLink[];
  selected?: string;
  onSelect: (name: string) => void;
  height?: number;
}) {
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [size, setSize] = useState({ width: 0, height });
  const [hovered, setHovered] = useState<string>("");
  const [view, setView] = useState({ zoom: 1, panX: 0, panY: 0 });
  const dragRef = useRef<{ x: number; y: number; panX: number; panY: number } | null>(null);

  // 容器尺寸（含滚动/resize）
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const apply = () => {
      const rect = el.getBoundingClientRect();
      setSize({ width: Math.max(0, Math.floor(rect.width)), height });
    };
    apply();
    const observer = new ResizeObserver(apply);
    observer.observe(el);
    return () => observer.disconnect();
  }, [height]);

  // 布局只在数据/尺寸变化时重算（确定性：无随机数）
  const positions = useMemo(
    () => (size.width > 0 ? layoutGraph(nodes, links, size.width, size.height) : new Map()),
    [nodes, links, size.width, size.height],
  );

  const radiusOf = useCallback(
    (node: GraphNode) => NODE_MIN_R + Math.min(node.dates, 6) * ((NODE_MAX_R - NODE_MIN_R) / 6),
    [],
  );

  const neighbors = useMemo(() => {
    const map = new Map<string, Set<string>>();
    for (const link of links) {
      if (!map.has(link.source)) map.set(link.source, new Set());
      if (!map.has(link.target)) map.set(link.target, new Set());
      map.get(link.source)!.add(link.target);
      map.get(link.target)!.add(link.source);
    }
    return map;
  }, [links]);

  // 命中检测：把屏幕坐标反变换到布局坐标，取最近的节点
  const hitTest = useCallback(
    (clientX: number, clientY: number): GraphNode | null => {
      const canvas = canvasRef.current;
      if (!canvas || size.width === 0) return null;
      const rect = canvas.getBoundingClientRect();
      const x = (clientX - rect.left - view.panX - size.width / 2) / view.zoom + size.width / 2;
      const y = (clientY - rect.top - view.panY - size.height / 2) / view.zoom + size.height / 2;
      let best: GraphNode | null = null;
      let bestDist = Infinity;
      for (const node of nodes) {
        const p = positions.get(node.name);
        if (!p) continue;
        const dist = Math.hypot(p.x - x, p.y - y);
        if (dist < radiusOf(node) + 6 && dist < bestDist) {
          best = node;
          bestDist = dist;
        }
      }
      return best;
    },
    [nodes, positions, radiusOf, size.height, size.width, view],
  );

  // 绘制
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || size.width === 0) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    canvas.width = size.width * dpr;
    canvas.height = size.height * dpr;
    canvas.style.width = `${size.width}px`;
    canvas.style.height = `${size.height}px`;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, size.width, size.height);

    ctx.save();
    ctx.translate(size.width / 2 + view.panX, size.height / 2 + view.panY);
    ctx.scale(view.zoom, view.zoom);
    ctx.translate(-size.width / 2, -size.height / 2);

    const focus = hovered || selected || "";
    const focusSet = focus ? neighbors.get(focus) || new Set<string>() : new Set<string>();

    // 边
    for (const link of links) {
      const a = positions.get(link.source);
      const b = positions.get(link.target);
      if (!a || !b) continue;
      const active = focus === link.source || focus === link.target;
      ctx.strokeStyle = active ? "rgba(59,130,246,0.75)" : "rgba(148,163,184,0.35)";
      ctx.lineWidth = active ? 1.8 : 1;
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
    }

    // 节点 + 常显标签
    for (const node of nodes) {
      const p = positions.get(node.name);
      if (!p) continue;
      const r = radiusOf(node);
      const isFocus = node.name === focus;
      const dimmed = focus !== "" && !isFocus && !focusSet.has(node.name);
      ctx.globalAlpha = dimmed ? 0.25 : 1;
      ctx.beginPath();
      ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
      ctx.fillStyle = KIND_COLORS[node.kind] || "#94a3b8";
      ctx.fill();
      if (isFocus) {
        ctx.lineWidth = 2.5;
        ctx.strokeStyle = "#1d4ed8";
        ctx.stroke();
      }

      if (view.zoom > 0.55) {
        ctx.font = `${LABEL_FONT}px system-ui, -apple-system, "Segoe UI", sans-serif`;
        ctx.textAlign = "center";
        ctx.textBaseline = "top";
        const label = node.name;
        const textWidth = ctx.measureText(label).width;
        const lx = p.x;
        const ly = p.y + r + 3;
        // 标签底板：保证在密集区域也可读
        ctx.globalAlpha = dimmed ? 0.25 : 0.92;
        ctx.fillStyle = "rgba(255,255,255,0.85)";
        ctx.fillRect(lx - textWidth / 2 - 3, ly - 1, textWidth + 6, LABEL_FONT + 4);
        ctx.globalAlpha = dimmed ? 0.3 : 1;
        ctx.fillStyle = "#0f172a";
        ctx.fillText(label, lx, ly);
      }
      ctx.globalAlpha = 1;
    }
    ctx.restore();
  }, [hovered, links, neighbors, nodes, positions, radiusOf, selected, size, view]);

  return (
    <div ref={wrapRef} style={{ position: "relative", width: "100%", height }}>
      <canvas
        ref={canvasRef}
        style={{ display: "block", cursor: hovered ? "pointer" : "grab" }}
        onMouseMove={(e) => {
          if (dragRef.current) {
            setView((v) => ({
              ...v,
              panX: dragRef.current!.panX + (e.clientX - dragRef.current!.x),
              panY: dragRef.current!.panY + (e.clientY - dragRef.current!.y),
            }));
            return;
          }
          const node = hitTest(e.clientX, e.clientY);
          setHovered(node?.name || "");
        }}
        onMouseDown={(e) => {
          dragRef.current = { x: e.clientX, y: e.clientY, panX: view.panX, panY: view.panY };
        }}
        onMouseUp={() => {
          dragRef.current = null;
        }}
        onMouseLeave={() => {
          dragRef.current = null;
          setHovered("");
        }}
        onClick={(e) => {
          const node = hitTest(e.clientX, e.clientY);
          if (node) onSelect(node.name);
        }}
        onWheel={(e) => {
          const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12;
          setView((v) => ({
            ...v,
            zoom: Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, v.zoom * factor)),
          }));
        }}
      />
      {/* 图例 + 提示 */}
      <div className="absolute left-3 bottom-3 flex items-center gap-3 text-[11px] text-muted-foreground">
        {Object.entries({ topic: "主题", company: "公司" }).map(([kind, label]) => (
          <span key={kind} className="inline-flex items-center gap-1">
            <span
              style={{
                width: 9,
                height: 9,
                borderRadius: 999,
                background: KIND_COLORS[kind],
                display: "inline-block",
              }}
            />
            {label}
          </span>
        ))}
        <span className="opacity-70">滚轮缩放 · 拖拽平移 · 点击节点打开页面</span>
      </div>
    </div>
  );
}
