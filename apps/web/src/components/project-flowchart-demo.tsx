"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import dagre from "@dagrejs/dagre";
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
} from "@xyflow/react";
import { ArrowDownLeft, ArrowRight, ChevronRight, CircleHelp, FileCode2, Search, WandSparkles, Workflow } from "lucide-react";
import type { ProjectFlowCatalog, ProjectFlowNode, ProjectFlowNodeKind } from "@/lib/project-flow-model";
import { demoOrderSource } from "@/fixtures/demo-order-source";

type FlowNodeData = {
  label: string;
  kind: ProjectFlowNodeKind;
  description: string;
  direction: "LR" | "TB";
  opensFlow: boolean;
  selected: boolean;
  activate: (nodeId: string) => void;
};
type FlowNode = Node<FlowNodeData, "business-flow">;
type FlowEdge = Edge<{ label?: string }>;
type FlowSearchResult = { id: string; title: string; context: string; path: string[]; nodeId?: string };

function flowPathLabel(catalog: ProjectFlowCatalog, path: string[]) {
  return path.map((id) => catalog.flows[id]?.scope ?? "Проект").join(" → ");
}

function searchFlowHierarchy(catalog: ProjectFlowCatalog, query: string): FlowSearchResult[] {
  const needle = query.trim().toLocaleLowerCase("ru-RU");
  if (!needle) return [];
  const results: FlowSearchResult[] = [];

  function visit(flowId: string, parentPath: string[], ancestry: Set<string>) {
    if (ancestry.has(flowId)) return;
    const flow = catalog.flows[flowId];
    if (!flow) return;
    const path = [...parentPath, flowId];
    const flowText = `${flow.title} ${flow.scope} ${flow.description}`.toLocaleLowerCase("ru-RU");
    if (flowText.includes(needle)) {
      results.push({ id: `flow:${path.join("/")}`, title: flow.title, context: flowPathLabel(catalog, path), path });
    }

    const nextAncestry = new Set(ancestry).add(flowId);
    for (const node of flow.nodes) {
      const nodeText = `${node.label} ${node.description}`.toLocaleLowerCase("ru-RU");
      if (nodeText.includes(needle)) {
        const targetPath = node.childFlowId ? [...path, node.childFlowId] : path;
        results.push({
          id: `node:${path.join("/")}:${node.id}`,
          title: node.label,
          context: flowPathLabel(catalog, targetPath),
          path: targetPath,
          nodeId: node.childFlowId ? undefined : node.id,
        });
      }
      if (node.childFlowId) visit(node.childFlowId, path, nextAncestry);
    }
  }

  visit(catalog.rootFlowId, [], new Set());
  const unique = new Map<string, FlowSearchResult>();
  for (const result of results) {
    const key = `${result.path.join("/")}:${result.title}`;
    if (!unique.has(key)) unique.set(key, result);
  }
  return [...unique.values()]
    .sort((left, right) => {
      const rank = (result: FlowSearchResult) => {
        const title = result.title.toLocaleLowerCase("ru-RU");
        return title === needle ? 0 : title.includes(needle) ? 1 : 2;
      };
      return rank(left) - rank(right) || right.path.length - left.path.length;
    })
    .slice(0, 8);
}

function nodeSize(node: ProjectFlowNode, compact = false) {
  if (compact) {
    if (node.kind === "start" || node.kind === "end") return { width: 248, height: 60 };
    if (node.kind === "decision") return { width: 260, height: 112 };
    return { width: 286, height: 76 };
  }
  if (node.kind === "start" || node.kind === "end") return { width: 176, height: 64 };
  if (node.kind === "decision") return { width: 194, height: 126 };
  return { width: 224, height: 92 };
}

function BusinessFlowNode({ id, data }: NodeProps<FlowNode>) {
  return (
    <div className={`business-flow-node business-flow-node--${data.kind}${data.selected ? " business-flow-node--selected" : ""}`}>
      <Handle type="target" position={data.direction === "TB" ? Position.Top : Position.Left} />
      <div className="business-flow-node__shape">
        <button
          className="business-flow-node__action nodrag nopan"
          type="button"
          aria-label={`${data.opensFlow ? "Открыть вложенную схему" : "Показать сведения"}: ${data.label}`}
          onClick={(event) => {
            event.stopPropagation();
            data.activate(id);
          }}
        >
          <span>{data.label}</span>
          {data.opensFlow ? <small><ChevronRight size={12} /> Раскрыть</small> : null}
        </button>
      </div>
      <Handle type="source" position={data.direction === "TB" ? Position.Bottom : Position.Right} />
    </div>
  );
}

const nodeTypes = { "business-flow": BusinessFlowNode };
const FLOW_FIT_OPTIONS = { padding: 0.16, minZoom: 0.25, maxZoom: 1.15 };

