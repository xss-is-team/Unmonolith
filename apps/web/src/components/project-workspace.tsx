"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import useSWR from "swr";
import gsap from "gsap";
import { useGSAP } from "@gsap/react";
import {
  AlertCircle,
  ArrowLeft,
  ArrowUpRight,
  Check,
  CircleDot,
  Clock3,
  Code2,
  GitBranch,
  LoaderCircle,
  Play,
  RefreshCw,
  Save,
  ShieldCheck,
  Sparkles,
  TerminalSquare,
  Waypoints,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Brand } from "@/components/brand";
import {
  analyzeProject,
  ApiError,
  getProjectWorkspace,
  MAX_SOURCE_BYTES,
  type AnalysisResult,
  type Project,
  updateProjectSource,
} from "@/lib/api";

gsap.registerPlugin(useGSAP);
const NO_DIAGNOSTICS: AnalysisResult["diagnostics"] = [];

const JavaEditor = dynamic(
  () => import("@/components/java-editor").then((module) => module.JavaEditor),
  { ssr: false, loading: () => <div className="editor-loading">Открываем редактор…</div> },
);

const CfgViewer = dynamic(
  () => import("@/components/cfg-viewer").then((module) => module.CfgViewer),
  { ssr: false, loading: () => <section className="cfg-panel cfg-panel--loading">Загружаем просмотрщик CFG…</section> },
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
      reload={() => mutate()}
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
  reload: () => Promise<unknown>;
}) {
  const [project, setProject] = useState(initialProject);
  const editorRef = useRef<import("@/components/java-editor").JavaEditorHandle>(null);
  const [dirty, setDirty] = useState(false);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "conflict" | "error">("idle");
  const dirtyRef = useRef(false);
  const revisionRef = useRef(initialRevision);
  const saveStateRef = useRef(saveState);
  const saveInFlightRef = useRef(false);
  const autosaveTimerRef = useRef<number | undefined>(undefined);
  const saveSourceRef = useRef<() => Promise<void>>(async () => {});
  const analysisInFlightRef = useRef(false);
  const rerunAnalysisRef = useRef(false);
  const analysisTimerRef = useRef<number | undefined>(undefined);
  const runAnalysisRef = useRef<() => Promise<void>>(async () => {});
  const [sourceStats, setSourceStats] = useState(() => sourceMetrics(initialSource));
  const [revision, setRevision] = useState(initialRevision);
  const [saveError, setSaveError] = useState("");
  const [analysisState, setAnalysisState] = useState<"idle" | "loading" | "complete" | "unavailable" | "error">("idle");
  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null);
  const [analysisError, setAnalysisError] = useState("");
  const [selectedSourceRange, setSelectedSourceRange] = useState<{ start: number; length: number } | null>(null);
  const [cfgMethodId, setCfgMethodId] = useState("");
  const [cfgMode, setCfgMode] = useState<"api" | "fixture">("api");
  const [viewMode, setViewMode] = useState<"source" | "cfg">("source");
  const previousViewMode = useRef(viewMode);
  const workspaceRef = useRef<HTMLElement>(null);
  const [reloading, setReloading] = useState(false);
  const { lineCount, byteLength } = sourceStats;
  const selectSourceRange = useCallback((range: { start: number; length: number } | null) => {
    setSelectedSourceRange(range);
  }, []);
  const scheduleAutosave = useCallback(() => {
    if (autosaveTimerRef.current !== undefined) window.clearTimeout(autosaveTimerRef.current);
    if (saveStateRef.current === "conflict") return;
    autosaveTimerRef.current = window.setTimeout(() => {
      autosaveTimerRef.current = undefined;
      void saveSourceRef.current();
    }, 500);
  }, []);
  const scheduleAnalysis = useCallback(() => {
    if (analysisTimerRef.current !== undefined) window.clearTimeout(analysisTimerRef.current);
    analysisTimerRef.current = window.setTimeout(() => {
      analysisTimerRef.current = undefined;
      if (analysisInFlightRef.current) {
        rerunAnalysisRef.current = true;
        return;
      }
      void runAnalysisRef.current();
    }, 700);
  }, []);
  const setSaveStatus = useCallback((next: typeof saveState) => {
    saveStateRef.current = next;
    setSaveState(next);
  }, []);

  useGSAP(() => {
    const media = gsap.matchMedia();
    media.add("(prefers-reduced-motion: no-preference)", () => {
      const timeline = gsap.timeline({ defaults: { ease: "power2.out" } });
      timeline
        .fromTo(".workspace-top", { autoAlpha: 0, y: -7 }, { autoAlpha: 1, y: 0, duration: 0.28 })
        .fromTo(".workspace-viewbar", { autoAlpha: 0, y: 7 }, { autoAlpha: 1, y: 0, duration: 0.28 }, "-=0.12")
        .fromTo(".editor-card, .cfg-panel, .analysis-panel", { autoAlpha: 0, y: 9 }, { autoAlpha: 1, y: 0, duration: 0.34, stagger: 0.05 }, "-=0.1");
    });
    return () => media.revert();
  }, { scope: workspaceRef });

  useGSAP(() => {
    if (previousViewMode.current === viewMode) return;
    previousViewMode.current = viewMode;

    const media = gsap.matchMedia();
    media.add("(prefers-reduced-motion: no-preference)", () => {
      gsap.fromTo(viewMode === "source" ? ".editor-card" : ".cfg-panel", { autoAlpha: 0, y: 7 }, { autoAlpha: 1, y: 0, duration: 0.24, ease: "power2.out" });
    });
    return () => media.revert();
  }, { scope: workspaceRef, dependencies: [viewMode], revertOnUpdate: true });

  useGSAP(() => {
    if (analysisState !== "complete") return;

    const media = gsap.matchMedia();
    media.add("(prefers-reduced-motion: no-preference)", () => {
      const targets = gsap.utils.toArray<Element>(".method-row, .diagnostic-item");
      gsap.fromTo(targets, { autoAlpha: 0, y: 5 }, {
        autoAlpha: 1, y: 0, duration: 0.2,
        stagger: targets.length > 30 ? 0 : 0.025,
        ease: "power2.out",
      });
    });
    return () => media.revert();
  }, { scope: workspaceRef, dependencies: [analysisState], revertOnUpdate: true });

  const saveSource = useCallback(async () => {
    if (!dirtyRef.current || saveInFlightRef.current) return;
    const source = editorRef.current?.getValue() ?? initialSource;
    if (new TextEncoder().encode(source).byteLength > MAX_SOURCE_BYTES) {
      setSaveError("Исходник превышает лимит API — 2 МиБ.");
      setSaveStatus("error");
      return;
    }
    if (autosaveTimerRef.current !== undefined) {
      window.clearTimeout(autosaveTimerRef.current);
      autosaveTimerRef.current = undefined;
    }
    saveInFlightRef.current = true;
    setSaveStatus("saving");
    setSaveError("");
    try {
      const updated = await updateProjectSource(projectId, source, revisionRef.current);
      setProject(updated);
      revisionRef.current = updated.revision;
      setRevision(updated.revision);
      editorRef.current?.markSaved(source);
      setAnalysis(null);
      setAnalysisState("idle");
      scheduleAnalysis();
      setSaveStatus("saved");
      window.setTimeout(() => {
        if (saveStateRef.current === "saved") setSaveStatus("idle");
      }, 2200);
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
  }, [initialSource, projectId, scheduleAnalysis, scheduleAutosave, setSaveStatus]);

  useEffect(() => {
    saveSourceRef.current = saveSource;
  }, [saveSource]);

  const onEditorDirtyChange = useCallback((nextDirty: boolean) => {
    dirtyRef.current = nextDirty;
    setDirty((current) => current === nextDirty ? current : nextDirty);
    if (nextDirty) {
      setSaveError("");
      if (analysisInFlightRef.current) setAnalysisState("idle");
      if (saveStateRef.current === "error") {
        setSaveStatus("idle");
      }
      scheduleAutosave();
    } else if (autosaveTimerRef.current !== undefined) {
      window.clearTimeout(autosaveTimerRef.current);
      autosaveTimerRef.current = undefined;
    }
  }, [scheduleAutosave, setSaveStatus]);

  useEffect(() => () => {
    if (autosaveTimerRef.current !== undefined) window.clearTimeout(autosaveTimerRef.current);
    if (analysisTimerRef.current !== undefined) window.clearTimeout(analysisTimerRef.current);
  }, []);

  const runAnalysis = useCallback(async () => {
    if (dirtyRef.current || analysisInFlightRef.current) return;
    const requestedRevision = revisionRef.current;
    analysisInFlightRef.current = true;
    setAnalysisState("loading");
    setAnalysisError("");
    setSelectedSourceRange(null);
    try {
      const result = await analyzeProject(projectId);
      if (result.revision !== requestedRevision || revisionRef.current !== requestedRevision || dirtyRef.current) {
        setAnalysis(null);
        setAnalysisState("idle");
        if (!dirtyRef.current && revisionRef.current !== requestedRevision) rerunAnalysisRef.current = true;
        return;
      }
      setAnalysis(result);
      setAnalysisState("complete");
    } catch (cause) {
      if (revisionRef.current !== requestedRevision || dirtyRef.current) {
        setAnalysis(null);
        setAnalysisState("idle");
        if (!dirtyRef.current && revisionRef.current !== requestedRevision) rerunAnalysisRef.current = true;
        return;
      }
      if (cause instanceof ApiError && cause.code === "analyzer_unavailable") {
        setAnalysisState("unavailable");
        setAnalysisError("Сервис анализа сейчас недоступен. Исходник сохранён; попробуйте запустить анализ позже.");
      } else {
        setAnalysisState("error");
        setAnalysisError(analysisErrorMessage(cause));
      }
    } finally {
      analysisInFlightRef.current = false;
      if (rerunAnalysisRef.current && !dirtyRef.current) {
        rerunAnalysisRef.current = false;
        scheduleAnalysis();
      }
    }
  }, [projectId, scheduleAnalysis]);
  useEffect(() => {
    runAnalysisRef.current = runAnalysis;
  }, [runAnalysis]);

  useEffect(() => {
    function onShortcut(event: KeyboardEvent) {
      if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
        event.preventDefault();
        void runAnalysis();
      }
    }
    window.addEventListener("keydown", onShortcut);
    return () => window.removeEventListener("keydown", onShortcut);
  }, [runAnalysis]);

  useEffect(() => {
    if (!selectedSourceRange || !window.matchMedia("(max-width: 820px)").matches) return;
    document.querySelector(".editor-card")?.scrollIntoView({ block: "start" });
  }, [selectedSourceRange]);

  async function reloadSource() {
    setReloading(true);
    try {
      await reload();
    } finally {
      setReloading(false);
    }
  }

  return (
    <main ref={workspaceRef} className="workspace-screen workspace-screen--ide">
      <header className="workspace-top">
        <div className="ide-project-path"><Brand compact /><div className="workspace-breadcrumb"><span>ФАЙЛ</span><i>/</i><strong>{project.filename}</strong><small>РЕД. {revision}</small></div></div>
        <div className="workspace-actions ide-actions">
          <span className={`save-indicator save-indicator--${dirty ? "dirty" : saveState}`}>
            {saveState === "saving" ? <LoaderCircle className="spin" size={14} /> : dirty ? <CircleDot size={14} /> : <Check size={14} />}
            {saveState === "saving" ? "Сохраняем" : dirty ? "Не сохранено" : saveState === "saved" ? "Сохранено" : "Сохранено"}
          </span>
          <Button className="action-button action-button--quiet" onClick={() => void saveSource()} disabled={!dirty || saveState === "saving"}>
            {saveState === "saving" ? <LoaderCircle className="spin" /> : <Save />}{saveState === "saving" ? "Сохранение" : "Сохранить сейчас"}
          </Button>
          <Button className="action-button action-button--primary" onClick={() => void runAnalysis()} disabled={dirty || analysisState === "loading"} title="Анализ сохранённой версии · Ctrl+Enter" aria-keyshortcuts="Control+Enter Meta+Enter">
            {analysisState === "loading" ? <LoaderCircle className="spin" /> : <Play />}{analysisState === "loading" ? "Анализируем" : "Запустить анализ"}
          </Button>
        </div>
        <Link className="back-link" href="/"><ArrowLeft size={15} /> Другой файл</Link>
      </header>

      {saveState === "conflict" ? (
        <div className="inline-alert" role="alert"><AlertCircle size={17} /><span>Исходник уже изменился на сервере. Перезагрузите его, чтобы не затереть чужую ревизию.</span><Button variant="outline" onClick={() => void reloadSource()} disabled={reloading}><RefreshCw className={reloading ? "spin" : ""} /> Перезагрузить</Button></div>
      ) : saveState === "error" ? (
        <div className="inline-alert" role="alert"><AlertCircle size={17} /><span>{saveError || "Не удалось сохранить. Проверьте соединение с API."}</span></div>
      ) : null}

      <div className="workspace-grid">
        <section className="ide-center" aria-label="Исходник и граф">
          <div className="workspace-viewbar">
            <div className="workspace-tabs" role="tablist" aria-label="Представление исходника" onKeyDown={(event) => {
              const tabs = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
              const current = tabs.indexOf(event.target as HTMLButtonElement);
              const next = event.key === "ArrowRight" ? (current + 1) % tabs.length
                : event.key === "ArrowLeft" ? (current - 1 + tabs.length) % tabs.length
                  : event.key === "Home" ? 0
                    : event.key === "End" ? tabs.length - 1
                      : current;
              if (next !== current && tabs[next]) {
                event.preventDefault();
                tabs[next].focus();
                tabs[next].click();
              }
            }}>
              <button className={viewMode === "source" ? "workspace-tab workspace-tab--active" : "workspace-tab"} type="button" role="tab" aria-selected={viewMode === "source"} onClick={() => setViewMode("source")}>
                <Code2 size={16} /> Исходник
              </button>
              <button className={viewMode === "cfg" ? "workspace-tab workspace-tab--active" : "workspace-tab"} type="button" role="tab" aria-selected={viewMode === "cfg"} onClick={() => setViewMode("cfg")}>
                <Waypoints size={16} /> Поток выполнения <span className="tab-status">M2</span>
              </button>
            </div>
            <span className="source-summary">{lineCount} строк <i /> {byteLength.toLocaleString("ru-RU")} байт</span>
          </div>

          {viewMode === "source" ? (
            <section className="editor-card" aria-label="Редактор Java">
              <div className="editor-toolbar">
                <div className="editor-file"><span className="java-file-icon">J</span><strong>{project.filename}</strong><span className="file-tag">JAVA</span></div>
                <div className="editor-toolbar-meta"><span><Code2 size={14} /> UTF-8</span><span><GitBranch size={14} /> Ред. {revision}</span></div>
              </div>
              <div className="editor-body"><JavaEditor
                ref={editorRef}
                value={initialSource}
                diagnostics={analysisState === "complete" && !dirty ? analysis?.diagnostics ?? NO_DIAGNOSTICS : NO_DIAGNOSTICS}
                focusRange={selectedSourceRange}
                onDirtyChange={onEditorDirtyChange}
                onMetricsChange={setSourceStats}
                onSourceSelection={(range) => setSelectedSourceRange((current) =>
                  current?.start === range?.start && current?.length === range?.length ? current : range,
                )}
              /></div>
              <footer className="editor-footer"><span><TerminalSquare size={14} /> Java</span><span>{dirty ? "Есть несохранённые изменения" : "Исходник не изменён анализом"}</span></footer>
            </section>
          ) : (
            <CfgViewer
              projectId={projectId}
              revision={revision}
              analysis={analysisState === "complete" && !dirty ? analysis : null}
              selectedSourceRange={selectedSourceRange}
              selectedMethodId={cfgMethodId}
              onSelectedMethodIdChange={setCfgMethodId}
              mode={cfgMode}
              onModeChange={setCfgMode}
              onSourceRangeSelect={(range) => { selectSourceRange(range); if (range) setViewMode("source"); }}
            />
          )}
        </section>

        <aside className="analysis-panel">
          <div className="panel-heading"><div><span className="panel-kicker">ОТЧЁТ</span><h2>Анализ кода</h2></div><span className={`worker-mark worker-mark--${analysisState}`}><Sparkles size={16} /></span></div>

          {analysisState === "complete" && analysis && !dirty ? (
            <div className="analysis-result">
              <div className="result-banner"><span className="result-check"><Check size={15} /></span><div><strong>{analysis.status === "completed" ? "Анализ завершён" : "Частичный результат"}</strong><small>Ревизия {analysis.revision} · протокол {analysis.protocol_version}</small></div></div>
              <div className="result-stats"><div><strong>{analysis.methods.length}</strong><span>{russianNoun(analysis.methods.length, "метод", "метода", "методов")}</span></div><div><strong>{analysis.diagnostics.length}</strong><span>{russianNoun(analysis.diagnostics.length, "замечание", "замечания", "замечаний")}</span></div></div>
              {analysis.methods.length ? <div className="method-list"><span className="list-label">МЕТОДЫ <i>·</i> НАЖМИТЕ, ЧТОБЫ ОТКРЫТЬ В КОДЕ</span>{analysis.methods.slice(0, 12).map((method) => <button className={`method-row${selectedSourceRange?.start === method.source_range.start ? " method-row--active" : ""}`} type="button" key={method.id} onClick={() => { setViewMode("source"); setSelectedSourceRange(method.source_range); }}><Code2 size={14} /><span>{method.name}</span><small>стр. {method.source_range.line ?? "—"}</small><ArrowUpRight className="method-jump" size={13} /></button>)}{analysis.methods.length > 12 ? <small className="more-methods">Показаны первые 12 из {analysis.methods.length}</small> : null}</div> : null}
              {analysis.diagnostics.length ? <div className="diagnostic-list"><span className="list-label">ДИАГНОСТИКИ <i>·</i> ПЕРЕЙТИ К СТРОКЕ</span>{analysis.diagnostics.map((item, index) => <button className={`diagnostic-item diagnostic-item--${item.severity}${selectedSourceRange?.start === item.source_range.start ? " diagnostic-item--active" : ""}`} type="button" key={`${item.code}-${index}`} onClick={() => { setViewMode("source"); setSelectedSourceRange(item.source_range); }}><span className={`diagnostic-dot diagnostic-dot--${item.severity}`} /><span className="diagnostic-copy"><strong>{item.code}</strong><span>{item.message}</span><small>Строка {item.source_range.line ?? "—"}</small></span><ArrowUpRight className="diagnostic-jump" size={14} /></button>)}</div> : <div className="empty-diagnostics"><Check size={15} /> Замечаний нет</div>}
            </div>
          ) : analysisState === "unavailable" ? (
            <div className="worker-state worker-state--unavailable"><span className="state-icon"><Clock3 /></span><strong>Анализатор недоступен</strong><p>{analysisError}</p><Button variant="outline" onClick={() => void runAnalysis()}><RefreshCw /> Повторить анализ</Button><div className="state-footnote"><span className="status-pip status-pip--amber" /> API доступен · анализатор не ответил</div></div>
          ) : analysisState === "error" ? (
            <div className="worker-state worker-state--error"><span className="state-icon"><AlertCircle /></span><strong>Анализ не выполнен</strong><p>{analysisError}</p><Button variant="outline" onClick={() => void runAnalysis()}><RefreshCw /> Попробовать снова</Button></div>
          ) : (
            <div className="worker-state"><span className="state-icon"><ShieldCheck /></span><strong>{analysisState === "loading" ? "Разбираем исходник" : dirty ? "Отчёт устарел" : "Отчёт появится здесь"}</strong><p>{analysisState === "loading" ? "Ищем методы и диагностики." : dirty ? `Результат относится к сохранённой ревизии ${analysis?.revision ?? revision}. После автосохранения он обновится.` : "Нажмите «Запустить анализ» — найдём методы и замечания."}</p>{analysisState === "loading" ? <div className="state-footnote"><span className="status-pip status-pip--pulse" />Редакция {revision}</div> : null}</div>
          )}

        </aside>
      </div>

      <footer className="workspace-bottom"><span>Автосохранение после паузы · 500 мс</span><span><ShieldCheck size={13} /> Приватная сессия</span></footer>
    </main>
  );
}

