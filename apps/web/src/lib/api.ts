export type Project = {
  id: string;
  filename: string;
  kind: "single_file" | "archive";
  file_count: number;
  revision: number;
  created_at: string;
};

export type ProjectFile = {
  path: string;
  kind: "java" | "resource";
  role: "main" | "test" | "resource";
  source_root: string | null;
  size: number;
  sha256: string;
};

export type ProjectFileManifest = {
  revision: number;
  files: ProjectFile[];
  skipped: { path: string; reason: string }[];
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

export type ProjectGraphNodeKind =
  | "class"
  | "interface"
  | "enum"
  | "record"
  | "annotation_type"
  | "method"
  | "constructor";

export type ProjectGraphNode = {
  id: string;
  kind: ProjectGraphNodeKind;
  name: string;
  qualified_name?: string | null;
  method_id?: string | null;
  cfg_entry?: string | null;
  source_range: SourceRange;
  external_supertypes?: {
    name: string;
    relation: "extends" | "implements";
    resolved: boolean;
  }[];
};

export type ProjectGraphEdge = {
  from: string;
  to: string;
  kind: "contains" | "extends" | "implements" | "calls" | "constructs" | "depends_on";
  count: number;
  sites: SourceRange[];
};

export type UnresolvedProjectCall = {
  id: string;
  from: string;
  kind: "method_call" | "constructor_call";
  name: string;
  expression: string;
  source_range: SourceRange;
  reason: string;
};

export type ProjectGraph = {
  version: 1;
  scope: "compilation_unit" | "project";
  nodes: ProjectGraphNode[];
  edges: ProjectGraphEdge[];
  unresolved_calls: UnresolvedProjectCall[];
  stats: {
    resolved_internal_calls: number;
    external_calls: number;
    unresolved_calls: number;
  };
};

export type ProjectGraphResponse = {
  revision: number;
  graph: ProjectGraph | null;
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

export type ComplexityReport = {
  revision: number;
  methods: {
    method_id: string;
    file: string | null;
    time_complexity: string;
    space_complexity: string | null;
    confidence: "high" | "medium" | "low";
    reason: string;
    assumptions: string[];
    limitations: string[];
    source_range: SourceRange;
    cfg_node_ids: string[];
  }[];
};

export type FindingRecord = {
  method_id: string;
  file: string | null;
  finding: AnalysisFinding;
};

export type FindingsReport = { revision: number; findings: FindingRecord[] };

export type OptimizationProposal = {
  id: string;
  rule: "nested_scan_to_hashmap" | string;
  title?: string;
  summary?: string;
  applicability: "applicable" | "not_applicable";
  not_applicable_reasons?: string[];
  preconditions: { id: string; status: "satisfied" | "violated"; detail: string }[];
  assumptions: { id: string; text: string }[];
  method_id: string | null;
  file: string;
  affected_files: string[];
  shape?: "first_match" | "all_matches";
  outer_loop?: SourceRange;
  complexity: {
    method_before?: string;
    scan_before?: string;
    scan_after?: string;
    note?: string;
  };
};

export type OptimizationList = { revision: number; rule: string; proposals: OptimizationProposal[] };

export type FileDiff = {
  path: string;
  status: "added" | "removed" | "modified";
  additions: number;
  deletions: number;
  unified_diff: string;
};

export type OptimizationPreview = {
  base_revision: number;
  proposal: OptimizationProposal;
  diff: FileDiff[];
  preview_sha256: string;
  method_complexity_before: string | null;
  method_complexity_after: string | null;
  validation: "valid";
};

export type OptimizationApplyResult = {
  revision: number;
  base_revision: number;
  proposal: OptimizationProposal;
  diff: FileDiff[];
  analysis_status: "completed" | "partial";
};

export type CfgEditOperation = {
  type: "move_statement";
  node_id: string;
  target_node_id: string;
  position: "before" | "after";
};

export type CfgEditPreview = {
  base_revision: number;
  method_id: string;
  operation: Record<string, unknown>;
  diff: FileDiff[];
  preview_sha256: string;
  node_mapping: { old_id: string; new_id: string | null }[];
};

export type CfgEditApplyResult = {
  revision: number;
  base_revision: number;
  method_id: string;
  operation: Record<string, unknown>;
  diff: FileDiff[];
  node_mapping: { old_id: string; new_id: string | null }[];
  analysis_status: "completed" | "partial";
};

export type BenchmarkWorkloadInput = { name: string; size?: number; stdin: string };
export type BenchmarkWorkloadResult = {
  name: string;
  declared_size: number | null;
  stdin_sha256: string;
  stdin_bytes: number;
  baseline: { status: string; samples_ns: number[]; warmup_ns: number[]; outcome?: string | null } | null;
  candidate: { status: string; samples_ns: number[]; warmup_ns: number[]; outcome?: string | null } | null;
  baseline_stats: { iterations: number; median_ns: number; min_ns: number; max_ns: number; p25_ns: number; p75_ns: number; relative_spread: number; mean_ns: number; stddev_ns: number; mad_ns: number } | null;
  candidate_stats: { iterations: number; median_ns: number; min_ns: number; max_ns: number; p25_ns: number; p75_ns: number; relative_spread: number; mean_ns: number; stddev_ns: number; mad_ns: number } | null;
  equivalent: boolean | null;
  verdict: string;
  speedup: number | null;
  speedup_ci95: [number, number] | null;
  p_value: number | null;
  notes: string[];
};

export type BenchmarkReport = {
  report_id: string;
  created_at: string;
  status: "completed" | "compilation_failed" | "incomplete";
  baseline_revision: number;
  candidate_revision: number;
  settings: { warmup_iterations: number; measured_iterations: number; class_loading: "fresh" | "shared"; iteration_timeout_ms: number; source_level: string; main_class: string | null };
  environment: { runner_image: string | null; cpu_limit: string; memory_limit: string; network: string; baseline_jvm: Record<string, unknown> | null; candidate_jvm: Record<string, unknown> | null };
  timings: { baseline: { compile_and_setup_ms: number; harness_setup_ms: number; benchmark_process_ms: number }; candidate: { compile_and_setup_ms: number; harness_setup_ms: number; benchmark_process_ms: number } };
  static_complexity: { changes: { method_id: string; before: string; after: string; confidence_before: string; confidence_after: string }[]; unchanged_methods: number; note: string };
  functional: { equivalent_on_all_workloads: boolean; non_equivalent_workloads: string[]; note: string };
  runtime: { workloads: BenchmarkWorkloadResult[]; overall: { verdict: string; summary: string; equivalent_on_all_workloads?: boolean } };
  baseline_compilation: CompilationResult | null;
  candidate_compilation: CompilationResult | null;
  limitations: string[];
};

export const MAX_SOURCE_BYTES = 2 * 1024 * 1024;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function responseError(response: Response) {
  const body = (await response.json().catch(() => null)) as
    | { code?: string; message?: string; details?: unknown }
    | null;
  return new ApiError(
    body?.message ?? `Запрос завершился с кодом ${response.status}`,
    response.status,
    body?.code ?? "request_failed",
    body?.details,
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

export async function uploadProjectFile(file: File): Promise<Project> {
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

export const uploadJavaFile = uploadProjectFile;

export function getProject(projectId: string) {
  return request<Project>(`/projects/${encodeURIComponent(projectId)}`);
}

export function getProjectFiles(projectId: string, revision?: number) {
  const query = revision === undefined ? "" : `?revision=${revision}`;
  return request<ProjectFileManifest>(`/projects/${encodeURIComponent(projectId)}/files${query}`);
}

function projectFileUrl(projectId: string, path: string, revision?: number) {
  const encodedPath = path.split("/").map(encodeURIComponent).join("/");
  const query = revision === undefined ? "" : `?revision=${revision}`;
  return `/api/v1/projects/${encodeURIComponent(projectId)}/files/${encodedPath}${query}`;
}

export async function getProjectFile(projectId: string, path: string, revision?: number) {
  const response = await fetch(projectFileUrl(projectId, path, revision), { credentials: "include", cache: "no-store" });
  if (!response.ok) throw await responseError(response);
  const sourceRevision = Number(response.headers.get("X-Source-Revision"));
  if (!Number.isSafeInteger(sourceRevision) || sourceRevision < 1) {
    throw new ApiError("API вернул некорректную ревизию файла.", 502, "invalid_response");
  }
  return { source: await response.text(), revision: sourceRevision };
}

export function updateProjectFile(projectId: string, path: string, source: string, expectedRevision: number) {
  return request<Project>(`/projects/${encodeURIComponent(projectId)}/files/${path.split("/").map(encodeURIComponent).join("/")}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ source, expected_revision: expectedRevision }),
  });
}

export async function getProjectGraph(projectId: string): Promise<ProjectGraphResponse> {
  const response = await request<unknown>(`/projects/${encodeURIComponent(projectId)}/graph`);
  if (!isRecord(response) || !Number.isSafeInteger(response.revision) || Number(response.revision) < 1) {
    throw new ApiError("API вернул некорректную ревизию графа проекта.", 502, "invalid_response");
  }
  const rawGraph = response.graph ?? response.project_graph;
  // `graph: null` is a valid parser result (see the `project_graph_failed` diagnostic).
  const graph = rawGraph === null || rawGraph === undefined ? null : parseProjectGraph(rawGraph);
  return { revision: response.revision as number, graph };
}

/** Validate parser-shaped project graph data at the API boundary. */
export function parseProjectGraph(value: unknown): ProjectGraph {
  if (!isProjectGraph(value)) {
    throw new ApiError("API вернул некорректный граф проекта.", 502, "invalid_response");
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isProjectGraph(value: unknown): value is ProjectGraph {
  if (!isRecord(value)
    || value.version !== 1
    || (value.scope !== "compilation_unit" && value.scope !== "project")
    || !Array.isArray(value.nodes)
    || !Array.isArray(value.edges)
    || !Array.isArray(value.unresolved_calls)
    || !isRecord(value.stats)) return false;

  const nodeKinds = new Set<ProjectGraphNodeKind>([
    "class", "interface", "enum", "record", "annotation_type", "method", "constructor",
  ]);
  const edgeKinds = new Set<ProjectGraphEdge["kind"]>([
    "contains", "extends", "implements", "calls", "constructs", "depends_on",
  ]);
  const nodeIds = new Set<string>();
  const requiresFileRanges = value.scope === "project";

  for (const node of value.nodes) {
    if (!isRecord(node)
      || typeof node.id !== "string" || !node.id
      || nodeIds.has(node.id)
      || typeof node.kind !== "string" || !nodeKinds.has(node.kind as ProjectGraphNodeKind)
      || typeof node.name !== "string" || !node.name
      || !isSourceRange(node.source_range, requiresFileRanges)
      || !isOptionalString(node.qualified_name)
      || !isOptionalString(node.method_id)
      || !isOptionalString(node.cfg_entry)) return false;
    if (node.external_supertypes !== undefined
      && (!Array.isArray(node.external_supertypes)
        || !node.external_supertypes.every((external) => isRecord(external)
          && typeof external.name === "string"
          && (external.relation === "extends" || external.relation === "implements")
          && typeof external.resolved === "boolean"))) return false;
    nodeIds.add(node.id);
  }

  for (const edge of value.edges) {
    if (!isRecord(edge)
      || typeof edge.from !== "string" || !nodeIds.has(edge.from)
      || typeof edge.to !== "string" || !nodeIds.has(edge.to)
      || typeof edge.kind !== "string" || !edgeKinds.has(edge.kind as ProjectGraphEdge["kind"])
      || !isPositiveInteger(edge.count)
      || !Array.isArray(edge.sites)
      || !edge.sites.every((site) => isSourceRange(site, requiresFileRanges))) return false;
  }

  const unresolvedIds = new Set<string>();
  for (const call of value.unresolved_calls) {
    if (!isRecord(call)
      || typeof call.id !== "string" || !call.id || unresolvedIds.has(call.id)
      || typeof call.from !== "string" || !nodeIds.has(call.from)
      || (call.kind !== "method_call" && call.kind !== "constructor_call")
      || typeof call.name !== "string"
      || typeof call.expression !== "string"
      || !isSourceRange(call.source_range, requiresFileRanges)
      || typeof call.reason !== "string") return false;
    unresolvedIds.add(call.id);
  }

  return isNonNegativeInteger(value.stats.resolved_internal_calls)
    && isNonNegativeInteger(value.stats.external_calls)
    && isNonNegativeInteger(value.stats.unresolved_calls)
    && value.stats.unresolved_calls === value.unresolved_calls.length;
}

function isOptionalString(value: unknown) {
  return value === undefined || value === null || typeof value === "string";
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0;
}

function isSourceRange(value: unknown, requireFile = false): value is SourceRange {
  return isRecord(value)
    && isNonNegativeInteger(value.start)
    && isNonNegativeInteger(value.length)
    && (typeof value.file === "string" && value.file.length > 0 || !requireFile && value.file === undefined)
    && (value.line === undefined || isPositiveInteger(value.line))
    && (value.end_line === undefined || isPositiveInteger(value.end_line))
    && (value.line === undefined || value.end_line === undefined || Number(value.end_line) >= Number(value.line));
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
    const project = await getProject(projectId);
    const manifest = await getProjectFiles(projectId, project.revision);
    if (manifest.revision !== project.revision) continue;
    const firstJava = manifest.files.find((file) => file.kind === "java");
    if (!firstJava) throw new ApiError("В проекте нет Java-файлов.", 422, "no_java_sources");
    const current = project.kind === "archive"
      ? await getProjectFile(projectId, firstJava.path, project.revision)
      : await getProjectSource(projectId);
    if (project.revision === current.revision) {
      return { project, source: current.source, revision: current.revision, activeFilePath: firstJava.path, files: manifest.files };
    }
  }
  throw new ApiError(
    "Исходник менялся во время загрузки. Обновите страницу.",
    409,
    "revision_conflict",
  );
}

export function getProjectComplexity(projectId: string) {
  return request<ComplexityReport>(`/projects/${encodeURIComponent(projectId)}/complexity`);
}

export function getProjectFindings(projectId: string) {
  return request<FindingsReport>(`/projects/${encodeURIComponent(projectId)}/findings`);
}

export function getOptimizations(projectId: string, sourceLevel = "21") {
  return request<OptimizationList>(`/projects/${encodeURIComponent(projectId)}/optimizations?source_level=${encodeURIComponent(sourceLevel)}`);
}

export function previewOptimization(projectId: string, proposalId: string, baseRevision: number, sourceLevel = "21") {
  return request<OptimizationPreview>(`/projects/${encodeURIComponent(projectId)}/optimizations/${encodeURIComponent(proposalId)}/preview`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base_revision: baseRevision, source_level: sourceLevel }),
  });
}

export function applyOptimization(projectId: string, proposalId: string, baseRevision: number, previewHash: string, acknowledgedAssumptions: string[], sourceLevel = "21") {
  return request<OptimizationApplyResult>(`/projects/${encodeURIComponent(projectId)}/optimizations/${encodeURIComponent(proposalId)}/apply`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base_revision: baseRevision, preview_sha256: previewHash, acknowledged_assumptions: acknowledgedAssumptions, source_level: sourceLevel }),
  });
}

export function previewCfgEdit(projectId: string, baseRevision: number, methodId: string, operation: CfgEditOperation, sourceLevel = "21") {
  return request<CfgEditPreview>(`/projects/${encodeURIComponent(projectId)}/cfg-edits/preview`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base_revision: baseRevision, method_id: methodId, operation, source_level: sourceLevel }),
  });
}

export function applyCfgEdit(projectId: string, baseRevision: number, methodId: string, operation: CfgEditOperation, previewHash: string, sourceLevel = "21") {
  return request<CfgEditApplyResult>(`/projects/${encodeURIComponent(projectId)}/cfg-edits/apply`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base_revision: baseRevision, method_id: methodId, operation, preview_sha256: previewHash, source_level: sourceLevel }),
  });
}

export function compareRevisions(projectId: string, from: number, to: number) {
  return request<{ from_revision: number; to_revision: number; files: FileDiff[] }>(`/projects/${encodeURIComponent(projectId)}/revisions/${from}/diff/${to}`);
}

export function rollbackProject(projectId: string, baseRevision: number, toRevision: number) {
  return request<Project>(`/projects/${encodeURIComponent(projectId)}/rollback`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ base_revision: baseRevision, to_revision: toRevision }),
  });
}

export async function exportProject(projectId: string) {
  const response = await fetch(`/api/v1/projects/${encodeURIComponent(projectId)}/export`, { credentials: "include", cache: "no-store" });
  if (!response.ok) throw await responseError(response);
  return { blob: await response.blob(), filename: response.headers.get("Content-Disposition")?.match(/filename="?([^";]+)"?/)?.[1] ?? "project.zip" };
}

export function runBenchmark(projectId: string, body: {
  baseline_revision: number;
  candidate_revision: number;
  workloads: BenchmarkWorkloadInput[];
  warmup_iterations?: number;
  measured_iterations?: number;
  class_loading?: "fresh" | "shared";
  iteration_timeout_ms?: number;
  main_class?: string | null;
  source_level?: string;
}) {
  return request<BenchmarkReport>(`/projects/${encodeURIComponent(projectId)}/benchmark`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
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
  options?: { main_class?: string | null; source_level?: string },
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
        ...options,
      }),
    },
  );
}

export function getVerificationReport(projectId: string, reportId: string) {
  return request<VerificationReport>(
    `/projects/${encodeURIComponent(projectId)}/verify/${encodeURIComponent(reportId)}`,
  );
}
