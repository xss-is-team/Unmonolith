"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import dagre from "@dagrejs/dagre";
import {
  applyNodeChanges,
  Background,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  useNodesState,
  type Edge,
  type Node,
  type NodeChange,
  type NodeProps,
  type ReactFlowInstance,
} from "@xyflow/react";
import { ArrowLeft, ArrowRight, Boxes, CircleHelp, Code2, GitBranch, Gauge, RotateCcw, ShieldCheck, Undo2, WandSparkles } from "lucide-react";
import type {
  JavaTarget,
  ModernizationCatalog,
  ModernizationOperation,
  ModernizationModuleId,
  ModernizationScenario,
  ModernizationScenarioKind,
  PlanStepKind,
} from "@/lib/modernization-model";

type PlanNodeData = { label: string; kind: PlanStepKind; detail: string; operation?: ModernizationOperation; selected: boolean; selectStep?: () => void };
type PlanNode = Node<PlanNodeData, "plan-step">;
type PlanEdge = Edge;

function PlanStepNode({ data }: NodeProps<PlanNode>) {
  return (
    <div className={`modernization-node modernization-node--${data.kind}${data.selected ? " modernization-node--selected" : ""}`}>
      <Handle type="target" position={Position.Left} />
      <div className="modernization-node__shape">
        <button
          className="modernization-node__action"
          type="button"
          aria-label={`Выбрать шаг плана: ${data.label}`}
          onClick={(event) => {
            event.stopPropagation();
            data.selectStep?.();
          }}
        >
          <span>{data.label}</span>
          {data.kind === "call" ? <small>Преобразование</small> : null}
        </button>
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  );
}

const planNodeTypes = { "plan-step": PlanStepNode };
const scenarioIcons: Record<ModernizationScenarioKind, typeof Code2> = {
  "java-migration": Code2,
  decomposition: Boxes,
  optimization: Gauge,
};

function planGraph(scenario: ModernizationScenario, selectedNodeId: string, compact: boolean) {
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: compact ? "TB" : "LR", nodesep: compact ? 18 : 28, ranksep: compact ? 28 : 24, marginx: 8, marginy: 16 });
  graph.setDefaultEdgeLabel(() => ({}));

  for (const step of scenario.steps) {
    const size = planNodeSize(step.kind, compact);
    graph.setNode(step.id, size);
  }
  for (const edge of scenario.edges) graph.setEdge(edge.source, edge.target, { label: edge.label });
  dagre.layout(graph);

  const nodes: PlanNode[] = scenario.steps.map((step) => {
    const size = planNodeSize(step.kind, compact);
    const point = graph.node(step.id);
    return {
      id: step.id,
      type: "plan-step",
      position: { x: point.x - size.width / 2, y: point.y - size.height / 2 },
      ...size,
      deletable: false,
      data: { label: step.label, kind: step.kind, detail: step.detail, operation: step.operation, selected: step.id === selectedNodeId },
    };
  });
  const edges: PlanEdge[] = scenario.edges.map((edge, index) => ({
    id: `${edge.source}-${edge.target}-${index}`,
    source: edge.source,
    target: edge.target,
    type: "smoothstep",
    label: edge.label,
    markerEnd: { type: MarkerType.ArrowClosed, color: "#688273" },
    style: { stroke: "#688273", strokeWidth: 1.7 },
    labelStyle: { fill: "#526357", fontSize: 10, fontWeight: 600 },
    labelBgStyle: { fill: "#fbfaf6", fillOpacity: 0.98 },
    labelBgPadding: [5, 3] as [number, number],
  }));
  return { nodes, edges, height: compact ? graph.graph().height ?? 0 : 0 };
}

function planNodeSize(kind: PlanStepKind, compact: boolean) {
  if (compact) {
    if (kind === "start" || kind === "end") return { width: 144, height: 58 };
    if (kind === "gateway") return { width: 160, height: 96 };
    return { width: 160, height: 70 };
  }
  if (kind === "start" || kind === "end") return { width: 140, height: 58 };
  if (kind === "gateway") return { width: 160, height: 92 };
  return { width: 160, height: 72 };
}

