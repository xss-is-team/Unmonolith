"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { MouseEvent } from "react";
import useSWR from "swr";
import ELK from "elkjs/lib/elk.bundled.js";
import dagre from "@dagrejs/dagre";
import gsap from "gsap";
import { useGSAP } from "@gsap/react";
import {
  Background,
  Controls,
  Handle,
  MiniMap,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
} from "@xyflow/react";
import { AlertCircle, GitBranch, LoaderCircle, RefreshCw } from "lucide-react";
import { ApiError, getMethodCfg, type AnalysisResult, type Cfg, type CfgNode, type ComplexityHotspot } from "@/lib/api";
import { Button } from "@/components/ui/button";

const NODE_WIDTH = 220;
const NODE_HEIGHT = 82;
const elkLayout = new ELK();
gsap.registerPlugin(useGSAP);
const COMPLEXITY_CFG_KINDS: Record<string, readonly string[]> = {
  if: ["if"],
  else_if: ["if"],
  for: ["for"],
  enhanced_for: ["foreach"],
  while: ["while"],
  do_while: ["do_while"],
  switch: ["switch"],
  switch_expression: ["switch"],
  case: ["case"],
  catch: ["catch"],
  labeled_break: ["break"],
  labeled_continue: ["continue"],
  logical_sequence: ["if", "for", "foreach", "while", "do_while", "switch"],
};

type FlowNodeData = CfgNode & { cognitiveContribution: number; cyclomaticContribution: number };
type FlowNode = Node<FlowNodeData, "cfg">;

