"use client";

import { useEffect, useMemo, useState } from "react";
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
} from "@xyflow/react";
import {
  AlertCircle,
  ArrowUpRight,
  Boxes,
  Braces,
  CircleHelp,
  FileCode2,
  LoaderCircle,
  Search,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  ApiError,
  getProjectGraph,
  type CfgMethod,
  type ProjectGraph,
  type ProjectGraphResponse,
  type SourceRange,
} from "@/lib/api";
import { toProjectOverviewModel, type ProjectOverviewType } from "@/lib/project-overview-model";

const NODE_WIDTH = 224;
const NODE_HEIGHT = 90;
const MAX_VISIBLE_TYPES = 250;
const elk = new ELK();
const EDGE_LABELS: Record<string, string> = {
  calls: "Вызовы",
  constructs: "Создание объектов",
  depends_on: "Зависимости",
  extends: "Наследование",
  implements: "Реализация",
};

type TypeNodeData = {
  name: string;
  qualifiedName: string;
  kind: string;
  methodCount: number;
  selected: boolean;
  selectType: () => void;
};
type FlowNode = Node<TypeNodeData, "project-type">;
type ProjectEdge = Edge & { data: { kind: string; count: number } };

function ProjectTypeNode({ data }: NodeProps<FlowNode>) {
  const TypeIcon = data.kind === "interface" ? Braces : Boxes;
  return (
    <div className={`project-type-node${data.selected ? " project-type-node--selected" : ""}`}>
      <Handle type="target" position={Position.Left} />
      <button
        className="project-type-node__action nodrag nopan"
        type="button"
        aria-label={`Выбрать ${data.kind === "interface" ? "интерфейс" : "класс"} ${data.qualifiedName}; методов: ${data.methodCount}`}
        onClick={(event) => {
          event.stopPropagation();
          data.selectType();
        }}
      >
        <span className="project-type-node__top">
          <span className="project-type-node__icon"><TypeIcon size={14} /></span>
          <small>{data.kind.replace("_", " ")}</small>
          <span className="project-type-node__count">{data.methodCount} методов</span>
        </span>
        <strong title={data.qualifiedName}>{data.name}</strong>
        <span className="project-type-node__path">{data.qualifiedName}</span>
      </button>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

const nodeTypes = { "project-type": ProjectTypeNode };

export function ProjectGraphViewer({
  projectId,
  revision,
  methods,
  onOpenMethod,
  onSourceRangeSelect,
  graphOverride,
  sourceLabel,
}: {
  projectId: string;
  revision: number;
  methods: CfgMethod[];
  onOpenMethod: (selector: string) => void;
  onSourceRangeSelect: (range: SourceRange) => void;
  graphOverride?: ProjectGraph;
  sourceLabel?: string;
}) {
  const graphRequest = useSWR<ProjectGraphResponse, unknown>(
    graphOverride ? null : `project-graph:${projectId}:${revision}`,
    async () => {
      const response = await getProjectGraph(projectId);
      if (response.revision !== revision) {
        throw new ApiError("Граф относится к другой ревизии исходника.", 409, "revision_conflict");
      }
      return response;
    },
    { shouldRetryOnError: false, revalidateOnFocus: false },
  );
  const [query, setQuery] = useState("");
  const [edgeKind, setEdgeKind] = useState("structure");
  const [selectedTypeId, setSelectedTypeId] = useState<string | null>(null);
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }>>(new Map());

  const graph = graphOverride ?? graphRequest.data?.graph ?? null;
  const overview = useMemo(() => graph ? toProjectOverviewModel(graph) : null, [graph]);
  const isLocalFixture = Boolean(sourceLabel);
  const typeNodes = useMemo(
    () => overview?.types ?? [],
    [overview],
  );
  const matchingTypes = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase("ru-RU");
    return normalized
      ? typeNodes.filter((node) => `${node.name} ${node.qualifiedName}`.toLocaleLowerCase("ru-RU").includes(normalized))
      : typeNodes;
  }, [query, typeNodes]);
  const filteredTypes = useMemo(() => matchingTypes.slice(0, MAX_VISIBLE_TYPES), [matchingTypes]);
  const visibleTypeKey = useMemo(() => filteredTypes.map((node) => node.id).join("|"), [filteredTypes]);
  const visibleTypeIds = useMemo(() => new Set(filteredTypes.map((node) => node.id)), [filteredTypes]);
  const projectedEdges = useMemo(
    () => overview ? overview.relationships.filter((edge) => visibleTypeIds.has(edge.from) && visibleTypeIds.has(edge.to)).filter((edge) => (
      edgeKind === "all"
      || (edgeKind === "structure" && (edge.kind === "extends" || edge.kind === "implements"))
      || edge.kind === edgeKind
    )) : [],
    [edgeKind, overview, visibleTypeIds],
  );
  const selectedType = filteredTypes.find((node) => node.id === selectedTypeId) ?? filteredTypes[0] ?? null;
  const selectedMethods = selectedType?.methods ?? [];

  useEffect(() => {
    let cancelled = false;
    if (!filteredTypes.length) return;
    void elk.layout({
      id: "project-overview",
      layoutOptions: {
        "elk.algorithm": "layered",
        "elk.direction": "RIGHT",
        "elk.edgeRouting": "ORTHOGONAL",
        "elk.layered.spacing.nodeNodeBetweenLayers": "96",
        "elk.spacing.nodeNode": "44",
        "elk.layered.cycleBreaking.strategy": "GREEDY",
      },
      children: filteredTypes.map((node) => ({ id: node.id, width: NODE_WIDTH, height: NODE_HEIGHT })),
      edges: projectedEdges.map((edge, index) => ({
        id: `project-edge-${index}`,
        sources: [edge.from],
        targets: [edge.to],
      })),
    }).then((layout) => {
      if (!cancelled) {
        setPositions(new Map(layout.children?.map((node) => [node.id, { x: node.x ?? 0, y: node.y ?? 0 }]) ?? []));
      }
    }).catch(() => {
      if (!cancelled) setPositions(layoutFallback(filteredTypes, projectedEdges));
    });
    return () => { cancelled = true; };
  }, [filteredTypes, projectedEdges]);

  const layoutKey = useMemo(
    () => `${visibleTypeKey}:${[...positions].map(([id, point]) => `${id}=${point.x},${point.y}`).join(";")}`,
    [positions, visibleTypeKey],
  );

  const flowNodes = useMemo<FlowNode[]>(() => filteredTypes.map((node) => ({
    id: node.id,
    type: "project-type",
    width: NODE_WIDTH,
    height: NODE_HEIGHT,
    position: positions.get(node.id) ?? { x: 0, y: 0 },
    data: {
      name: node.name,
      qualifiedName: node.qualifiedName,
      kind: node.kind,
      methodCount: node.methods.length,
      selected: node.id === selectedType?.id,
      selectType: () => setSelectedTypeId(node.id),
    },
    selected: node.id === selectedType?.id,
    deletable: false,
  })), [filteredTypes, positions, selectedType?.id]);

  const flowEdges = useMemo<ProjectEdge[]>(() => projectedEdges.map((edge, index) => ({
    id: `${edge.from}-${edge.to}-${edge.kind}-${index}`,
    source: edge.from,
    target: edge.to,
    type: "smoothstep",
    label: `${EDGE_LABELS[edge.kind] ?? edge.kind}${edge.count > 1 ? ` · ${edge.count}` : ""}`,
    markerEnd: { type: MarkerType.ArrowClosed, color: edgeColor(edge.kind) },
    style: { stroke: edgeColor(edge.kind), strokeWidth: edge.kind === "depends_on" ? 1.3 : 1.8 },
    labelStyle: { fill: "#56665b", fontSize: 9, fontWeight: 600 },
    labelBgStyle: { fill: "#fbfaf6", fillOpacity: 0.96 },
    labelBgPadding: [4, 2] as [number, number],
    data: { kind: edge.kind, count: edge.count },
  })), [projectedEdges]);

  function selectNode(_event: MouseEvent, node: FlowNode) {
    setSelectedTypeId(node.id);
  }

  if (!graph || !overview) {
    const error = graphRequest.error;
    const message = error instanceof ApiError && error.status === 404
      ? "API пока не публикует граф проекта."
      : error instanceof Error ? error.message : "Граф проекта недоступен.";
    return (
      <section className="project-graph-panel" aria-label="Обзор проекта">
        <div className="project-graph-state">
          {graphRequest.isLoading ? <LoaderCircle className="spin" /> : <AlertCircle />}
          <strong>{graphRequest.isLoading ? "Загружаем граф проекта" : "Обзор проекта пока недоступен"}</strong>
          <p>{graphRequest.isLoading ? "Запрашиваем структуру текущей ревизии." : message}</p>
        </div>
      </section>
    );
  }

  const unresolved = overview.unresolvedCalls;
  const externals = overview.types.flatMap((type) => type.externalSupertypes.map((external) => ({ owner: type, ...external })));
  const truncated = matchingTypes.length > filteredTypes.length;

  return (
    <section className="project-graph-panel" aria-label="Обзор структуры проекта">
      <div className="project-graph-toolbar">
        <label className="project-graph-search"><Search size={15} /><input name="projectGraphSearch" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Найти класс или интерфейс" aria-label="Найти тип" /></label>
        <label className="project-graph-filter"><span>Связи</span><select name="projectGraphEdgeKind" value={edgeKind} onChange={(event) => setEdgeKind(event.target.value)} aria-label="Фильтр типов связей">
          <option value="structure">Структурные</option>
          <option value="all">Все типы</option>
          {Object.entries(EDGE_LABELS).map(([kind, label]) => <option key={kind} value={kind}>{label}</option>)}
        </select></label>
        <span className={`project-graph-source${isLocalFixture ? " project-graph-source--fixture" : ""}`}>{sourceLabel ?? `РЕВИЗИЯ ${revision}`}</span>
      </div>

      {isLocalFixture ? <div className="project-graph-fixture-note"><CircleHelp size={14} /> Тестовые данные из `project-graph.json`; они не относятся к открытому исходнику.</div> : null}

      <div className="project-graph-summary">
        <span><strong>{typeNodes.length}</strong> типов</span>
        <span><strong>{overview.types.reduce((total, type) => total + type.methods.length, 0)}</strong> методов</span>
        <span><strong>{overview.stats.resolved_internal_calls}</strong> внутренних вызовов</span>
        <span><strong>{overview.externalCallCount}</strong> внешних вызовов</span>
        <span><strong>{unresolved.length}</strong> неразрешённых</span>
      </div>

      <div className="project-graph-canvas">
        {filteredTypes.length ? (
          <ReactFlow
            key={layoutKey}
            nodes={flowNodes}
            edges={flowEdges}
            nodeTypes={nodeTypes}
            fitView
            fitViewOptions={{ padding: 0.12, minZoom: 0.05, maxZoom: 1.2 }}
            onNodeClick={selectNode}
            nodesDraggable={false}
            minZoom={0.05}
            maxZoom={1.5}
            nodesConnectable={false}
            elementsSelectable
            nodesFocusable={false}
            edgesFocusable={false}
            disableKeyboardA11y
            panOnScroll
            zoomOnDoubleClick={false}
            aria-label="Граф типов проекта"
          >
            <Background color="#d0d6cd" gap={24} size={1} />
            <Controls showInteractive={false} />
            <MiniMap pannable zoomable nodeColor={(node) => node.id === selectedType?.id ? "#d7ee72" : "#71917a"} />
          </ReactFlow>
        ) : <div className="project-graph-empty"><Search size={19} /><strong>Типы не найдены</strong><span>Измените запрос или снимите фильтр.</span></div>}
      </div>

      {truncated ? <p className="project-graph-limit">Показаны первые {MAX_VISIBLE_TYPES} типов из {typeNodes.length}. Уточните поиск, чтобы сузить карту.</p> : null}

      {selectedType ? (
        <div className="project-graph-inspector">
          <div className="project-graph-inspector__head">
            <div><small>{selectedType.kind.replace("_", " ")} · {selectedType.sourceRange.line ? `строка ${selectedType.sourceRange.line}` : "без диапазона"}</small><h3>{selectedType.qualifiedName}</h3></div>
            {!isLocalFixture && selectedType.sourceRange ? <Button variant="outline" onClick={() => onSourceRangeSelect(selectedType.sourceRange)}><FileCode2 size={14} /> Исходник</Button> : null}
          </div>
          <div className="project-graph-methods" aria-label={`Методы ${selectedType.name}`}>
            {selectedMethods.length ? selectedMethods.map((method) => {
              const methodId = method.methodId ?? "";
              const matches = methods.filter((item) => item.id === methodId);
              const target = matches.find((item) => sameSourceRange(item.source_range, method.sourceRange))
                ?? (matches.length === 1 ? matches[0] : undefined);
              return (
                <div className="project-graph-method" key={method.id}>
                  <span><Braces size={13} /><strong>{method.name}</strong><small>{method.kind === "constructor" ? "конструктор" : method.sourceRange.line ? `стр. ${method.sourceRange.line}` : "метод"}</small></span>
                  <div>
                    {!isLocalFixture && method.sourceRange ? <button type="button" title="Открыть место в исходнике" onClick={() => onSourceRangeSelect(method.sourceRange)}><FileCode2 size={14} /></button> : null}
                    {!isLocalFixture && target ? <button type="button" title="Открыть CFG и Big O" onClick={() => onOpenMethod(target.selector)}><ArrowUpRight size={14} /></button> : null}
                    {!isLocalFixture && !target ? <small className="project-graph-no-cfg">CFG недоступен</small> : null}
                  </div>
                </div>
              );
            }) : <p className="project-graph-no-methods">Методы не найдены.</p>}
            {isLocalFixture ? <p className="project-graph-fixture-footnote">Переходы к исходнику и CFG отключены для локального примера.</p> : null}
          </div>
        </div>
      ) : null}

      <div className="project-graph-details">
        <details>
          <summary>Внешние типы и вызовы <span>{externals.length + overview.externalCallCount}</span></summary>
          <div className="project-graph-detail-list">
            {externals.map((item, index) => <div key={`${item.owner.id}-${item.name}-${index}`}><span>{item.owner.name} <i>→</i> {item.name}</span><small>{item.relation}</small></div>)}
            <div><span>Вызовы библиотек и JDK</span><small>{overview.externalCallCount}</small></div>
            {!externals.length && !overview.externalCallCount ? <p>Внешних зависимостей не найдено.</p> : null}
          </div>
        </details>
        <details>
          <summary>Неразрешённые вызовы <span>{unresolved.length}</span></summary>
          <div className="project-graph-detail-list">
            {unresolved.slice(0, 40).map((call) => <div key={call.id}><span title={call.expression}>{call.expression}</span><small>{call.reason}{call.sourceRange.line ? ` · стр. ${call.sourceRange.line}` : ""}</small></div>)}
            {unresolved.length > 40 ? <p>Показаны первые 40 из {unresolved.length} вызовов.</p> : null}
            {!unresolved.length ? <p>Неразрешённых вызовов нет.</p> : null}
          </div>
        </details>
      </div>
    </section>
  );
}

function sameSourceRange(left: SourceRange, right: SourceRange) {
  return left.start === right.start
    && left.length === right.length
    && left.file === right.file;
}

function edgeColor(kind: string) {
  switch (kind) {
    case "extends": return "#9e6744";
    case "implements": return "#567e5b";
    case "calls": return "#527e89";
    case "constructs": return "#9470a1";
    default: return "#879187";
  }
}

function layoutFallback(nodes: ProjectOverviewType[], edges: { from: string; to: string }[]) {
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: "LR", nodesep: 54, ranksep: 88, marginx: 24, marginy: 24 });
  graph.setDefaultEdgeLabel(() => ({}));
  for (const node of nodes) graph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  for (const edge of edges) graph.setEdge(edge.from, edge.to);
  dagre.layout(graph);
  return new Map(nodes.map((node) => {
    const point = graph.node(node.id);
    return [node.id, { x: point.x - NODE_WIDTH / 2, y: point.y - NODE_HEIGHT / 2 }];
  }));
}
