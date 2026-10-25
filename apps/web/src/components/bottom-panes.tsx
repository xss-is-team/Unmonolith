"use client";

import { CheckCircle2, CircleAlert, CircleHelp, Play, Terminal } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { VerificationReport } from "@/lib/api";
import { ruWarning } from "@/lib/ru";

export type LogLine = { time: string; kind: "info" | "ok" | "error"; text: string };

export function logTime() {
  return new Date().toLocaleTimeString("ru-RU", { hour12: false });
}

/** Console of the session: what the workspace did and what the real runner answered. */
export function ConsolePane({ lines }: { lines: LogLine[] }) {
  return (
    <section className="grid min-h-0 grid-rows-[34px_minmax(0,1fr)] border-l border-slate-800 bg-[#0a1220]" aria-label="Консоль">
      <header className="flex items-center gap-2 border-b border-slate-800 px-3 text-[12px] font-semibold text-slate-300"><Terminal size={14} /> Консоль</header>
      <div className="min-h-0 overflow-auto p-2 font-mono text-[12px] leading-5">
        {lines.length === 0 ? <p className="m-0 text-slate-600">Пока ничего не запускалось.</p> : null}
        {lines.map((line, index) => (
          <p key={index} className={`m-0 ${line.kind === "ok" ? "text-emerald-300" : line.kind === "error" ? "text-rose-300" : "text-slate-400"}`}>
            <span className="text-slate-600">[{line.time}]</span> {line.text}
          </p>
        ))}
      </div>
    </section>
  );
}

/** Per-scenario results of the latest verification (real runner), as in the mockup's «Результаты тестов». */
export function TestsPane({ report, busy, onRun }: { report: VerificationReport | null; busy: boolean; onRun: () => void }) {
  const total = report?.test_cases.length ?? 0;
  const passed = report?.test_cases.filter((item) => item.passed).length ?? 0;
  const verdict = !report ? null : report.behaviorally_verified ? "ok" : report.status === "unstable" ? "unstable" : "fail";
  return (
    <section className="grid min-h-0 grid-rows-[34px_minmax(0,1fr)] border-l border-slate-800 bg-[#0a1220]" aria-label="Результаты тестов">
      <header className="flex items-center gap-2 border-b border-slate-800 px-3 text-[12px] font-semibold text-slate-300"><CheckCircle2 size={14} /> Результаты тестов</header>
      <div className="min-h-0 overflow-auto p-3 text-[12px]">
        {!report ? (
          <div className="grid justify-items-start gap-2 text-slate-500">
            <p className="m-0 flex items-center gap-2"><CircleHelp size={16} /> Проверка поведения ещё не запускалась.</p>
            <Button size="sm" variant="outline" disabled={busy} onClick={onRun}><Play /> Открыть проверку</Button>
          </div>
        ) : (
          <div className="grid gap-2">
            <div className="flex items-center gap-2">
              {verdict === "ok" ? <CheckCircle2 className="text-emerald-400" size={22} /> : <CircleAlert className={verdict === "unstable" ? "text-amber-400" : "text-rose-400"} size={22} />}
              <div>
                <strong className={`block text-[14px] ${verdict === "ok" ? "text-emerald-300" : verdict === "unstable" ? "text-amber-300" : "text-rose-300"}`}>
                  {verdict === "ok" ? "Все тесты пройдены" : verdict === "unstable" ? "Результат неопределён" : report.status === "compilation_failed" ? "Компиляция не прошла" : "Есть различия"}
                </strong>
                <small className="text-slate-500">{passed} из {total} · REV {report.baseline_revision} → REV {report.candidate_revision}</small>
              </div>
            </div>
            <div className="h-1.5 overflow-hidden rounded bg-slate-800"><div className={`h-full ${verdict === "ok" ? "bg-emerald-500" : "bg-rose-500"}`} style={{ width: `${total ? (passed / total) * 100 : 0}%` }} /></div>
            <table className="w-full border-collapse text-left">
              <thead><tr className="text-slate-500"><th className="py-1 font-medium">Тест</th><th className="py-1 font-medium">Результат</th><th className="py-1 font-medium">Запусков</th></tr></thead>
              <tbody>
                {report.test_cases.map((item) => (
                  <tr key={item.name} className="border-t border-slate-800">
                    <td className="py-1 pr-2 font-mono text-slate-300">{item.name}</td>
                    <td className={`py-1 pr-2 ${item.passed ? "text-emerald-300" : "text-rose-300"}`}>{item.passed ? "✓ Успешно" : item.mismatches.some((m) => m.field === "unstable") ? "~ Нестабильно" : "✕ Различие"}</td>
                    <td className="py-1 text-slate-400">{item.runs ?? 1}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {report.warnings?.length ? <p className="m-0 text-amber-300">Предупреждения: {report.warnings.map((w) => ruWarning(w)).join(" ")}</p> : null}
          </div>
        )}
      </div>
    </section>
  );
}
