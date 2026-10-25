"use client";

import { GitCompareArrows, LoaderCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { BenchmarkReport, BenchmarkWorkloadInput } from "@/lib/api";

export function BenchmarkPanel({
  revisions, baseline, candidate, onBaseline, onCandidate, workload, onWorkload,
  iterations, onIterations, mainClass, onMainClass, sourceLevel, onSourceLevel,
  report, error, busy, dirty, onRun, onClose,
}: {
  revisions: { revision: number; has_analysis: boolean }[] | undefined;
  baseline: number;
  candidate: number;
  onBaseline: (value: number) => void;
  onCandidate: (value: number) => void;
  workload: BenchmarkWorkloadInput;
  onWorkload: (value: BenchmarkWorkloadInput) => void;
  iterations: number;
  onIterations: (value: number) => void;
  mainClass: string;
  onMainClass: (value: string) => void;
  sourceLevel: string;
  onSourceLevel: (value: string) => void;
  report: BenchmarkReport | null;
  error: string;
  busy: boolean;
  dirty: boolean;
  onRun: () => void;
  onClose: () => void;
}) {
  return (
    <section className="grid size-full min-h-0 content-start gap-3 overflow-auto bg-[#0a1220] p-3" aria-label="Измерение времени выполнения">
      <header className="flex items-start justify-between gap-3"><div><h2 className="m-0 flex items-center gap-2 text-[14px] text-slate-200"><GitCompareArrows size={16} /> Runtime benchmark</h2><p className="m-0 mt-1 text-[12px] text-slate-500">Измеряет реальные запуски в runner. Статический Big O и функциональная проверка показаны отдельно.</p></div><button className="bg-transparent text-slate-500" type="button" aria-label="Закрыть бенчмарк" onClick={onClose}>×</button></header>
      {!revisions ? <p role="status" className="text-[13px] text-slate-400">Загружаем ревизии…</p> : (
        <div className="grid max-w-5xl grid-cols-[1fr_1fr_220px_100px_130px] gap-2">
          <label className="grid gap-1 text-[12px] text-slate-400">Baseline<select className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[12px] text-slate-200" value={baseline} onChange={(event) => onBaseline(Number(event.target.value))}>{revisions.map((item) => <option key={item.revision} value={item.revision}>REV {item.revision}</option>)}</select></label>
          <label className="grid gap-1 text-[12px] text-slate-400">Candidate<select className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[12px] text-slate-200" value={candidate} onChange={(event) => onCandidate(Number(event.target.value))}>{revisions.map((item) => <option key={item.revision} value={item.revision}>REV {item.revision}</option>)}</select></label>
          <label className="grid gap-1 text-[12px] text-slate-400">Измерений<input className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200" type="number" min={1} max={100} value={iterations} onChange={(event) => onIterations(Math.max(1, Math.min(100, Number(event.target.value))))} /></label>
          <label className="grid gap-1 text-[12px] text-slate-400">Главный класс<input aria-label="Главный класс для бенчмарка" className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[12px] text-slate-200" value={mainClass} onChange={(event) => onMainClass(event.target.value)} placeholder="например Organizer" /></label>
          <label className="grid gap-1 text-[12px] text-slate-400">Java<select aria-label="Уровень Java для бенчмарка" className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200" value={sourceLevel} onChange={(event) => onSourceLevel(event.target.value)}>{["8", "11", "17", "21"].map((level) => <option key={level} value={level}>{level}</option>)}</select></label>
          <label className="grid gap-1 text-[12px] text-slate-400">Нагрузка<input className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200" value={workload.name} onChange={(event) => onWorkload({ ...workload, name: event.target.value })} /></label>
          <label className="grid gap-1 text-[12px] text-slate-400">Размер, если известен<input className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200" type="number" min={0} value={workload.size ?? ""} onChange={(event) => onWorkload({ ...workload, size: event.target.value === "" ? undefined : Number(event.target.value) })} /></label>
          <Button className="self-end" onClick={onRun} disabled={dirty || busy || !workload.name.trim()}>{busy ? <LoaderCircle className="spin" /> : <GitCompareArrows />} {busy ? "Измеряем…" : "Запустить измерение"}</Button>
          <label className="col-span-full grid max-w-3xl gap-1 text-[12px] text-slate-400">stdin<textarea className="min-h-12 rounded border border-slate-700 bg-[#08101d] p-2 font-mono text-[12px] text-slate-200" value={workload.stdin} onChange={(event) => onWorkload({ ...workload, stdin: event.target.value })} /></label>
        </div>
      )}
      {dirty ? <p className="m-0 text-[12px] text-amber-300">Сохраните изменения перед запуском.</p> : null}
      {error ? <p className="m-0 text-[13px] text-rose-300" role="alert">{error}</p> : null}
      {report ? <BenchmarkResult report={report} /> : null}
    </section>
  );
}

function BenchmarkResult({ report }: { report: BenchmarkReport }) {
  return <section className="grid max-w-5xl gap-2 rounded border border-slate-700 bg-slate-950/70 p-3 text-[12px]" aria-label="Результат runtime benchmark">
    <strong className="text-slate-200">{report.status} · REV {report.baseline_revision} → REV {report.candidate_revision}</strong>
    <div className="grid gap-1 border-b border-slate-800 pb-2"><strong className="text-violet-200">Статический Big O</strong><span className="text-slate-400">{report.static_complexity.note}</span>{report.static_complexity.changes.map((item) => <code key={item.method_id} className="text-slate-300">{item.method_id}: {item.before} → {item.after}</code>)}</div>
    <p className={`m-0 ${report.functional.equivalent_on_all_workloads ? "text-emerald-300" : "text-rose-300"}`}>Функционально: {report.functional.equivalent_on_all_workloads ? "совпало на измеренных нагрузках" : "есть различия"} · {report.functional.note}</p>
    {report.runtime.workloads.map((item) => <article className="grid gap-1 rounded border border-slate-800 p-2" key={item.name}><strong className="text-slate-300">{item.name} · {item.stdin_bytes} байт · {item.verdict}</strong><div className="flex flex-wrap gap-x-4 gap-y-1 font-mono text-slate-400"><span>baseline median {formatMedian(item.baseline_stats?.median_ns)}</span><span>candidate median {formatMedian(item.candidate_stats?.median_ns)}</span><span>IQR {formatPercent(item.baseline_stats?.relative_spread)} / {formatPercent(item.candidate_stats?.relative_spread)}</span>{item.speedup === null ? <span>Нет подтверждённого speedup</span> : <strong className="text-emerald-300">измерено {item.speedup.toFixed(2)}×</strong>}{item.p_value === null ? null : <span>p={item.p_value.toPrecision(3)}</span>}</div><small className="text-slate-500">samples {item.baseline_stats?.iterations ?? "—"}/{item.candidate_stats?.iterations ?? "—"} · stdin SHA-256 {item.stdin_sha256}</small>{item.notes.map((note) => <small key={note} className="text-amber-300">{note}</small>)}</article>)}
    <small className="text-slate-500">JVM {String(report.environment.baseline_jvm?.version ?? "unknown")} · CPU {report.environment.cpu_limit} · память {report.environment.memory_limit}. Setup/compile: {report.timings.baseline.compile_and_setup_ms}/{report.timings.candidate.compile_and_setup_ms} ms (не время алгоритма).</small>
    {report.limitations.map((item) => <small key={item} className="text-slate-500">{item}</small>)}
  </section>;
}

function formatMedian(value: number | undefined) {
  return value === undefined ? "—" : `${(value / 1e6).toFixed(3)} ms`;
}

function formatPercent(value: number | undefined) {
  return value === undefined ? "—" : `${(value * 100).toFixed(1)}%`;
}
