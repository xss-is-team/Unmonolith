"use client";

import { useEffect, useRef } from "react";
import dagre from "@dagrejs/dagre";
import BpmnModeler from "bpmn-js/lib/Modeler";
import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn.css";
import type { ModernizationOperation, ModernizationPlanStep, ModernizationScenario } from "@/lib/modernization-model";

type BpmnElement = {
  id: string;
  type: string;
  businessObject: {
    id: string;
    name?: string;
    extensionElements?: { values: Array<{ $instanceOf?: (type: string) => boolean; operationId?: string; parametersJson?: string }> };
  };
};

export type BpmnPlanEditorHandle = {
  updateStep: (step: ModernizationPlanStep) => void;
  undo: () => void;
  zoomIn: () => void;
  zoomOut: () => void;
  fitView: () => void;
  exportXml: () => Promise<string>;
};

const blueprintsModdle = {
  name: "Blueprints",
  uri: "https://java-blueprints.dev/schema/bpmn",
  prefix: "bp",
  xml: { tagAlias: "lowerCase" },
  types: [{
    name: "Operation",
    superClass: ["Element"],
    properties: [
      { name: "operationId", isAttr: true, type: "String" },
      { name: "parametersJson", isAttr: true, type: "String" },
    ],
  }],
};

const operationIds = new Set([
  "migration.set_target_java",
  "decomposition.extract_modules",
  "optimization.index_by_key",
]);

function escapeXml(value: string) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&apos;");
}

function safeId(value: string) {
  return value.replace(/[^A-Za-z0-9_]/g, "_");
}

function shapeSize(step: ModernizationPlanStep) {
  if (step.kind === "start" || step.kind === "end") return { width: 40, height: 40 };
  if (step.kind === "gateway") return { width: 58, height: 58 };
  return { width: 184, height: 86 };
}

function operationXml(operation: ModernizationOperation | undefined) {
  if (!operation) return "";
  return `<bpmn:extensionElements><bp:Operation operationId="${escapeXml(operation.id)}" parametersJson="${escapeXml(JSON.stringify(operation.parameters))}" /></bpmn:extensionElements>`;
}

function createBpmnXml(scenario: ModernizationScenario, steps: ModernizationPlanStep[], compact: boolean) {
  const graph = new dagre.graphlib.Graph({ multigraph: true });
  graph.setGraph({
    rankdir: compact ? "TB" : "LR",
    nodesep: compact ? 24 : 44,
    ranksep: compact ? 34 : 52,
    marginx: compact ? 36 : 54,
    marginy: compact ? 42 : 54,
  });
  graph.setDefaultEdgeLabel(() => ({}));
  for (const step of steps) graph.setNode(step.id, shapeSize(step));
  scenario.edges.forEach((edge, index) => {
    if (graph.hasNode(edge.source) && graph.hasNode(edge.target)) graph.setEdge(edge.source, edge.target, {}, `flow_${index}`);
  });
  dagre.layout(graph);

  const nodeXml = steps.map((step) => {
    const id = `Step_${safeId(step.id)}`;
    const name = ` name="${escapeXml(step.label)}"`;
    const operation = operationXml(step.operation);
    if (step.kind === "start") return `<bpmn:startEvent id="${id}"${name}/>`;
    if (step.kind === "end") return `<bpmn:endEvent id="${id}"${name}/>`;
    if (step.kind === "gateway") return `<bpmn:exclusiveGateway id="${id}"${name}/>`;
    if (step.kind === "call") return `<bpmn:serviceTask id="${id}"${name}>${operation}</bpmn:serviceTask>`;
    return `<bpmn:task id="${id}"${name}/>`;
  }).join("\n");

  const flowXml = scenario.edges.flatMap((edge, index) => {
    if (!graph.hasNode(edge.source) || !graph.hasNode(edge.target)) return [];
    const id = `Flow_${index + 1}`;
    const name = edge.label ? ` name="${escapeXml(edge.label)}"` : "";
    return [`<bpmn:sequenceFlow id="${id}" sourceRef="Step_${safeId(edge.source)}" targetRef="Step_${safeId(edge.target)}"${name}/>`];
  }).join("\n");

  const shapeXml = steps.map((step) => {
    const id = `Step_${safeId(step.id)}`;
    const size = shapeSize(step);
    const node = graph.node(step.id);
    const x = Math.round((node?.x ?? 0) - size.width / 2);
    const y = Math.round((node?.y ?? 0) - size.height / 2);
    const shape = `<bpmndi:BPMNShape id="${id}_di" bpmnElement="${id}"><dc:Bounds x="${x}" y="${y}" width="${size.width}" height="${size.height}" /></bpmndi:BPMNShape>`;
    return shape;
  }).join("\n");

  const edgeXml = scenario.edges.flatMap((edge, index) => {
    if (!graph.hasNode(edge.source) || !graph.hasNode(edge.target)) return [];
    const points = graph.edge(edge.source, edge.target, `flow_${index}`)?.points ?? [];
    const waypoints = points.map((point: { x: number; y: number }) => `<di:waypoint x="${Math.round(point.x)}" y="${Math.round(point.y)}" />`).join("");
    return [`<bpmndi:BPMNEdge id="Flow_${index + 1}_di" bpmnElement="Flow_${index + 1}">${waypoints}</bpmndi:BPMNEdge>`];
  }).join("\n");

  return `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:bp="${blueprintsModdle.uri}" id="Definitions_${safeId(scenario.id)}" targetNamespace="https://java-blueprints.dev/bpmn">
  <bpmn:process id="Process_${safeId(scenario.id)}" name="${escapeXml(scenario.kind === "java-migration" ? "План миграции Java" : scenario.title)}" isExecutable="false">
    ${nodeXml}
    ${flowXml}
  </bpmn:process>
  <bpmndi:BPMNDiagram id="Diagram_${safeId(scenario.id)}"><bpmndi:BPMNPlane id="Plane_${safeId(scenario.id)}" bpmnElement="Process_${safeId(scenario.id)}">
    ${shapeXml}
    ${edgeXml}
  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</bpmn:definitions>`;
}

