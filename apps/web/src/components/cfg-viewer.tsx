"use client";

import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { DragEvent, MouseEvent, ReactNode } from "react";
import { createPortal } from "react-dom";
import useSWR from "swr";
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeChange,
  type NodeProps,
  type ReactFlowInstance,
} from "@xyflow/react";
import {
  AlertCircle,
  Check,
  GitBranch,
  LoaderCircle,
  MoveRight,
  RefreshCw,
  Sparkles,
  WandSparkles,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { BlockEditor } from "@/components/block-editor";
import { BLOCK_MIME, type BlockTemplate } from "@/components/block-palette";
import { RoutedEdge, type Point } from "@/components/routed-edge";
import { PreviewDialog } from "@/components/preview-dialog";
import { ruFinding, ruText } from "@/lib/ru";
import { modelAssumptions, originsOf, pretty, readFormula, variablesOf } from "@/lib/complexity";
import { DiffView } from "@/components/diff-view";
import {
  ApiError,
  getMethodAnalysis,
  getMethodCfg,
  applyCfgEdit,
  previewCfgEdit,
  replaceCondition,
  type Cfg,
  type CfgEditOperation,
  type CfgEditPreview,
  type CfgEdge,
  type CfgMethod,
  type CfgNode,
  type MethodAnalysis,
  type SourceRange,
} from "@/lib/api";

const NODE_WIDTH = 248;
const NODE_HEIGHT = 124;

type FlowData = CfgNode & {
  hasFinding: boolean;
  complexityFragment: string | null;
  /** Executions in the last traced run: undefined without a trace, null when the node did not run. */
  hits?: number | null;
  iterations?: number | null;
};
type FlowNode = Node<FlowData, "cfg">;

const CfgFlowNode = memo(function CfgFlowNode({ data, selected }: NodeProps<FlowNode>) {
  const condition = data.kind === "branch";
  const terminal = data.kind === "entry" || data.kind === "exit";
  const connector = data.kind === "merge";
  const label = data.text || data.statement_kind || data.kind;
  const verticalInset = condition ? 2 : terminal ? 28 : connector ? 26 : 16;
  const kindName = data.statement_kind ?? "";
  const loop = condition && ["while", "do_while", "foreach", "for"].includes(kindName);
  const variable = kindName === "variable_declaration";
  const returning = data.kind === "return";
  const throwing = data.kind === "throw";
  const borderClass = selected
    ? "border-cyan-400"
    : terminal ? "border-emerald-700" : connector ? "border-slate-500" : returning ? "border-emerald-500" : throwing ? "border-rose-500"
      : variable ? "border-sky-500" : data.kind === "statement" ? "border-blue-600" : "border-slate-600";
  const surfaceClass = terminal ? "bg-[#10231b]" : returning ? "bg-[#0e2a1f]" : throwing ? "bg-[#2a1216]" : variable ? "bg-[#0b2238]" : data.kind === "statement" ? "bg-[#0e1b34]" : "bg-slate-900";
  const diamondBorder = selected ? "bg-cyan-400" : loop ? "bg-violet-500" : "bg-amber-600";
  const diamondFill = loop ? "bg-[#1d1730]" : "bg-[#251e15]";
  const accentText = loop ? "text-violet-300/80" : "text-amber-300/80";
  return (
    <div
      className={[
        "relative flex h-[124px] w-[248px] flex-col items-center justify-center text-center text-slate-300",
        data.reachable === false || data.hits === null ? "opacity-35" : "",
      ].filter(Boolean).join(" ")}
      title={condition ? "Нажмите, чтобы выбрать условие и подсветить ветвь." : undefined}
    >
      <Handle type="target" position={Position.Top} style={{ top: verticalInset }} />
      {condition ? (
        <div
          className={`absolute inset-x-0 inset-y-0 ${diamondBorder}`}
          style={{ clipPath: "polygon(50% 0, 100% 50%, 50% 100%, 0 50%)" }}
          aria-hidden="true"
        >
          <div className={`absolute inset-[2px] ${diamondFill}`} style={{ clipPath: "polygon(50% 0, 100% 50%, 50% 100%, 0 50%)" }} />
        </div>
      ) : connector ? (
        <div className={`absolute top-[26px] left-[88px] size-[72px] rounded-full border ${borderClass} ${surfaceClass}`} aria-hidden="true" />
      ) : (
        <div className={`absolute inset-x-1 top-4 bottom-4 border ${terminal ? "rounded-full" : "rounded-sm"} ${borderClass} ${surfaceClass}`} aria-hidden="true" />
      )}
      {data.hasFinding ? <div className={`pointer-events-none absolute ${condition ? "inset-2 [clip-path:polygon(50%_0,100%_50%,50%_100%,0_50%)]" : terminal ? "inset-x-3 top-7 bottom-7 rounded-full" : connector ? "top-[24px] left-[86px] size-[76px] rounded-full" : "inset-x-0 top-3 bottom-3 rounded"} ring-2 ring-rose-400/35`} aria-hidden="true" /> : null}
      <div className={`relative z-10 flex flex-col items-center justify-center gap-1 ${condition ? "max-w-[154px]" : connector ? "max-w-16" : "max-w-[214px]"}`}>
        <span className={`font-mono text-[10px] font-semibold tracking-[.06em] ${condition ? accentText : terminal ? "text-emerald-300/70" : returning ? "text-emerald-300/80" : variable ? "text-sky-300/80" : data.kind === "statement" ? "text-blue-300/80" : "text-slate-500"}`}>{formatNodeKind(data.kind, data.statement_kind)}</span>
        {!connector ? <strong className="line-clamp-2 max-w-full font-mono text-[13px] font-medium leading-[18px] text-slate-200">{label}</strong> : null}
        {condition ? <small className={`text-[10px] ${accentText}`}>клик — ветвь и код</small> : null}
      </div>
      {typeof data.hits === "number" ? <div className={`pointer-events-none absolute ${condition ? "inset-1 [clip-path:polygon(50%_0,100%_50%,50%_100%,0_50%)]" : terminal ? "inset-x-2 top-6 bottom-6 rounded-full" : connector ? "top-[22px] left-[84px] size-[80px] rounded-full" : "inset-x-0 top-3 bottom-3 rounded"} ring-2 ring-emerald-400/80`} aria-hidden="true" /> : null}
      {typeof data.hits === "number" ? <small className="absolute bottom-1 left-1 z-20 rounded border border-emerald-800 bg-emerald-950 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-emerald-300" title="Сколько раз выполнялся в последнем запуске">×{data.hits}{typeof data.iterations === "number" ? ` · итераций ${data.iterations}` : ""}</small> : null}
      {data.complexityFragment ? <small className="absolute top-1 right-1 z-20 rounded border border-rose-900 bg-rose-950 px-1.5 py-1 font-mono text-[10px] font-semibold text-rose-300">{data.complexityFragment}</small> : null}
      <Handle type="source" position={Position.Bottom} style={{ bottom: verticalInset }} />
    </div>
  );
});

const nodeTypes = { cfg: CfgFlowNode };

/**
 * ELK ends a route on the bounding box of a node, but the visible shape is smaller (a diamond, an inset block, a
 * circle). Moves an end point along its last segment onto the outline the user sees, so lines touch the blocks.
 */
function toOutline(point: Point, toward: Point, kind: string, box: Point): Point {
  const local = { x: point.x - box.x, y: point.y - box.y };
  const vertical = Math.abs(toward.x - point.x) < Math.abs(toward.y - point.y);
  const cx = NODE_WIDTH / 2;
  const cy = NODE_HEIGHT / 2;
  if (kind === "branch") {
    // diamond |x - cx| / cx + |y - cy| / cy = 1
    if (vertical) {
      const dy = cy * (1 - Math.min(1, Math.abs(local.x - cx) / cx));
      return { x: point.x, y: box.y + (local.y < cy ? cy - dy : cy + dy) };
    }
    const dx = cx * (1 - Math.min(1, Math.abs(local.y - cy) / cy));
    return { x: box.x + (local.x < cx ? cx - dx : cx + dx), y: point.y };
  }
  if (kind === "merge") {
    const r = 36;
    const center = { x: 124, y: 62 };
    if (vertical) {
      const dx = Math.min(r, Math.abs(local.x - center.x));
      const dy = Math.sqrt(r * r - dx * dx);
      return { x: point.x, y: box.y + (local.y < center.y ? center.y - dy : center.y + dy) };
    }
    const dy = Math.min(r, Math.abs(local.y - center.y));
    const dx = Math.sqrt(r * r - dy * dy);
    return { x: box.x + (local.x < center.x ? center.x - dx : center.x + dx), y: point.y };
  }
  // blocks and entry/exit pills: inset 16px from top and bottom, 4px from the sides
  if (vertical) return { x: point.x, y: box.y + (local.y < cy ? 16 : NODE_HEIGHT - 16) };
  return { x: box.x + (local.x < cx ? 4 : NODE_WIDTH - 4), y: point.y };
}

function attach(points: Point[] | undefined, from: { kind: string; at: Point } | null, to: { kind: string; at: Point } | null) {
  if (!points || points.length < 2 || !from || !to) return points ?? null;
  const copy = points.map((point) => ({ ...point }));
  copy[0] = toOutline(copy[0], copy[1], from.kind, from.at);
  const last = copy.length - 1;
  copy[last] = toOutline(copy[last], copy[last - 1], to.kind, to.at);
  return copy;
}

const edgeTypes = { routed: RoutedEdge };

export function CfgViewer({
  projectId,
  revision,
  methods,
  selectedSourceRange,
  selectedMethodSelector,
  onSelectedMethodSelectorChange,
  onSourceRangeSelect,
  onTransformed,
  showAnalysisSummary = true,
  blockTemplate = null,
  onBlockDrop,
  trace = null,
  panelContainer = null,
  onNodeSelected,
}: {
  projectId: string;
  revision: number;
  methods: CfgMethod[];
  selectedSourceRange: SourceRange | null;
  selectedMethodSelector: string;
  onSelectedMethodSelectorChange: (selector: string) => void;
  onSourceRangeSelect: (range: SourceRange | null, keepGraphVisible?: boolean) => void;
  onTransformed: () => Promise<void>;
  showAnalysisSummary?: boolean;
  blockTemplate?: { code: string; nonce: number; position?: "before" | "after" } | null;
  /** A palette block was dropped on a statement node. */
  onBlockDrop?: (template: BlockTemplate) => void;
  /** Execution counts of the last «Запуск» of this revision, keyed `<file>:<offset>`. */
  trace?: { revision: number; hits: Record<string, number> } | null;
  panelContainer?: HTMLElement | null;
  onNodeSelected?: () => void;
}) {
  const selector = methods.some((method) => method.selector === selectedMethodSelector)
    ? selectedMethodSelector
    : methods[0]?.selector ?? "";
  const method = methods.find((item) => item.selector === selector);
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }>>(new Map());
  const [routes, setRoutes] = useState<Map<number, Point[]>>(new Map());
  const [activeBranch, setActiveBranch] = useState<CfgEdge | null>(null);
  const [conditionDraft, setConditionDraft] = useState<{ nodeId: string; value: string } | null>(null);
  const [transformState, setTransformState] = useState<"idle" | "saving" | "done" | "error">("idle");
  const [transformError, setTransformError] = useState("");
  const [moveNodeId, setMoveNodeId] = useState("");
  const [targetNodeId, setTargetNodeId] = useState("");
  const [movePosition, setMovePosition] = useState<"before" | "after">("before");
  const [movePreview, setMovePreview] = useState<CfgEditPreview | null>(null);
  const [moveBusy, setMoveBusy] = useState(false);
  const [drag, setDrag] = useState<{ id: string; position: { x: number; y: number } } | null>(null);
  const [dropHint, setDropHint] = useState("");
  const [moveError, setMoveError] = useState("");
  const flowRef = useRef<ReactFlowInstance<FlowNode, Edge> | null>(null);

  const cfgRequest = useSWR(
    selector ? `cfg:${projectId}:${revision}:${selector}` : null,
    () => getMethodCfg(projectId, selector).then((cfg) => {
      validateCfg(cfg, method?.id ?? "");
      return cfg;
    }),
    { shouldRetryOnError: false, revalidateOnFocus: false },
  );
  const analysisRequest = useSWR(
    selector ? `method-analysis:${projectId}:${revision}:${selector}` : null,
    () => getMethodAnalysis(projectId, selector).then((result) => {
      if (result.revision !== revision || result.method_id !== method?.id) {
        throw new ApiError("Анализ метода относится к другой ревизии.", 409, "revision_conflict");
      }
      return result.analysis;
    }),
    { shouldRetryOnError: false, revalidateOnFocus: false },
  );

  const cfg = cfgRequest.data ?? null;
  const methodAnalysis = analysisRequest.data ?? null;
  const selectedNode = cfg && selectedSourceRange
    ? matchingNode(cfg, selectedSourceRange)
    : null;

  const conditionExpression = selectedNode
    && conditionDraft?.nodeId === selectedNode.id
    ? conditionDraft.value
    : selectedNode?.text ?? "";

  useEffect(() => {
    let cancelled = false;
    if (!cfg?.nodes.length) return;
    void import("elkjs/lib/elk.bundled.js").then(({ default: ELK }) => new ELK().layout({
      id: "cfg",
      layoutOptions: {
        "elk.algorithm": "layered",
        "elk.direction": "DOWN",
        "elk.edgeRouting": "ORTHOGONAL",
        "elk.layered.spacing.nodeNodeBetweenLayers": "34",
        "elk.spacing.nodeNode": "34",
        // keep parallel lines apart and route around nodes instead of through each other
        "elk.spacing.edgeEdge": "16",
        "elk.spacing.edgeNode": "20",
        "elk.layered.spacing.edgeEdgeBetweenLayers": "14",
        "elk.layered.spacing.edgeNodeBetweenLayers": "18",
        "elk.layered.nodePlacement.strategy": "NETWORK_SIMPLEX",
        "elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
        "elk.layered.cycleBreaking.strategy": "MODEL_ORDER",
        "elk.layered.mergeEdges": "false",
      },
      children: cfg.nodes.map((node) => ({
        id: node.id,
        width: NODE_WIDTH,
        height: NODE_HEIGHT,
      })),
      // loop back edges are laid out reversed, so the graph is acyclic: entry on top, every loop body below its head
      edges: cfg.edges.map((edge, index) => ({
        id: `e${index}`,
        sources: [edge.back ? edge.to : edge.from],
        targets: [edge.back ? edge.from : edge.to],
      })),
    })).then((graph) => {
      if (!cancelled) {
        const routed = new Map<number, Point[]>();
        for (const edge of (graph.edges ?? []) as { id: string; sections?: { startPoint: Point; endPoint: Point; bendPoints?: Point[] }[] }[]) {
          const section = edge.sections?.[0];
          const index = Number(edge.id.slice(1));
          if (!section || !Number.isInteger(index)) continue;
          const points = [section.startPoint, ...(section.bendPoints ?? []), section.endPoint];
          routed.set(index, cfg.edges[index]?.back ? points.reverse() : points);
        }
        setRoutes(routed);
        setPositions(new Map(
          graph.children?.map((node) => [
            node.id,
            { x: node.x ?? 0, y: node.y ?? 0 },
          ]) ?? [],
        ));
      }
    }).catch(() => {
      if (!cancelled) setPositions(layoutGrid(cfg.nodes));
    });
    return () => {
      cancelled = true;
    };
  }, [cfg]);

  useEffect(() => {
    if (!positions.size || !flowRef.current) return;
    const frame = window.requestAnimationFrame(() => {
      void flowRef.current?.fitView({ padding: 0.06, minZoom: 0.5, maxZoom: 1.2 });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [positions]);

  const activeEdges = useMemo(
    () => cfg && activeBranch ? branchFootprint(cfg, activeBranch) : new Set<number>(),
    [cfg, activeBranch],
  );
  const highlighted = useMemo(() => analysisHighlights(methodAnalysis), [methodAnalysis]);
  const traceOf = useMemo(() => {
    const active = trace && trace.revision === revision ? trace.hits : null;
    if (!active) return () => ({});
    // the counters are keyed by file and offset; a node without its own file belongs to the method's file
    const byOffset = new Map<string, number>();
    for (const [key, count] of Object.entries(active)) {
      const match = /^(.*):(\d+)(:i)?$/.exec(key);
      if (match) byOffset.set(`${match[2]}${match[3] ?? ""}`, (byOffset.get(`${match[2]}${match[3] ?? ""}`) ?? 0) + count);
      if (match) byOffset.set(`${match[1]}|${match[2]}${match[3] ?? ""}`, count);
    }
    const file = method?.source_range.file;
    return (node: CfgNode) => {
      if (node.kind === "entry" || node.kind === "exit" || node.kind === "merge") return {};
      const start = node.source_range.start;
      const own = node.source_range.file ?? file;
      const hits = own ? byOffset.get(`${own}|${start}`) : byOffset.get(`${start}`);
      const iterations = own ? byOffset.get(`${own}|${start}:i`) : byOffset.get(`${start}:i`);
      return { hits: hits ?? null, iterations: iterations ?? null };
    };
  }, [trace, revision, method?.source_range.file]);
  const endpoint = useCallback((id: string) => {
    const node = cfg?.nodes.find((item) => item.id === id);
    const at = positions.get(id);
    return node && at ? { kind: node.kind, at } : null;
  }, [cfg, positions]);
  const flow = useMemo(() => ({
    nodes: cfg?.nodes.map((node) => ({
      id: node.id,
      type: "cfg" as const,
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
      position: drag?.id === node.id ? drag.position : positions.get(node.id) ?? { x: 0, y: 0 },
      draggable: node.kind === "statement",
      data: {
        ...node,
        hasFinding: highlighted.findings.has(node.id),
        ...traceOf(node),
        complexityFragment: highlighted.fragments.get(node.id) ?? null,
      },
      selected: node.id === selectedNode?.id,
    })) ?? [],
    edges: cfg?.edges.map((edge, index) => {
      const active = activeEdges.has(index);
      const back = edge.back === true;
      return {
        id: `${edge.from}-${edge.to}-${index}`,
        source: edge.from,
        target: edge.to,
        type: "routed" as const,
        // a dragged node leaves its routed lines behind, so they fall back to a plain path while it moves
        data: {
          points: drag && (drag.id === edge.from || drag.id === edge.to) ? null : attach(
            routes.get(index),
            endpoint(edge.from),
            endpoint(edge.to),
          ),
        },
        label: edge.label || formatEdgeKind(edge.kind),
        markerEnd: { type: MarkerType.ArrowClosed, color: active ? "#bb613e" : "#69786d" },
        className: active ? "cfg-edge--active" : undefined,
        style: {
          stroke: active ? "#bb613e" : back ? "#b56e41" : "#69786d",
          strokeWidth: active ? 2.8 : back ? 2 : 1.5,
          strokeDasharray: back ? "5 4" : undefined,
        },
        labelStyle: { fill: "#f6f5ed", fontSize: 11, fontWeight: 600 },
        labelBgStyle: { fill: active ? "#9b4a2f" : "#263b31", fillOpacity: 0.98 },
        labelBgPadding: [4, 2] as [number, number],
        labelBgBorderRadius: 2,
      };
    }) ?? [],
  }), [activeEdges, cfg, drag, endpoint, highlighted, positions, routes, selectedNode?.id, traceOf]);

  /** The statement node under a screen point, other than `except`. */
  function statementUnder(clientX: number, clientY: number, except = "") {
    for (const element of document.elementsFromPoint(clientX, clientY)) {
      const holder = element.closest(".react-flow__node");
      const id = holder?.getAttribute("data-id");
      if (id && id !== except && cfg?.nodes.find((node) => node.id === id)?.kind === "statement") return { id, rect: holder!.getBoundingClientRect() };
    }
    return null;
  }

  function onDropBlock(event: DragEvent<HTMLDivElement>) {
    const raw = event.dataTransfer.getData(BLOCK_MIME);
    if (!raw) return;
    event.preventDefault();
    const target = statementUnder(event.clientX, event.clientY);
    setDropHint("");
    if (!target) { setDropHint("Бросьте блок на оператор (прямоугольный узел), а не на условие или пустое место."); return; }
    const block = JSON.parse(raw) as { id: string; label: string; code: string };
    setMoveNodeId(target.id);
    onBlockDrop?.({ id: block.id, label: block.label, code: block.code, nonce: Date.now(), position: event.clientY < target.rect.top + target.rect.height / 2 ? "before" : "after" });
  }

  function onNodesChange(changes: NodeChange<FlowNode>[]) {
    for (const change of changes) {
      if (change.type === "position" && change.position) setDrag({ id: change.id, position: change.position });
    }
  }

  function onNodeDragStop(event: globalThis.MouseEvent | TouchEvent, node: FlowNode) {
    setDrag(null);
    const point = "changedTouches" in event ? event.changedTouches[0] : event;
    const target = point ? statementUnder(point.clientX, point.clientY, node.id) : null;
    if (!target || !point) return;
    const where = point.clientY < target.rect.top + target.rect.height / 2 ? "before" : "after";
    setMoveNodeId(node.id);
    setTargetNodeId(target.id);
    setMovePosition(where);
    onNodeSelected?.();
    void requestMovePreview(node.id, target.id, where);
  }

  const onNodeClick = useCallback((_event: MouseEvent, node: FlowNode) => {
    setMoveNodeId(node.data.kind === "statement" ? node.id : "");
    onNodeSelected?.();
    setMovePreview(null);
    setMoveError("");
    onSourceRangeSelect(node.data.source_range, node.data.kind === "branch");
    if (!cfg || node.data.kind !== "branch") {
      setActiveBranch(null);
      return;
    }
    setActiveBranch((current) => {
      const nextKind = current?.from === node.id && current.kind === "true" ? "false" : "true";
      return findBranch(cfg, node.id, nextKind) ?? findBranch(cfg, node.id, "true");
    });
  }, [cfg, onSourceRangeSelect, onNodeSelected]);

  /** Big O, move, condition and block panels: in the analysis pane when a container is given, under the graph otherwise. */
  const renderLower = (children: ReactNode) => panelContainer
    ? createPortal(<div className="grid gap-0">{children}</div>, panelContainer)
    : <div className="max-h-[34%] min-h-0 shrink-0 overflow-auto">{children}</div>;
  const moveSource = cfg?.nodes.find((node) => node.id === moveNodeId);
  const moveTarget = cfg?.nodes.find((node) => node.id === targetNodeId);

  async function requestMovePreview(sourceId = moveSource?.id, targetId = moveTarget?.id, where = movePosition) {
    if (!method || !sourceId || !targetId || sourceId === targetId) return;
    const operation: CfgEditOperation = { type: "move_statement", node_id: sourceId, target_node_id: targetId, position: where };
    setMoveBusy(true);
    setMoveError("");
    setMovePreview(null);
    try {
      setMovePreview(await previewCfgEdit(projectId, revision, method.id, operation));
    } catch (cause) {
      setMoveError(cfgEditErrorMessage(cause));
    } finally {
      setMoveBusy(false);
    }
  }

  async function confirmMove() {
    if (!method || !movePreview || !moveSource || !moveTarget) return;
    const operation: CfgEditOperation = { type: "move_statement", node_id: moveSource.id, target_node_id: moveTarget.id, position: movePosition };
    setMoveBusy(true);
    setMoveError("");
    try {
      await applyCfgEdit(projectId, revision, method.id, operation, movePreview.preview_sha256);
      setMovePreview(null);
      setMoveNodeId("");
      setTargetNodeId("");
      await onTransformed();
    } catch (cause) {
      setMoveError(cfgEditErrorMessage(cause));
    } finally {
      setMoveBusy(false);
    }
  }

  async function applyCondition() {
    if (!cfg || !method || selectedNode?.kind !== "branch" || !conditionExpression.trim()) return;
    setTransformState("saving");
    setTransformError("");
    try {
      await replaceCondition(
        projectId,
        revision,
        method.id,
        selectedNode.id,
        conditionExpression.trim(),
      );
      setTransformState("done");
      await onTransformed();
    } catch (cause) {
      setTransformState("error");
      setTransformError(transformErrorMessage(cause));
    }
  }

  const cfgState = !selector
    ? "empty"
    : cfgRequest.isLoading || (!cfgRequest.data && !cfgRequest.error)
      ? "loading"
      : cfgRequest.error
        ? "error"
        : "ready";

  return (
    <section className="flex size-full min-h-0 min-w-0 flex-col overflow-hidden bg-[#080f1b]" aria-label="Граф потока управления">
      <header className="flex min-h-[46px] items-center justify-between gap-3 border-b border-slate-800 bg-[#0d1625] px-3">
        <div className="flex min-w-0 items-center gap-2.5">
          <GitBranch size={16} className="shrink-0 text-violet-400" />
          <h2 className="m-0 text-[14px] font-medium text-slate-200">Поток выполнения</h2>
        </div>
        <div className="flex min-w-0 items-center gap-2">
          <label className="text-[13px] text-slate-500" htmlFor="cfg-method">Метод</label>
          <select
            className="h-8 min-w-52 rounded border border-slate-700 bg-[#0c1422] px-2 font-mono text-[13px] text-slate-300 outline-none"
            id="cfg-method"
            value={selector}
            onChange={(event) => {
              setActiveBranch(null);
              setConditionDraft(null);
              setTransformState("idle");
              setTransformError("");
              onSelectedMethodSelectorChange(event.target.value);
            }}
          >
            <option value="" disabled>{methods.length ? "Выберите метод" : "Нет методов с CFG"}</option>
            {methods.map((item) => (
              <option key={item.selector} value={item.selector}>
                {item.selector} · {item.id}
              </option>
            ))}
          </select>
          {selector ? (
            <Button variant="outline" size="sm" onClick={() => {
              void cfgRequest.mutate();
              void analysisRequest.mutate();
            }} aria-label="Обновить CFG">
              <RefreshCw size={14} />
            </Button>
          ) : null}
        </div>
      </header>

      <div className="relative min-h-[200px] flex-1 overflow-hidden">
        {cfgState === "loading" ? (
          <div className="flex min-h-full flex-col items-center justify-center gap-2 p-7 text-center text-slate-500" role="status"><LoaderCircle className="spin text-cyan-400" /><strong className="text-slate-300">Загружаем CFG</strong><span>Селектор {selector} · ревизия {revision}</span></div>
        ) : null}
        {cfgState === "error" ? (
          <div className="flex min-h-full flex-col items-center justify-center gap-2 p-7 text-center text-slate-500" role="alert"><AlertCircle className="text-rose-400" /><strong className="text-slate-300">CFG недоступен</strong><span>{apiMessage(cfgRequest.error)}</span><Button variant="outline" size="sm" onClick={() => void cfgRequest.mutate()}><RefreshCw size={14} /> Повторить</Button></div>
        ) : null}
        {cfgState === "empty" ? (
          <div className="flex min-h-full flex-col items-center justify-center gap-2 p-7 text-center text-slate-500"><GitBranch className="text-cyan-400" /><strong className="text-slate-300">Нет методов с CFG</strong><span>Завершите анализ сохранённой ревизии.</span></div>
        ) : null}
        {cfgState === "ready" && cfg && !cfg.nodes.length ? (
          <div className="flex min-h-full flex-col items-center justify-center gap-2 p-7 text-center text-slate-500"><GitBranch className="text-cyan-400" /><strong className="text-slate-300">Граф пуст</strong></div>
        ) : null}
        {cfgState === "ready" && cfg?.nodes.length ? (
          <div onDragOver={(event) => { if (event.dataTransfer.types.includes(BLOCK_MIME)) event.preventDefault(); }} onDrop={onDropBlock} className="relative size-full bg-[#080f1b] [background-image:linear-gradient(rgba(51,65,85,.18)_1px,transparent_1px),linear-gradient(90deg,rgba(51,65,85,.18)_1px,transparent_1px)] [background-size:24px_24px]">
            <p className="absolute top-2 left-2 z-10 m-0 rounded border border-slate-700 bg-slate-950/90 px-2 py-1 text-[12px] text-slate-400">{dropHint || "Клик — код; перетащите оператор на другой, чтобы переставить; бросьте блок из палитры, чтобы вставить."}</p>
            <ReactFlow
              nodes={flow.nodes}
              edges={flow.edges}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              onNodeClick={onNodeClick}
              onInit={(instance) => { flowRef.current = instance as unknown as ReactFlowInstance<FlowNode, Edge>; }}
              fitView
              fitViewOptions={{ padding: 0.06, minZoom: 0.5, maxZoom: 1.2 }}
              minZoom={0.04}
              maxZoom={1.6}
              nodesDraggable
              nodesConnectable={false}
              onNodesChange={onNodesChange}
              onNodeDragStop={onNodeDragStop}
            >
              <Background color="#d7dbd3" gap={20} size={1} />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
        ) : null}
      </div>

      {renderLower(<>
      {showAnalysisSummary && methodAnalysis ? <BigOPanel analysis={methodAnalysis} onSelect={onSourceRangeSelect} /> : showAnalysisSummary && analysisRequest.error ? (
        <div className="flex min-h-[30px] items-center gap-1.5 border-t border-slate-800 bg-[#0d1625] px-3 text-[13px] text-slate-400"><AlertCircle size={15} /> Big O для метода недоступна: {apiMessage(analysisRequest.error)}</div>
      ) : showAnalysisSummary && selector ? (
        <div className="flex min-h-[30px] items-center gap-1.5 border-t border-slate-800 bg-[#0d1625] px-3 text-[13px] text-slate-400"><LoaderCircle className="spin" size={15} /> Загружаем Big O…</div>
      ) : null}

      {selectedNode?.kind === "branch" && method ? (
        <div className="grid grid-cols-[minmax(210px,.8fr)_minmax(200px,1fr)_auto] items-center gap-3 border-t border-slate-800 bg-[#0d1625] px-3 py-2.5" aria-label="Изменение условия через ASTRewrite">
          <div className="min-w-0">
            <strong className="flex items-center gap-1.5 text-[13px] text-violet-200"><WandSparkles size={15} /> Заменить условие (ASTRewrite)</strong>
            <span className="mt-1 block text-[12px] leading-5 text-slate-500">{method.id} · узел {selectedNode.id}. Создаётся новая ревизия: затем сравните её в «Ревизиях» и запустите «Проверку».</span>
          </div>
          <input
            className="h-8 w-full rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[13px] text-slate-200 outline-none focus:border-cyan-500"
            aria-label="Новое Java-условие"
            value={conditionExpression}
            onChange={(event) => setConditionDraft({ nodeId: selectedNode.id, value: event.target.value })}
            disabled={transformState === "saving"}
          />
          <Button
            onClick={() => void applyCondition()}
            disabled={transformState === "saving" || !conditionExpression.trim() || conditionExpression.trim() === (selectedNode.text ?? "").trim()}
          >
            {transformState === "saving" ? <LoaderCircle className="spin" /> : <WandSparkles />}
            {transformState === "saving" ? "Применяем" : "Создать ревизию"}
          </Button>
          {transformState === "error" ? <p className="col-start-2 col-end-[-1] m-0 text-[12px] text-rose-300" role="alert">{transformError}</p> : null}
          {transformState === "done" ? <p className="col-start-2 col-end-[-1] m-0 flex items-center gap-1.5 text-[12px] text-emerald-300"><Check size={14} /> Новая ревизия создана. Сравните её с текущей и запустите проверку поведения.</p> : null}
        </div>
      ) : null}
      {method ? (
        <section className="grid gap-2 border-t border-slate-800 bg-[#0d1625] px-3 py-2.5" aria-label="Перемещение оператора в исходном коде">
          <div className="flex flex-wrap items-center gap-2">
            <strong className="flex items-center gap-1.5 text-[13px] text-slate-300"><MoveRight size={15} /> Переместить оператор</strong>
            <span className="text-[12px] text-slate-500">Это меняет Java только после проверки и подтверждения.</span>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-[12px]">
            <span className="font-mono text-slate-400">Источник: {moveSource?.id ?? "нажмите на оператор"}</span>
            <label className="text-slate-400">Цель
              <select aria-label="Целевой оператор" className="ml-1 h-8 w-full min-w-[160px] max-w-[420px] rounded border border-slate-700 bg-[#08101d] px-2 text-slate-200" value={targetNodeId} onChange={(event) => setTargetNodeId(event.target.value)}>
                <option value="">Не выбрана</option>
                {(cfg?.nodes ?? []).filter((node) => node.kind === "statement" && node.id !== moveNodeId).map((node) => <option value={node.id} key={node.id}>{node.id} · {node.text || node.statement_kind}</option>)}
              </select>
            </label>
            <label className="text-slate-400">Позиция
              <select aria-label="Позиция перемещения" className="ml-1 h-8 rounded border border-slate-700 bg-[#08101d] px-2 text-slate-200" value={movePosition} onChange={(event) => setMovePosition(event.target.value as "before" | "after")}>
                <option value="before">Перед целью</option><option value="after">После цели</option>
              </select>
            </label>
            <Button variant="outline" size="sm" disabled={!moveSource || !moveTarget || moveBusy} onClick={() => void requestMovePreview()}>{moveBusy ? <LoaderCircle className="spin" /> : <MoveRight />} Предпросмотр</Button>
          </div>
          <p className="m-0 text-[11px] text-slate-500">Перетаскивание узлов меняет только раскладку; неподдерживаемые и зависимые перемещения отклоняются API.</p>
          {moveError ? <p className="m-0 text-[12px] text-rose-300" role="alert">{moveError}</p> : null}
          {movePreview ? <PreviewDialog title="Перемещение оператора: проверьте diff" onClose={() => setMovePreview(null)}><div className="grid gap-2" aria-label="Предпросмотр перемещения"><DiffView files={movePreview.diff} label="Diff перемещения CFG" /><div className="flex flex-wrap items-center gap-2"><Button onClick={() => void confirmMove()} disabled={moveBusy}>{moveBusy ? <LoaderCircle className="spin" /> : <Check />} Подтвердить перемещение</Button><Button variant="outline" onClick={() => setMovePreview(null)} disabled={moveBusy}>Отмена</Button><small className="text-slate-500">Preview привязан к ревизии {movePreview.base_revision}.</small></div></div></PreviewDialog> : null}
        </section>
      ) : null}
      {method ? <BlockEditor key={`${method.id}:${blockTemplate?.nonce ?? 0}`} initialCode={blockTemplate?.code} autoInsert={blockTemplate?.position} projectId={projectId} revision={revision} methodId={method.id} nodeId={moveNodeId} nodeLabel={moveSource?.text ?? ""} statements={(cfg?.nodes ?? []).filter((node) => node.kind === "statement").map((node) => ({ id: node.id, text: (node.text ?? node.statement_kind ?? "").slice(0, 60) }))} onApplied={onTransformed} /> : null}
      </>)}
    </section>
  );
}

function BigOPanel({
  analysis,
  onSelect,
}: {
  analysis: MethodAnalysis;
  onSelect: (range: SourceRange | null, keepGraphVisible?: boolean) => void;
}) {
  const complexity = analysis.complexity;
  const variables = variablesOf(complexity);
  const origins = originsOf(complexity, analysis.fragments);
  return (
    <section className="grid gap-3 border-t border-slate-800 bg-slate-950 p-3 text-[13px] leading-5 text-slate-300" aria-label="Оценка сложности метода">
      <div className="flex flex-wrap items-center gap-2">
        <span className="flex items-center gap-1.5 text-[12px] text-slate-500"><Sparkles size={15} /> Сложность по времени</span>
        <strong className="rounded bg-violet-400/10 px-2 py-0.5 font-mono text-[18px] text-violet-200">{pretty(complexity.time)}</strong>
        <small className="text-[11px] text-slate-500">уверенность: {confidenceLabel(complexity.confidence)} · оценка сверху по коду, без запуска</small>
      </div>
      {variables.length ? (
        <div className="grid gap-1" aria-label="Что означают буквы">
          <strong className="text-[12px] text-slate-400">Что означают буквы <span className="font-normal text-slate-500">· размеры входных данных, от которых зависит время</span></strong>
          {variables.map((item) => <p key={item.letter} className="m-0 flex gap-2"><code className="w-5 shrink-0 font-mono font-semibold text-violet-200">{item.letter}</code><span>{item.what}</span></p>)}
        </div>
      ) : null}
      <div className="grid gap-1" aria-label="Как читать оценку">
        <strong className="text-[12px] text-slate-400">Как читать</strong>
        {readFormula(complexity).map((sentence) => <p key={sentence} className="m-0">{sentence}</p>)}
      </div>
      {origins.length ? (
        <div className="grid gap-1" aria-label="Откуда берётся оценка">
          <strong className="text-[12px] text-slate-400">Откуда берётся (циклы метода)</strong>
          {origins.map((origin) => (
            <button key={`${origin.range.start}`} type="button" className="flex items-center gap-2 rounded border border-slate-800 bg-slate-900/60 px-2 py-1 text-left hover:border-cyan-700" onClick={() => onSelect(origin.range, true)}>
              <span className="w-16 shrink-0 font-mono text-[11px] text-slate-500">стр. {origin.line ?? "—"}</span>
              <span className="min-w-0 flex-1 truncate">{origin.label}</span>
              <code className="shrink-0 font-mono text-[12px] text-violet-200">{origin.time}</code>
            </button>
          ))}
          <small className="text-[11px] text-slate-500">Вложенный цикл даёт произведение, следующие друг за другом — сумму. Клик — строка в коде и узел на графе.</small>
        </div>
      ) : null}
      <details className="text-[12px] text-slate-400"><summary className="cursor-pointer text-slate-500">Как анализатор пришёл к оценке и что он допускает</summary>
        <p className="m-0 mt-1">{ruText(complexity.reason)}</p>
        <ul className="mt-1 mb-0 pl-4 [&_li]:leading-5">{modelAssumptions(complexity).map((item) => <li key={item}>{ruText(item)}</li>)}</ul>
      </details>
      {analysis.findings.length ? (
        <div className="grid gap-1.5">
          {analysis.findings.map((finding) => (
            <details key={finding.id + finding.source_range.start} className="rounded border border-slate-700 bg-slate-900">
              <summary className="cursor-pointer p-2 text-[13px] text-slate-300">{ruFinding(finding).title}</summary>
              <div className="border-t border-slate-800 p-2 text-[12px] leading-5">
                <p className="m-0">{ruFinding(finding).explanation}</p>
                <p className="mt-1 mb-0 text-slate-300">Рекомендация: {ruFinding(finding).recommendation}</p>
                <Button variant="ghost" size="sm" className="mt-1.5" onClick={() => onSelect(finding.source_range)}>К коду</Button>
              </div>
            </details>
          ))}
        </div>
      ) : <small className="text-emerald-300">Проблем в этом методе не найдено.</small>}
    </section>
  );
}

function analysisHighlights(analysis: MethodAnalysis | null) {
  const findings = new Set<string>();
  const fragments = new Map<string, string>();
  for (const finding of analysis?.findings ?? []) {
    for (const nodeId of finding.node_ids) findings.add(nodeId);
  }
  for (const fragment of analysis?.fragments ?? []) {
    for (const nodeId of fragment.node_ids) fragments.set(nodeId, fragment.time);
  }
  return { findings, fragments };
}

function validateCfg(cfg: Cfg, methodId: string) {
  if (!cfg || cfg.method_id !== methodId || !Array.isArray(cfg.nodes) || !Array.isArray(cfg.edges)) {
    throw new ApiError("API вернул CFG в неизвестном формате.", 502, "invalid_response");
  }
  const ids = new Set<string>();
  for (const node of cfg.nodes) {
    if (!node || typeof node.id !== "string" || ids.has(node.id)
      || typeof node.kind !== "string"
      || !Number.isSafeInteger(node.source_range?.start)
      || !Number.isSafeInteger(node.source_range?.length)) {
      throw new ApiError("CFG содержит некорректный узел.", 502, "invalid_response");
    }
    ids.add(node.id);
  }
  if (!ids.has(cfg.entry) || !ids.has(cfg.exit)
    || cfg.edges.some((edge) => !ids.has(edge.from) || !ids.has(edge.to))) {
    throw new ApiError("CFG содержит некорректные связи.", 502, "invalid_response");
  }
}

function matchingNode(cfg: Cfg, range: SourceRange) {
  const selectionEnd = range.start + Math.max(range.length, 1);
  return cfg.nodes
    .map((node) => ({
      node,
      start: node.source_range.start,
      end: node.source_range.start + Math.max(node.source_range.length, 1),
    }))
    .filter(({ start, end }) => start < selectionEnd && range.start < end)
    .sort((left, right) => (left.end - left.start) - (right.end - right.start))[0]?.node ?? null;
}

function cfgEditErrorMessage(error: unknown) {
  if (!(error instanceof ApiError)) return "Не удалось получить предпросмотр перемещения.";
  const details = error.details as { reasons?: { code?: string; message?: string }[] } | undefined;
  const reasons = details?.reasons?.map((reason) => reason.message ?? reason.code).filter(Boolean).join("; ");
  if (error.code === "move_rejected") return reasons || "Перемещение небезопасно или не поддерживается для этих операторов.";
  if (error.code === "revision_conflict") return "Ревизия устарела. Обновите CFG перед следующим действием.";
  if (error.code === "unsupported_node") return "Выберите обычные операторы, а не узлы входа, слияния или выхода.";
  return error.message;
}

function findBranch(cfg: Cfg, source: string, kind: "true" | "false") {
  return cfg.edges.find((edge) => edge.from === source && edge.kind === kind) ?? null;
}

function branchFootprint(cfg: Cfg, branch: CfgEdge) {
  const result = new Set<number>();
  const first = cfg.edges.findIndex((edge) => edge === branch);
  if (first < 0) return result;
  result.add(first);
  const kinds = new Map(cfg.nodes.map((node) => [node.id, node.kind]));
  const visited = new Set([branch.from]);
  const queue = [branch.to];
  while (queue.length) {
    const current = queue.shift()!;
    if (visited.has(current)) continue;
    visited.add(current);
    if (["merge", "exit", "return", "throw"].includes(kinds.get(current) ?? "")) continue;
    cfg.edges.forEach((edge, index) => {
      if (edge.from !== current || edge.back) return;
      result.add(index);
      queue.push(edge.to);
    });
  }
  return result;
}

function layoutGrid(nodes: CfgNode[]) {
  const columns = Math.max(1, Math.ceil(Math.sqrt(nodes.length)));
  return new Map(nodes.map((node, index) => [
    node.id,
    { x: (index % columns) * (NODE_WIDTH + 38), y: Math.floor(index / columns) * (NODE_HEIGHT + 66) },
  ]));
}

function formatNodeKind(kind: string, statementKind?: string) {
  if (kind === "branch") {
    if (statementKind && ["while", "do_while", "foreach", "for"].includes(statementKind)) return `ЦИКЛ · ${statementKind.replace("_", " ").toLocaleUpperCase("ru-RU")}`;
    return statementKind ? `УСЛОВИЕ · ${statementKind.toLocaleUpperCase("ru-RU")}` : "УСЛОВИЕ";
  }
  if (kind === "statement" && statementKind === "variable_declaration") return "ПЕРЕМЕННАЯ";
  const labels: Record<string, string> = {
    entry: "МЕТОД · ВХОД",
    exit: "МЕТОД · ВЫХОД",
    merge: "СЛИЯНИЕ",
    statement: "ОПЕРАЦИЯ",
    return: "ВОЗВРАТ",
    throw: "ИСКЛЮЧЕНИЕ",
  };
  return labels[kind] ?? kind.toLocaleUpperCase("ru-RU");
}

function formatEdgeKind(kind: string) {
  const labels: Record<string, string> = {
    next: "",
    true: "Да",
    false: "Нет",
    break: "break",
    continue: "continue",
    return: "return",
    exception: "exception",
    finally: "finally",
    case: "case",
    default: "default",
  };
  return labels[kind] ?? kind;
}

function confidenceLabel(value: string) {
  if (value === "high") return "высокая";
  if (value === "medium") return "средняя";
  return "низкая";
}

function apiMessage(error: unknown) {
  return error instanceof ApiError ? error.message : "Не удалось получить данные метода.";
}

function transformErrorMessage(error: unknown) {
  if (!(error instanceof ApiError)) return "Не удалось применить преобразование.";
  if (error.status === 409) return "Ревизия устарела. Перезагрузите проект и повторите изменение.";
  if (error.code === "transformation_failed") return "Java parser отклонил выражение: проверьте синтаксис и типы.";
  if (error.code === "transformation_invalid") return "Новый исходник не прошёл повторный разбор.";
  return error.message;
}
