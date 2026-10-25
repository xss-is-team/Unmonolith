"use client";

import { useEffect, useMemo, useState } from "react";
import { AlertCircle, Boxes, Check, LoaderCircle, Scissors } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DiffView } from "@/components/diff-view";
import {
  ApiError, applyDecomposition, getDecomposition, previewDecomposition,
  type DecompositionList, type DecompositionPreview, type DecompositionSelection,
} from "@/lib/api";

type Mode = "types" | "groups";

function message(cause: unknown) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : "Не удалось выполнить запрос.";
}

/** Splits the monolith: member types into files of their own, or groups of static methods into new classes. */
export function DecompositionPanel({ projectId, revision, sourceLevel, dirty, onApplied }: {
  projectId: string;
  revision: number;
  sourceLevel: string;
  dirty: boolean;
  onApplied: () => Promise<void>;
}) {
  const [list, setList] = useState<DecompositionList | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [mode, setMode] = useState<Mode>("types");
  const [types, setTypes] = useState<string[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [names, setNames] = useState<Record<string, string>>({});
  const [preview, setPreview] = useState<DecompositionPreview | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const result = await getDecomposition(projectId, sourceLevel);
        if (cancelled) return;
        setList(result);
        setTypes(result.candidates.filter((item) => item.extractable).map((item) => item.id));
        setGroups(result.method_groups.filter((item) => item.extractable).map((item) => item.id));
        setMode(result.candidates.some((item) => item.extractable) || !result.method_groups.some((item) => item.extractable) ? "types" : "groups");
      } catch (cause) {
        if (!cancelled) setError(message(cause));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [projectId, sourceLevel]);

  const selection = useMemo<DecompositionSelection>(() => mode === "types"
    ? { classes: types }
    : { groups: groups.map((id) => ({ id, ...(names[id] ? { name: names[id] } : {}) })) }, [mode, types, groups, names]);
  const selectedCount = mode === "types" ? types.length : groups.length;

  async function showPreview() {
    setBusy(true);
    setError("");
    setPreview(null);
    try {
      const result = await previewDecomposition(projectId, revision, selection, sourceLevel);
      if (result.base_revision !== revision) throw new ApiError("Предпросмотр относится к другой ревизии.", 409, "revision_conflict");
      setPreview(result);
    } catch (cause) {
      setError(message(cause));
    } finally {
      setBusy(false);
    }
  }

  async function apply() {
    if (!preview) return;
    setBusy(true);
    setError("");
    try {
      await applyDecomposition(projectId, revision, selection, preview.preview_sha256, sourceLevel);
      setPreview(null);
      await onApplied();
    } catch (cause) {
      setError(message(cause));
    } finally {
      setBusy(false);
    }
  }

  const toggle = (id: string, current: string[], set: (next: string[]) => void) => {
    setPreview(null);
    set(current.includes(id) ? current.filter((item) => item !== id) : [...current, id]);
  };
  const hostLines = preview ? Object.entries(preview.host_lines) : [];
  const nothing = list && !list.candidates.some((item) => item.extractable) && !list.method_groups.some((item) => item.extractable);

  return (
    <div className="grid min-h-full content-start gap-3 p-3" aria-label="Декомпозиция монолита">
      <header className="flex flex-wrap items-center gap-2 text-[14px] font-semibold text-slate-200">
        <Scissors size={16} /> Разбор монолита
        <span className="text-[12px] font-normal text-slate-500">механическое разбиение по предусловиям, результат проверяется компиляцией; поведение сверяйте во вкладке «Проверка»</span>
      </header>
      {loading ? <p className="flex items-center gap-2 text-[13px] text-slate-400" role="status"><LoaderCircle className="spin" size={14} /> Ищем, что можно вынести…</p> : null}
      {error ? <p className="m-0 flex items-center gap-2 text-[13px] text-rose-300" role="alert"><AlertCircle size={14} />{error}</p> : null}
      {dirty ? <p className="m-0 text-[12px] text-amber-300">Сначала сохраните изменения: разбиение применяется к сохранённой ревизии.</p> : null}
      {nothing ? <p className="m-0 text-[13px] text-slate-400">В этой ревизии нечего выносить: нет статических вложенных типов и групп статических методов, которые можно переместить без потери доступа.</p> : null}

      {list && !nothing ? (
        <div className="flex gap-1" role="tablist" aria-label="Что выносить">
          <button type="button" role="tab" aria-selected={mode === "types"} className={`rounded px-3 py-1.5 text-[12px] ${mode === "types" ? "bg-slate-800 text-cyan-200" : "text-slate-400 hover:bg-slate-900"}`} onClick={() => { setMode("types"); setPreview(null); }}>Вложенные типы → файлы · {list.candidates.filter((item) => item.extractable).length}/{list.candidates.length}</button>
          <button type="button" role="tab" aria-selected={mode === "groups"} className={`rounded px-3 py-1.5 text-[12px] ${mode === "groups" ? "bg-slate-800 text-cyan-200" : "text-slate-400 hover:bg-slate-900"}`} onClick={() => { setMode("groups"); setPreview(null); }}>Группы методов → классы · {list.method_groups.filter((item) => item.extractable).length}/{list.method_groups.length}</button>
        </div>
      ) : null}

      {list && mode === "types" ? (
        <div className="grid max-w-5xl gap-1.5">
          {list.candidates.map((item) => (
            <label key={item.id} className={`grid grid-cols-[auto_1fr] items-start gap-2 rounded border p-2 text-[12px] ${item.extractable ? "border-slate-800 bg-slate-900/50" : "border-slate-800/60 bg-slate-950/40 opacity-70"}`}>
              <input type="checkbox" disabled={!item.extractable} checked={types.includes(item.id)} onChange={() => toggle(item.id, types, setTypes)} />
              <span className="grid gap-0.5">
                <span className="flex flex-wrap items-center gap-2"><strong className="font-mono text-slate-200">{item.name}</strong><span className="text-slate-500">{item.kind} · {item.lines} строк → {item.new_file}</span></span>
                {item.depends_on.length ? <small className="text-slate-500">Вместе с: {item.depends_on.join(", ")}</small> : null}
                {item.not_extractable_reasons.map((reason) => <small key={reason} className="text-amber-300">{reason}</small>)}
              </span>
            </label>
          ))}
        </div>
      ) : null}

      {list && mode === "groups" ? (
        <div className="grid max-w-5xl gap-1.5">
          {list.method_groups.length === 0 ? <p className="m-0 text-[12px] text-slate-500">Групп статических методов с общим первым словом в названии нет.</p> : null}
          {list.method_groups.map((item) => (
            <div key={item.id} className={`grid grid-cols-[auto_1fr] items-start gap-2 rounded border p-2 text-[12px] ${item.extractable ? "border-slate-800 bg-slate-900/50" : "border-slate-800/60 bg-slate-950/40 opacity-70"}`}>
              <input type="checkbox" aria-label={`Вынести ${item.name}`} disabled={!item.extractable} checked={groups.includes(item.id)} onChange={() => toggle(item.id, groups, setGroups)} />
              <div className="grid gap-1">
                <div className="flex flex-wrap items-center gap-2">
                  <input className="h-7 w-44 rounded border border-slate-700 bg-[#08101d] px-2 font-mono text-[12px] text-slate-200" aria-label={`Имя класса для ${item.id}`} defaultValue={item.name} disabled={!item.extractable} onChange={(event) => { setPreview(null); setNames((current) => ({ ...current, [item.id]: event.target.value.trim() === item.name ? "" : event.target.value.trim() })); }} />
                  <span className="text-slate-500">из {item.host} · {item.lines} строк → {item.new_file}</span>
                </div>
                <small className="font-mono text-slate-400">{item.methods.map((method) => method.name).join(", ")}</small>
                {item.moves_along.length ? <small className="text-slate-500">Переезжают вместе: {item.moves_along.map((member) => member.name).join(", ")}</small> : null}
                {item.not_extractable_reasons.map((reason) => <small key={reason} className="text-amber-300">{reason}</small>)}
              </div>
            </div>
          ))}
        </div>
      ) : null}

      {list && !nothing ? (
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" variant="outline" disabled={busy || dirty || selectedCount === 0} onClick={() => void showPreview()}>{busy && !preview ? <LoaderCircle className="spin" /> : <Boxes />} Предпросмотр · {selectedCount}</Button>
          {preview ? <Button size="sm" disabled={busy || dirty} onClick={() => void apply()}>{busy ? <LoaderCircle className="spin" /> : <Check />} Применить · REV {revision + 1}</Button> : null}
          {preview ? <Button size="sm" variant="outline" disabled={busy} onClick={() => setPreview(null)}>Отмена</Button> : null}
        </div>
      ) : null}

      {preview ? (
        <section className="grid max-w-5xl gap-2" aria-label="Предпросмотр разбиения">
          <p className="m-0 text-[12px] leading-5 text-slate-300">
            Новых файлов: <strong>{preview.added_files.length}</strong> ({preview.added_files.join(", ")}). Изменено: {preview.changed_files.join(", ") || "—"}.
            {hostLines.map(([file, lines]) => <span key={file} className="ml-2 font-mono text-slate-400">{file}: {lines.before} → {lines.after} строк</span>)}
          </p>
          <p className="m-0 text-[11px] leading-4 text-slate-500">Разбиение механическое: компилируется и перепроверяется анализом, но не доказывает эквивалентность поведения. После применения проект станет многофайловым, прежние ревизии остаются доступными.</p>
          <DiffView files={preview.diff} label="Diff разбиения" />
        </section>
      ) : null}
    </div>
  );
}
