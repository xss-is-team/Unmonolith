import type { FileDiff } from "@/lib/api";

export function DiffView({ files, label = "Изменения исходника" }: { files: FileDiff[]; label?: string }) {
  if (!files.length) return <p className="text-[13px] text-slate-500">Изменений в файлах нет.</p>;
  return (
    <section className="grid min-w-0 gap-2" aria-label={label}>
      {files.map((file) => (
        <details className="min-w-0 overflow-hidden rounded border border-slate-700 bg-[#08101d]" key={file.path} open>
          <summary className="flex cursor-pointer items-center gap-2 border-b border-slate-800 px-2.5 py-2 text-[13px] text-slate-300">
            <code className="min-w-0 flex-1 truncate">{file.path}</code>
            <span className="shrink-0 font-mono text-[11px] text-emerald-400">+{file.additions}</span>
            <span className="shrink-0 font-mono text-[11px] text-rose-400">−{file.deletions}</span>
          </summary>
          <pre className="m-0 max-h-80 overflow-auto p-2 font-mono text-[12px] leading-5">
            {file.unified_diff.split("\n").map((line, index) => {
              const style = line.startsWith("+") && !line.startsWith("+++")
                ? "bg-emerald-950/40 text-emerald-300"
                : line.startsWith("-") && !line.startsWith("---")
                  ? "bg-rose-950/40 text-rose-300"
                  : line.startsWith("@@")
                    ? "text-cyan-300"
                    : "text-slate-500";
              return <span className={`block min-w-max px-1 ${style}`} key={`${index}-${line}`}>{line || " "}</span>;
            })}
          </pre>
        </details>
      ))}
    </section>
  );
}
