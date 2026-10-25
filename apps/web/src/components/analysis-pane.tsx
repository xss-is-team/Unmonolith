"use client";

import type { ReactNode } from "react";
import { AlertTriangle, ArrowRight, Check, Gauge, LoaderCircle, Zap } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ComplexityOverview } from "@/components/complexity-overview";
import { ruFinding } from "@/lib/ru";
import type {
  AnalysisResult, ComplexityReport, Diagnostic, FindingsReport, OptimizationProposal, SourceRange,
} from "@/lib/api";

export type Tab = "problems" | "recommendations" | "metrics" | "edit";

const HIGH = new Set(["repeated_collection_scan", "nested_collection_iteration"]);

/** «Анализ» of the mockup: Problems / Recommendations / Metrics tabs and the complexity card with the measured effect. */
export function AnalysisPane({
  loading, analysis, methodCount, findings, complexity, diagnostics, recommendations, proposals, selectedMethodId, selectedProposal,
  speedup, tab, onTab, onPortalRef, onOpenRange, onOpenMethod, onMeasure,
}: {
  loading: boolean;
  analysis: AnalysisResult | null;
  methodCount: number;
  findings: FindingsReport | null;
  complexity: ComplexityReport | null;
  diagnostics: Diagnostic[];
  recommendations: ReactNode;
  proposals: OptimizationProposal[];
  selectedMethodId: string | null;
  selectedProposal: OptimizationProposal | null;
  speedup: { value: number; note: string } | null;
  tab: Tab;
  onTab: (tab: Tab) => void;
  onPortalRef: (element: HTMLElement | null) => void;
  onOpenRange: (range: SourceRange, file: string | null) => void;
  onOpenMethod: (methodId: string, range: SourceRange, file: string | null) => void;
  onMeasure: () => void;
}) {

  const list = findings?.findings ?? [];
  const current = complexity?.methods.find((item) => item.method_id === selectedMethodId)?.time_complexity ?? null;
  const proposalBefore = selectedProposal && selectedProposal.method_id === selectedMethodId ? (selectedProposal.complexity.method_before ?? selectedProposal.complexity.scan_before) : null;
  const proposalAfter = selectedProposal && selectedProposal.method_id === selectedMethodId ? selectedProposal.complexity.scan_after : null;
  const tabs: [Tab, string, string][] = [["problems", `Проблемы ${list.length}`, `Проблемы (${list.length})`], ["recommendations", `Советы ${proposals.length}`, `Рекомендации (${proposals.length})`], ["metrics", "Метрики", "Метрики"], ["edit", "Правка", "Правка графа"]];
  return (
    <aside className="grid min-h-0 grid-rows-[auto_minmax(0,1fr)_auto] bg-[#0b1120]" aria-label="Анализ">
      <div className="grid gap-1.5 border-b border-slate-800 px-3 py-2">
        <div className="flex items-center justify-between"><h2 className="m-0 text-[14px] font-semibold text-slate-100">Анализ</h2><span className="font-mono text-[11px] text-slate-500">REV {analysis?.revision ?? "—"}</span></div>
        <div className="grid grid-cols-4 gap-0.5 rounded-md bg-slate-900 p-0.5" role="tablist" aria-label="Разделы анализа">
          {tabs.map(([id, label, full]) => (
            <button key={id} type="button" role="tab" aria-label={full} aria-selected={tab === id} className={`h-7 rounded text-[12px] ${tab === id ? "bg-cyan-500/20 text-cyan-200" : "text-slate-400 hover:text-slate-200"}`} onClick={() => onTab(id)}>{label}</button>
          ))}
        </div>
        {loading ? <p className="m-0 flex items-center gap-2 text-[12px] text-slate-400" role="status"><LoaderCircle className="spin" size={14} /> Анализ проекта…</p> : null}
        {!loading && analysis ? <p className="m-0 flex items-center gap-2 text-[12px] text-emerald-300" role="status"><Check size={14} /> Анализ завершён · {methodCount} методов · диагностик: {analysis.diagnostics.length}</p> : null}
      </div>

      <div className="min-h-0 overflow-x-hidden overflow-y-auto p-3 [&_*]:min-w-0 [&_button]:break-words [&_small]:break-words [&_strong]:break-words [&_p]:break-words">
        {tab === "problems" ? (
          <div className="grid gap-2" aria-label="Найденные проблемы">
            {list.length === 0 && !loading ? <p className="m-0 text-[13px] text-slate-500">Проблем, найденных анализатором, нет.</p> : null}
            {list.map(({ method_id, file, finding }) => {
              const high = HIGH.has(finding.id);
              return (
                <article key={`${file}-${finding.id}-${finding.source_range.start}`} className="rounded-md border border-slate-800 bg-slate-900/60 p-2.5">
                  <div className="flex items-start gap-2">
                    <AlertTriangle size={16} className={`mt-0.5 shrink-0 ${high ? "text-rose-400" : "text-amber-400"}`} />
                    <div className="grid flex-1 gap-1">
                      <div className="flex flex-wrap items-center gap-2"><strong className="text-[13px] text-slate-100">{ruFinding(finding).title}</strong>
                        <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${high ? "bg-rose-500/20 text-rose-300" : "bg-amber-500/20 text-amber-300"}`}>{high ? "Высокая" : finding.severity === "warning" ? "Средняя" : "Низкая"}</span></div>
                      <details open><summary className="cursor-pointer text-[12px] text-slate-400 hover:text-slate-200">Почему это проблема</summary><p className="m-0 mt-1 text-[12px] leading-5 text-slate-400">{ruFinding(finding).explanation}</p>{finding.recommendation ? <p className="m-0 mt-1 text-[12px] leading-5 text-emerald-300/90"><strong>Рекомендация:</strong> {ruFinding(finding).recommendation}</p> : null}</details>
                      <small className="truncate font-mono text-[11px] text-slate-500" title={method_id}>{(file ?? "").split("/").at(-1)}:{finding.source_range.line ?? "—"} · {method_id}</small>
                      <div className="flex gap-3"><button type="button" className="w-fit text-[12px] text-cyan-300 hover:text-cyan-100" onClick={() => onOpenMethod(method_id, finding.source_range, file ?? finding.source_range.file ?? null)}>Показать на графе</button><button type="button" className="w-fit text-[12px] text-slate-400 hover:text-slate-200" onClick={() => onOpenRange(finding.source_range, file ?? finding.source_range.file ?? null)}>К исходнику</button></div>
                    </div>
                  </div>
                </article>
              );
            })}
            {diagnostics.length ? (
              <details className="mt-1"><summary className="cursor-pointer text-[12px] font-semibold text-slate-400">Диагностики файла · {diagnostics.length}</summary>
                <div className="mt-1 grid gap-1">{diagnostics.map((item, index) => <button type="button" key={`${item.code}-${index}`} className="rounded border border-slate-800 bg-slate-900/60 p-2 text-left text-[12px] text-slate-300 hover:border-slate-600" onClick={() => onOpenRange(item.source_range, item.source_range.file ?? null)}><span>{item.message}</span><small className="mt-1 block font-mono text-[11px] text-slate-500">{item.code} · строка {item.source_range.line ?? "—"}</small></button>)}</div>
              </details>
            ) : null}
          </div>
        ) : null}
        {tab === "recommendations" ? <div className="grid gap-2">{recommendations}</div> : null}
        <div ref={onPortalRef} className={`peer ${tab === "edit" ? "-m-3" : "hidden"}`} aria-label="Правка графа" />
        {tab === "edit" ? <p className="m-0 mt-4 hidden text-[12px] leading-5 text-slate-500 peer-empty:block">Выберите метод во вкладке «CFG»: здесь появятся Big O метода, перенос оператора, замена условия и блоки.</p> : null}
        {tab === "metrics" ? (
          <div className="grid gap-2">
            <ComplexityOverview report={loading ? null : complexity} onOpen={(row, range) => onOpenMethod(row.method_id, range, row.file)} />
            {analysis ? <p className="m-0 text-[12px] text-slate-500">Методов: {methodCount} · диагностик: {analysis.diagnostics.length} · проблем: {list.length}</p> : null}
          </div>
        ) : null}
      </div>

      <div className="grid gap-2 border-t border-slate-800 p-3" aria-label="Сложность">
        <button type="button" className="flex items-center gap-2 bg-transparent text-left text-[13px] font-semibold text-slate-200" onClick={() => onTab("metrics")}><Gauge size={15} /> Сложность</button>
        <div className="flex flex-wrap items-center gap-2 text-[12px]">
          <span className="text-slate-500">Текущая:</span>
          <code className="rounded bg-rose-500/15 px-1.5 py-0.5 font-mono text-rose-300">{current ?? "—"}</code>
          {proposalAfter ? <><ArrowRight size={14} className="text-slate-600" /><span className="text-slate-500">После оптимизации:</span><code className="rounded bg-emerald-500/15 px-1.5 py-0.5 font-mono text-emerald-300">{proposalAfter}</code></> : null}
        </div>
        {proposalBefore && proposalAfter ? <small className="text-[11px] leading-4 text-slate-500">Оценка заменяемого перебора: {proposalBefore} → {proposalAfter}; границу всего метода даёт анализатор после применения.</small> : null}
        <div className="flex items-center justify-between rounded-md border border-slate-800 bg-slate-900/50 p-2.5 text-[12px]">
          <div className="grid gap-0.5"><span className="flex items-center gap-1.5 text-slate-300"><Zap size={14} /> Оценка производительности</span><small className="text-slate-500">{speedup ? speedup.note : "Измеренное ускорение появится после замера (вкладка «Бенчмарк»)."}</small></div>
          {speedup ? <span className="rounded bg-emerald-500/20 px-2 py-1 font-mono text-[13px] font-semibold text-emerald-300">до {speedup.value.toFixed(1)}×</span> : <Button size="sm" variant="outline" onClick={onMeasure}>Измерить</Button>}
        </div>
      </div>
    </aside>
  );
}
