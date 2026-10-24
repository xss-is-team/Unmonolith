"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useCallback, useState } from "react";
import useSWR from "swr";
import {
  AlertCircle,
  ArrowLeft,
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
  const [source, setSource] = useState(initialSource);
  const [savedSource, setSavedSource] = useState(initialSource);
  const [revision, setRevision] = useState(initialRevision);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "saved" | "conflict" | "error">("idle");
  const [saveError, setSaveError] = useState("");
  const [analysisState, setAnalysisState] = useState<"idle" | "loading" | "complete" | "unavailable" | "error">("idle");
  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null);
  const [analysisError, setAnalysisError] = useState("");
  const [selectedSourceRange, setSelectedSourceRange] = useState<{ start: number; length: number } | null>(null);
  const [reloading, setReloading] = useState(false);
  const dirty = source !== savedSource;
  const lineCount = source.length ? source.split("\n").length : 0;
  const selectSourceRange = useCallback((range: { start: number; length: number } | null) => {
    setSelectedSourceRange(range);
  }, []);

  async function saveSource() {
    if (!dirty || saveState === "saving") return;
    if (new TextEncoder().encode(source).byteLength > MAX_SOURCE_BYTES) {
      setSaveError("Исходник превышает лимит API — 2 МиБ.");
      setSaveState("error");
      return;
    }
    setSaveState("saving");
    setSaveError("");
    try {
      const updated = await updateProjectSource(projectId, source, revision);
      setProject(updated);
      setRevision(updated.revision);
      setSavedSource(source);
      setAnalysis(null);
      setAnalysisState("idle");
      setSaveState("saved");
      window.setTimeout(() => setSaveState((state) => state === "saved" ? "idle" : state), 2200);
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        setSaveState("conflict");
      } else {
        setSaveState("error");
        setSaveError(saveErrorMessage(cause));
      }
    }
  }

  async function runAnalysis() {
    if (dirty || analysisState === "loading") return;
    setAnalysisState("loading");
    setAnalysisError("");
    setSelectedSourceRange(null);
    try {
      setAnalysis(await analyzeProject(projectId));
      setAnalysisState("complete");
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === "analyzer_unavailable") {
        setAnalysisState("unavailable");
        setAnalysisError("Сервис анализа сейчас недоступен. Исходник сохранён; попробуйте запустить анализ позже.");
      } else {
        setAnalysisState("error");
        setAnalysisError(analysisErrorMessage(cause));
      }
    }
  }

  async function reloadSource() {
    setReloading(true);
    try {
      await reload();
    } finally {
      setReloading(false);
    }
  }

  return (
    <main className="workspace-screen">
      <header className="workspace-top">
        <Brand compact />
        <div className="workspace-breadcrumb"><span>РАБОЧЕЕ ПРОСТРАНСТВО</span><i>/</i><strong>{project.filename}</strong></div>
        <Link className="back-link" href="/"><ArrowLeft size={15} /> Новый файл</Link>
      </header>

      <section className="workspace-heading">
        <div>
          <div className="eyebrow"><span className="eyebrow-dot" /> JAVA SOURCE / REVISION {revision.toString().padStart(2, "0")}</div>
          <h1>{project.filename}</h1>
          <p>Исходник проекта и результаты разбора JDT для выбранной ревизии.</p>
        </div>
        <div className="workspace-actions">
          <span className={`save-indicator save-indicator--${dirty ? "dirty" : saveState}`}>
            {saveState === "saving" ? <LoaderCircle className="spin" size={14} /> : dirty ? <CircleDot size={14} /> : <Check size={14} />}
            {saveState === "saving" ? "Сохраняем" : dirty ? "Есть изменения" : saveState === "saved" ? "Сохранено" : "Все изменения сохранены"}
          </span>
          <Button className="action-button action-button--quiet" onClick={() => void saveSource()} disabled={!dirty || saveState === "saving"}>
            {saveState === "saving" ? <LoaderCircle className="spin" /> : <Save />}{saveState === "saving" ? "Сохранение" : "Сохранить"}
          </Button>
          <Button className="action-button action-button--primary" onClick={() => void runAnalysis()} disabled={dirty || analysisState === "loading"}>
            {analysisState === "loading" ? <LoaderCircle className="spin" /> : <Play />}{analysisState === "loading" ? "Анализируем" : "Запустить анализ"}
          </Button>
        </div>
      </section>

      {saveState === "conflict" ? (
        <div className="inline-alert" role="alert"><AlertCircle size={17} /><span>Исходник уже изменился на сервере. Перезагрузите его, чтобы не затереть чужую ревизию.</span><Button variant="outline" onClick={() => void reloadSource()} disabled={reloading}><RefreshCw className={reloading ? "spin" : ""} /> Перезагрузить</Button></div>
      ) : saveState === "error" ? (
        <div className="inline-alert" role="alert"><AlertCircle size={17} /><span>{saveError || "Не удалось сохранить. Проверьте соединение с API."}</span></div>
      ) : null}

      <div className="workspace-grid">
        <section className="editor-card" aria-label="Редактор Java">
          <div className="editor-toolbar">
            <div className="editor-file"><span className="java-file-icon">J</span><strong>{project.filename}</strong><span className="file-tag">JAVA</span></div>
            <div className="editor-toolbar-meta"><span><Code2 size={14} /> UTF-8</span><span><GitBranch size={14} /> REV {revision}</span></div>
          </div>
          <div className="editor-body"><JavaEditor value={source} focusRange={selectedSourceRange} onChange={(next) => { setSource(next); setSelectedSourceRange(null); setSaveError(""); if (saveState === "error") setSaveState("idle"); }} /></div>
          <footer className="editor-footer"><span><TerminalSquare size={14} /> Исходный код</span><span>{lineCount} {russianNoun(lineCount, "строка", "строки", "строк")} <i>·</i> {new TextEncoder().encode(source).length.toLocaleString("ru-RU")} байт</span></footer>
        </section>

        <aside className="analysis-panel">
          <div className="panel-heading"><div><span className="panel-kicker">JAVA ANALYZER</span><h2>Состояние анализа</h2></div><span className={`worker-mark worker-mark--${analysisState}`}><Sparkles size={16} /></span></div>

          {analysisState === "complete" && analysis ? (
            <div className="analysis-result">
              <div className="result-banner"><span className="result-check"><Check size={15} /></span><div><strong>{analysis.status === "completed" ? "Анализ завершён" : "Частичный результат"}</strong><small>Ревизия {analysis.revision} · протокол {analysis.protocol_version}</small></div></div>
              <div className="result-stats"><div><strong>{analysis.methods.length}</strong><span>{russianNoun(analysis.methods.length, "метод", "метода", "методов")}</span></div><div><strong>{analysis.diagnostics.length}</strong><span>{russianNoun(analysis.diagnostics.length, "замечание", "замечания", "замечаний")}</span></div></div>
              {analysis.methods.length ? <div className="method-list"><span className="list-label">МЕТОДЫ</span>{analysis.methods.slice(0, 8).map((method) => <div className="method-row" key={method.id}><Code2 size={13} /><span>{method.name}</span><small>стр. {method.source_range.line ?? "—"}</small></div>)}{analysis.methods.length > 8 ? <small className="more-methods">и ещё {analysis.methods.length - 8}</small> : null}</div> : null}
              {analysis.diagnostics.length ? <div className="diagnostic-list">{analysis.diagnostics.map((item, index) => <div className="diagnostic-item" key={`${item.code}-${index}`}><span className={`diagnostic-dot diagnostic-dot--${item.severity}`} /><div><strong>{item.code}</strong><p>{item.message}</p><small>Строка {item.source_range.line ?? "—"}</small></div></div>)}</div> : <p className="empty-diagnostics">Диагностик нет.</p>}
            </div>
          ) : analysisState === "unavailable" ? (
            <div className="worker-state worker-state--unavailable"><span className="state-icon"><Clock3 /></span><strong>Анализатор недоступен</strong><p>{analysisError}</p><Button variant="outline" onClick={() => void runAnalysis()}><RefreshCw /> Повторить анализ</Button><div className="state-footnote"><span className="status-pip status-pip--amber" /> API доступен · анализатор не ответил</div></div>
          ) : analysisState === "error" ? (
            <div className="worker-state worker-state--error"><span className="state-icon"><AlertCircle /></span><strong>Анализ не выполнен</strong><p>{analysisError}</p><Button variant="outline" onClick={() => void runAnalysis()}><RefreshCw /> Попробовать снова</Button></div>
          ) : (
            <div className="worker-state"><span className="state-icon"><ShieldCheck /></span><strong>{analysisState === "loading" ? "JDT разбирает исходник" : "Готов к анализу"}</strong><p>{analysisState === "loading" ? "API обрабатывает сохранённую ревизию и собирает список методов и диагностики." : "Запустите анализ сохранённой ревизии. Результаты CFG выбранного метода отображаются ниже."}</p><div className="state-footnote"><span className={`status-pip ${analysisState === "loading" ? "status-pip--pulse" : "status-pip--neutral"}`} />{analysisState === "loading" ? "Обрабатываем ревизию" : "Ожидание команды"}</div></div>
          )}

          <div className="panel-divider" />
          <div className="pipeline-note"><span className="pipeline-index">01</span><div><strong>Сохранённый исходник</strong><p>Ревизия {revision} · {lineCount} {russianNoun(lineCount, "строка", "строки", "строк")}</p></div><Check size={15} /></div>
          <div className={`pipeline-note${analysisState === "complete" ? "" : " pipeline-note--pending"}`}><span className="pipeline-index">02</span><div><strong>Методы и диагностики</strong><p>{analysis ? `Ревизия ${analysis.revision} · ${analysis.methods.length} ${russianNoun(analysis.methods.length, "метод", "метода", "методов")} · ${analysis.diagnostics.length} ${russianNoun(analysis.diagnostics.length, "замечание", "замечания", "замечаний")}` : analysisState === "loading" ? "JDT обрабатывает исходник" : analysisState === "unavailable" || analysisState === "error" ? "Последний запуск не завершён" : "Запустите анализ сохранённой ревизии"}</p></div>{analysisState === "complete" ? <Check size={15} /> : <span className="pending-dash" />}</div>
          <div className="panel-tip"><span>i</span><p>Результаты анализа относятся только к той ревизии исходника, которую обработал backend.</p></div>
        </aside>
      </div>

      <CfgViewer
        projectId={projectId}
        revision={revision}
        analysis={analysisState === "complete" && !dirty ? analysis : null}
        onSourceRangeSelect={selectSourceRange}
      />

      <footer className="workspace-bottom"><span>JAVA BLUEPRINTS <i>·</i> M1 SOURCE WORKSPACE</span><span><ShieldCheck size={13} /> Код хранится в приватной гостевой сессии</span></footer>
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