function saveErrorMessage(cause: unknown) {
  if (!(cause instanceof ApiError)) {
    return "Не удалось сохранить. Проверьте соединение с API.";
  }
  switch (cause.code) {
    case "unauthorized":
      return "Гостевая сессия завершилась. Вернитесь на главную и загрузите файл заново.";
    case "project_not_found":
      return "Проект больше не найден в этой гостевой сессии.";
    case "invalid_request":
      return "API не принял исходник. Проверьте его размер: не более 2 МиБ.";
    default:
      return `Не удалось сохранить (HTTP ${cause.status}).`;
  }
}

function analysisErrorMessage(cause: unknown) {
  if (!(cause instanceof ApiError)) {
    return "Не удалось связаться с API. Проверьте соединение и повторите анализ.";
  }
  switch (cause.code) {
    case "analyzer_timeout":
      return "Анализ занял слишком много времени. Повторите попытку.";
    case "analyzer_invalid_output":
      return "Анализатор вернул некорректный результат. Исходник не изменён.";
    case "analyzer_failed":
      return "Не удалось разобрать этот файл. Исходник не изменён.";
    case "unauthorized":
      return "Гостевая сессия завершилась. Загрузите файл заново.";
    case "project_not_found":
      return "Проект больше не найден в этой гостевой сессии.";
    default:
      return `Анализ не выполнен (HTTP ${cause.status}).`;
  }
}

function russianNoun(count: number, one: string, few: string, many: string) {
  const form = new Intl.PluralRules("ru").select(count);
  return form === "one" ? one : form === "few" ? few : many;
}

function sourceMetrics(source: string) {
  return {
    lineCount: source.length ? source.split("\n").length : 0,
    byteLength: new TextEncoder().encode(source).byteLength,
  };
}
