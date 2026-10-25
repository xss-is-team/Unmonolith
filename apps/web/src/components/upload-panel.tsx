"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowUpRight, FileCode2, FileUp, LoaderCircle, LockKeyhole } from "lucide-react";
import { ApiError, MAX_SOURCE_BYTES, uploadProjectFile } from "@/lib/api";

const MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;

export function UploadPanel() {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function openFile(file?: File) {
    if (!file) return;
    setError("");
    const javaFile = file.name.toLowerCase().endsWith(".java");
    const archiveFile = file.name.toLowerCase().endsWith(".zip");
    if (!javaFile && !archiveFile) {
      setError("Выберите Java-файл .java или ZIP-проект .zip.");
      return;
    }
    if (file.size > (javaFile ? MAX_SOURCE_BYTES : MAX_ARCHIVE_BYTES)) {
      setError(javaFile ? "Java-файл больше лимита 2 МиБ." : "ZIP-архив больше лимита 16 МиБ.");
      return;
    }

    setBusy(true);
    try {
      const project = await uploadProjectFile(file);
      router.push(`/projects/${project.id}`);
    } catch (cause) {
      setError(uploadErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-7">
      <button
        className={`flex min-h-[92px] w-full items-center gap-4 rounded-lg border border-dashed p-[18px] text-left text-slate-200 transition hover:-translate-y-px hover:border-cyan-400 hover:bg-[#0e1b2d] disabled:cursor-progress disabled:opacity-70 ${dragging ? "-translate-y-px border-cyan-400 bg-[#0e1b2d]" : "border-slate-600 bg-[#0a1221]"}`}
        type="button"
        onClick={() => inputRef.current?.click()}
        onDragEnter={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragOver={(event) => event.preventDefault()}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          void openFile(event.dataTransfer.files[0]);
        }}
        disabled={busy}
      >
        <span className="grid size-[42px] shrink-0 place-items-center rounded-lg bg-cyan-400/10 text-cyan-400 [&_svg]:size-5">
          {busy ? <LoaderCircle className="spin" /> : <FileUp />}
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <strong className="text-xs text-slate-200">{busy ? "Загружаем проект…" : "Перетащите .java или .zip сюда"}</strong>
          <span className="text-[10px] text-slate-400">{busy ? "Создаём приватную сессию и сохраняем исходник" : "Java-файл до 2 МиБ · ZIP до 16 МиБ"}</span>
        </span>
        <ArrowUpRight className="w-4 text-slate-500" />
      </button>
      <input
        ref={inputRef}
        className="sr-only"
        type="file"
        accept=".java,.zip,text/x-java-source,application/zip"
        aria-label="Выбрать файл Java"
        tabIndex={-1}
        onChange={(event) => {
          void openFile(event.currentTarget.files?.[0]);
          event.currentTarget.value = "";
        }}
      />
      {error ? <p className="mt-2.5 text-[11px] text-rose-300" role="alert">{error}</p> : null}
      <div className="mt-2.5 flex justify-between gap-3 font-mono text-[9px] text-slate-500 max-[700px]:flex-col">
        <span className="inline-flex items-center gap-1.5"><FileCode2 size={15} /> .java или ZIP-проект</span>
        <span className="inline-flex items-center gap-1.5"><LockKeyhole size={14} /> Приватная гостевая сессия</span>
      </div>
    </div>
  );
}

function uploadErrorMessage(cause: unknown) {
  if (!(cause instanceof ApiError)) return "Не удалось загрузить файл. Проверьте соединение с API.";
  switch (cause.code) {
    case "project_too_large": return "Файл превышает допустимый размер.";
    case "invalid_archive": return "ZIP-архив повреждён или не читается.";
    case "unsafe_archive_path": return "В архиве найден небезопасный путь.";
    case "no_java_sources": return "В архиве не найдено Java-файлов.";
    case "invalid_request": return "API не принял файл. Проверьте расширение и кодировку UTF-8.";
    case "unauthorized": return "Не удалось создать гостевую сессию. Попробуйте ещё раз.";
    case "request_failed": return "Сервер недоступен. Запустите API и попробуйте снова.";
    default: return cause.message || `Не удалось загрузить файл (HTTP ${cause.status}).`;
  }
}
