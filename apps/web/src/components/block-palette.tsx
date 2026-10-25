"use client";

import { useState } from "react";
import { Braces, CornerDownLeft, GitBranch, Repeat, Search, Sigma, SquareFunction } from "lucide-react";

export type BlockTemplate = { id: string; label: string; code: string; nonce: number };

const ITEMS = [
  { id: "variable", label: "Переменная", code: "int value = 0;", icon: Braces, tone: "text-sky-300 bg-sky-500/15" },
  { id: "condition", label: "Условие", code: "if (true) {\n    \n}", icon: GitBranch, tone: "text-amber-300 bg-amber-500/15" },
  { id: "loop", label: "Цикл", code: "for (int i = 0; i < 10; i++) {\n    \n}", icon: Repeat, tone: "text-emerald-300 bg-emerald-500/15" },
  { id: "method", label: "Метод", code: 'System.out.println("text");', icon: SquareFunction, tone: "text-blue-300 bg-blue-500/15" },
  { id: "operation", label: "Операция", code: "value += 1;", icon: Sigma, tone: "text-lime-300 bg-lime-500/15" },
  { id: "return", label: "Возврат", code: "return;", icon: CornerDownLeft, tone: "text-rose-300 bg-rose-500/15" },
] as const;

/** Block palette of the case mockup: a click puts the block's Java template into the block editor of the CFG view. */
export function BlockPalette({ onPick }: { onPick: (template: BlockTemplate) => void }) {
  const [query, setQuery] = useState("");
  const shown = ITEMS.filter((item) => item.label.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <details className="grid gap-2 border-t border-slate-800 p-2.5" aria-label="Палитра блоков">
      <summary className="cursor-pointer text-[13px] font-semibold text-slate-200">Блоки <span className="font-normal text-slate-500">· шаблоны для вставки в граф</span></summary>
      <label className="mt-2 flex h-8 items-center gap-2 rounded border border-slate-700 bg-[#08101d] px-2 text-slate-500">
        <Search size={14} />
        <input className="min-w-0 flex-1 bg-transparent text-[12px] text-slate-200 outline-none placeholder:text-slate-600" placeholder="Поиск блоков…" aria-label="Поиск блоков" value={query} onChange={(event) => setQuery(event.target.value)} />
      </label>
      <div className="mt-2 grid grid-cols-2 gap-1.5">
        {shown.map((item) => {
          const Icon = item.icon;
          return (
            <button key={item.id} type="button" className="flex h-8 items-center gap-2 rounded border border-slate-800 bg-slate-900/70 px-2 text-left text-[12px] text-slate-200 hover:border-cyan-700" title="Поместить шаблон в редактор блоков (вкладка CFG)" onClick={() => onPick({ id: item.id, label: item.label, code: item.code, nonce: Date.now() })}>
              <span className={`grid size-6 place-items-center rounded ${item.tone}`}><Icon size={14} /></span>
              {item.label}
            </button>
          );
        })}
        {shown.length === 0 ? <p className="m-0 text-[12px] text-slate-500">Нет подходящих блоков.</p> : null}
      </div>
    </details>
  );
}
