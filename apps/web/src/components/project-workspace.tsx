"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import useSWR from "swr";
import { diffLines } from "diff";
import {
  AlertCircle,
  ArrowLeft,
  ArrowUpRight,
  Check,
  CircleDot,
  Clock3,
  Code2,
  GitCompareArrows,
  GitBranch,
  History,
  LoaderCircle,
  Network,
  Plus,
  RefreshCw,
  Save,
  ShieldCheck,
  Sparkles,
  TerminalSquare,
  Trash2,
  Waypoints,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Brand } from "@/components/brand";
import {
  analyzeProjectStream,
  ApiError,
  getProjectMethods,
  getProjectRevision,
  getProjectRevisions,
  getProjectWorkspace,
  MAX_SOURCE_BYTES,
  type AnalysisResult,
  type CfgMethod,
  type Diagnostic,
  type Project,
  type SourceRange,
  type VerificationReport,
  type VerifyTestCase,
  updateProjectSource,
  verifyRevisions,
} from "@/lib/api";

const NO_DIAGNOSTICS: Diagnostic[] = [];

const JavaEditor = dynamic(
  () => import("@/components/java-editor").then((module) => module.JavaEditor),
  { ssr: false, loading: () => <div className="editor-loading">Открываем редактор…</div> },
);

const CfgViewer = dynamic(
  () => import("@/components/cfg-viewer").then((module) => module.CfgViewer),
  { ssr: false, loading: () => <section className="cfg-panel cfg-panel--loading">Загружаем CFG…</section> },
);

const ProjectGraphViewer = dynamic(
  () => import("@/components/project-graph-viewer").then((module) => module.ProjectGraphViewer),
  { ssr: false, loading: () => <section className="project-graph-panel project-graph-panel--loading">Загружаем обзор проекта…</section> },
);

function projectLoadMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    return "Сессия не найдена. Вернитесь на главную и загрузите файл заново.";
  }
  if (error instanceof ApiError && error.code === "revision_conflict") {
    return "Исходник менялся во время загрузки. Обновите страницу.";
  }
  if (error instanceof ApiError && error.status === 404) {
    return "Проект не найден в этой гостевой сессии.";
  }
  return "Не удалось загрузить проект. Проверьте, что API запущен.";
}

export function ProjectWorkspace({ projectId }: { projectId: string }) {
  const { data, error, isLoading, mutate } = useSWR(
    `project:${projectId}`,
    () => getProjectWorkspace(projectId),
    { shouldRetryOnError: false, revalidateOnFocus: false },
  );

  if (isLoading || (!data && !error)) {
    return (
      <main className="workspace-screen">
        <div className="workspace-top"><Brand compact /><Link className="back-link" href="/"><ArrowLeft size={15} /> Все проекты</Link></div>
        <section className="load-state"><LoaderCircle className="spin" /><span>Загружаем проект…</span></section>
      </main>
    );
  }

  if (error || !data) {
    return (
      <main className="workspace-screen">
        <div className="workspace-top"><Brand compact /><Link className="back-link" href="/"><ArrowLeft size={15} /> Все проекты</Link></div>
        <section className="load-state"><AlertCircle /><h1>Не удалось открыть проект</h1><p>{projectLoadMessage(error)}</p><Button onClick={() => void mutate()}><RefreshCw /> Повторить</Button></section>
      </main>
    );
  }

  return (
    <WorkspaceEditor
      key={`${projectId}:${data.revision}`}
      projectId={projectId}
      project={data.project}
      initialSource={data.source}
      initialRevision={data.revision}
      reload={async () => { await mutate(); }}
    />
  );
}

