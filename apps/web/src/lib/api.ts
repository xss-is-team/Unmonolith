export type Project = {
  id: string;
  filename: string;
  revision: number;
  created_at: string;
};

export type SourceRange = {
  file?: string;
  start: number;
  length: number;
  line?: number;
};

export type Diagnostic = {
  code: string;
  severity: "error" | "warning" | "info";
  message: string;
  source_range: SourceRange;
};

export type AnalysisResult = {
  protocol_version: 1;
  status: "completed" | "partial";
  revision: number;
  methods: { id: string; name: string; source_range: SourceRange }[];
  diagnostics: Diagnostic[];
};

export const MAX_SOURCE_BYTES = 2 * 1024 * 1024;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    credentials: "include",
    cache: "no-store",
  });

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as
      | { code?: string; message?: string }
      | null;
    throw new ApiError(
      body?.message ?? `Запрос завершился с кодом ${response.status}`,
      response.status,
      body?.code ?? "request_failed",
    );
  }

  return (await response.json()) as T;
}

export function checkHealth() {
  return request<{ status: "ok" }>("/healthz");
}

export async function uploadJavaFile(file: File): Promise<Project> {
  const form = new FormData();
  form.append("file", file, file.name);
  const upload = () => request<Project>("/projects", { method: "POST", body: form });

  try {
    return await upload();
  } catch (cause) {
    if (!(cause instanceof ApiError) || cause.status !== 401) throw cause;
    await request<{ kind: "guest" }>("/sessions/guest", { method: "POST" });
    return upload();
  }
}

export function getProject(projectId: string) {
  return request<Project>(`/projects/${projectId}`);
}

export async function getProjectSource(projectId: string) {
  const response = await fetch(`/api/v1/projects/${projectId}/source`, {
    credentials: "include",
    cache: "no-store",
  });

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as
      | { code?: string; message?: string }
      | null;
    throw new ApiError(
      body?.message ?? `Не удалось загрузить исходник (${response.status})`,
      response.status,
      body?.code ?? "request_failed",
    );
  }

  return {
    source: await response.text(),
    revision: parseRevision(response.headers.get("X-Source-Revision")),
  };
}

function parseRevision(value: string | null) {
  const revision = value === null ? Number.NaN : Number(value);
  if (!Number.isSafeInteger(revision) || revision < 1) {
    throw new ApiError("API вернул некорректную ревизию исходника.", 502, "invalid_response");
  }
  return revision;
}

export async function getProjectWorkspace(projectId: string) {
  // Project metadata and source are separate endpoints. Retry if a save lands
  // between them so the editor never starts with mismatched revisions.
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const [project, current] = await Promise.all([
      getProject(projectId),
      getProjectSource(projectId),
    ]);
    if (project.revision === current.revision) {
      return { project, source: current.source, revision: current.revision };
    }
  }
  throw new ApiError("Исходник менялся во время загрузки. Обновите страницу.", 409, "revision_conflict");
}

export function updateProjectSource(
  projectId: string,
  source: string,
  expectedRevision: number,
) {
  return request<Project>(`/projects/${projectId}/source`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ source, expected_revision: expectedRevision }),
  });
}

export function analyzeProject(projectId: string) {
  return request<AnalysisResult>(`/projects/${projectId}/analyze`, {
    method: "POST",
  });
}
