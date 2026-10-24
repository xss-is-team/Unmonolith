"use client";

import dynamic from "next/dynamic";
import { useMemo, useRef, useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  Boxes,
  CircleHelp,
  Code2,
  GitBranch,
  Gauge,
  RotateCcw,
  ShieldCheck,
  Undo2,
  WandSparkles,
  ZoomIn,
  ZoomOut,
  Maximize2,
  Download,
  PanelsLeftBottom,
} from "lucide-react";
import type {
  JavaTarget,
  ModernizationCatalog,
  ModernizationOperation,
  ModernizationModuleId,
  ModernizationPlanStep,
  ModernizationScenario,
  ModernizationScenarioKind,
} from "@/lib/modernization-model";
import type { BpmnPlanEditorHandle } from "@/components/bpmn-plan-editor";

const BpmnPlanEditor = dynamic(
  () => import("@/components/bpmn-plan-editor").then((module) => module.BpmnPlanEditor),
  { ssr: false, loading: () => <div className="bpmn-plan-loading">Загружаем BPMN 2.0…</div> },
);

const scenarioIcons: Record<ModernizationScenarioKind, typeof Code2> = {
  "java-migration": Code2,
  decomposition: Boxes,
  optimization: Gauge,
};

function operationParameterSummary(operation: ModernizationOperation) {
  switch (operation.id) {
    case "migration.set_target_java": return `targetJava=${operation.parameters.targetJava}`;
    case "decomposition.extract_modules": return `modules=${operation.parameters.modules.join(",") || "none"}`;
    case "optimization.index_by_key": return `key=${operation.parameters.key}`;
  }
}