function WorkspaceEditor({
  projectId,
  project: initialProject,
  initialSource,
  initialRevision,
  reload,
}: {
  projectId: string;
  project: Project;
  initialSource: string;
  initialRevision: number;
  reload: () => Promise<void>;
}) {
  const editorRef = useRef<import("@/components/java-editor").JavaEditorHandle>(null);
  const [project, setProject] = useState(initialProject);
  const [revision, setRevision] = useState(initialRevision);
  const revisionRef = useRef(initialRevision);
  const [savedSource, setSavedSource] = useState(initialSource);
  const [sourceStats, setSourceStats] = useState(() => sourceMetrics(initialSource));
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "conflict" | "error">("idle");
  const saveStateRef = useRef(saveState);
  const [saveError, setSaveError] = useState("");
  const saveInFlightRef = useRef(false);
  const autosaveTimerRef = useRef<number | undefined>(undefined);
  const saveSourceRef = useRef<() => Promise<void>>(async () => {});

  const [analysisState, setAnalysisState] = useState<"idle" | "loading" | "complete" | "unavailable" | "error">("idle");
  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null);
  const [methods, setMethods] = useState<CfgMethod[]>([]);
  const [analysisMessage, setAnalysisMessage] = useState("");
  const analysisInFlightRef = useRef(false);
  const analysisTimerRef = useRef<number | undefined>(undefined);
  const runAnalysisRef = useRef<() => Promise<void>>(async () => {});
  const rerunAnalysisRef = useRef(false);
  const initialAnalysisRef = useRef(false);

  const [viewMode, setViewMode] = useState<"source" | "project" | "cfg">("source");
  const [selectedSourceRange, setSelectedSourceRange] = useState<SourceRange | null>(null);
  const [selectedMethodSelector, setSelectedMethodSelector] = useState("");
  const [historyOpen, setHistoryOpen] = useState(false);
  const [verificationOpen, setVerificationOpen] = useState(false);
  const [selectedHistoryRevision, setSelectedHistoryRevision] = useState<number | null>(null);
  const [showRevisionDiff, setShowRevisionDiff] = useState(false);
  const [reloading, setReloading] = useState(false);

  const historyEnabled = historyOpen || verificationOpen;
  const {
    data: revisions,
    error: revisionsError,
    mutate: mutateRevisions,
  } = useSWR(
    historyEnabled ? `revisions:${projectId}:${revision}` : null,
    () => getProjectRevisions(projectId),
    { shouldRetryOnError: false, revalidateOnFocus: false },
  );
  const { data: historicalRevision, error: historicalRevisionError } = useSWR(
    historyOpen && selectedHistoryRevision !== null
      ? `revision:${projectId}:${selectedHistoryRevision}`
      : null,
    () => getProjectRevision(projectId, selectedHistoryRevision!),
    { shouldRetryOnError: false, revalidateOnFocus: false },
  );

  const [baselineRevision, setBaselineRevision] = useState(1);
  const [candidateRevision, setCandidateRevision] = useState(initialRevision);
  const [verificationCases, setVerificationCases] = useState<VerifyTestCase[]>([
    { name: "основной сценарий", stdin: "" },
  ]);
  const [verificationState, setVerificationState] = useState<"idle" | "running" | "complete" | "error">("idle");
  const [verificationReport, setVerificationReport] = useState<VerificationReport | null>(null);
  const [verificationError, setVerificationError] = useState("");

  const setSaveStatus = useCallback((next: typeof saveState) => {
    saveStateRef.current = next;
    setSaveState(next);
  }, []);

  const scheduleAnalysis = useCallback(() => {
    if (analysisTimerRef.current !== undefined) window.clearTimeout(analysisTimerRef.current);
    analysisTimerRef.current = window.setTimeout(() => {
      analysisTimerRef.current = undefined;
      if (analysisInFlightRef.current) {
        rerunAnalysisRef.current = true;
      } else {
        void runAnalysisRef.current();
      }
    }, 450);
  }, []);

  const scheduleAutosave = useCallback(() => {
    if (autosaveTimerRef.current !== undefined) window.clearTimeout(autosaveTimerRef.current);
    if (saveStateRef.current === "conflict") return;
    autosaveTimerRef.current = window.setTimeout(() => {
      autosaveTimerRef.current = undefined;
      void saveSourceRef.current();
    }, 500);
  }, []);

  const saveSource = useCallback(async () => {
    if (!dirtyRef.current || saveInFlightRef.current) return;
    const source = editorRef.current?.getValue() ?? initialSource;
    if (new TextEncoder().encode(source).byteLength > MAX_SOURCE_BYTES) {
      setSaveError("Исходник превышает лимит API — 2 МиБ.");
      setSaveStatus("error");
      return;
    }

    saveInFlightRef.current = true;
    setSaveStatus("saving");
    setSaveError("");
    try {
      const updated = await updateProjectSource(projectId, source, revisionRef.current);
      setProject(updated);
      revisionRef.current = updated.revision;
      setRevision(updated.revision);
      setCandidateRevision(updated.revision);
      setSavedSource(source);
      editorRef.current?.markSaved(source);
      setAnalysis(null);
      setMethods([]);
      setAnalysisState("idle");
      setSelectedSourceRange(null);
      setVerificationReport(null);
      setShowRevisionDiff(false);
      setSaveStatus("saved");
      void mutateRevisions();
      scheduleAnalysis();
      window.setTimeout(() => {
        if (saveStateRef.current === "saved") setSaveStatus("idle");
      }, 1800);
      if (editorRef.current?.getValue() !== source) scheduleAutosave();
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        setSaveStatus("conflict");
      } else {
        setSaveStatus("error");
        setSaveError(saveErrorMessage(cause));
      }
    } finally {
      saveInFlightRef.current = false;
    }
  }, [
    initialSource,
    mutateRevisions,
    projectId,
    scheduleAnalysis,
    scheduleAutosave,
    setSaveStatus,
  ]);

  useEffect(() => {
    saveSourceRef.current = saveSource;
  }, [saveSource]);

  const onEditorDirtyChange = useCallback((next: boolean) => {
    dirtyRef.current = next;
    setDirty(next);
    if (next) {
      setAnalysisState("idle");
      setSelectedSourceRange(null);
      if (saveStateRef.current === "error") setSaveStatus("idle");
      scheduleAutosave();
    } else if (autosaveTimerRef.current !== undefined) {
      window.clearTimeout(autosaveTimerRef.current);
      autosaveTimerRef.current = undefined;
    }
  }, [scheduleAutosave, setSaveStatus]);

  const runAnalysis = useCallback(async () => {
    if (dirtyRef.current || analysisInFlightRef.current) return;
    const requestedRevision = revisionRef.current;
    analysisInFlightRef.current = true;
    setAnalysisState("loading");
    setAnalysisMessage("Запускаем JDT-анализ…");
    try {
      const result = await analyzeProjectStream(projectId, (stage) => {
        setAnalysisMessage(analysisProgressLabel(stage));
      });
      if (dirtyRef.current || revisionRef.current !== requestedRevision || result.revision !== requestedRevision) {
        setAnalysisState("idle");
        if (!dirtyRef.current) rerunAnalysisRef.current = true;
        return;
      }
      const availableMethods = await getProjectMethods(projectId);
      if (availableMethods.revision !== requestedRevision) {
        throw new ApiError("Список методов относится к другой ревизии.", 409, "revision_conflict");
      }
      setAnalysis(result);
      setMethods(availableMethods.methods);
      setSelectedMethodSelector((current) => (
        availableMethods.methods.some((method) => method.selector === current)
          ? current
          : availableMethods.methods[0]?.selector ?? ""
      ));
      setAnalysisState("complete");
      setAnalysisMessage("");
      void mutateRevisions();
    } catch (cause) {
      if (dirtyRef.current || revisionRef.current !== requestedRevision) {
        setAnalysisState("idle");
        return;
      }
      if (cause instanceof ApiError && cause.code === "analyzer_unavailable") {
        setAnalysisState("unavailable");
      } else {
        setAnalysisState("error");
      }
      setAnalysisMessage(analysisErrorMessage(cause));
    } finally {
      analysisInFlightRef.current = false;
      if (rerunAnalysisRef.current && !dirtyRef.current) {
        rerunAnalysisRef.current = false;
        scheduleAnalysis();
      }
    }
  }, [mutateRevisions, projectId, scheduleAnalysis]);

  useEffect(() => {
    runAnalysisRef.current = runAnalysis;
  }, [runAnalysis]);

  useEffect(() => {
    if (initialAnalysisRef.current) return;
    initialAnalysisRef.current = true;
    void runAnalysisRef.current();
  }, []);

  useEffect(() => () => {
    if (autosaveTimerRef.current !== undefined) window.clearTimeout(autosaveTimerRef.current);
    if (analysisTimerRef.current !== undefined) window.clearTimeout(analysisTimerRef.current);
  }, []);

  async function reloadSource() {
    setReloading(true);
    try {
      await reload();
    } finally {
      setReloading(false);
    }
  }

  async function runVerification() {
    setVerificationState("running");
    setVerificationError("");
    setVerificationReport(null);
    try {
      const report = await verifyRevisions(
        projectId,
        baselineRevision,
        candidateRevision,
        verificationCases.map((item) => ({ name: item.name.trim(), stdin: item.stdin })),
      );
      setVerificationReport(report);
      setVerificationState("complete");
    } catch (cause) {
      setVerificationState("error");
      setVerificationError(verificationErrorMessage(cause));
    }
  }

  const revisionDiffRows = useMemo(
    () => showRevisionDiff && historicalRevision && !dirty
      ? buildRevisionDiffRows(historicalRevision.source, savedSource)
      : [],
    [dirty, historicalRevision, savedSource, showRevisionDiff],
  );
  const { lineCount, byteLength } = sourceStats;
  const analysisIsPartial = analysis?.status === "partial";
  const analysisFailed = analysis?.status === "failed";
  const currentDiagnostics = analysisState === "complete" && !dirty
    ? analysis?.diagnostics ?? NO_DIAGNOSTICS
    : NO_DIAGNOSTICS;

  return (
    <main className="workspace-screen workspace-screen--ide">
      <header className="workspace-top">
        <div className="ide-project-path"><Brand compact /><div className="workspace-breadcrumb"><span>ФАЙЛ</span><i>/</i><strong>{project.filename}</strong><small>РЕД. {revision}</small></div></div>
        <div className="workspace-actions ide-actions">
          <span className={`save-indicator save-indicator--${dirty ? "dirty" : saveState}`}>
            {saveState === "saving" ? <LoaderCircle className="spin" size={14} /> : dirty ? <CircleDot size={14} /> : <Check size={14} />}
            {saveState === "saving" ? "Сохраняем" : dirty ? "Не сохранено" : "Сохранено"}
          </span>
          <Button className="action-button action-button--quiet" onClick={() => void saveSource()} disabled={!dirty || saveState === "saving"}>
            {saveState === "saving" ? <LoaderCircle className="spin" /> : <Save />} Сохранить
          </Button>
          <Button className="action-button action-button--quiet" onClick={() => { setHistoryOpen((open) => !open); setVerificationOpen(false); }} aria-expanded={historyOpen}>
            <History /> Ревизии
          </Button>
          <Button className="action-button action-button--quiet" onClick={() => { setVerificationOpen((open) => !open); setHistoryOpen(false); }} aria-expanded={verificationOpen}>
            <ShieldCheck /> Проверка
          </Button>
        </div>
        <Link className="back-link" href="/"><ArrowLeft size={15} /> Другой файл</Link>
      </header>

      {saveState === "conflict" ? (
        <div className="inline-alert" role="alert"><AlertCircle size={17} /><span>Серверная ревизия уже изменилась. Локальный текст не отправлялся повторно.</span><Button variant="outline" onClick={() => void reloadSource()} disabled={reloading}><RefreshCw className={reloading ? "spin" : ""} /> Перезагрузить</Button></div>
      ) : saveState === "error" ? (
        <div className="inline-alert" role="alert"><AlertCircle size={17} /><span>{saveError}</span></div>
      ) : null}

      {historyOpen ? (
        <RevisionPanel
          currentRevision={revision}
          dirty={dirty}
          revisions={revisions}
          revisionsError={revisionsError}
          selectedRevision={selectedHistoryRevision}
          onSelectedRevision={(value) => { setSelectedHistoryRevision(value); setShowRevisionDiff(false); }}
          historicalRevisionReady={Boolean(historicalRevision)}
          historicalRevisionError={historicalRevisionError}
          showDiff={showRevisionDiff}
          onToggleDiff={() => setShowRevisionDiff((shown) => !shown)}
          diffRows={revisionDiffRows}
          onClose={() => setHistoryOpen(false)}
        />
      ) : null}

      {verificationOpen ? (
        <VerificationPanel
          currentRevision={revision}
          dirty={dirty}
          revisions={revisions}
          revisionsError={revisionsError}
          baselineRevision={baselineRevision}
          candidateRevision={candidateRevision}
          onBaselineRevision={setBaselineRevision}
          onCandidateRevision={setCandidateRevision}
          cases={verificationCases}
          onCases={setVerificationCases}
          state={verificationState}
          report={verificationReport}
          error={verificationError}
          onRun={() => void runVerification()}
          onClose={() => setVerificationOpen(false)}
        />
      ) : null}

      <div className={`workspace-grid${viewMode === "project" ? " workspace-grid--project" : ""}`}>
        <section className="ide-center" aria-label="Исходник и граф">
          <div className="workspace-viewbar">
            <div className="workspace-tabs" role="tablist" aria-label="Представление проекта">
              <button className={viewMode === "source" ? "workspace-tab workspace-tab--active" : "workspace-tab"} type="button" role="tab" aria-selected={viewMode === "source"} onClick={() => setViewMode("source")}><Code2 size={16} /> Исходник</button>
              <button className={viewMode === "project" ? "workspace-tab workspace-tab--active" : "workspace-tab"} type="button" role="tab" aria-selected={viewMode === "project"} onClick={() => setViewMode("project")} disabled={dirty}><Network size={16} /> Проект</button>
              <button className={viewMode === "cfg" ? "workspace-tab workspace-tab--active" : "workspace-tab"} type="button" role="tab" aria-selected={viewMode === "cfg"} onClick={() => setViewMode("cfg")} disabled={dirty}><Waypoints size={16} /> CFG + Big O</button>
            </div>
            <span className="source-summary">{lineCount} строк <i /> {byteLength.toLocaleString("ru-RU")} байт</span>
          </div>

          {viewMode === "source" ? (
            <section className="editor-card" aria-label="Редактор Java">
              <div className="editor-toolbar">
                <div className="editor-file"><span className="java-file-icon">J</span><strong>{project.filename}</strong><span className="file-tag">JAVA</span></div>
                <div className="editor-toolbar-meta"><span><Code2 size={14} /> UTF-8 / ranges UTF-16</span><span><GitBranch size={14} /> Ред. {revision}</span></div>
              </div>
              <div className="editor-body">
                <JavaEditor
                  ref={editorRef}
                  value={initialSource}
                  diagnostics={currentDiagnostics}
                  focusRange={selectedSourceRange}
                  onDirtyChange={onEditorDirtyChange}
                  onMetricsChange={setSourceStats}
                  onSourceSelection={setSelectedSourceRange}
                />
              </div>
              <footer className="editor-footer"><span><TerminalSquare size={14} /> Java</span><span>{dirty ? "CFG и анализ скрыты до сохранения" : "Исходник синхронизирован с ревизией"}</span></footer>
            </section>
          ) : viewMode === "project" ? (
            <ProjectGraphViewer
              projectId={projectId}
              revision={revision}
              methods={methods}
              onOpenMethod={(selector) => {
                setSelectedMethodSelector(selector);
                setSelectedSourceRange(null);
                setViewMode("cfg");
              }}
              onSourceRangeSelect={(range) => {
                setSelectedSourceRange(range);
                setViewMode("source");
              }}
            />
          ) : (
            <CfgViewer
              projectId={projectId}
              revision={revision}
              methods={analysisState === "complete" && !dirty ? methods : []}
              selectedSourceRange={selectedSourceRange}
              selectedMethodSelector={selectedMethodSelector}
              onSelectedMethodSelectorChange={(selector) => {
                setSelectedMethodSelector(selector);
                setSelectedSourceRange(null);
              }}
              onSourceRangeSelect={(range, keepGraphVisible) => {
                setSelectedSourceRange(range);
                if (range && !keepGraphVisible) setViewMode("source");
              }}
              onTransformed={reload}
            />
          )}
        </section>

        <aside className="analysis-panel">
          <div className="panel-heading"><div><span className="panel-kicker">JDT / РЕВИЗИЯ {revision}</span><h2>Диагностика</h2></div><span className={`worker-mark worker-mark--${analysisState}`}><Sparkles size={16} /></span></div>
          {analysisState === "complete" && analysis ? (
            <div className="analysis-result">
              <div className="result-banner">
                <span className={`result-check${analysisIsPartial ? " result-check--partial" : analysisFailed ? " result-check--failed" : ""}`}>
                  {analysisIsPartial || analysisFailed ? <AlertCircle size={15} /> : <Check size={15} />}
                </span>
                <div>
                  <strong>{analysisIsPartial ? "Частичный результат" : analysisFailed ? "Анализ не выполнен" : "Анализ завершён"}</strong>
                  <small>{analysisIsPartial
                    ? "Часть исходника могла не обработаться. Проверьте диагностики ниже."
                    : analysisFailed
                      ? "Анализатор не смог обработать исходник. Проверьте диагностики ниже."
                      : `${methods.length} методов с CFG · протокол ${analysis.protocol_version}`}</small>
                </div>
              </div>
              <div className="result-stats"><div><strong>{analysis.methods.length}</strong><span>методов</span></div><div><strong>{analysis.diagnostics.length}</strong><span>диагностик</span></div></div>
              {analysis.methods.length ? (
                <div className="method-list">
                  <span className="list-label">МЕТОДЫ</span>
                  {analysis.methods.slice(0, 14).map((method) => {
                    const cfgMethod = methods.find((item) => item.id === method.id);
                    return (
                      <button type="button" className="method-row" key={method.id} onClick={() => {
                        if (cfgMethod) {
                          setSelectedMethodSelector(cfgMethod.selector);
                          setViewMode("cfg");
                        } else {
                          setSelectedSourceRange(method.source_range);
                          setViewMode("source");
                        }
                      }}>
                        <Code2 size={13} /><span title={method.id}>{method.name}</span><small>{cfgMethod?.selector ?? "без CFG"} · стр. {method.source_range.line ?? "—"}</small><ArrowUpRight size={13} />
                      </button>
                    );
                  })}
                </div>
              ) : null}
              {analysis.diagnostics.length ? (
                <div className="diagnostic-list">
                  <span className="list-label">ДИАГНОСТИКИ</span>
                  {analysis.diagnostics.map((item, index) => (
                    <button className={`diagnostic-item diagnostic-item--${item.severity}`} type="button" key={`${item.code}-${index}`} onClick={() => {
                      setSelectedSourceRange(item.source_range);
                      setViewMode("source");
                    }}>
                      <span className={`diagnostic-dot diagnostic-dot--${item.severity}`} />
                      <span className="diagnostic-copy"><strong>{item.code}</strong><span>{item.message}</span><small>Строка {item.source_range.line ?? "—"}</small></span>
                    </button>
                  ))}
                </div>
              ) : <div className="empty-diagnostics"><Check size={15} /> Диагностик нет</div>}
            </div>
          ) : analysisState === "loading" ? (
            <div className="worker-state"><span className="state-icon"><LoaderCircle className="spin" /></span><strong>Анализируем сохранённую ревизию</strong><p>{analysisMessage}</p><div className="state-footnote"><span className="status-pip status-pip--pulse" /> SSE подключён</div></div>
          ) : analysisState === "unavailable" ? (
            <div className="worker-state worker-state--unavailable"><span className="state-icon"><Clock3 /></span><strong>Анализатор недоступен</strong><p>{analysisMessage}</p><Button variant="outline" onClick={() => void runAnalysis()}><RefreshCw /> Повторить</Button></div>
          ) : analysisState === "error" ? (
            <div className="worker-state worker-state--error"><span className="state-icon"><AlertCircle /></span><strong>Анализ не выполнен</strong><p>{analysisMessage}</p><Button variant="outline" onClick={() => void runAnalysis()}><RefreshCw /> Повторить</Button></div>
          ) : (
            <div className="worker-state"><span className="state-icon"><ShieldCheck /></span><strong>{dirty ? "Анализ ожидает сохранения" : "Анализ запустится автоматически"}</strong><p>{dirty ? "После паузы исходник сохранится с проверкой ревизии, затем запустится JDT." : "Ожидаем запуска анализа."}</p></div>
          )}
        </aside>
      </div>

      <footer className="workspace-bottom"><span>Автосохранение 500 мс · анализ только сохранённой ревизии</span><span><ShieldCheck size={13} /> Приватная гостевая сессия</span></footer>
    </main>
  );
}

