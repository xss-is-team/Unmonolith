"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowUpRight, FileCode2, FileUp, LoaderCircle, LockKeyhole } from "lucide-react";
import { ApiError, MAX_SOURCE_BYTES, uploadJavaFile } from "@/lib/api";

export function UploadPanel() {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function openFile(file?: File) {
    if (!file) return;
    setError("");
    if (!file.name.toLowerCase().endsWith(".java")) {
      setError("Выберите исходный файл с расширением .java.");
      return;
    }
    if (file.size > MAX_SOURCE_BYTES) {
      setError("Файл больше лимита 2 МБ.");
      return;
    }

    setBusy(true);
    try {
      const project = await uploadJavaFile(file);
      router.push(`/projects/${project.id}`);
    } catch (cause) {
      setError(uploadErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="upload-stack">
      <button
        className={`dropzone${dragging ? " dropzone--active" : ""}`}
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
        <span className="dropzone-icon">
          {busy ? <LoaderCircle className="spin" /> : <FileUp />}
        </span>
        <span className="dropzone-copy">
          <strong>{busy ? "Загружаем проект…" : "Перетащите .java сюда"}</strong>
          <span>{busy ? "Создаём приватную сессию и сохраняем исходник" : "или выберите файл на компьютере"}</span>
        </span>
        <ArrowUpRight className="dropzone-arrow" />
      </button>
      <input
        ref={inputRef}
        className="sr-only"
        type="file"
        accept=".java,text/x-java-source"
        onChange={(event) => {
          void openFile(event.currentTarget.files?.[0]);
          event.currentTarget.value = "";
        }}
      />
      {error ? <p className="form-error" role="alert">{error}</p> : null}
      <div className="upload-meta">
        <span><FileCode2 size={15} /> Один файл · до 2 МБ</span>
        <span><LockKeyhole size={14} /> Приватная гостевая сессия</span>
      </div>
    </div>
  );
}

function uploadErrorMessage(cause: unknown) {
  if (!(cause instanceof ApiError)) return "Не удалось загрузить файл. Проверьте соединение с API.";
  switch (cause.code) {
    case "project_too_large": return "Файл превышает лимит 2 МиБ.";
    case "invalid_request": return "API не принял файл. Проверьте расширение и кодировку UTF-8.";
    case "unauthorized": return "Не удалось создать гостевую сессию. Попробуйте ещё раз.";
    case "request_failed": return "Сервер недоступен. Запустите API и попробуйте снова.";
    default: return cause.message || `Не удалось загрузить файл (HTTP ${cause.status}).`;
  }
}
