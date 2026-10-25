"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertCircle, ArrowLeft, ChevronRight, Code2, Download, History, FileCode2, FileText, FolderOpen, Gauge, GitCompareArrows, LayoutGrid,
  LoaderCircle, Network, Play, Save, Scissors, ShieldCheck, Sparkles, Upload, Waypoints,
} from "lucide-react";
import { AnalysisPane } from "@/components/analysis-pane";
import { BlockPalette, type BlockTemplate } from "@/components/block-palette";
import { ConsolePane, TestsPane, logTime, type LogLine } from "@/components/bottom-panes";
import { Button } from "@/components/ui/button";
import { DecompositionPanel } from "@/components/decomposition-panel";
import { DiffView } from "@/components/diff-view";
import { HistoryPanel } from "@/components/history-panel";
import { PreviewDialog } from "@/components/preview-dialog";
import { loadScenarios, saveScenarios, suggestScenarios } from "@/lib/scenarios";
import { Splitter, clamp } from "@/components/splitter";
import { ruAssumption, ruComplexityNote, ruNotCovered, ruPrecondition, ruProposalSummary, ruRuleTitle, ruText, ruVerdict, ruWarning } from "@/lib/ru";
import { StepBar, type Step } from "@/components/step-bar";
import {
  analyzeProjectStream, ApiError, applyOptimization, compareRevisions, exportProject,
  getOptimizations, getProject, getProjectComplexity, getProjectFile,
  getProjectFiles, getProjectFindings, getProjectMethods, getProjectReport, getProjectRevisions,
  MAX_SOURCE_BYTES, previewOptimization, runBenchmark, updateProjectFile, verifyRevisions,
  type AnalysisResult, type BenchmarkReport, type CfgMethod, type ComplexityReport,
  type FileDiff, type FindingsReport, type OptimizationList, type OptimizationPreview,
  type Project, type ProjectFile, type SourceRange,
  type VerificationReport, type VerifyTestCase,
} from "@/lib/api";

const JavaEditor = dynamic(() => import("@/components/java-editor").then((m) => m.JavaEditor), { ssr: false });
const CfgViewer = dynamic(() => import("@/components/cfg-viewer").then((m) => m.CfgViewer), { ssr: false });
const ProjectGraphViewer = dynamic(() => import("@/components/project-graph-viewer").then((m) => m.ProjectGraphViewer), { ssr: false });

type ArchiveWorkspaceProps = {
  project: Project;
  initialRevision: number;
  initialSource: string;
  initialFilePath: string;
  initialFiles: ProjectFile[];
};

export function ArchiveWorkspace(props: ArchiveWorkspaceProps) {
  return <ArchiveWorkspaceEditor key={`${props.project.id}:${props.initialRevision}`} {...props} />;
}

