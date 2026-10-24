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
  end_line?: number;
};

export type Diagnostic = {
  code: string;
  severity: "error" | "warning" | "info";
  message: string;
  source_range: SourceRange;
};

export type MethodSummary = {
  id: string;
  name: string;
  source_range: SourceRange;
};

export type AnalysisResult = {
  protocol_version: 1;
  status: "completed" | "partial" | "failed";
  revision: number;
  methods: MethodSummary[];
  diagnostics: Diagnostic[];
};

export type CfgMethod = MethodSummary & {
  selector: string;
  kind: "method" | "constructor";
};

export type MethodsResponse = {
  revision: number;
  methods: CfgMethod[];
};

export type CfgNode = {
  id: string;
  kind: string;
  statement_kind?: string;
  text?: string;
  source_range: SourceRange;
  reachable?: boolean;
};

export type CfgEdge = {
  from: string;
  to: string;
  kind: string;
  label?: string;
  back?: boolean;
};

export type Cfg = {
  method_id: string;
  entry: string;
  exit: string;
  nodes: CfgNode[];
  edges: CfgEdge[];
};

export type ComplexityEstimate = {
  time: string;
  confidence: "high" | "medium" | "low";
  reason: string;
  assumptions: string[];
};

export type ComplexityFragment = ComplexityEstimate & {
  kind: string;
  statement_kind: string;
  source_range: SourceRange;
  node_ids: string[];
};

export type AnalysisFinding = {
  id: string;
  severity: "warning" | "info";
  message: string;
  explanation: string;
  recommendation: string;
  source_range: SourceRange;
  node_ids: string[];
};

export type MethodAnalysis = {
  complexity: ComplexityEstimate;
  fragments: ComplexityFragment[];
  findings: AnalysisFinding[];
};

export type MethodAnalysisResponse = {
  revision: number;
  method_id: string;
  analysis: MethodAnalysis;
};

export type TransformResponse = {
  revision: number;
  source: string;
};

export type RevisionSummary = {
  revision: number;
  has_analysis: boolean;
};

export type RevisionDetail = RevisionSummary & {
  source: string;
};

export type VerifyTestCase = {
  name: string;
  stdin: string;
};

export type CapturedStream = {
  utf8: string | null;
  base64: string;
  truncated: boolean;
};

export type CompilationResult = {
  status: "completed" | "failed" | "timed_out" | "output_overflow" | "missing_main";
  exit_code: number | null;
  timed_out: boolean;
  output_overflow: boolean;
  stdout: CapturedStream;
  stderr: CapturedStream;
  main_class: string | null;
  diagnostics: { code: string; message: string }[];
};

export type ExecutionObservation = {
  stdout: CapturedStream;
  stderr: CapturedStream;
  exit_code: number | null;
  timed_out: boolean;
  output_overflow: boolean;
};

export type VerificationCaseResult = {
  name: string;
  passed: boolean;
  mismatches: { field: string; explanation: string }[];
  baseline: ExecutionObservation;
  candidate: ExecutionObservation;
};

export type VerificationReport = {
  report_id: string;
  created_at: string;
  requested_test_cases: number;
  behaviorally_verified: boolean;
  status: "passed" | "failed" | "compilation_failed";
  baseline_revision: number;
  candidate_revision: number;
  baseline_compilation: CompilationResult;
  candidate_compilation: CompilationResult;
  executed_test_cases: number;
  test_cases: VerificationCaseResult[];
  explanation: string;
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

async function responseError(response: Response) {
  const body = (await response.json().catch(() => null)) as
    | { code?: string; message?: string }
    | null;
  return new ApiError(
    body?.message ?? `Запрос завершился с кодом ${response.status}`,
    response.status,
    body?.code ?? "request_failed",
  );
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    credentials: "include",
    cache: "no-store",
  });
  if (!response.ok) throw await responseError(response);
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
  return request<Project>(`/projects/${encodeURIComponent(projectId)}`);
}