function operationFrom(element: BpmnElement): ModernizationOperation | undefined {
  const extension = element.businessObject.extensionElements?.values.find((item) => item.$instanceOf?.("bp:Operation"));
  if (!extension?.operationId || !operationIds.has(extension.operationId) || !extension.parametersJson) return undefined;
  try {
    const parameters = JSON.parse(extension.parametersJson) as Record<string, unknown>;
    if (extension.operationId === "migration.set_target_java" && ["11", "16", "17", "21"].includes(String(parameters.targetJava))) {
      return { id: "migration.set_target_java", parameters: { targetJava: String(parameters.targetJava) as "11" | "16" | "17" | "21" } };
    }
    if (extension.operationId === "decomposition.extract_modules" && Array.isArray(parameters.modules) && parameters.modules.every((id) => typeof id === "string")) {
      return { id: "decomposition.extract_modules", parameters: { modules: parameters.modules as string[] } };
    }
    if (extension.operationId === "optimization.index_by_key" && parameters.key === "Order.id") {
      return { id: "optimization.index_by_key", parameters: { key: "Order.id" } };
    }
  } catch {
    return undefined;
  }
  return undefined;
}

function extractSteps(modeler: BpmnModeler, previous: ModernizationPlanStep[]) {
  const known = new Map(previous.map((step) => [`Step_${safeId(step.id)}`, step]));
  const registry = modeler.get("elementRegistry") as { getAll: () => BpmnElement[] };
  return registry.getAll().flatMap((element): ModernizationPlanStep[] => {
    const type = element.type;
    if (!type.startsWith("bpmn:") || type === "bpmn:SequenceFlow" || type === "bpmn:Process" || type === "bpmn:Participant" || type === "bpmn:Collaboration") return [];
    const businessObject = element.businessObject;
    const id = businessObject.id.startsWith("Step_") ? businessObject.id.slice(5) : businessObject.id;
    const old = known.get(businessObject.id);
    const kind: ModernizationPlanStep["kind"] = type === "bpmn:StartEvent" ? "start"
      : type === "bpmn:EndEvent" ? "end"
        : type.endsWith("Gateway") ? "gateway"
          : type === "bpmn:ServiceTask" ? "call" : "task";
    return [{
      id,
      label: businessObject.name ?? old?.label ?? "Новый шаг",
      kind,
      detail: old?.detail ?? "Добавленный BPMN-элемент. Для него ещё не настроена операция изменения кода.",
      operation: operationFrom(element),
    }];
  });
}

function updateModelerStep(modeler: BpmnModeler, step: ModernizationPlanStep) {
  const registry = modeler.get("elementRegistry") as { get: (id: string) => BpmnElement | undefined };
  const element = registry.get(`Step_${safeId(step.id)}`) ?? registry.get(step.id);
  if (!element) return;
  const moddle = modeler.get("moddle") as { create: (type: string, properties?: Record<string, unknown>) => { values?: unknown[] } };
  const modeling = modeler.get("modeling") as { updateProperties: (element: BpmnElement, properties: Record<string, unknown>) => void };
  const current = element.businessObject.extensionElements?.values ?? [];
  const values = current.filter((item) => !item.$instanceOf?.("bp:Operation"));
  if (step.operation) values.push(moddle.create("bp:Operation", {
    operationId: step.operation.id,
    parametersJson: JSON.stringify(step.operation.parameters),
  }) as NonNullable<BpmnElement["businessObject"]["extensionElements"]>["values"][number]);
  const extensionElements = values.length ? moddle.create("bpmn:ExtensionElements", { values }) : undefined;
  modeling.updateProperties(element, { name: step.label, extensionElements });
}

