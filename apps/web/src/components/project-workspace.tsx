"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useState } from "react";
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
  type AnalysisResult,
  type Project,
  updateProjectSource,
} from "@/lib/api";

const JavaEditor = dynamic(
  () => import("@/components/java-editor").then((module) => module.JavaEditor),
  { ssr: false, loading: () => <div className="editor-loading">Открываем редактор…</div> },
);

function projectLoadMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 401) {
    return "Сессия не найдена. Вернитесь на главную и загрузите файл заново.";
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
  const [analysisState, setAnalysisState] = useState<"idle" | "loading" | "complete" | "unavailable" | "error">("idle");
  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null);
  const [analysisError, setAnalysisError] = useState("");
  const [reloading, setReloading] = useState(false);
  const dirty = source !== savedSource;
  const lineCount = source.length ? source.split("\n").length : 0;

  async function saveSource() {
    if (!dirty || saveState === "saving") return;
    setSaveState("saving");
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
      setSaveState(cause instanceof ApiError && cause.status === 409 ? "conflict" : "error");
    }
  }

  async function runAnalysis() {
    if (dirty || analysisState === "loading") return;
    setAnalysisState("loading");
    setAnalysisError("");
    try {
      setAnalysis(await analyzeProject(projectId));
      setAnalysisState("complete");
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === "analyzer_unavailable") {
        setAnalysisState("unavailable");
        setAnalysisError("Java worker пока не подключён. Исходник сохранён; результат анализа появится после настройки parser.");
      } else {
        setAnalysisState("error");
        setAnalysisError(cause instanceof ApiError ? cause.message : "Не удалось связаться с analyzer.");
      }
    }
  }

  async function reloadSource() {
    setReloading(true);
    await reload();
    setReloading(false);
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
          <p>Исходник и состояние проекта. Анализ подключится вместе с Java worker.</p>
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
        <div className="inline-alert" role="alert"><AlertCircle size={17} /><span>Не удалось сохранить. Проверьте соединение с API.</span></div>
      ) : null}

      <div className="workspace-grid">
        <section className="editor-card" aria-label="Редактор Java">
          <div className="editor-toolbar">
            <div className="editor-file"><span className="java-file-icon">J</span><strong>{project.filename}</strong><span className="file-tag">JAVA</span></div>
            <div className="editor-toolbar-meta"><span><Code2 size={14} /> UTF-8</span><span><GitBranch size={14} /> REV {revision}</span></div>
          </div>
          <div className="editor-body"><JavaEditor value={source} onChange={setSource} /></div>
          <footer className="editor-footer"><span><TerminalSquare size={14} /> Исходный код</span><span>{lineCount} строк <i>·</i> {new TextEncoder().encode(source).length.toLocaleString("ru-RU")} байт</span></footer>
        </section>

        <aside className="analysis-panel">
          <div className="panel-heading"><div><span className="panel-kicker">JAVA WORKER</span><h2>Состояние анализа</h2></div><span className={`worker-mark worker-mark--${analysisState}`}><Sparkles size={16} /></span></div>

          {analysisState === "complete" && analysis ? (
            <div className="analysis-result">
              <div className="result-banner"><span className="result-check"><Check size={15} /></span><div><strong>{analysis.status === "completed" ? "Анализ завершён" : analysis.status === "partial" ? "Частичный результат" : "Анализ завершился с ошибкой"}</strong><small>Ревизия {analysis.revision} · протокол {analysis.protocol_version}</small></div></div>
              <div className="result-stats"><div><strong>{analysis.methods.length}</strong><span>методов</span></div><div><strong>{analysis.diagnostics.length}</strong><span>замечаний</span></div></div>
              {analysis.methods.length ? <div className="method-list"><span className="list-label">МЕТОДЫ</span>{analysis.methods.slice(0, 8).map((method) => <div className="method-row" key={method.id}><Code2 size={13} /><span>{method.name}</span><small>стр. {method.source_range.line ?? "—"}</small></div>)}{analysis.methods.length > 8 ? <small className="more-methods">и ещё {analysis.methods.length - 8}</small> : null}</div> : null}
              {analysis.diagnostics.length ? <div className="diagnostic-list">{analysis.diagnostics.map((item, index) => <div className="diagnostic-item" key={`${item.code}-${index}`}><span className={`diagnostic-dot diagnostic-dot--${item.severity}`} /><div><strong>{item.code}</strong><p>{item.message}</p><small>Строка {item.source_range.line ?? "—"}</small></div></div>)}</div> : <p className="empty-diagnostics">Диагностик нет.</p>}
            </div>
          ) : analysisState === "unavailable" ? (
            <div className="worker-state worker-state--unavailable"><span className="state-icon"><Clock3 /></span><strong>Ждём Java worker</strong><p>{analysisError}</p><div className="state-footnote"><span className="status-pip status-pip--amber" /> API доступен · analyzer не настроен</div></div>
          ) : analysisState === "error" ? (
            <div className="worker-state worker-state--error"><span className="state-icon"><AlertCircle /></span><strong>Анализ не выполнен</strong><p>{analysisError}</p><Button variant="outline" onClick={() => void runAnalysis()}><RefreshCw /> Попробовать снова</Button></div>
          ) : (
            <div className="worker-state"><span className="state-icon"><ShieldCheck /></span><strong>{analysisState === "loading" ? "Читаем структуру" : "Готов к подключению"}</strong><p>{analysisState === "loading" ? "Запрос отправлен Java worker. Обычно это занимает несколько секунд." : "Сохраните исходник и запустите проверку. Backend вернёт методы и диагностические сообщения, когда worker будет настроен."}</p><div className="state-footnote"><span className={`status-pip ${analysisState === "loading" ? "status-pip--pulse" : "status-pip--neutral"}`} />{analysisState === "loading" ? "Обрабатываем ревизию" : "Ожидание команды"}</div></div>
          )}

          <div className="panel-divider" />
          <div className="pipeline-note"><span className="pipeline-index">01</span><div><strong>Сохранённый исходник</strong><p>Ревизия {revision} · {lineCount} строк</p></div><Check size={15} /></div>
          <div className="pipeline-note pipeline-note--pending"><span className="pipeline-index">02</span><div><strong>AST и диагностика</strong><p>Появятся после подключения parser</p></div><span className="pending-dash" /></div>
          <div className="panel-tip"><span>i</span><p>Результаты анализа относятся только к той ревизии исходника, которую обработал backend.</p></div>
        </aside>
      </div>

      <footer className="workspace-bottom"><span>JAVA BLUEPRINTS <i>·</i> M1 SOURCE WORKSPACE</span><span><ShieldCheck size={13} /> Код хранится в приватной гостевой сессии</span></footer>
    </main>
  );
}