export async function getProjectSource(projectId: string) {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/source`,
    { credentials: "include", cache: "no-store" },
  );
  if (!response.ok) throw await responseError(response);

  const revision = Number(response.headers.get("X-Source-Revision"));
  if (!Number.isSafeInteger(revision) || revision < 1) {
    throw new ApiError("API вернул некорректную ревизию исходника.", 502, "invalid_response");
  }
  return { source: await response.text(), revision };
}

export async function getProjectWorkspace(projectId: string) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const [project, current] = await Promise.all([
      getProject(projectId),
      getProjectSource(projectId),
    ]);
    if (project.revision === current.revision) {
      return { project, source: current.source, revision: current.revision };
    }
  }
  throw new ApiError(
    "Исходник менялся во время загрузки. Обновите страницу.",
    409,
    "revision_conflict",
  );
}

export function updateProjectSource(
  projectId: string,
  source: string,
  expectedRevision: number,
) {
  return request<Project>(`/projects/${encodeURIComponent(projectId)}/source`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ source, expected_revision: expectedRevision }),
  });
}

export async function analyzeProjectStream(
  projectId: string,
  onProgress: (stage: string) => void,
): Promise<AnalysisResult> {
  const response = await fetch(
    `/api/v1/projects/${encodeURIComponent(projectId)}/analyze/stream`,
    { method: "POST", credentials: "include", cache: "no-store" },
  );
  if (!response.ok || !response.body) {
    if (!response.ok) throw await responseError(response);
    throw new ApiError("API не вернул поток анализа.", 502, "invalid_response");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let result: AnalysisResult | undefined;

  const consume = (chunk: string) => {
    buffer += chunk;
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() ?? "";
    for (const block of blocks) {
      const lines = block.split(/\r?\n/);
      const event = lines.find((line) => line.startsWith("event:"))?.slice(6).trim();
      const data = lines
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart())
        .join("\n");
      if (!event || !data) continue;
      if (event === "started") onProgress("started");
      if (event === "progress") {
        onProgress((JSON.parse(data) as { stage?: string }).stage ?? "analyzing");
      }
      if (event === "completed") result = JSON.parse(data) as AnalysisResult;
      if (event === "failed") {
        const failure = JSON.parse(data) as { code?: string; message?: string };
        throw new ApiError(
          failure.message ?? "Анализ не выполнен.",
          500,
          failure.code ?? "analysis_failed",
        );
      }
    }
  };

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      consume(decoder.decode(value, { stream: true }));
    }
    consume(decoder.decode());
  } finally {
    reader.releaseLock();
  }
  if (!result) {
    throw new ApiError(
      "Поток анализа завершился без результата.",
      502,
      "invalid_response",
    );
  }
  return result;
}

export function getProjectRevisions(projectId: string) {
  return request<RevisionSummary[]>(
    `/projects/${encodeURIComponent(projectId)}/revisions`,
  );
}

export function getProjectRevision(projectId: string, revision: number) {
  return request<RevisionDetail>(
    `/projects/${encodeURIComponent(projectId)}/revisions/${revision}`,
  );
}

export function getProjectMethods(projectId: string) {
  return request<MethodsResponse>(
    `/projects/${encodeURIComponent(projectId)}/methods`,
  );
}

export function getMethodCfg(projectId: string, selector: string) {
  return request<Cfg>(
    `/projects/${encodeURIComponent(projectId)}/methods/${encodeURIComponent(selector)}/cfg`,
  );
}

export function getMethodAnalysis(projectId: string, selector: string) {
  return request<MethodAnalysisResponse>(
    `/projects/${encodeURIComponent(projectId)}/methods/${encodeURIComponent(selector)}/analysis`,
  );
}

export function replaceCondition(
  projectId: string,
  baseRevision: number,
  methodId: string,
  nodeId: string,
  expression: string,
) {
  return request<TransformResponse>(
    `/projects/${encodeURIComponent(projectId)}/transform`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        base_revision: baseRevision,
        method_id: methodId,
        node_id: nodeId,
        operation: { type: "replace_condition", expression },
      }),
    },
  );
}

export function verifyRevisions(
  projectId: string,
  baselineRevision: number,
  candidateRevision: number,
  testCases: VerifyTestCase[],
) {
  return request<VerificationReport>(
    `/projects/${encodeURIComponent(projectId)}/verify`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        baseline_revision: baselineRevision,
        candidate_revision: candidateRevision,
        test_cases: testCases,
      }),
    },
  );
}

export function getVerificationReport(projectId: string, reportId: string) {
  return request<VerificationReport>(
    `/projects/${encodeURIComponent(projectId)}/verify/${encodeURIComponent(reportId)}`,
  );
}
