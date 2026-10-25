"use client";

import type { ComplexityReport, SourceRange } from "@/lib/api";

type Row = ComplexityReport["methods"][number];

/** Orders static estimates: unknown < O(1) < O(log n) < O(n) < O(n log n) < O(n^2) < O(n^3)... */
export function complexityRank(value: string): number {
  const text = value.replace(/\s/g, "").toLowerCase();
  if (!text.startsWith("o(") || text.includes("unknown")) return -1;
  if (text === "o(1)") return 0;
  if (text.includes("log") && !text.includes("n*") && !text.includes("^")) return text.includes("nlog") ? 3 : 1;
  const power = /\^(\d+)/.exec(text);
  if (power) return 2 + Number(power[1]);
  const variables = text.slice(2, -1).split("+").map((term) => term.split("*").length);
  return 2 + Math.max(...variables) - 1;
}

function tone(rank: number) {
  if (rank < 0) return "border-slate-700 bg-slate-800/60 text-slate-400";
  if (rank <= 2) return "border-emerald-900 bg-emerald-950/40 text-emerald-300";
  if (rank === 3) return "border-yellow-900 bg-yellow-950/30 text-yellow-300";
  if (rank === 4) return "border-orange-900 bg-orange-950/40 text-orange-300";
  return "border-rose-900 bg-rose-950/40 text-rose-300";
}

/** Where the project spends its asymptotic budget: counts per class and the heaviest methods, each a link to the code. */
export function ComplexityOverview({ report, onOpen }: { report: ComplexityReport | null; onOpen: (row: Row, range: SourceRange) => void }) {
  if (!report || report.methods.length === 0) return null;
  const rows = report.methods.map((row) => ({ row, rank: complexityRank(row.time_complexity) }));
  const buckets = [
    { label: "O(1)–O(n)", test: (rank: number) => rank >= 0 && rank <= 2 },
    { label: "O(n log n)", test: (rank: number) => rank === 3 },
    { label: "O(n²)", test: (rank: number) => rank === 4 },
    { label: "O(n³)+", test: (rank: number) => rank >= 5 },
    { label: "не определена", test: (rank: number) => rank < 0 },
  ];
  const heaviest = rows.filter((item) => item.rank >= 3).sort((a, b) => b.rank - a.rank).slice(0, 6);
  return (
    <section className="mb-3 grid gap-2 rounded border border-slate-800 bg-slate-900/40 p-2" aria-label="Сложность проекта">
      <h2 className="m-0 text-[12px] font-semibold text-slate-400">Сложность по Big O · {rows.length} методов</h2>
      <div className="grid grid-cols-5 gap-1 text-center">
        {buckets.map((bucket) => {
          const count = rows.filter((item) => bucket.test(item.rank)).length;
          const sample = rows.find((item) => bucket.test(item.rank));
          return (
            <div key={bucket.label} className={`rounded border px-1 py-1.5 ${tone(sample ? sample.rank : -1)} ${count === 0 ? "opacity-40" : ""}`}>
              <strong className="block font-mono text-[15px] leading-5">{count}</strong>
              <small className="block text-[10px] leading-3">{bucket.label}</small>
            </div>
          );
        })}
      </div>
      {heaviest.length ? (
        <div className="grid gap-1">
          {heaviest.map(({ row, rank }) => (
            <button key={`${row.file}-${row.method_id}`} type="button" title={row.reason} className="flex min-w-0 items-center gap-2 rounded border border-slate-800 bg-slate-950/50 px-2 py-1 text-left hover:border-slate-600" onClick={() => onOpen(row, row.source_range)}>
              <span className={`shrink-0 rounded border px-1.5 py-0.5 font-mono text-[11px] ${tone(rank)}`}>{row.time_complexity}</span>
              <span className="min-w-0 flex-1 truncate font-mono text-[11px] text-slate-300">{row.method_id}</span>
              <small className="shrink-0 text-[10px] text-slate-500">{row.confidence}</small>
            </button>
          ))}
        </div>
      ) : <p className="m-0 text-[12px] text-slate-500">Методов хуже O(n) log n не найдено среди оценённых.</p>}
      <small className="text-[10px] leading-4 text-slate-600">Статическая оценка по тексту кода, не измерение времени; «не определена» — модель стоимости не знает вызываемый код.</small>
    </section>
  );
}
