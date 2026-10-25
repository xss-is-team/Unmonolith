"use client";

import { memo, useCallback, useEffect, useMemo, useState } from "react";
import type { MouseEvent } from "react";
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
  type NodeProps,
} from "@xyflow/react";
import {
  AlertCircle,
  ArrowUpRight,
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
import { RoutedEdge, elkRoutes, type Point } from "@/components/routed-edge";

const edgeTypes = { routed: RoutedEdge };
import {
  resolveCfgSelector,
  toProjectOverviewModel,
  type ProjectOverviewRelationship,
  type ProjectOverviewType,
} from "@/lib/project-overview-model";

const NODE_WIDTH = 208;
const NODE_HEIGHT = 72;
const MAX_VISIBLE_TYPES = 250;
const EDGE_KIND_LABELS: Record<string, string> = {
  calls: "вызовы",
  constructs: "создание",
  depends_on: "зависимости",
  extends: "наследование",
  implements: "реализация",
};

type TypeNodeData = {
  name: string;
  qualifiedName: string;
  kind: string;
  methodCount: number;
  selected: boolean;
  dimmed: boolean;
  selectType: () => void;
};
type FlowNode = Node<TypeNodeData, "project-type">;
type ProjectEdge = Edge & { data: { relationship: ProjectOverviewRelationship; points: Point[] | null } };

const ProjectTypeNode = memo(function ProjectTypeNode({ data }: NodeProps<FlowNode>) {
  return (
    <div
      className={`flex h-[72px] w-52 flex-col justify-center gap-1 rounded-md border bg-slate-900 px-3 transition-opacity ${
        data.selected ? "border-cyan-400 ring-2 ring-cyan-400/25" : "border-slate-600"
      } ${data.dimmed ? "opacity-30" : "opacity-100"}`}
    >
      <Handle type="target" position={Position.Left} />
      <button
        className="nodrag nopan flex w-full flex-col gap-1 border-0 bg-transparent p-0 text-left"
        type="button"
        aria-label={`Выбрать ${data.kind === "interface" ? "интерфейс" : "класс"} ${data.qualifiedName}; методов: ${data.methodCount}`}
        onClick={(event) => {
          event.stopPropagation();
          data.selectType();
        }}
      >
        <span className="flex w-full items-center gap-1.5 text-[10px] text-slate-500">
          <Braces className="size-3.5 shrink-0 text-violet-400" />
          <small className="truncate">{data.kind.replace("_", " ")}</small>
          <span className="ml-auto shrink-0">{data.methodCount} м.</span>
        </span>
        <strong className="truncate text-[13px] leading-4 text-slate-200" title={data.qualifiedName}>{data.name}</strong>
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
  const [focusedMethod, setFocusedMethod] = useState<string | null>(null);
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
  const [edgeFocus, setEdgeFocus] = useState<{ id: string; pinned: boolean } | null>(null);
  const [positions, setPositions] = useState<Map<string, { x: number; y: number }>>(new Map());
  const [routes, setRoutes] = useState<Map<string, Point[]>>(new Map());

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
  const projectedRelationships = useMemo(
    () => overview ? overview.relationships.filter((edge) => visibleTypeIds.has(edge.from) && visibleTypeIds.has(edge.to)).filter((edge) => (
      edgeKind === "all"
      || (edgeKind === "structure" && edge.kinds.some((item) => item.kind === "extends" || item.kind === "implements"))
      || edge.kinds.some((item) => item.kind === edgeKind)
    )) : [],
    [edgeKind, overview, visibleTypeIds],
  );
  const selectedType = selectedTypeId
    ? filteredTypes.find((node) => node.id === selectedTypeId) ?? null
    : null;
  const selectedMethod = selectedType?.methods.find((method) => method.id === focusedMethod) ?? null;
  const neighborIds = useMemo(
    () => selectedType ? overview?.neighbors.get(selectedType.id) ?? new Set<string>() : null,
    [overview, selectedType],
  );

  const dependencies = useMemo(() => {
    if (!selectedType || !overview) return [];
    return projectedRelationships
      .filter((edge) => edge.from === selectedType.id || edge.to === selectedType.id)
      .map((edge) => {
        const otherId = edge.from === selectedType.id ? edge.to : edge.from;
        return { relationship: edge, type: overview.typeById.get(otherId) ?? null };
      })
      .filter((item) => item.type !== null);
  }, [overview, projectedRelationships, selectedType]);

  useEffect(() => {
    let cancelled = false;
    if (!filteredTypes.length) return;
    void import("elkjs/lib/elk.bundled.js").then(({ default: ELK }) => new ELK().layout({
      id: "project-overview",
      layoutOptions: {
        "elk.algorithm": "layered",
        "elk.direction": "RIGHT",
        "elk.edgeRouting": "ORTHOGONAL",
        "elk.layered.thoroughness": "detail",
        "elk.layered.spacing.nodeNodeBetweenLayers": "80",
        "elk.spacing.nodeNode": "28",
        "elk.padding": "[top=24;left=24;bottom=24;right=24]",
        "elk.layered.cycleBreaking.strategy": "GREEDY",
        "elk.spacing.edgeEdge": "14",
        "elk.spacing.edgeNode": "18",
        "elk.layered.spacing.edgeEdgeBetweenLayers": "12",
        "elk.layered.spacing.edgeNodeBetweenLayers": "16",
        "elk.layered.mergeEdges": "false",
      },
      children: filteredTypes.map((node) => ({ id: node.id, width: NODE_WIDTH, height: NODE_HEIGHT })),
      edges: projectedRelationships.map((edge, index) => ({
        id: `project-edge-${index}`,
        sources: [edge.from],
        targets: [edge.to],
      })),
    })).then((layout) => {
      if (!cancelled) {
        const routes = elkRoutes(layout.edges);
        setRoutes(new Map(projectedRelationships.map((edge, index) => [relationshipId(edge), routes.get(`project-edge-${index}`) ?? null] as const).filter((entry): entry is [string, Point[]] => entry[1] !== null)));
        setPositions(new Map(layout.children?.map((node) => [node.id, { x: node.x ?? 0, y: node.y ?? 0 }]) ?? []));
      }
    }).catch(() => {
      if (!cancelled) setPositions(layoutFallback(filteredTypes));
    });
    return () => { cancelled = true; };
  }, [filteredTypes, projectedRelationships]);

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
      dimmed: neighborIds !== null && node.id !== selectedType?.id && !neighborIds.has(node.id),
      selectType: () => setSelectedTypeId(node.id === selectedType?.id ? null : node.id),
    },
    selected: node.id === selectedType?.id,
    deletable: false,
  })), [filteredTypes, positions, selectedType?.id, neighborIds, setSelectedTypeId]);

  const focusedEdgeId = edgeFocus?.id ?? null;
  const focusedRelationship = useMemo(() => {
    if (!focusedEdgeId) return null;
    const match = projectedRelationships.find((edge) => relationshipId(edge) === focusedEdgeId);
    return match ?? null;
  }, [focusedEdgeId, projectedRelationships]);

  const flowEdges = useMemo<ProjectEdge[]>(() => projectedRelationships.map((edge) => {
    const id = relationshipId(edge);
    const focused = focusedEdgeId === id;
    const neighborEdge = selectedType !== null
      && (edge.from === selectedType.id || edge.to === selectedType.id);
    const dimmed = Boolean(selectedType) && !neighborEdge;
    return {
      id,
      source: edge.from,
      target: edge.to,
      type: "routed" as const,
      markerStart: edge.directions.length > 1
        ? { type: MarkerType.ArrowClosed, color: relationshipColor(edge) }
        : undefined,
      markerEnd: { type: MarkerType.ArrowClosed, color: relationshipColor(edge) },
      style: {
        stroke: relationshipColor(edge),
        strokeWidth: focused ? 3 : 1.4,
        opacity: dimmed ? 0.18 : focused ? 1 : 0.8,
      },
      data: { relationship: edge, points: routes.get(id) ?? null },
    };
  }), [projectedRelationships, focusedEdgeId, selectedType, routes]);

  function openTypeNode(_event: MouseEvent, node: FlowNode) {
    const type = filteredTypes.find((item) => item.id === node.id);
    if (!type) return;
    for (const method of type.methods) {
      const resolution = resolveCfgSelector(method, methods);
      if (resolution && typeof resolution === "object") {
        onOpenMethod(resolution.selector);
        return;
      }
    }
    setSelectedTypeId(node.id);
  }

  function selectNode(_event: MouseEvent, node: FlowNode) {
    setSelectedTypeId(node.id === selectedTypeId ? null : node.id);
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
          <strong className="text-[14px] text-slate-300">{waitingForAnalysis ? "Ожидаем анализ проекта" : graphRequest.isLoading ? "Загружаем граф проекта" : "Обзор проекта пока недоступен"}</strong>
          <p className="max-w-lg text-[13px]">{waitingForAnalysis ? "Граф будет загружен после завершения анализа сохранённой ревизии." : graphRequest.isLoading ? "Запрашиваем структуру текущей ревизии." : message}</p>
        </div>
      </section>
    );
  }

  const unresolved = overview.unresolvedCalls;
  const externals = overview.types.flatMap((type) => type.externalSupertypes.map((external) => ({ owner: type, ...external })));
  const canNavigateToSource = overview.scope === "compilation_unit";
  const truncated = matchingTypes.length > filteredTypes.length;
  const methodTotal = overview.types.reduce((total, type) => total + type.methods.length, 0);

  function openSourceRange(range: SourceRange) {
    onSourceRangeSelect(range);
  }

  return (
    <section className="flex size-full min-h-0 min-w-0 flex-col overflow-hidden bg-[#080f1b]" aria-label="Обзор проекта">
      <div className="flex min-h-[42px] items-center gap-3 border-b border-slate-800 bg-[#0d1625] px-3">
        <label className="flex h-8 min-w-52 flex-1 items-center gap-2 rounded border border-slate-700 bg-[#08101d] px-2.5 text-slate-500"><Search size={15} /><input className="w-full border-0 bg-transparent text-[13px] text-slate-300 outline-none" name="projectGraphSearch" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Найти класс или интерфейс" aria-label="Найти в проекте" /></label>
        <label className="flex items-center gap-2 text-[13px] text-slate-500"><span>Связи</span><select className="h-8 min-w-32 rounded border border-slate-700 bg-[#0c1422] px-2 text-[13px] text-slate-300 outline-none" name="projectGraphEdgeKind" value={edgeKind} onChange={(event) => setEdgeKind(event.target.value)} aria-label="Фильтр типов связей">
          <option value="all">Все</option>
          <option value="structure">Структурные</option>
          {Object.entries(EDGE_KIND_LABELS).map(([kind, label]) => <option key={kind} value={kind}>{label}</option>)}
        </select></label>
        <span className="whitespace-nowrap text-[13px] text-slate-500"><strong className="font-mono text-slate-300">{typeNodes.length}</strong> типов · <strong className="font-mono text-slate-300">{methodTotal}</strong> методов</span>
      </div>

      <div className="flex min-h-[34px] items-center gap-1.5 overflow-x-auto border-b border-slate-800 px-3 text-[13px] whitespace-nowrap" aria-label="Путь: проект, пакет, класс">
        <button type="button" className="rounded border-0 bg-transparent p-0.5 text-slate-400 hover:bg-slate-800 hover:text-slate-200" aria-current={selectedTypeId === null && packagePath.length === 0 ? "location" : undefined} onClick={() => { setQuery(""); setPackagePath([]); setSelectedTypeId(null); setFocusedMethod(null); }}>
          {hasPackageHierarchy ? "Весь проект" : "Проект"}
        </button>
        {!query.trim() && packagePath.map((segment, index) => (
          <span key={`${segment}-${index}`} className="flex items-center gap-1.5"><ChevronRight size={14} className="text-slate-600" /><button type="button" className="rounded border-0 bg-transparent p-0.5 font-mono text-cyan-200" aria-current={index === packagePath.length - 1 ? "location" : undefined} onClick={() => { setPackagePath((path) => path.slice(0, index + 1)); setSelectedTypeId(null); setFocusedMethod(null); }}>{segment}</button></span>
        ))}
        <ChevronRight size={14} className="text-slate-600" />
        {selectedType ? (
          <span className="flex items-center gap-2 font-mono text-cyan-200">{selectedType.qualifiedName}
            {selectedMethod ? <><ChevronRight size={14} className="text-slate-600" /><span className="font-mono text-slate-300">{selectedMethod.name}({selectedMethod.kind === "constructor" ? "конструктор" : ""})</span></> : null}
          </span>
        ) : <span className="text-slate-600">выберите тип на схеме</span>}
      </div>

      <div className="relative min-h-0 flex-1 bg-[#080f1b] [background-image:linear-gradient(rgba(51,65,85,.16)_1px,transparent_1px),linear-gradient(90deg,rgba(51,65,85,.16)_1px,transparent_1px)] [background-size:24px_24px]">
        {packageChildren.length ? (
          <div className="absolute top-2.5 left-2.5 z-10 grid max-h-[calc(100%-20px)] w-[min(300px,calc(100%-20px))] gap-1 overflow-auto rounded-md border border-slate-700 bg-[#0a121f]/95 p-2" aria-label="Вложенные пакеты">
            <p className="mb-1 text-[13px] text-slate-300">{packagePath.length ? "Вложенные пакеты" : "Пакеты проекта"}</p>
            {packageChildren.slice(0, 24).map((pkg) => (
              <button className="flex items-center gap-2 rounded border border-slate-700 bg-slate-900 p-2 text-left text-slate-400" type="button" key={pkg.name} aria-label={`Открыть пакет ${pkg.name}: ${pkg.typeCount} типов`} onClick={() => { setPackagePath(pkg.name.split(".")); setSelectedTypeId(null); setFocusedMethod(null); }}>
                <FolderOpen size={17} />
                <span className="grid min-w-0 flex-1 gap-0.5"><strong className="overflow-hidden text-ellipsis whitespace-nowrap text-[13px] text-slate-300">{pkg.label}</strong><small className="text-[11px] text-slate-500">{pkg.typeCount} типов · {pkg.files.size} файлов</small></span>
                <ChevronRight size={14} />
              </button>
            ))}
            {packageChildren.length > 24 ? <p className="text-[11px] text-slate-500">Показаны первые 24 пакета; найдите класс через поиск.</p> : null}
          </div>
        ) : null}
        {focusedRelationship ? (
          <div className="absolute top-2.5 right-2.5 z-10 w-64 rounded-md border border-cyan-800 bg-[#0a121f]/95 p-2.5" role="status" aria-label="Детали выбранной связи">
            <div className="flex items-start justify-between gap-2">
              <strong className="break-words font-mono text-[12px] leading-4 text-slate-200">{overview.typeById.get(focusedRelationship.from)?.name} <span className="text-slate-500">{focusedRelationship.directions.length > 1 ? "↔" : "→"}</span> {overview.typeById.get(focusedRelationship.to)?.name}</strong>
              <button type="button" className="shrink-0 border-0 bg-transparent text-slate-500 hover:text-slate-300" aria-label="Закрыть детали связи" onClick={() => setEdgeFocus(null)}>×</button>
            </div>
            <div className="mt-2 grid gap-1.5">
              {focusedRelationship.directions.map((direction) => (
                <div key={`${direction.from}-${direction.to}`} className="rounded bg-slate-800 px-2 py-1.5 text-[11px] text-slate-400">
                  <span className="font-mono text-slate-200">{overview.typeById.get(direction.from)?.name} → {overview.typeById.get(direction.to)?.name}</span>
                  <span className="mt-0.5 block">{direction.kinds.map((item) => `${EDGE_KIND_LABELS[item.kind] ?? item.kind}: ${item.count}`).join(" · ")}</span>
                </div>
              ))}
            </div>
            {!edgeFocus?.pinned ? <small className="mt-1.5 block text-[11px] text-slate-500">Клик по связи закрепляет детали.</small> : null}
          </div>
        ) : null}
        {filteredTypes.length ? (
          <ReactFlow
            key={layoutKey}
            nodes={flowNodes}
            edges={flowEdges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            fitView
            fitViewOptions={{ padding: 0.04, minZoom: 0.3, maxZoom: 1.2 }}
            onNodeClick={selectNode}
            onNodeDoubleClick={openTypeNode}
            onEdgeMouseEnter={(_event, edge) => setEdgeFocus((current) => current?.pinned ? current : { id: edge.id, pinned: false })}
            onEdgeMouseLeave={() => setEdgeFocus((current) => current?.pinned ? current : null)}
            onEdgeClick={(_event, edge) => setEdgeFocus((current) => (current?.id === edge.id && current.pinned ? null : { id: edge.id, pinned: true }))}
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
          </ReactFlow>
        ) : !packageChildren.length ? <div className="grid h-full place-content-center gap-1.5 text-center text-slate-500"><Search className="mx-auto" size={19} /><strong className="text-[14px] text-slate-300">Типы не найдены</strong><span className="text-[13px]">Измените запрос или снимите фильтр.</span></div>
          : null}
      </div>

      {truncated ? <p className="m-0 bg-amber-950/40 px-3 py-1.5 text-[13px] text-amber-300">Показаны первые {MAX_VISIBLE_TYPES} типов из {typeNodes.length}. Уточните поиск, чтобы сузить карту.</p> : null}

      {selectedType ? (
        <div className="grid max-h-[210px] grid-cols-[minmax(220px,.45fr)_minmax(300px,1fr)] gap-4 overflow-auto border-t border-slate-800 bg-[#0d1625] px-3 py-2.5">
          <div className="grid content-start gap-2">
            <div className="flex items-start justify-between gap-2">
              <div>
                <small className="text-[11px] text-slate-500">{selectedType.kind.replace("_", " ")}{selectedType.sourceRange.line ? ` · строка ${selectedType.sourceRange.line}` : ""}</small>
                <h3 className="mt-0.5 mb-0 break-words font-mono text-[13px] leading-4 text-slate-200">{selectedType.qualifiedName}</h3>
              </div>
              {canNavigateToSource && selectedType.sourceRange ? <Button variant="outline" onClick={() => openSourceRange(selectedType.sourceRange)}><FileCode2 /> Исходник</Button> : null}
            </div>
            <div className="grid content-start gap-0.5" aria-label={`Зависимости ${selectedType.name}`}>
              <span className="text-[11px] font-semibold tracking-wide text-slate-500 uppercase">Связи · {dependencies.length}</span>
              {dependencies.length ? dependencies.map(({ relationship, type }) => (
                <button key={`${relationship.from}-${relationship.to}-${relationship.kinds.map((item) => item.kind).join(",")}`} className="flex w-full items-center justify-between gap-2 rounded border-0 bg-transparent px-1.5 py-1 text-left hover:bg-slate-800" type="button" onClick={() => { setSelectedTypeId(type!.id); setFocusedMethod(null); }}>
                  <span className="truncate font-mono text-[13px] text-slate-300">{type!.name}</span>
                  <span className="shrink-0 font-mono text-[11px] text-slate-500">{relationship.total} · {relationship.kinds.map((item) => EDGE_KIND_LABELS[item.kind] ?? item.kind).join(", ")}</span>
                </button>
              )) : <p className="m-0 text-[13px] text-slate-500">Связей с другими типами нет.</p>}
            </div>
          </div>
          <div className="grid content-start gap-0.5" aria-label={`Методы ${selectedType.name}`}>
            <span className="text-[11px] font-semibold tracking-wide text-slate-500 uppercase">Методы · {selectedType.methods.length}</span>
            {selectedType.methods.length ? selectedType.methods.map((method) => {
              const resolution = resolveCfgSelector(method, methods);
              return (
                <div className={`flex items-center justify-between gap-2 rounded px-1.5 py-1 ${focusedMethod === method.id ? "bg-slate-800" : "bg-slate-900"}`} key={method.id}>
                  <button className="flex min-w-0 flex-1 items-center gap-1.5 border-0 bg-transparent p-0 text-left" type="button" onClick={() => setFocusedMethod(method.id === focusedMethod ? null : method.id)} aria-label={`Метод ${method.name}, строка ${method.sourceRange.line ?? "неизвестна"}`}>
                    <Braces size={13} className="shrink-0 text-violet-400" />
                    <strong className="truncate text-[13px] text-slate-200">{method.name}</strong>
                    <small className="shrink-0 text-[11px] text-slate-500">{method.kind === "constructor" ? "конструктор" : method.sourceRange.line ? `стр. ${method.sourceRange.line}` : "метод"}</small>
                  </button>
                  <div className="flex shrink-0 items-center gap-1">
                    {canNavigateToSource && method.sourceRange ? <button type="button" title="Открыть место в исходнике" className="grid size-7 place-items-center rounded border-0 bg-transparent text-slate-500 hover:bg-slate-800 hover:text-cyan-300" aria-label={`Открыть исходник метода ${method.name}`} onClick={() => { setFocusedMethod(method.id); openSourceRange(method.sourceRange); }}><FileCode2 size={14} /></button> : null}
                    {resolution && typeof resolution === "object" ? (
                      <button type="button" title="Открыть CFG и Big O" className="inline-flex h-7 items-center gap-1 rounded border border-slate-700 bg-transparent px-2 text-[11px] text-slate-400 hover:border-cyan-800 hover:bg-slate-800 hover:text-cyan-200" aria-label={`Открыть CFG метода ${method.name}`} onClick={() => { setFocusedMethod(method.id); onOpenMethod(resolution.selector); }}>
                        CFG <ArrowUpRight size={13} />
                      </button>
                    ) : resolution === "no_cfg" ? (
                      <small className="text-[11px] text-slate-600">нет CFG</small>
                    ) : (
                      <small className="text-[11px] text-amber-400" title="method_id встречается несколько раз (локальные классы), CFG не определена для этого узла">несколько CFG</small>
                    )}
                  </div>
                </div>
              );
            }) : <p className="m-0 text-[13px] text-slate-500">Методы не найдены.</p>}
          </div>
        </div>
      ) : null}

      <div className="hidden max-h-[90px] grid-cols-2 gap-1.5 overflow-auto border-t border-slate-800 bg-[#0b1321] px-3 py-2 [&_details]:min-w-0 [&_details]:rounded [&_details]:border [&_details]:border-slate-700 [&_details]:bg-slate-900 [&_details]:px-2.5 [&_details]:py-1.5 [&_details]:text-[12px] [&_details]:text-slate-400 [&_summary]:flex [&_summary]:cursor-pointer [&_summary]:justify-between [&_summary]:text-[13px] [&_summary]:text-slate-300">
        <details>
          <summary>Внешние типы и вызовы <span>{externals.length + overview.externalCallCount}</span></summary>
          <div className="mt-1 grid gap-1 [&>div]:flex [&>div]:min-w-0 [&>div]:justify-between [&>div]:gap-1.5 [&>div>span]:overflow-hidden [&>div>span]:text-ellipsis [&>div>span]:whitespace-nowrap [&>p]:m-0 [&>p]:text-slate-500">
            {externals.map((item, index) => <div key={`${item.owner.id}-${item.name}-${index}`}><span>{item.owner.name} → {item.name}</span><small>{item.relation}</small></div>)}
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

function relationshipColor(relationship: ProjectOverviewRelationship) {
  const kind = relationship.kinds[0]?.kind;
  switch (kind) {
    case "extends": return "#c08552";
    case "implements": return "#7fa884";
    case "calls": return "#7fa8bd";
    case "constructs": return "#b08cc0";
    default: return "#879187";
  }
}

function relationshipId(relationship: ProjectOverviewRelationship) {
  return `edge-${relationship.from}-${relationship.to}`;
}

function layoutFallback(nodes: ProjectOverviewType[]) {
  const columns = Math.max(1, Math.ceil(Math.sqrt(nodes.length)));
  return new Map(nodes.map((node, index) => [
    node.id,
    { x: (index % columns) * (NODE_WIDTH + 110), y: Math.floor(index / columns) * (NODE_HEIGHT + 72) },
  ]));
}
