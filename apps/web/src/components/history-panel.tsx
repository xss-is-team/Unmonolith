"use client";

import { useEffect, useState } from "react";
import { AlertCircle, History, LoaderCircle, RotateCcw, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DiffView } from "@/components/diff-view";
import { ApiError, compareRevisions, getProjectRevisions, rollbackProject, type FileDiff, type RevisionSummary } from "@/lib/api";

type Row = RevisionSummary & { files: FileDiff[] | null };

function message(cause: unknown) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : "Не удалось выполнить запрос.";
}

/** Revision history: what each revision changed, compare it, roll back to it (a rollback is a new revision) or verify it. */
export function HistoryPanel({ projectId, revision, dirty, onRolledBack, onVerify }: {
  projectId: string;
  revision: number;
  dirty: boolean;
  onRolledBack: () => Promise<void>;
  onVerify: (baseline: number, candidate: number) => void;
}) {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [error, setError] = useState("");
  const [open, setOpen] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await getProjectRevisions(projectId);
        const withDiffs: Row[] = await Promise.all(list.map(async (item) => {
          if (item.revision === 1) return { ...item, files: null };
          try {
            return { ...item, files: (await compareRevisions(projectId, item.revision - 1, item.revision)).files };
          } catch {
            return { ...item, files: null };
          }
        }));
        if (!cancelled) setRows(withDiffs.sort((a, b) => b.revision - a.revision));
      } catch (cause) {
        if (!cancelled) setError(message(cause));
      }
    })();
    return () => { cancelled = true; };
  }, [projectId, revision]);

  async function rollback(to: number) {
    setBusy(true);
    setError("");
    try {
      await rollbackProject(projectId, revision, to);
      await onRolledBack();
    } catch (cause) {
      setError(message(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid max-w-4xl content-start gap-3 p-4" aria-label="История ревизий">
      <header className="flex items-center gap-2 text-[14px] font-semibold text-slate-200"><History size={16} /> История ревизий <span className="text-[12px] font-normal text-slate-500">откат создаёт новую ревизию, старые остаются</span></header>
      {dirty ? <p className="m-0 text-[12px] text-amber-300">Сначала сохраните изменения: откат применяется к сохранённой ревизии.</p> : null}
      {error ? <p className="m-0 flex items-center gap-2 text-[13px] text-rose-300" role="alert"><AlertCircle size={14} />{error}</p> : null}
      {!rows && !error ? <p className="m-0 flex items-center gap-2 text-[13px] text-slate-400" role="status"><LoaderCircle className="spin" size={14} /> Загружаем историю…</p> : null}
      {rows?.map((row) => {
        const added = row.files?.reduce((sum, file) => sum + file.additions, 0) ?? 0;
        const removed = row.files?.reduce((sum, file) => sum + file.deletions, 0) ?? 0;
        const current = row.revision === revision;
        return (
          <article key={row.revision} className={`grid gap-2 rounded-md border p-3 ${current ? "border-cyan-800 bg-cyan-950/20" : "border-slate-800 bg-slate-900/50"}`}>
            <div className="flex flex-wrap items-center gap-2 text-[13px]">
              <strong className="font-mono text-slate-100">REV {row.revision}</strong>
              {current ? <span className="rounded bg-cyan-500/20 px-1.5 py-0.5 text-[10px] font-semibold text-cyan-300">текущая</span> : null}
              <span className="text-slate-400">{row.revision === 1 ? "исходная загрузка" : row.files ? `${row.files.length} файл(ов): ${row.files.map((file) => file.path.split("/").at(-1)).slice(0, 3).join(", ")}${row.files.length > 3 ? "…" : ""}` : "изменения недоступны"}</span>
              {row.files ? <span className="font-mono text-[12px]"><span className="text-emerald-400">+{added}</span> <span className="text-rose-400">−{removed}</span></span> : null}
              <span className="ml-auto flex flex-wrap gap-1.5">
                {row.files ? <Button size="sm" variant="outline" onClick={() => setOpen(open === row.revision ? null : row.revision)}>{open === row.revision ? "Скрыть diff" : "Показать diff"}</Button> : null}
                {!current ? <Button size="sm" variant="outline" onClick={() => onVerify(row.revision, revision)}><ShieldCheck /> Проверить против текущей</Button> : null}
                {!current ? <Button size="sm" variant="outline" disabled={busy || dirty} onClick={() => void rollback(row.revision)}>{busy ? <LoaderCircle className="spin" /> : <RotateCcw />} Откатить сюда</Button> : null}
              </span>
            </div>
            {open === row.revision && row.files ? <DiffView files={row.files} label={`Изменения REV ${row.revision - 1} → REV ${row.revision}`} /> : null}
          </article>
        );
      })}
    </div>
  );
}