function operationParameterSummary(operation: ModernizationOperation) {
  switch (operation.id) {
    case "migration.set_target_java": return `targetJava=${operation.parameters.targetJava}`;
    case "decomposition.extract_modules": return `modules=${operation.parameters.modules.join(",") || "none"}`;
    case "optimization.index_by_key": return `key=${operation.parameters.key}`;
  }
}

function EditablePlan({ scenario, onBack, compactLayout }: { scenario: ModernizationScenario; onBack: () => void; compactLayout: boolean }) {
  const initialSelectedId = scenario.steps.find((step) => step.kind === "call")?.id ?? scenario.steps[1].id;
  const initial = useMemo(() => planGraph(scenario, initialSelectedId, compactLayout), [scenario, initialSelectedId, compactLayout]);
  const [nodes, setNodes] = useNodesState(initial.nodes);
  const [edges] = useState(initial.edges);
  const [planInstance, setPlanInstance] = useState<ReactFlowInstance<PlanNode, PlanEdge> | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState(initialSelectedId);
  const [planReady, setPlanReady] = useState(false);
  const [undoStack, setUndoStack] = useState<PlanNode[][]>([]);
  const editSnapshot = useRef<PlanNode[] | null>(null);
  const editSnapshotRecorded = useRef(false);
  const previousLayout = useRef(compactLayout);
  const selectedNode = nodes.find((node) => node.id === selectedNodeId);
  const migrationOperation = nodes.find((node) => node.data.operation?.id === "migration.set_target_java")?.data.operation;
  const targetJava: JavaTarget = migrationOperation?.id === "migration.set_target_java" ? migrationOperation.parameters.targetJava : "16";
  const decompositionOperation = nodes.find((node) => node.data.operation?.id === "decomposition.extract_modules")?.data.operation;
  const moduleBoundaries = scenario.moduleBoundaries ?? [];
  const selectedModuleIds = decompositionOperation?.id === "decomposition.extract_modules"
    ? decompositionOperation.parameters.modules
    : [];
  const decompositionMatchesFixture = moduleBoundaries.length > 0
    && selectedModuleIds.length === moduleBoundaries.length
    && moduleBoundaries.every((module) => selectedModuleIds.includes(module.id));
  const scenarioTitle = scenario.kind === "java-migration" ? `Миграция на Java ${targetJava}` : scenario.title;
  const isDirty = nodes.some((node) => {
    const original = initial.nodes.find((item) => item.id === node.id);
    return !original
      || node.position.x !== original.position.x
      || node.position.y !== original.position.y
      || node.data.label !== original.data.label
      || JSON.stringify(node.data.operation) !== JSON.stringify(original.data.operation);
  }) || nodes.length !== initial.nodes.length;
  const displayNodes = useMemo(() => nodes.map((node) => ({
    ...node,
    data: {
      ...node.data,
      ...(scenario.kind === "java-migration" && node.id === "compile" ? { label: `Собрать на Java ${targetJava}` } : {}),
      selected: node.id === selectedNodeId,
      selectStep: () => setSelectedNodeId(node.id),
    },
  })), [nodes, scenario.kind, selectedNodeId, targetJava]);

  useEffect(() => {
    if (previousLayout.current === compactLayout) return;
    previousLayout.current = compactLayout;
    const layoutNodes = new Map(initial.nodes.map((node) => [node.id, node]));
    const applyLayout = (current: PlanNode[]) => current.map((node) => {
      const layout = layoutNodes.get(node.id);
      return layout ? { ...node, position: layout.position, width: layout.width, height: layout.height } : node;
    });
    setNodes(applyLayout);
    setUndoStack((current) => current.map(applyLayout));
  }, [compactLayout, initial.nodes, setNodes]);

  useEffect(() => {
    if (!compactLayout || !planInstance) return;
    const frame = window.requestAnimationFrame(() => {
      void planInstance.fitView({ padding: 0.08, minZoom: 0.82, maxZoom: 1 });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [compactLayout, initial.nodes, planInstance]);

  function handleNodeChanges(changes: NodeChange<PlanNode>[]) {
    setNodes((current) => applyNodeChanges(changes, current) as PlanNode[]);
  }

  function recordUndo() {
    setUndoStack((current) => [...current, nodes].slice(-30));
  }

  function beginFieldEdit() {
    editSnapshot.current = nodes;
    editSnapshotRecorded.current = false;
  }

  function recordFieldEdit() {
    if (editSnapshotRecorded.current) return;
    setUndoStack((current) => [...current, editSnapshot.current ?? nodes].slice(-30));
    editSnapshotRecorded.current = true;
  }

  function endFieldEdit() {
    editSnapshot.current = null;
    editSnapshotRecorded.current = false;
  }

  function undoPlanEdit() {
    const previous = undoStack.at(-1);
    if (!previous) return;
    setNodes(previous);
    setUndoStack((current) => current.slice(0, -1));
  }

  function resetPlan() {
    setNodes(initial.nodes);
    setUndoStack([]);
    endFieldEdit();
    setPlanReady(false);
  }

  function renameSelectedNode(label: string) {
    setNodes((current) => current.map((node) => node.id === selectedNodeId
      ? { ...node, data: { ...node.data, label } }
      : node));
  }

  function updateOperation(operation: ModernizationOperation) {
    if (!selectedNode) return;
    setNodes((current) => current.map((node) => node.id === selectedNodeId
      ? { ...node, data: { ...node.data, operation } }
      : node));
  }

  function toggleModule(moduleId: ModernizationModuleId, checked: boolean) {
    if (decompositionOperation?.id !== "decomposition.extract_modules") return;
    const selected = new Set(decompositionOperation.parameters.modules);
    if (checked) selected.add(moduleId);
    else if (selected.size > 1) selected.delete(moduleId);
    updateOperation({
      id: "decomposition.extract_modules",
      parameters: { modules: moduleBoundaries.filter((module) => selected.has(module.id)).map((module) => module.id) },
    });
  }

  const preview = useMemo(() => {
    const operation = nodes.find((node) => node.data.operation)?.data.operation;
    if (operation?.id === "migration.set_target_java") {
      return {
        ...scenario.preview,
        files: scenario.preview.files.map((file) => file.path === "pom.xml"
          ? { ...file, after: file.after.replace("<release>16</release>", `<release>${operation.parameters.targetJava}</release>`) }
          : file),
      };
    }
    return scenario.preview;
  }, [nodes, scenario.preview]);

  return (
    <div className="modernization-plan">
      <div className="modernization-plan__bar">
        <button type="button" onClick={onBack}><ArrowLeft size={14} /> К вариантам</button>
        <div><small>РЕДАКТИРУЕМЫЙ ПЛАН · DEMO</small><strong>{scenarioTitle}</strong></div>
        <div className="modernization-plan__edit-actions">
          <button type="button" onClick={undoPlanEdit} disabled={!undoStack.length} aria-label="Отменить последнее изменение плана"><Undo2 size={13} /> Отменить</button>
          <button type="button" onClick={resetPlan} disabled={!isDirty} aria-label="Сбросить изменения плана"><RotateCcw size={13} /> Сбросить</button>
        </div>
        <button type="button" className="modernization-plan__primary" onClick={() => setPlanReady(true)}><WandSparkles size={14} /> Показать состав плана</button>
      </div>

      <div className="modernization-plan__canvas" style={compactLayout ? { height: `${Math.max(760, initial.height + 36)}px` } : undefined}>
        <ReactFlow
          nodes={displayNodes}
          edges={edges}
          nodeTypes={planNodeTypes}
          onNodesChange={handleNodeChanges}
          onNodeDragStart={recordUndo}
          nodesFocusable={false}
          edgesFocusable={false}
          disableKeyboardA11y
          nodesDraggable
          nodesConnectable={false}
          elementsSelectable={false}
          minZoom={0.2}
          maxZoom={1.3}
          panOnScroll
          zoomOnDoubleClick={false}
          fitView
          fitViewOptions={{ padding: compactLayout ? 0.08 : 0.1, minZoom: compactLayout ? 0.82 : 0.68, maxZoom: compactLayout ? 1 : 0.92 }}
          onInit={setPlanInstance}
          aria-label={`Редактируемый план: ${scenarioTitle}`}
        >
          <Background color="#d0d6cd" gap={24} size={1} />
          <Controls showInteractive={false} />
          <MiniMap position="top-right" pannable zoomable nodeColor={(node) => node.id === selectedNodeId ? "#d7ee72" : "#91a18e"} />
        </ReactFlow>
      </div>

      <aside className="modernization-plan__inspector">
        <div className="modernization-plan__step-copy">
          <small>{selectedNode?.data.operation ? "ШАГ ТРАНСФОРМАЦИИ" : "ШАГ ПЛАНА · можно перетаскивать"}</small>
          <input name="plan-step-title" aria-label="Название шага плана" value={selectedNode?.data.label ?? ""} onFocus={beginFieldEdit} onBlur={endFieldEdit} onChange={(event) => { recordFieldEdit(); renameSelectedNode(event.target.value); }} />
          <p>{selectedNode?.data.detail ?? "Выберите блок на схеме."}</p>
          {selectedNode?.data.operation?.id === "migration.set_target_java" ? (
            <label className="modernization-plan__parameter">
              <span>Целевая версия Java</span>
              <select
                name="plan-target-java"
                aria-label="Целевая версия Java"
                value={selectedNode.data.operation.parameters.targetJava}
                onFocus={beginFieldEdit}
                onBlur={endFieldEdit}
                onChange={(event) => {
                  recordFieldEdit();
                  updateOperation({ id: "migration.set_target_java", parameters: { targetJava: event.target.value as JavaTarget } });
                }}
              >
                <option value="11">Java 11</option>
                <option value="16">Java 16</option>
                <option value="17">Java 17</option>
                <option value="21">Java 21</option>
              </select>
            </label>
          ) : null}
          {selectedNode?.data.operation?.id === "decomposition.extract_modules" ? (
            <div className="modernization-plan__modules">
              <strong>Границы декомпозиции</strong>
              <ul>{moduleBoundaries.map((module) => {
                const checked = selectedModuleIds.includes(module.id);
                return <li key={module.id}><label>
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={checked && selectedModuleIds.length === 1}
                    onFocus={beginFieldEdit}
                    onBlur={endFieldEdit}
                    onChange={(event) => {
                      recordFieldEdit();
                      toggleModule(module.id, event.target.checked);
                    }}
                  />
                  <span>{module.label}</span>
                </label></li>;
              })}</ul>
              <small>Выберите хотя бы один модуль. Пример — Java-классы в одном процессе, не микросервисы.</small>
            </div>
          ) : null}
        </div>
        <span className="modernization-plan__mapping"><GitBranch size={14} />
          {selectedNode?.data.operation
            ? <>Операция демо: <code>{selectedNode.data.operation.id}</code><small>{operationParameterSummary(selectedNode.data.operation)}</small></>
            : "Контрольный шаг · без изменения исходников"}
        </span>
      </aside>

      {planReady ? (
        <section className="modernization-plan__preview" aria-live="polite">
          <div><strong>Пример состава изменений</strong><span>Demo · не diff проекта · Java-код не менялся · файлов: {scenario.kind === "decomposition" && !decompositionMatchesFixture ? "не рассчитан" : preview.files.length}</span></div>
          <ol>{displayNodes.map((node) => <li key={node.id}>
            <span>{node.data.label}</span>
            {node.data.operation ? <><code>{node.data.operation.id}</code><code>{operationParameterSummary(node.data.operation)}</code></> : null}
          </li>)}</ol>
          {scenario.kind === "decomposition" && !decompositionMatchesFixture ? (
            <p className="modernization-plan__preview-note" role="status">В демо нет diff для выбранного набора границ. Реальный diff должен вернуть backend после анализа и применения операции.</p>
          ) : (
            <div className="modernization-demo-diff">
              {preview.files.map((file) => (
                <article className="modernization-demo-diff__file" key={file.path} aria-label={`Изменение файла ${file.path}`}>
                  <header><code>{file.path}</code><span>{file.change === "added" ? "Новый файл" : "Изменён"}</span></header>
                  <div className="modernization-demo-diff__pair">
                    <section><small>До</small><pre>{file.before}</pre></section>
                    <section><small>После</small><pre>{file.after}</pre></section>
                  </div>
                </article>
              ))}
            </div>
          )}
          <p className="modernization-demo-metric"><Gauge size={14} /> {preview.metric}</p>
          <div className="modernization-plan__checks">
            <span><Code2 size={14} /> Сборка <small>{scenario.kind === "java-migration" ? `Java ${targetJava} · ждёт backend` : "ожидает backend"}</small></span>
            <span><ShieldCheck size={14} /> Тесты поведения <small>ожидают backend</small></span>
            <span><Gauge size={14} /> Метрики до/после <small>ожидают backend</small></span>
          </div>
        </section>
      ) : null}

      <p className="modernization-plan__notice"><CircleHelp size={13} /> Параметры трансформации меняют локальный preview. Перетаскивание меняет только расположение; Java не преобразуется без backend transform API.</p>
    </div>
  );
}

export function ModernizationDemo({ catalog, onBackToFlows }: { catalog: ModernizationCatalog; onBackToFlows: () => void }) {
  const [selectedScenarioId, setSelectedScenarioId] = useState<string | null>(null);
  const [planOpen, setPlanOpen] = useState(false);
  const [compactLayout, setCompactLayout] = useState(false);
  const scenario = catalog.scenarios.find((item) => item.id === selectedScenarioId) ?? catalog.scenarios[0];

  useEffect(() => {
    const media = window.matchMedia("(max-width: 760px)");
    const updateLayout = () => setCompactLayout(media.matches);
    updateLayout();
    media.addEventListener("change", updateLayout);
    return () => media.removeEventListener("change", updateLayout);
  }, []);

  if (!scenario) {
    return (
      <section className="modernization-demo" aria-label="Рекомендации по модернизации">
        <header className="modernization-demo__header"><div><small>РЕКОМЕНДАЦИИ</small><h2>Пока нет предложений</h2><p>После анализа проекта здесь появятся миграции и улучшения с обоснованием.</p></div><button type="button" onClick={onBackToFlows}><ArrowLeft size={14} /> К блок-схемам</button></header>
      </section>
    );
  }

  const ScenarioIcon = scenarioIcons[scenario.kind];
  if (planOpen) return <EditablePlan key={scenario.id} scenario={scenario} onBack={() => setPlanOpen(false)} compactLayout={compactLayout} />;

  return (
    <section className="modernization-demo" aria-label="Демонстрация модернизации проекта">
      <header className="modernization-demo__header">
        <div><small><WandSparkles size={13} /> РЕКОМЕНДАЦИИ · DEMO</small><h2>Что улучшить в проекте?</h2><p>Выберите действие, чтобы посмотреть план. Демонстрационные варианты не рассчитаны по открытому коду.</p></div>
        <button type="button" onClick={onBackToFlows}><ArrowLeft size={14} /> К блок-схемам</button>
      </header>

      <div className="modernization-options">
        {catalog.scenarios.map((item) => {
          const Icon = scenarioIcons[item.kind];
          return (
            <button className={`modernization-option${item.id === scenario.id ? " modernization-option--selected" : ""}`} type="button" key={item.id} onClick={() => setSelectedScenarioId(item.id)} aria-pressed={item.id === scenario.id}>
              <span><Icon size={16} /></span><strong>{item.shortTitle}</strong><small>{item.scope.split(" · ")[0]}</small>
            </button>
          );
        })}
      </div>

      <article className="modernization-recommendation">
        <div className="modernization-recommendation__title"><span><ScenarioIcon size={18} /></span><div><small>ПРЕДЛОЖЕНИЕ · НЕ ПРИМЕНЕНО</small><h3>{scenario.title}</h3></div></div>
        <p>{scenario.summary}</p>
        <div className="modernization-recommendation__scope"><GitBranch size={14} /> {scenario.scope}</div>
        <div className="modernization-recommendation__body">
          <div><strong>Почему это предложено</strong><p>{scenario.rationale}</p></div>
          <div><strong>Ожидаемые области изменений</strong><ul>{scenario.expectedChanges.map((change) => <li key={change}>{change}</li>)}</ul></div>
        </div>
        <button className="modernization-recommendation__cta" type="button" onClick={() => setPlanOpen(true)}><span>Подготовить схему изменений</span><ArrowRight size={16} /></button>
      </article>

      <p className="modernization-demo__notice"><CircleHelp size={13} /> Локальная демонстрация. Источник, зависимости и результаты проверок здесь не анализируются.</p>
    </section>
  );
}