function RevisionPanel({
  currentRevision,
  dirty,
  revisions,
  revisionsError,
  selectedRevision,
  onSelectedRevision,
  historicalRevisionReady,
  historicalRevisionError,
  showDiff,
  onToggleDiff,
  diffRows,
  onClose,
}: {
  currentRevision: number;
  dirty: boolean;
  revisions: { revision: number; has_analysis: boolean }[] | undefined;
  revisionsError: unknown;
  selectedRevision: number | null;
  onSelectedRevision: (value: number | null) => void;
  historicalRevisionReady: boolean;
  historicalRevisionError: unknown;
  showDiff: boolean;
  onToggleDiff: () => void;
  diffRows: RevisionDiffRow[];
  onClose: () => void;
}) {
  return (
    <section className="revision-history" aria-label="Текстовое сравнение ревизий">
      <div className="revision-history__heading"><div><strong>Текстовый diff исходника</strong><span>Показывает только изменения текста, не эквивалентность поведения.</span></div><button type="button" onClick={onClose} aria-label="Закрыть историю">×</button></div>
      {revisionsError ? <p role="alert">Не удалось загрузить ревизии.</p> : !revisions ? <p role="status">Загружаем ревизии…</p> : (
        <div className="revision-history__body">
          <label htmlFor="revision-select">Сравнить ревизию {currentRevision} с</label>
          <select id="revision-select" value={selectedRevision ?? ""} onChange={(event) => onSelectedRevision(event.target.value ? Number(event.target.value) : null)}>
            <option value="">Выберите ревизию</option>
            {revisions.filter((item) => item.revision !== currentRevision).map((item) => <option key={item.revision} value={item.revision}>Ревизия {item.revision}{item.has_analysis ? " · есть анализ" : ""}</option>)}
          </select>
          {selectedRevision !== null ? <Button variant="outline" disabled={dirty || !historicalRevisionReady || Boolean(historicalRevisionError)} onClick={onToggleDiff}>{showDiff ? "Скрыть diff" : "Показать diff"}</Button> : null}
          {dirty ? <small>Сначала дождитесь автосохранения.</small> : null}
          {historicalRevisionError ? <small role="alert">Ревизия недоступна.</small> : null}
        </div>
      )}
      {showDiff && diffRows.length ? (
        <div className="revision-diff" aria-label="Старая ревизия слева, текущая справа">
          <div className="revision-diff__header"><span>РЕВИЗИЯ {selectedRevision}</span><span>РЕВИЗИЯ {currentRevision}</span></div>
          <div className="revision-diff__body">
            {diffRows.map((row, index) => row.kind === "gap"
              ? <div className="revision-diff__gap" key={`gap-${index}`}>··· {row.hidden} неизменённых строк ···</div>
              : <div className={`revision-diff__row revision-diff__row--${row.kind}`} key={`row-${index}`}>
                <span className="revision-diff__number">{row.oldLine ?? ""}</span><code className="revision-diff__old">{row.oldText ?? ""}</code>
                <span className="revision-diff__number">{row.newLine ?? ""}</span><code className="revision-diff__new">{row.newText ?? ""}</code>
              </div>)}
          </div>
        </div>
      ) : showDiff ? <p className="revision-diff-empty"><Check size={14} /> Тексты ревизий совпадают.</p> : null}
    </section>
  );
}

