"use client";

import { memo, useCallback, useEffect, useMemo, useState } from "react";
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
} from "@xyflow/react";
import {
  AlertCircle,
  ArrowUpRight,
  Boxes,
  Braces,
  ChevronRight,
  FileCode2,
  FolderOpen,
  LoaderCircle,
  Search,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  ApiError,
  getProjectGraph,
  type CfgMethod,
  type ProjectGraphResponse,
  type SourceRange,
} from "@/lib/api";
import { toProjectOverviewModel, type ProjectOverviewType } from "@/lib/project-overview-model";

const NODE_WIDTH = 224;
const NODE_HEIGHT = 90;
const MAX_VISIBLE_TYPES = 250;
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

const ProjectTypeNode = memo(function ProjectTypeNode({ data }: NodeProps<FlowNode>) {
  const TypeIcon = data.kind === "interface" ? Braces : Boxes;
  return (
    <div className={`h-[90px] w-56 rounded-lg border bg-slate-900 shadow-lg ${data.selected ? "border-cyan-400 ring-2 ring-cyan-400/20" : "border-slate-600"}`}>
      <Handle type="target" position={Position.Left} />
      <button
        className="nodrag nopan flex size-full flex-col gap-1.5 border-0 bg-transparent p-2.5 text-left text-slate-300"
        type="button"
        aria-label={`Выбрать ${data.kind === "interface" ? "интерфейс" : "класс"} ${data.qualifiedName}; методов: ${data.methodCount}`}
        onClick={(event) => {
          event.stopPropagation();
          data.selectType();
        }}
      >
        <span className="flex w-full items-center gap-1.5 text-[8px] text-slate-500">
          <span className="grid size-[21px] place-items-center rounded bg-violet-400/10 text-violet-400"><TypeIcon size={14} /></span>
          <small>{data.kind.replace("_", " ")}</small>
          <span className="ml-auto">{data.methodCount} методов</span>
        </span>
        <strong className="text-[11px] text-slate-200" title={data.qualifiedName}>{data.name}</strong>
        <span className="overflow-hidden text-ellipsis whitespace-nowrap font-mono text-[8px] text-slate-500">{data.qualifiedName}</span>
      </button>
      <Handle type="source" position={Position.Right} />
    </div>
  );
});

const nodeTypes = { "project-type": ProjectTypeNode };