function layoutFlow(nodes: ProjectFlowNode[], edges: { from: string; to: string; label?: string }[], compact: boolean) {
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({
    rankdir: compact ? "TB" : "LR",
    nodesep: compact ? 28 : 52,
    ranksep: compact ? 34 : 82,
    marginx: compact ? 18 : 28,
    marginy: compact ? 20 : 24,
  });
  graph.setDefaultEdgeLabel(() => ({}));
  for (const node of nodes) graph.setNode(node.id, nodeSize(node, compact));
  for (const edge of edges) graph.setEdge(edge.from, edge.to, { label: edge.label });
  dagre.layout(graph);
  return new Map(nodes.map((node) => {
    const point = graph.node(node.id);
    const size = nodeSize(node, compact);
    return [node.id, { x: point.x - size.width / 2, y: point.y - size.height / 2 }];
  }));
}

export function ProjectFlowchartDemo({
  catalog,
  onBackToStructure,
  onOpenModernization,
}: {
  catalog: ProjectFlowCatalog;
  onBackToStructure: () => void;
  onOpenModernization: () => void;
}) {
  const [flowPath, setFlowPath] = useState(() => [catalog.rootFlowId]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [compactLayout, setCompactLayout] = useState(false);
  const [flowInstance, setFlowInstance] = useState<{ flowId: string; direction: "LR" | "TB"; instance: ReactFlowInstance<FlowNode, FlowEdge> } | null>(null);
  const flowId = flowPath.at(-1) ?? catalog.rootFlowId;
  const flow = catalog.flows[flowId] ?? catalog.flows[catalog.rootFlowId];
  const direction = compactLayout ? "TB" : "LR";
  const positions = useMemo(() => layoutFlow(flow.nodes, flow.edges, direction === "TB"), [direction, flow]);
  const searchResults = useMemo(() => searchFlowHierarchy(catalog, searchQuery), [catalog, searchQuery]);
  const selectedNode = flow.nodes.find((node) => node.id === selectedNodeId)
    ?? flow.nodes.find((node) => node.childFlowId)
    ?? flow.nodes.find((node) => node.kind === "process")
    ?? flow.nodes[0];

  const activateNode = useCallback((id: string) => {
    const selected = flow.nodes.find((node) => node.id === id);
    if (!selected) return;
    if (selected.childFlowId) {
      setSelectedNodeId(null);
      setFlowPath((path) => [...path, selected.childFlowId!]);
    } else {
      setSelectedNodeId(selected.id);
    }
  }, [flow.nodes]);

  const nodes = useMemo<FlowNode[]>(() => flow.nodes.map((node) => ({
    id: node.id,
    type: "business-flow",
    ...nodeSize(node, direction === "TB"),
    position: positions.get(node.id) ?? { x: 0, y: 0 },
    data: {
      label: node.label,
      kind: node.kind,
      description: node.description,
      direction,
      opensFlow: Boolean(node.childFlowId),
      selected: node.id === selectedNode?.id,
      activate: activateNode,
    },
  })), [activateNode, direction, flow.nodes, positions, selectedNode?.id]);

  const edges = useMemo<FlowEdge[]>(() => flow.edges.map((edge, index) => ({
    id: `${flow.id}-${edge.from}-${edge.to}-${index}`,
    source: edge.from,
    target: edge.to,
    type: "smoothstep",
    label: edge.label,
    markerEnd: { type: MarkerType.ArrowClosed, color: "#688273" },
    style: { stroke: "#688273", strokeWidth: 1.7 },
    labelStyle: { fill: "#526357", fontSize: 11, fontWeight: 600 },
    labelBgStyle: { fill: "#fbfaf6", fillOpacity: 0.98 },
    labelBgPadding: [5, 3] as [number, number],
  })), [flow.edges, flow.id]);

  useEffect(() => {
    if (!flowInstance || flowInstance.flowId !== flow.id || flowInstance.direction !== direction) return;
    let secondFrame = 0;
    const firstFrame = window.requestAnimationFrame(() => {
      secondFrame = window.requestAnimationFrame(() => {
        void flowInstance.instance.fitView(FLOW_FIT_OPTIONS);
      });
    });
    return () => {
      window.cancelAnimationFrame(firstFrame);
      if (secondFrame) window.cancelAnimationFrame(secondFrame);
    };
  }, [direction, flow.id, flowInstance]);

  useEffect(() => {
    const media = window.matchMedia("(max-width: 760px)");
    const updateLayout = () => setCompactLayout(media.matches);
    updateLayout();
    media.addEventListener("change", updateLayout);
    return () => media.removeEventListener("change", updateLayout);
  }, []);

  const breadcrumbs = flowPath.map((id, index) => ({ id, title: catalog.flows[id]?.scope ?? "Проект", index }));

  function openSearchResult(result: FlowSearchResult) {
    setFlowPath(result.path);
    setSelectedNodeId(result.nodeId ?? null);
    setSearchQuery("");
  }

  return (
    <section className="project-flow-demo" aria-label="Демонстрационная иерархическая блок-схема">
      <header className="project-flow-demo__header">
        <div>
          <div className="project-flow-demo__eyebrow"><Workflow size={14} /> ПРОТОТИП НА DEMO-ДАННЫХ</div>
          <h2>Блок-схемы проекта</h2>
          <p>Схемы только показывают логику. Нажмите на вложенный блок, чтобы углубиться.</p>
        </div>
        <div className="project-flow-demo__actions">
          <button className="project-flow-demo__back project-flow-demo__back--primary" type="button" onClick={onOpenModernization}><WandSparkles size={14} /> Модернизация</button>
          <button className="project-flow-demo__back" type="button" onClick={onBackToStructure}><ArrowDownLeft size={15} /> К примеру графа классов</button>
        </div>
      </header>

      <nav className="project-flow-breadcrumbs" aria-label="Уровень блок-схемы">
        {breadcrumbs.map((crumb, index) => (
          <span key={`${crumb.id}-${index}`}>
            {index ? <ChevronRight size={13} aria-hidden="true" /> : null}
            <button
              className={index === breadcrumbs.length - 1 ? "project-flow-breadcrumb--current" : ""}
              type="button"
              aria-current={index === breadcrumbs.length - 1 ? "page" : undefined}
              onClick={() => { setSelectedNodeId(null); setFlowPath((path) => path.slice(0, index + 1)); }}
            >{crumb.title}</button>
          </span>
        ))}
        <strong>{flow.title}</strong>
      </nav>

      <div className="project-flow-meta">
        <span>{flow.scope}</span>
        <span>{flow.nodes.length} блоков</span>
        <p>{flow.description}</p>
      </div>

      <div className="project-flow-search">
        <label className="project-flow-search__input"><Search size={15} /><input
          type="search"
          name="project-flow-search"
          role="combobox"
          value={searchQuery}
          onChange={(event) => setSearchQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && searchResults[0]) openSearchResult(searchResults[0]);
            if (event.key === "Escape") setSearchQuery("");
          }}
          placeholder="Найти шаг или уровень проекта"
          aria-label="Найти шаг или уровень проекта"
          aria-expanded={searchResults.length > 0}
          aria-haspopup={searchResults.length ? "listbox" : undefined}
          aria-autocomplete="list"
          aria-controls={searchQuery.trim() ? "project-flow-search-results" : undefined}
        /></label>
        {searchQuery.trim() && searchResults.length ? <div className="project-flow-search__results" id="project-flow-search-results" role="listbox" aria-label="Результаты поиска по демо-схемам">
          {searchResults.map((result) => <button type="button" role="option" aria-selected="false" key={result.id} onClick={() => openSearchResult(result)}>
            <span><strong>{result.title}</strong><small>{result.context}</small></span><ArrowRight size={14} />
          </button>)}
        </div> : searchQuery.trim() ? <p className="project-flow-search__empty" id="project-flow-search-results" role="status">В демо-схемах совпадений нет.</p> : null}
        <small>Поиск охватывает шаги и вложенные схемы демо.</small>
      </div>

      <div className={`project-flow-canvas${compactLayout ? " project-flow-canvas--compact" : ""}`}>
        <ReactFlow
          key={`${flow.id}:${direction}`}
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          fitView
          fitViewOptions={FLOW_FIT_OPTIONS}
          onInit={(instance) => setFlowInstance({ flowId: flow.id, direction, instance })}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          nodesFocusable={false}
          edgesFocusable={false}
          disableKeyboardA11y
          minZoom={0.2}
          maxZoom={1.5}
          panOnScroll
          zoomOnDoubleClick={false}
          aria-label={`Блок-схема: ${flow.title}`}
        >
          <Background color="#d0d6cd" gap={24} size={1} />
          <Controls showInteractive={false} />
          {!compactLayout ? <MiniMap position="top-right" pannable zoomable nodeColor={(node) => node.id === selectedNode?.id ? "#d7ee72" : "#769382"} /> : null}
        </ReactFlow>
      </div>

      <div className="project-flow-selection" aria-live="polite">
        <span className={`project-flow-kind project-flow-kind--${selectedNode?.kind ?? "process"}`}>
          {selectedNode?.kind === "decision" ? "УСЛОВИЕ" : selectedNode?.kind === "subprocess" ? "ВЛОЖЕННАЯ СХЕМА" : "ШАГ"}
        </span>
        <div><strong>{selectedNode?.label}</strong><p>{selectedNode?.description}</p></div>
        {selectedNode?.childFlowId ? <span className="project-flow-hint"><CircleHelp size={13} /> Нажмите блок, чтобы открыть схему глубже</span> : null}
      </div>
      {selectedNode?.source ? (
        <details className="project-flow-source">
          <summary><FileCode2 size={13} /><span>{selectedNode.source.file}</span><small>строки {selectedNode.source.range.line}–{selectedNode.source.range.end_line}</small></summary>
          <pre><code>{demoOrderSource.slice(selectedNode.source.range.start, selectedNode.source.range.start + selectedNode.source.range.length)}</code></pre>
        </details>
      ) : null}
      <p className="project-flow-demo__notice"><CircleHelp size={13} /> Демонстрационная схема не построена по загруженному исходному коду.</p>
    </section>
  );
}
