"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { MouseEvent } from "react";
import useSWR from "swr";
import ELK from "elkjs/lib/elk.bundled.js";
import dagre from "@dagrejs/dagre";
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
import {
  AlertCircle,
  Check,
  GitBranch,
  LoaderCircle,
  RefreshCw,
  Sparkles,
  WandSparkles,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  ApiError,
  getMethodAnalysis,
  getMethodCfg,
  replaceCondition,
  type Cfg,
  type CfgEdge,
  type CfgMethod,
  type CfgNode,
  type MethodAnalysis,
  type SourceRange,
} from "@/lib/api";

const NODE_WIDTH = 236;
const NODE_HEIGHT = 92;
const elk = new ELK();

type FlowData = CfgNode & {
  hasFinding: boolean;
  complexityFragment: string | null;
};
type FlowNode = Node<FlowData, "cfg">;

function CfgFlowNode({ data }: NodeProps<FlowNode>) {
  const condition = data.kind === "branch";
  const label = data.text || data.statement_kind || data.kind;
  return (
    <div
      className={[
        "cfg-node",
        `cfg-node--${safeKind(data.kind)}`,
        data.reachable === false ? "cfg-node--unreachable" : "",
        data.hasFinding ? "cfg-node--complexity-high" : "",
      ].filter(Boolean).join(" ")}
      title={condition ? "Нажмите, чтобы выбрать условие и подсветить ветвь." : undefined}
    >
      <Handle type="target" position={Position.Top} />
      <span>{formatNodeKind(data.kind, data.statement_kind)}</span>
      <strong>{label}</strong>
      {data.complexityFragment ? <small className="cfg-complexity-mark">{data.complexityFragment}</small> : null}
      {condition ? <small className="cfg-node__click-hint">Условие можно изменить ниже</small> : null}
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

const nodeTypes = { cfg: CfgFlowNode };

export function CfgViewer({
  projectId,
  revision,
  methods,
  selectedSourceRange,
  selectedMethodSelector,
  onSelectedMethodSelectorChange,
  onSourceRangeSelect,
  onTransformed,
}: {
  projectId: string;
  revision: number;
  methods: CfgMethod[];
  selectedSourceRange: SourceRange | null;
  selectedMethodSelector: string;
  onSelectedMethodSelectorChange: (selector: string) => void;
  onSourceRangeSelect: (range: SourceRange | null, keepGraphVisible?: boolean) => void;
  onTransformed: () => Promise<void>;
}) {
  const selector = methods.some((method) => method.selector === selectedMethodSelector)
    ? selectedMethodSelector
    : methods[0]?.selector ?? "";
  const method = methods.find((item) => item.selector === selector);
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }>>(new Map());
  const [activeBranch, setActiveBranch] = useState<CfgEdge | null>(null);
  const [conditionDraft, setConditionDraft] = useState<{ nodeId: string; value: string } | null>(null);
  const [transformState, setTransformState] = useState<"idle" | "saving" | "done" | "error">("idle");
  const [transformError, setTransformError] = useState("");
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
    void elk.layout({
      id: "cfg",
      layoutOptions: {
        "elk.algorithm": "layered",
        "elk.direction": "DOWN",
        "elk.edgeRouting": "ORTHOGONAL",
        "elk.layered.spacing.nodeNodeBetweenLayers": "66",
        "elk.spacing.nodeNode": "38",
        "elk.layered.cycleBreaking.strategy": "GREEDY",
      },
      children: cfg.nodes.map((node) => ({
        id: node.id,
        width: NODE_WIDTH,
        height: NODE_HEIGHT,
      })),
      edges: cfg.edges.map((edge, index) => ({
        id: `e${index}`,
        sources: [edge.from],
        targets: [edge.to],
      })),
    }).then((graph) => {
      if (!cancelled) {
        setPositions(new Map(
          graph.children?.map((node) => [
            node.id,
            { x: node.x ?? 0, y: node.y ?? 0 },
          ]) ?? [],
        ));
      }
    }).catch(() => {
      if (!cancelled) setPositions(layoutDagre(cfg));
    });
    return () => {
      cancelled = true;
    };
  }, [cfg]);

  useEffect(() => {
    if (!positions.size || !flowRef.current) return;
    const frame = window.requestAnimationFrame(() => {
      void flowRef.current?.fitView({ padding: 0.2, minZoom: 0.04, maxZoom: 1.4 });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [positions]);

  const activeEdges = useMemo(
    () => cfg && activeBranch ? branchFootprint(cfg, activeBranch) : new Set<number>(),
    [cfg, activeBranch],
  );
  const highlighted = useMemo(() => analysisHighlights(methodAnalysis), [methodAnalysis]);
  const flow = useMemo(() => ({
    nodes: cfg?.nodes.map((node) => ({
      id: node.id,
      type: "cfg" as const,
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
      position: positions.get(node.id) ?? { x: 0, y: 0 },
      data: {
        ...node,
        hasFinding: highlighted.findings.has(node.id),
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
        type: "smoothstep" as const,
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
  }), [activeEdges, cfg, highlighted, positions, selectedNode?.id]);

  const onNodeClick = useCallback((_event: MouseEvent, node: FlowNode) => {
    onSourceRangeSelect(node.data.source_range, node.data.kind === "branch");
    if (!cfg || node.data.kind !== "branch") {
      setActiveBranch(null);
      return;
    }
    setActiveBranch((current) => {
      const nextKind = current?.from === node.id && current.kind === "true" ? "false" : "true";
      return findBranch(cfg, node.id, nextKind) ?? findBranch(cfg, node.id, "true");
    });
  }, [cfg, onSourceRangeSelect]);

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
    <section className="cfg-panel" aria-label="Граф потока управления">
      <header className="cfg-header">
        <div className="cfg-title">
          <span className="cfg-icon"><GitBranch size={17} /></span>
          <div><span className="panel-kicker">CFG / UTF-16 SOURCE MAP</span><h2>Поток выполнения</h2></div>
        </div>
        <div className="cfg-controls">
          <label htmlFor="cfg-method">Метод</label>
          <select
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

      <div className="cfg-content">
        {cfgState === "loading" ? (
          <div className="cfg-state" role="status"><LoaderCircle className="spin" /><strong>Загружаем CFG</strong><span>Селектор {selector} · ревизия {revision}</span></div>
        ) : null}
        {cfgState === "error" ? (
          <div className="cfg-state cfg-state--error" role="alert"><AlertCircle /><strong>CFG недоступен</strong><span>{apiMessage(cfgRequest.error)}</span><Button variant="outline" size="sm" onClick={() => void cfgRequest.mutate()}><RefreshCw size={14} /> Повторить</Button></div>
        ) : null}
        {cfgState === "empty" ? (
          <div className="cfg-state"><GitBranch /><strong>Нет методов с CFG</strong><span>Завершите анализ сохранённой ревизии.</span></div>
        ) : null}
        {cfgState === "ready" && cfg && !cfg.nodes.length ? (
          <div className="cfg-state"><GitBranch /><strong>Граф пуст</strong></div>
        ) : null}
        {cfgState === "ready" && cfg?.nodes.length ? (
          <div className="cfg-canvas">
            <p className="cfg-canvas-help"><GitBranch size={12} /> Узлы связаны с UTF-16 диапазонами редактора. Back edge показан пунктиром.</p>
            <ReactFlow
              nodes={flow.nodes}
              edges={flow.edges}
              nodeTypes={nodeTypes}
              onNodeClick={onNodeClick}
              onInit={(instance) => { flowRef.current = instance as ReactFlowInstance<FlowNode, Edge>; }}
              fitView
              minZoom={0.04}
              maxZoom={1.6}
              nodesDraggable={false}
              nodesConnectable={false}
            >
              <Background color="#d7dbd3" gap={20} size={1} />
              <Controls showInteractive={false} />
              {cfg.nodes.length >= 24 ? <MiniMap pannable zoomable nodeColor="#86a08f" nodeStrokeColor="#466450" /> : null}
            </ReactFlow>
          </div>
        ) : null}
      </div>

      {methodAnalysis ? <BigOPanel analysis={methodAnalysis} onSelect={onSourceRangeSelect} /> : analysisRequest.error ? (
        <div className="cfg-analysis-error"><AlertCircle size={15} /> Big O для метода недоступна: {apiMessage(analysisRequest.error)}</div>
      ) : selector ? (
        <div className="cfg-analysis-error"><LoaderCircle className="spin" size={15} /> Загружаем Big O…</div>
      ) : null}

      {selectedNode?.kind === "branch" && method ? (
        <div className="cfg-transform" aria-label="Изменение условия через ASTRewrite">
          <div>
            <strong><WandSparkles size={15} /> ASTRewrite: заменить условие</strong>
            <span>{method.id} · {selectedNode.id}. Будет создана новая ревизия и выполнен повторный разбор.</span>
          </div>
          <input
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
          {transformState === "error" ? <p role="alert">{transformError}</p> : null}
          {transformState === "done" ? <p><Check size={14} /> Новая ревизия создана.</p> : null}
        </div>
      ) : null}
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
  return (
    <section className="big-o-panel" aria-label="Оценка сложности метода">
      <div className="big-o-summary">
        <span><Sparkles size={15} /> BIG O</span>
        <strong>{analysis.complexity.time}</strong>
        <small>уверенность: {confidenceLabel(analysis.complexity.confidence)}</small>
      </div>
      <p>{analysis.complexity.reason}</p>
      {analysis.complexity.assumptions.length ? (
        <ul>{analysis.complexity.assumptions.map((item) => <li key={item}>{item}</li>)}</ul>
      ) : null}
      {analysis.findings.length ? (
        <div className="big-o-findings">
          {analysis.findings.map((finding) => (
            <button type="button" key={finding.id} onClick={() => onSelect(finding.source_range)}>
              <strong>{finding.message}</strong>
              <span>{finding.explanation}</span>
              <small>Рекомендация: {finding.recommendation}</small>
            </button>
          ))}
        </div>
      ) : <small className="big-o-clean">Рекомендаций для выбранного метода нет.</small>}
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

function layoutDagre(cfg: Cfg) {
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: "TB", nodesep: 38, ranksep: 66, marginx: 32, marginy: 32 });
  graph.setDefaultEdgeLabel(() => ({}));
  cfg.nodes.forEach((node) => graph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT }));
  cfg.edges.forEach((edge) => graph.setEdge(edge.from, edge.to));
  dagre.layout(graph);
  return new Map(cfg.nodes.map((node) => {
    const point = graph.node(node.id);
    return [node.id, { x: point.x - NODE_WIDTH / 2, y: point.y - NODE_HEIGHT / 2 }];
  }));
}

function formatNodeKind(kind: string, statementKind?: string) {
  if (kind === "branch") return statementKind ? `УСЛОВИЕ · ${statementKind.toLocaleUpperCase("ru-RU")}` : "УСЛОВИЕ";
  const labels: Record<string, string> = {
    entry: "ВХОД",
    exit: "ВЫХОД",
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

function safeKind(kind: string) {
  return kind.toLowerCase().replace(/[_/]/g, "-").replace(/[^a-z0-9-]/g, "-");
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