function CfgFlowNode({ data }: NodeProps<FlowNode>) {
  const complexityLevel = data.cognitiveContribution >= 4 || data.cyclomaticContribution >= 3
    ? "high"
    : data.cognitiveContribution >= 2
      ? "medium"
      : "low";
  const hasComplexity = data.cognitiveContribution > 0 || data.cyclomaticContribution > 0;
  const condition = isCondition(data.kind);
  return (
    <div
      className={`cfg-node cfg-node--${safeKind(data.kind)}${hasComplexity ? ` cfg-node--complexity-${complexityLevel}` : ""}`}
      data-tooltip={condition ? "Кликните: подсветить ветвь «Да»; повторный клик переключит на «Нет»." : undefined}
      role={condition ? "group" : undefined}
      aria-label={condition ? `${data.label}. Нажмите, чтобы подсветить ветвь Да или Нет.` : undefined}
    >
      <Handle type="target" position={Position.Top} />
      <span>{formatNodeKind(data.kind)}</span>
      <strong>{data.label}</strong>
      {condition ? <small className="cfg-node__click-hint">↵ нажмите для подсветки ветви</small> : null}
      {data.cognitiveContribution > 0 || data.cyclomaticContribution > 0 ? (
        <small className="cfg-complexity-mark" title="Вклад узла в структурную сложность метода">
          +{data.cognitiveContribution} Cog · +{data.cyclomaticContribution} CC
        </small>
      ) : null}
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

const nodeTypes = { cfg: CfgFlowNode };

export function CfgViewer({
  projectId,
  revision,
  analysis,
  selectedSourceRange,
  selectedMethodId,
  onSelectedMethodIdChange,
  onSourceRangeSelect,
}: {
  projectId: string;
  revision: number;
  analysis: AnalysisResult | null;
  selectedSourceRange: { start: number; length: number } | null;
  selectedMethodId: string;
  onSelectedMethodIdChange: (methodId: string) => void;
  onSourceRangeSelect: (range: { start: number; length: number } | null, keepGraphVisible?: boolean) => void;
}) {
  const methods = useMemo(() => analysis?.methods ?? [], [analysis]);
  const [conditionValue, setConditionValue] = useState("");
  const [activeBranch, setActiveBranch] = useState<{ source: string; target: string; kind: string } | null>(null);
  const [methodSwitching, setMethodSwitching] = useState(false);
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }>>(new Map());
  const reactFlowRef = useRef<ReactFlowInstance<FlowNode, Edge> | null>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  const exitTweenRef = useRef<gsap.core.Tween | null>(null);
  const methodId = methods.some((method) => method.id === selectedMethodId)
    ? selectedMethodId
    : methods[0]?.id ?? "";
  const methodComplexity = methods.find((method) => method.id === methodId)?.complexity;
  const complexityHotspots = useMemo(() => methodComplexity?.hotspots ?? [], [methodComplexity]);
  const canRequest = Boolean(methodId && analysis && analysis.revision === revision);
  const { data: apiCfg, error: apiError, isLoading, mutate } = useSWR(
    canRequest ? `cfg:${projectId}:${revision}:${methodId}` : null,
    () => getMethodCfg(projectId, methodId).then((result) => {
      validateCfg(result, methodId);
      return result;
    }),
    { shouldRetryOnError: false, revalidateOnFocus: false },
  );
  const cfg = apiCfg ?? null;
  const state: "empty" | "loading" | "ready" | "error" | "unsupported" = !canRequest
    ? "empty"
    : isLoading || (!apiCfg && !apiError)
      ? "loading"
      : apiError instanceof ApiError && apiError.status === 404 && apiError.code === "cfg_not_found"
        ? "unsupported"
        : apiError
          ? "error"
          : "ready";
  const errorMessage = state === "unsupported"
    ? "Для текущей ревизии или выбранного метода граф не найден. Запустите анализ и проверьте, что метод содержит поддерживаемый код."
    : apiError instanceof ApiError
      ? apiError.message
      : "Не удалось загрузить CFG метода.";

  const onNodeClick = useCallback((_event: MouseEvent, node: FlowNode) => {
    setConditionValue(node.data.label);
    onSourceRangeSelect(node.data.source_range, isCondition(node.data.kind));
    if (!isCondition(node.data.kind) || !cfg) {
      setActiveBranch(null);
      return;
    }
    setActiveBranch((current) => {
      if (current?.source === node.id) {
        return findConditionBranch(cfg, node.id, current.kind === "false" ? "true" : "false");
      }
      return findConditionBranch(cfg, node.id, "true") ?? findConditionBranch(cfg, node.id, "false");
    });
  }, [cfg, onSourceRangeSelect]);

  const onEdgeClick = useCallback((_event: MouseEvent, edge: Edge) => {
    if (edge.label !== "Да" && edge.label !== "Нет") return;
    const index = Number(edge.id.slice(edge.id.lastIndexOf("-") + 1));
    const selected = cfg?.edges[index];
    if (!selected) return;
    setActiveBranch((current) => current?.source === selected.source && current.target === selected.target && current.kind === selected.kind
      ? null
      : selected);
  }, [cfg]);

  const selectedCondition = cfg && selectedSourceRange
    ? matchingNode(cfg, selectedSourceRange)
    : null;
  const selectedBranchKind = selectedCondition && activeBranch?.source === selectedCondition.id
    ? activeBranch.kind === "false" ? "false" : "true"
    : null;

  const activeEdges = useMemo(() => cfg && activeBranch ? branchFootprint(cfg, activeBranch) : new Set<number>(), [cfg, activeBranch]);
  const complexityByNode = useMemo(() => mapComplexityToCfg(cfg, complexityHotspots), [cfg, complexityHotspots]);
  const flow = useMemo(() => ({
    nodes: cfg?.nodes.map((node) => ({
      id: node.id,
      type: "cfg" as const,
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
      position: positions.get(node.id) ?? { x: 0, y: 0 },
      data: { ...node, ...(complexityByNode.get(node.id) ?? { cognitiveContribution: 0, cyclomaticContribution: 0 }) },
      selected: node.id === selectedCondition?.id,
    })) ?? [],
    edges: cfg?.edges.map((edge, index) => {
      const highlighted = activeEdges.has(index);
      return {
        id: `${edge.source}-${edge.target}-${index}`,
        source: edge.source,
        target: edge.target,
        type: "smoothstep" as const,
        label: formatEdgeKind(edge.kind),
        markerEnd: { type: MarkerType.ArrowClosed, color: highlighted ? "#bb613e" : "#69786d" },
        className: highlighted ? "cfg-edge--active" : undefined,
        style: {
          stroke: highlighted ? "#bb613e" : edge.kind.startsWith("back") ? "#b56e41" : "#69786d",
          strokeWidth: highlighted ? 2.8 : edge.kind.startsWith("back") ? 2 : 1.5,
          strokeDasharray: edge.kind.startsWith("back") ? "5 4" : undefined,
        },
        labelStyle: { fill: "#f6f5ed", fontSize: 11, fontWeight: 600 },
        labelBgStyle: { fill: highlighted ? "#9b4a2f" : "#263b31", fillOpacity: 0.98 },
        labelBgPadding: [4, 2] as [number, number],
        labelBgBorderRadius: 2,
      };
    }) ?? [],
  }), [cfg, positions, selectedCondition?.id, activeEdges, complexityByNode]);

  useGSAP(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const media = gsap.matchMedia();
    media.add("(prefers-reduced-motion: no-preference)", () => {
      gsap.fromTo(canvas, { autoAlpha: 0, y: 8 }, {
        autoAlpha: 1,
        y: 0,
        duration: 0.24,
        ease: "power2.out",
      });
    });
    return () => media.revert();
  }, { dependencies: [cfg?.method_id, revision], revertOnUpdate: true });

  useGSAP(() => {
    const canvas = canvasRef.current;
    if (!canvas || activeEdges.size === 0) return;
    const media = gsap.matchMedia();
    media.add("(prefers-reduced-motion: no-preference)", () => {
      const paths = canvas.querySelectorAll<SVGPathElement>(".react-flow__edge.cfg-edge--active .react-flow__edge-path");
      if (paths.length) {
        gsap.fromTo(paths, { opacity: 0.42, strokeWidth: 1.5 }, {
          opacity: 1,
          strokeWidth: 2.8,
          duration: 0.38,
          stagger: 0.025,
          ease: "power2.out",
        });
      }
    });
    return () => media.revert();
  }, { dependencies: [activeEdges], scope: canvasRef, revertOnUpdate: true });

  useEffect(() => () => {
    exitTweenRef.current?.kill();
  }, []);

  const changeMethod = useCallback((nextMethodId: string) => {
    if (!nextMethodId || nextMethodId === methodId || methodSwitching) return;
    const finish = () => {
      setMethodSwitching(false);
      onSelectedMethodIdChange(nextMethodId);
    };
    setConditionValue("");
    setActiveBranch(null);
    onSourceRangeSelect(null);
    const canvas = canvasRef.current;
    if (!canvas || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      finish();
      return;
    }
    setMethodSwitching(true);
    exitTweenRef.current?.kill();
    exitTweenRef.current = gsap.to(canvas, {
      autoAlpha: 0,
      y: -8,
      duration: 0.16,
      ease: "power2.in",
      onComplete: () => {
        exitTweenRef.current = null;
        finish();
      },
    });
  }, [methodId, methodSwitching, onSelectedMethodIdChange, onSourceRangeSelect]);
  useEffect(() => {
    let cancelled = false;
    if (!cfg || cfg.nodes.length === 0) return;
    const layout = elkLayout.layout({
      id: "cfg",
      layoutOptions: {
        "elk.algorithm": "layered",
        "elk.direction": "DOWN",
        "elk.edgeRouting": "ORTHOGONAL",
        "elk.layered.spacing.nodeNodeBetweenLayers": "64",
        "elk.spacing.nodeNode": "36",
        "elk.layered.cycleBreaking.strategy": "GREEDY",
      },
      children: cfg.nodes.map((node) => ({ id: node.id, width: NODE_WIDTH, height: NODE_HEIGHT })),
      edges: cfg.edges.map((edge, index) => ({ id: `e${index}`, sources: [edge.source], targets: [edge.target] })),
    }).then((graph) => {
      if (cancelled) return;
      setPositions(new Map(graph.children?.map((node) => [node.id, { x: node.x ?? 0, y: node.y ?? 0 }]) ?? []));
    }).catch((cause: unknown) => {
      if (!cancelled) {
        console.error("ELK CFG layout failed; using Dagre fallback", cause);
        setPositions(layoutDagre(cfg));
      }
    });
    return () => {
      cancelled = true;
      void layout;
    };
  }, [cfg]);

  useEffect(() => {
    if (positions.size === 0 || !reactFlowRef.current) return;
    const frame = window.requestAnimationFrame(() => {
      void reactFlowRef.current?.fitView({ padding: 0.18, minZoom: 0.04, maxZoom: 1.6 });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [positions]);

  return (
    <section className="cfg-panel" aria-label="Граф потока управления">
      <header className="cfg-header">
        <div className="cfg-title"><span className="cfg-icon"><GitBranch size={17} /></span><div><span className="panel-kicker">ГРАФ ПОТОКА</span><h2>Граф метода</h2></div></div>
        <div className="cfg-controls">
          <label htmlFor="cfg-method">Метод</label>
          <select id="cfg-method" value={methodId} disabled={methodSwitching} onChange={(event) => changeMethod(event.target.value)}>
            <option value="" disabled>{methods.length ? "Выберите метод" : "Сначала запустите анализ"}</option>
            {methods.map((method) => <option key={method.id} value={method.id}>{method.id}</option>)}
          </select>
          {methodId ? <Button variant="outline" size="sm" onClick={() => void mutate()} aria-label="Обновить CFG"><RefreshCw size={14} /></Button> : null}
        </div>
      </header>

      <div className="cfg-content">
        {state === "loading" ? <div className="cfg-state" role="status"><LoaderCircle className="spin" /><strong>Загружаем CFG</strong><span>Получаем граф выбранного метода для ревизии {revision}.</span></div> : null}
        {state === "unsupported" ? <div className="cfg-state" role="status"><AlertCircle /><strong>CFG недоступен</strong><span>{errorMessage}</span></div> : null}
        {state === "error" ? <div className="cfg-state cfg-state--error" role="alert"><AlertCircle /><strong>Не удалось получить граф</strong><span>{errorMessage}</span><Button variant="outline" size="sm" onClick={() => void mutate()}><RefreshCw size={14} /> Повторить</Button></div> : null}
        {state === "empty" ? <div className="cfg-state" role="status"><GitBranch /><strong>{analysis ? "Выберите метод" : "Сначала разберите исходник"}</strong><span>{analysis ? "Для каждого метода строится отдельный граф." : "Запустите анализ, затем откройте граф метода."}</span></div> : null}
        {state === "ready" && cfg?.nodes.length === 0 ? <div className="cfg-state"><GitBranch /><strong>Граф пуст</strong><span>Для этого метода backend не вернул узлов CFG.</span></div> : null}
        {state === "ready" && cfg?.nodes.length ? (
          <div className="cfg-canvas" ref={canvasRef}>
            <p className="cfg-canvas-help"><GitBranch size={12} /> Нажмите на условие, чтобы подсветить ветвь «Да»; повторный клик переключит на «Нет».</p>
            <ReactFlow nodes={flow.nodes} edges={flow.edges} nodeTypes={nodeTypes} onNodeClick={onNodeClick} onEdgeClick={onEdgeClick} onInit={(instance) => { reactFlowRef.current = instance as ReactFlowInstance<FlowNode, Edge>; }} fitView fitViewOptions={{ padding: 0.18, minZoom: 0.04 }} minZoom={0.04} maxZoom={1.6} nodesDraggable={false} nodesConnectable={false} elementsSelectable>
              <Background color="#d7dbd3" gap={20} size={1} />
              <Controls showInteractive={false} />
              {cfg && cfg.nodes.length >= 24 ? <MiniMap pannable zoomable nodeStrokeWidth={3} nodeColor="#86a08f" nodeStrokeColor="#466450" maskColor="rgba(32, 49, 39, .14)" style={{ background: "#fffefa", border: "1px solid #d8d9cf", borderRadius: 6 }} /> : null}
            </ReactFlow>
          </div>
        ) : null}
      </div>

      {state === "ready" && cfg && selectedCondition && isCondition(selectedCondition.kind) ? (
        <div className="cfg-branch-controls" aria-label="Подсветка ветви условия">
          <span>Клик по узлу переключает ветви «Да» / «Нет»</span>
          <Button variant="outline" size="sm" aria-pressed={selectedBranchKind === "true"} onClick={() => selectBranch(cfg, selectedCondition.id, "true", setActiveBranch)}>Ветка «Да»</Button>
          <Button variant="outline" size="sm" aria-pressed={selectedBranchKind === "false"} onClick={() => selectBranch(cfg, selectedCondition.id, "false", setActiveBranch)}>Ветка «Нет»</Button>
          {activeBranch ? <Button variant="ghost" size="sm" onClick={() => setActiveBranch(null)}>Сбросить</Button> : null}
        </div>
      ) : null}

      {state === "ready" && selectedCondition && isCondition(selectedCondition.kind) ? (
        <div className="cfg-transform" aria-label="Редактирование условия">
          <div><strong>Условие узла</strong><span>Изменения будут доступны после подключения transform endpoint.</span></div>
          <input aria-label="Новое условие" value={conditionValue || selectedCondition.label} onChange={(event) => setConditionValue(event.target.value)} />
          <Button disabled>Применить</Button>
        </div>
      ) : null}
    </section>
  );
}

function validateCfg(cfg: Cfg, methodId: string) {
  if (!cfg || cfg.method_id !== methodId || !Array.isArray(cfg.nodes) || !Array.isArray(cfg.edges)) {
    throw new ApiError("API вернул CFG в неизвестном формате.", 502, "invalid_response");
  }
  const ids = new Set<string>();
  for (const node of cfg.nodes) {
    if (!node || typeof node.id !== "string" || ids.has(node.id) || typeof node.kind !== "string" || typeof node.label !== "string" || !Number.isSafeInteger(node.source_range?.start) || !Number.isSafeInteger(node.source_range?.length) || node.source_range.start < 0 || node.source_range.length < 0) {
      throw new ApiError("CFG содержит узел с некорректными данными.", 502, "invalid_response");
    }
    ids.add(node.id);
  }
  if (cfg.edges.some((edge) => !edge || typeof edge.source !== "string" || typeof edge.target !== "string" || !ids.has(edge.source) || !ids.has(edge.target) || typeof edge.kind !== "string")) {
    throw new ApiError("CFG содержит связь с отсутствующим узлом.", 502, "invalid_response");
  }
}

function matchingNode(cfg: Cfg, range: { start: number; length: number }) {
  const selectionEnd = range.start + Math.max(range.length, 1);
  return cfg.nodes
    .map((node) => ({
      node,
      start: node.source_range.start,
      end: node.source_range.start + Math.max(node.source_range.length, 1),
    }))
    .filter(({ start, end }) => start < selectionEnd && range.start < end)
    .sort((a, b) => (a.end - a.start) - (b.end - b.start))[0]?.node ?? null;
}

function mapComplexityToCfg(cfg: Cfg | null, hotspots: ComplexityHotspot[]) {
  const result = new Map<string, { cognitiveContribution: number; cyclomaticContribution: number }>();
  if (!cfg) return result;
  const nodesByKind = new Map<string, CfgNode[]>();
  const eligibleNodes = cfg.nodes.filter((node) => !["entry", "exit", "merge"].includes(node.kind));
  for (const node of eligibleNodes) {
    const nodes = nodesByKind.get(node.kind) ?? [];
    nodes.push(node);
    nodesByKind.set(node.kind, nodes);
  }
  eligibleNodes.sort((left, right) => left.source_range.start - right.source_range.start);
  for (const nodes of nodesByKind.values()) nodes.sort((left, right) => left.source_range.start - right.source_range.start);

  for (const hotspot of hotspots) {
    // The current CFG models else as a labeled edge, not a node; don't attribute its score to a nested block.
    if (hotspot.kind === "else") continue;
    const start = hotspot.source_range.start;
    const end = start + Math.max(hotspot.source_range.length, 1);
    const preferredKinds = COMPLEXITY_CFG_KINDS[hotspot.kind];
    let target: CfgNode | null = null;
    for (const kind of preferredKinds ?? []) {
      const candidate = nearestOverlappingNode(nodesByKind.get(kind) ?? [], start, end);
      if (candidate && (!target || nodeDistance(candidate, start) < nodeDistance(target, start))) target = candidate;
    }
    target ??= nearestOverlappingNode(eligibleNodes, start, end);
    if (!target) continue;
    const current = result.get(target.id) ?? { cognitiveContribution: 0, cyclomaticContribution: 0 };
    result.set(target.id, {
      cognitiveContribution: current.cognitiveContribution + hotspot.cognitive_increment,
      cyclomaticContribution: current.cyclomaticContribution + hotspot.cyclomatic_increment,
    });
  }
  return result;
}

function nearestOverlappingNode(nodes: CfgNode[], start: number, end: number) {
  let low = 0;
  let high = nodes.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (nodes[middle].source_range.start < start) low = middle + 1;
    else high = middle;
  }

  let left = low - 1;
  let right = low;
  let nearest: CfgNode | null = null;
  let nearestDistance = Number.POSITIVE_INFINITY;
  while (left >= 0 || right < nodes.length) {
    const leftDistance = left >= 0 ? start - nodes[left].source_range.start : Number.POSITIVE_INFINITY;
    const rightDistance = right < nodes.length ? nodes[right].source_range.start - start : Number.POSITIVE_INFINITY;
    const distance = Math.min(leftDistance, rightDistance);
    if (distance > nearestDistance) break;
    const node = leftDistance <= rightDistance ? nodes[left--] : nodes[right++];
    const nodeStart = node.source_range.start;
    const nodeEnd = nodeStart + Math.max(node.source_range.length, 1);
    if (start < nodeEnd && nodeStart < end && (distance < nearestDistance
        || node.source_range.length < (nearest?.source_range.length ?? Number.POSITIVE_INFINITY))) {
      nearest = node;
      nearestDistance = distance;
    }
  }
  return nearest;
}

function nodeDistance(node: CfgNode, start: number) {
  return Math.abs(node.source_range.start - start);
}

function branchFootprint(cfg: Cfg, branch: { source: string; target: string; kind: string }) {
  const highlighted = new Set<number>();
  const firstEdge = cfg.edges.findIndex((edge) => edge.source === branch.source && edge.target === branch.target && edge.kind === branch.kind);
  if (firstEdge < 0) return highlighted;
  highlighted.add(firstEdge);
  const nodes = new Map(cfg.nodes.map((node) => [node.id, node]));
  const visited = new Set([branch.source]);
  const queue = [branch.target];
  while (queue.length) {
    const current = queue.shift()!;
    if (visited.has(current)) continue;
    visited.add(current);
    const kind = nodes.get(current)?.kind;
    if (["merge", "exit", "return", "throw"].includes(kind ?? "")) continue;
    cfg.edges.forEach((edge, index) => {
      if (edge.source !== current) return;
      highlighted.add(index);
      queue.push(edge.target);
    });
  }
  return highlighted;
}

function layoutDagre(cfg: Cfg) {
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: "TB", nodesep: 38, ranksep: 64, marginx: 32, marginy: 32 });
  graph.setDefaultEdgeLabel(() => ({}));
  cfg.nodes.forEach((node) => graph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT }));
  cfg.edges.forEach((edge) => graph.setEdge(edge.source, edge.target));
  dagre.layout(graph);
  return new Map(cfg.nodes.map((node) => {
    const position = graph.node(node.id);
    return [node.id, { x: position.x - NODE_WIDTH / 2, y: position.y - NODE_HEIGHT / 2 }];
  }));
}