export function BpmnPlanEditor({ scenario, steps, selectedStepId, paletteOpen, onSelectStep, onStepsChange, onDirtyChange, onUndoChange, onReady }: {
  scenario: ModernizationScenario;
  steps: ModernizationPlanStep[];
  selectedStepId: string;
  paletteOpen: boolean;
  onSelectStep: (stepId: string) => void;
  onStepsChange: (steps: ModernizationPlanStep[]) => void;
  onDirtyChange: (dirty: boolean) => void;
  onUndoChange: (canUndo: boolean) => void;
  onReady: (handle: BpmnPlanEditorHandle | null) => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const stepsRef = useRef(steps);
  const selectedStepIdRef = useRef(selectedStepId);
  const baselineRef = useRef("");
  const callbacksRef = useRef({ onDirtyChange, onSelectStep, onStepsChange, onUndoChange, onReady });

  useEffect(() => {
    stepsRef.current = steps;
    selectedStepIdRef.current = selectedStepId;
    callbacksRef.current = { onDirtyChange, onSelectStep, onStepsChange, onUndoChange, onReady };
  }, [steps, selectedStepId, onDirtyChange, onSelectStep, onStepsChange, onUndoChange, onReady]);

  useEffect(() => {
    if (!containerRef.current) return;
    const modeler = new BpmnModeler({ container: containerRef.current, moddleExtensions: { bp: blueprintsModdle } });
    let disposed = false;
    let saveSequence = 0;
    let initialized = false;

    const updateUndoState = () => {
      const commandStack = modeler.get("commandStack") as { canUndo?: () => boolean };
      callbacksRef.current.onUndoChange(Boolean(commandStack.canUndo?.()));
    };
    const syncModel = async () => {
      if (!initialized) return;
      const sequence = ++saveSequence;
      const result = await modeler.saveXML({ format: true });
      if (disposed || sequence !== saveSequence) return;
      callbacksRef.current.onDirtyChange(result.xml !== baselineRef.current);
      callbacksRef.current.onStepsChange(extractSteps(modeler, stepsRef.current));
      updateUndoState();
    };

    const eventBus = modeler.get("eventBus") as { on: (event: string, listener: (event: { newSelection?: BpmnElement[] }) => void) => void };
    eventBus.on("selection.changed", (event) => {
      const element = event.newSelection?.find((candidate) => candidate.businessObject.id.startsWith("Step_"));
      if (element) callbacksRef.current.onSelectStep(element.businessObject.id.slice(5));
    });
    eventBus.on("commandStack.changed", () => { void syncModel(); });

    const compact = window.matchMedia("(max-width: 760px)").matches;
    void modeler.importXML(createBpmnXml(scenario, stepsRef.current, compact)).then(async () => {
      if (disposed) return;
      const result = await modeler.saveXML({ format: true });
      if (!result.xml) throw new Error("BPMN export returned no XML");
      baselineRef.current = result.xml;
      initialized = true;
      const canvas = modeler.get("canvas") as { zoom: (value: string) => void };
      canvas.zoom("fit-viewport");
      const registry = modeler.get("elementRegistry") as { get: (id: string) => BpmnElement | undefined };
      const initial = registry.get(`Step_${safeId(selectedStepIdRef.current)}`);
      if (initial) (modeler.get("selection") as { select: (element: BpmnElement) => void }).select(initial);
      callbacksRef.current.onReady({
        updateStep: (step) => updateModelerStep(modeler, step),
        undo: () => (modeler.get("commandStack") as { undo: () => void }).undo(),
        zoomIn: () => {
          const diagramCanvas = modeler.get("canvas") as { zoom: (value?: number | string) => number };
          diagramCanvas.zoom(diagramCanvas.zoom() + 0.1);
        },
        zoomOut: () => {
          const diagramCanvas = modeler.get("canvas") as { zoom: (value?: number | string) => number };
          diagramCanvas.zoom(diagramCanvas.zoom() - 0.1);
        },
        fitView: () => (modeler.get("canvas") as { zoom: (value: string) => void }).zoom("fit-viewport"),
        exportXml: async () => {
          const exported = await modeler.saveXML({ format: true });
          if (!exported.xml) throw new Error("BPMN export returned no XML");
          return exported.xml;
        },
      });
      updateUndoState();
    }).catch(() => {
      callbacksRef.current.onDirtyChange(false);
    });

    return () => {
      disposed = true;
      modeler.destroy();
      callbacksRef.current.onReady(null);
    };
  }, [scenario]);

  return <div className={`bpmn-plan-editor${paletteOpen ? " bpmn-plan-editor--palette-open" : ""}`} ref={containerRef} aria-label={`BPMN 2.0 план: ${scenario.title}`} />;
}