function EditablePlan({ scenario, onBack }: { scenario: ModernizationScenario; onBack: () => void }) {
  const initialSelectedId = scenario.steps.find((step) => step.kind === "call")?.id ?? scenario.steps[1]?.id ?? "";
  const editorRef = useRef<BpmnPlanEditorHandle | null>(null);
  const [steps, setSteps] = useState(scenario.steps);
  const [selectedStepId, setSelectedStepId] = useState(initialSelectedId);
  const [planReady, setPlanReady] = useState(false);
  const [diagramDirty, setDiagramDirty] = useState(false);
  const [canUndo, setCanUndo] = useState(false);
  const [resetKey, setResetKey] = useState(0);
  const [exportError, setExportError] = useState("");
  const [paletteOpen, setPaletteOpen] = useState(false);
  const selectedStep = steps.find((step) => step.id === selectedStepId);
  const migrationOperation = steps.find((step) => step.operation?.id === "migration.set_target_java")?.operation;
  const targetJava: JavaTarget = migrationOperation?.id === "migration.set_target_java" ? migrationOperation.parameters.targetJava : "16";
  const decompositionOperation = steps.find((step) => step.operation?.id === "decomposition.extract_modules")?.operation;
  const moduleBoundaries = scenario.moduleBoundaries ?? [];
  const selectedModuleIds = decompositionOperation?.id === "decomposition.extract_modules"
    ? decompositionOperation.parameters.modules
    : [];
  const decompositionMatchesFixture = moduleBoundaries.length > 0
    && selectedModuleIds.length === moduleBoundaries.length
    && moduleBoundaries.every((module) => selectedModuleIds.includes(module.id));
  const scenarioTitle = scenario.kind === "java-migration" ? `Миграция на Java ${targetJava}` : scenario.title;
  const isDirty = diagramDirty || JSON.stringify(steps) !== JSON.stringify(scenario.steps);

  function undoPlanEdit() {
    editorRef.current?.undo();
  }

  function handleStepsChange(nextSteps: ModernizationPlanStep[]) {
    setSteps(nextSteps);
    if (!nextSteps.some((step) => step.id === selectedStepId)) setSelectedStepId(nextSteps[0]?.id ?? "");
  }

  function resetPlan() {
    setSteps(scenario.steps);
    setSelectedStepId(initialSelectedId);
    setDiagramDirty(false);
    setCanUndo(false);
    setPlanReady(false);
    setResetKey((current) => current + 1);
  }

  function changeStep(update: Partial<ModernizationPlanStep>) {
    if (!selectedStep) return;
    editorRef.current?.updateStep({ ...selectedStep, ...update });
  }

  function updateOperation(operation: ModernizationOperation) {
    changeStep({ operation });
  }

  async function exportBpmnXml() {
    try {
      const xml = await editorRef.current?.exportXml();
      if (!xml) return;
      const url = URL.createObjectURL(new Blob([xml], { type: "application/bpmn+xml;charset=utf-8" }));
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `${scenario.id}.bpmn`;
      anchor.click();
      URL.revokeObjectURL(url);
      setExportError("");
    } catch {
      setExportError("Не удалось экспортировать BPMN XML.");
    }
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
    const operation = steps.find((step) => step.operation)?.operation;
    if (operation?.id === "migration.set_target_java") {
      return {
        ...scenario.preview,
        files: scenario.preview.files.map((file) => file.path === "pom.xml"
          ? { ...file, after: file.after.replace("<release>16</release>", `<release>${operation.parameters.targetJava}</release>`) }
          : file),
      };
    }
    return scenario.preview;
  }, [scenario.preview, steps]);

  return (
    <div className="modernization-plan">
      <div className="modernization-plan__bar">
        <button type="button" onClick={onBack}><ArrowLeft size={14} /> К вариантам</button>
        <div><small>РЕДАКТИРУЕМЫЙ ПЛАН · DEMO</small><strong>{scenarioTitle}</strong></div>
        <div className="modernization-plan__edit-actions">
          <button type="button" onClick={undoPlanEdit} disabled={!canUndo} aria-label="Отменить последнее изменение плана"><Undo2 size={13} /> Отменить</button>
          <button type="button" onClick={resetPlan} disabled={!isDirty} aria-label="Сбросить изменения плана"><RotateCcw size={13} /> Сбросить</button>
        </div>
        <button type="button" className="modernization-plan__primary" onClick={() => setPlanReady(true)}><WandSparkles size={14} /> Показать состав плана</button>
      </div>

      <div className="modernization-plan__canvas modernization-plan__canvas--bpmn">
        <div className="bpmn-plan-toolbar" aria-label="Управление BPMN-диаграммой">
          <span>BPMN 2.0 · DEMO</span>
          <button type="button" onClick={() => editorRef.current?.fitView()} aria-label="Показать всю диаграмму"><Maximize2 size={14} /></button>
          <button type="button" onClick={() => editorRef.current?.zoomOut()} aria-label="Уменьшить диаграмму"><ZoomOut size={14} /></button>
          <button type="button" onClick={() => editorRef.current?.zoomIn()} aria-label="Увеличить диаграмму"><ZoomIn size={14} /></button>
          <button type="button" onClick={() => setPaletteOpen((open) => !open)} aria-label="Показать палитру BPMN" aria-pressed={paletteOpen}><PanelsLeftBottom size={14} /></button>
          <button type="button" onClick={() => void exportBpmnXml()} aria-label="Скачать BPMN 2.0 XML"><Download size={14} /></button>
        </div>
        <BpmnPlanEditor
          key={`${scenario.id}:${resetKey}`}
          scenario={scenario}
          steps={steps}
          selectedStepId={selectedStepId}
          paletteOpen={paletteOpen}
          onReady={(handle) => { editorRef.current = handle; }}
          onSelectStep={setSelectedStepId}
          onStepsChange={handleStepsChange}
          onDirtyChange={setDiagramDirty}
          onUndoChange={setCanUndo}
        />
      </div>
      {exportError ? <p className="bpmn-plan-error" role="alert">{exportError}</p> : null}

      <aside className="modernization-plan__inspector">
        <div className="modernization-plan__step-copy">
          <small>{selectedStep?.operation ? "ШАГ ТРАНСФОРМАЦИИ" : "ШАГ BPMN · можно перемещать"}</small>
          <input name="plan-step-title" aria-label="Название шага плана" value={selectedStep?.label ?? ""} onChange={(event) => changeStep({ label: event.target.value })} />
          <p>{selectedStep?.detail ?? "Выберите блок на BPMN-схеме."}</p>
          {selectedStep?.operation?.id === "migration.set_target_java" ? (
            <label className="modernization-plan__parameter">
              <span>Целевая версия Java</span>
              <select
                name="plan-target-java"
                aria-label="Целевая версия Java"
                value={selectedStep.operation.parameters.targetJava}
                onChange={(event) => {
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
          {selectedStep?.operation?.id === "decomposition.extract_modules" ? (
            <div className="modernization-plan__modules">
              <strong>Границы декомпозиции</strong>
              <ul>{moduleBoundaries.map((module) => {
                const checked = selectedModuleIds.includes(module.id);
                return <li key={module.id}><label>
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={checked && selectedModuleIds.length === 1}
                    onChange={(event) => {
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
          {selectedStep?.operation
            ? <>Операция демо: <code>{selectedStep.operation.id}</code><small>{operationParameterSummary(selectedStep.operation)}</small></>
            : "Контрольный шаг · без изменения исходников"}
        </span>
      </aside>

      {planReady ? (
        <section className="modernization-plan__preview" aria-live="polite">
          <div><strong>Пример состава изменений</strong><span>Demo · не diff проекта · Java-код не менялся · файлов: {scenario.kind === "decomposition" && !decompositionMatchesFixture ? "не рассчитан" : preview.files.length}</span></div>
          <ol>{steps.map((step) => <li key={step.id}>
            <span>{step.label}</span>
            {step.operation ? <><code>{step.operation.id}</code><code>{operationParameterSummary(step.operation)}</code></> : null}
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
  const scenario = catalog.scenarios.find((item) => item.id === selectedScenarioId) ?? catalog.scenarios[0];

  if (!scenario) {
    return (
      <section className="modernization-demo" aria-label="Рекомендации по модернизации">
        <header className="modernization-demo__header"><div><small>РЕКОМЕНДАЦИИ</small><h2>Пока нет предложений</h2><p>После анализа проекта здесь появятся миграции и улучшения с обоснованием.</p></div><button type="button" onClick={onBackToFlows}><ArrowLeft size={14} /> К блок-схемам</button></header>
      </section>
    );
  }

  const ScenarioIcon = scenarioIcons[scenario.kind];
  if (planOpen) return <EditablePlan key={scenario.id} scenario={scenario} onBack={() => setPlanOpen(false)} />;

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
