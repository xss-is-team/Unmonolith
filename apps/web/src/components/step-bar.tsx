"use client";

import { Check } from "lucide-react";

export type Step = {
  id: string;
  label: string;
  hint: string;
  state: "done" | "next" | "todo" | "warn";
  onClick: () => void;
};

/** The path the case describes, always visible: what is done and what to do next. */
export function StepBar({ steps }: { steps: Step[] }) {
  return (
    <nav className="flex min-w-0 items-center gap-1 overflow-x-auto border-b border-slate-800 bg-[#0a101c] px-3" aria-label="Ход работы">
      {steps.map((step, index) => (
        <div key={step.id} className="flex shrink-0 items-center gap-1">
          {index > 0 ? <span className="px-1 text-slate-700">›</span> : null}
          <button
            type="button"
            title={step.hint}
            className={`flex h-7 items-center gap-1.5 rounded-full border px-3 text-[12px] ${
              step.state === "done" ? "border-emerald-800 bg-emerald-950/40 text-emerald-300"
              : step.state === "next" ? "border-cyan-600 bg-cyan-500/15 text-cyan-200"
              : step.state === "warn" ? "border-amber-700 bg-amber-950/40 text-amber-300"
              : "border-slate-800 bg-transparent text-slate-500 hover:text-slate-300"}`}
            onClick={step.onClick}
          >
            <span className={`grid size-4 place-items-center rounded-full text-[10px] font-semibold ${step.state === "done" ? "bg-emerald-500 text-slate-950" : step.state === "next" ? "bg-cyan-400 text-slate-950" : "bg-slate-800 text-slate-400"}`}>
              {step.state === "done" ? <Check size={11} /> : index + 1}
            </span>
            {step.label}
          </button>
        </div>
      ))}
    </nav>
  );
}