function VerificationPanel({
  currentRevision,
  dirty,
  revisions,
  revisionsError,
  baselineRevision,
  candidateRevision,
  onBaselineRevision,
  onCandidateRevision,
  cases,
  onCases,
  state,
  report,
  error,
  onRun,
  onClose,
}: {
  currentRevision: number;
  dirty: boolean;
  revisions: { revision: number; has_analysis: boolean }[] | undefined;
  revisionsError: unknown;
  baselineRevision: number;
  candidateRevision: number;
  onBaselineRevision: (value: number) => void;
  onCandidateRevision: (value: number) => void;
  cases: VerifyTestCase[];
  onCases: (cases: VerifyTestCase[]) => void;
  state: "idle" | "running" | "complete" | "error";
  report: VerificationReport | null;
  error: string;
  onRun: () => void;
  onClose: () => void;
}) {
  const canRun = !dirty && state !== "running" && cases.length > 0
    && cases.every((item) => item.name.trim().length > 0);

  return (
    <section className="verification-panel" aria-label="Поведенческое сравнение ревизий">
      <div className="revision-history__heading"><div><strong>Поведенческое сравнение</strong><span>Docker runner компилирует две ревизии и сравнивает вывод только на заданных stdin-сценариях.</span></div><button type="button" onClick={onClose} aria-label="Закрыть проверку">×</button></div>
      {revisionsError ? <p role="alert">Не удалось загрузить ревизии.</p> : !revisions ? <p role="status">Загружаем ревизии…</p> : (
        <div className="verification-form">
          <div className="verification-revisions">
            <label>Базовая ревизия<select value={baselineRevision} onChange={(event) => onBaselineRevision(Number(event.target.value))}>{revisions.map((item) => <option key={item.revision} value={item.revision}>Ревизия {item.revision}</option>)}</select></label>
            <GitCompareArrows />
            <label>Кандидат<select value={candidateRevision} onChange={(event) => onCandidateRevision(Number(event.target.value))}>{revisions.map((item) => <option key={item.revision} value={item.revision}>Ревизия {item.revision}{item.revision === currentRevision ? " · текущая" : ""}</option>)}</select></label>
          </div>
          <div className="verification-cases">
            {cases.map((item, index) => (
              <div className="verification-case" key={index}>
                <input aria-label={`Имя сценария ${index + 1}`} value={item.name} maxLength={64} onChange={(event) => onCases(cases.map((current, position) => position === index ? { ...current, name: event.target.value } : current))} />
                <textarea aria-label={`stdin сценария ${index + 1}`} value={item.stdin} placeholder="stdin (может быть пустым)" onChange={(event) => onCases(cases.map((current, position) => position === index ? { ...current, stdin: event.target.value } : current))} />
                <Button variant="ghost" size="sm" aria-label="Удалить сценарий" disabled={cases.length === 1} onClick={() => onCases(cases.filter((_, position) => position !== index))}><Trash2 /></Button>
              </div>
            ))}
            <Button variant="outline" size="sm" disabled={cases.length >= 20} onClick={() => onCases([...cases, { name: `сценарий ${cases.length + 1}`, stdin: "" }])}><Plus /> Добавить stdin-сценарий</Button>
          </div>
          <Button onClick={onRun} disabled={!canRun}>{state === "running" ? <LoaderCircle className="spin" /> : <ShieldCheck />}{state === "running" ? "Компилируем и запускаем" : "Запустить изолированную проверку"}</Button>
          {dirty ? <small>Сначала дождитесь автосохранения.</small> : null}
        </div>
      )}
      {state === "error" ? <div className="verification-error" role="alert"><AlertCircle />{error}</div> : null}
      {report ? <VerificationResult report={report} /> : null}
    </section>
  );
}