function findConditionBranch(cfg: Cfg, source: string, kind: "true" | "false") {
  return cfg.edges.find((edge) => edge.source === source
    && (edge.kind === kind || (kind === "true" && edge.kind === "back_true"))) ?? null;
}

function selectBranch(
  cfg: Cfg,
  source: string,
  kind: "true" | "false",
  setActiveBranch: (branch: { source: string; target: string; kind: string } | null) => void,
) {
  const edge = cfg.edges.find((candidate) => candidate.source === source
    && (candidate.kind === kind || (kind === "true" && candidate.kind === "back_true")));
  if (edge) setActiveBranch(edge);
}

function safeKind(kind: string) {
  return kind.toLowerCase().replace(/[_/]/g, "-").replace(/[^a-z0-9-]/g, "-");
}

function isCondition(kind: string) {
  return ["condition", "if", "while", "for", "do_while", "do/while"].includes(kind.toLowerCase());
}

function formatNodeKind(kind: string) {
  const translations: Record<string, string> = {
    entry: "ВХОД",
    exit: "ВЫХОД",
    condition: "УСЛОВИЕ",
    if: "УСЛОВИЕ",
    while: "ЦИКЛ",
    for: "ЦИКЛ",
    do_while: "ЦИКЛ",
    "do/while": "ЦИКЛ",
    statement: "ОПЕРАЦИЯ",
    operation: "ОПЕРАЦИЯ",
    method: "МЕТОД",
    return: "ВОЗВРАТ",
  };
  return translations[kind.toLowerCase()] ?? kind.toLocaleUpperCase("ru-RU");
}

function formatEdgeKind(kind: string) {
  if (kind === "next") return "";
  const translations: Record<string, string> = {
    true: "Да",
    false: "Нет",
    back: "Повтор",
    back_true: "Повтор · да",
    break: "Выход из цикла",
    continue: "Следующий проход",
    return: "Возврат",
    exception: "Исключение",
    finally: "finally",
    default: "По умолчанию",
    "no-match": "Совпадений нет",
    case: "Ветка",
  };
  return translations[kind.toLowerCase()] ?? kind;
}
