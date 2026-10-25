"use client";

import { useState } from "react";
import { AlertCircle, Check, LoaderCircle, Plus, Scissors, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DiffView } from "@/components/diff-view";
import {
  ApiError, applyCfgEdit, previewCfgEdit,
  type CfgEditOperation, type CfgEditPreview,
} from "@/lib/api";

/** Block palette of the case mockup (slide 4): each entry is Java text that can be edited before it is inserted. */
const PALETTE = [
  { id: "variable", label: "Переменная", code: "int value = 0;" },
  { id: "condition", label: "Условие", code: "if (true) {\n    \n}" },
  { id: "loop", label: "Цикл", code: "for (int i = 0; i < 10; i++) {\n    \n}" },
  { id: "call", label: "Вызов", code: 'System.out.println("text");' },
  { id: "operation", label: "Операция", code: "value += 1;" },
  { id: "return", label: "Return", code: "return;" },
] as const;

function message(cause: unknown) {
  if (cause instanceof ApiError) {
    if (cause.code === "transformation_invalid") return `Результат не компилируется или не проходит анализ: ${cause.message}`;
    if (cause.code === "invalid_operation") return `Код не является корректной последовательностью операторов Java. ${cause.message}`;
    return cause.message;
  }
  return cause instanceof Error ? cause.message : "Не удалось выполнить запрос.";
}

/** Insert a block next to the selected statement or delete the selected statement; every edit is previewed and validated. */
export function BlockEditor({ projectId, revision, methodId, nodeId, nodeLabel, statements, onApplied }: {
  projectId: string;
  revision: number;
  methodId: string;
  nodeId: string;
  nodeLabel: string;
  statements: { id: string; text: string }[];
  onApplied: () => Promise<void>;
}) {
  const [code, setCode] = useState<string>(PALETTE[0].code);
  const [position, setPosition] = useState<"before" | "after">("after");
  const [operation, setOperation] = useState<CfgEditOperation | null>(null);
  const [preview, setPreview] = useState<CfgEditPreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [firstId, setFirstId] = useState("");
  const [lastId, setLastId] = useState("");
  const [methodName, setMethodName] = useState("extracted");

  async function request(next: CfgEditOperation) {
    setBusy(true);
    setError("");
    setPreview(null);
    setOperation(next);
    try {
      setPreview(await previewCfgEdit(projectId, revision, methodId, next));
    } catch (cause) {
      setError(message(cause));
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (!preview || !operation) return;
    setBusy(true);
    setError("");
    try {
      await applyCfgEdit(projectId, revision, methodId, operation, preview.preview_sha256);
      setPreview(null);
      setOperation(null);
      await onApplied();
    } catch (cause) {
      setError(message(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="grid gap-2 border-t border-slate-800 bg-[#0d1625] px-3 py-2.5" aria-label="Добавление и удаление блоков">
      <div className="flex flex-wrap items-center gap-2">
        <strong className="flex items-center gap-1.5 text-[13px] text-slate-300"><Plus size={15} /> Блоки</strong>
        <span className="text-[12px] text-slate-500">Выберите оператор на графе, затем вставьте рядом новый блок или удалите выбранный. Результат проверяется компиляцией; поведение сверяйте во вкладке «Проверка».</span>
      </div>
      <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Палитра блоков">
        {PALETTE.map((item) => (
          <button key={item.id} type="button" className="rounded border border-slate-700 bg-slate-900 px-2 py-1 text-[12px] text-slate-300 hover:border-cyan-700" onClick={() => { setCode(item.code); setPreview(null); }}>{item.label}</button>
        ))}
      </div>
      <div className="grid grid-cols-[1fr_auto] gap-2">
        <textarea className="min-h-16 rounded border border-slate-700 bg-[#08101d] p-2 font-mono text-[12px] text-slate-200" aria-label="Java-код блока" value={code} onChange={(event) => { setCode(event.target.value); setPreview(null); }} />
        <div className="grid content-start gap-1.5 text-[12px]">
          <label className="text-slate-400">Куда
            <select aria-label="Позиция вставки" className="ml-1 h-8 rounded border border-slate-700 bg-[#08101d] px-2 text-slate-200" value={position} onChange={(event) => { setPosition(event.target.value as "before" | "after"); setPreview(null); }}>
              <option value="before">Перед выбранным</option>
              <option value="after">После выбранного</option>
            </select>
          </label>
          <Button size="sm" variant="outline" disabled={!nodeId || !code.trim() || busy} onClick={() => void request({ type: "insert_statement", target_node_id: nodeId, position, code })}>{busy ? <LoaderCircle className="spin" /> : <Plus />} Показать вставку</Button>
          <Button size="sm" variant="outline" disabled={!nodeId || busy} onClick={() => void request({ type: "delete_statement", node_id: nodeId })}><Trash2 /> Показать удаление</Button>
        </div>
      </div>
      <small className="font-mono text-slate-500">Выбран: {nodeId ? `${nodeId} · ${nodeLabel}` : "нажмите на оператор в графе"}</small>
      <div className="flex flex-wrap items-end gap-2 border-t border-slate-800 pt-2 text-[12px] text-slate-400" aria-label="Выделение метода">
        <strong className="flex items-center gap-1.5 text-[13px] text-slate-300"><Scissors size={15} /> Выделить метод</strong>
        <label className="grid gap-0.5">С оператора
          <select aria-label="Первый оператор выделения" className="h-8 max-w-[240px] rounded border border-slate-700 bg-[#08101d] px-2 text-slate-200" value={firstId} onChange={(event) => { setFirstId(event.target.value); setPreview(null); }}>
            <option value="">—</option>
            {statements.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.text}</option>)}
          </select>
        </label>
        <label className="grid gap-0.5">По оператор
          <select aria-label="Последний оператор выделения" className="h-8 max-w-[240px] rounded border border-slate-700 bg-[#08101d] px-2 text-slate-200" value={lastId} onChange={(event) => { setLastId(event.target.value); setPreview(null); }}>
            <option value="">—</option>
            {statements.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.text}</option>)}
          </select>
        </label>
        <label className="grid gap-0.5">Имя метода
          <input aria-label="Имя нового метода" className="h-8 w-36 rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-slate-200" value={methodName} onChange={(event) => { setMethodName(event.target.value); setPreview(null); }} />
        </label>
        <Button size="sm" variant="outline" disabled={!firstId || !lastId || !methodName.trim() || busy} onClick={() => void request({ type: "extract_method", first_node_id: firstId, last_node_id: lastId, name: methodName.trim() })}>{busy ? <LoaderCircle className="spin" /> : <Scissors />} Показать извлечение</Button>
      </div>
      {error ? <p className="m-0 flex items-center gap-1.5 text-[12px] text-rose-300" role="alert"><AlertCircle size={14} />{error}</p> : null}
      {preview ? (
        <div className="grid gap-2" aria-label="Предпросмотр правки блока">
          <DiffView files={preview.diff} label="Diff правки блока" />
          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={() => void confirm()} disabled={busy}>{busy ? <LoaderCircle className="spin" /> : <Check />} Применить · REV {revision + 1}</Button>
            <Button variant="outline" onClick={() => { setPreview(null); setOperation(null); }} disabled={busy}>Отмена</Button>
          </div>
        </div>
      ) : null}
    </section>
  );
}