function VerificationResult({ report }: { report: VerificationReport }) {
  return (
    <section className={`verification-result verification-result--${report.status}`}>
      <div>
        <strong>{report.behaviorally_verified ? "Сценарии совпали" : report.status === "compilation_failed" ? "Компиляция не прошла" : "Обнаружено различие"}</strong>
        <span>Отчёт {report.report_id} · ревизии {report.baseline_revision} → {report.candidate_revision}</span>
      </div>
      <p>{report.explanation}</p>
      <div className="verification-compilation">
        <span>База: {report.baseline_compilation.status}</span>
        <span>Кандидат: {report.candidate_compilation.status}</span>
        <span>Выполнено: {report.executed_test_cases}/{report.requested_test_cases}</span>
      </div>
      {report.test_cases.map((item) => (
        <div className="verification-case-result" key={item.name}>
          <strong>{item.passed ? <Check size={14} /> : <AlertCircle size={14} />}{item.name}</strong>
          {item.mismatches.length ? <ul>{item.mismatches.map((mismatch) => <li key={mismatch.field}>{mismatch.field}: {mismatch.explanation}</li>)}</ul> : <span>stdout, stderr, exit code и лимиты совпали.</span>}
        </div>
      ))}
      <small>Результат относится только к указанным сценариям и не доказывает полную эквивалентность программ.</small>
    </section>
  );
}