function ArchiveWorkspaceEditor({ project: initialProject, initialRevision, initialSource, initialFilePath, initialFiles }: ArchiveWorkspaceProps) {
  const editorRef = useRef<import("@/components/java-editor").JavaEditorHandle>(null);
  const [project, setProject] = useState(initialProject);
  const [revision, setRevision] = useState(initialRevision);
  const revisionRef = useRef(initialRevision);
  const [files, setFiles] = useState(initialFiles);
  const [activeFile, setActiveFile] = useState(initialFilePath);
  const [source, setSource] = useState(initialSource);
  const [savedSource, setSavedSource] = useState(initialSource);
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [view, setView] = useState<"overview" | "cfg">("overview");
  const [selector, setSelector] = useState("");
  const [range, setRange] = useState<SourceRange | null>(null);
  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null);
  const [methods, setMethods] = useState<CfgMethod[]>([]);
  const [findings, setFindings] = useState<FindingsReport | null>(null);
  const [complexity, setComplexity] = useState<ComplexityReport | null>(null);
  const [optimizations, setOptimizations] = useState<OptimizationList | null>(null);
  const [selectedProposal, setSelectedProposal] = useState("");
  const [preview, setPreview] = useState<OptimizationPreview | null>(null);
  const [acknowledged, setAcknowledged] = useState<string[]>([]);
  const [optimizationError, setOptimizationError] = useState("");
  const [loading, setLoading] = useState(true);
  const [workbench, setWorkbench] = useState<"code" | "run" | "verify" | "benchmark" | "diff" | "decompose" | "history">("code");
  const [repeats, setRepeats] = useState(2);
  const [analysisTab, setAnalysisTab] = useState<"problems" | "recommendations" | "metrics" | "edit">("problems");
  const [editPortal, setEditPortal] = useState<HTMLElement | null>(null);
  const [blockTemplate, setBlockTemplate] = useState<BlockTemplate | null>(null);
  const [openTabs, setOpenTabs] = useState<string[]>([initialFilePath]);
  const [logLines, setLogLines] = useState<LogLine[]>([]);
  const [panes, setPanes] = useState({ code: false, console: false, tests: false });
  const [codeTall, setCodeTall] = useState(false);
  const [sizes, setSizes] = useState({ left: 224, right: 380, bottom: 260 });
  const sizesLoaded = useRef(false);
  useEffect(() => {
    void Promise.resolve().then(() => {
      try {
        const stored = JSON.parse(window.localStorage.getItem("blueprints.pane-sizes") ?? "null") as Partial<typeof sizes> | null;
        const roomy = clamp(Math.round(window.innerHeight * 0.26), 150, 360);
        if (stored) setSizes((current) => ({ left: clamp(Number(stored.left) || current.left, 160, 520), right: clamp(Number(stored.right) || current.right, 280, 900), bottom: clamp(Number(stored.bottom) || roomy, 120, 800) }));
        else setSizes((current) => ({ ...current, bottom: roomy }));
      } catch { /* a stored layout is a convenience only */ }
      sizesLoaded.current = true;
    });
  }, []);
  useEffect(() => {
    if (!sizesLoaded.current) return;
    try { window.localStorage.setItem("blueprints.pane-sizes", JSON.stringify(sizes)); } catch { /* ignore */ }
  }, [sizes]);
  const [revisions, setRevisions] = useState<{ revision: number; has_analysis: boolean }[]>([]);
  const [baselineRevision, setBaselineRevision] = useState(1);
  const [candidateRevision, setCandidateRevision] = useState(initialRevision);
  const [cases, setCases] = useState<VerifyTestCase[]>([{ name: "Сценарий", stdin: "" }]);
  const [verifyReport, setVerifyReport] = useState<VerificationReport | null>(null);
  /** The automatic quality gate: after every new revision the saved scenarios compare REV 1 with it. */
  const [gate, setGate] = useState<{ revision: number; state: "running" | "passed" | "failed" | "unstable" | "error" | "none"; passed?: number; total?: number; text?: string } | null>(null);
  const scenariosLoaded = useRef(false);
  useEffect(() => {
    void (async () => {
      const stored = loadScenarios(initialProject.id);
      if (stored) {
        setCases(stored);
      } else {
        const sources = [initialSource];
        for (const file of initialFiles.filter((item) => item.kind === "java" && item.path !== initialFilePath).slice(0, 30)) {
          try { sources.push((await getProjectFile(initialProject.id, file.path, initialRevision)).source); } catch { /* a missing file only weakens the suggestion */ }
        }
        const suggested = suggestScenarios(sources);
        if (suggested.some((item) => item.stdin.trim())) {
          setCases(suggested);
          setLogLines((lines) => [...lines, { time: logTime(), kind: "info", text: `Сценарии проверки предложены по коду: ${suggested.length}. Они запускаются автоматически после каждой правки (вкладка «Проверка»).` }]);
        }
      }
      scenariosLoaded.current = true;
    })();
  }, [initialProject.id, initialSource, initialFiles, initialFilePath, initialRevision]);
  useEffect(() => {
    if (scenariosLoaded.current) saveScenarios(project.id, cases);
  }, [project.id, cases]);
  const [verifyError, setVerifyError] = useState("");
  const [runStdin, setRunStdin] = useState<string | null>(null);
  const [runResult, setRunResult] = useState<{ revision: number; report: VerificationReport } | null>(null);
  const [runBusy, setRunBusy] = useState(false);
  const [runError, setRunError] = useState("");
  const [verifyBusy, setVerifyBusy] = useState(false);
  const [benchmarkReport, setBenchmarkReport] = useState<BenchmarkReport | null>(null);
  const [benchmarkError, setBenchmarkError] = useState("");
  const [benchmarkBusy, setBenchmarkBusy] = useState(false);
  const [mainClass, setMainClass] = useState("");
  const [sourceLevel, setSourceLevel] = useState("21");
  const [workload, setWorkload] = useState({ name: "n=1200", size: "1200", stdin: "1200\n800\n997\n" });
  const [measuredIterations, setMeasuredIterations] = useState(10);
  const [revisionDiff, setRevisionDiff] = useState<FileDiff[] | null>(null);
  const [diffFrom, setDiffFrom] = useState(1);
  const [diffTo, setDiffTo] = useState(initialRevision);

  const javaFiles = useMemo(() => files.filter((file) => file.kind === "java"), [files]);
  const selectedMethod = methods.find((method) => method.selector === selector) ?? null;
  const selectedProposalData = optimizations?.proposals.find((proposal) => proposal.id === selectedProposal) ?? null;

  const analyzeRevision = useCallback(async (expectedRevision: number) => {
    const result = await analyzeProjectStream(project.id, () => {});
    if (result.revision !== expectedRevision) throw new ApiError("Анализ завершился для другой ревизии.", 409, "revision_conflict");
    const methodResult = await getProjectMethods(project.id);
    if (methodResult.revision !== expectedRevision) throw new ApiError("Список методов относится к другой ревизии.", 409, "revision_conflict");
    const [findingResult, complexityResult, optimizationResult] = await Promise.all([
      getProjectFindings(project.id), getProjectComplexity(project.id), getOptimizations(project.id, sourceLevel),
    ]);
    if (findingResult.revision !== expectedRevision || complexityResult.revision !== expectedRevision || optimizationResult.revision !== expectedRevision) {
      throw new ApiError("Результаты анализа относятся к другой ревизии.", 409, "revision_conflict");
    }
    setAnalysis(result);
    setLogLines((lines) => [...lines, { time: logTime(), kind: "ok", text: `Проанализирован REV ${expectedRevision}: методов ${methodResult.methods.length}, проблем ${findingResult.findings.length}` }]);
    setMethods(methodResult.methods);
    setFindings(findingResult);
    setComplexity(complexityResult);
    setOptimizations(optimizationResult);
    setSelector((current) => methodResult.methods.some((method) => method.selector === current) ? current : methodResult.methods[0]?.selector ?? "");
    setSelectedProposal((current) => optimizationResult.proposals.some((proposal) => proposal.id === current) ? current : "");
  }, [project.id, sourceLevel, setSelector, setSelectedProposal, setLogLines]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      setLoading(true);
      try {
        await analyzeRevision(revisionRef.current);
      } catch (cause) {
        if (!cancelled) setError(errorMessage(cause));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [analyzeRevision]);

  async function openFile(path: string, nextRange: SourceRange | null = null) {
    setPanes((current) => (current.code ? current : { ...current, code: true }));
    if (path === activeFile) {
      setRange(nextRange);
      setWorkbench("code");
      return;
    }
    if (dirtyRef.current) {
      await saveFile();
      if (dirtyRef.current) return;
    }
    setBusy(true);
    setError("");
    try {
      const opened = await getProjectFile(project.id, path, revisionRef.current);
      if (opened.revision !== revisionRef.current) throw new ApiError("Файл относится к другой ревизии.", 409, "revision_conflict");
      setActiveFile(path);
      setOpenTabs((tabs) => (tabs.includes(path) ? tabs : [...tabs, path]));
      setSource(opened.source);
      setSavedSource(opened.source);
      setRange(nextRange);
      setWorkbench("code");
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  async function openRange(sourceRange: SourceRange | null) {
    setPanes((current) => (current.code ? current : { ...current, code: true }));
    if (!sourceRange) {
      setRange(null);
      setWorkbench("code");
      return;
    }
    const filePath = sourceRange.file ?? activeFile;
    await openFile(filePath, sourceRange);
  }

  async function saveFile() {
    if (!dirtyRef.current || busy) return;
    const value = editorRef.current?.getValue() ?? source;
    if (new TextEncoder().encode(value).byteLength > MAX_SOURCE_BYTES) {
      setError("Файл превышает лимит API — 2 МиБ.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const updated = await updateProjectFile(project.id, activeFile, value, revisionRef.current);
      revisionRef.current = updated.revision;
      setRevision(updated.revision);
      setProject(updated);
      setCandidateRevision(updated.revision);
      setDiffTo(updated.revision);
      setSavedSource(value);
      setSource(value);
      editorRef.current?.markSaved(value);
      dirtyRef.current = false;
      setDirty(false);
      setPreview(null);
      setVerifyReport(null);
      setBenchmarkReport(null);
      const manifest = await getProjectFiles(project.id, updated.revision);
      setFiles(manifest.files);
      await analyzeRevision(updated.revision);
      void runQualityGate(updated.revision);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  async function reloadCurrentRevision() {
    setBusy(true);
    setError("");
    try {
      const updated = await getProject(project.id);
      const manifest = await getProjectFiles(project.id, updated.revision);
      const path = manifest.files.some((file) => file.path === activeFile && file.kind === "java")
        ? activeFile : manifest.files.find((file) => file.kind === "java")?.path;
      if (!path) throw new ApiError("В ревизии нет Java-файлов.", 422, "no_java_sources");
      const opened = await getProjectFile(project.id, path, updated.revision);
      setProject(updated);
      revisionRef.current = updated.revision;
      setRevision(updated.revision);
      setCandidateRevision(updated.revision);
      setDiffTo(updated.revision);
      setFiles(manifest.files);
      setActiveFile(path);
      setOpenTabs((tabs) => (tabs.includes(path) ? tabs : [...tabs, path]));
      setSource(opened.source);
      setSavedSource(opened.source);
      dirtyRef.current = false;
      setDirty(false);
      setRange(null);
      setPreview(null);
      setVerifyReport(null);
      setBenchmarkReport(null);
      setRevisions(await getProjectRevisions(project.id));
      await analyzeRevision(updated.revision);
      void runQualityGate(updated.revision);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  async function previewSelectedOptimization() {
    if (!selectedProposalData || selectedProposalData.applicability !== "applicable" || dirty) return;
    setOptimizationError("");
    setPreview(null);
    try {
      const result = await previewOptimization(project.id, selectedProposalData.id, revision, sourceLevel);
      if (result.base_revision !== revision) throw new ApiError("Предпросмотр относится к другой ревизии.", 409, "revision_conflict");
      setPreview(result);
      setAcknowledged([]);
    } catch (cause) {
      setOptimizationError(errorMessage(cause));
    }
  }

  async function applySelectedOptimization() {
    if (!selectedProposalData || !preview || dirty) return;
    setBusy(true);
    setOptimizationError("");
    try {
      await applyOptimization(project.id, selectedProposalData.id, revision, preview.preview_sha256, acknowledged, sourceLevel);
      setLogLines((lines) => [...lines, { time: logTime(), kind: "ok", text: `Оптимизация применена: ${ruRuleTitle(selectedProposalData.rule, selectedProposalData.title)} → REV ${revision + 1}` }]);
      await reloadCurrentRevision();
      setPreview(null);
    } catch (cause) {
      setOptimizationError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  async function runFunctionalVerification() {
    setVerifyBusy(true);
    setVerifyError("");
    setVerifyReport(null);
    try {
      setLogLines((lines) => [...lines, { time: logTime(), kind: "info", text: `Проверка: REV ${baselineRevision} → REV ${candidateRevision}, сценариев ${cases.length}, запусков ${repeats}…` }]);
      const report = await verifyRevisions(project.id, baselineRevision, candidateRevision, cases, { main_class: mainClass || null, source_level: sourceLevel, repeats });
      setVerifyReport(report);
      setPanes((current) => ({ ...current, console: true, tests: true }));
      const passedCount = report.test_cases.filter((item) => item.passed).length;
      setLogLines((lines) => [...lines, { time: logTime(), kind: report.baseline_compilation.status === "completed" && report.candidate_compilation.status === "completed" ? "ok" : "error", text: `Сборка REV ${report.baseline_revision}: ${report.baseline_compilation.status}; REV ${report.candidate_revision}: ${report.candidate_compilation.status}` }]);
      setLogLines((lines) => [...lines, report.status === "compilation_failed"
        ? { time: logTime(), kind: "error", text: "Компиляция не прошла" }
        : { time: logTime(), kind: report.behaviorally_verified ? "ok" : "error", text: report.behaviorally_verified ? `Поведение совпадает (${passedCount} из ${report.test_cases.length})` : report.status === "unstable" ? "Результат неопределён: вывод нестабилен" : `Есть различия (${passedCount} из ${report.test_cases.length} совпало)` }]);
    } catch (cause) {
      setVerifyError(errorMessage(cause));
    } finally {
      setVerifyBusy(false);
    }
  }

  /**
   * Runs a verify request; when the project has several main classes and none is chosen, picks the one called Main
   * (otherwise the first the API lists), remembers it and retries once.
   */
  async function verifyWithMain(baseline: number, candidate: number, scenarios: VerifyTestCase[], runs: number) {
    try {
      return await verifyRevisions(project.id, baseline, candidate, scenarios, { main_class: mainClass || null, source_level: sourceLevel, repeats: runs });
    } catch (cause) {
      if (!(cause instanceof ApiError) || cause.code !== "main_class_required" || mainClass) throw cause;
      const listed = (cause.message.split(":").at(-1) ?? "").split(",").map((item) => item.trim()).filter((item) => /^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*$/.test(item));
      const pick = listed.find((item) => item === "Main" || item.endsWith(".Main")) ?? listed[0];
      if (!pick) throw cause;
      setMainClass(pick);
      setLogLines((lines) => [...lines, { time: logTime(), kind: "info", text: `В проекте несколько классов с main (${listed.join(", ")}); выбран ${pick}. Другой можно указать в «Проверке».` }]);
      return await verifyRevisions(project.id, baseline, candidate, scenarios, { main_class: pick, source_level: sourceLevel, repeats: runs });
    }
  }

  /** «Запуск» of the mockup: the current revision runs once in the Docker runner on the given stdin (the verify endpoint, same revision on both sides). */
  async function runProgram(stdin: string) {
    setRunBusy(true);
    setRunError("");
    setRunResult(null);
    const target = revision;
    try {
      setLogLines((lines) => [...lines, { time: logTime(), kind: "info", text: `Запуск REV ${target} в Docker…` }]);
      const report = await verifyWithMain(target, target, [{ name: "Запуск", stdin }], 1);
      setRunResult({ revision: target, report });
      const run = report.test_cases[0]?.baseline;
      const output = (run?.stdout.utf8 ?? "").split("\n").filter((line, index, all) => index < all.length - 1 || line).slice(0, 200);
      setLogLines((lines) => [...lines,
        ...output.map((text) => ({ time: logTime(), kind: "info" as const, text: `> ${text}` })),
        report.status === "compilation_failed"
          ? { time: logTime(), kind: "error" as const, text: `REV ${target} не компилируется` }
          : { time: logTime(), kind: run?.exit_code === 0 ? "ok" as const : "error" as const, text: `Программа завершилась: код ${run?.exit_code ?? "—"}${run?.timed_out ? ", превышен лимит времени" : ""}` }]);
      setPanes((current) => ({ ...current, console: true }));
    } catch (cause) {
      setRunError(errorMessage(cause));
    } finally {
      setRunBusy(false);
    }
  }

  /** Quality gate of the case: every new revision is compared with REV 1 on the saved scenarios, without a click. */
  async function runQualityGate(target: number) {
    if (target <= 1) return;
    const usable = cases.filter((item) => item.name.trim());
    if (!usable.some((item) => item.stdin.trim())) {
      setGate({ revision: target, state: "none" });
      return;
    }
    setGate({ revision: target, state: "running" });
    setLogLines((lines) => [...lines, { time: logTime(), kind: "info", text: `Автопроверка: REV 1 → REV ${target}, сценариев ${usable.length}…` }]);
    try {
      const report = await verifyWithMain(1, target, usable, 1);
      const passedCount = report.test_cases.filter((item) => item.passed).length;
      const state = report.behaviorally_verified ? "passed" : report.status === "unstable" ? "unstable" : "failed";
      setGate({ revision: target, state, passed: passedCount, total: report.test_cases.length });
      setBaselineRevision(1);
      setCandidateRevision(target);
      setVerifyReport(report);
      setPanes((current) => ({ ...current, tests: true }));
      setLogLines((lines) => [...lines, { time: logTime(), kind: state === "passed" ? "ok" : "error", text: state === "passed" ? `Автопроверка пройдена: поведение REV ${target} совпадает с REV 1 (${passedCount} из ${report.test_cases.length})` : report.status === "compilation_failed" ? `Автопроверка: REV ${target} не компилируется` : `Автопроверка: различия (${passedCount} из ${report.test_cases.length} совпало)` }]);
    } catch (cause) {
      setGate({ revision: target, state: "error", text: errorMessage(cause) });
      setLogLines((lines) => [...lines, { time: logTime(), kind: "error", text: `Автопроверка не запущена: ${errorMessage(cause)}` }]);
    }
  }

  async function runRuntimeBenchmark() {
    setBenchmarkBusy(true);
    setBenchmarkError("");
    setBenchmarkReport(null);
    try {
      setBenchmarkReport(await runBenchmark(project.id, {
        baseline_revision: baselineRevision,
        candidate_revision: candidateRevision,
        workloads: [{ name: workload.name.trim(), size: workload.size ? Number(workload.size) : undefined, stdin: workload.stdin }],
        warmup_iterations: 2,
        measured_iterations: measuredIterations,
        class_loading: "fresh",
        main_class: mainClass || null,
        source_level: sourceLevel,
      }));
    } catch (cause) {
      setBenchmarkError(errorMessage(cause));
    } finally {
      setBenchmarkBusy(false);
    }
  }

  async function loadDiff() {
    setRevisionDiff(null);
    try {
      const result = await compareRevisions(project.id, diffFrom, diffTo);
      setRevisionDiff(result.files);
    } catch (cause) {
      setError(errorMessage(cause));
    }
  }

  async function downloadReport() {
    try {
      const text = await getProjectReport(project.id, Math.min(baselineRevision, revision), revision);
      const url = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=utf-8" }));
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `report-${project.filename.replace(/\.[^.]+$/, "")}-rev${revision}.md`;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (cause) {
      setError(errorMessage(cause));
    }
  }

  async function downloadProject() {
    try {
      const result = await exportProject(project.id);
      const url = URL.createObjectURL(result.blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = result.filename;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (cause) {
      setError(errorMessage(cause));
    }
  }

  const isDirty = dirty;
  const activeDiagnostics = analysis?.diagnostics.filter((diagnostic) => !diagnostic.source_range.file || diagnostic.source_range.file === activeFile) ?? [];

  const speedup = (() => {
    const items = benchmarkReport?.runtime.workloads.filter((item) => item.verdict === "faster" && item.speedup !== null) ?? [];
    if (!items.length) return null;
    const best = items.reduce((a, b) => ((b.speedup ?? 0) > (a.speedup ?? 0) ? b : a));
    return { value: best.speedup ?? 0, note: `Измерено на нагрузке «${best.name}» (REV ${benchmarkReport?.baseline_revision} → REV ${benchmarkReport?.candidate_revision}); не прогноз.` };
  })();
  const firstApplicable = optimizations?.proposals.find((proposal) => proposal.applicability === "applicable") ?? null;
  const overlayTitle = workbench === "run" ? `Запуск программы · REV ${revision}` : workbench === "verify" ? "Проверка поведения" : workbench === "benchmark" ? "Бенчмарк" : workbench === "diff" ? "Diff ревизий" : workbench === "decompose" ? "Разбор монолита" : workbench === "history" ? "История ревизий" : "";
  const methodGroups = (() => {
    const groups = new Map<string, CfgMethod[]>();
    for (const method of methods) {
      const head = method.id.split("(")[0];
      const group = head.includes(".") ? head.slice(0, head.lastIndexOf(".")) : "(без класса)";
      groups.set(group, [...(groups.get(group) ?? []), method]);
    }
    return [...groups.entries()];
  })();
  const openVerify = () => { setWorkbench("verify"); void getProjectRevisions(project.id).then(setRevisions); };
  const openBenchmark = () => { setWorkbench("benchmark"); void getProjectRevisions(project.id).then(setRevisions); };
  const applicableCount = optimizations?.proposals.filter((proposal) => proposal.applicability === "applicable").length ?? 0;
  const steps: Step[] = [
    { id: "analysis", label: loading ? "1 Анализ…" : "Анализ", hint: "Граф проекта, CFG методов и Big O", state: loading ? "next" : "done", onClick: () => { setWorkbench("code"); setView("overview"); setAnalysisTab("problems"); } },
    { id: "optimize", label: `Оптимизация${applicableCount ? ` · ${applicableCount}` : ""}`, hint: "Предложения по ускорению с предпросмотром diff", state: revision > 1 ? "done" : applicableCount ? "next" : "todo", onClick: () => { setWorkbench("code"); setAnalysisTab("recommendations"); if (firstApplicable) { setSelectedProposal(firstApplicable.id); setPreview(null); setAcknowledged([]); } } },
    { id: "verify", label: "Проверка поведения", hint: "Запуск в Docker: поведение до и после совпадает?", state: verifyReport ? (verifyReport.behaviorally_verified ? "done" : "warn") : revision > 1 ? "next" : "todo", onClick: openVerify },
    { id: "benchmark", label: "Замер ускорения", hint: "Измерение времени выполнения до и после", state: benchmarkReport ? "done" : verifyReport?.behaviorally_verified ? "next" : "todo", onClick: openBenchmark },
    { id: "decompose", label: "Разбор монолита", hint: "Разделить на файлы и классы по ответственности", state: files.length > 1 ? "done" : "todo", onClick: () => setWorkbench("decompose") },
    { id: "report", label: "Отчёт", hint: "Скачать итоговый отчёт (Markdown)", state: "todo", onClick: () => void downloadReport() },
  ];

  return (
    <main className="grid h-svh min-w-[1100px] grid-rows-[44px_34px_minmax(0,1fr)_auto] overflow-hidden bg-[#070b14]">
      <header className="flex min-w-0 items-center gap-2 border-b border-slate-800 bg-[#0b1220] px-3">
        <Link href="/" className="flex shrink-0 items-center gap-2 rounded px-1 hover:bg-slate-800/60" title="На главную: загрузить другой проект"><LayoutGrid size={20} className="shrink-0 text-cyan-400" /><strong className="whitespace-nowrap text-[15px] text-slate-100">Визуальная среда программирования</strong></Link>
        
        <span className="whitespace-nowrap rounded bg-slate-800 px-2 py-1 font-mono text-[11px] text-slate-400" data-testid="revision">REV {revision}</span>
        {gate && gate.revision === revision ? (
          <button type="button" data-testid="quality-gate" title={gate.text ?? "Автоматическая проверка поведения: REV 1 против текущей ревизии на сохранённых сценариях"} onClick={() => { setWorkbench("verify"); void getProjectRevisions(project.id).then(setRevisions); }}
            className={`inline-flex h-7 items-center gap-1.5 whitespace-nowrap rounded border px-2 text-[12px] ${gate.state === "passed" ? "border-emerald-700 bg-emerald-500/15 text-emerald-200" : gate.state === "running" ? "border-slate-700 text-slate-300" : gate.state === "none" ? "border-amber-800 text-amber-200" : "border-rose-700 bg-rose-500/15 text-rose-200"}`}>
            {gate.state === "running" ? <LoaderCircle className="spin" size={13} /> : <ShieldCheck size={13} />}
            {gate.state === "passed" ? `Поведение сохранено · ${gate.passed}/${gate.total}` : gate.state === "running" ? "Автопроверка…" : gate.state === "none" ? "Нет сценариев проверки" : gate.state === "unstable" ? "Вывод нестабилен" : gate.state === "error" ? "Автопроверка не запущена" : `Поведение изменилось · ${gate.passed}/${gate.total}`}
          </button>
        ) : null}
        <span className={`text-[12px] ${isDirty ? "text-amber-300" : "text-emerald-300"}`} role="status">{isDirty ? "Не сохранено" : "Сохранено"}</span>
        <Button variant="outline" size="sm" onClick={() => void saveFile()} disabled={!isDirty || busy}>{busy ? <LoaderCircle className="spin" /> : <Save />} Сохранить</Button>
        <div className="ml-auto flex items-center gap-1.5">
          <Link title="Импорт Java: загрузить другой проект" aria-label="Импорт Java" className="inline-flex h-8 items-center gap-1.5 whitespace-nowrap rounded border border-slate-700 px-2.5 text-[13px] text-slate-200 hover:border-cyan-700" href="/"><Upload size={15} /><span className="hidden min-[1700px]:inline">Импорт Java</span></Link>
          <Button variant="outline" size="sm" aria-label="Экспортировать проект" title="Экспорт Java: скачать проект" onClick={() => void downloadProject()}><Download /><span className="hidden min-[1700px]:inline"> Экспорт Java</span></Button>
          <Button size="sm" className="bg-cyan-600 text-white hover:bg-cyan-500" disabled={loading || !firstApplicable} title={firstApplicable ? "Открыть применимую оптимизацию" : "Применимых оптимизаций нет"} onClick={() => { setAnalysisTab("recommendations"); if (firstApplicable) { setSelectedProposal(firstApplicable.id); setPreview(null); setAcknowledged([]); } }}><Sparkles /> Оптимизировать</Button>
          <Button size="sm" className="bg-emerald-600 text-white hover:bg-emerald-500" title="Запустить текущую ревизию в Docker и показать вывод" onClick={() => { setWorkbench("run"); if (runStdin === null) setRunStdin(cases.find((item) => item.stdin.trim())?.stdin ?? ""); }}><Play /> Запуск</Button>
        </div>
        <div className="ml-2 flex items-center gap-1 border-l border-slate-800 pl-2" aria-label="Инструменты">
          <Button variant="ghost" size="sm" aria-label="Проверка" title="Проверка поведения" onClick={() => { setWorkbench("verify"); void getProjectRevisions(project.id).then(setRevisions); }}><ShieldCheck /></Button>
          <Button variant="ghost" size="sm" aria-label="Бенчмарк" title="Измерение времени выполнения" onClick={() => { setWorkbench("benchmark"); void getProjectRevisions(project.id).then(setRevisions); }}><Gauge /></Button>
          <Button variant="ghost" size="sm" aria-label="Разбор монолита" title="Разбор монолита на файлы и классы" onClick={() => setWorkbench("decompose")}><Scissors /><span className="hidden min-[1700px]:inline"> Разбор монолита</span></Button>
          <Button variant="ghost" size="sm" aria-label="История" title="История ревизий и откат" onClick={() => setWorkbench("history")}><History /></Button>
          <Button variant="ghost" size="sm" aria-label="Diff ревизий" title="Сравнение ревизий" onClick={() => { setWorkbench("diff"); void getProjectRevisions(project.id).then(setRevisions); }}><GitCompareArrows /></Button>
          <Button variant="ghost" size="sm" aria-label="Отчёт" title="Скачать отчёт (Markdown)" onClick={() => void downloadReport()} disabled={loading}><FileText /><span className="hidden min-[1700px]:inline"> Отчёт</span></Button>
        </div>
      </header>

      <StepBar steps={steps} />

      {error ? <div className="absolute top-14 left-1/2 z-50 flex w-[min(720px,95vw)] -translate-x-1/2 items-center gap-2 rounded border border-rose-900 bg-rose-950 px-3 py-2 text-[13px] text-rose-200 shadow-xl" role="alert"><AlertCircle size={16} />{error}<button className="ml-auto bg-transparent text-slate-400" type="button" aria-label="Закрыть сообщение" onClick={() => setError("")}>×</button></div> : null}

      <div className="grid min-h-0 min-w-0" style={{ gridTemplateColumns: `${sizes.left}px 5px minmax(240px,1fr) 5px ${sizes.right}px` }}>
        <aside className="grid min-h-0 grid-rows-[minmax(0,1fr)_auto] border-r border-slate-800 bg-[#0b1120]" aria-label="Файлы проекта">
          <div className="min-h-0 overflow-auto">
            <div className="flex h-10 items-center justify-between border-b border-slate-800 px-3 text-[13px] font-semibold text-slate-200"><span className="flex items-center gap-2"><FolderOpen size={15} /> Проект</span><span className="font-mono text-[11px] text-slate-500">{javaFiles.length}</span></div>
            <FileTree files={files} activeFile={activeFile} onOpen={(path) => void openFile(path)} />
            <details open className="border-t border-slate-800">
              <summary className="cursor-pointer px-3 py-2 text-[12px] font-semibold text-slate-400">Методы · {methods.length}</summary>
              <div className="grid gap-0.5 px-1.5 pb-3">
                {methodGroups.map(([group, items]) => (
                  <details key={group} open={items.some((method) => method.selector === selector) || methodGroups.length <= 3}>
                    <summary className="flex min-w-0 cursor-pointer items-center gap-1.5 rounded px-2 py-1 text-[12px] text-slate-300 hover:bg-slate-900"><span className="min-w-0 flex-1 truncate font-mono" title={group}>{group}</span><span className="font-mono text-[11px] text-slate-500">{items.length}</span></summary>
                    <div className="grid gap-0.5 pl-3">
                      {items.map((method, index) => <button key={`${method.selector}:${index}`} type="button" title={method.id} className={`flex min-w-0 items-center gap-2 rounded px-2 py-1 text-left text-[12px] ${selector === method.selector ? "bg-slate-800 text-slate-100" : "text-slate-400 hover:bg-slate-900"}`} onClick={() => { setSelector(method.selector); setView("cfg"); void openRange(method.source_range); }}><Code2 size={13} className="shrink-0 text-blue-400" /><span className="min-w-0 flex-1 truncate">{method.id.slice(group.length + 1)}</span></button>)}
                    </div>
                  </details>
                ))}
              </div>
            </details>
          </div>
          <BlockPalette onPick={(template) => { setBlockTemplate(template); setView("cfg"); setAnalysisTab("edit"); if (!selector && methods[0]) setSelector(methods[0].selector); setLogLines((lines) => [...lines, { time: logTime(), kind: "info", text: `Блок «${template.label}»: шаблон лежит во вкладке «Правка», выберите оператор на графе и нажмите «Показать вставку»` }]); }} />
        </aside>
        <Splitter axis="x" label="Ширина левой панели" onResize={(delta) => setSizes((c) => ({ ...c, left: clamp(c.left + delta, 160, 520) }))} onReset={() => setSizes((c) => ({ ...c, left: 224 }))} />

        <section className="grid min-h-0 min-w-0 grid-rows-[40px_minmax(0,1fr)] border-r border-slate-800 bg-[#080f1b]" aria-label="Граф проекта">
          <header className="flex min-w-0 items-end justify-between gap-2 border-b border-slate-800 bg-[#0b1120] px-2">
            <div className="flex min-w-0 items-end gap-0.5" role="tablist" aria-label="Открытые файлы">
              {openTabs.map((path) => (
                <button key={path} type="button" role="tab" aria-selected={path === activeFile} className={`flex h-8 max-w-44 items-center gap-1.5 rounded-t border border-b-0 px-3 text-[12px] ${path === activeFile ? "border-slate-700 bg-[#080f1b] text-cyan-200" : "border-transparent text-slate-500 hover:text-slate-300"}`} title={path} onClick={() => void openFile(path)}><FileCode2 size={13} className="shrink-0" /><span className="truncate">{path.split("/").at(-1)}</span></button>
              ))}
            </div>
            <nav className="mb-1 flex items-center gap-1" aria-label="Представление проекта">
              {view === "overview"
                ? <span className="mr-2 hidden text-[11px] text-slate-500 xl:inline">клик по классу — связи и методы · двойной клик — граф метода</span>
                : <button className="mr-2 inline-flex h-7 items-center gap-1 rounded border border-cyan-800 bg-cyan-950/30 px-2 text-[12px] text-cyan-200 hover:bg-cyan-900/40" type="button" title="Вернуться к графу всего проекта" onClick={() => setView("overview")}><ArrowLeft size={14} /> К графу проекта</button>}
              <button className={`inline-flex h-7 items-center gap-1.5 rounded px-2 text-[12px] ${view === "overview" ? "bg-slate-800 text-cyan-200" : "text-slate-400 hover:bg-slate-900"}`} type="button" onClick={() => setView("overview")}><Network size={14} /> Обзор</button>
              <button className={`inline-flex h-7 items-center gap-1.5 rounded px-2 text-[12px] ${view === "cfg" ? "bg-slate-800 text-cyan-200" : "text-slate-400 hover:bg-slate-900"}`} type="button" onClick={() => setView("cfg")}><Waypoints size={14} /> CFG</button>
            </nav>
          </header>
          <div className="min-h-0 min-w-0 overflow-hidden">
            {view === "overview" ? <ProjectGraphViewer projectId={project.id} revision={revision} enabled={!loading && !isDirty} methods={methods} onOpenMethod={(next) => { setSelector(next); setView("cfg"); const found = methods.find((method) => method.selector === next); if (found) void openRange(found.source_range); }} onSourceRangeSelect={(next) => void openRange(next)} /> : (
              <CfgViewer onBlockDrop={(template) => { setBlockTemplate(template); setAnalysisTab("edit"); setLogLines((lines) => [...lines, { time: logTime(), kind: "info", text: `Блок «${template.label}» брошен на оператор: проверьте предпросмотр вставки` }]); }} projectId={project.id} revision={revision} methods={loading || isDirty ? [] : methods} selectedSourceRange={range} selectedMethodSelector={selector} onSelectedMethodSelectorChange={setSelector} onSourceRangeSelect={(next) => { if (next) void openRange(next); }} onTransformed={reloadCurrentRevision} showAnalysisSummary blockTemplate={blockTemplate} panelContainer={editPortal} onNodeSelected={() => setAnalysisTab("edit")} />
            )}
          </div>
        </section>
        <Splitter axis="x" label="Ширина правой панели" onResize={(delta) => setSizes((c) => ({ ...c, right: clamp(c.right - delta, 280, 900) }))} onReset={() => setSizes((c) => ({ ...c, right: 380 }))} />

        <AnalysisPane
          loading={loading} analysis={analysis} methodCount={methods.length} findings={findings} complexity={complexity}
          diagnostics={activeDiagnostics} proposals={optimizations?.proposals ?? []} selectedMethodId={selectedMethod?.id ?? null}
          selectedProposal={selectedProposalData} speedup={speedup} tab={analysisTab} onTab={setAnalysisTab} onPortalRef={setEditPortal}
          onOpenRange={(sourceRange, file) => void openRange({ ...sourceRange, file: sourceRange.file ?? file ?? activeFile })}
          onOpenMethod={(methodId, sourceRange, file) => { const found = methods.find((method) => method.id === methodId); if (found) { setSelector(found.selector); setView("cfg"); } void openRange({ ...sourceRange, file: sourceRange.file ?? file ?? activeFile }); }}
          onMeasure={() => { setWorkbench("benchmark"); void getProjectRevisions(project.id).then(setRevisions); }}
          recommendations={
            <OptimizationPanel
              proposals={optimizations?.proposals ?? []} revision={revision} selectedId={selectedProposal} onSelect={(id) => { setSelectedProposal(id); setPreview(null); setOptimizationError(""); setAcknowledged([]); }}
              selected={selectedProposalData} preview={preview} acknowledged={acknowledged} busy={busy} error={optimizationError}
              onToggleAssumption={(id) => setAcknowledged((current) => current.includes(id) ? current.filter((item) => item !== id) : [...current, id])}
              onPreview={() => void previewSelectedOptimization()} onApply={() => void applySelectedOptimization()}
              onOpenFile={(path, sourceRange) => void openRange({ ...sourceRange, file: path })}
            />
          }
        />
      </div>

      <section className="relative grid min-h-0 grid-rows-[34px_auto] border-t border-slate-700 bg-[#0a1220]" aria-label="Рабочая панель">
        <Splitter axis="y" label="Высота нижних панелей" className="absolute inset-x-0 -top-[3px]" onResize={(delta) => setSizes((c) => ({ ...c, bottom: clamp(c.bottom - delta, 120, 800) }))} onReset={() => setSizes((c) => ({ ...c, bottom: 260 }))} />
        <nav className="flex items-center gap-1 border-b border-slate-800 bg-[#0d1625] px-2" aria-label="Нижние панели">
          {([["code", "Код Java"], ["console", "Консоль"], ["tests", "Результаты тестов"]] as const).map(([id, label]) => (
            <button key={id} type="button" aria-pressed={panes[id]} className={`rounded px-2.5 py-1 text-[12px] ${panes[id] ? "bg-slate-800 text-cyan-200" : "text-slate-500 hover:bg-slate-800 hover:text-slate-300"}`} onClick={() => setPanes((current) => ({ ...current, [id]: !current[id] }))}>{label}</button>
          ))}
          <button type="button" className="ml-auto rounded px-2 py-1 text-[11px] text-slate-500 hover:bg-slate-800 hover:text-slate-300" title="Скрыть или показать все нижние панели" onClick={() => setPanes((current) => (current.code || current.console || current.tests ? { code: false, console: false, tests: false } : { code: true, console: true, tests: true }))}>{panes.code || panes.console || panes.tests ? "Свернуть ▾" : "Развернуть ▴"}</button>
        </nav>
        <div className={`min-h-0 ${panes.code || panes.console || panes.tests ? "" : "hidden"} grid`} style={{ height: codeTall ? "58vh" : sizes.bottom, gridTemplateColumns: [panes.code ? "minmax(0,1.5fr)" : null, panes.console ? "minmax(0,1fr)" : null, panes.tests ? "minmax(0,1fr)" : null].filter(Boolean).join(" ") || "1fr" }}>
        <div className={`${panes.code ? "grid" : "hidden"} h-full min-h-0 grid-rows-[34px_minmax(0,1fr)]`}>
          <header className="flex items-center gap-2 border-b border-slate-800 px-3 text-[12px] text-slate-400"><FileCode2 size={14} /><strong className="text-slate-200">Код Java</strong><span className="truncate font-mono text-slate-500">{activeFile}</span><span className="ml-auto whitespace-nowrap">{dirty ? "Черновик" : `Сохранено · REV ${revision}`}</span><button type="button" className="rounded px-1.5 text-[11px] text-slate-400 hover:bg-slate-800 hover:text-slate-200" title="Высота панелей" onClick={() => setCodeTall((value) => !value)}>{codeTall ? "▾ меньше" : "▴ больше"}</button></header>
            <div className="min-h-0 min-w-0"><JavaEditor key={`${activeFile}:${revision}:${savedSource.length}`} ref={editorRef} value={source} diagnostics={activeDiagnostics} focusRange={range} onDirtyChange={(next) => { dirtyRef.current = next; setDirty(next); }} onMetricsChange={() => {}} onSourceSelection={(selection) => setRange(selection ? { ...selection, file: activeFile } : null)} /></div>
        </div>
        {panes.console ? <ConsolePane lines={logLines} /> : null}
        {panes.tests ? <TestsPane report={verifyReport} busy={verifyBusy} onRun={() => { setWorkbench("verify"); void getProjectRevisions(project.id).then(setRevisions); }} /> : null}
        </div>
      </section>

      {workbench !== "code" ? (
        <div className="fixed inset-x-0 bottom-0 top-[78px] z-30 grid grid-rows-[40px_minmax(0,1fr)] bg-[#0a1220]" role="dialog" aria-label={overlayTitle}>
          <header className="flex items-center gap-3 border-b border-slate-700 bg-[#0d1625] px-4"><strong className="text-[14px] text-slate-100">{overlayTitle}</strong><button className="ml-auto rounded border border-slate-700 px-3 py-1 text-[12px] text-slate-200 hover:border-cyan-700" type="button" onClick={() => { setWorkbench("code"); setPanes((current) => (current.code ? current : { ...current, code: true })); }}>Код</button></header>
          <div className="min-h-0 overflow-auto">
        {workbench === "run" ? <RunWorkbench stdin={runStdin ?? ""} onStdin={setRunStdin} mainClass={mainClass} onMainClass={setMainClass} revision={revision} busy={runBusy} error={runError} result={runResult} onRun={() => void runProgram(runStdin ?? "")} scenarios={cases.filter((item) => item.stdin.trim())} /> : null}
        {workbench === "verify" ? <VerificationWorkbench onSuggest={() => void (async () => { const sources = [source]; for (const file of javaFiles.filter((item) => item.path !== activeFile).slice(0, 30)) { try { sources.push((await getProjectFile(project.id, file.path, revision)).source); } catch { /* skip */ } } setCases(suggestScenarios(sources)); })()} revisions={revisions} baseline={baselineRevision} candidate={candidateRevision} onBaseline={setBaselineRevision} onCandidate={setCandidateRevision} cases={cases} onCases={setCases} mainClass={mainClass} onMainClass={setMainClass} sourceLevel={sourceLevel} onSourceLevel={setSourceLevel} repeats={repeats} onRepeats={setRepeats} report={verifyReport} error={verifyError} busy={verifyBusy} onRun={() => void runFunctionalVerification()} /> : null}
        {workbench === "benchmark" ? <BenchmarkWorkbench cases={cases} revisions={revisions} baseline={baselineRevision} candidate={candidateRevision} onBaseline={setBaselineRevision} onCandidate={setCandidateRevision} mainClass={mainClass} onMainClass={setMainClass} sourceLevel={sourceLevel} onSourceLevel={setSourceLevel} workload={workload} onWorkload={setWorkload} iterations={measuredIterations} onIterations={setMeasuredIterations} report={benchmarkReport} error={benchmarkError} busy={benchmarkBusy} onRun={() => void runRuntimeBenchmark()} /> : null}
        {workbench === "diff" ? <DiffWorkbench revisions={revisions} from={diffFrom} to={diffTo} onFrom={setDiffFrom} onTo={setDiffTo} files={revisionDiff} onLoad={() => void loadDiff()} /> : null}
        {workbench === "history" ? <HistoryPanel projectId={project.id} revision={revision} dirty={dirty} onRolledBack={async () => { await reloadCurrentRevision(); setWorkbench("code"); }} onVerify={(baseline, candidate) => { setBaselineRevision(baseline); setCandidateRevision(candidate); setWorkbench("verify"); void getProjectRevisions(project.id).then(setRevisions); }} /> : null}
        {workbench === "decompose" ? <DecompositionPanel key={`${revision}:${sourceLevel}`} projectId={project.id} revision={revision} sourceLevel={sourceLevel} dirty={dirty} onApplied={reloadCurrentRevision} /> : null}
          </div>
        </div>
      ) : null}
    </main>
  );
}

function FileTree({ files, activeFile, onOpen }: { files: ProjectFile[]; activeFile: string; onOpen: (path: string) => void }) {
  const root = useMemo(() => buildTree(files), [files]);
  return <div className="grid gap-0.5 p-1.5" role="tree" aria-label="Иерархия файлов">
    {root.map((item) => <TreeItem key={item.path} item={item} depth={0} activeFile={activeFile} onOpen={onOpen} />)}
  </div>;
}

type TreeNode = { name: string; path: string; file?: ProjectFile; children: TreeNode[] };
function buildTree(files: ProjectFile[]): TreeNode[] {
  const root: TreeNode = { name: "", path: "", children: [] };
  for (const file of files) {
    let parent = root;
    const parts = file.path.split("/");
    for (const [index, name] of parts.entries()) {
      const path = parts.slice(0, index + 1).join("/");
      let child = parent.children.find((item) => item.name === name);
      if (!child) { child = { name, path, children: [] }; parent.children.push(child); }
      if (index === parts.length - 1) child.file = file;
      parent = child;
    }
  }
  const sort = (nodes: TreeNode[]) => { nodes.sort((a, b) => Number(Boolean(a.file)) - Number(Boolean(b.file)) || a.name.localeCompare(b.name)); nodes.forEach((node) => sort(node.children)); };
  sort(root.children);
  return root.children;
}

function TreeItem({ item, depth, activeFile, onOpen }: { item: TreeNode; depth: number; activeFile: string; onOpen: (path: string) => void }) {
  if (!item.file) return <details open className="min-w-0" role="treeitem" aria-selected={false}><summary className="flex h-7 cursor-pointer items-center gap-1.5 rounded px-1 text-[12px] text-slate-400 hover:bg-slate-900" style={{ paddingLeft: 6 + depth * 12 }}><ChevronRight size={12} className="rotate-90" /><FolderOpen size={14} className="text-amber-300" /><span className="truncate">{item.name}</span></summary><div>{item.children.map((child) => <TreeItem key={child.path} item={child} depth={depth + 1} activeFile={activeFile} onOpen={onOpen} />)}</div></details>;
  if (item.file.kind !== "java") return <div role="treeitem" aria-selected={false} className="flex h-7 items-center gap-1.5 truncate px-1 text-[12px] text-slate-600" style={{ paddingLeft: 20 + depth * 12 }} title={`${item.path} · ресурс`}>{item.name}</div>;
  return <button role="treeitem" aria-selected={item.path === activeFile} data-testid={`file-${item.path}`} className={`flex h-7 w-full min-w-0 items-center gap-1.5 rounded border-0 bg-transparent pr-1 text-left text-[12px] ${item.path === activeFile ? "bg-slate-800 text-cyan-200" : "text-slate-400 hover:bg-slate-900"}`} style={{ paddingLeft: 20 + depth * 12 }} type="button" title={item.path} onClick={() => onOpen(item.path)}><FileCode2 size={14} className="shrink-0 text-blue-400" /><span className="truncate">{item.name}</span><small className="ml-auto shrink-0 text-[10px] text-slate-600">{item.file.role === "main" ? "main" : item.file.role}</small></button>;
}

function OptimizationPanel({ proposals, revision, selectedId, onSelect, selected, preview, acknowledged, busy, error, onToggleAssumption, onPreview, onApply, onOpenFile }: {
  proposals: NonNullable<OptimizationList["proposals"]>; revision: number; selectedId: string; onSelect: (id: string) => void;
  selected: OptimizationList["proposals"][number] | null; preview: OptimizationPreview | null; acknowledged: string[]; busy: boolean; error: string;
  onToggleAssumption: (id: string) => void; onPreview: () => void; onApply: () => void; onOpenFile: (path: string, range: SourceRange) => void;
}) {
  return <section className="mt-3 grid gap-2 border-t border-slate-800 pt-2" aria-label="Оптимизации"><h2 className="m-0 text-[12px] font-semibold text-slate-400">Оптимизация · {proposals.length}</h2>
    {proposals.length === 0 ? <p className="m-0 text-[12px] leading-5 text-slate-500">Для текущей ревизии предложений нет.</p> : null}
    {proposals.map((proposal) => <button key={proposal.id} className={`rounded border px-2 py-1.5 text-left ${selectedId === proposal.id ? "border-cyan-800 bg-cyan-950/30" : "border-slate-800 bg-slate-900/50 hover:border-slate-600"}`} type="button" onClick={() => onSelect(proposal.id)}><strong className="block text-[12px] text-slate-200">{ruRuleTitle(proposal.rule, proposal.title)}</strong><small className="block truncate font-mono text-[11px] text-slate-500">{proposal.method_id ?? proposal.file}</small><small className={`text-[11px] ${proposal.applicability === "applicable" ? "text-emerald-400" : "text-amber-400"}`}>{proposal.applicability === "applicable" ? "Применимо после preview" : "Не применимо"}</small></button>)}
    {selected ? <div className="grid gap-2 rounded border border-slate-800 bg-slate-950/60 p-2">
      <p className="m-0 text-[12px] leading-5 text-slate-300">{ruProposalSummary(selected)}</p><p className="m-0 text-[11px] leading-4 text-slate-500">{ruComplexityNote(selected.rule, selected.complexity.note)}</p>
      <div className="flex flex-wrap gap-1.5 font-mono text-[11px]"><span className="rounded bg-slate-800 px-1.5 py-1 text-slate-300">{selected.complexity.scan_before ?? selected.complexity.method_before ?? "unknown"}</span><span className="text-slate-600">→</span><span className="rounded bg-slate-800 px-1.5 py-1 text-slate-300">{selected.complexity.scan_after ?? "—"}</span></div>
      <small className="text-[11px] leading-4 text-slate-500">Файл: {selected.file}; затронуты: {selected.affected_files.join(", ") || "не указаны"}</small>
      {selected.preconditions.map((condition) => <p className={`m-0 text-[11px] leading-4 ${condition.status === "satisfied" ? "text-emerald-400" : "text-amber-300"}`} key={condition.id}>{condition.status === "satisfied" ? "✓" : "!"} {ruPrecondition(condition)}{condition.status === "satisfied" ? "" : ` — ${condition.detail}`}</p>)}
      {selected.applicability === "not_applicable" ? <p className="m-0 text-[12px] text-amber-300">{selected.not_applicable_reasons?.join("; ") || "Нарушено предусловие оптимизации."}</p> : <Button variant="outline" size="sm" disabled={busy || Boolean(preview)} onClick={onPreview}>Предпросмотр diff</Button>}
      {error ? <p className="m-0 text-[12px] text-rose-300" role="alert">{error}</p> : null}
      {preview ? <PreviewDialog title={`Предпросмотр: ${ruRuleTitle(selected.rule, selected.title)}`} onClose={() => onSelect("")}><div className="grid gap-3" aria-label="Предпросмотр оптимизации"><DiffView files={preview.diff} label="Diff оптимизации" />
        {selected.assumptions.map((assumption) => <label className="flex items-start gap-2 text-[12px] leading-5 text-amber-200" key={assumption.id}><input className="mt-0.5 size-5 shrink-0 cursor-pointer accent-cyan-500" type="checkbox" checked={acknowledged.includes(assumption.id)} onChange={() => onToggleAssumption(assumption.id)} /><span>{ruAssumption(assumption)}</span></label>)}
        <p className="m-0 text-[11px] leading-4 text-slate-500">Подтвердите каждое допущение: предпросмотр — это только переписывание кода, а совпадение поведения показывает вкладка «Проверка».</p>
        <div className="flex flex-wrap gap-2"><Button size="sm" disabled={busy || acknowledged.length !== selected.assumptions.length} onClick={onApply}>Применить · REV {revision + 1}</Button><Button size="sm" variant="outline" disabled={busy} onClick={() => onSelect("")}>Отмена</Button></div>
        <button className="text-left text-[11px] text-cyan-300" type="button" onClick={() => onOpenFile(selected.file, selected.outer_loop ?? { start: 0, length: 1 })}>Открыть затронутый файл</button>
      </div></PreviewDialog> : null}
    </div> : null}
  </section>;
}

function RevisionSelect({ label, values, value, onChange }: { label: string; values: { revision: number; has_analysis: boolean }[]; value: number; onChange: (value: number) => void }) {
  return <label className="grid gap-1 text-[12px] text-slate-400">{label}<select className="h-9 w-full rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[12px] text-slate-200" value={value} onChange={(event) => onChange(Number(event.target.value))}>{values.map((item) => <option key={item.revision} value={item.revision}>REV {item.revision}{item.has_analysis ? " · анализ" : ""}</option>)}</select></label>;
}

function RunWorkbench({ stdin, onStdin, mainClass, onMainClass, revision, busy, error, result, onRun, scenarios }: {
  stdin: string; onStdin: (value: string) => void; mainClass: string; onMainClass: (value: string) => void; revision: number; busy: boolean; error: string;
  result: { revision: number; report: VerificationReport } | null; onRun: () => void; scenarios: VerifyTestCase[];
}) {
  const run = result?.report.test_cases[0]?.baseline ?? null;
  const compilation = result?.report.baseline_compilation ?? null;
  return <div className="mx-auto grid w-full max-w-[1500px] content-start gap-4 p-4" aria-label="Запуск программы">
    <header className="flex flex-wrap items-center gap-2 text-[14px] font-semibold text-slate-200"><Play size={16} /> Запуск REV {revision} <span className="text-[12px] font-normal text-slate-500">в изолированном Docker-контейнере: без сети, с лимитами CPU, памяти и времени</span></header>
    <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <div className="grid content-start gap-2">
        <label className="grid gap-1 text-[12px] text-slate-400">stdin программы<textarea aria-label="stdin запуска" className="min-h-48 w-full resize-y rounded border border-slate-700 bg-[#08101d] p-2 font-mono text-[13px] leading-5 text-slate-200" value={stdin} onChange={(event) => onStdin(event.target.value)} /></label>
        {scenarios.length ? <div className="flex flex-wrap items-center gap-1.5 text-[12px] text-slate-500">Взять из сценария:{scenarios.slice(0, 8).map((item) => <button key={item.name} type="button" className="rounded border border-slate-700 px-2 py-0.5 text-slate-300 hover:border-cyan-700" onClick={() => onStdin(item.stdin)}>{item.name}</button>)}</div> : null}
        <label className="grid gap-1 text-[12px] text-slate-400">Класс запуска<input aria-label="Главный класс запуска" className="h-9 w-full rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[12px] text-slate-200" placeholder="если main один — не нужно" value={mainClass} onChange={(event) => onMainClass(event.target.value)} /></label>
        <Button className="w-fit" onClick={onRun} disabled={busy}>{busy ? <LoaderCircle className="spin" /> : <Play />} Запустить REV {revision}</Button>
        {error ? <p role="alert" className="m-0 text-[13px] text-rose-300">{error}</p> : null}
      </div>
      <section className="grid content-start gap-2" aria-label="Вывод программы">
        {!result && !busy ? <p className="m-0 rounded border border-dashed border-slate-700 p-4 text-[13px] text-slate-500">Здесь появится вывод программы. Он же дублируется во вкладку «Консоль».</p> : null}
        {busy ? <p className="m-0 flex items-center gap-2 text-[13px] text-slate-400"><LoaderCircle className="spin" size={14} /> Компиляция и запуск…</p> : null}
        {result && result.report.status === "compilation_failed" ? <div className="rounded border border-rose-800 bg-rose-950/30 p-3 text-[13px] text-rose-200">REV {result.revision} не компилируется{compilation?.diagnostics.length ? <ul className="mb-0">{compilation.diagnostics.slice(0, 10).map((item, index) => <li key={index}>{item.message}</li>)}</ul> : <pre className="m-0 mt-2 whitespace-pre-wrap font-mono text-[12px]">{compilation?.stderr.utf8 ?? ""}</pre>}</div> : null}
        {run ? <>
          <div className="flex flex-wrap gap-2 text-[12px]"><span className={`rounded px-2 py-0.5 ${run.exit_code === 0 ? "bg-emerald-500/15 text-emerald-200" : "bg-rose-500/15 text-rose-200"}`}>код выхода {run.exit_code ?? "—"}</span>{run.timed_out ? <span className="rounded bg-amber-500/15 px-2 py-0.5 text-amber-200">превышен лимит времени</span> : null}{run.stdout.truncated ? <span className="rounded bg-amber-500/15 px-2 py-0.5 text-amber-200">вывод обрезан</span> : null}</div>
          <pre aria-label="stdout запуска" className="m-0 max-h-[55vh] overflow-auto whitespace-pre-wrap rounded border border-slate-800 bg-[#08101d] p-3 font-mono text-[12px] leading-5 text-slate-200">{run.stdout.utf8 ?? "(вывод не в UTF-8)"}</pre>
          {run.stderr.utf8 ? <pre aria-label="stderr запуска" className="m-0 max-h-48 overflow-auto whitespace-pre-wrap rounded border border-rose-900/60 bg-rose-950/20 p-3 font-mono text-[12px] leading-5 text-rose-200">{run.stderr.utf8}</pre> : null}
        </> : null}
      </section>
    </div>
  </div>;
}

function VerificationWorkbench({ revisions, baseline, candidate, onBaseline, onCandidate, cases, onCases, mainClass, onMainClass, sourceLevel, onSourceLevel, repeats, onRepeats, report, error, busy, onRun, onSuggest }: {
  onSuggest: () => void;
  revisions: { revision: number; has_analysis: boolean }[]; baseline: number; candidate: number; onBaseline: (revision: number) => void; onCandidate: (revision: number) => void;
  cases: VerifyTestCase[]; onCases: (cases: VerifyTestCase[]) => void; mainClass: string; onMainClass: (value: string) => void; sourceLevel: string; onSourceLevel: (value: string) => void; repeats: number; onRepeats: (value: number) => void; report: VerificationReport | null; error: string; busy: boolean; onRun: () => void;
}) {
  const update = (index: number, patch: Partial<VerifyTestCase>) => onCases(cases.map((test, i) => (i === index ? { ...test, ...patch } : test)));
  return <div className="mx-auto grid w-full max-w-[1500px] content-start gap-4 p-4" aria-label="Поведенческая проверка"><header className="flex flex-wrap items-center gap-2 text-[14px] font-semibold text-slate-200"><ShieldCheck size={16} /> Функциональная проверка <span className="text-[12px] font-normal text-slate-500">сравнивает вывод до и после, не время</span></header>
    <div className="grid grid-cols-[repeat(auto-fit,minmax(190px,1fr))] gap-3">
      <RevisionSelect label="Baseline (до)" values={revisions} value={baseline} onChange={onBaseline} />
      <RevisionSelect label="Candidate (после)" values={revisions} value={candidate} onChange={onCandidate} />
      <label className="grid gap-1 text-[12px] text-slate-400">Класс запуска<input aria-label="Главный класс Java" className="h-9 w-full rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[12px] text-slate-200" placeholder="например shop.Main (если main один — не нужно)" value={mainClass} onChange={(event) => onMainClass(event.target.value)} /></label>
      <label className="grid gap-1 text-[12px] text-slate-400">Java<select className="h-9 w-full rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200" value={sourceLevel} onChange={(event) => onSourceLevel(event.target.value)}>{["8", "11", "17", "21"].map((level) => <option key={level} value={level}>{level}</option>)}</select></label>
      <label className="grid gap-1 text-[12px] text-slate-400">Запусков каждого сценария<select aria-label="Число запусков сценария" className="h-9 w-full rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200" value={repeats} onChange={(event) => onRepeats(Number(event.target.value))}>{[1, 2, 3, 5].map((count) => <option key={count} value={count}>{count}{count === 1 ? " (нестабильный вывод не заметить)" : ""}</option>)}</select></label>
    </div>
    <div className="grid gap-3">{cases.map((item, index) => (
      <div className="grid gap-2 rounded-md border border-slate-800 bg-slate-900/40 p-3 lg:grid-cols-[minmax(160px,240px)_minmax(0,1fr)_minmax(0,1fr)_auto]" key={index}>
        <label className="grid content-start gap-1 text-[12px] text-slate-400">Сценарий<input className="h-9 w-full rounded border border-slate-700 bg-[#08101d] px-2 text-[13px] text-slate-200" aria-label={`Имя сценария ${index + 1}`} value={item.name} onChange={(event) => update(index, { name: event.target.value })} /></label>
        <label className="grid gap-1 text-[12px] text-slate-400">stdin — что вводит пользователь<textarea className="min-h-28 w-full resize-y rounded border border-slate-700 bg-[#08101d] p-2 font-mono text-[12px] leading-5 text-slate-200" aria-label={`stdin сценария ${index + 1}`} value={item.stdin} onChange={(event) => update(index, { stdin: event.target.value })} /></label>
        <label className="grid gap-1 text-[12px] text-slate-400">Ожидаемый stdout (необязательно)<textarea className="min-h-28 w-full resize-y rounded border border-slate-800 bg-[#08101d] p-2 font-mono text-[12px] leading-5 text-slate-300 placeholder:text-slate-600" aria-label={`Ожидаемый stdout сценария ${index + 1}`} placeholder="если задан — сверяется с выводом исходной версии" value={item.expected_stdout ?? ""} onChange={(event) => update(index, { expected_stdout: event.target.value ? event.target.value : null })} /></label>
        <Button className="self-start lg:mt-5" size="sm" variant="outline" disabled={cases.length === 1} onClick={() => onCases(cases.filter((_, i) => i !== index))}>Удалить</Button>
      </div>))}
      <div className="flex flex-wrap items-center gap-3"><Button className="w-fit" size="sm" variant="outline" disabled={cases.length >= 20} onClick={() => onCases([...cases, { name: `Сценарий ${cases.length + 1}`, stdin: "" }])}>Добавить сценарий</Button>
        <Button className="w-fit" size="sm" variant="outline" title="Заполнить сценарии командами, которые программа сравнивает с вводом" onClick={onSuggest}>Подсказать сценарии по коду</Button>
        <Button className="w-fit" onClick={onRun} disabled={busy || !cases.length || cases.some((item) => !item.name.trim())}>{busy ? <LoaderCircle className="spin" /> : <ShieldCheck />} Запустить реальные сценарии</Button>
        <span className="text-[12px] text-slate-500">сценарии сохраняются и запускаются автоматически после каждой правки (REV 1 против новой); сценарий должен доходить до изменённого кода</span></div>
    </div>
    {error ? <p role="alert" className="m-0 text-[13px] text-rose-300">{error}{error.includes("main_class_required") ? " Введите полностью квалифицированный класс с main(String[])." : ""}</p> : null}
    {report ? <VerificationReportView report={report} /> : null}
  </div>;
}

function VerificationReportView({ report }: { report: VerificationReport }) {
  return <section className="grid w-full gap-2 rounded border border-slate-700 bg-slate-950/70 p-3 text-[12px]" aria-label="Результат функциональной проверки"><strong className={report.behaviorally_verified ? "text-emerald-300" : report.status === "unstable" ? "text-amber-300" : "text-rose-300"}>{report.behaviorally_verified ? "Сценарии совпали" : report.status === "compilation_failed" ? "Компиляция не прошла" : report.status === "unstable" ? "Результат неопределён: вывод нестабилен" : "Есть различия"} · REV {report.baseline_revision} → REV {report.candidate_revision}{report.repeats && report.repeats > 1 ? ` · ${report.repeats} запуска на сценарий` : ""}</strong><p className="m-0 text-slate-400">{ruText(report.explanation)}</p>
    {report.warnings?.length ? <div className="grid gap-1 rounded border border-amber-900/60 bg-amber-950/20 p-2" aria-label="Предупреждения проверки">{report.warnings.map((warning) => <p className="m-0 text-amber-200" key={warning.code}><code className="mr-1 text-amber-400">{warning.code}</code>{ruWarning(warning)}</p>)}</div> : null}<div className="flex gap-4 text-slate-400"><span>Сборка baseline: {report.baseline_compilation.status}</span><span>candidate: {report.candidate_compilation.status}</span><span>Сценариев: {report.executed_test_cases}/{report.requested_test_cases}</span></div><button className="w-fit text-cyan-300" type="button" onClick={() => navigator.clipboard?.writeText(JSON.stringify(report, null, 2))}>Скопировать реальный JSON-отчёт</button>{report.test_cases.map((item) => <details className="border-t border-slate-800 pt-2" key={item.name}><summary className="cursor-pointer text-slate-300">{item.passed ? "✓" : "!"} {item.name} · {item.mismatches.length ? "различия" : "совпало"}</summary>{item.mismatches.map((mismatch) => <p className={`m-0 mt-1 ${mismatch.field === "unstable" ? "text-amber-300" : "text-rose-300"}`} key={mismatch.field}>{mismatch.field}: {ruText(mismatch.explanation)}</p>)}{item.expectation_failures?.map((failure) => <p className="m-0 mt-1 text-rose-300" key={`expected-${failure.field}`}>ожидание {failure.field}: {failure.explanation}</p>)}</details>)}{report.not_covered?.length ? <details className="border-t border-slate-800 pt-2"><summary className="cursor-pointer text-slate-400">Чего эта проверка не показывает · {report.not_covered.length}</summary><ul className="m-0 mt-1 grid gap-1 pl-4 text-slate-500">{report.not_covered.map((note) => <li key={note}>{ruNotCovered(note)}</li>)}</ul></details> : null}<small className="text-slate-500">Проверка относится только к введённым сценариям, полной эквивалентности не доказывает.</small></section>;
}

function BenchmarkWorkbench({ cases, revisions, baseline, candidate, onBaseline, onCandidate, mainClass, onMainClass, sourceLevel, onSourceLevel, workload, onWorkload, iterations, onIterations, report, error, busy, onRun }: {
  cases: VerifyTestCase[]; revisions: { revision: number; has_analysis: boolean }[]; baseline: number; candidate: number; onBaseline: (revision: number) => void; onCandidate: (revision: number) => void; mainClass: string; onMainClass: (value: string) => void; sourceLevel: string; onSourceLevel: (value: string) => void;
  workload: { name: string; size: string; stdin: string }; onWorkload: (value: { name: string; size: string; stdin: string }) => void; iterations: number; onIterations: (value: number) => void; report: BenchmarkReport | null; error: string; busy: boolean; onRun: () => void;
}) {
  const field = "h-9 w-full rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200";
  return <div className="mx-auto grid w-full max-w-[1500px] content-start gap-4 p-4" aria-label="Измерение производительности"><header className="flex flex-wrap items-center gap-2 text-[14px] font-semibold text-slate-200"><Gauge size={16} /> Замер времени выполнения <span className="text-[12px] font-normal text-slate-500">реальные JVM-измерения; Big O отдельно</span></header>
    <div className="grid grid-cols-[repeat(auto-fit,minmax(190px,1fr))] gap-3">
      <RevisionSelect label="Baseline (до)" values={revisions} value={baseline} onChange={onBaseline} />
      <RevisionSelect label="Candidate (после)" values={revisions} value={candidate} onChange={onCandidate} />
      <label className="grid gap-1 text-[12px] text-slate-400">Главный класс<input aria-label="Главный класс для бенчмарка" className={`${field} font-mono`} value={mainClass} onChange={(event) => onMainClass(event.target.value)} placeholder="shop.Main (если main один — не нужно)" /></label>
      <label className="grid gap-1 text-[12px] text-slate-400">Java<select className={field} value={sourceLevel} onChange={(event) => onSourceLevel(event.target.value)}>{["8", "11", "17", "21"].map((level) => <option key={level} value={level}>{level}</option>)}</select></label>
    </div>
    <section className="grid gap-3 rounded-md border border-slate-800 bg-slate-900/40 p-3" aria-label="Параметры замера">
      <div className="flex flex-wrap items-center gap-2 text-[12px] text-slate-400"><strong className="text-slate-200">Нагрузка</strong>
        <label className="flex items-center gap-2">взять из сценария проверки
          <select aria-label="Сценарий для нагрузки" className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200" value="" onChange={(event) => { const chosen = cases.find((item) => item.name === event.target.value); if (chosen) onWorkload({ ...workload, name: chosen.name, stdin: chosen.stdin }); }}>
            <option value="">— выбрать —</option>
            {cases.filter((item) => item.stdin.trim()).map((item) => <option key={item.name} value={item.name}>{item.name}</option>)}
          </select></label>
        <span className="text-slate-500">ввод должен подходить вашей программе, иначе замер ничего не покажет</span></div>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(150px,1fr))] gap-3">
        <label className="grid gap-1 text-[12px] text-slate-400">Нагрузка (название)<input className={field} value={workload.name} onChange={(event) => onWorkload({ ...workload, name: event.target.value })} /></label>
        <label className="grid gap-1 text-[12px] text-slate-400">Размер входа (число)<input type="number" min="0" className={field} value={workload.size} onChange={(event) => onWorkload({ ...workload, size: event.target.value })} /></label>
        <label className="grid gap-1 text-[12px] text-slate-400">Итераций (от 10)<input aria-label="Число измерений" type="number" min="1" max="100" className={field} value={iterations} onChange={(event) => onIterations(Math.max(1, Math.min(100, Number(event.target.value))))} /></label>
      </div>
      <label className="grid gap-1 text-[12px] text-slate-400">stdin<textarea className="min-h-32 w-full resize-y rounded border border-slate-700 bg-[#08101d] p-2 font-mono text-[12px] leading-5 text-slate-200" value={workload.stdin} onChange={(event) => onWorkload({ ...workload, stdin: event.target.value })} /></label>
    </section>
    {error ? <p role="alert" className="m-0 text-[13px] text-rose-300">{error}{error.includes("main_class_required") ? " Введите класс с main(String[])." : ""}</p> : null}
    <Button className="w-fit" disabled={busy || !workload.name.trim()} onClick={onRun}>{busy ? <LoaderCircle className="spin" /> : <GitCompareArrows />} Измерить ревизии</Button>
    {report ? <BenchmarkReportView report={report} /> : null}
  </div>;
}

function BenchmarkReportView({ report }: { report: BenchmarkReport }) {
  return <section className="grid w-full gap-3 rounded border border-slate-700 bg-slate-950/70 p-3 text-[12px]" aria-label="Результат runtime benchmark"><div className="flex flex-wrap items-center gap-3"><strong className="text-slate-200">{report.status} · REV {report.baseline_revision} → REV {report.candidate_revision}</strong><span className="text-slate-500">Отчёт {report.report_id}</span><span className="text-slate-400">JVM {String(report.environment.baseline_jvm?.version ?? "unknown")}</span><span className="text-slate-400">CPU {report.environment.cpu_limit} · память {report.environment.memory_limit}</span></div><div className="grid gap-1 rounded border border-slate-800 p-2"><strong className="text-violet-200">Статическая сложность (Big O)</strong><span className="text-slate-400">{ruText(report.static_complexity.note)}</span>{report.static_complexity.changes.map((item) => <code key={item.method_id} className="text-slate-300">{item.method_id}: {item.before} → {item.after}</code>)}</div><div className="rounded border border-slate-800 p-2"><strong className={report.functional.equivalent_on_all_workloads ? "text-emerald-300" : "text-rose-300"}>Функционально: {report.functional.equivalent_on_all_workloads ? "вывод и результат совпали" : "обнаружены различия"}</strong><p className="m-0 mt-1 text-slate-400">{ruText(report.functional.note)}</p></div><div className="grid gap-2">{report.runtime.workloads.map((item) => <article className="grid gap-1 rounded border border-slate-800 p-2" key={item.name}><strong className="text-slate-200">{item.name} · вход {item.stdin_bytes} байт · SHA-256 {item.stdin_sha256.slice(0, 12)}</strong><div className="flex flex-wrap gap-x-4 gap-y-1 font-mono text-slate-400"><span>Вывод: {ruVerdict(item.verdict)}</span><span>baseline медиана: {formatNs(item.baseline_stats?.median_ns)}</span><span>candidate медиана: {formatNs(item.candidate_stats?.median_ns)}</span><span>разброс IQR: {formatPercent(item.baseline_stats?.relative_spread)} / {formatPercent(item.candidate_stats?.relative_spread)}</span>{item.speedup !== null ? <strong className="text-emerald-300">измерено: {item.speedup.toFixed(2)}×</strong> : <span>коэффициент ускорения не подтверждён</span>}{item.p_value !== null ? <span>p={item.p_value.toPrecision(3)}</span> : null}</div><small className="text-slate-500">измерений: {item.baseline_stats?.iterations ?? 0} / {item.candidate_stats?.iterations ?? 0}; размер ввода: {item.declared_size ?? "не указан"}</small>{item.notes.map((note) => <small key={note} className="text-amber-300">{ruText(note)}</small>)}</article>)}</div><div className="text-[11px] text-slate-500">Компиляция и запуск JVM: {report.timings.baseline.compile_and_setup_ms} ms / {report.timings.candidate.compile_and_setup_ms} ms. Это не время алгоритма. Измеренные samples приведены в наносекундах.</div>{report.limitations.map((item) => <small key={item} className="text-slate-500">{ruText(item)}</small>)}</section>;
}

function DiffWorkbench({ revisions, from, to, onFrom, onTo, files, onLoad }: { revisions: { revision: number; has_analysis: boolean }[]; from: number; to: number; onFrom: (value: number) => void; onTo: (value: number) => void; files: FileDiff[] | null; onLoad: () => void }) {
  return <div className="grid content-start gap-3 p-3"><h2 className="m-0 text-[14px] text-slate-200">Сравнение файлов ревизий</h2><div className="flex items-end gap-2"><RevisionSelect label="От" values={revisions} value={from} onChange={onFrom} /><RevisionSelect label="К" values={revisions} value={to} onChange={onTo} /><Button onClick={onLoad}>Показать diff</Button></div>{files ? <DiffView files={files} label="Изменения между ревизиями" /> : null}</div>;
}

function formatNs(value: number | undefined) { return value === undefined ? "—" : `${(value / 1e6).toFixed(3)} ms`; }
function formatPercent(value: number | undefined) { return value === undefined ? "—" : `${(value * 100).toFixed(1)}%`; }
function errorMessage(cause: unknown) {
  if (!(cause instanceof ApiError)) return "Не удалось связаться с API.";
  if (cause.code === "main_class_required") return `${cause.code}: ${cause.message}`;
  if (cause.code === "runner_unavailable") return "Docker runner недоступен. Соберите java-blueprints-runner:java21.";
  if (cause.code === "revision_conflict") return "Ревизия изменилась на сервере. Обновите проект; локальные правки не отправлены.";
  const details = cause.details as { reasons?: { message?: string; code?: string }[] } | undefined;
  const reasons = details?.reasons?.map((item) => item.message ?? item.code).filter(Boolean).join("; ");
  return reasons ? `${cause.message}: ${reasons}` : cause.message;
}
