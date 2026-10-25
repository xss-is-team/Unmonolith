"use client";

import { useState } from "react";
import { Braces, CornerDownLeft, GitBranch, Plus, Repeat, Search, Sigma, SquareFunction, Star, Trash2 } from "lucide-react";

export type BlockTemplate = { id: string; label: string; code: string; nonce: number; position?: "before" | "after" };

export const BLOCK_MIME = "application/x-java-block";

const ITEMS = [
  { id: "variable", label: "Переменная", code: "int value = 0;", icon: Braces, tone: "text-sky-300 bg-sky-500/15" },
  { id: "condition", label: "Условие", code: "if (true) {\n    \n}", icon: GitBranch, tone: "text-amber-300 bg-amber-500/15" },
  { id: "loop", label: "Цикл", code: "for (int i = 0; i < 10; i++) {\n    \n}", icon: Repeat, tone: "text-emerald-300 bg-emerald-500/15" },
  { id: "method", label: "Метод", code: 'System.out.println("text");', icon: SquareFunction, tone: "text-blue-300 bg-blue-500/15" },
  { id: "operation", label: "Операция", code: "value += 1;", icon: Sigma, tone: "text-lime-300 bg-lime-500/15" },
  { id: "return", label: "Возврат", code: "return;", icon: CornerDownLeft, tone: "text-rose-300 bg-rose-500/15" },
] as const;

type Custom = { id: string; label: string; code: string };
const STORE = "blueprints.custom-blocks";

function loadCustom(): Custom[] {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(STORE) ?? "[]");
    return Array.isArray(parsed) ? parsed.filter((item) => item && typeof item.label === "string" && typeof item.code === "string") : [];
  } catch {
    return [];
  }
}

function saveCustom(items: Custom[]) {
  try { window.localStorage.setItem(STORE, JSON.stringify(items)); } catch { /* storage may be blocked */ }
}

/**
 * Block palette of the case mockup. A block can be dragged onto a statement of the graph (it is inserted before or after it,
 * after a preview) or clicked (its Java goes into the block editor). Own blocks are kept in this browser.
 */
export function BlockPalette({ onPick }: { onPick: (template: BlockTemplate) => void }) {
  const [query, setQuery] = useState("");
  const [custom, setCustom] = useState<Custom[]>(() => (typeof window === "undefined" ? [] : loadCustom()));
  const [adding, setAdding] = useState(false);
  const [label, setLabel] = useState("");
  const [code, setCode] = useState("");
  const all = [
    ...ITEMS.map((item) => ({ ...item, own: false as boolean })),
    ...custom.map((item) => ({ ...item, icon: Star, tone: "text-fuchsia-300 bg-fuchsia-500/15", own: true as boolean })),
  ];
  const shown = all.filter((item) => item.label.toLowerCase().includes(query.trim().toLowerCase()));

  function addCustom() {
    if (!label.trim() || !code.trim()) return;
    const next = [...custom, { id: `own-${Date.now()}`, label: label.trim(), code }];
    setCustom(next);
    saveCustom(next);
    setLabel("");
    setCode("");
    setAdding(false);
  }

  function removeCustom(id: string) {
    const next = custom.filter((item) => item.id !== id);
    setCustom(next);
    saveCustom(next);
  }

  return (
    <details className="grid gap-2 border-t border-slate-800 p-2.5" aria-label="Палитра блоков" open>
      <summary className="cursor-pointer text-[13px] font-semibold text-slate-200">Блоки <span className="font-normal text-slate-500">· перетащите на оператор графа</span></summary>
      <label className="mt-2 flex h-8 items-center gap-2 rounded border border-slate-700 bg-[#08101d] px-2 text-slate-500">
        <Search size={14} />
        <input className="min-w-0 flex-1 bg-transparent text-[12px] text-slate-200 outline-none placeholder:text-slate-600" placeholder="Поиск блоков…" aria-label="Поиск блоков" value={query} onChange={(event) => setQuery(event.target.value)} />
      </label>
      <div className="mt-2 grid grid-cols-2 gap-1.5">
        {shown.map((item) => {
          const Icon = item.icon;
          return (
            <div key={item.id} className="group relative">
              <button
                type="button"
                draggable
                className="flex h-8 w-full cursor-grab items-center gap-2 rounded border border-slate-800 bg-slate-900/70 px-2 text-left text-[12px] text-slate-200 hover:border-cyan-700 active:cursor-grabbing"
                title="Перетащите на оператор графа, чтобы вставить; клик кладёт Java в редактор блоков"
                onDragStart={(event) => {
                  event.dataTransfer.setData(BLOCK_MIME, JSON.stringify({ id: item.id, label: item.label, code: item.code }));
                  event.dataTransfer.setData("text/plain", item.code);
                  event.dataTransfer.effectAllowed = "copy";
                }}
                onClick={() => onPick({ id: item.id, label: item.label, code: item.code, nonce: Date.now() })}
              >
                <span className={`grid size-6 shrink-0 place-items-center rounded ${item.tone}`}><Icon size={14} /></span>
                <span className="truncate">{item.label}</span>
              </button>
              {item.own ? <button type="button" aria-label={`Удалить блок ${item.label}`} className="absolute top-1 right-1 hidden size-6 place-items-center rounded text-slate-500 hover:text-rose-300 group-hover:grid" onClick={() => removeCustom(item.id)}><Trash2 size={13} /></button> : null}
            </div>
          );
        })}
        {shown.length === 0 ? <p className="m-0 text-[12px] text-slate-500">Нет подходящих блоков.</p> : null}
      </div>
      {adding ? (
        <div className="grid gap-1.5 rounded border border-slate-700 bg-slate-950/60 p-2">
          <input className="h-8 rounded border border-slate-700 bg-[#08101d] px-2 text-[12px] text-slate-200 outline-none focus:border-cyan-500" aria-label="Название своего блока" placeholder="Название блока" value={label} onChange={(event) => setLabel(event.target.value)} />
          <textarea className="min-h-20 rounded border border-slate-700 bg-[#08101d] p-2 font-mono text-[12px] text-slate-200 outline-none focus:border-cyan-500" aria-label="Java-код своего блока" placeholder={"Java-операторы, например:\nSystem.out.println(\"debug\");"} value={code} onChange={(event) => setCode(event.target.value)} />
          <div className="flex gap-2"><button type="button" className="h-8 flex-1 rounded bg-cyan-600 text-[12px] font-semibold text-white disabled:opacity-40" disabled={!label.trim() || !code.trim()} onClick={addCustom}>Сохранить блок</button><button type="button" className="h-8 rounded border border-slate-700 px-3 text-[12px] text-slate-300" onClick={() => setAdding(false)}>Отмена</button></div>
        </div>
      ) : (
        <button type="button" className="flex h-8 items-center justify-center gap-1.5 rounded border border-dashed border-slate-600 text-[12px] text-slate-300 hover:border-cyan-600 hover:text-cyan-200" onClick={() => setAdding(true)}><Plus size={14} /> Свой блок</button>
      )}
    </details>
  );
}