function saveErrorMessage(cause: unknown) {
  if (!(cause instanceof ApiError)) return "Не удалось сохранить. Проверьте соединение с API.";
  if (cause.status === 409) return "Ревизия устарела.";
  if (cause.status === 413 || cause.code === "project_too_large") return "Исходник превышает лимит API.";
  return cause.message;
}

function analysisErrorMessage(cause: unknown) {
  if (!(cause instanceof ApiError)) return "Не удалось связаться с API.";
  if (cause.code === "analyzer_unavailable") return "Java parser не настроен. Выполните ./dev parser-build и перезапустите API.";
  if (cause.code === "analyzer_timeout") return "JDT-анализ превысил допустимое время.";
  if (cause.code === "analyzer_invalid_output") return "Parser вернул результат, не соответствующий protocol_version 1.";
  return cause.message;
}

function verificationErrorMessage(cause: unknown) {
  if (!(cause instanceof ApiError)) return "Не удалось выполнить поведенческую проверку.";
  if (cause.code === "runner_unavailable") return "Docker runner не настроен. Соберите образ и задайте BLUEPRINTS_RUNNER_IMAGE.";
  if (cause.code === "revision_not_found") return "Одна из выбранных ревизий больше недоступна.";
  return cause.message;
}

function analysisProgressLabel(stage: string) {
  if (stage === "started") return "SSE-поток открыт, ревизия зафиксирована…";
  if (stage === "analyzing") return "JDT строит AST, семантику, CFG и Big O…";
  return "Анализатор обрабатывает сохранённую ревизию…";
}

