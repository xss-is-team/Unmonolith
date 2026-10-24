"use client";

import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { MouseEvent } from "react";
import useSWR from "swr";
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

type FlowData = CfgNode & {
  hasFinding: boolean;
  complexityFragment: string | null;
};
type FlowNode = Node<FlowData, "cfg">;

const CfgFlowNode = memo(function CfgFlowNode({ data, selected }: NodeProps<FlowNode>) {
  const condition = data.kind === "branch";
  const label = data.text || data.statement_kind || data.kind;
  return (
    <div
      className={[
        "relative flex h-[92px] w-[236px] flex-col items-center justify-center gap-1.5 rounded-lg border bg-slate-900 px-3.5 py-3 text-center text-slate-300 shadow-lg",
        data.kind === "branch" ? "border-amber-700 bg-[#251e15]" : ["entry", "exit", "return"].includes(data.kind) ? "border-emerald-800 bg-[#10231b]" : "border-slate-600",
        data.reachable === false ? "opacity-45" : "",
        data.hasFinding ? "ring-2 ring-rose-400/30" : "",
        selected ? "border-cyan-400 ring-2 ring-cyan-400/20" : "",
      ].filter(Boolean).join(" ")}
      title={condition ? "Нажмите, чтобы выбрать условие и подсветить ветвь." : undefined}
    >
      <Handle type="target" position={Position.Top} />
      <span className="font-mono text-[8px] font-semibold tracking-[.06em] text-slate-500">{formatNodeKind(data.kind, data.statement_kind)}</span>
      <strong className="max-w-full overflow-hidden text-ellipsis font-mono text-[10px] font-medium leading-5 text-slate-200">{label}</strong>
      {data.complexityFragment ? <small className="absolute -top-2 right-2 rounded border border-rose-900 bg-rose-950 px-1.5 py-1 font-mono text-[8px] font-semibold text-rose-300">{data.complexityFragment}</small> : null}
      {condition ? <small className="text-[7px] text-amber-300/70">Условие можно изменить ниже</small> : null}
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
});

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
  showAnalysisSummary = true,
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
    void import("elkjs/lib/elk.bundled.js").then(({ default: ELK }) => new ELK().layout({
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
    })).then((graph) => {
      if (!cancelled) {
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
    <section className="flex size-full min-h-0 min-w-0 flex-col overflow-hidden bg-[#080f1b]" aria-label="Граф потока управления">
      <header className="flex min-h-[49px] items-center justify-between gap-2.5 border-b border-slate-800 bg-[#0d1625] px-2.5 py-2">
        <div className="flex items-center gap-2">
          <span className="grid size-7 place-items-center rounded border border-slate-700 bg-violet-400/5 text-violet-400"><GitBranch size={17} /></span>
          <div><span className="block font-mono text-[8px] font-semibold tracking-[.07em] text-slate-500">CFG / UTF-16 SOURCE MAP</span><h2 className="mt-1 mb-0 text-[11px] text-slate-300">Поток выполнения</h2></div>
        </div>
        <div className="flex min-w-0 items-center gap-2">
          <label className="text-[8px] text-slate-500" htmlFor="cfg-method">Метод</label>
          <select
            className="h-7 min-w-44 rounded border border-slate-700 bg-[#0c1422] px-2 font-mono text-[9px] text-slate-300 outline-none"
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

      <div className="min-h-0 flex-1">
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
          <div className="relative size-full bg-[#080f1b] [background-image:linear-gradient(rgba(51,65,85,.18)_1px,transparent_1px),linear-gradient(90deg,rgba(51,65,85,.18)_1px,transparent_1px)] [background-size:24px_24px]">
            <p className="absolute top-2 left-2 z-10 m-0 flex items-center gap-1.5 rounded border border-slate-700 bg-slate-950/90 px-2 py-1.5 text-[8px] text-slate-500"><GitBranch size={12} /> Узлы связаны с UTF-16 диапазонами редактора. Back edge показан пунктиром.</p>
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

      {showAnalysisSummary && methodAnalysis ? <BigOPanel analysis={methodAnalysis} onSelect={onSourceRangeSelect} /> : showAnalysisSummary && analysisRequest.error ? (
        <div className="flex min-h-[30px] items-center gap-1.5 border-t border-slate-800 bg-[#0d1625] px-2.5 text-[8px] text-slate-400"><AlertCircle size={15} /> Big O для метода недоступна: {apiMessage(analysisRequest.error)}</div>
      ) : showAnalysisSummary && selector ? (
        <div className="flex min-h-[30px] items-center gap-1.5 border-t border-slate-800 bg-[#0d1625] px-2.5 text-[8px] text-slate-400"><LoaderCircle className="spin" size={15} /> Загружаем Big O…</div>
      ) : null}

      {selectedNode?.kind === "branch" && method ? (
        <div className="grid grid-cols-[minmax(210px,.8fr)_minmax(200px,1fr)_auto] items-center gap-2 border-t border-slate-800 bg-[#0d1625] px-2.5 py-2" aria-label="Изменение условия через ASTRewrite">
          <div className="min-w-0">
            <strong className="flex items-center gap-1 text-[9px] text-violet-200"><WandSparkles size={15} /> ASTRewrite: заменить условие</strong>
            <span className="mt-1 block overflow-hidden text-ellipsis whitespace-nowrap text-[8px] text-slate-500">{method.id} · {selectedNode.id}. Будет создана новая ревизия и выполнен повторный разбор.</span>
          </div>
          <input
            className="h-[29px] w-full rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[10px] text-slate-200 outline-none focus:border-cyan-500"
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
          {transformState === "error" ? <p className="col-start-2 col-end-[-1] m-0 text-[8px] text-rose-300" role="alert">{transformError}</p> : null}
          {transformState === "done" ? <p className="col-start-2 col-end-[-1] m-0 flex items-center gap-1 text-[8px] text-emerald-300"><Check size={14} /> Новая ревизия создана.</p> : null}
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
    <section className="grid grid-cols-[150px_1fr] gap-2 border-t border-slate-800 bg-slate-950 p-2.5" aria-label="Оценка сложности метода">
      <div className="grid gap-1 rounded border border-slate-700 bg-violet-400/5 p-2">
        <span><Sparkles size={15} /> BIG O</span>
        <strong>{analysis.complexity.time}</strong>
        <small>уверенность: {confidenceLabel(analysis.complexity.confidence)}</small>
      </div>
      <p>{analysis.complexity.reason}</p>
      {analysis.complexity.assumptions.length ? (
        <ul>{analysis.complexity.assumptions.map((item) => <li key={item}>{item}</li>)}</ul>
      ) : null}
      {analysis.findings.length ? (
        <div className="grid gap-1">
          {analysis.findings.map((finding) => (
            <button className="grid gap-1 rounded border border-slate-700 bg-slate-900 p-2 text-left text-[8px] text-slate-400" type="button" key={finding.id} onClick={() => onSelect(finding.source_range)}>
              <strong>{finding.message}</strong>
              <span>{finding.explanation}</span>
              <small>Рекомендация: {finding.recommendation}</small>
            </button>
          ))}
        </div>
      ) : <small className="text-emerald-300">Рекомендаций для выбранного метода нет.</small>}
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

function layoutGrid(nodes: CfgNode[]) {
  const columns = Math.max(1, Math.ceil(Math.sqrt(nodes.length)));
  return new Map(nodes.map((node, index) => [
    node.id,
    { x: (index % columns) * (NODE_WIDTH + 38), y: Math.floor(index / columns) * (NODE_HEIGHT + 66) },
  ]));
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
