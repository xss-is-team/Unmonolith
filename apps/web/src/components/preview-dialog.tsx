"use client";

import { X } from "lucide-react";
import type { ReactNode } from "react";

/** A wide window for what must be read in full: diffs and the confirmation of an edit. */
export function PreviewDialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="fixed inset-0 z-40 grid place-items-center bg-black/65 p-4 sm:p-8" role="dialog" aria-modal="true" aria-label={title} onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div className="grid max-h-[90vh] w-full max-w-6xl grid-rows-[auto_minmax(0,1fr)] overflow-hidden rounded-lg border border-slate-600 bg-[#0b1220] shadow-2xl">
        <header className="flex items-center gap-3 border-b border-slate-700 bg-[#0d1625] px-4 py-2.5">
          <strong className="text-[14px] text-slate-100">{title}</strong>
          <button type="button" className="ml-auto rounded p-1 text-slate-400 hover:bg-slate-800 hover:text-slate-100" aria-label="Закрыть окно" onClick={onClose}><X size={18} /></button>
        </header>
        <div className="min-h-0 overflow-auto p-4 [&_pre]:max-h-[52vh] [&_pre]:text-[13px]">{children}</div>
      </div>
    </div>
  );
}