function sourceMetrics(source: string) {
  return {
    lineCount: source.length ? source.split("\n").length : 0,
    byteLength: new TextEncoder().encode(source).byteLength,
  };
}

type RevisionDiffRow = {
  kind: "same" | "changed" | "added" | "removed" | "gap";
  oldLine?: number;
  newLine?: number;
  oldText?: string;
  newText?: string;
  hidden?: number;
};

function buildRevisionDiffRows(oldSource: string, newSource: string): RevisionDiffRow[] {
  const rows: RevisionDiffRow[] = [];
  let oldLine = 1;
  let newLine = 1;
  let removed: string[] = [];
  let added: string[] = [];
  const lines = (value: string) => value.length === 0 ? [] : value.replace(/\n$/, "").split("\n");
  const flush = () => {
    const count = Math.max(removed.length, added.length);
    for (let index = 0; index < count; index += 1) {
      const oldText = removed[index];
      const newText = added[index];
      rows.push({
        kind: oldText !== undefined && newText !== undefined ? "changed" : oldText !== undefined ? "removed" : "added",
        oldLine: oldText === undefined ? undefined : oldLine++,
        newLine: newText === undefined ? undefined : newLine++,
        oldText,
        newText,
      });
    }
    removed = [];
    added = [];
  };

  for (const part of diffLines(oldSource, newSource)) {
    if (part.removed) removed.push(...lines(part.value));
    else if (part.added) added.push(...lines(part.value));
    else {
      flush();
      for (const text of lines(part.value)) {
        rows.push({ kind: "same", oldLine: oldLine++, newLine: newLine++, oldText: text, newText: text });
      }
    }
  }
  flush();

  const compact: RevisionDiffRow[] = [];
  for (let index = 0; index < rows.length;) {
    if (rows[index].kind !== "same") {
      compact.push(rows[index++]);
      continue;
    }
    let end = index + 1;
    while (end < rows.length && rows[end].kind === "same") end += 1;
    const run = rows.slice(index, end);
    if (run.length <= 8) compact.push(...run);
    else compact.push(...run.slice(0, 3), { kind: "gap", hidden: run.length - 6 }, ...run.slice(-3));
    index = end;
  }
  return compact;
}