export function ProjectGraphViewer({
  projectId,
  revision,
  enabled,
  methods,
  onOpenMethod,
  onSourceRangeSelect,
}: {
  projectId: string;
  revision: number;
  enabled: boolean;
  methods: CfgMethod[];
  onOpenMethod: (selector: string) => void;
  onSourceRangeSelect: (range: SourceRange) => void;
}) {
  const graphRequest = useSWR<ProjectGraphResponse, unknown>(
    enabled ? `project-graph:${projectId}:${revision}` : null,
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
  const [edgeKind, setEdgeKind] = useState("all");
  const navigationKey = `${projectId}:${revision}`;
  const [navigation, setNavigation] = useState({ key: navigationKey, packagePath: [] as string[], selectedTypeId: null as string | null });
  const activeNavigation = navigation.key === navigationKey
    ? navigation
    : { key: navigationKey, packagePath: [], selectedTypeId: null };
  const packagePath = activeNavigation.packagePath;
  const selectedTypeId = activeNavigation.selectedTypeId;
  const setPackagePath = useCallback((value: string[] | ((current: string[]) => string[])) => {
    setNavigation((current) => {
      const path = current.key === navigationKey ? current.packagePath : [];
      return { key: navigationKey, packagePath: typeof value === "function" ? value(path) : value, selectedTypeId: null };
    });
  }, [navigationKey]);
  const setSelectedTypeId = useCallback((id: string | null) => {
    setNavigation((current) => ({
      key: navigationKey,
      packagePath: current.key === navigationKey ? current.packagePath : [],
      selectedTypeId: id,
    }));
  }, [navigationKey]);
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }>>(new Map());

  const graph = graphRequest.data?.graph ?? null;
  const overview = useMemo(() => graph ? toProjectOverviewModel(graph) : null, [graph]);
  const typeNodes = useMemo(
    () => overview?.types ?? [],
    [overview],
  );
  const hasPackageHierarchy = overview?.scope === "project" && typeNodes.some((node) => node.sourceFile);
  const currentPackage = packagePath.join(".");
  const packageChildren = useMemo(() => {
    if (!hasPackageHierarchy || query.trim()) return [];
    const prefix = currentPackage ? `${currentPackage}.` : "";
    const packages = new Map<string, { typeCount: number; files: Set<string> }>();
    for (const node of typeNodes) {
      if (!node.packageName || (currentPackage && !node.packageName.startsWith(prefix))) continue;
      const remainder = currentPackage ? node.packageName.slice(prefix.length) : node.packageName;
      const childName = remainder.split(".")[0];
      if (!childName) continue;
      const fullName = currentPackage ? `${currentPackage}.${childName}` : childName;
      const entry = packages.get(fullName) ?? { typeCount: 0, files: new Set<string>() };
      entry.typeCount += 1;
      if (node.sourceFile) entry.files.add(node.sourceFile);
      packages.set(fullName, entry);
    }
    return [...packages.entries()]
      .filter(([name]) => name !== currentPackage)
      .sort(([left], [right]) => left.localeCompare(right, "ru"))
      .map(([name, counts]) => ({ name, label: name.split(".").at(-1) ?? name, ...counts }));
  }, [currentPackage, hasPackageHierarchy, query, typeNodes]);
  const packageTypes = useMemo(
    () => hasPackageHierarchy && !query.trim() && packagePath.length > 0
      ? typeNodes.filter((node) => node.packageName === currentPackage)
      : typeNodes,
    [currentPackage, hasPackageHierarchy, packagePath.length, query, typeNodes],
  );
  const matchingTypes = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase("ru-RU");
    return normalized
      ? typeNodes.filter((node) => `${node.name} ${node.qualifiedName} ${node.packageName} ${node.sourceFile ?? ""}`.toLocaleLowerCase("ru-RU").includes(normalized))
      : packageTypes;
  }, [packageTypes, query, typeNodes]);
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
    void import("elkjs/lib/elk.bundled.js").then(({ default: ELK }) => new ELK().layout({
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
    })).then((layout) => {
      if (!cancelled) {
        setPositions(new Map(layout.children?.map((node) => [node.id, { x: node.x ?? 0, y: node.y ?? 0 }]) ?? []));
      }
    }).catch(() => {
      if (!cancelled) setPositions(layoutFallback(filteredTypes));
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
  })), [filteredTypes, positions, selectedType?.id, setSelectedTypeId]);

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
    const waitingForAnalysis = !enabled;
    const error = graphRequest.error;
    const message = graphRequest.data?.graph === null
      ? "Парсер не построил граф проекта для этой ревизии. Проверьте диагностику project_graph_failed."
      : error instanceof ApiError && error.status === 404
        ? "Граф появится после завершения анализа текущей ревизии."
        : error instanceof Error ? error.message : "Граф проекта недоступен.";
    return (
      <section className="flex size-full min-h-0 min-w-0 flex-col overflow-hidden bg-[#080f1b]" aria-label="Обзор проекта">
        <div className="flex min-h-48 flex-col items-center justify-center gap-2 p-5 text-center text-slate-500">
          {waitingForAnalysis || graphRequest.isLoading ? <LoaderCircle className="spin" /> : <AlertCircle />}
          <strong>{waitingForAnalysis ? "Ожидаем анализ проекта" : graphRequest.isLoading ? "Загружаем граф проекта" : "Обзор проекта пока недоступен"}</strong>
          <p>{waitingForAnalysis ? "Граф будет загружен после завершения анализа сохранённой ревизии." : graphRequest.isLoading ? "Запрашиваем структуру текущей ревизии." : message}</p>
        </div>
      </section>
    );
  }

  const unresolved = overview.unresolvedCalls;
  const externals = overview.types.flatMap((type) => type.externalSupertypes.map((external) => ({ owner: type, ...external })));
  const canNavigateToSource = overview.scope === "compilation_unit";
  const truncated = matchingTypes.length > filteredTypes.length;

  function openSourceRange(range: SourceRange) {
    onSourceRangeSelect(range);
  }

  return (
    <section className="flex size-full min-h-0 min-w-0 flex-col overflow-hidden bg-[#080f1b]" aria-label="Обзор структуры проекта">
      <div className="flex min-h-[43px] items-center gap-2.5 border-b border-slate-800 bg-[#0d1625] p-2">
        <label className="flex h-7 min-w-44 flex-1 items-center gap-1.5 rounded border border-slate-700 bg-[#08101d] px-2 text-slate-500"><Search size={15} /><input className="w-full border-0 bg-transparent text-[9px] text-slate-300 outline-none" name="projectGraphSearch" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Найти пакет, класс или интерфейс" aria-label="Найти в проекте" /></label>
        <label className="flex items-center gap-1.5 text-[8px] text-slate-500"><span>Связи</span><select className="h-7 min-w-28 rounded border border-slate-700 bg-[#0c1422] px-2 font-mono text-[9px] text-slate-300 outline-none" name="projectGraphEdgeKind" value={edgeKind} onChange={(event) => setEdgeKind(event.target.value)} aria-label="Фильтр типов связей">
          <option value="structure">Структурные</option>
          <option value="all">Все типы</option>
          {Object.entries(EDGE_LABELS).map(([kind, label]) => <option key={kind} value={kind}>{label}</option>)}
        </select></label>
        <span className="whitespace-nowrap rounded border border-cyan-900 bg-cyan-400/5 px-1.5 py-1 font-mono text-[8px] font-semibold text-cyan-300">РЕВИЗИЯ {revision}</span>
      </div>

      <div className="flex min-h-[34px] items-center gap-3 overflow-x-auto border-b border-slate-800 px-2.5 text-[8px] whitespace-nowrap text-slate-500 [&_strong]:font-mono [&_strong]:text-slate-300">
        <span><strong>{typeNodes.length}</strong> типов в проекте</span>
        {hasPackageHierarchy && !query.trim() && matchingTypes.length > 0 ? <span><strong>{matchingTypes.length}</strong> {packagePath.length ? "в текущем пакете" : "на схеме"}</span> : null}
        <span><strong>{overview.types.reduce((total, type) => total + type.methods.length, 0)}</strong> методов в проекте</span>
        <span><strong>{overview.stats.resolved_internal_calls}</strong> внутренних вызовов</span>
        <span><strong>{overview.externalCallCount}</strong> внешних вызовов</span>
        <span><strong>{unresolved.length}</strong> неразрешённых</span>
      </div>

      <div className="flex min-h-0 flex-1 flex-col">
        {hasPackageHierarchy ? (
          <nav className="flex min-h-[31px] items-center justify-between gap-2.5 border-b border-slate-800 bg-[#0b1321] px-2.5 text-[8px] text-slate-500" aria-label="Путь по пакетам">
            <div className="flex items-center gap-1 [&_span]:flex [&_span]:items-center [&_span]:gap-1 [&_button]:border-0 [&_button]:bg-transparent [&_button]:p-0.5 [&_button]:text-[8px] [&_button]:text-slate-400">
              <button type="button" aria-current={packagePath.length === 0 && !query.trim() ? "location" : undefined} onClick={() => { setQuery(""); setPackagePath([]); setSelectedTypeId(null); }}>Весь проект</button>
              {!query.trim() ? packagePath.map((segment, index) => (
                <span key={`${segment}-${index}`}><ChevronRight size={13} /><button type="button" aria-current={index === packagePath.length - 1 ? "location" : undefined} onClick={() => { setPackagePath((path) => path.slice(0, index + 1)); setSelectedTypeId(null); }}>{segment}</button></span>
              )) : <span className="text-slate-600"><ChevronRight size={13} /> Поиск по всему проекту</span>}
            </div>
            {query.trim() ? <span className="text-slate-600">Сбросьте поиск, чтобы вернуться к пакетам</span> : null}
          </nav>
        ) : null}
        <div className="relative min-h-0 flex-1 bg-[#080f1b] [background-image:linear-gradient(rgba(51,65,85,.16)_1px,transparent_1px),linear-gradient(90deg,rgba(51,65,85,.16)_1px,transparent_1px)] [background-size:24px_24px]">
        {packageChildren.length ? (
          <div className="absolute top-2.5 left-2.5 z-10 grid max-h-[calc(100%-20px)] w-[min(300px,calc(100%-20px))] gap-1 overflow-auto rounded-md border border-slate-700 bg-[#0a121f]/95 p-2" aria-label="Вложенные пакеты">
            <p className="mb-1 flex justify-between text-[9px] text-slate-300">{packagePath.length ? "Вложенные пакеты" : "Пакеты проекта"}<span className="text-[8px] text-slate-500">Выберите пакет, чтобы перейти к его типам.</span></p>
            {packageChildren.slice(0, 24).map((pkg) => (
              <button className="flex items-center gap-2 rounded border border-slate-700 bg-slate-900 p-2 text-left text-slate-400" type="button" key={pkg.name} aria-label={`Открыть пакет ${pkg.name}: ${pkg.typeCount} типов в ${pkg.files.size} файлах`} onClick={() => { setPackagePath(pkg.name.split(".")); setSelectedTypeId(null); }}>
                <FolderOpen size={17} />
                <span className="grid min-w-0 flex-1 gap-0.5"><strong className="overflow-hidden text-ellipsis whitespace-nowrap text-[9px] text-slate-300">{pkg.label}</strong><small className="overflow-hidden text-ellipsis whitespace-nowrap text-[8px] text-slate-500">{pkg.typeCount} типов · {pkg.files.size} файлов</small></span>
                <ChevronRight size={14} />
              </button>
            ))}
            {packageChildren.length > 24 ? <p className="text-[8px] text-slate-500">Показаны первые 24 пакета. Найдите класс через поиск, чтобы перейти к нужному месту.</p> : null}
          </div>
        ) : null}
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
            <MiniMap style={{ width: 128, height: 74 }} pannable zoomable nodeColor={(node) => node.id === selectedType?.id ? "#d7ee72" : "#71917a"} />
          </ReactFlow>
        ) : !packageChildren.length ? <div className="grid h-full place-content-center gap-1.5 text-center text-slate-500"><Search className="mx-auto" size={19} /><strong className="text-slate-300">Типы не найдены</strong><span>Измените запрос или снимите фильтр.</span></div>
          : null}
        </div>
      </div>

      {truncated ? <p className="m-0 bg-amber-950/40 px-2.5 py-1 text-[8px] text-amber-300">Показаны первые {MAX_VISIBLE_TYPES} типов из {typeNodes.length}. Уточните поиск, чтобы сузить карту.</p> : null}

      {selectedType ? (
        <div className="grid max-h-[135px] grid-cols-[minmax(190px,.45fr)_minmax(260px,1fr)] gap-2.5 overflow-auto border-t border-slate-800 bg-[#0d1625] px-2.5 py-2">
          <div className="flex items-start justify-between gap-2">
            <div><small className="text-[8px] text-slate-500">{selectedType.kind.replace("_", " ")} · {selectedType.sourceRange.line ? `строка ${selectedType.sourceRange.line}` : "без диапазона"}</small><h3 className="mt-1 font-mono text-[10px] leading-tight text-slate-300 break-words">{selectedType.qualifiedName}</h3></div>
            {canNavigateToSource && selectedType.sourceRange ? <Button variant="outline" onClick={() => openSourceRange(selectedType.sourceRange)}><FileCode2 size={14} /> Исходник</Button> : null}
          </div>
          <div className="grid content-start gap-0.5" aria-label={`Методы ${selectedType.name}`}>
            {selectedMethods.length ? selectedMethods.map((method) => {
              const methodId = method.methodId ?? "";
              const matches = canNavigateToSource ? methods.filter((item) => item.id === methodId) : [];
              const target = matches.find((item) => sameSourceRange(item.source_range, method.sourceRange))
                ?? (matches.length === 1 ? matches[0] : undefined);
              return (
                <div className="flex items-center justify-between gap-2 rounded bg-slate-900 px-1.5 py-1" key={method.id}>
                  <span className="flex min-w-0 items-center gap-1.5"><Braces size={13} /><strong className="overflow-hidden text-ellipsis whitespace-nowrap text-[8px] text-slate-300">{method.name}</strong><small className="text-[7px] text-slate-500">{method.kind === "constructor" ? "конструктор" : method.sourceRange.line ? `стр. ${method.sourceRange.line}` : "метод"}</small></span>
                  <div className="flex items-center gap-1 [&_button]:grid [&_button]:size-[22px] [&_button]:place-items-center [&_button]:rounded [&_button]:border-0 [&_button]:bg-transparent [&_button]:text-slate-500 hover:[&_button]:bg-slate-800 hover:[&_button]:text-cyan-300">
                    {canNavigateToSource && method.sourceRange ? <button type="button" title="Открыть место в исходнике" onClick={() => openSourceRange(method.sourceRange)}><FileCode2 size={14} /></button> : null}
                    {target ? <button type="button" title="Открыть CFG и Big O" onClick={() => onOpenMethod(target.selector)}><ArrowUpRight size={14} /></button> : null}
                    {!target ? <small className="text-[8px] text-slate-500">CFG недоступен</small> : null}
                  </div>
                </div>
              );
            }) : <p className="text-[8px] text-slate-500">Методы не найдены.</p>}
          </div>
        </div>
      ) : null}

      <div className="grid max-h-[90px] grid-cols-2 gap-1.5 overflow-auto border-t border-slate-800 bg-[#0b1321] px-2.5 py-1.5 [&_details]:min-w-0 [&_details]:rounded [&_details]:border [&_details]:border-slate-700 [&_details]:bg-slate-900 [&_details]:px-2 [&_details]:py-1.5 [&_details]:text-[8px] [&_details]:text-slate-400 [&_summary]:flex [&_summary]:cursor-pointer [&_summary]:justify-between [&_summary]:text-slate-300">
        <details>
          <summary>Внешние типы и вызовы <span>{externals.length + overview.externalCallCount}</span></summary>
          <div className="mt-1 grid gap-1 [&>div]:flex [&>div]:min-w-0 [&>div]:justify-between [&>div]:gap-1.5 [&>div>span]:overflow-hidden [&>div>span]:text-ellipsis [&>div>span]:whitespace-nowrap [&>p]:m-0 [&>p]:text-slate-500">
            {externals.map((item, index) => <div key={`${item.owner.id}-${item.name}-${index}`}><span>{item.owner.name} <i>→</i> {item.name}</span><small>{item.relation}</small></div>)}
            <div><span>Вызовы библиотек и JDK</span><small>{overview.externalCallCount}</small></div>
            {!externals.length && !overview.externalCallCount ? <p>Внешних зависимостей не найдено.</p> : null}
          </div>
        </details>
        <details>
          <summary>Неразрешённые вызовы <span>{unresolved.length}</span></summary>
          <div className="mt-1 grid gap-1 [&>div]:flex [&>div]:min-w-0 [&>div]:justify-between [&>div]:gap-1.5 [&>div>span]:overflow-hidden [&>div>span]:text-ellipsis [&>div>span]:whitespace-nowrap [&>p]:m-0 [&>p]:text-slate-500">
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

function layoutFallback(nodes: ProjectOverviewType[]) {
  const columns = Math.max(1, Math.ceil(Math.sqrt(nodes.length)));
  return new Map(nodes.map((node, index) => [
    node.id,
    { x: (index % columns) * (NODE_WIDTH + 88), y: Math.floor(index / columns) * (NODE_HEIGHT + 54) },
  ]));
}
