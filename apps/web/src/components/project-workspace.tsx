"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from "react";
import useSWR from "swr";
import {
  AlertCircle,
  ArrowLeft,
  ArrowUpRight,
  Box,
  Braces,
  Check,
  ChevronDown,
  ChevronRight,
  CircleDot,
  Clock3,
  Code2,
  GitCompareArrows,
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
import { ArchiveWorkspace } from "@/components/archive-workspace";
import { BenchmarkPanel } from "@/components/benchmark-panel";
import {
  analyzeProjectStream,
  ApiError,
  runBenchmark,
  getProjectMethods,
  getMethodAnalysis,
  getProjectRevision,
  getProjectRevisions,
  getProjectWorkspace,
  MAX_SOURCE_BYTES,
  type AnalysisResult,
  type BenchmarkReport,
  type CfgMethod,
  type Diagnostic,
  type MethodAnalysis,
  type Project,
  type SourceRange,
  type VerificationReport,
  type VerifyTestCase,
  type BenchmarkWorkloadInput,
  updateProjectSource,
  verifyRevisions,
} from "@/lib/api";

const NO_DIAGNOSTICS: Diagnostic[] = [];

const JavaEditor = dynamic(
  () => import("@/components/java-editor").then((module) => module.JavaEditor),
  { ssr: false, loading: () => <div className="grid size-full place-items-center text-[10px] text-slate-500">Открываем редактор…</div> },
);

const CfgViewer = dynamic(
  () => import("@/components/cfg-viewer").then((module) => module.CfgViewer),
  { ssr: false, loading: () => <section className="flex size-full items-center justify-center bg-[#080f1b] text-[10px] text-slate-500">Загружаем CFG…</section> },
);

const ProjectGraphViewer = dynamic(
  () => import("@/components/project-graph-viewer").then((module) => module.ProjectGraphViewer),
  { ssr: false, loading: () => <section className="flex size-full items-center justify-center bg-[#080f1b] text-[10px] text-slate-500">Загружаем обзор проекта…</section> },
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

export function ProjectWorkspace({ projectId, legacySingleFile = false }: { projectId: string; legacySingleFile?: boolean }) {
  const { data, error, isLoading, mutate } = useSWR(
    `project:${projectId}`,
    () => getProjectWorkspace(projectId),
    { shouldRetryOnError: false, revalidateOnFocus: false },
  );

  if (isLoading || (!data && !error)) {
    return (
      <main className="min-h-svh bg-[#070b14] px-6 pb-6">
        <div className="flex h-[58px] items-center justify-between border-b border-slate-800"><Brand compact /><Link className="inline-flex items-center gap-1.5 text-[10px] text-slate-400 hover:text-cyan-400" href="/"><ArrowLeft size={15} /> Все проекты</Link></div>
        <section className="flex min-h-[calc(100svh-82px)] flex-col items-center justify-center gap-2 text-center text-slate-500"><LoaderCircle className="spin" /><span>Загружаем проект…</span></section>
      </main>
    );
  }

  if (error || !data) {
    return (
      <main className="min-h-svh bg-[#070b14] px-6 pb-6">
        <div className="flex h-[58px] items-center justify-between border-b border-slate-800"><Brand compact /><Link className="inline-flex items-center gap-1.5 text-[10px] text-slate-400 hover:text-cyan-400" href="/"><ArrowLeft size={15} /> Все проекты</Link></div>
        <section className="flex min-h-[calc(100svh-82px)] flex-col items-center justify-center gap-2 text-center text-slate-500"><AlertCircle /><h1 className="m-0 text-lg text-slate-200">Не удалось открыть проект</h1><p className="m-0 max-w-lg leading-6">{projectLoadMessage(error)}</p><Button onClick={() => void mutate()}><RefreshCw /> Повторить</Button></section>
      </main>
    );
  }

  if (data.project.kind === "archive" || !legacySingleFile) {
    return <ArchiveWorkspace project={data.project} initialRevision={data.revision} initialSource={data.source} initialFilePath={data.activeFilePath} initialFiles={data.files} />;
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

  const [viewMode, setViewMode] = useState<"project" | "cfg">("project");
  const [dockMode, setDockMode] = useState<"code" | "revisions" | "verification" | "benchmark">("code");
  const [selectedSourceRange, setSelectedSourceRange] = useState<SourceRange | null>(null);
  const [selectedMethodSelector, setSelectedMethodSelector] = useState("");
  const [historyOpen, setHistoryOpen] = useState(false);
  const [verificationOpen, setVerificationOpen] = useState(false);
  const [benchmarkOpen, setBenchmarkOpen] = useState(false);
  const [selectedHistoryRevision, setSelectedHistoryRevision] = useState<number | null>(null);
  const [showRevisionDiff, setShowRevisionDiff] = useState(false);
  const [revisionDiff, setRevisionDiff] = useState<{ key: string; rows: RevisionDiffRow[] } | null>(null);
  const [reloading, setReloading] = useState(false);

  const historyEnabled = historyOpen || verificationOpen || benchmarkOpen;
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
  const [mainClass, setMainClass] = useState("");
  const [sourceLevel, setSourceLevel] = useState("21");
  const [benchmarkState, setBenchmarkState] = useState<"idle" | "running" | "complete" | "error">("idle");
  const [benchmarkReport, setBenchmarkReport] = useState<BenchmarkReport | null>(null);
  const [benchmarkError, setBenchmarkError] = useState("");
  const [benchmarkIterations, setBenchmarkIterations] = useState(12);
  const [benchmarkWorkload, setBenchmarkWorkload] = useState<BenchmarkWorkloadInput>({ name: "Ввод программы", stdin: "" });
  const paneSizes = useIdePaneSizes();

  const activeMethod = methods.find((method) => method.selector === selectedMethodSelector) ?? null;
  const methodAnalysisRequest = useSWR<MethodAnalysis, unknown>(
    !dirty && activeMethod
      ? `method-analysis:${projectId}:${revision}:${activeMethod.selector}`
      : null,
    () => getMethodAnalysis(projectId, activeMethod!.selector).then((result) => {
      if (result.revision !== revision || result.method_id !== activeMethod!.id) {
        throw new ApiError("Анализ метода относится к другой ревизии.", 409, "revision_conflict");
      }
      return result.analysis;
    }),
    { shouldRetryOnError: false, revalidateOnFocus: false },
  );

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
        { main_class: mainClass || null, source_level: sourceLevel },
      );
      setVerificationReport(report);
      setVerificationState("complete");
    } catch (cause) {
      setVerificationState("error");
      setVerificationError(verificationErrorMessage(cause));
    }
  }

  async function runRuntimeBenchmark() {
    setBenchmarkState("running");
    setBenchmarkError("");
    setBenchmarkReport(null);
    try {
      const report = await runBenchmark(projectId, {
        baseline_revision: baselineRevision,
        candidate_revision: candidateRevision,
        workloads: [{ ...benchmarkWorkload, name: benchmarkWorkload.name.trim() }],
        warmup_iterations: 2,
        measured_iterations: benchmarkIterations,
        class_loading: "fresh",
        main_class: mainClass || null,
        source_level: sourceLevel,
      });
      setBenchmarkReport(report);
      setBenchmarkState("complete");
    } catch (cause) {
      setBenchmarkState("error");
      setBenchmarkError(verificationErrorMessage(cause));
    }
  }

  const revisionDiffKey = showRevisionDiff && historicalRevision && !dirty
    ? `${historicalRevision.revision}:${revision}:${savedSource.length}`
    : null;
  const revisionDiffRows = revisionDiff?.key === revisionDiffKey ? revisionDiff.rows : [];

  useEffect(() => {
    let cancelled = false;
    if (!revisionDiffKey || !historicalRevision) return;
    void buildRevisionDiffRows(historicalRevision.source, savedSource).then((rows) => {
      if (!cancelled) setRevisionDiff({ key: revisionDiffKey, rows });
    });
    return () => { cancelled = true; };
  }, [historicalRevision, revisionDiffKey, savedSource]);
  const { lineCount, byteLength } = sourceStats;
  const analysisIsPartial = analysis?.status === "partial";
  const analysisFailed = analysis?.status === "failed";
  const currentDiagnostics = analysisState === "complete" && !dirty
    ? analysis?.diagnostics ?? NO_DIAGNOSTICS
    : NO_DIAGNOSTICS;

  function openDock(mode: "code" | "revisions" | "verification" | "benchmark") {
    setDockMode(mode);
    setHistoryOpen(mode === "revisions");
    setVerificationOpen(mode === "verification");
    setBenchmarkOpen(mode === "benchmark");
  }

  function openSourceRange(range: SourceRange | null) {
    setSelectedSourceRange(range);
    openDock("code");
  }

  return (
    <main className="relative grid h-svh min-w-[920px] grid-rows-[46px_minmax(220px,1fr)_5px_var(--ide-bottom)] overflow-hidden bg-[#070b14]" style={paneSizes.style}>
      <header className="z-20 grid grid-cols-[minmax(240px,1fr)_auto_minmax(360px,1fr)] items-center gap-3 border-b border-slate-800 bg-[#0b1220] px-3 max-[1120px]:grid-cols-[minmax(190px,1fr)_auto_minmax(300px,1fr)]">
        <div className="flex min-w-0 items-center gap-3"><Brand compact /><div className="flex min-w-0 items-center gap-2"><strong className="max-w-64 overflow-hidden text-ellipsis whitespace-nowrap text-[14px] text-slate-200">{project.filename}</strong><small className="rounded bg-slate-800 px-1.5 py-0.5 font-mono text-[11px] text-slate-400">REV {revision}</small></div></div>
        <nav className="flex h-full items-stretch justify-center gap-1" aria-label="Центральное представление">
          <button className={`relative inline-flex h-full items-center gap-2 border-0 bg-transparent px-3 text-[13px] hover:bg-slate-900 disabled:opacity-40 [&_svg]:size-4 ${viewMode === "project" ? "text-cyan-100 after:absolute after:right-2.5 after:bottom-0 after:left-2.5 after:h-0.5 after:bg-cyan-400" : "text-slate-400 hover:text-slate-200"}`} type="button" onClick={() => setViewMode("project")} disabled={dirty}><Network /> Обзор проекта</button>
          <button className={`relative inline-flex h-full items-center gap-2 border-0 bg-transparent px-3 text-[13px] hover:bg-slate-900 disabled:opacity-40 [&_svg]:size-4 ${viewMode === "cfg" ? "text-cyan-100 after:absolute after:right-2.5 after:bottom-0 after:left-2.5 after:h-0.5 after:bg-cyan-400" : "text-slate-400 hover:text-slate-200"}`} type="button" onClick={() => setViewMode("cfg")} disabled={dirty}><Waypoints /> CFG</button>
        </nav>
        <div className="flex min-w-0 items-center justify-end gap-2">
          <span className={`inline-flex items-center gap-1.5 whitespace-nowrap text-[12px] max-[1120px]:hidden ${dirty || saveState === "conflict" || saveState === "error" ? "text-amber-300 [&_svg]:text-amber-400" : "text-emerald-300 [&_svg]:text-emerald-400"}`}>
            {saveState === "saving" ? <LoaderCircle className="spin" size={14} /> : dirty ? <CircleDot size={14} /> : <Check size={14} />}
            {saveState === "saving" ? "Сохраняем" : dirty ? "Не сохранено" : "Сохранено"}
          </span>
          <Button className="!h-8 !rounded !border-slate-700 !bg-slate-900 !text-[13px] !text-slate-300 hover:!bg-slate-800" onClick={() => void saveSource()} disabled={!dirty || saveState === "saving"}>
            {saveState === "saving" ? <LoaderCircle className="spin" /> : <Save />} Сохранить
          </Button>
          <Button className="!h-8 !rounded !border-slate-700 !bg-slate-900 !text-[13px] !text-slate-300 hover:!bg-slate-800" onClick={() => openDock("revisions")} aria-expanded={historyOpen}>
            <History /> Ревизии
          </Button>
          <Button className="!h-8 !rounded !border-slate-700 !bg-slate-900 !text-[13px] !text-slate-300 hover:!bg-slate-800" onClick={() => openDock("verification")} aria-expanded={verificationOpen}>
            <ShieldCheck /> Проверка
          </Button>
          <Button className="!h-8 !rounded !border-slate-700 !bg-slate-900 !text-[13px] !text-slate-300 hover:!bg-slate-800" onClick={() => openDock("benchmark")} aria-expanded={benchmarkOpen}>
            <GitCompareArrows /> Бенчмарк
          </Button>
        </div>
        <Link className="ml-0.5 inline-flex items-center gap-1 whitespace-nowrap text-[13px] text-slate-400 hover:text-cyan-400" href="/"><ArrowLeft size={15} /> Другой файл</Link>
      </header>

      <div className="pointer-events-none absolute top-[52px] left-1/2 z-50 w-[min(720px,calc(100%-40px))] -translate-x-1/2">
        {saveState === "conflict" ? (
        <div className="pointer-events-auto flex items-center gap-2.5 rounded-md border border-amber-800 bg-amber-950/95 px-2.5 py-2 text-[10px] text-amber-200 shadow-xl" role="alert"><AlertCircle size={17} /><span className="flex-1">Серверная ревизия уже изменилась. Локальный текст не отправлялся повторно.</span><Button variant="outline" onClick={() => void reloadSource()} disabled={reloading}><RefreshCw className={reloading ? "spin" : ""} /> Перезагрузить</Button></div>
      ) : saveState === "error" ? (
        <div className="pointer-events-auto flex items-center gap-2.5 rounded-md border border-rose-800 bg-rose-950/95 px-2.5 py-2 text-[10px] text-rose-200 shadow-xl" role="alert"><AlertCircle size={17} /><span>{saveError}</span></div>
      ) : null}
      </div>

      <div className="grid min-h-0 min-w-0 grid-cols-[var(--ide-left)_5px_minmax(300px,1fr)_5px_var(--ide-right)] overflow-hidden">
        <aside className="min-h-0 min-w-0 overflow-auto border-r border-slate-800 bg-[#0b1120]" aria-label="Обозреватель проекта">
          <div className="flex h-9 items-center justify-between border-b border-slate-800 px-3 text-[13px] font-semibold text-slate-300"><span>Методы</span><small className="grid size-5 place-items-center rounded bg-slate-800 font-mono text-[11px] font-normal text-slate-400">{methods.length}</small></div>
          <div className="p-1.5 [&_svg]:size-4 [&_svg]:shrink-0 [&_svg]:text-slate-500">
            <div className="flex h-8 w-full min-w-0 items-center gap-1.5 rounded px-1.5 text-[13px] text-slate-400"><ChevronDown /><Box /><strong className="overflow-hidden text-ellipsis whitespace-nowrap text-slate-300">{project.filename.replace(/\.java$/i, "")}</strong></div>
            <button className="flex h-8 w-full min-w-0 items-center gap-1.5 rounded border-0 bg-transparent pr-2 pl-[19px] text-left text-[13px] text-slate-400 hover:bg-slate-800 hover:text-slate-100" type="button" onClick={() => openSourceRange(null)}><ChevronDown /><span className="grid size-[18px] shrink-0 place-items-center rounded bg-[#c45f42] font-mono text-[11px] font-semibold text-white">J</span><span className="truncate">{project.filename}</span></button>
            <div className="flex h-8 w-full min-w-0 items-center gap-1.5 rounded pr-2 pl-[33px] text-[13px] text-slate-400"><ChevronDown /><Braces className="text-violet-400!" /><span className="truncate">{project.filename.replace(/\.java$/i, "")}</span></div>
            <div className="explorer-methods">
              {methods.length ? methods.map((method) => (
                <button className={`flex h-8 w-full min-w-0 items-center gap-1.5 rounded border-0 bg-transparent pr-2 pl-12 text-left text-[13px] hover:bg-slate-800 hover:text-slate-100 ${method.selector === selectedMethodSelector ? "bg-slate-800 text-slate-100" : "text-slate-400"}`} type="button" key={method.selector} title={method.id} onClick={() => { setSelectedMethodSelector(method.selector); setSelectedSourceRange(null); setViewMode("cfg"); }}>
                  <ChevronRight /><Code2 className="text-blue-400!" /><span className="min-w-0 flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{method.name}</span><small className="font-mono text-[11px] text-slate-600">{method.source_range.line ?? "—"}</small>
                </button>
              )) : <p className="my-2 mr-2 ml-12 text-[13px] leading-5 text-slate-600">{analysisState === "loading" ? "Индексируем методы…" : "Методы появятся после анализа"}</p>}
            </div>
          </div>
        </aside>

        <button className="z-10 w-[5px] touch-none cursor-col-resize border-0 bg-slate-900 p-0 hover:bg-cyan-400 focus-visible:bg-cyan-400 focus-visible:outline-none" type="button" role="separator" aria-label="Изменить ширину обозревателя" aria-orientation="vertical" onPointerDown={(event) => paneSizes.begin("left", event)} onKeyDown={(event) => paneSizes.onKey("left", event)} />

        <section className="grid min-h-0 min-w-0 grid-rows-[40px_minmax(0,1fr)] bg-[#080e19]" aria-label="Граф проекта">
          <header className="flex min-w-0 items-center gap-1.5 overflow-x-auto border-b border-slate-800 bg-[#0d1524] px-3 text-[13px] whitespace-nowrap" aria-label="Путь: проект, класс, метод">
            <button type="button" className="rounded border-0 bg-transparent p-0.5 text-slate-400 hover:bg-slate-900 hover:text-slate-200" onClick={() => setViewMode("project")} aria-current={viewMode === "project" ? "page" : undefined}>{project.filename}</button>
            {viewMode === "cfg" && activeMethod ? (
              <>
                <ChevronRight size={14} className="shrink-0 text-slate-600" />
                <button type="button" className="rounded border-0 bg-transparent p-0.5 font-mono text-slate-400 hover:bg-slate-900 hover:text-slate-200" onClick={() => setViewMode("project")}>{classFromMethodId(activeMethod.id)}</button>
                <ChevronRight size={14} className="shrink-0 text-slate-600" />
                <span className="truncate font-mono text-slate-200" aria-current="page">{activeMethod.name} · {activeMethod.selector}</span>
              </>
            ) : null}
          </header>
          <div className="relative min-h-0 min-w-0 overflow-hidden">
          {viewMode === "project" ? (
            <ProjectGraphViewer
              projectId={projectId}
              revision={revision}
              enabled={analysisState === "complete" && !dirty}
              methods={methods}
              onOpenMethod={(selector) => {
                setSelectedMethodSelector(selector);
                setSelectedSourceRange(null);
                setViewMode("cfg");
              }}
              onSourceRangeSelect={openSourceRange}
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
                if (range) openSourceRange(range);
                if (keepGraphVisible) setDockMode("code");
              }}
              onTransformed={reload}
              showAnalysisSummary={false}
            />
          )}
          {dirty ? <div className="absolute right-3 bottom-3 z-20 flex items-center gap-1.5 rounded border border-amber-800 bg-amber-950/95 px-2.5 py-1.5 text-[12px] text-amber-300 [&_svg]:size-3.5"><CircleDot /> Граф относится к сохранённой ревизии {revision}</div> : null}
          </div>
        </section>

        <button className="z-10 w-[5px] touch-none cursor-col-resize border-0 bg-slate-900 p-0 hover:bg-cyan-400 focus-visible:bg-cyan-400 focus-visible:outline-none" type="button" role="separator" aria-label="Изменить ширину панели анализа" aria-orientation="vertical" onPointerDown={(event) => paneSizes.begin("right", event)} onKeyDown={(event) => paneSizes.onKey("right", event)} />

        <aside className="flex min-h-0 min-w-0 flex-col overflow-auto border-l border-slate-800 bg-[#0b1120]">
          <div className="flex min-h-11 shrink-0 items-center justify-between border-b border-slate-800 px-3 py-2">
            <h2 className="m-0 text-[14px] font-semibold text-slate-200">Диагностика</h2>
            <span className={`inline-flex items-center gap-1.5 text-[12px] ${analysisState === "complete" ? "text-emerald-400" : analysisState === "loading" ? "text-cyan-400" : analysisState === "error" || analysisState === "unavailable" ? "text-amber-400" : "text-slate-400"}`}>{analysisState === "loading" ? <LoaderCircle className="spin" size={14} /> : <ShieldCheck size={14} />}{analysisState === "loading" ? "анализ…" : `REV ${revision}`}</span>
          </div>
          {analysisState === "complete" && analysis ? (
            <div className="max-h-[47%] min-h-0 overflow-auto border-b border-slate-800 p-3">
              <div className="flex items-start gap-2.5">
                <span className={`mt-0.5 grid size-6 shrink-0 place-items-center rounded-full ${analysisIsPartial ? "bg-amber-400/10 text-amber-400" : analysisFailed ? "bg-rose-400/10 text-rose-400" : "bg-emerald-400/10 text-emerald-400"}`}>
                  {analysisIsPartial || analysisFailed ? <AlertCircle size={15} /> : <Check size={15} />}
                </span>
                <div className="min-w-0 text-[13px] leading-5">
                  <strong className={analysisIsPartial || analysisFailed ? "text-amber-300" : "text-slate-200"}>{analysisIsPartial ? "Частичный результат" : analysisFailed ? "Анализ не выполнен" : "Анализ завершён"}</strong>
                  <small className="mt-0.5 block text-[12px] text-slate-500">{analysisIsPartial
                    ? "Часть исходника могла не обработаться — см. диагностики ниже."
                    : analysisFailed
                      ? "Анализатор не смог обработать исходник — см. диагностики ниже."
                      : `${analysis.methods.length} методов · диагностик: ${analysis.diagnostics.length}`}</small>
                </div>
              </div>
              {analysis.diagnostics.length ? (
                <div className="mt-2.5 grid gap-0.5">
                  {analysis.diagnostics.map((item, index) => (
                    <button className="flex w-full items-start gap-2 rounded border-0 bg-transparent px-1.5 py-1.5 text-left hover:bg-slate-800" type="button" key={`${item.code}-${index}`} onClick={() => openSourceRange(item.source_range)}>
                      <span className={`mt-1.5 size-2 shrink-0 rounded-full ${item.severity === "error" ? "bg-rose-400" : item.severity === "warning" ? "bg-amber-400" : "bg-blue-400"}`} />
                      <span className="grid min-w-0 flex-1 gap-0.5 text-[13px] leading-5 text-slate-300"><span className="line-clamp-2">{item.message}</span><small className="text-[11px] text-slate-500">{item.code} · строка {item.source_range.line ?? "—"}</small></span>
                      <ArrowUpRight className="mt-1 size-3.5 shrink-0 text-slate-600" />
                    </button>
                  ))}
                </div>
              ) : <div className="mt-2.5 flex items-center gap-1.5 rounded border border-emerald-900 bg-emerald-400/5 p-2 text-[13px] text-emerald-300"><Check size={15} /> Диагностик нет</div>}
            </div>
          ) : analysisState === "loading" ? (
            <div className="flex min-h-48 flex-col items-center justify-center gap-2 p-5 text-center"><span className="grid size-10 place-items-center rounded-lg border border-slate-700 bg-slate-900 text-cyan-400"><LoaderCircle className="spin" /></span><strong className="text-[14px] text-slate-300">Анализируем сохранённую ревизию</strong><p className="m-0 max-w-64 text-[13px] leading-5 text-slate-500">{analysisMessage}</p></div>
          ) : analysisState === "unavailable" ? (
            <div className="flex min-h-48 flex-col items-center justify-center gap-2 p-5 text-center"><span className="grid size-10 place-items-center rounded-lg border border-slate-700 bg-slate-900 text-amber-400"><Clock3 /></span><strong className="text-[14px] text-slate-300">Анализатор недоступен</strong><p className="m-0 max-w-64 text-[13px] leading-5 text-slate-500">{analysisMessage}</p><Button variant="outline" onClick={() => void runAnalysis()}><RefreshCw /> Повторить</Button></div>
          ) : analysisState === "error" ? (
            <div className="flex min-h-48 flex-col items-center justify-center gap-2 p-5 text-center"><span className="grid size-10 place-items-center rounded-lg border border-slate-700 bg-slate-900 text-rose-400"><AlertCircle /></span><strong className="text-[14px] text-slate-300">Анализ не выполнен</strong><p className="m-0 max-w-64 text-[13px] leading-5 text-slate-500">{analysisMessage}</p><Button variant="outline" onClick={() => void runAnalysis()}><RefreshCw /> Повторить</Button></div>
          ) : (
            <div className="flex min-h-48 flex-col items-center justify-center gap-2 p-5 text-center"><span className="grid size-10 place-items-center rounded-lg border border-slate-700 bg-slate-900 text-cyan-400"><ShieldCheck /></span><strong className="text-[14px] text-slate-300">{dirty ? "Анализ ожидает сохранения" : "Анализ запустится автоматически"}</strong><p className="m-0 max-w-64 text-[13px] leading-5 text-slate-500">{dirty ? "После автосохранения запустится JDT-анализ." : "Ожидаем запуска анализа."}</p></div>
          )}

          <section className="min-h-0 flex-1 overflow-auto" aria-label="Big O и рекомендации">
            <div className="flex h-9 items-center justify-between border-b border-slate-800 px-3 text-[13px] font-semibold text-slate-300"><span>Сложность</span><Sparkles className="size-4 text-violet-400" /></div>
            {methodAnalysisRequest.data ? (
              <>
                <div className="m-2.5 flex items-center gap-3 rounded-md border border-slate-700 bg-slate-900 px-3 py-2.5">
                  <strong className="font-mono text-[20px] font-semibold text-violet-300">{methodAnalysisRequest.data.complexity.time}</strong>
                  <small className="text-[12px] text-slate-500">уверенность: {methodAnalysisRequest.data.complexity.confidence}</small>
                </div>
                <p className="mx-2.5 mb-2.5 text-[13px] leading-5 text-slate-400">{methodAnalysisRequest.data.complexity.reason}</p>
                {methodAnalysisRequest.data.complexity.assumptions.length ? (
                  <details className="mx-2.5 mb-2 text-[12px] text-slate-500"><summary className="cursor-pointer">Допущения · {methodAnalysisRequest.data.complexity.assumptions.length}</summary><ul className="mt-1 [&_li]:leading-5">{methodAnalysisRequest.data.complexity.assumptions.map((item) => <li key={item}>{item}</li>)}</ul></details>
                ) : null}
                <div className="grid gap-1.5 px-2.5 pb-2.5">
                  <span className="text-[11px] font-semibold tracking-wide text-slate-500 uppercase">Рекомендации · {methodAnalysisRequest.data.findings.length}</span>
                  {methodAnalysisRequest.data.findings.length ? methodAnalysisRequest.data.findings.map((finding) => (
                    <details key={finding.id} className="rounded border border-slate-700 bg-slate-900">
                      <summary className="cursor-pointer px-2.5 py-2 text-[13px] leading-5 text-slate-200">{finding.message}</summary>
                      <div className="border-t border-slate-800 px-2.5 py-2 text-[12px] leading-5 text-slate-400">
                        <p className="m-0">{finding.explanation}</p>
                        <p className="mt-1.5 mb-0 text-slate-300">Рекомендация: {finding.recommendation}</p>
                        <Button variant="ghost" size="sm" className="mt-2" onClick={() => openSourceRange(finding.source_range)}>К коду</Button>
                        <small className="mt-1.5 block text-[11px] text-slate-500">Авто-правка не поддерживается — измените условие вручную на CFG, затем запустите проверку.</small>
                      </div>
                    </details>
                  )) : <div className="flex items-center gap-1.5 rounded border border-emerald-900 bg-emerald-400/5 p-2 text-[13px] text-emerald-300"><Check /> Рекомендаций нет</div>}
                </div>
              </>
            ) : activeMethod && !methodAnalysisRequest.error ? <div className="m-2.5 flex items-center gap-1.5 text-[13px] text-slate-500"><LoaderCircle className="spin size-4" /> Загружаем Big O…</div>
              : methodAnalysisRequest.error ? <div className="m-2.5 flex items-center gap-1.5 text-[13px] text-slate-500"><AlertCircle className="size-4" /> Big O недоступна</div>
                : <p className="m-2.5 text-[13px] text-slate-500">Выберите метод в обозревателе или на CFG.</p>}
          </section>
        </aside>
      </div>

      <button className="z-10 h-[5px] w-full touch-none cursor-row-resize border-0 bg-slate-900 p-0 hover:bg-cyan-400 focus-visible:bg-cyan-400 focus-visible:outline-none" type="button" role="separator" aria-label="Изменить высоту нижней панели" aria-orientation="horizontal" onPointerDown={(event) => paneSizes.begin("bottom", event)} onKeyDown={(event) => paneSizes.onKey("bottom", event)} />

      <section className="grid min-h-0 min-w-0 grid-rows-[38px_minmax(0,1fr)] border-t border-slate-800 bg-[#0b1120]" aria-label="Редактор и инструменты">
        <header className="flex min-w-0 items-stretch border-b border-slate-800 bg-[#0d1524]" role="tablist" aria-label="Нижняя панель">
          <button className={`relative inline-flex items-center gap-1.5 border-0 bg-transparent px-3 text-[13px] hover:bg-slate-900 [&_svg]:size-4 ${dockMode === "code" ? "text-cyan-100 after:absolute after:right-2 after:bottom-[-1px] after:left-2 after:h-0.5 after:bg-cyan-400" : "text-slate-400"}`} type="button" role="tab" aria-selected={dockMode === "code"} onClick={() => openDock("code")}><Code2 /> Код Java</button>
          <button className={`relative inline-flex items-center gap-1.5 border-0 bg-transparent px-3 text-[13px] hover:bg-slate-900 [&_svg]:size-4 ${dockMode === "revisions" ? "text-cyan-100 after:absolute after:right-2 after:bottom-[-1px] after:left-2 after:h-0.5 after:bg-cyan-400" : "text-slate-400"}`} type="button" role="tab" aria-selected={dockMode === "revisions"} onClick={() => openDock("revisions")}><History /> Ревизии</button>
          <button className={`relative inline-flex items-center gap-1.5 border-0 bg-transparent px-3 text-[13px] hover:bg-slate-900 [&_svg]:size-4 ${dockMode === "verification" ? "text-cyan-100 after:absolute after:right-2 after:bottom-[-1px] after:left-2 after:h-0.5 after:bg-cyan-400" : "text-slate-400"}`} type="button" role="tab" aria-selected={dockMode === "verification"} onClick={() => openDock("verification")}><ShieldCheck /> Проверка</button>
        </header>
        <div className="relative min-h-0 min-w-0 overflow-hidden">
          <section className={`flex size-full min-h-0 flex-col bg-[#08101d] ${dockMode === "code" ? "" : "pointer-events-none invisible absolute inset-0"}`} aria-label="Редактор Java" aria-hidden={dockMode !== "code"}>
            <div className="min-h-0 flex-1 overflow-hidden">
              <JavaEditor ref={editorRef} value={initialSource} diagnostics={currentDiagnostics} focusRange={selectedSourceRange} onDirtyChange={onEditorDirtyChange} onMetricsChange={setSourceStats} onSourceSelection={setSelectedSourceRange} />
            </div>
            <footer className="flex h-[26px] shrink-0 items-center justify-between border-t border-slate-800 bg-[#0c1422] px-2.5 font-mono text-[11px] text-slate-500 [&_span]:inline-flex [&_span]:items-center [&_span]:gap-1.5 [&_svg]:size-3.5"><span><TerminalSquare /> {project.filename}</span><span>{lineCount} строк · {byteLength.toLocaleString("ru-RU")} байт</span><span>{dirty ? "CFG и анализ обновятся после сохранения" : "Исходник синхронизирован"}</span></footer>
          </section>
          {dockMode === "revisions" ? (
            <RevisionPanel currentRevision={revision} dirty={dirty} revisions={revisions} revisionsError={revisionsError} selectedRevision={selectedHistoryRevision} onSelectedRevision={(value) => { setSelectedHistoryRevision(value); setShowRevisionDiff(false); }} historicalRevisionReady={Boolean(historicalRevision)} historicalRevisionError={historicalRevisionError} showDiff={showRevisionDiff} onToggleDiff={() => setShowRevisionDiff((shown) => !shown)} diffRows={revisionDiffRows} onClose={() => openDock("code")} />
          ) : null}
          {dockMode === "verification" ? (
            <VerificationPanel currentRevision={revision} dirty={dirty} revisions={revisions} revisionsError={revisionsError} baselineRevision={baselineRevision} candidateRevision={candidateRevision} onBaselineRevision={setBaselineRevision} onCandidateRevision={setCandidateRevision} cases={verificationCases} onCases={setVerificationCases} mainClass={mainClass} onMainClass={setMainClass} sourceLevel={sourceLevel} onSourceLevel={setSourceLevel} state={verificationState} report={verificationReport} error={verificationError} onRun={() => void runVerification()} onClose={() => openDock("code")} />
          ) : null}
          {dockMode === "benchmark" ? (
            <BenchmarkPanel revisions={revisions} baseline={baselineRevision} candidate={candidateRevision} onBaseline={setBaselineRevision} onCandidate={setCandidateRevision} workload={benchmarkWorkload} onWorkload={setBenchmarkWorkload} iterations={benchmarkIterations} onIterations={setBenchmarkIterations} mainClass={mainClass} onMainClass={setMainClass} sourceLevel={sourceLevel} onSourceLevel={setSourceLevel} report={benchmarkReport} error={benchmarkError} busy={benchmarkState === "running"} dirty={dirty} onRun={() => void runRuntimeBenchmark()} onClose={() => openDock("code")} />
          ) : null}
        </div>
      </section>
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
    <section className="grid size-full min-h-0 grid-cols-[minmax(190px,.42fr)_minmax(200px,.45fr)_minmax(420px,1.4fr)] items-start gap-3 overflow-auto bg-[#0a1220] p-3" aria-label="Текстовое сравнение ревизий">
      <div className="flex justify-between gap-2"><div className="grid gap-2"><strong className="text-[14px] text-slate-200">Текстовый diff исходника</strong><span className="text-[12px] leading-5 text-slate-500">Показывает только изменения текста, не эквивалентность поведения.</span></div><button className="border-0 bg-transparent text-lg text-slate-500" type="button" onClick={onClose} aria-label="Закрыть историю">×</button></div>
      {revisionsError ? <p role="alert">Не удалось загрузить ревизии.</p> : !revisions ? <p role="status">Загружаем ревизии…</p> : (
        <div className="grid gap-2 text-[12px] text-slate-500">
          <label htmlFor="revision-select">Сравнить ревизию {currentRevision} с</label>
          <select className="h-8 w-full rounded border border-slate-700 bg-[#0c1422] px-2 font-mono text-[13px] text-slate-300" id="revision-select" value={selectedRevision ?? ""} onChange={(event) => onSelectedRevision(event.target.value ? Number(event.target.value) : null)}>
            <option value="">Выберите ревизию</option>
            {revisions.filter((item) => item.revision !== currentRevision).map((item) => <option key={item.revision} value={item.revision}>Ревизия {item.revision}{item.has_analysis ? " · есть анализ" : ""}</option>)}
          </select>
          {selectedRevision !== null ? <Button variant="outline" disabled={dirty || !historicalRevisionReady || Boolean(historicalRevisionError)} onClick={onToggleDiff}>{showDiff ? "Скрыть diff" : "Показать diff"}</Button> : null}
          {dirty ? <small>Сначала дождитесь автосохранения.</small> : null}
          {historicalRevisionError ? <small role="alert">Ревизия недоступна.</small> : null}
        </div>
      )}
      {showDiff && diffRows.length ? (
        <div className="max-h-full min-w-0 overflow-auto rounded border border-slate-700 bg-[#08101d] font-mono text-[12px] leading-6" aria-label="Старая ревизия слева, текущая справа">
          <div className="sticky top-0 z-10 grid min-w-[660px] grid-cols-[40px_minmax(240px,1fr)_40px_minmax(240px,1fr)] border-b border-slate-700 bg-slate-900 py-1.5 text-slate-500 [&_span]:col-span-2 [&_span]:pl-2"><span>РЕВИЗИЯ {selectedRevision}</span><span>РЕВИЗИЯ {currentRevision}</span></div>
          <div className="min-w-[660px]">
            {diffRows.map((row, index) => row.kind === "gap"
              ? <div className="min-w-[660px] bg-slate-900 py-1 text-center text-slate-500" key={`gap-${index}`}>··· {row.hidden} неизменённых строк ···</div>
              : <div className="grid min-w-[660px] grid-cols-[40px_minmax(240px,1fr)_40px_minmax(240px,1fr)] whitespace-pre" key={`row-${index}`}>
                <span className="pr-2 text-right text-slate-600 select-none">{row.oldLine ?? ""}</span><code className={`min-h-6 border-r border-slate-700 px-2 ${row.kind === "changed" || row.kind === "removed" ? "bg-rose-950/40 text-rose-300" : "text-slate-400"}`}>{row.oldText ?? ""}</code>
                <span className="pr-2 text-right text-slate-600 select-none">{row.newLine ?? ""}</span><code className={`min-h-6 px-2 ${row.kind === "changed" || row.kind === "added" ? "bg-emerald-950/40 text-emerald-300" : "text-slate-400"}`}>{row.newText ?? ""}</code>
              </div>)}
          </div>
        </div>
      ) : showDiff ? <p className="flex items-center gap-1.5 text-[13px] text-emerald-300"><Check size={14} /> Тексты ревизий совпадают.</p> : null}
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
  mainClass,
  onMainClass,
  sourceLevel,
  onSourceLevel,
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
  mainClass: string;
  onMainClass: (value: string) => void;
  sourceLevel: string;
  onSourceLevel: (value: string) => void;
  state: "idle" | "running" | "complete" | "error";
  report: VerificationReport | null;
  error: string;
  onRun: () => void;
  onClose: () => void;
}) {
  const canRun = !dirty && state !== "running" && cases.length > 0
    && cases.every((item) => item.name.trim().length > 0);

  return (
    <section className="grid size-full min-h-0 grid-cols-[minmax(200px,.42fr)_minmax(340px,1fr)] items-start gap-3 overflow-auto bg-[#0a1220] p-3" aria-label="Поведенческое сравнение ревизий">
      <div className="flex justify-between gap-2"><div className="grid gap-2"><strong className="text-[14px] text-slate-200">Поведенческое сравнение</strong><span className="text-[12px] leading-5 text-slate-500">Docker runner компилирует две ревизии и на одинаковых stdin-сценариях сравнивает stdout, stderr и exit code. Время выполнения не измеряется — ускорение не утверждается.</span></div><button className="border-0 bg-transparent text-lg text-slate-500" type="button" onClick={onClose} aria-label="Закрыть проверку">×</button></div>
      {revisionsError ? <p role="alert">Не удалось загрузить ревизии.</p> : !revisions ? <p role="status">Загружаем ревизии…</p> : (
        <div className="grid gap-2.5">
          <div className="grid grid-cols-[1fr_auto_1fr] items-end gap-2 text-[12px] text-slate-500 [&_label]:grid [&_label]:gap-1 [&_select]:h-8 [&_select]:w-full [&_select]:rounded [&_select]:border [&_select]:border-slate-700 [&_select]:bg-[#0c1422] [&_select]:px-2 [&_select]:font-mono [&_select]:text-[13px] [&_select]:text-slate-300">
            <label>Базовая ревизия<select value={baselineRevision} onChange={(event) => onBaselineRevision(Number(event.target.value))}>{revisions.map((item) => <option key={item.revision} value={item.revision}>Ревизия {item.revision}</option>)}</select></label>
            <GitCompareArrows />
            <label>Кандидат<select value={candidateRevision} onChange={(event) => onCandidateRevision(Number(event.target.value))}>{revisions.map((item) => <option key={item.revision} value={item.revision}>Ревизия {item.revision}{item.revision === currentRevision ? " · текущая" : ""}</option>)}</select></label>
          </div>
          <div className="grid max-w-lg grid-cols-[1fr_120px] gap-2 text-[12px] text-slate-500 [&_label]:grid [&_label]:gap-1">
            <label>Главный класс<input aria-label="Главный класс Java" value={mainClass} onChange={(event) => onMainClass(event.target.value)} placeholder="например Organizer" className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[12px] text-slate-200" /></label>
            <label>Уровень Java<select aria-label="Уровень Java" value={sourceLevel} onChange={(event) => onSourceLevel(event.target.value)} className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200">{["8", "11", "17", "21"].map((level) => <option key={level} value={level}>{level}</option>)}</select></label>
          </div>
          <div className="grid gap-1.5">
            {cases.map((item, index) => (
              <div className="grid grid-cols-[minmax(120px,.4fr)_minmax(180px,1fr)_auto] gap-1.5 [&_input]:h-8 [&_input]:w-full [&_input]:rounded [&_input]:border [&_input]:border-slate-700 [&_input]:bg-[#08101d] [&_input]:px-2 [&_input]:font-mono [&_input]:text-[12px] [&_input]:text-slate-300 [&_textarea]:min-h-12 [&_textarea]:w-full [&_textarea]:resize-y [&_textarea]:rounded [&_textarea]:border [&_textarea]:border-slate-700 [&_textarea]:bg-[#08101d] [&_textarea]:p-2 [&_textarea]:font-mono [&_textarea]:text-[12px] [&_textarea]:text-slate-300" key={index}>
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
      {state === "error" ? <div className="col-span-full flex items-center gap-1.5 rounded border border-rose-900 bg-rose-950/20 p-2.5 text-[13px] text-rose-300" role="alert"><AlertCircle />{error}{error.includes("main_class_required") ? " Укажите класс с main(String[])." : ""}</div> : null}
      {report ? <VerificationResult report={report} /> : null}
    </section>
  );
}

function VerificationResult({ report }: { report: VerificationReport }) {
  return (
    <section className={`col-span-full grid gap-2 rounded border p-3 text-[12px] ${report.status === "passed" ? "border-emerald-900 bg-emerald-950/20" : "border-rose-900 bg-rose-950/20"}`}>
      <div className="flex flex-wrap justify-between gap-2">
        <strong className="text-[14px]">{report.behaviorally_verified ? "Сценарии совпали" : report.status === "compilation_failed" ? "Компиляция не прошла" : "Обнаружено различие"}</strong>
        <span>Отчёт {report.report_id} · ревизии {report.baseline_revision} → {report.candidate_revision}</span>
      </div>
      <p className="m-0 leading-5 text-slate-400">{report.explanation}</p>
      <div className="flex flex-wrap gap-1.5 [&_span]:rounded [&_span]:bg-slate-800 [&_span]:px-1.5 [&_span]:py-1 [&_span]:font-mono [&_span]:text-slate-400">
        <span>База: {report.baseline_compilation.status}</span>
        <span>Кандидат: {report.candidate_compilation.status}</span>
        <span>Выполнено: {report.executed_test_cases}/{report.requested_test_cases}</span>
      </div>
      {report.test_cases.map((item) => (
        <div className="grid gap-1 border-t border-slate-700 pt-2" key={item.name}>
          <strong className="flex items-center gap-1.5 text-[13px]">{item.passed ? <Check size={14} /> : <AlertCircle size={14} />}{item.name}</strong>
          {item.mismatches.length ? <ul>{item.mismatches.map((mismatch) => <li key={mismatch.field}>{mismatch.field}: {mismatch.explanation}</li>)}</ul> : <span>stdout, stderr, exit code и лимиты совпали.</span>}
        </div>
      ))}
      <small className="leading-5">Результат относится только к указанным сценариям и не доказывает полную эквивалентность программ. Длительности (компиляции, запуска контейнера, алгоритма) в отчёте не измеряются.</small>
    </section>
  );
}

type PaneKind = "left" | "right" | "bottom";

function useIdePaneSizes() {
  const [sizes, setSizes] = useState({ left: 224, right: 328, bottom: 292 });
  const cleanupRef = useRef<(() => void) | null>(null);

  useEffect(() => () => cleanupRef.current?.(), []);

  const begin = useCallback((kind: PaneKind, event: ReactPointerEvent<HTMLButtonElement>) => {
    event.preventDefault();
    cleanupRef.current?.();
    const start = { x: event.clientX, y: event.clientY, sizes };
    const cursor = kind === "bottom" ? "row-resize" : "col-resize";
    document.body.style.cursor = cursor;
    document.body.style.userSelect = "none";

    const move = (moveEvent: PointerEvent) => {
      setSizes({
        left: kind === "left" ? clamp(start.sizes.left + moveEvent.clientX - start.x, 168, 360) : start.sizes.left,
        right: kind === "right" ? clamp(start.sizes.right + start.x - moveEvent.clientX, 260, 460) : start.sizes.right,
        bottom: kind === "bottom" ? clamp(start.sizes.bottom + start.y - moveEvent.clientY, 180, 520) : start.sizes.bottom,
      });
    };
    const finish = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", finish);
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
      cleanupRef.current = null;
    };
    cleanupRef.current = finish;
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", finish, { once: true });
  }, [sizes]);

  const onKey = useCallback((kind: PaneKind, event: ReactKeyboardEvent<HTMLButtonElement>) => {
    const delta = event.key === "ArrowRight" || event.key === "ArrowUp" ? 12
      : event.key === "ArrowLeft" || event.key === "ArrowDown" ? -12
        : 0;
    if (!delta) return;
    event.preventDefault();
    setSizes((current) => ({
      left: kind === "left" ? clamp(current.left + delta, 168, 360) : current.left,
      right: kind === "right" ? clamp(current.right - delta, 260, 460) : current.right,
      bottom: kind === "bottom" ? clamp(current.bottom + delta, 180, 520) : current.bottom,
    }));
  }, []);

  return {
    begin,
    onKey,
    style: {
      "--ide-left": `${sizes.left}px`,
      "--ide-right": `${sizes.right}px`,
      "--ide-bottom": `${sizes.bottom}px`,
    } as CSSProperties,
  };
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value));
}

function classFromMethodId(methodId: string) {
  const dot = methodId.lastIndexOf(".");
  return dot > 0 ? methodId.slice(0, dot) : methodId;
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
  if (cause.code === "main_class_required") return `${cause.code}: укажите класс с main(String[]).`;
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

async function buildRevisionDiffRows(oldSource: string, newSource: string): Promise<RevisionDiffRow[]> {
  const { diffLines } = await import("diff");
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
