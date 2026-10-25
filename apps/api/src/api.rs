use std::{collections::HashSet, convert::Infallible, path::PathBuf, sync::Arc};

use axum::{
    body::Body,
    extract::{
        multipart::MultipartRejection, rejection::JsonRejection, DefaultBodyLimit, Multipart, Path,
        Query, State,
    },
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::Response,
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use time::{format_description::well_known::Rfc3339, OffsetDateTime};
use tokio::{
    fs,
    sync::{mpsc, Mutex},
};
use tokio_stream::wrappers::ReceiverStream;
use utoipa::{Modify, OpenApi};
use utoipa_swagger_ui::SwaggerUi;
use uuid::Uuid;

use crate::{
    analyzer::{
        AnalysisResult, AnalysisStatus, DecomposeRequest, Diagnostic, DiagnosticSeverity,
        JavaAnalyzer, Method, OptimizeRequest, SourceRange, TransformOperation, TransformRequest,
    },
    archive, benchmark,
    diff::{self, DiffStatus, FileDiff},
    error::{ApiError, ApiErrorBody},
    project::{
        self, FileEntry, FileRole, Manifest, Project, ProjectKind, RevisionDetail, RevisionSummary,
        UpdateFileRequest, UpdateSourceRequest,
    },
    runner::{
        BehaviorMismatch, CapturedStream, CompilationResult, CompilationStatus,
        ExecutionObservation, JavaRunner, MismatchField, RunnerDiagnostic, TestCaseResult,
        VerificationReport, VerificationStatus, VerifyRequest, VerifyTestCase,
    },
    session,
    verify_hints::{self, VerifyWarning},
    Config,
};

#[derive(Clone)]
pub struct AppState(Arc<AppStateInner>);

struct AppStateInner {
    data_dir: PathBuf,
    max_project_bytes: usize,
    import_limits: archive::ImportLimits,
    secure_cookies: bool,
    analyzer: JavaAnalyzer,
    runner: JavaRunner,
    writes: Mutex<()>,
}

impl AppState {
    pub async fn new(config: Config) -> std::io::Result<Self> {
        fs::create_dir_all(config.data_dir.join("sessions")).await?;
        let data_dir = fs::canonicalize(&config.data_dir).await?;
        Ok(Self(Arc::new(AppStateInner {
            data_dir,
            max_project_bytes: config.max_project_bytes,
            import_limits: archive::ImportLimits {
                max_file_bytes: config.max_project_bytes,
                ..config.import_limits
            },
            secure_cookies: config.secure_cookies,
            analyzer: JavaAnalyzer::new(
                config.analyzer_jar,
                config.java_bin,
                config.analyzer_timeout,
                config.analyzer_output_bytes,
            ),
            runner: JavaRunner::new(
                config.container_bin,
                config.runner_image,
                config.runner_compile_timeout,
                config.runner_case_timeout,
                config.runner_output_bytes,
            )
            .with_benchmark_timeout(config.runner_benchmark_timeout),
            writes: Mutex::new(()),
        })))
    }
}

#[derive(OpenApi)]
#[openapi(
    paths(
        healthz,
        create_guest_session,
        upload_project,
        get_project,
        get_project_source,
        update_project_source,
        list_project_files,
        get_project_file,
        update_project_file,
        analyze_project,
        analyze_project_stream,
        list_project_revisions,
        get_project_revision,
        list_methods,
        get_method_cfg,
        get_method_analysis,
        get_project_graph,
        get_project_complexity,
        list_optimizations,
        preview_optimization,
        apply_optimization,
        list_decomposition,
        preview_decomposition,
        apply_decomposition,
        rollback_project,
        run_benchmark,
        get_benchmark_report,
        preview_cfg_edit,
        apply_cfg_edit,
        compare_revisions,
        get_project_findings,
        transform_project,
        verify_project,
        get_verification_report
    ),
    components(schemas(
        HealthResponse,
        GuestSessionResponse,
        UploadRequest,
        ApiErrorBody,
        Project,
        UpdateSourceRequest,
        UpdateFileRequest,
        ProjectKind,
        Manifest,
        FileEntry,
        FileRole,
        archive::FileKind,
        archive::SkippedEntry,
        AnalysisResult,
        AnalysisStatus,
        Method,
        Diagnostic,
        DiagnosticSeverity,
        SourceRange,
        RevisionSummary,
        RevisionDetail,
        MethodsResponse,
        CfgMethod,
        Cfg,
        CfgNode,
        CfgEdge,
        MethodAnalysisResponse,
        MethodAnalysis,
        ProjectGraphResponse,
        ProjectGraph,
        ProjectGraphNode,
        ProjectGraphSupertype,
        ProjectGraphEdge,
        ProjectGraphUnresolvedCall,
        ProjectGraphStats,
        ComplexityEstimate,
        ComplexityReport,
        OptimizationList,
        OptimizationPreviewRequest,
        OptimizationPreview,
        OptimizationApplyRequest,
        OptimizationApplyResponse,
        DecompositionList,
        DecompositionRequest,
        DecompositionPreview,
        DecompositionApplyRequest,
        DecompositionApplyResponse,
        RollbackRequest,
        BenchmarkRequest,
        BenchmarkWorkloadInput,
        ClassLoading,
        BenchmarkReport,
        BenchmarkSettingsReport,
        BenchmarkEnvironment,
        PhaseTimings,
        RevisionTimings,
        StaticComplexity,
        ComplexityChange,
        FunctionalOutcome,
        RuntimeComparison,
        benchmark::SampleStats,
        benchmark::WorkloadObservation,
        benchmark::WorkloadComparison,
        benchmark::OverallVerdict,
        benchmark::Verdict,
        CfgEditRequest,
        CfgEditOperation,
        MovePosition,
        CfgEditApplyRequest,
        CfgEditPreview,
        CfgEditApplyResponse,
        RevisionDiff,
        FileDiff,
        DiffStatus,
        MethodComplexity,
        FindingsReport,
        MethodFinding,
        ComplexityFragment,
        AnalysisFinding,
        TransformRequest,
        TransformOperation,
        TransformResponse,
        VerifyRequest,
        VerifyTestCase,
        VerificationReport,
        VerificationStatus,
        CompilationResult,
        CompilationStatus,
        RunnerDiagnostic,
        CapturedStream,
        TestCaseResult,
        ExecutionObservation,
        BehaviorMismatch,
        MismatchField,
        VerifyWarning,
        PersistedVerificationReport
    )),
    modifiers(&SecurityAddon),
    tags(
        (name = "System"),
        (name = "Sessions"),
        (name = "Projects"),
        (name = "Analysis"),
        (name = "Optimization"),
        (name = "Transformations"),
        (name = "Verification")
    )
)]
struct ApiDoc;

struct SecurityAddon;

impl Modify for SecurityAddon {
    fn modify(&self, openapi: &mut utoipa::openapi::OpenApi) {
        use utoipa::openapi::security::{ApiKey, ApiKeyValue, SecurityScheme};

        if let Some(components) = openapi.components.as_mut() {
            components.add_security_scheme(
                "guestSession",
                SecurityScheme::ApiKey(ApiKey::Cookie(ApiKeyValue::new(session::COOKIE_NAME))),
            );
        }
    }
}

pub fn app(state: AppState) -> Router {
    let body_limit = state
        .0
        .max_project_bytes
        .max(state.0.import_limits.max_archive_bytes)
        .saturating_add(64 * 1024);
    let api = Router::new()
        .route("/healthz", get(healthz))
        .route("/sessions/guest", post(create_guest_session))
        .route("/projects", post(upload_project))
        .route("/projects/{project_id}", get(get_project))
        .route(
            "/projects/{project_id}/source",
            get(get_project_source).put(update_project_source),
        )
        .route("/projects/{project_id}/files", get(list_project_files))
        .route(
            "/projects/{project_id}/files/{*path}",
            get(get_project_file).put(update_project_file),
        )
        .route("/projects/{project_id}/analyze", post(analyze_project))
        .route(
            "/projects/{project_id}/analyze/stream",
            post(analyze_project_stream),
        )
        .route(
            "/projects/{project_id}/revisions",
            get(list_project_revisions),
        )
        .route(
            "/projects/{project_id}/revisions/{revision}",
            get(get_project_revision),
        )
        .route("/projects/{project_id}/methods", get(list_methods))
        .route(
            "/projects/{project_id}/methods/{method_selector}/cfg",
            get(get_method_cfg),
        )
        .route(
            "/projects/{project_id}/methods/{method_selector}/analysis",
            get(get_method_analysis),
        )
        .route("/projects/{project_id}/graph", get(get_project_graph))
        .route(
            "/projects/{project_id}/complexity",
            get(get_project_complexity),
        )
        .route(
            "/projects/{project_id}/optimizations",
            get(list_optimizations),
        )
        .route(
            "/projects/{project_id}/optimizations/{proposal_id}/preview",
            post(preview_optimization),
        )
        .route(
            "/projects/{project_id}/optimizations/{proposal_id}/apply",
            post(apply_optimization),
        )
        .route(
            "/projects/{project_id}/decomposition",
            get(list_decomposition),
        )
        .route(
            "/projects/{project_id}/decomposition/preview",
            post(preview_decomposition),
        )
        .route(
            "/projects/{project_id}/decomposition/apply",
            post(apply_decomposition),
        )
        .route(
            "/projects/{project_id}/cfg-edits/preview",
            post(preview_cfg_edit),
        )
        .route(
            "/projects/{project_id}/cfg-edits/apply",
            post(apply_cfg_edit),
        )
        .route("/projects/{project_id}/benchmark", post(run_benchmark))
        .route(
            "/projects/{project_id}/benchmark/{report_id}",
            get(get_benchmark_report),
        )
        .route("/projects/{project_id}/rollback", post(rollback_project))
        .route(
            "/projects/{project_id}/revisions/{from}/diff/{to}",
            get(compare_revisions),
        )
        .route("/projects/{project_id}/findings", get(get_project_findings))
        .route("/projects/{project_id}/transform", post(transform_project))
        .route("/projects/{project_id}/verify", post(verify_project))
        .route(
            "/projects/{project_id}/verify/{report_id}",
            get(get_verification_report),
        )
        .layer(DefaultBodyLimit::max(body_limit));

    Router::new()
        .nest("/api/v1", api)
        .merge(SwaggerUi::new("/swagger-ui").url("/api/v1/openapi.json", ApiDoc::openapi()))
        .with_state(state)
}

#[derive(Serialize, utoipa::ToSchema)]
struct HealthResponse {
    status: &'static str,
}

#[derive(Serialize, utoipa::ToSchema)]
struct GuestSessionResponse {
    kind: &'static str,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct UploadRequest {
    #[schema(value_type = String, format = Binary)]
    file: Vec<u8>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct OptimizationList {
    #[schema(minimum = 1)]
    revision: u64,
    rule: String,
    /// Each proposal has `id`, `applicability` (`applicable` | `not_applicable`), `preconditions`,
    /// `assumptions`, `complexity`, `method_id`, `file`, `affected_files`, source ranges and a summary.
    #[schema(value_type = Vec<Object>)]
    proposals: Vec<Value>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct DecompositionList {
    #[schema(minimum = 1)]
    revision: u64,
    rule: String,
    /// One entry per static member type of a top-level class: `id`, `name`, `kind`, `host`, `file`, `new_file`,
    /// `lines`, `extractable`, `depends_on` (ids that must move with it), `preconditions` and
    /// `not_extractable_reasons`.
    #[schema(value_type = Vec<Object>)]
    candidates: Vec<Value>,
}

#[derive(Deserialize, utoipa::ToSchema)]
struct DecompositionRequest {
    #[schema(minimum = 1)]
    base_revision: u64,
    /// Java language level (8, 11, 17 or 21); default 21.
    source_level: Option<String>,
    /// Candidate ids to move; empty or omitted selects every extractable type. A type that uses a sibling must be
    /// selected together with it.
    #[serde(default)]
    classes: Vec<String>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct DecompositionPreview {
    #[schema(minimum = 1)]
    base_revision: u64,
    #[schema(value_type = Vec<Object>)]
    extracted: Vec<Value>,
    /// Rewritten files and the new files, as exact diffs.
    diff: Vec<FileDiff>,
    preview_sha256: String,
    added_files: Vec<String>,
    changed_files: Vec<String>,
    /// Lines of each rewritten file before and after.
    #[schema(value_type = Object)]
    host_lines: Value,
    validation: String,
}

#[derive(Deserialize, utoipa::ToSchema)]
struct DecompositionApplyRequest {
    #[schema(minimum = 1)]
    base_revision: u64,
    source_level: Option<String>,
    #[serde(default)]
    classes: Vec<String>,
    preview_sha256: String,
}

#[derive(Serialize, utoipa::ToSchema)]
struct DecompositionApplyResponse {
    #[schema(minimum = 2)]
    revision: u64,
    base_revision: u64,
    #[schema(value_type = Vec<Object>)]
    extracted: Vec<Value>,
    diff: Vec<FileDiff>,
    /// Status of the re-analysis of the new revision.
    analysis_status: AnalysisStatus,
    /// `archive` from now on: a single-file project becomes a multi-file project (earlier revisions stay readable).
    #[schema(value_type = String)]
    project_kind: project::ProjectKind,
    #[schema(minimum = 1)]
    file_count: u32,
}

#[derive(Deserialize, utoipa::ToSchema)]
struct OptimizationPreviewRequest {
    #[schema(minimum = 1)]
    base_revision: u64,
    /// Java language level (8, 11, 17 or 21); default 21.
    source_level: Option<String>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct OptimizationPreview {
    #[schema(minimum = 1)]
    base_revision: u64,
    #[schema(value_type = Object)]
    proposal: Value,
    diff: Vec<FileDiff>,
    /// Fingerprint of `diff`; `apply` must echo it to apply exactly what was previewed.
    preview_sha256: String,
    /// Complexity estimate of the affected method before / after (static engine, may be `unknown`).
    #[schema(value_type = Object)]
    method_complexity_before: Value,
    #[schema(value_type = Object)]
    method_complexity_after: Value,
    /// `valid`: the rewritten project re-parses and introduces no new errors.
    validation: String,
}

#[derive(Deserialize, utoipa::ToSchema)]
struct OptimizationApplyRequest {
    #[schema(minimum = 1)]
    base_revision: u64,
    source_level: Option<String>,
    preview_sha256: String,
    /// Ids of every assumption listed in the proposal.
    acknowledged_assumptions: Vec<String>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct OptimizationApplyResponse {
    #[schema(minimum = 2)]
    revision: u64,
    base_revision: u64,
    #[schema(value_type = Object)]
    proposal: Value,
    diff: Vec<FileDiff>,
    #[schema(value_type = Object)]
    method_complexity_before: Value,
    #[schema(value_type = Object)]
    method_complexity_after: Value,
    /// Status of the re-analysis of the new revision.
    analysis_status: AnalysisStatus,
}

#[derive(Deserialize, utoipa::ToSchema)]
struct RollbackRequest {
    /// The current revision (optimistic lock).
    #[schema(minimum = 1)]
    base_revision: u64,
    /// An earlier revision whose files are restored as a new revision.
    #[schema(minimum = 1)]
    to_revision: u64,
}

#[derive(Serialize, utoipa::ToSchema)]
struct RevisionDiff {
    from_revision: u64,
    to_revision: u64,
    files: Vec<FileDiff>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
enum MovePosition {
    Before,
    After,
}

#[derive(Clone, Debug, Deserialize, Serialize, utoipa::ToSchema)]
#[serde(tag = "type", rename_all = "snake_case")]
enum CfgEditOperation {
    /// Moves one statement before/after another statement of the same block. Moving CFG nodes in the
    /// browser changes layout only; this is the operation that changes Java.
    MoveStatement {
        /// CFG node of the statement to move (a statement node, or the condition node of a compound statement).
        node_id: String,
        /// CFG node of the statement it is placed next to.
        target_node_id: String,
        position: MovePosition,
    },
}

#[derive(Deserialize, utoipa::ToSchema)]
struct CfgEditRequest {
    #[schema(minimum = 1)]
    base_revision: u64,
    /// Java language level (8, 11, 17 or 21); default 21.
    source_level: Option<String>,
    /// Stable method id (`package.Type.method(Params)`); node ids must come from this method's current CFG.
    method_id: String,
    operation: CfgEditOperation,
}

#[derive(Deserialize, utoipa::ToSchema)]
struct CfgEditApplyRequest {
    #[schema(minimum = 1)]
    base_revision: u64,
    source_level: Option<String>,
    method_id: String,
    operation: CfgEditOperation,
    /// `preview_sha256` of the preview that is being confirmed.
    preview_sha256: String,
}

#[derive(Serialize, utoipa::ToSchema)]
struct CfgEditPreview {
    base_revision: u64,
    method_id: String,
    /// What moves and the independence evidence for every statement it jumps over.
    #[schema(value_type = Object)]
    operation: Value,
    diff: Vec<FileDiff>,
    preview_sha256: String,
    /// Old CFG node id -> node id in the edited source (matched by kind and text; null if the node vanished).
    #[schema(value_type = Vec<Object>)]
    node_mapping: Vec<Value>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct CfgEditApplyResponse {
    #[schema(minimum = 2)]
    revision: u64,
    base_revision: u64,
    method_id: String,
    #[schema(value_type = Object)]
    operation: Value,
    diff: Vec<FileDiff>,
    #[schema(value_type = Vec<Object>)]
    node_mapping: Vec<Value>,
    analysis_status: AnalysisStatus,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
enum ClassLoading {
    /// Every iteration loads the program in a new class loader: static state cannot leak between iterations.
    #[default]
    Fresh,
    /// Iterations reuse loaded classes (JIT-warmed steady state); static state persists.
    Shared,
}

#[derive(Deserialize, utoipa::ToSchema)]
#[serde(deny_unknown_fields)]
struct BenchmarkWorkloadInput {
    /// Unique label, e.g. `n=2000`.
    name: String,
    /// Optional input size you used to build `stdin`, reported back for scaling analysis.
    size: Option<u64>,
    /// Deterministic stdin of the program (at most 64 KiB).
    stdin: String,
}

#[derive(Deserialize, utoipa::ToSchema)]
#[serde(deny_unknown_fields)]
struct BenchmarkRequest {
    #[schema(minimum = 1)]
    baseline_revision: u64,
    #[schema(minimum = 1)]
    candidate_revision: u64,
    /// 1 to 8 workloads; use several sizes to see how the difference scales.
    workloads: Vec<BenchmarkWorkloadInput>,
    /// Discarded iterations per workload (0-50, default 5).
    warmup_iterations: Option<u32>,
    /// Measured iterations per workload (1-100, default 15). Fewer than 10 never supports a speed claim.
    measured_iterations: Option<u32>,
    class_loading: Option<ClassLoading>,
    /// Per-iteration limit in ms (default 10000, max 60000); an exceeded iteration aborts the benchmark.
    iteration_timeout_ms: Option<u64>,
    /// Archive projects: fully qualified class with `main(String[])`.
    main_class: Option<String>,
    /// Java language level for compilation (8, 11, 17, 21; default 21).
    source_level: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, utoipa::ToSchema)]
struct BenchmarkSettingsReport {
    warmup_iterations: u32,
    measured_iterations: u32,
    class_loading: ClassLoading,
    iteration_timeout_ms: u64,
    source_level: String,
    main_class: Option<String>,
}

#[derive(Clone, Serialize, Deserialize, utoipa::ToSchema)]
struct BenchmarkEnvironment {
    runner_image: Option<String>,
    cpu_limit: String,
    memory_limit: String,
    network: String,
    /// JVM facts reported by the harness inside the container (version, VM, flags, processors, heap).
    #[schema(value_type = Option<Object>)]
    baseline_jvm: Option<Value>,
    #[schema(value_type = Option<Object>)]
    candidate_jvm: Option<Value>,
}

#[derive(Clone, Serialize, Deserialize, utoipa::ToSchema)]
struct RevisionTimings {
    /// Container start, source copy, javac, main-class inspection.
    compile_and_setup_ms: u64,
    harness_setup_ms: u64,
    /// Wall time of the whole benchmark JVM including its startup; the per-iteration algorithm timings are
    /// under `runtime`.
    benchmark_process_ms: u64,
}

#[derive(Clone, Serialize, Deserialize, utoipa::ToSchema)]
struct PhaseTimings {
    baseline: RevisionTimings,
    candidate: RevisionTimings,
}

#[derive(Clone, Serialize, Deserialize, utoipa::ToSchema)]
struct ComplexityChange {
    method_id: String,
    before: String,
    after: String,
    confidence_before: String,
    confidence_after: String,
}

/// Static Big O before/after, from the analysis of each revision. No timing is involved.
#[derive(Clone, Serialize, Deserialize, utoipa::ToSchema)]
struct StaticComplexity {
    /// Methods whose estimate differs between the revisions (only methods present in both).
    changes: Vec<ComplexityChange>,
    unchanged_methods: usize,
    note: String,
}

/// Functional outcome on the benchmark workloads. This is a behaviour check on the supplied inputs, not a
/// proof; use `POST /verify` for the stdin-based test-case verification.
#[derive(Clone, Serialize, Deserialize, utoipa::ToSchema)]
struct FunctionalOutcome {
    equivalent_on_all_workloads: bool,
    non_equivalent_workloads: Vec<String>,
    note: String,
}

#[derive(Clone, Serialize, utoipa::ToSchema)]
struct RuntimeComparison {
    workloads: Vec<benchmark::WorkloadComparison>,
    overall: benchmark::OverallVerdict,
}

#[derive(Clone, Serialize, utoipa::ToSchema)]
struct BenchmarkReport {
    report_id: Uuid,
    created_at: String,
    /// `completed`, `compilation_failed` or `incomplete` (a workload timed out or the run was cut off).
    status: String,
    baseline_revision: u64,
    candidate_revision: u64,
    settings: BenchmarkSettingsReport,
    environment: BenchmarkEnvironment,
    timings: PhaseTimings,
    /// 1. Static Big O before/after.
    static_complexity: StaticComplexity,
    /// 2. Functional outcome on the workloads.
    functional: FunctionalOutcome,
    /// 3. Measured runtime before/after.
    runtime: RuntimeComparison,
    #[schema(value_type = Option<Object>)]
    baseline_compilation: Option<crate::runner::CompilationResult>,
    #[schema(value_type = Option<Object>)]
    candidate_compilation: Option<crate::runner::CompilationResult>,
    limitations: Vec<String>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct MethodComplexity {
    /// Stable method reference (`package.Type.method(ParamTypes)`).
    method_id: String,
    file: Option<String>,
    /// `O(...)` or the literal `unknown` when no bound can be justified.
    time_complexity: String,
    /// Always null: space complexity is not estimated.
    space_complexity: Option<String>,
    confidence: String,
    analysis_engine: String,
    reason: String,
    assumptions: Vec<String>,
    limitations: Vec<String>,
    source_range: SourceRange,
    /// CFG nodes of the loops that determine the estimate (empty when none do).
    cfg_node_ids: Vec<String>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct ComplexityReport {
    #[schema(minimum = 1)]
    revision: u64,
    methods: Vec<MethodComplexity>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct MethodFinding {
    method_id: String,
    file: Option<String>,
    finding: AnalysisFinding,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct FindingsReport {
    #[schema(minimum = 1)]
    revision: u64,
    findings: Vec<MethodFinding>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct MethodsResponse {
    #[schema(minimum = 1)]
    revision: u64,
    methods: Vec<CfgMethod>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct CfgMethod {
    selector: String,
    id: String,
    name: String,
    kind: String,
    source_range: SourceRange,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct Cfg {
    method_id: String,
    entry: String,
    exit: String,
    nodes: Vec<CfgNode>,
    edges: Vec<CfgEdge>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct CfgNode {
    id: String,
    kind: String,
    statement_kind: Option<String>,
    text: Option<String>,
    source_range: SourceRange,
    reachable: Option<bool>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct CfgEdge {
    from: String,
    to: String,
    kind: String,
    label: Option<String>,
    back: Option<bool>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct MethodAnalysisResponse {
    #[schema(minimum = 1)]
    revision: u64,
    method_id: String,
    analysis: MethodAnalysis,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct MethodAnalysis {
    complexity: ComplexityEstimate,
    fragments: Vec<ComplexityFragment>,
    findings: Vec<AnalysisFinding>,
}

// Documentation-only schema mirroring the Java parser's `project_graph` output
// (see apps/parser ProjectGraph.build()); the endpoint returns that JSON verbatim.
#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct ProjectGraphResponse {
    #[schema(minimum = 1)]
    revision: u64,
    graph: Option<ProjectGraph>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct ProjectGraph {
    version: u32,
    scope: String,
    nodes: Vec<ProjectGraphNode>,
    edges: Vec<ProjectGraphEdge>,
    unresolved_calls: Vec<ProjectGraphUnresolvedCall>,
    stats: ProjectGraphStats,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct ProjectGraphNode {
    id: String,
    kind: String,
    name: String,
    qualified_name: Option<String>,
    method_id: Option<String>,
    cfg_entry: Option<String>,
    external_supertypes: Option<Vec<ProjectGraphSupertype>>,
    /// Project-relative file path (archive projects).
    file: Option<String>,
    source_range: SourceRange,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct ProjectGraphSupertype {
    name: String,
    relation: String,
    resolved: bool,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct ProjectGraphEdge {
    from: String,
    to: String,
    kind: String,
    count: u64,
    sites: Vec<SourceRange>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct ProjectGraphUnresolvedCall {
    id: String,
    from: String,
    kind: String,
    name: String,
    expression: String,
    source_range: SourceRange,
    reason: String,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct ProjectGraphStats {
    resolved_internal_calls: u64,
    external_calls: u64,
    unresolved_calls: u64,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct ComplexityEstimate {
    time: String,
    confidence: String,
    reason: String,
    assumptions: Vec<String>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct ComplexityFragment {
    kind: String,
    statement_kind: String,
    source_range: SourceRange,
    node_ids: Vec<String>,
    time: String,
    confidence: String,
    reason: String,
    assumptions: Vec<String>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct AnalysisFinding {
    id: String,
    severity: String,
    message: String,
    explanation: String,
    recommendation: String,
    source_range: SourceRange,
    node_ids: Vec<String>,
}

#[derive(Debug, Deserialize, Serialize, utoipa::ToSchema)]
struct PersistedVerificationReport {
    report_id: Uuid,
    #[schema(value_type = String, format = DateTime)]
    created_at: String,
    #[schema(minimum = 1)]
    requested_test_cases: usize,
    behaviorally_verified: bool,
    /// Advisory notes on how much a result can be trusted (empty scenarios, unchanged or unreachable code).
    /// They never change `behaviorally_verified`; reports stored before this field existed read as empty.
    #[serde(default)]
    warnings: Vec<VerifyWarning>,
    #[serde(flatten)]
    report: VerificationReport,
}

#[derive(Serialize, utoipa::ToSchema)]
struct TransformResponse {
    #[schema(minimum = 2)]
    revision: u64,
    source: String,
}

#[utoipa::path(
    get,
    path = "/api/v1/healthz",
    tag = "System",
    responses((status = 200, body = HealthResponse))
)]
async fn healthz() -> Json<HealthResponse> {
    Json(HealthResponse { status: "ok" })
}

#[utoipa::path(
    post,
    path = "/api/v1/sessions/guest",
    tag = "Sessions",
    responses((
        status = 201,
        body = GuestSessionResponse,
        headers(("Set-Cookie" = String, description = "Opaque HttpOnly guest session cookie"))
    ))
)]
async fn create_guest_session(
    State(state): State<AppState>,
) -> Result<(StatusCode, HeaderMap, Json<GuestSessionResponse>), ApiError> {
    let new_session = session::create(&state.0.data_dir, state.0.secure_cookies).await?;
    let mut headers = HeaderMap::new();
    headers.insert(
        header::SET_COOKIE,
        HeaderValue::from_str(&new_session.cookie).map_err(|_| {
            ApiError::new(
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                "could not create session cookie",
            )
        })?,
    );
    Ok((
        StatusCode::CREATED,
        headers,
        Json(GuestSessionResponse { kind: "guest" }),
    ))
}

#[utoipa::path(
    post,
    path = "/api/v1/projects",
    tag = "Projects",
    security(("guestSession" = [])),
    request_body(content = inline(UploadRequest), content_type = "multipart/form-data"),
    responses(
        (status = 201, body = Project),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 413, body = ApiErrorBody)
    )
)]
async fn upload_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    multipart: Result<Multipart, MultipartRejection>,
) -> Result<(StatusCode, Json<Project>), ApiError> {
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let too_large = || {
        ApiError::new(
            StatusCode::PAYLOAD_TOO_LARGE,
            "project_too_large",
            "uploaded source exceeds the configured size limit",
        )
    };
    let mut multipart = multipart.map_err(|error| {
        if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
            too_large()
        } else {
            ApiError::bad_request("invalid multipart upload")
        }
    })?;
    let mut upload = None;
    while let Some(mut field) = multipart
        .next_field()
        .await
        .map_err(|_| ApiError::bad_request("invalid multipart upload"))?
    {
        if field.name() != Some("file") || upload.is_some() {
            return Err(ApiError::bad_request(
                "upload must contain exactly one field named file",
            ));
        }
        let raw_name = field
            .file_name()
            .ok_or_else(|| ApiError::bad_request("file must include a filename"))?
            .rsplit(['/', '\\'])
            .next()
            .unwrap_or_default()
            .to_owned();
        let is_archive = raw_name.to_ascii_lowercase().ends_with(".zip");
        let limit = if is_archive {
            state.0.import_limits.max_archive_bytes
        } else {
            state.0.max_project_bytes
        };
        let filename = if is_archive {
            normalize_archive_filename(&raw_name)?
        } else {
            normalize_java_filename(&raw_name)?
        };

        let mut bytes = Vec::new();
        while let Some(chunk) = field
            .chunk()
            .await
            .map_err(|_| ApiError::bad_request("invalid multipart upload"))?
        {
            if bytes.len().saturating_add(chunk.len()) > limit {
                return Err(too_large());
            }
            bytes.extend_from_slice(&chunk);
        }
        if is_archive {
            if !archive::is_zip(&bytes) {
                return Err(ApiError::new(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "invalid_archive",
                    "not a readable ZIP archive",
                ));
            }
        } else {
            std::str::from_utf8(&bytes)
                .map_err(|_| ApiError::bad_request("Java source must be valid UTF-8"))?;
        }
        upload = Some((filename, bytes, is_archive));
    }
    let (filename, bytes, is_archive) =
        upload.ok_or_else(|| ApiError::bad_request("missing file field"))?;
    let project = if is_archive {
        let limits = state.0.import_limits.clone();
        // Decompression is CPU-bound; keep it off the async workers.
        let extracted = tokio::task::spawn_blocking(move || archive::extract(&bytes, &limits))
            .await
            .map_err(|_| {
                ApiError::new(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal_error",
                    "archive import failed",
                )
            })??;
        let _guard = state.0.writes.lock().await;
        project::create_archive(&session_dir, &filename, extracted).await?
    } else {
        let _guard = state.0.writes.lock().await;
        project::create(&session_dir, &filename, &bytes).await?
    };
    Ok((StatusCode::CREATED, Json(project)))
}

fn normalize_archive_filename(value: &str) -> Result<String, ApiError> {
    if value.len() <= ".zip".len() || value.contains('\0') || value == ".." {
        return Err(ApiError::bad_request("invalid archive filename"));
    }
    Ok(value.to_owned())
}

fn normalize_java_filename(value: &str) -> Result<String, ApiError> {
    let filename = value.rsplit(['/', '\\']).next().unwrap_or_default();
    if filename.is_empty()
        || filename == "."
        || filename == ".."
        || filename.contains('\0')
        || !filename.to_ascii_lowercase().ends_with(".java")
    {
        return Err(ApiError::bad_request("only one .java file is supported"));
    }
    Ok(filename.to_owned())
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, body = Project),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Json<Project>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, _) = project::load(&session_dir, project_id).await?;
    Ok(Json(project))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/source",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, body = String, content_type = "text/plain", headers(("X-Source-Revision" = u64))),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_project_source(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Response, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if project::is_archive(&project) {
        return Err(project::multi_file_unsupported("reading /source"));
    }
    let source = project::read_source(&directory, project.revision, &project.filename).await?;
    let mut response = Response::new(Body::from(source));
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; charset=utf-8"),
    );
    response.headers_mut().insert(
        "x-source-revision",
        HeaderValue::from_str(&project.revision.to_string()).expect("integer header is valid"),
    );
    Ok(response)
}

#[utoipa::path(
    put,
    path = "/api/v1/projects/{project_id}/source",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = UpdateSourceRequest,
    responses(
        (status = 200, body = Project),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody),
        (status = 413, body = ApiErrorBody)
    )
)]
async fn update_project_source(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<UpdateSourceRequest>, JsonRejection>,
) -> Result<Json<Project>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid source update"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    if request.source.len() > state.0.max_project_bytes {
        return Err(ApiError::bad_request(
            "source exceeds the configured size limit",
        ));
    }
    let _guard = state.0.writes.lock().await;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if project::is_archive(&project) {
        return Err(project::multi_file_unsupported("writing /source"));
    }
    Ok(Json(project::update(&directory, project, request).await?))
}

#[derive(Debug, Deserialize, utoipa::IntoParams)]
struct RevisionQuery {
    /// Defaults to the current revision.
    revision: Option<u64>,
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/files",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), RevisionQuery),
    responses(
        (status = 200, body = Manifest),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn list_project_files(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    Query(query): Query<RevisionQuery>,
) -> Result<Json<Manifest>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let revision = query.revision.unwrap_or(project.revision);
    Ok(Json(
        project::load_manifest(&directory, &project, revision).await?,
    ))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/files/{path}",
    tag = "Projects",
    security(("guestSession" = [])),
    params(
        ("project_id" = Uuid, Path),
        ("path" = String, Path, description = "Archive-relative file path, e.g. src/main/java/app/Main.java"),
        RevisionQuery
    ),
    responses(
        (status = 200, body = String, content_type = "text/plain", headers(("X-Source-Revision" = u64))),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_project_file(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, path)): Path<(String, String)>,
    Query(query): Query<RevisionQuery>,
) -> Result<Response, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let path = archive::validate_request_path(&path)?;
    let revision = query.revision.unwrap_or(project.revision);
    let source = project::read_file(&directory, &project, revision, &path).await?;
    let mut response = Response::new(Body::from(source));
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; charset=utf-8"),
    );
    response.headers_mut().insert(
        "x-source-revision",
        HeaderValue::from_str(&revision.to_string()).expect("integer header is valid"),
    );
    Ok(response)
}

#[utoipa::path(
    put,
    path = "/api/v1/projects/{project_id}/files/{path}",
    tag = "Projects",
    security(("guestSession" = [])),
    params(
        ("project_id" = Uuid, Path),
        ("path" = String, Path, description = "Path of an existing file")
    ),
    request_body = UpdateFileRequest,
    responses(
        (status = 200, body = Project),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody),
        (status = 413, body = ApiErrorBody)
    )
)]
async fn update_project_file(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, path)): Path<(String, String)>,
    request: Result<Json<UpdateFileRequest>, JsonRejection>,
) -> Result<Json<Project>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid file update"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let path = archive::validate_request_path(&path)?;
    if request.source.len() > state.0.max_project_bytes {
        return Err(ApiError::new(
            StatusCode::PAYLOAD_TOO_LARGE,
            "project_too_large",
            "source exceeds the configured size limit",
        ));
    }
    let _guard = state.0.writes.lock().await;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    Ok(Json(
        project::update_file(
            &directory,
            project,
            &path,
            request.source.as_bytes(),
            request.expected_revision,
        )
        .await?,
    ))
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/analyze",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), AnalyzeQuery),
    responses(
        (status = 200, body = AnalysisResult),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn analyze_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    Query(query): Query<AnalyzeQuery>,
) -> Result<Json<AnalysisResult>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if project::is_archive(&project) {
        let files = java_files(&directory, &project).await?;
        return Ok(Json(
            state
                .0
                .analyzer
                .analyze_project(
                    directory,
                    project.revision,
                    &files,
                    query
                        .source_level
                        .as_deref()
                        .unwrap_or(DEFAULT_SOURCE_LEVEL),
                )
                .await?,
        ));
    }
    Ok(Json(
        state
            .0
            .analyzer
            .analyze(directory, project.revision, &project.filename)
            .await?,
    ))
}

const DEFAULT_SOURCE_LEVEL: &str = "21";

#[derive(Debug, Deserialize, utoipa::IntoParams)]
struct AnalyzeQuery {
    /// Java language level of the sources: 8, 11, 17 or 21 (default). Archive projects only.
    source_level: Option<String>,
}

async fn java_files(
    directory: &std::path::Path,
    project: &Project,
) -> Result<Vec<String>, ApiError> {
    let manifest = project::load_manifest(directory, project, project.revision).await?;
    Ok(manifest
        .files
        .into_iter()
        .filter(|file| file.kind == archive::FileKind::Java)
        .map(|file| file.path)
        .collect())
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/analyze/stream",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), AnalyzeQuery),
    responses(
        (status = 200, description = "Server-sent analysis lifecycle events", content_type = "text/event-stream"),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn analyze_project_stream(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    Query(query): Query<AnalyzeQuery>,
) -> Result<Response, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let revision = project.revision;
    let archive_files = if project::is_archive(&project) {
        Some(java_files(&directory, &project).await?)
    } else {
        None
    };
    let source_level = query
        .source_level
        .unwrap_or_else(|| DEFAULT_SOURCE_LEVEL.to_owned());
    let filename = project.filename;
    let analyzer = state.0.analyzer.clone();
    let (sender, receiver) = mpsc::channel::<Result<String, Infallible>>(4);

    tokio::spawn(async move {
        if sender
            .send(Ok(format!(
                "event: started\ndata: {{\"revision\":{revision}}}\n\n"
            )))
            .await
            .is_err()
        {
            return;
        }
        if sender
            .send(Ok(format!(
                "event: progress\ndata: {{\"stage\":\"analyzing\",\"revision\":{revision}}}\n\n"
            )))
            .await
            .is_err()
        {
            return;
        }

        let analysis = match &archive_files {
            Some(files) => {
                analyzer
                    .analyze_project(directory, revision, files, &source_level)
                    .await
            }
            None => analyzer.analyze(directory, revision, &filename).await,
        };
        let event = match analysis {
            Ok(result) => format!(
                "event: completed\ndata: {}\n\n",
                serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_owned())
            ),
            Err(error) => format!(
                "event: failed\ndata: {}\n\n",
                serde_json::json!({
                    "code": error.code,
                    "message": error.message,
                    "revision": revision,
                })
            ),
        };
        let _ = sender.send(Ok(event)).await;
    });

    Ok(Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/event-stream")
        .header(header::CACHE_CONTROL, "no-cache, no-transform")
        .header(header::CONNECTION, "keep-alive")
        .body(Body::from_stream(ReceiverStream::new(receiver)))
        .expect("SSE response headers are valid"))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/revisions",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, body = [RevisionSummary]),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn list_project_revisions(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Json<Vec<RevisionSummary>>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    Ok(Json(
        project::list_revisions(
            &directory,
            project.revision,
            &project.filename,
            project::is_archive(&project),
        )
        .await?,
    ))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/revisions/{revision}",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), ("revision" = u64, Path)),
    responses(
        (status = 200, body = RevisionDetail),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_project_revision(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, revision)): Path<(String, u64)>,
) -> Result<Json<RevisionDetail>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if project::is_archive(&project) {
        return Err(project::multi_file_unsupported("revision source detail"));
    }
    Ok(Json(
        project::read_revision(&directory, revision, project.revision, &project.filename).await?,
    ))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/methods",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, body = MethodsResponse),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn list_methods(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Json<MethodsResponse>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let artifact = load_analysis(&directory, project.revision).await?;
    let mut methods = Vec::new();
    visit_cfg_methods(&artifact, &mut |member, cfg| {
        if let Some(method) = cfg_method(member, cfg) {
            methods.push(method);
        }
    });
    Ok(Json(MethodsResponse {
        revision: project.revision,
        methods,
    }))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/methods/{method_selector}/cfg",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(
        ("project_id" = Uuid, Path),
        ("method_selector" = String, Path, description = "Opaque revision-local selector returned by the methods endpoint")
    ),
    responses(
        (status = 200, body = Cfg),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_method_cfg(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, method_selector)): Path<(String, String)>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let artifact = load_analysis(&directory, project.revision).await?;
    let mut found = None;
    visit_cfg_methods(&artifact, &mut |_member, cfg| {
        if found.is_none() && cfg_selector(cfg) == Some(method_selector.as_str()) {
            found = Some(cfg.clone());
        }
    });
    found.map(Json).ok_or_else(method_not_found)
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/methods/{method_selector}/analysis",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(
        ("project_id" = Uuid, Path),
        ("method_selector" = String, Path, description = "Opaque revision-local selector returned by the methods endpoint")
    ),
    responses(
        (status = 200, body = MethodAnalysisResponse),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody)
    )
)]
async fn get_method_analysis(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, method_selector)): Path<(String, String)>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let artifact = load_analysis(&directory, project.revision).await?;
    let mut method = None;
    visit_cfg_methods(&artifact, &mut |member, cfg| {
        if method.is_none() && cfg_selector(cfg) == Some(method_selector.as_str()) {
            method = Some(member);
        }
    });
    let member = method.ok_or_else(method_not_found)?;
    let analysis = member
        .get("analysis")
        .filter(|analysis| analysis.is_object())
        .ok_or_else(method_analysis_not_found)?;
    let method_id = member
        .get("id")
        .and_then(Value::as_str)
        .ok_or_else(method_not_found)?;

    Ok(Json(serde_json::json!({
        "revision": project.revision,
        "method_id": method_id,
        "analysis": analysis,
    })))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/graph",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (
            status = 200,
            description = "Project graph persisted by the Java parser for the current published revision. `graph` is JSON null when the parser could not build the graph (see the `project_graph_failed` diagnostic)",
            body = ProjectGraphResponse
        ),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody),
        (status = 500, body = ApiErrorBody)
    )
)]
async fn get_project_graph(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let artifact = load_analysis(&directory, project.revision).await?;
    let graph = artifact.get("project_graph").ok_or_else(graph_invalid)?;
    if !graph.is_null() && !graph.is_object() {
        return Err(graph_invalid());
    }
    Ok(Json(serde_json::json!({
        "revision": project.revision,
        "graph": graph,
    })))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/complexity",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, description = "Complexity estimates only; code smells are under /findings", body = ComplexityReport),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody)
    )
)]
async fn get_project_complexity(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Json<ComplexityReport>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let artifact = load_analysis(&directory, project.revision).await?;
    let mut methods = Vec::new();
    visit_cfg_methods(&artifact, &mut |member, cfg| {
        if let Some(entry) = method_complexity(member, cfg) {
            methods.push(entry);
        }
    });
    Ok(Json(ComplexityReport {
        revision: project.revision,
        methods,
    }))
}

fn method_complexity(member: &Value, cfg: &Value) -> Option<MethodComplexity> {
    let complexity = member.pointer("/analysis/complexity")?;
    let strings = |value: Option<&Value>| -> Vec<String> {
        value
            .and_then(Value::as_array)
            .map(|items| {
                items
                    .iter()
                    .filter_map(|item| item.as_str().map(str::to_owned))
                    .collect()
            })
            .unwrap_or_default()
    };
    let mut cfg_node_ids = Vec::new();
    for fragment in member
        .pointer("/analysis/fragments")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        for id in strings(fragment.get("node_ids")) {
            if !cfg_node_ids.contains(&id) {
                cfg_node_ids.push(id);
            }
        }
    }
    if cfg_node_ids.is_empty() {
        if let Some(entry) = cfg.get("entry").and_then(Value::as_str) {
            cfg_node_ids.push(entry.to_owned());
        }
    }
    let source_range: SourceRange =
        serde_json::from_value(member.get("source_range")?.clone()).ok()?;
    Some(MethodComplexity {
        method_id: member.get("id")?.as_str()?.to_owned(),
        file: source_range.file.clone(),
        time_complexity: complexity.get("time")?.as_str()?.to_owned(),
        space_complexity: None,
        confidence: complexity.get("confidence")?.as_str()?.to_owned(),
        analysis_engine: complexity
            .get("engine")
            .and_then(Value::as_str)
            .unwrap_or("jdt-static-cost-model")
            .to_owned(),
        reason: complexity
            .get("reason")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned(),
        assumptions: strings(complexity.get("assumptions")),
        limitations: strings(complexity.get("limitations")),
        source_range,
        cfg_node_ids,
    })
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/findings",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, description = "Code smells and optimization findings, separate from complexity estimates and from transformations", body = FindingsReport),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody)
    )
)]
async fn get_project_findings(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let artifact = load_analysis(&directory, project.revision).await?;
    let mut findings = Vec::new();
    visit_cfg_methods(&artifact, &mut |member, _cfg| {
        let Some(method_id) = member.get("id").and_then(Value::as_str) else {
            return;
        };
        let file = member
            .pointer("/source_range/file")
            .cloned()
            .unwrap_or(Value::Null);
        for finding in member
            .pointer("/analysis/findings")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            findings.push(serde_json::json!({
                "method_id": method_id,
                "file": file,
                "finding": finding,
            }));
        }
    });
    Ok(Json(serde_json::json!({
        "revision": project.revision,
        "findings": findings,
    })))
}

fn parse_proposal_id(value: &str) -> Result<&str, ApiError> {
    let valid = value.len() == 20
        && value.starts_with("opt_")
        && value[4..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte));
    if valid {
        Ok(value)
    } else {
        Err(ApiError::bad_request("malformed proposal id"))
    }
}

fn level_or_default(level: &Option<String>) -> &str {
    level.as_deref().unwrap_or(DEFAULT_SOURCE_LEVEL)
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/optimizations",
    tag = "Optimization",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), AnalyzeQuery),
    responses(
        (status = 200, description = "Optimization candidates of the current revision, applicable or not, each with its preconditions", body = OptimizationList),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn list_optimizations(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    Query(query): Query<AnalyzeQuery>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let files = java_files(&directory, &project).await?;
    let run = state
        .0
        .analyzer
        .optimize(
            &directory,
            project.revision,
            &files,
            level_or_default(&query.source_level),
            OptimizeRequest::Discover,
        )
        .await?;
    Ok(Json(serde_json::json!({
        "revision": project.revision,
        "rule": run.result.get("rule").cloned().unwrap_or(Value::Null),
        "proposals": run.result.get("proposals").cloned().unwrap_or(Value::Array(Vec::new())),
    })))
}

/// A rewritten project built by the optimizer, compared with the base revision.
struct Candidate {
    run: crate::analyzer::OptimizationRun,
    changes: Vec<(String, Vec<u8>)>,
    diff: Vec<FileDiff>,
}

async fn build_candidate(
    state: &AppState,
    directory: &std::path::Path,
    project: &Project,
    proposal_id: &str,
    source_level: &str,
    acknowledged: &[String],
    preview: bool,
) -> Result<Candidate, ApiError> {
    let files = java_files(directory, project).await?;
    let run = state
        .0
        .analyzer
        .optimize(
            directory,
            project.revision,
            &files,
            source_level,
            OptimizeRequest::Apply {
                proposal_id,
                acknowledged,
                preview,
            },
        )
        .await?;
    candidate_from_run(state, directory, project, &files, run).await
}

/// Turns the output of a successful `optimize`/`edit` run into a candidate: reads the changed files from the
/// candidate tree and diffs them against the base revision.
async fn candidate_from_run(
    state: &AppState,
    directory: &std::path::Path,
    project: &Project,
    files: &[String],
    run: crate::analyzer::OptimizationRun,
) -> Result<Candidate, ApiError> {
    let invalid = || {
        ApiError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "optimizer_invalid_output",
            "Java optimizer returned an invalid response",
        )
    };
    let changed: Vec<String> = run
        .result
        .get("changed_files")
        .and_then(Value::as_array)
        .ok_or_else(invalid)?
        .iter()
        .filter_map(|v| v.as_str().map(str::to_owned))
        .collect();
    let mut changes = Vec::new();
    let mut diff = Vec::new();
    for path in changed {
        if !files.contains(&path) {
            return Err(invalid());
        }
        let after = fs::read(run.tree.join(&path))
            .await
            .map_err(|_| invalid())?;
        if after.len() > state.0.max_project_bytes {
            return Err(invalid());
        }
        let before = project::read_file(directory, project, project.revision, &path).await?;
        let (before_text, after_text) = (
            String::from_utf8_lossy(&before).into_owned(),
            String::from_utf8(after.clone()).map_err(|_| invalid())?,
        );
        if let Some(file_diff) = diff::diff_file(&path, Some(&before_text), Some(&after_text)) {
            diff.push(file_diff);
            changes.push((path, after));
        }
    }
    if changes.is_empty() {
        return Err(invalid());
    }
    Ok(Candidate { run, changes, diff })
}

/// Re-analyses the candidate exactly as the stored revision would be analysed.
async fn analyze_candidate(
    state: &AppState,
    project: &Project,
    candidate: &Candidate,
    source_level: &str,
    files: &[String],
) -> Result<(AnalysisResult, Vec<u8>), ApiError> {
    let next = project.revision + 1;
    if project::is_archive(project) {
        state
            .0
            .analyzer
            .analyze_tree(
                &candidate.run.tree,
                &candidate.run.stage,
                next,
                files,
                source_level,
            )
            .await
    } else {
        state
            .0
            .analyzer
            .analyze_file(
                &candidate.run.tree.join(&project.filename),
                &candidate.run.stage,
                next,
            )
            .await
    }
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/optimizations/{proposal_id}/preview",
    tag = "Optimization",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), ("proposal_id" = String, Path)),
    request_body = OptimizationPreviewRequest,
    responses(
        (status = 200, description = "Exact source diff of the rewrite, re-parsed and validated. Nothing is stored.", body = OptimizationPreview),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody),
        (status = 422, description = "optimization_not_applicable or transformation_invalid", body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn preview_optimization(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, proposal_id)): Path<(String, String)>,
    request: Result<Json<OptimizationPreviewRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let proposal_id = parse_proposal_id(&proposal_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid preview request"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if request.base_revision != project.revision {
        return Err(revision_conflict_error(
            request.base_revision,
            project.revision,
        ));
    }
    let level = level_or_default(&request.source_level);
    let candidate =
        build_candidate(&state, &directory, &project, proposal_id, level, &[], true).await?;
    let files = java_files(&directory, &project).await?;
    // the candidate must analyse as a revision; this also proves the preview is what apply would store
    analyze_candidate(&state, &project, &candidate, level, &files).await?;
    Ok(Json(serde_json::json!({
        "base_revision": project.revision,
        "proposal": candidate.run.result.get("proposal").cloned().unwrap_or(Value::Null),
        "diff": candidate.diff,
        "preview_sha256": diff::fingerprint(&candidate.diff),
        "method_complexity_before": candidate.run.result.get("method_complexity_before").cloned().unwrap_or(Value::Null),
        "method_complexity_after": candidate.run.result.get("method_complexity_after").cloned().unwrap_or(Value::Null),
        "validation": "valid",
    })))
}

fn revision_conflict_error(expected: u64, current: u64) -> ApiError {
    ApiError::new(
        StatusCode::CONFLICT,
        "revision_conflict",
        format!("expected revision {expected} but current revision is {current}"),
    )
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/optimizations/{proposal_id}/apply",
    tag = "Optimization",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), ("proposal_id" = String, Path)),
    request_body = OptimizationApplyRequest,
    responses(
        (status = 200, description = "A new revision holding the rewrite, re-analysed. Roll back with POST /rollback.", body = OptimizationApplyResponse),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, description = "revision_conflict or preview_mismatch", body = ApiErrorBody),
        (status = 422, description = "optimization_not_applicable, assumptions_not_acknowledged or transformation_invalid", body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn apply_optimization(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, proposal_id)): Path<(String, String)>,
    request: Result<Json<OptimizationApplyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let proposal_id = parse_proposal_id(&proposal_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid apply request"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let _guard = state.0.writes.lock().await;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if request.base_revision != project.revision {
        return Err(revision_conflict_error(
            request.base_revision,
            project.revision,
        ));
    }
    let level = level_or_default(&request.source_level);
    let candidate = build_candidate(
        &state,
        &directory,
        &project,
        proposal_id,
        level,
        &request.acknowledged_assumptions,
        false,
    )
    .await?;
    if diff::fingerprint(&candidate.diff) != request.preview_sha256 {
        return Err(ApiError::new(
            StatusCode::CONFLICT,
            "preview_mismatch",
            "the rewrite no longer matches the previewed diff; preview it again",
        ));
    }
    let files = java_files(&directory, &project).await?;
    let (analysis, artifact) =
        analyze_candidate(&state, &project, &candidate, level, &files).await?;
    let new_project = project::commit_changes(
        &directory,
        project,
        request.base_revision,
        &candidate.changes,
        Some(&artifact),
    )
    .await?;
    Ok(Json(serde_json::json!({
        "revision": new_project.revision,
        "base_revision": request.base_revision,
        "proposal": candidate.run.result.get("proposal").cloned().unwrap_or(Value::Null),
        "diff": candidate.diff,
        "method_complexity_before": candidate.run.result.get("method_complexity_before").cloned().unwrap_or(Value::Null),
        "method_complexity_after": candidate.run.result.get("method_complexity_after").cloned().unwrap_or(Value::Null),
        "analysis_status": analysis.status,
    })))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/decomposition",
    tag = "Decomposition",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), AnalyzeQuery),
    responses(
        (status = 200, description = "Static member types of the current revision that can move into files of their own, each with its preconditions", body = DecompositionList),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn list_decomposition(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    Query(query): Query<AnalyzeQuery>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let files = java_files(&directory, &project).await?;
    let run = state
        .0
        .analyzer
        .decompose(
            &directory,
            project.revision,
            &files,
            level_or_default(&query.source_level),
            DecomposeRequest::Discover,
        )
        .await?;
    Ok(Json(serde_json::json!({
        "revision": project.revision,
        "rule": run.result.get("rule").cloned().unwrap_or(Value::Null),
        "candidates": run.result.get("candidates").cloned().unwrap_or(Value::Array(Vec::new())),
    })))
}

/// A split project built by the decomposer, compared with the base revision.
struct Split {
    run: crate::analyzer::OptimizationRun,
    changes: Vec<(String, Vec<u8>)>,
    additions: Vec<(String, Vec<u8>)>,
    diff: Vec<FileDiff>,
    files: Vec<String>,
}

fn safe_new_path(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && path.ends_with(".java")
        && path
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

async fn build_split(
    state: &AppState,
    directory: &std::path::Path,
    project: &Project,
    classes: &[String],
    source_level: &str,
) -> Result<Split, ApiError> {
    let invalid = || {
        ApiError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "decomposer_invalid_output",
            "Java decomposer returned an invalid response",
        )
    };
    let files = java_files(directory, project).await?;
    let run = state
        .0
        .analyzer
        .decompose(
            directory,
            project.revision,
            &files,
            source_level,
            DecomposeRequest::Apply { classes },
        )
        .await?;
    let listed = |key: &str| -> Result<Vec<String>, ApiError> {
        Ok(run
            .result
            .get(key)
            .and_then(Value::as_array)
            .ok_or_else(invalid)?
            .iter()
            .filter_map(|v| v.as_str().map(str::to_owned))
            .collect())
    };
    let (changed, added) = (listed("changed_files")?, listed("added_files")?);
    let mut changes = Vec::new();
    let mut additions = Vec::new();
    let mut diff = Vec::new();
    for path in changed {
        if !files.contains(&path) {
            return Err(invalid());
        }
        let after = fs::read(run.tree.join(&path))
            .await
            .map_err(|_| invalid())?;
        if after.len() > state.0.max_project_bytes {
            return Err(invalid());
        }
        let before = project::read_file(directory, project, project.revision, &path).await?;
        let after_text = String::from_utf8(after.clone()).map_err(|_| invalid())?;
        if let Some(file_diff) = diff::diff_file(
            &path,
            Some(&String::from_utf8_lossy(&before)),
            Some(&after_text),
        ) {
            diff.push(file_diff);
            changes.push((path, after));
        }
    }
    for path in added {
        if files.contains(&path) || !safe_new_path(&path) {
            return Err(invalid());
        }
        let after = fs::read(run.tree.join(&path))
            .await
            .map_err(|_| invalid())?;
        if after.len() > state.0.max_project_bytes {
            return Err(invalid());
        }
        let after_text = String::from_utf8(after.clone()).map_err(|_| invalid())?;
        if let Some(file_diff) = diff::diff_file(&path, None, Some(&after_text)) {
            diff.push(file_diff);
            additions.push((path, after));
        }
    }
    if additions.is_empty() {
        return Err(invalid());
    }
    let mut all_files = files;
    all_files.extend(additions.iter().map(|(path, _)| path.clone()));
    Ok(Split {
        run,
        changes,
        additions,
        diff,
        files: all_files,
    })
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/decomposition/preview",
    tag = "Decomposition",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = DecompositionRequest,
    responses(
        (status = 200, description = "Exact diffs of the split (rewritten host file and new files), re-analysed and validated. Nothing is stored.", body = DecompositionPreview),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, description = "project or class not found", body = ApiErrorBody),
        (status = 409, body = ApiErrorBody),
        (status = 422, description = "not_extractable, dependency_not_selected, nothing_to_extract or transformation_invalid", body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn preview_decomposition(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<DecompositionRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) =
        request.map_err(|_| ApiError::bad_request("invalid decomposition request"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if request.base_revision != project.revision {
        return Err(revision_conflict_error(
            request.base_revision,
            project.revision,
        ));
    }
    let level = level_or_default(&request.source_level);
    let split = build_split(&state, &directory, &project, &request.classes, level).await?;
    Ok(Json(serde_json::json!({
        "base_revision": project.revision,
        "extracted": json_field(&split.run, "extracted"),
        "diff": split.diff,
        "preview_sha256": diff::fingerprint(&split.diff),
        "added_files": json_field(&split.run, "added_files"),
        "changed_files": json_field(&split.run, "changed_files"),
        "host_lines": json_field(&split.run, "host_lines"),
        "validation": "valid",
    })))
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/decomposition/apply",
    tag = "Decomposition",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = DecompositionApplyRequest,
    responses(
        (status = 200, description = "A new revision holding the split project (re-analysed). A single-file project becomes an archive project; roll back with POST /rollback.", body = DecompositionApplyResponse),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, description = "revision_conflict or preview_mismatch", body = ApiErrorBody),
        (status = 422, body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn apply_decomposition(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<DecompositionApplyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) =
        request.map_err(|_| ApiError::bad_request("invalid decomposition request"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let _guard = state.0.writes.lock().await;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if request.base_revision != project.revision {
        return Err(revision_conflict_error(
            request.base_revision,
            project.revision,
        ));
    }
    let level = level_or_default(&request.source_level);
    let split = build_split(&state, &directory, &project, &request.classes, level).await?;
    if diff::fingerprint(&split.diff) != request.preview_sha256 {
        return Err(ApiError::new(
            StatusCode::CONFLICT,
            "preview_mismatch",
            "the split no longer matches the previewed diff; preview it again",
        ));
    }
    let (analysis, artifact) = state
        .0
        .analyzer
        .analyze_tree(
            &split.run.tree,
            &split.run.stage,
            project.revision + 1,
            &split.files,
            level,
        )
        .await?;
    let new_project = project::commit_decomposition(
        &directory,
        project,
        request.base_revision,
        &split.changes,
        &split.additions,
        Some(&artifact),
    )
    .await?;
    Ok(Json(serde_json::json!({
        "revision": new_project.revision,
        "base_revision": request.base_revision,
        "extracted": json_field(&split.run, "extracted"),
        "diff": split.diff,
        "analysis_status": analysis.status,
        "project_kind": new_project.kind,
        "file_count": new_project.file_count,
    })))
}

async fn build_edit_candidate(
    state: &AppState,
    directory: &std::path::Path,
    project: &Project,
    source_level: &str,
    method_id: &str,
    operation: &CfgEditOperation,
) -> Result<Candidate, ApiError> {
    let files = java_files(directory, project).await?;
    let operation_json = match operation {
        CfgEditOperation::MoveStatement {
            node_id,
            target_node_id,
            position,
        } => serde_json::json!({
            "type": "move_statement",
            "node_id": node_id,
            "target_node_id": target_node_id,
            "position": position,
        }),
    };
    let run = state
        .0
        .analyzer
        .edit(
            directory,
            project.revision,
            &files,
            !project::is_archive(project),
            source_level,
            method_id,
            &operation_json,
        )
        .await?;
    candidate_from_run(state, directory, project, &files, run).await
}

fn json_field(run: &crate::analyzer::OptimizationRun, key: &str) -> Value {
    run.result.get(key).cloned().unwrap_or(Value::Null)
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/cfg-edits/preview",
    tag = "Transformations",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = CfgEditRequest,
    responses(
        (status = 200, description = "Exact Java diff of the edit, re-parsed and validated; nothing is stored", body = CfgEditPreview),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, description = "project or method not found", body = ApiErrorBody),
        (status = 409, body = ApiErrorBody),
        (status = 422, description = "move_rejected (with `details.reasons`), unsupported_node, no_change or transformation_invalid", body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn preview_cfg_edit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<CfgEditRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid CFG edit request"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if request.base_revision != project.revision {
        return Err(revision_conflict_error(
            request.base_revision,
            project.revision,
        ));
    }
    let candidate = build_edit_candidate(
        &state,
        &directory,
        &project,
        level_or_default(&request.source_level),
        &request.method_id,
        &request.operation,
    )
    .await?;
    Ok(Json(serde_json::json!({
        "base_revision": project.revision,
        "method_id": request.method_id,
        "operation": json_field(&candidate.run, "operation"),
        "diff": candidate.diff,
        "preview_sha256": diff::fingerprint(&candidate.diff),
        "node_mapping": json_field(&candidate.run, "node_mapping"),
    })))
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/cfg-edits/apply",
    tag = "Transformations",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = CfgEditApplyRequest,
    responses(
        (status = 200, description = "A new revision with the edit applied and re-analysed (CFG and source mappings are recomputed)", body = CfgEditApplyResponse),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, description = "revision_conflict or preview_mismatch", body = ApiErrorBody),
        (status = 422, description = "move_rejected, unsupported_node, no_change or transformation_invalid", body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn apply_cfg_edit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<CfgEditApplyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid CFG edit request"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let _guard = state.0.writes.lock().await;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if request.base_revision != project.revision {
        return Err(revision_conflict_error(
            request.base_revision,
            project.revision,
        ));
    }
    let level = level_or_default(&request.source_level);
    let candidate = build_edit_candidate(
        &state,
        &directory,
        &project,
        level,
        &request.method_id,
        &request.operation,
    )
    .await?;
    if diff::fingerprint(&candidate.diff) != request.preview_sha256 {
        return Err(ApiError::new(
            StatusCode::CONFLICT,
            "preview_mismatch",
            "the edit no longer matches the previewed diff; preview it again",
        ));
    }
    let files = java_files(&directory, &project).await?;
    let (analysis, artifact) =
        analyze_candidate(&state, &project, &candidate, level, &files).await?;
    let new_project = project::commit_changes(
        &directory,
        project,
        request.base_revision,
        &candidate.changes,
        Some(&artifact),
    )
    .await?;
    Ok(Json(serde_json::json!({
        "revision": new_project.revision,
        "base_revision": request.base_revision,
        "method_id": request.method_id,
        "operation": json_field(&candidate.run, "operation"),
        "diff": candidate.diff,
        "node_mapping": json_field(&candidate.run, "node_mapping"),
        "analysis_status": analysis.status,
    })))
}

fn validate_benchmark_request(
    request: &BenchmarkRequest,
) -> Result<BenchmarkSettingsReport, ApiError> {
    const MAX_WORKLOADS: usize = 8;
    const MAX_STDIN_BYTES: usize = 64 * 1024;
    if request.baseline_revision == 0 || request.candidate_revision == 0 {
        return Err(ApiError::bad_request("revision numbers must be positive"));
    }
    if request.workloads.is_empty() || request.workloads.len() > MAX_WORKLOADS {
        return Err(ApiError::bad_request(format!(
            "workloads must contain between 1 and {MAX_WORKLOADS} entries"
        )));
    }
    let mut names = HashSet::new();
    let mut total = 0_usize;
    for workload in &request.workloads {
        if workload.name.trim().is_empty() || workload.name.len() > 64 {
            return Err(ApiError::bad_request(
                "workload names must contain 1 to 64 bytes",
            ));
        }
        if !names.insert(workload.name.as_str()) {
            return Err(ApiError::bad_request("workload names must be unique"));
        }
        if workload.stdin.len() > MAX_STDIN_BYTES {
            return Err(ApiError::bad_request(
                "a workload stdin exceeds the 65536-byte limit",
            ));
        }
        total += workload.stdin.len();
    }
    if total > 4 * MAX_STDIN_BYTES {
        return Err(ApiError::bad_request(
            "combined workload stdin exceeds the 262144-byte limit",
        ));
    }
    let warmup = request.warmup_iterations.unwrap_or(5);
    let measured = request.measured_iterations.unwrap_or(15);
    if warmup > 50 || measured == 0 || measured > 100 {
        return Err(ApiError::bad_request(
            "warmup_iterations must be 0-50 and measured_iterations 1-100",
        ));
    }
    let timeout = request.iteration_timeout_ms.unwrap_or(10_000);
    if timeout == 0 || timeout > 60_000 {
        return Err(ApiError::bad_request(
            "iteration_timeout_ms must be 1-60000",
        ));
    }
    let level = level_or_default(&request.source_level).to_owned();
    crate::analyzer::check_source_level(&level)?;
    Ok(BenchmarkSettingsReport {
        warmup_iterations: warmup,
        measured_iterations: measured,
        class_loading: request.class_loading.unwrap_or_default(),
        iteration_timeout_ms: timeout,
        source_level: level,
        main_class: request.main_class.clone(),
    })
}

fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// The analysis artifact of a past revision, running the analyzer if that revision was never analysed.
async fn ensure_analysis(
    state: &AppState,
    directory: &std::path::Path,
    project: &Project,
    revision: u64,
    level: &str,
) -> Result<Value, ApiError> {
    match load_analysis(directory, revision).await {
        Ok(artifact) => return Ok(artifact),
        Err(error) if error.code == "analysis_not_found" => {}
        Err(error) => return Err(error),
    }
    if project::is_archive(project) {
        let manifest = project::load_manifest(directory, project, revision).await?;
        let files: Vec<String> = manifest
            .files
            .into_iter()
            .filter(|f| f.kind == archive::FileKind::Java)
            .map(|f| f.path)
            .collect();
        state
            .0
            .analyzer
            .analyze_project(directory.to_path_buf(), revision, &files, level)
            .await?;
    } else {
        state
            .0
            .analyzer
            .analyze(directory.to_path_buf(), revision, &project.filename)
            .await?;
    }
    load_analysis(directory, revision).await
}

fn complexity_by_method(artifact: &Value) -> std::collections::BTreeMap<String, (String, String)> {
    let mut map = std::collections::BTreeMap::new();
    visit_cfg_methods(artifact, &mut |member, _| {
        if let (Some(id), Some(time), Some(confidence)) = (
            member.get("id").and_then(Value::as_str),
            member
                .pointer("/analysis/complexity/time")
                .and_then(Value::as_str),
            member
                .pointer("/analysis/complexity/confidence")
                .and_then(Value::as_str),
        ) {
            map.insert(id.to_owned(), (time.to_owned(), confidence.to_owned()));
        }
    });
    map
}

fn static_complexity(before: &Value, after: &Value) -> StaticComplexity {
    let (a, b) = (complexity_by_method(before), complexity_by_method(after));
    let mut changes = Vec::new();
    let mut unchanged = 0;
    for (id, (time_before, confidence_before)) in &a {
        if let Some((time_after, confidence_after)) = b.get(id) {
            if time_before == time_after {
                unchanged += 1;
            } else {
                changes.push(ComplexityChange {
                    method_id: id.clone(),
                    before: time_before.clone(),
                    after: time_after.clone(),
                    confidence_before: confidence_before.clone(),
                    confidence_after: confidence_after.clone(),
                });
            }
        }
    }
    StaticComplexity {
        changes,
        unchanged_methods: unchanged,
        note: "Static estimates from the source of each revision (jdt-static-cost-model); they say nothing about measured time. `unknown` means no bound could be justified.".to_owned(),
    }
}

fn observation(lines: &[Value], index: usize) -> Option<benchmark::WorkloadObservation> {
    lines
        .iter()
        .find(|line| line.get("workload").and_then(Value::as_u64) == Some(index as u64))
        .and_then(|line| serde_json::from_value(line.clone()).ok())
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/benchmark",
    tag = "Verification",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = BenchmarkRequest,
    responses(
        (status = 200, description = "Reproducible before/after comparison. Static Big O, functional outcome and measured runtime are separate sections. A speedup is reported only when the evidence supports it.", body = BenchmarkReport),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 503, description = "runner_unavailable when no runner image is configured", body = ApiErrorBody)
    )
)]
async fn run_benchmark(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<BenchmarkRequest>, JsonRejection>,
) -> Result<Json<BenchmarkReport>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid benchmark request"))?;
    let settings = validate_benchmark_request(&request)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let main_class = resolve_main_class(
        &directory,
        &project,
        request.baseline_revision,
        request.main_class.as_deref(),
    )
    .await?;
    let baseline_target = compile_target(
        &directory,
        &project,
        request.baseline_revision,
        main_class.clone(),
        &settings.source_level,
    )
    .await?;
    let candidate_target = compile_target(
        &directory,
        &project,
        request.candidate_revision,
        main_class.clone(),
        &settings.source_level,
    )
    .await?;
    let stdin: Vec<Vec<u8>> = request
        .workloads
        .iter()
        .map(|w| w.stdin.clone().into_bytes())
        .collect();
    let run_settings = crate::runner::BenchmarkSettings {
        warmup_iterations: settings.warmup_iterations,
        measured_iterations: settings.measured_iterations,
        fresh_class_loader: matches!(settings.class_loading, ClassLoading::Fresh),
        iteration_timeout_ms: settings.iteration_timeout_ms,
    };
    // sequential on purpose: two JVMs measuring at once would compete for the same CPUs
    let baseline = state
        .0
        .runner
        .benchmark_revision(&baseline_target, &stdin, &run_settings)
        .await?;
    let candidate = state
        .0
        .runner
        .benchmark_revision(&candidate_target, &stdin, &run_settings)
        .await?;

    let compiled = baseline.compilation.status == crate::runner::CompilationStatus::Completed
        && candidate.compilation.status == crate::runner::CompilationStatus::Completed;
    let mut comparisons = Vec::new();
    for (index, workload) in request.workloads.iter().enumerate() {
        comparisons.push(benchmark::compare_workload(
            &workload.name,
            workload.size,
            sha256_hex(workload.stdin.as_bytes()),
            workload.stdin.len(),
            observation(&baseline.workloads, index),
            observation(&candidate.workloads, index),
        ));
    }
    let overall = benchmark::overall(&comparisons);
    let non_equivalent: Vec<String> = comparisons
        .iter()
        .filter(|c| c.equivalent == Some(false))
        .map(|c| c.name.clone())
        .collect();
    let incomplete = !compiled
        || baseline.timed_out
        || candidate.timed_out
        || comparisons
            .iter()
            .any(|c| c.verdict == benchmark::Verdict::Incomplete);

    let _guard = state.0.writes.lock().await;
    let static_result = {
        let before = ensure_analysis(
            &state,
            &directory,
            &project,
            request.baseline_revision,
            &settings.source_level,
        )
        .await;
        let after = ensure_analysis(
            &state,
            &directory,
            &project,
            request.candidate_revision,
            &settings.source_level,
        )
        .await;
        match (before, after) {
            (Ok(before), Ok(after)) => static_complexity(&before, &after),
            _ => StaticComplexity {
                changes: Vec::new(),
                unchanged_methods: 0,
                note: "Static analysis of one of the revisions was not available; no static comparison is reported.".to_owned(),
            },
        }
    };
    let report = BenchmarkReport {
        report_id: Uuid::new_v4(),
        created_at: OffsetDateTime::now_utc()
            .format(&Rfc3339)
            .expect("RFC 3339 supports all current UTC dates"),
        status: if !compiled {
            "compilation_failed"
        } else if incomplete {
            "incomplete"
        } else {
            "completed"
        }
        .to_owned(),
        baseline_revision: request.baseline_revision,
        candidate_revision: request.candidate_revision,
        settings: BenchmarkSettingsReport {
            main_class,
            ..settings
        },
        environment: BenchmarkEnvironment {
            runner_image: state.0.runner.image().map(str::to_owned),
            cpu_limit: baseline.limits.cpus.to_owned(),
            memory_limit: baseline.limits.memory.to_owned(),
            network: "none".to_owned(),
            baseline_jvm: baseline.environment.clone(),
            candidate_jvm: candidate.environment.clone(),
        },
        timings: PhaseTimings {
            baseline: RevisionTimings {
                compile_and_setup_ms: baseline.setup_ms,
                harness_setup_ms: baseline.harness_setup_ms,
                benchmark_process_ms: baseline.execution_ms,
            },
            candidate: RevisionTimings {
                compile_and_setup_ms: candidate.setup_ms,
                harness_setup_ms: candidate.harness_setup_ms,
                benchmark_process_ms: candidate.execution_ms,
            },
        },
        static_complexity: static_result,
        functional: FunctionalOutcome {
            equivalent_on_all_workloads: overall.equivalent_on_all_workloads,
            non_equivalent_workloads: non_equivalent,
            note: "Identical stdout and outcome on the supplied workloads; this is not a proof of equivalence.".to_owned(),
        },
        runtime: RuntimeComparison {
            workloads: comparisons,
            overall,
        },
        baseline_compilation: Some(baseline.compilation),
        candidate_compilation: Some(candidate.compilation),
        limitations: benchmark::LIMITATIONS.iter().map(|s| (*s).to_owned()).collect(),
    };
    let bytes = serde_json::to_vec_pretty(&report).map_err(|error| {
        tracing::error!(%error, "benchmark report serialization failed");
        ApiError::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "benchmark report serialization failed",
        )
    })?;
    project::store_benchmark_report(&directory, report.report_id, &bytes).await?;
    Ok(Json(report))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/benchmark/{report_id}",
    tag = "Verification",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), ("report_id" = Uuid, Path)),
    responses(
        (status = 200, body = BenchmarkReport),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_benchmark_report(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, report_id)): Path<(String, String)>,
) -> Result<Response, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let report_id =
        Uuid::parse_str(&report_id).map_err(|_| ApiError::bad_request("malformed report id"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (_, directory) = project::load(&session_dir, project_id).await?;
    let bytes = match fs::read(project::benchmark_report_path(&directory, report_id)).await {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(ApiError::new(
                StatusCode::NOT_FOUND,
                "benchmark_report_not_found",
                "benchmark report does not exist",
            ))
        }
        Err(error) => return Err(error.into()),
    };
    let mut response = Response::new(Body::from(bytes));
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    Ok(response)
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/rollback",
    tag = "Optimization",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = RollbackRequest,
    responses(
        (status = 200, description = "A new revision with the files (and analysis) of `to_revision`; history is kept", body = Project),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody)
    )
)]
async fn rollback_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<RollbackRequest>, JsonRejection>,
) -> Result<Json<Project>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid rollback request"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let _guard = state.0.writes.lock().await;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    Ok(Json(
        project::restore_revision(
            &directory,
            project,
            request.base_revision,
            request.to_revision,
        )
        .await?,
    ))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/revisions/{from}/diff/{to}",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path), ("from" = u64, Path), ("to" = u64, Path)),
    responses(
        (status = 200, description = "Unified diff of every file that differs between the two revisions", body = RevisionDiff),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn compare_revisions(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, from, to)): Path<(String, u64, u64)>,
) -> Result<Json<RevisionDiff>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let before = project::load_manifest(&directory, &project, from).await?;
    let after = project::load_manifest(&directory, &project, to).await?;
    let mut paths: Vec<&str> = before
        .files
        .iter()
        .chain(after.files.iter())
        .map(|f| f.path.as_str())
        .collect();
    paths.sort_unstable();
    paths.dedup();
    let mut files = Vec::new();
    for path in paths {
        let a = before.files.iter().find(|f| f.path == path);
        let b = after.files.iter().find(|f| f.path == path);
        if let (Some(a), Some(b)) = (a, b) {
            if a.sha256 == b.sha256 {
                continue;
            }
        }
        let read = |present: bool, revision: u64| {
            let directory = directory.clone();
            let project = project.clone();
            let path = path.to_owned();
            async move {
                if present {
                    project::read_file(&directory, &project, revision, &path)
                        .await
                        .map(|bytes| Some(String::from_utf8_lossy(&bytes).into_owned()))
                } else {
                    Ok(None)
                }
            }
        };
        let (old, new) = (read(a.is_some(), from).await?, read(b.is_some(), to).await?);
        if let Some(file_diff) = diff::diff_file(path, old.as_deref(), new.as_deref()) {
            files.push(file_diff);
        }
    }
    Ok(Json(RevisionDiff {
        from_revision: from,
        to_revision: to,
        files,
    }))
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/transform",
    tag = "Transformations",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = TransformRequest,
    responses(
        (status = 200, body = TransformResponse),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody),
        (status = 422, body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn transform_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<TransformRequest>, JsonRejection>,
) -> Result<Json<TransformResponse>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid transform request"))?;
    if request.operation.expression().trim().is_empty() {
        return Err(ApiError::bad_request(
            "replacement expression must not be empty",
        ));
    }
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let _guard = state.0.writes.lock().await;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if project::is_archive(&project) {
        return Err(project::multi_file_unsupported("transform"));
    }
    if request.base_revision != project.revision {
        return Err(ApiError::new(
            StatusCode::CONFLICT,
            "revision_conflict",
            format!(
                "expected revision {} but current revision is {}",
                request.base_revision, project.revision
            ),
        ));
    }
    let artifact = load_analysis(&directory, project.revision).await?;
    validate_transform_target(&artifact, &request.method_id, &request.node_id)?;

    let transformed = state
        .0
        .analyzer
        .transform(
            &directory,
            &project.filename,
            &request,
            state.0.max_project_bytes,
        )
        .await?;
    let project = project::commit_transformation(
        &directory,
        project,
        request.base_revision,
        transformed.source.as_bytes(),
        &transformed.analysis,
    )
    .await?;
    Ok(Json(TransformResponse {
        revision: project.revision,
        source: transformed.source,
    }))
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/verify",
    tag = "Verification",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = VerifyRequest,
    responses(
        (status = 200, body = PersistedVerificationReport),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 503, body = ApiErrorBody)
    )
)]
async fn verify_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<VerifyRequest>, JsonRejection>,
) -> Result<Json<PersistedVerificationReport>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid verify request"))?;
    validate_verify_request(&request)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let main_class = resolve_main_class(
        &directory,
        &project,
        request.baseline_revision,
        request.main_class.as_deref(),
    )
    .await?;
    let level = level_or_default(&request.source_level);
    let baseline_target = compile_target(
        &directory,
        &project,
        request.baseline_revision,
        main_class.clone(),
        level,
    )
    .await?;
    let candidate_target = compile_target(
        &directory,
        &project,
        request.candidate_revision,
        main_class,
        level,
    )
    .await?;
    let report = state
        .0
        .runner
        .verify(
            request.baseline_revision,
            &baseline_target,
            request.candidate_revision,
            &candidate_target,
            &request.test_cases,
        )
        .await?;
    let behaviorally_verified = behaviorally_verified(&report, request.test_cases.len())?;
    let mut warnings = verify_hints::scenario_warnings(&request.test_cases);
    let baseline_analysis = load_analysis(&directory, request.baseline_revision)
        .await
        .ok();
    let candidate_analysis = load_analysis(&directory, request.candidate_revision)
        .await
        .ok();
    warnings.extend(verify_hints::change_warnings(
        baseline_analysis.as_ref(),
        candidate_analysis.as_ref(),
    ));
    let persisted = PersistedVerificationReport {
        report_id: Uuid::new_v4(),
        created_at: OffsetDateTime::now_utc()
            .format(&Rfc3339)
            .expect("RFC 3339 supports all current UTC dates"),
        requested_test_cases: request.test_cases.len(),
        behaviorally_verified,
        warnings,
        report,
    };
    let bytes = serde_json::to_vec_pretty(&persisted).map_err(|error| {
        tracing::error!(%error, "verification report serialization failed");
        ApiError::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "verification report serialization failed",
        )
    })?;
    let _guard = state.0.writes.lock().await;
    project::store_verification_report(&directory, persisted.report_id, &bytes).await?;
    Ok(Json(persisted))
}

/// Fully qualified class names with a `main(String[])` method in the analysis of `revision`.
fn main_class_candidates(artifact: &Value) -> Vec<String> {
    let mut classes = Vec::new();
    for method in artifact
        .get("methods")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        if let Some(id) = method.get("id").and_then(Value::as_str) {
            if let Some(class) = id.strip_suffix(".main(String[])") {
                classes.push(class.to_owned());
            }
        }
    }
    classes
}

fn valid_class_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 256
        && name.split('.').all(|part| {
            let mut chars = part.chars();
            chars
                .next()
                .is_some_and(|c| c.is_alphabetic() || c == '_' || c == '$')
                && chars.all(|c| c.is_alphanumeric() || c == '_' || c == '$')
        })
}

/// Archive projects need an explicit or unambiguous main class; single-file projects keep their legacy
/// "class named like the file" rule and return `None`.
async fn resolve_main_class(
    directory: &std::path::Path,
    project: &Project,
    revision: u64,
    requested: Option<&str>,
) -> Result<Option<String>, ApiError> {
    if !project::is_archive(project) {
        if requested.is_some() {
            return Err(ApiError::bad_request(
                "main_class applies to archive projects only",
            ));
        }
        return Ok(None);
    }
    if let Some(name) = requested {
        if !valid_class_name(name) {
            return Err(ApiError::bad_request(
                "main_class is not a valid class name",
            ));
        }
        return Ok(Some(name.to_owned()));
    }
    let candidates = match load_analysis(directory, revision).await {
        Ok(artifact) => main_class_candidates(&artifact),
        Err(_) => Vec::new(),
    };
    match candidates.as_slice() {
        [only] => Ok(Some(only.clone())),
        [] => Err(ApiError::new(
            StatusCode::BAD_REQUEST,
            "main_class_required",
            "pass main_class (or analyze the project so its single main method can be found)",
        )),
        many => Err(ApiError::new(
            StatusCode::BAD_REQUEST,
            "main_class_required",
            format!(
                "several classes have a main method; pass main_class: {}",
                many.join(", ")
            ),
        )),
    }
}

/// The files of one revision as the runner should compile them.
async fn compile_target(
    directory: &std::path::Path,
    project: &Project,
    revision: u64,
    main_class: Option<String>,
    source_level: &str,
) -> Result<crate::runner::CompileTarget, ApiError> {
    if !project::is_archive(project) {
        let path = revision_source(directory, project, revision).await?;
        return Ok(crate::runner::CompileTarget::single(
            &project.filename,
            path,
        ));
    }
    crate::analyzer::check_source_level(source_level)?;
    let manifest = project::load_manifest(directory, project, revision).await?;
    let files = manifest
        .files
        .into_iter()
        .filter(|file| file.kind == archive::FileKind::Java)
        .map(|file| {
            let path = project::source_path(directory, revision, &file.path);
            (file.path, path)
        })
        .collect();
    Ok(crate::runner::CompileTarget {
        files,
        main_class,
        release: source_level.to_owned(),
    })
}

fn behaviorally_verified(
    report: &VerificationReport,
    expected_test_cases: usize,
) -> Result<bool, ApiError> {
    let complete_pass = is_complete_pass(report, expected_test_cases);
    if report.status == VerificationStatus::Passed && !complete_pass {
        tracing::error!(
            expected_test_cases,
            executed_test_cases = report.executed_test_cases,
            returned_test_cases = report.test_cases.len(),
            "runner returned an incomplete passing verification report"
        );
        return Err(ApiError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "runner_invalid_result",
            "runner returned an incomplete passing verification report",
        ));
    }
    Ok(report.status == VerificationStatus::Passed && complete_pass)
}

fn is_complete_pass(report: &VerificationReport, expected_test_cases: usize) -> bool {
    expected_test_cases > 0
        && report.baseline_compilation.status == CompilationStatus::Completed
        && report.candidate_compilation.status == CompilationStatus::Completed
        && report.executed_test_cases == expected_test_cases
        && report.test_cases.len() == expected_test_cases
        && report.test_cases.iter().all(|test_case| {
            test_case.passed
                && !test_case.baseline.timed_out
                && !test_case.baseline.output_overflow
                && !test_case.candidate.timed_out
                && !test_case.candidate.output_overflow
        })
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/verify/{report_id}",
    tag = "Verification",
    security(("guestSession" = [])),
    params(
        ("project_id" = Uuid, Path),
        ("report_id" = Uuid, Path)
    ),
    responses(
        (status = 200, body = PersistedVerificationReport),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_verification_report(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, report_id)): Path<(String, String)>,
) -> Result<Json<PersistedVerificationReport>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let report_id = Uuid::parse_str(&report_id)
        .map_err(|_| ApiError::bad_request("report_id must be a UUID"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (_, directory) = project::load(&session_dir, project_id).await?;
    let path = project::verification_report_path(&directory, report_id);
    let bytes = match fs::read(path).await {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(verification_report_not_found());
        }
        Err(error) => return Err(error.into()),
    };
    let report: PersistedVerificationReport = serde_json::from_slice(&bytes).map_err(|error| {
        tracing::error!(%error, %report_id, "stored verification report is invalid");
        ApiError::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "stored verification report is invalid",
        )
    })?;
    if report.report_id != report_id {
        return Err(verification_report_not_found());
    }
    let verified = report.report.status == VerificationStatus::Passed
        && is_complete_pass(&report.report, report.requested_test_cases);
    if report.behaviorally_verified != verified
        || (report.report.status == VerificationStatus::Passed && !verified)
    {
        tracing::error!(%report_id, "stored verification status is inconsistent");
        return Err(ApiError::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "stored verification report is invalid",
        ));
    }
    Ok(Json(report))
}

fn validate_verify_request(request: &VerifyRequest) -> Result<(), ApiError> {
    const MAX_CASES: usize = 20;
    const MAX_STDIN_BYTES: usize = 64 * 1024;
    const MAX_TOTAL_STDIN_BYTES: usize = 256 * 1024;

    if request.baseline_revision == 0 || request.candidate_revision == 0 {
        return Err(ApiError::bad_request("revision numbers must be positive"));
    }
    if request.test_cases.is_empty() || request.test_cases.len() > MAX_CASES {
        return Err(ApiError::bad_request(format!(
            "test_cases must contain between 1 and {MAX_CASES} cases"
        )));
    }
    let mut names = HashSet::new();
    let mut total = 0_usize;
    for test_case in &request.test_cases {
        if test_case.name.trim().is_empty() || test_case.name.len() > 64 {
            return Err(ApiError::bad_request(
                "test case names must contain 1 to 64 UTF-8 bytes",
            ));
        }
        if !names.insert(test_case.name.as_str()) {
            return Err(ApiError::bad_request("test case names must be unique"));
        }
        if test_case.stdin.len() > MAX_STDIN_BYTES {
            return Err(ApiError::bad_request(
                "a test case stdin exceeds the 65536-byte limit",
            ));
        }
        total = total.saturating_add(test_case.stdin.len());
    }
    if total > MAX_TOTAL_STDIN_BYTES {
        return Err(ApiError::bad_request(
            "combined test case stdin exceeds the 262144-byte limit",
        ));
    }
    Ok(())
}

async fn revision_source(
    directory: &std::path::Path,
    project: &Project,
    revision: u64,
) -> Result<PathBuf, ApiError> {
    if revision == 0 || revision > project.revision {
        return Err(revision_not_found());
    }
    let path = project::source_path(directory, revision, &project.filename);
    match fs::metadata(&path).await {
        Ok(metadata) if metadata.is_file() => Ok(path),
        Ok(_) | Err(_) => Err(revision_not_found()),
    }
}

fn revision_not_found() -> ApiError {
    ApiError::new(
        StatusCode::NOT_FOUND,
        "revision_not_found",
        "requested source revision does not exist",
    )
}

async fn load_analysis(directory: &std::path::Path, revision: u64) -> Result<Value, ApiError> {
    let path = directory.join("analysis").join(format!("{revision}.json"));
    let bytes = match fs::read(path).await {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(ApiError::new(
                StatusCode::NOT_FOUND,
                "analysis_not_found",
                "the current source revision has not been analyzed",
            ));
        }
        Err(error) => return Err(error.into()),
    };
    let artifact: Value = serde_json::from_slice(&bytes).map_err(|error| {
        tracing::error!(%error, revision, "stored analysis artifact is invalid");
        ApiError::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "stored analysis artifact is invalid",
        )
    })?;
    if artifact.get("revision").and_then(Value::as_u64) != Some(revision) {
        return Err(ApiError::new(
            StatusCode::CONFLICT,
            "analysis_revision_mismatch",
            "analysis does not match the current source revision",
        ));
    }
    Ok(artifact)
}

fn visit_cfg_methods<'a>(value: &'a Value, visitor: &mut impl FnMut(&'a Value, &'a Value)) {
    match value {
        Value::Object(object) => {
            let is_method = matches!(
                object.get("kind").and_then(Value::as_str),
                Some("method" | "constructor")
            );
            if is_method {
                if let Some(cfg) = object.get("cfg").filter(|cfg| cfg.is_object()) {
                    visitor(value, cfg);
                }
            }
            for (key, child) in object {
                if key != "cfg" {
                    visit_cfg_methods(child, visitor);
                }
            }
        }
        Value::Array(values) => {
            for child in values {
                visit_cfg_methods(child, visitor);
            }
        }
        _ => {}
    }
}

fn cfg_selector(cfg: &Value) -> Option<&str> {
    let entry = cfg.get("entry")?.as_str()?;
    let (prefix, node) = entry.rsplit_once(".n")?;
    // Single-file selectors are `m<N>`; project selectors add a file index: `f<F>m<N>`.
    let method = match prefix.strip_prefix('f') {
        Some(rest) => {
            let (file, method) = rest.split_once('m')?;
            if file.is_empty() || !file.bytes().all(|byte| byte.is_ascii_digit()) {
                return None;
            }
            method
        }
        None => prefix.strip_prefix('m')?,
    };
    if method.is_empty()
        || !method.bytes().all(|byte| byte.is_ascii_digit())
        || !node.bytes().all(|byte| byte.is_ascii_digit())
    {
        return None;
    }
    Some(prefix)
}

fn cfg_method(member: &Value, cfg: &Value) -> Option<CfgMethod> {
    Some(CfgMethod {
        selector: cfg_selector(cfg)?.to_owned(),
        id: member.get("id")?.as_str()?.to_owned(),
        name: member.get("name")?.as_str()?.to_owned(),
        kind: member.get("kind")?.as_str()?.to_owned(),
        source_range: serde_json::from_value(member.get("source_range")?.clone()).ok()?,
    })
}

fn validate_transform_target(
    artifact: &Value,
    method_id: &str,
    node_id: &str,
) -> Result<(), ApiError> {
    let mut method_cfg = None;
    visit_cfg_methods(artifact, &mut |member, cfg| {
        if method_cfg.is_none() && member.get("id").and_then(Value::as_str) == Some(method_id) {
            method_cfg = Some(cfg);
        }
    });
    let cfg = method_cfg.ok_or_else(method_not_found)?;
    let node = cfg
        .get("nodes")
        .and_then(Value::as_array)
        .and_then(|nodes| {
            nodes
                .iter()
                .find(|node| node.get("id").and_then(Value::as_str) == Some(node_id))
        })
        .ok_or_else(|| {
            ApiError::new(
                StatusCode::NOT_FOUND,
                "node_not_found",
                "CFG node does not exist in the selected method",
            )
        })?;
    if node.get("kind").and_then(Value::as_str) != Some("branch") {
        return Err(ApiError::new(
            StatusCode::UNPROCESSABLE_ENTITY,
            "invalid_transform_target",
            "replace_condition requires a branch condition node",
        ));
    }
    Ok(())
}

fn method_not_found() -> ApiError {
    ApiError::new(
        StatusCode::NOT_FOUND,
        "method_not_found",
        "method with an available CFG does not exist",
    )
}

fn method_analysis_not_found() -> ApiError {
    ApiError::new(
        StatusCode::NOT_FOUND,
        "method_analysis_not_found",
        "method analysis is not available for the current revision",
    )
}

fn graph_invalid() -> ApiError {
    ApiError::new(
        StatusCode::INTERNAL_SERVER_ERROR,
        "graph_invalid",
        "stored project graph is missing or has an unexpected shape",
    )
}

fn verification_report_not_found() -> ApiError {
    ApiError::new(
        StatusCode::NOT_FOUND,
        "verification_report_not_found",
        "verification report does not exist",
    )
}

fn parse_project_id(value: &str) -> Result<Uuid, ApiError> {
    Uuid::parse_str(value).map_err(|_| ApiError::bad_request("project_id must be a UUID"))
}

#[cfg(test)]
mod tests {
    use std::{path::PathBuf, time::Duration};

    use axum::{
        body::{to_bytes, Body},
        http::{header, Request, StatusCode},
    };
    use serde_json::Value;
    use sha2::{Digest, Sha256};
    use tokio::fs;
    use tower::ServiceExt;

    use super::{app, normalize_java_filename, AppState};
    use crate::Config;

    struct TestData(PathBuf);

    impl TestData {
        fn new() -> Self {
            Self(
                std::env::temp_dir().join(format!("java-blueprints-test-{}", uuid::Uuid::new_v4())),
            )
        }
    }

    impl Drop for TestData {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[cfg(unix)]
    const FAKE_TRANSFORM_JAVA: &str = r#"#!/bin/sh
mode=
IFS= read -r mode < "$2" || :
if [ "$#" -eq 9 ] && [ "$1" = "-jar" ] && [ "$3" = "transform" ] && [ "$4" = "--input" ] && [ "$6" = "--operation" ] && [ "$8" = "--output" ]; then
  case "$5:$7:$9" in
    /*:/*:/*) ;;
    *) exit 2 ;;
  esac
  case "$mode" in
    timeout)
      exec sleep 2
      ;;
    failure)
      printf %s '{"status":"failed","error":{"code":"internal_error","message":"fake worker failure"}}'
      exit 1
      ;;
    *)
      if grep -q '"base_revision"' "$7"; then
        printf %s '{"status":"failed","error":{"code":"invalid_operation","message":"base_revision leaked to worker"}}'
        exit 1
      fi
      if grep -q '"expression":"bad +"' "$7"; then
        printf %s '{"status":"failed","error":{"code":"invalid_expression","message":"invalid Java expression"}}'
        exit 1
      fi
      sed 's/count > 0/count >= 0/' "$5" > "$9"
      printf %s '{"status":"completed"}'
      ;;
  esac
elif [ "$#" -eq 3 ] && [ "$1" = "-jar" ]; then
  case "$3" in
    /*) ;;
    *) exit 2 ;;
  esac
  if [ "$mode" = "analysis-invalid" ]; then
    printf %s '{"protocol_version":1,"status":"partial","types":[],"methods":[],"diagnostics":[{"code":"test_error","severity":"error","message":"invalid candidate","source_range":{"start":0,"length":1,"line":1,"end_line":1}}]}'
  else
    printf %s '{"protocol_version":1,"status":"completed","types":[{"kind":"class","members":[{"kind":"method","id":"Example.process(int)","name":"process","source_range":{"start":16,"length":70,"line":1,"end_line":1},"cfg":{"method_id":"Example.process(int)","entry":"m0.n0","exit":"m0.n1","nodes":[{"id":"m0.n0","kind":"entry","source_range":{"start":16,"length":70,"line":1,"end_line":1}},{"id":"m0.n1","kind":"exit","source_range":{"start":16,"length":70,"line":1,"end_line":1}},{"id":"m0.n2","kind":"branch","statement_kind":"if","text":"count >= 0","source_range":{"start":52,"length":10,"line":1,"end_line":1}}],"edges":[{"from":"m0.n0","to":"m0.n2","kind":"next"},{"from":"m0.n2","to":"m0.n1","kind":"true"},{"from":"m0.n2","to":"m0.n1","kind":"false"}]}}]}],"methods":[{"id":"Example.process(int)","name":"process","source_range":{"start":16,"length":70,"line":1,"end_line":1}}],"diagnostics":[]}'
  fi
else
  exit 2
fi
"#;

    #[cfg(unix)]
    const FAKE_DOCKER: &str = r#"#!/bin/sh
state="${0}.state"
mode=
IFS= read -r mode < "${0}.mode" || :
mkdir -p "$state"

case "${1:-}" in
  create)
    if [ "$mode" = "create-failure" ]; then
      printf %s 'fake container create failure' >&2
      exit 1
    fi
    shift
    name=
    while [ "$#" -gt 0 ]; do
      if [ "$1" = "--name" ]; then
        name="$2"
        break
      fi
      shift
    done
    [ -n "$name" ] || exit 2
    mkdir -p "$state/$name/work"
    printf %s "$name"
    ;;
  start)
    [ -d "$state/$2" ] || exit 1
    ;;
  exec)
    shift
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --interactive) shift ;;
        --workdir) shift 2 ;;
        *) break ;;
      esac
    done
    name=$1
    shift
    command=$1
    shift
    if [ "$command" = "dd" ]; then
      destination=${1#of=}
      filename=${destination##*/}
      cat > "$state/$name/work/$filename" || exit 1
      printf '%s\n' "$filename" > "$state/$name/filename"
      exit 0
    fi
    filename=
    IFS= read -r filename < "$state/$name/filename" || exit 1
    source="$state/$name/work/$filename"
    stem=${filename%.java}
    case "$command" in
      javac)
        last=
        for argument in "$@"; do last=$argument; done
        last=${last##*/}
        if grep -q 'REQUIRE_EXAMPLE_FILENAME' "$source" && [ "$last" != "Example.java" ]; then
          printf %s 'wrong Java filename' >&2
          exit 1
        fi
        if grep -q 'COMPILE_ERROR' "$source"; then
          printf %s 'Example.java:1: error: simulated compiler diagnostic' >&2
          exit 1
        fi
        : > "$state/$name/work/$stem.class"
        ;;
      find)
        printf '/work/%s.class\n' "$stem"
        ;;
      javap)
        if grep -q 'NO_MAIN' "$source"; then
          printf 'public class %s {}\n' "$stem"
        else
          printf 'public class %s { public static void main(java.lang.String[]); }\n' "$stem"
        fi
        ;;
      java)
        if grep -q 'TIMEOUT' "$source"; then
          exec sleep 2
        fi
        if grep -q 'OVERFLOW' "$source"; then
          i=0
          while [ "$i" -lt 256 ]; do
            printf x
            i=$((i + 1))
          done
          exit 0
        fi
        if grep -q 'ECHO_STDOUT' "$source"; then
          cat
        elif grep -q 'ECHO_STDERR' "$source"; then
          cat >&2
        elif grep -q 'STDOUT_ONE' "$source"; then
          printf one
        elif grep -q 'STDOUT_TWO' "$source"; then
          printf two
        fi
        if grep -q 'STDERR_ONE' "$source"; then printf first >&2; fi
        if grep -q 'STDERR_TWO' "$source"; then printf second >&2; fi
        if grep -q 'EXIT_7' "$source"; then exit 7; fi
        if grep -q 'EXIT_8' "$source"; then exit 8; fi
        ;;
      *) exit 2 ;;
    esac
    ;;
  rm)
    name=
    for argument in "$@"; do name=$argument; done
    directory="$state/$name"
    if [ -d "$directory" ]; then
      find "$directory" -depth -delete
    fi
    ;;
  *) exit 2 ;;
esac
"#;

    async fn test_app(data: &TestData) -> axum::Router {
        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data.0.clone(),
            max_project_bytes: 1024,
            import_limits: Default::default(),
            secure_cookies: false,
            analyzer_jar: None,
            java_bin: "java".into(),
            analyzer_timeout: Duration::from_secs(1),
            analyzer_output_bytes: 1024,
            runner_image: None,
            container_bin: "docker".into(),
            runner_compile_timeout: Duration::from_secs(1),
            runner_case_timeout: Duration::from_secs(1),
            runner_output_bytes: 1024,
            runner_benchmark_timeout: Duration::from_secs(30),
        })
        .await
        .unwrap();
        app(state)
    }

    #[cfg(unix)]
    async fn test_app_with_worker(
        data: &TestData,
        mode: &str,
        analyzer_timeout: Duration,
    ) -> axum::Router {
        fs::create_dir_all(&data.0).await.unwrap();
        let jar = data.0.join("parser.jar");
        fs::write(&jar, format!("{mode}\n")).await.unwrap();
        let java = data.0.join("fake-java");
        crate::test_util::install_executable(&java, FAKE_TRANSFORM_JAVA);

        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data.0.clone(),
            max_project_bytes: 4096,
            import_limits: Default::default(),
            secure_cookies: false,
            analyzer_jar: Some(jar),
            java_bin: java,
            analyzer_timeout,
            analyzer_output_bytes: 16 * 1024,
            runner_image: None,
            container_bin: "docker".into(),
            runner_compile_timeout: Duration::from_secs(1),
            runner_case_timeout: Duration::from_secs(1),
            runner_output_bytes: 1024,
            runner_benchmark_timeout: Duration::from_secs(30),
        })
        .await
        .unwrap();
        app(state)
    }

    async fn test_app_with_real_worker(data: &TestData) -> axum::Router {
        let jar = std::env::var_os("BLUEPRINTS_REAL_JAR")
            .map(PathBuf::from)
            .expect("BLUEPRINTS_REAL_JAR must point to the built parser fat JAR");
        let java = std::env::var_os("BLUEPRINTS_REAL_JAVA")
            .map(PathBuf::from)
            .expect("BLUEPRINTS_REAL_JAVA must point to an OpenJDK 21 java executable");
        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data.0.clone(),
            max_project_bytes: 4096,
            import_limits: Default::default(),
            secure_cookies: false,
            analyzer_jar: Some(jar),
            java_bin: java,
            analyzer_timeout: Duration::from_secs(10),
            analyzer_output_bytes: 1024 * 1024,
            runner_image: None,
            container_bin: "docker".into(),
            runner_compile_timeout: Duration::from_secs(1),
            runner_case_timeout: Duration::from_secs(1),
            runner_output_bytes: 1024,
            runner_benchmark_timeout: Duration::from_secs(30),
        })
        .await
        .unwrap();
        app(state)
    }

    #[cfg(unix)]
    async fn test_app_with_runner(
        data: &TestData,
        mode: &str,
        case_timeout: Duration,
        output_bytes: u64,
    ) -> axum::Router {
        fs::create_dir_all(&data.0).await.unwrap();
        let container_bin = data.0.join("fake-docker");
        fs::write(data.0.join("fake-docker.mode"), mode)
            .await
            .unwrap();
        crate::test_util::install_executable(&container_bin, FAKE_DOCKER);

        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data.0.join("data"),
            max_project_bytes: 4096,
            import_limits: Default::default(),
            secure_cookies: false,
            analyzer_jar: None,
            java_bin: "java".into(),
            analyzer_timeout: Duration::from_secs(1),
            analyzer_output_bytes: 1024,
            runner_image: Some("fake-java21".to_owned()),
            container_bin,
            runner_compile_timeout: Duration::from_secs(1),
            runner_case_timeout: case_timeout,
            runner_output_bytes: output_bytes,
            runner_benchmark_timeout: Duration::from_secs(30),
        })
        .await
        .unwrap();
        app(state)
    }

    async fn guest_cookie(app: &axum::Router) -> String {
        let response = app
            .clone()
            .oneshot(
                Request::post("/api/v1/sessions/guest")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        response.headers()[header::SET_COOKIE]
            .to_str()
            .unwrap()
            .split(';')
            .next()
            .unwrap()
            .to_owned()
    }

    async fn upload(app: &axum::Router, cookie: &str, filename: &str) -> axum::response::Response {
        upload_source(app, cookie, filename, "class Main {}\n").await
    }

    async fn upload_source(
        app: &axum::Router,
        cookie: &str,
        filename: &str,
        source: &str,
    ) -> axum::response::Response {
        let boundary = "m1-test-boundary";
        let body = format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: text/plain\r\n\r\n{source}\r\n--{boundary}--\r\n"
        );
        app.clone()
            .oneshot(
                Request::post("/api/v1/projects")
                    .header(header::COOKIE, cookie)
                    .header(
                        header::CONTENT_TYPE,
                        format!("multipart/form-data; boundary={boundary}"),
                    )
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap()
    }

    async fn response_json(response: axum::response::Response) -> Value {
        // the served OpenAPI document alone is well above 64 KiB
        let bytes = to_bytes(response.into_body(), 512 * 1024).await.unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    fn project_directory(data: &TestData, cookie: &str, project_id: &str) -> PathBuf {
        let token = cookie
            .strip_prefix("blueprints_session=")
            .expect("test cookie has expected name");
        let digest = Sha256::digest(token.as_bytes());
        let session_hash = digest
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        data.0
            .join("sessions")
            .join(session_hash)
            .join("projects")
            .join(project_id)
    }

    fn cfg_artifact(revision: u64) -> Value {
        serde_json::json!({
            "protocol_version": 1,
            "status": "completed",
            "revision": revision,
            "types": [{
                "kind": "class",
                "name": "Example",
                "members": [{
                    "kind": "constructor",
                    "id": "Example.Example()",
                    "name": "Example",
                    "source_range": {"start": 16, "length": 11, "line": 1, "end_line": 1},
                    "cfg": {
                        "method_id": "Example.Example()",
                        "entry": "m0.n0",
                        "exit": "m0.n1",
                        "nodes": [
                            {"id": "m0.n0", "kind": "entry", "source_range": {"start": 16, "length": 11, "line": 1, "end_line": 1}},
                            {"id": "m0.n1", "kind": "exit", "source_range": {"start": 16, "length": 11, "line": 1, "end_line": 1}}
                        ],
                        "edges": [{"from": "m0.n0", "to": "m0.n1", "kind": "next"}]
                    }
                }, {
                    "kind": "method",
                    "id": "Example.process(int)",
                    "name": "process",
                    "source_range": {"start": 29, "length": 67, "line": 2, "end_line": 4},
                    "body": [{"kind": "if", "condition": "count > 0"}],
                    "analysis": {
                        "complexity": {
                            "time": "O(n)",
                            "confidence": "high",
                            "reason": "Dominant term n: a loop over values; sequential loops are additive, the largest wins.",
                            "assumptions": ["n = number of elements in values"]
                        },
                        "fragments": [{
                            "kind": "loop",
                            "statement_kind": "foreach",
                            "source_range": {"start": 67, "length": 9, "line": 3, "end_line": 3},
                            "node_ids": ["m1.n2"],
                            "time": "O(n)",
                            "confidence": "high",
                            "reason": "Dominant term n: a loop over values; sequential loops are additive, the largest wins.",
                            "assumptions": ["n = number of elements in values"]
                        }],
                        "findings": [{
                            "id": "repeated_collection_scan",
                            "severity": "warning",
                            "message": "Repeated scan",
                            "explanation": "Nested scan",
                            "recommendation": "Build an index",
                            "source_range": {"start": 67, "length": 9, "line": 3, "end_line": 3},
                            "node_ids": ["m1.n2"]
                        }]
                    },
                    "cfg": {
                        "method_id": "Example.process(int)",
                        "entry": "m1.n0",
                        "exit": "m1.n1",
                        "nodes": [
                            {"id": "m1.n0", "kind": "entry", "source_range": {"start": 29, "length": 67, "line": 2, "end_line": 4}},
                            {"id": "m1.n1", "kind": "exit", "source_range": {"start": 29, "length": 67, "line": 2, "end_line": 4}},
                            {"id": "m1.n2", "kind": "branch", "statement_kind": "if", "text": "count > 0", "source_range": {"start": 67, "length": 9, "line": 3, "end_line": 3}}
                        ],
                        "edges": [
                            {"from": "m1.n0", "to": "m1.n2", "kind": "next"},
                            {"from": "m1.n2", "to": "m1.n1", "kind": "true", "label": "kept-exactly"},
                            {"from": "m1.n2", "to": "m1.n1", "kind": "false"}
                        ]
                    }
                }]
            }],
            "methods": [
                {"id": "Example.Example()", "name": "Example", "source_range": {"start": 16, "length": 11, "line": 1, "end_line": 1}},
                {"id": "Example.process(int)", "name": "process", "source_range": {"start": 29, "length": 67, "line": 2, "end_line": 4}}
            ],
            "diagnostics": []
        })
    }

    async fn store_analysis(data: &TestData, cookie: &str, project_id: &str, artifact: &Value) {
        let path = project_directory(data, cookie, project_id)
            .join("analysis")
            .join(format!("{}.json", artifact["revision"].as_u64().unwrap()));
        fs::write(path, serde_json::to_vec(artifact).unwrap())
            .await
            .unwrap();
    }

    /// Mirrors the Java parser's `project_graph` shape (apps/parser ProjectGraph.build()):
    /// type and callable nodes, edges with sites, unresolved calls, stats.
    fn project_graph_fixture() -> Value {
        serde_json::json!({
            "version": 1,
            "scope": "compilation_unit",
            "nodes": [
                {
                    "id": "t0",
                    "kind": "class",
                    "name": "Example",
                    "qualified_name": "Example",
                    "external_supertypes": [
                        {"name": "java.io.Serializable", "relation": "implements", "resolved": true}
                    ],
                    "source_range": {"start": 0, "length": 15, "line": 1, "end_line": 1}
                },
                {
                    "id": "c0",
                    "kind": "method",
                    "name": "process",
                    "method_id": "Example.process(int)",
                    "cfg_entry": "m1.n0",
                    "source_range": {"start": 29, "length": 67, "line": 2, "end_line": 4}
                }
            ],
            "edges": [
                {
                    "from": "t0",
                    "to": "c0",
                    "kind": "contains",
                    "count": 1,
                    "sites": [{"start": 29, "length": 67, "line": 2, "end_line": 4}]
                },
                {
                    "from": "c0",
                    "to": "t0",
                    "kind": "depends_on",
                    "count": 1,
                    "sites": [{"start": 67, "length": 9, "line": 3, "end_line": 3}]
                }
            ],
            "unresolved_calls": [
                {
                    "id": "u0",
                    "from": "c0",
                    "kind": "method_call",
                    "name": "missing",
                    "expression": "missing(1)",
                    "source_range": {"start": 60, "length": 9, "line": 3, "end_line": 3},
                    "reason": "unresolved_binding"
                }
            ],
            "stats": {"resolved_internal_calls": 0, "external_calls": 1, "unresolved_calls": 1}
        })
    }

    fn transform_request(
        cookie: &str,
        project_id: &str,
        base_revision: u64,
        method_id: &str,
        node_id: &str,
        expression: &str,
    ) -> Request<Body> {
        Request::post(format!("/api/v1/projects/{project_id}/transform"))
            .header(header::COOKIE, cookie)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(
                serde_json::json!({
                    "base_revision": base_revision,
                    "method_id": method_id,
                    "node_id": node_id,
                    "operation": {
                        "type": "replace_condition",
                        "expression": expression
                    }
                })
                .to_string(),
            ))
            .unwrap()
    }

    fn verify_request(
        cookie: &str,
        project_id: &str,
        baseline_revision: u64,
        candidate_revision: u64,
        cases: Value,
    ) -> Request<Body> {
        Request::post(format!("/api/v1/projects/{project_id}/verify"))
            .header(header::COOKIE, cookie)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(
                serde_json::json!({
                    "baseline_revision": baseline_revision,
                    "candidate_revision": candidate_revision,
                    "test_cases": cases
                })
                .to_string(),
            ))
            .unwrap()
    }

    fn verification_report_request(
        cookie: &str,
        project_id: &str,
        report_id: &str,
    ) -> Request<Body> {
        Request::get(format!("/api/v1/projects/{project_id}/verify/{report_id}"))
            .header(header::COOKIE, cookie)
            .body(Body::empty())
            .unwrap()
    }

    async fn update_source(
        app: &axum::Router,
        cookie: &str,
        project_id: &str,
        source: &str,
        revision: u64,
    ) -> axum::response::Response {
        app.clone()
            .oneshot(
                Request::put(format!("/api/v1/projects/{project_id}/source"))
                    .header(header::COOKIE, cookie)
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(
                        serde_json::json!({
                            "source": source,
                            "expected_revision": revision
                        })
                        .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap()
    }

    async fn project_with_revisions(
        app: &axum::Router,
        cookie: &str,
        baseline: &str,
        candidate: &str,
    ) -> String {
        let project =
            response_json(upload_source(app, cookie, "Example.java", baseline).await).await;
        let project_id = project["id"].as_str().unwrap().to_owned();
        let response = update_source(app, cookie, &project_id, candidate, 1).await;
        assert_eq!(response.status(), StatusCode::OK);
        project_id
    }

    #[tokio::test]
    async fn project_is_private_to_guest_session() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let owner = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let response = upload(&app, &owner, "Main.java").await;
        assert_eq!(response.status(), StatusCode::CREATED);
        let project = response_json(response).await;

        let response = app
            .oneshot(
                Request::get(format!(
                    "/api/v1/projects/{}",
                    project["id"].as_str().unwrap()
                ))
                .header(header::COOKIE, other)
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn revision_history_returns_only_owned_published_revisions() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let owner = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let baseline = "class Example { static void main(String[] args) {} }\n";
        let candidate =
            "class Example { static void main(String[] args) { System.out.println(1); } }\n";
        let project_id = project_with_revisions(&app, &owner, baseline, candidate).await;
        store_analysis(&data, &owner, &project_id, &cfg_artifact(1)).await;

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/revisions"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let revisions = response_json(response).await;
        assert_eq!(
            revisions,
            serde_json::json!([
                {"revision": 2, "has_analysis": false},
                {"revision": 1, "has_analysis": true}
            ])
        );

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/revisions/1"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            response_json(response).await,
            serde_json::json!({"revision": 1, "source": baseline, "has_analysis": true})
        );

        let missing = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/revisions/3"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(missing).await["code"], "revision_not_found");

        let foreign = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/revisions"))
                    .header(header::COOKIE, &other)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(foreign).await["code"], "project_not_found");
    }

    #[tokio::test]
    async fn analysis_stream_reports_real_analyzer_failure_as_sse() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Main.java").await).await;
        let project_id = project["id"].as_str().unwrap();

        let response = app
            .oneshot(
                Request::post(format!("/api/v1/projects/{project_id}/analyze/stream"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            response.headers()[header::CONTENT_TYPE],
            "text/event-stream"
        );
        let body = to_bytes(response.into_body(), 64 * 1024).await.unwrap();
        let body = std::str::from_utf8(&body).unwrap();
        assert!(body.contains("event: started"));
        assert!(body.contains("event: progress"));
        assert!(body.contains("event: failed"));
        assert!(body.contains("analyzer_unavailable"));
    }

    #[tokio::test]
    async fn lists_nested_methods_and_returns_exact_cfg() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let artifact = cfg_artifact(1);
        store_analysis(&data, &cookie, project_id, &artifact).await;

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let methods = response_json(response).await;
        assert_eq!(methods["revision"], 1);
        assert_eq!(methods["methods"].as_array().unwrap().len(), 2);
        assert_eq!(methods["methods"][0]["kind"], "constructor");
        assert_eq!(methods["methods"][0]["selector"], "m0");
        assert_eq!(methods["methods"][1]["id"], "Example.process(int)");
        assert_eq!(methods["methods"][1]["selector"], "m1");
        assert_eq!(methods["methods"][1]["source_range"]["end_line"], 4);

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods/m1/cfg"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let cfg = response_json(response).await;
        assert_eq!(cfg, artifact["types"][0]["members"][1]["cfg"]);
        assert_eq!(cfg["nodes"][2]["id"], "m1.n2");
        assert_eq!(cfg["nodes"][2]["source_range"]["start"], 67);
        assert_eq!(cfg["nodes"][2]["source_range"]["length"], 9);
        assert_eq!(cfg["edges"][1]["label"], "kept-exactly");
    }

    #[tokio::test]
    async fn returns_exact_method_analysis_with_cfg_ids_utf16_ranges_and_revision() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let owner = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let project = response_json(upload(&app, &owner, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let artifact = cfg_artifact(1);
        store_analysis(&data, &owner, project_id, &artifact).await;

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods/m1/analysis"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let response = response_json(response).await;
        assert_eq!(response["revision"], 1);
        assert_eq!(response["method_id"], "Example.process(int)");
        assert_eq!(
            response["analysis"],
            artifact["types"][0]["members"][1]["analysis"]
        );
        assert_eq!(response["analysis"]["fragments"][0]["node_ids"][0], "m1.n2");
        assert_eq!(
            response["analysis"]["findings"][0]["source_range"]["start"],
            67
        );
        assert_eq!(
            response["analysis"]["findings"][0]["source_range"]["length"],
            9
        );

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods/m1/analysis"))
                    .header(header::COOKIE, &other)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "project_not_found");
    }

    #[tokio::test]
    async fn method_analysis_rejects_unknown_method_and_missing_analysis() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let mut artifact = cfg_artifact(1);
        artifact["types"][0]["members"][1]["analysis"] = Value::Null;
        store_analysis(&data, &cookie, project_id, &artifact).await;

        let missing_method = app
            .clone()
            .oneshot(
                Request::get(format!(
                    "/api/v1/projects/{project_id}/methods/m99/analysis"
                ))
                .header(header::COOKIE, &cookie)
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(missing_method.status(), StatusCode::NOT_FOUND);
        assert_eq!(
            response_json(missing_method).await["code"],
            "method_not_found"
        );

        let missing_analysis = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods/m1/analysis"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(missing_analysis.status(), StatusCode::NOT_FOUND);
        assert_eq!(
            response_json(missing_analysis).await["code"],
            "method_analysis_not_found"
        );
    }

    #[tokio::test]
    async fn methods_reject_missing_analysis_method_and_cross_session_access() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let owner = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let project = response_json(upload(&app, &owner, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods/m1/analysis"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "analysis_not_found");

        store_analysis(&data, &owner, project_id, &cfg_artifact(1)).await;
        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods/m99/cfg"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "method_not_found");

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &other)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "project_not_found");
    }

    #[tokio::test]
    async fn methods_reject_analysis_from_a_different_revision() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let mut artifact = cfg_artifact(1);
        artifact["revision"] = 7.into();
        let path = project_directory(&data, &cookie, project_id)
            .join("analysis")
            .join("1.json");
        fs::write(path, serde_json::to_vec(&artifact).unwrap())
            .await
            .unwrap();

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods/m1/analysis"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(
            response_json(response).await["code"],
            "analysis_revision_mismatch"
        );
    }

    #[tokio::test]
    async fn graph_returns_exact_persisted_project_graph_for_current_revision() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let mut artifact = cfg_artifact(1);
        artifact["project_graph"] = project_graph_fixture();
        store_analysis(&data, &cookie, project_id, &artifact).await;

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/graph"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let response = response_json(response).await;
        assert_eq!(response["revision"], 1);
        // The parser-produced graph is returned verbatim, identifiers included.
        assert_eq!(response["graph"], artifact["project_graph"]);
        assert_eq!(response["graph"]["nodes"][0]["id"], "t0");
        assert_eq!(
            response["graph"]["nodes"][1]["method_id"],
            "Example.process(int)"
        );
        assert_eq!(response["graph"]["nodes"][1]["cfg_entry"], "m1.n0");
        assert_eq!(response["graph"]["edges"][0]["kind"], "contains");
        assert_eq!(response["graph"]["unresolved_calls"][0]["id"], "u0");
        assert_eq!(response["graph"]["stats"]["unresolved_calls"], 1);
    }

    #[tokio::test]
    async fn graph_returns_null_when_parser_emitted_null_project_graph() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let mut artifact = cfg_artifact(1);
        artifact["project_graph"] = Value::Null;
        store_analysis(&data, &cookie, project_id, &artifact).await;

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/graph"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let response = response_json(response).await;
        assert_eq!(response["revision"], 1);
        assert!(response["graph"].is_null());
    }

    #[tokio::test]
    async fn graph_rejects_missing_analysis_cross_session_and_malformed_graph() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let owner = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let project = response_json(upload(&app, &owner, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();

        // The current revision has no stored analysis yet.
        let missing = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/graph"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(missing).await["code"], "analysis_not_found");

        // Another session cannot read the project at all.
        let foreign = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/graph"))
                    .header(header::COOKIE, &other)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(foreign.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(foreign).await["code"], "project_not_found");

        // An artifact without a project_graph key violates the parser contract.
        store_analysis(&data, &owner, project_id, &cfg_artifact(1)).await;
        let missing_key = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/graph"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(missing_key.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(response_json(missing_key).await["code"], "graph_invalid");

        // A project_graph value that is neither an object nor null is malformed.
        let mut artifact = cfg_artifact(1);
        artifact["project_graph"] = serde_json::json!([1, 2, 3]);
        store_analysis(&data, &owner, project_id, &artifact).await;
        let malformed = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/graph"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(malformed.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(response_json(malformed).await["code"], "graph_invalid");
    }

    #[tokio::test]
    async fn graph_rejects_analysis_from_a_different_revision() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let mut artifact = cfg_artifact(1);
        artifact["revision"] = 7.into();
        artifact["project_graph"] = project_graph_fixture();
        let path = project_directory(&data, &cookie, project_id)
            .join("analysis")
            .join("1.json");
        fs::write(path, serde_json::to_vec(&artifact).unwrap())
            .await
            .unwrap();

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/graph"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(
            response_json(response).await["code"],
            "analysis_revision_mismatch"
        );
    }

    #[tokio::test]
    async fn project_route_requires_a_session() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{}", uuid::Uuid::new_v4()))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn generated_openapi_documents_analysis_transform_and_verify_routes() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let response = app
            .oneshot(
                Request::get("/api/v1/openapi.json")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let document = response_json(response).await;
        assert!(
            document["paths"]["/api/v1/projects/{project_id}/analyze/stream"]["post"].is_object()
        );
        assert!(document["paths"]["/api/v1/projects/{project_id}/revisions"]["get"].is_object());
        assert!(
            document["paths"]["/api/v1/projects/{project_id}/revisions/{revision}"]["get"]
                .is_object()
        );
        assert!(document["paths"]["/api/v1/projects/{project_id}/methods"]["get"].is_object());
        assert!(document["paths"]["/api/v1/projects/{project_id}/graph"]["get"].is_object());
        assert!(
            document["paths"]["/api/v1/projects/{project_id}/methods/{method_selector}/cfg"]["get"]
                .is_object()
        );
        assert!(document["paths"]
            ["/api/v1/projects/{project_id}/methods/{method_selector}/analysis"]["get"]
            .is_object());
        assert!(document["paths"]["/api/v1/projects/{project_id}/transform"]["post"].is_object());
        assert!(document["paths"]["/api/v1/projects/{project_id}/verify"]["post"].is_object());
        assert!(
            document["paths"]["/api/v1/projects/{project_id}/verify/{report_id}"]["get"]
                .is_object()
        );
        assert!(document["components"]["schemas"]["TransformRequest"].is_object());
        assert!(document["components"]["schemas"]["MethodAnalysisResponse"].is_object());
        assert!(document["components"]["schemas"]["ProjectGraphResponse"].is_object());
        assert!(document["components"]["schemas"]["PersistedVerificationReport"].is_object());
        assert!(document["components"]["schemas"]["RevisionDetail"].is_object());
        assert!(
            document["components"]["schemas"]["MethodAnalysisResponse"]["properties"]["analysis"]
                .is_object()
        );
    }

    /// Drift guard: the hand-written `openapi.yaml` and the served (utoipa) document describe the same
    /// operations, so Swagger UI and the contract file cannot silently diverge.
    #[test]
    fn openapi_yaml_lists_every_served_operation() {
        use utoipa::OpenApi;
        let served = serde_json::to_value(super::ApiDoc::openapi()).unwrap();
        let mut served_ops = std::collections::BTreeSet::new();
        for (path, item) in served["paths"].as_object().unwrap() {
            for method in ["get", "post", "put", "delete", "patch"] {
                if item.get(method).is_some() {
                    served_ops.insert(format!(
                        "{} {}",
                        method.to_uppercase(),
                        path.trim_start_matches("/api/v1")
                    ));
                }
            }
        }
        let yaml =
            std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/openapi.yaml")).unwrap();
        let mut documented = std::collections::BTreeSet::new();
        let mut current = String::new();
        let mut in_paths = false;
        for line in yaml.lines() {
            if line.starts_with("paths:") {
                in_paths = true;
                continue;
            }
            if in_paths && !line.starts_with(' ') && !line.is_empty() {
                in_paths = false;
            }
            if !in_paths {
                continue;
            }
            if let Some(path) = line
                .strip_prefix("  /")
                .and_then(|rest| rest.strip_suffix(':'))
            {
                current = format!("/{path}");
            } else if let Some(method) = line
                .strip_prefix("    ")
                .and_then(|rest| rest.strip_suffix(':'))
            {
                if ["get", "post", "put", "delete", "patch"].contains(&method) {
                    documented.insert(format!("{} {}", method.to_uppercase(), current));
                }
            }
        }
        // `/healthz`-style planned routes may stay documented ahead of implementation, never the other way round
        let missing: Vec<_> = served_ops.difference(&documented).collect();
        assert!(
            missing.is_empty(),
            "served but missing from openapi.yaml: {missing:?}"
        );
    }

    #[tokio::test]
    async fn upload_rejects_non_java_file() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let response = upload(&app, &cookie, "notes.txt").await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        // A .zip name routes to archive import and must really be a ZIP.
        let response = upload(&app, &cookie, "project.zip").await;
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    }

    fn zip_bytes(entries: &[(&str, String)]) -> Vec<u8> {
        use std::io::Write;
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default();
        for (name, content) in entries {
            writer.start_file(*name, options).unwrap();
            writer.write_all(content.as_bytes()).unwrap();
        }
        writer.finish().unwrap().into_inner()
    }

    async fn upload_bytes(
        app: &axum::Router,
        cookie: &str,
        filename: &str,
        content: &[u8],
    ) -> axum::response::Response {
        let boundary = "m1-test-boundary";
        let mut body = format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: application/zip\r\n\r\n"
        )
        .into_bytes();
        body.extend_from_slice(content);
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
        app.clone()
            .oneshot(
                Request::post("/api/v1/projects")
                    .header(header::COOKIE, cookie)
                    .header(
                        header::CONTENT_TYPE,
                        format!("multipart/form-data; boundary={boundary}"),
                    )
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap()
    }

    fn twelve_file_archive() -> Vec<u8> {
        let mut entries: Vec<(String, String)> = (0..11)
            .map(|index| {
                (
                    format!("src/main/java/com/acme/p{index}/C{index}.java"),
                    format!("package com.acme.p{index}; public class C{index} {{}}\n"),
                )
            })
            .collect();
        entries.push((
            "src/test/java/com/acme/CTest.java".into(),
            "package com.acme; class CTest {}\n".into(),
        ));
        entries.push(("pom.xml".into(), "<project/>".into()));
        entries.push(("__MACOSX/._pom.xml".into(), "x".into()));
        let borrowed: Vec<(&str, String)> = entries
            .iter()
            .map(|(name, body)| (name.as_str(), body.clone()))
            .collect();
        zip_bytes(&borrowed)
    }

    async fn get_with_cookie(
        app: &axum::Router,
        cookie: &str,
        uri: &str,
    ) -> axum::response::Response {
        app.clone()
            .oneshot(
                Request::get(uri)
                    .header(header::COOKIE, cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap()
    }

    async fn put_json(
        app: &axum::Router,
        cookie: &str,
        uri: &str,
        body: Value,
    ) -> axum::response::Response {
        app.clone()
            .oneshot(
                Request::put(uri)
                    .header(header::COOKIE, cookie)
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn zip_upload_exposes_all_files_with_paths_and_edits_create_snapshots() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;

        let response = upload_bytes(&app, &cookie, "legacy.zip", &twelve_file_archive()).await;
        assert_eq!(response.status(), StatusCode::CREATED);
        let project = response_json(response).await;
        assert_eq!(project["kind"], "archive");
        assert_eq!(project["file_count"], 13);
        let id = project["id"].as_str().unwrap();

        let manifest = response_json(
            get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/files")).await,
        )
        .await;
        let files = manifest["files"].as_array().unwrap();
        assert_eq!(files.len(), 13);
        let java: Vec<_> = files.iter().filter(|f| f["kind"] == "java").collect();
        assert_eq!(java.len(), 12);
        let test_file = files
            .iter()
            .find(|f| f["path"] == "src/test/java/com/acme/CTest.java")
            .unwrap();
        assert_eq!(test_file["role"], "test");
        assert_eq!(test_file["source_root"], "src/test/java/");
        assert_eq!(manifest["skipped"][0]["path"], "__MACOSX/._pom.xml");

        let uri = format!("/api/v1/projects/{id}/files/src/main/java/com/acme/p3/C3.java");
        let response = get_with_cookie(&app, &cookie, &uri).await;
        assert_eq!(response.headers()["x-source-revision"], "1");
        let body = to_bytes(response.into_body(), 4096).await.unwrap();
        assert!(String::from_utf8_lossy(&body).contains("class C3"));

        // Edit one file: new revision, other files and the old revision are untouched.
        let edited = "package com.acme.p3; public class C3 { int x; }\n";
        let response = put_json(
            &app,
            &cookie,
            &uri,
            serde_json::json!({"source": edited, "expected_revision": 1}),
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response_json(response).await["revision"], 2);

        let stale = put_json(
            &app,
            &cookie,
            &uri,
            serde_json::json!({"source": edited, "expected_revision": 1}),
        )
        .await;
        assert_eq!(stale.status(), StatusCode::CONFLICT);

        let old = get_with_cookie(&app, &cookie, &format!("{uri}?revision=1")).await;
        let old = to_bytes(old.into_body(), 4096).await.unwrap();
        assert!(!String::from_utf8_lossy(&old).contains("int x"));
        let new = get_with_cookie(&app, &cookie, &uri).await;
        let new = to_bytes(new.into_body(), 4096).await.unwrap();
        assert!(String::from_utf8_lossy(&new).contains("int x"));
        let other = get_with_cookie(
            &app,
            &cookie,
            &format!("/api/v1/projects/{id}/files/src/main/java/com/acme/p4/C4.java"),
        )
        .await;
        assert_eq!(other.status(), StatusCode::OK);

        let manifest_2 = response_json(
            get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/files")).await,
        )
        .await;
        assert_eq!(manifest_2["revision"], 2);
        let revisions = response_json(
            get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/revisions")).await,
        )
        .await;
        assert_eq!(revisions.as_array().unwrap().len(), 2);

        // Unknown paths and traversal are rejected; single-file endpoints say why.
        let missing = get_with_cookie(
            &app,
            &cookie,
            &format!("/api/v1/projects/{id}/files/Nope.java"),
        )
        .await;
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
        let traversal = get_with_cookie(
            &app,
            &cookie,
            &format!("/api/v1/projects/{id}/files/%2e%2e/project.json"),
        )
        .await;
        assert!(traversal.status().is_client_error());
        let source = get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/source")).await;
        assert_eq!(source.status(), StatusCode::CONFLICT);
        assert_eq!(response_json(source).await["code"], "multi_file_project");
    }

    #[tokio::test]
    async fn zip_upload_is_session_owned() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let owner = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let project =
            response_json(upload_bytes(&app, &owner, "p.zip", &twelve_file_archive()).await).await;
        let id = project["id"].as_str().unwrap();
        let denied = get_with_cookie(&app, &other, &format!("/api/v1/projects/{id}/files")).await;
        assert_eq!(denied.status(), StatusCode::NOT_FOUND);
        let anonymous = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{id}/files"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(anonymous.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn zip_upload_rejects_malicious_archives_without_creating_projects() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        for (entries, code) in [
            (
                vec![("../Evil.java", "class Evil {}")],
                "unsafe_archive_path",
            ),
            (
                vec![("A.java", "class A {}"), ("a.JAVA", "class A {}")],
                "duplicate_archive_path",
            ),
            (
                vec![("A.java", "class A {}"), ("lib.jar", "x")],
                "unsupported_archive_entry",
            ),
            (vec![("notes.md", "x")], "no_java_sources"),
        ] {
            let owned: Vec<(&str, String)> =
                entries.iter().map(|(n, c)| (*n, (*c).to_owned())).collect();
            let response = upload_bytes(&app, &cookie, "bad.zip", &zip_bytes(&owned)).await;
            assert_eq!(
                response.status(),
                StatusCode::UNPROCESSABLE_ENTITY,
                "{code}"
            );
            assert_eq!(response_json(response).await["code"], code);
        }
        let projects = std::fs::read_dir(project_directory(&data, &cookie, "x").parent().unwrap());
        assert!(projects.map(|mut d| d.next().is_none()).unwrap_or(true));
    }

    #[tokio::test]
    async fn single_file_project_supports_files_endpoints() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Main.java").await).await;
        assert_eq!(project["kind"], "single_file");
        let id = project["id"].as_str().unwrap();
        let manifest = response_json(
            get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/files")).await,
        )
        .await;
        assert_eq!(manifest["files"][0]["path"], "Main.java");
        let updated = put_json(
            &app,
            &cookie,
            &format!("/api/v1/projects/{id}/files/Main.java"),
            serde_json::json!({"source": "class Main { int a; }\n", "expected_revision": 1}),
        )
        .await;
        assert_eq!(updated.status(), StatusCode::OK);
        let source = get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/source")).await;
        let source = to_bytes(source.into_body(), 4096).await.unwrap();
        assert!(String::from_utf8_lossy(&source).contains("int a"));
    }

    #[tokio::test]
    async fn upload_preserves_java_basename() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;

        let project = response_json(upload(&app, &cookie, "Example.java").await).await;

        assert_eq!(project["filename"], "Example.java");
        assert_eq!(
            normalize_java_filename("C:\\fakepath\\Example.java").unwrap(),
            "Example.java"
        );
        assert_eq!(
            normalize_java_filename("../../Example.java").unwrap(),
            "Example.java"
        );
    }

    #[tokio::test]
    async fn source_update_rejects_stale_revision() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Main.java").await).await;
        let project_id = project["id"].as_str().unwrap();

        let update = |source: &str, revision: u64| {
            Request::put(format!("/api/v1/projects/{project_id}/source"))
                .header(header::COOKIE, &cookie)
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    serde_json::json!({
                        "source": source,
                        "expected_revision": revision
                    })
                    .to_string(),
                ))
                .unwrap()
        };
        let response = app
            .clone()
            .oneshot(update("class Main { int x; }", 1))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let response = app
            .oneshot(update("class Main { int y; }", 1))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(response_json(response).await["code"], "revision_conflict");
    }

    async fn post_json(
        app: &axum::Router,
        cookie: &str,
        uri: &str,
        body: Value,
    ) -> (StatusCode, Value) {
        let response = app
            .clone()
            .oneshot(
                Request::post(uri)
                    .header(header::COOKIE, cookie)
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        let status = response.status();
        let bytes = to_bytes(response.into_body(), 8 << 20).await.unwrap();
        (
            status,
            serde_json::from_slice(&bytes).unwrap_or(Value::Null),
        )
    }

    const OPT_CUSTOMER: &str = "package m;\npublic class Customer {\n    private final int id;\n    public Customer(int id) { this.id = id; }\n    public int id() { return id; }\n}\n";
    const OPT_ORDER: &str = "package m;\npublic class Order {\n    private final int customerId;\n    public Order(int customerId) { this.customerId = customerId; }\n    public int customerId() { return customerId; }\n}\n";
    const OPT_SERVICE: &str = "package m;\n\nimport java.util.List;\nimport java.util.Map;\n\npublic class Service {\n    public int attach(List<Order> orders, List<Customer> customers, Map<Integer, Integer> out) {\n        int n = 0;\n        for (Order order : orders) {\n            for (Customer customer : customers) {\n                if (customer.id() == order.customerId()) {\n                    out.put(order.customerId(), customer.id());\n                    n++;\n                    break;\n                }\n            }\n        }\n        return n;\n    }\n}\n";

    async fn cfg_node(
        app: &axum::Router,
        cookie: &str,
        base: &str,
        method_id: &str,
        text: &str,
    ) -> String {
        let listed = get_with_cookie(app, cookie, &format!("{base}/methods")).await;
        let listed: Value =
            serde_json::from_slice(&to_bytes(listed.into_body(), 4 << 20).await.unwrap()).unwrap();
        let selector = listed["methods"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["id"] == method_id)
            .unwrap()["selector"]
            .as_str()
            .unwrap()
            .to_owned();
        let cfg = get_with_cookie(app, cookie, &format!("{base}/methods/{selector}/cfg")).await;
        let cfg: Value =
            serde_json::from_slice(&to_bytes(cfg.into_body(), 4 << 20).await.unwrap()).unwrap();
        cfg["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|n| n["text"] == text)
            .unwrap_or_else(|| panic!("no CFG node {text}"))["id"]
            .as_str()
            .unwrap()
            .to_owned()
    }

    async fn run_cfg_edit_flow(
        app: &axum::Router,
        cookie: &str,
        base: &str,
        method_id: &str,
        level: &str,
    ) {
        let (status, _) = post_json(
            app,
            cookie,
            &format!("{base}/analyze?source_level={level}"),
            Value::Null,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let x = cfg_node(app, cookie, base, method_id, "int x = a + 1;").await;
        let y = cfg_node(app, cookie, base, method_id, "int y = b * 2;").await;
        let z = cfg_node(app, cookie, base, method_id, "int z = x * 3;").await;
        let edit = |node: &str, target: &str, position: &str, extra: Value| {
            let mut body = serde_json::json!({
                "base_revision": 1, "source_level": level, "method_id": method_id,
                "operation": {"type": "move_statement", "node_id": node, "target_node_id": target, "position": position},
            });
            if let Some(object) = extra.as_object() {
                for (k, v) in object {
                    body[k] = v.clone();
                }
            }
            body
        };

        // dependent statements: rejected with structured reasons, nothing stored
        let (status, rejected) = post_json(
            app,
            cookie,
            &format!("{base}/cfg-edits/preview"),
            edit(&z, &x, "before", Value::Null),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{rejected}");
        assert_eq!(rejected["code"], "move_rejected");
        assert_eq!(rejected["details"]["reasons"][0]["code"], "data_dependency");
        assert_eq!(
            rejected["details"]["reasons"][0]["statements"]
                .as_array()
                .unwrap()
                .len(),
            2
        );
        assert_eq!(
            response_json(get_with_cookie(app, cookie, base).await).await["revision"],
            1
        );

        // independent: preview shows the exact diff
        let (status, preview) = post_json(
            app,
            cookie,
            &format!("{base}/cfg-edits/preview"),
            edit(&y, &x, "before", Value::Null),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{preview}");
        let unified = preview["diff"][0]["unified_diff"].as_str().unwrap();
        assert!(unified.contains("+        int y = b * 2;"), "{unified}");
        assert!(unified.contains("-        int y = b * 2;"), "{unified}");
        assert_eq!(preview["operation"]["jumped_over"][0]["independent"], true);
        let sha = preview["preview_sha256"].clone();

        // apply needs the previewed fingerprint
        let (status, body) = post_json(
            app,
            cookie,
            &format!("{base}/cfg-edits/apply"),
            edit(
                &y,
                &x,
                "before",
                serde_json::json!({"preview_sha256": "bad"}),
            ),
        )
        .await;
        assert_eq!(
            (status, body["code"].as_str()),
            (StatusCode::CONFLICT, Some("preview_mismatch"))
        );
        let (status, applied) = post_json(
            app,
            cookie,
            &format!("{base}/cfg-edits/apply"),
            edit(&y, &x, "before", serde_json::json!({"preview_sha256": sha})),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{applied}");
        assert_eq!(applied["revision"], 2);
        assert_eq!(applied["analysis_status"], "completed");

        // the CFG of revision 2 is valid and ordered y, x, z
        let new_y = cfg_node(app, cookie, base, method_id, "int y = b * 2;").await;
        let new_x = cfg_node(app, cookie, base, method_id, "int x = a + 1;").await;
        let mapped = applied["node_mapping"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["old_id"] == x.as_str())
            .unwrap()["new_id"]
            .as_str()
            .unwrap()
            .to_owned();
        assert_eq!(mapped, new_x);
        assert_ne!(new_y, new_x);
        let source = if base.is_empty() {
            String::new()
        } else {
            String::new()
        };
        let _ = source;
        let compare = get_with_cookie(app, cookie, &format!("{base}/revisions/1/diff/2")).await;
        let compare: Value =
            serde_json::from_slice(&to_bytes(compare.into_body(), 1 << 20).await.unwrap()).unwrap();
        assert_eq!(
            compare["files"][0]["unified_diff"],
            preview["diff"][0]["unified_diff"]
        );

        // a stale base revision is rejected
        let (status, stale) = post_json(
            app,
            cookie,
            &format!("{base}/cfg-edits/preview"),
            edit(&y, &x, "after", Value::Null),
        )
        .await;
        assert_eq!(
            (status, stale["code"].as_str()),
            (StatusCode::CONFLICT, Some("revision_conflict"))
        );
    }

    const EDIT_SRC: &str = "public class Calc {\n    public int run(int a, int b) {\n        int x = a + 1;\n        int y = b * 2;\n        int z = x * 3;\n        return y + z;\n    }\n}\n";

    const LINKER_PROGRAM: &str = "import java.util.*;\n\npublic class Linker {\n    record Contact(int id, String name) { }\n\n    static final class Task {\n        private final int id;\n        private final int contactId;\n        Task(int id, int contactId) { this.id = id; this.contactId = contactId; }\n        int id() { return id; }\n        int getContactId() { return contactId; }\n    }\n\n    static int link(List<Task> taskList, List<Contact> contactList, Map<Integer, String> owners) {\n        int linked = 0;\n        for (Task task : taskList) {\n            for (Contact contact : contactList) {\n                if (contact.id() == task.getContactId()) {\n                    owners.put(task.id(), contact.name());\n                    linked++;\n                    break;\n                }\n            }\n        }\n        return linked;\n    }\n\n    public static void main(String[] args) {\n        Scanner in = new Scanner(System.in);\n        int n = in.nextInt();\n        List<Contact> contacts = new ArrayList<>();\n        for (int i = 0; i < n; i++) { contacts.add(new Contact((i * 7) % (2 * n + 1), \"c\" + i)); }\n        List<Task> tasks = new ArrayList<>();\n        for (int i = 0; i < n; i++) { tasks.add(new Task(i, (i * 13) % (2 * n + 1))); }\n        Map<Integer, String> owners = new TreeMap<>();\n        int linked = link(tasks, contacts, owners);\n        long checksum = 0;\n        for (Map.Entry<Integer, String> e : owners.entrySet()) { checksum = checksum * 31 + e.getKey() + e.getValue().hashCode(); }\n        System.out.println(linked + \" \" + checksum);\n    }\n}\n";

    async fn test_app_with_parser_and_runner(data: &TestData) -> Option<axum::Router> {
        let image = std::env::var("BLUEPRINTS_REAL_RUNNER_IMAGE").ok()?;
        let jar = PathBuf::from(std::env::var_os("BLUEPRINTS_REAL_JAR")?);
        let java = PathBuf::from(std::env::var_os("BLUEPRINTS_REAL_JAVA")?);
        let container_bin = std::env::var_os("BLUEPRINTS_REAL_CONTAINER_BIN")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("docker"));
        let available = tokio::process::Command::new(&container_bin)
            .args(["image", "inspect", &image])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .await
            .is_ok_and(|status| status.success());
        if !available {
            eprintln!("SKIPPED: Docker is unavailable or the runner image is missing");
            return None;
        }
        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data.0.clone(),
            max_project_bytes: 16 * 1024,
            import_limits: Default::default(),
            secure_cookies: false,
            analyzer_jar: Some(jar),
            java_bin: java,
            analyzer_timeout: Duration::from_secs(20),
            analyzer_output_bytes: 2 * 1024 * 1024,
            runner_image: Some(image),
            container_bin,
            runner_compile_timeout: Duration::from_secs(30),
            runner_case_timeout: Duration::from_secs(10),
            runner_output_bytes: 64 * 1024,
            runner_benchmark_timeout: Duration::from_secs(120),
        })
        .await
        .unwrap();
        Some(app(state))
    }

    #[tokio::test]
    #[ignore = "requires parser JAR, JDK and Docker with the runner image (BLUEPRINTS_REAL_JAR/JAVA/RUNNER_IMAGE)"]
    async fn real_archive_project_verify_and_benchmark() {
        let data = TestData::new();
        let Some(app) = test_app_with_parser_and_runner(&data).await else {
            return;
        };
        let cookie = guest_cookie(&app).await;
        let main = "package m;\nimport java.util.*;\npublic class Main {\n    public static void main(String[] args) {\n        Scanner in = new Scanner(System.in);\n        int n = in.nextInt();\n        List<Customer> cs = new ArrayList<>();\n        List<Order> os = new ArrayList<>();\n        for (int i = 0; i < n; i++) { cs.add(new Customer((i * 7) % (2 * n + 1))); os.add(new Order((i * 13) % (2 * n + 1))); }\n        Map<Integer, Integer> out = new TreeMap<>();\n        int linked = new Service().attach(os, cs, out);\n        long sum = 0;\n        for (Map.Entry<Integer, Integer> e : out.entrySet()) { sum = sum * 31 + e.getKey() * 7 + e.getValue(); }\n        System.out.println(linked + \" \" + sum);\n    }\n}\n";
        let files = vec![
            ("src/main/java/m/Customer.java", OPT_CUSTOMER.to_owned()),
            ("src/main/java/m/Order.java", OPT_ORDER.to_owned()),
            ("src/main/java/m/Service.java", OPT_SERVICE.to_owned()),
            ("src/main/java/m/Main.java", main.to_owned()),
        ];
        let project =
            response_json(upload_bytes(&app, &cookie, "shop.zip", &zip_bytes(&files)).await).await;
        let id = project["id"].as_str().unwrap();
        let base = format!("/api/v1/projects/{id}");

        // several mains -> required, unambiguous one -> found from the analysis
        let verify_body = serde_json::json!({
            "baseline_revision": 1, "candidate_revision": 1, "source_level": "17",
            "test_cases": [{"name": "t", "stdin": "5\n"}],
        });
        let (status, body) = post_json(
            &app,
            &cookie,
            &format!("{base}/verify"),
            verify_body.clone(),
        )
        .await;
        assert_eq!(
            (status, body["code"].as_str()),
            (StatusCode::BAD_REQUEST, Some("main_class_required"))
        );
        let (status, _) = post_json(
            &app,
            &cookie,
            &format!("{base}/analyze?source_level=17"),
            Value::Null,
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        let list = get_with_cookie(
            &app,
            &cookie,
            &format!("{base}/optimizations?source_level=17"),
        )
        .await;
        let list: Value =
            serde_json::from_slice(&to_bytes(list.into_body(), 4 << 20).await.unwrap()).unwrap();
        let proposal_id = list["proposals"][0]["id"].as_str().unwrap().to_owned();
        let (_, preview) = post_json(
            &app,
            &cookie,
            &format!("{base}/optimizations/{proposal_id}/preview"),
            serde_json::json!({"base_revision": 1, "source_level": "17"}),
        )
        .await;
        let (status, applied) = post_json(&app, &cookie, &format!("{base}/optimizations/{proposal_id}/apply"), serde_json::json!({
            "base_revision": 1, "source_level": "17", "preview_sha256": preview["preview_sha256"],
            "acknowledged_assumptions": ["null_and_exception_timing", "single_threaded"],
        })).await;
        assert_eq!(status, StatusCode::OK, "{applied}");

        // multi-file verification: all files of each revision compile together
        let (status, verify) = post_json(&app, &cookie, &format!("{base}/verify"), serde_json::json!({
            "baseline_revision": 1, "candidate_revision": 2, "source_level": "17",
            "test_cases": [{"name": "one", "stdin": "1\n"}, {"name": "medium", "stdin": "250\n"}],
        })).await;
        assert_eq!(status, StatusCode::OK, "{verify}");
        assert_eq!(verify["status"], "passed", "{verify}");
        assert_eq!(verify["baseline_compilation"]["main_class"], "m.Main");

        // an explicit but wrong main class is reported, not guessed
        let (_, wrong) = post_json(&app, &cookie, &format!("{base}/verify"), serde_json::json!({
            "baseline_revision": 1, "candidate_revision": 2, "main_class": "m.Nope", "source_level": "17",
            "test_cases": [{"name": "t", "stdin": "1\n"}],
        })).await;
        assert_eq!(wrong["status"], "compilation_failed");
        assert_eq!(wrong["baseline_compilation"]["status"], "missing_main");

        let (status, report) = post_json(
            &app,
            &cookie,
            &format!("{base}/benchmark"),
            serde_json::json!({
                "baseline_revision": 1, "candidate_revision": 2, "source_level": "17",
                "workloads": [{"name": "n=2000", "size": 2000, "stdin": "2000\n"}],
                "warmup_iterations": 3, "measured_iterations": 15,
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{report}");
        assert_eq!(report["settings"]["main_class"], "m.Main");
        assert_eq!(report["functional"]["equivalent_on_all_workloads"], true);
        assert_eq!(
            report["runtime"]["workloads"][0]["verdict"], "faster",
            "{report}"
        );
        assert_eq!(
            report["static_complexity"]["changes"][0]["after"],
            "O(n + m)"
        );
    }

    #[tokio::test]
    #[ignore = "requires parser JAR, JDK and Docker with the runner image (BLUEPRINTS_REAL_JAR/JAVA/RUNNER_IMAGE)"]
    async fn real_optimize_verify_and_benchmark_end_to_end() {
        let data = TestData::new();
        let Some(app) = test_app_with_parser_and_runner(&data).await else {
            return;
        };
        let cookie = guest_cookie(&app).await;
        let project =
            response_json(upload_source(&app, &cookie, "Linker.java", LINKER_PROGRAM).await).await;
        let id = project["id"].as_str().unwrap();
        let base = format!("/api/v1/projects/{id}");

        // detect -> preview -> explicit apply
        let list = get_with_cookie(&app, &cookie, &format!("{base}/optimizations")).await;
        let list: Value =
            serde_json::from_slice(&to_bytes(list.into_body(), 4 << 20).await.unwrap()).unwrap();
        let proposal = &list["proposals"][0];
        assert_eq!(proposal["applicability"], "applicable", "{proposal}");
        let proposal_id = proposal["id"].as_str().unwrap().to_owned();
        let assumptions: Vec<Value> = proposal["assumptions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|a| a["id"].clone())
            .collect();
        let (status, preview) = post_json(
            &app,
            &cookie,
            &format!("{base}/optimizations/{proposal_id}/preview"),
            serde_json::json!({"base_revision": 1}),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{preview}");
        let (status, applied) = post_json(&app, &cookie, &format!("{base}/optimizations/{proposal_id}/apply"),
            serde_json::json!({"base_revision": 1, "preview_sha256": preview["preview_sha256"], "acknowledged_assumptions": assumptions})).await;
        assert_eq!(status, StatusCode::OK, "{applied}");
        assert_eq!(applied["revision"], 2);

        // functional verification (stdout/stderr/exit code on fixtures) in the isolated runner
        let (status, verify) = post_json(&app, &cookie, &format!("{base}/verify"), serde_json::json!({
            "baseline_revision": 1, "candidate_revision": 2,
            "test_cases": [{"name": "tiny", "stdin": "3\n"}, {"name": "small", "stdin": "40\n"}, {"name": "medium", "stdin": "300\n"}],
        })).await;
        assert_eq!(status, StatusCode::OK, "{verify}");
        assert_eq!(verify["status"], "passed", "{verify}");
        assert_eq!(verify["behaviorally_verified"], true);

        // runtime benchmark: separate static / functional / runtime sections
        let workloads = serde_json::json!([
            {"name": "n=300", "size": 300, "stdin": "300\n"},
            {"name": "n=1200", "size": 1200, "stdin": "1200\n"},
            {"name": "n=2500", "size": 2500, "stdin": "2500\n"},
        ]);
        let (status, report) = post_json(
            &app,
            &cookie,
            &format!("{base}/benchmark"),
            serde_json::json!({
                "baseline_revision": 1, "candidate_revision": 2, "workloads": workloads,
                "warmup_iterations": 3, "measured_iterations": 15,
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{report}");
        assert_eq!(report["status"], "completed", "{report}");
        // 1. static Big O
        let change = &report["static_complexity"]["changes"][0];
        assert_eq!(
            change["method_id"],
            "Linker.link(List<Task>,List<Contact>,Map<Integer, String>)"
        );
        assert_eq!(
            (change["before"].as_str(), change["after"].as_str()),
            (Some("O(n*m)"), Some("O(n + m)"))
        );
        // 2. functional outcome
        assert_eq!(report["functional"]["equivalent_on_all_workloads"], true);
        // 3. measured runtime, with the evidence behind any claim
        let biggest = &report["runtime"]["workloads"][2];
        assert_eq!(biggest["baseline_stats"]["iterations"], 15);
        assert_eq!(biggest["verdict"], "faster", "{biggest}");
        assert!(biggest["speedup"].as_f64().unwrap() > 2.0, "{biggest}");
        assert!(biggest["speedup_ci95"][0].as_f64().unwrap() > 1.0);
        assert!(biggest["p_value"].as_f64().unwrap() < 0.01);
        assert_eq!(
            report["runtime"]["overall"]["equivalent_on_all_workloads"],
            true
        );
        assert_eq!(report["settings"]["measured_iterations"], 15);
        assert_eq!(report["environment"]["cpu_limit"], "1");
        assert!(report["environment"]["baseline_jvm"]["java_version"]
            .as_str()
            .unwrap()
            .starts_with("21"));
        assert!(
            report["timings"]["baseline"]["compile_and_setup_ms"]
                .as_u64()
                .unwrap()
                > 0
        );
        assert_eq!(biggest["stdin_sha256"].as_str().unwrap().len(), 64);
        // the stored report can be fetched again
        let fetched = get_with_cookie(
            &app,
            &cookie,
            &format!("{base}/benchmark/{}", report["report_id"].as_str().unwrap()),
        )
        .await;
        assert_eq!(fetched.status(), StatusCode::OK);

        // too few iterations never produce a speedup claim
        let (_, few) = post_json(&app, &cookie, &format!("{base}/benchmark"), serde_json::json!({
            "baseline_revision": 1, "candidate_revision": 2, "workloads": [{"name": "n=2500", "stdin": "2500\n"}],
            "warmup_iterations": 1, "measured_iterations": 4,
        })).await;
        assert_eq!(
            few["runtime"]["workloads"][0]["verdict"], "insufficient_evidence",
            "{few}"
        );
        assert_eq!(few["runtime"]["workloads"][0]["speedup"], Value::Null);

        // a "faster" candidate that changes behaviour is reported as not equivalent, not as an optimization
        let broken = LINKER_PROGRAM.replace("linked++;", "linked += 2;");
        let updated = put_json(
            &app,
            &cookie,
            &format!("{base}/source"),
            serde_json::json!({"source": broken, "expected_revision": 2}),
        )
        .await;
        assert_eq!(updated.status(), StatusCode::OK);
        let (_, wrong) = post_json(&app, &cookie, &format!("{base}/benchmark"), serde_json::json!({
            "baseline_revision": 1, "candidate_revision": 3, "workloads": [{"name": "n=300", "stdin": "300\n"}],
            "warmup_iterations": 1, "measured_iterations": 10,
        })).await;
        assert_eq!(
            wrong["runtime"]["overall"]["verdict"], "not_equivalent",
            "{wrong}"
        );
        assert_eq!(wrong["functional"]["equivalent_on_all_workloads"], false);
        assert_eq!(wrong["runtime"]["workloads"][0]["speedup"], Value::Null);
    }

    #[tokio::test]
    #[ignore = "requires a built parser JAR and a JDK via BLUEPRINTS_REAL_JAR/BLUEPRINTS_REAL_JAVA"]
    async fn real_cfg_edit_end_to_end_single_file_and_archive() {
        let data = TestData::new();
        let app = test_app_with_real_worker(&data).await;
        let cookie = guest_cookie(&app).await;

        let single = response_json(upload_source(&app, &cookie, "Calc.java", EDIT_SRC).await).await;
        let id = single["id"].as_str().unwrap();
        run_cfg_edit_flow(
            &app,
            &cookie,
            &format!("/api/v1/projects/{id}"),
            "Calc.run(int,int)",
            "21",
        )
        .await;

        let files = vec![
            (
                "src/main/java/Other.java",
                "public class Other { public int one() { return 1; } }\n".to_owned(),
            ),
            ("src/main/java/Calc.java", EDIT_SRC.to_owned()),
        ];
        let archive =
            response_json(upload_bytes(&app, &cookie, "calc.zip", &zip_bytes(&files)).await).await;
        let id = archive["id"].as_str().unwrap();
        run_cfg_edit_flow(
            &app,
            &cookie,
            &format!("/api/v1/projects/{id}"),
            "Calc.run(int,int)",
            "17",
        )
        .await;
        // the untouched file was copied byte for byte into revision 2
        let other = get_with_cookie(
            &app,
            &cookie,
            &format!("/api/v1/projects/{id}/files/src/main/java/Other.java"),
        )
        .await;
        assert_eq!(
            to_bytes(other.into_body(), 4096).await.unwrap(),
            files[0].1.as_bytes()
        );
    }

    #[tokio::test]
    #[ignore = "requires a built parser JAR and a JDK via BLUEPRINTS_REAL_JAR/BLUEPRINTS_REAL_JAVA"]
    async fn real_optimization_workflow_end_to_end() {
        let files: Vec<(&str, String)> = vec![
            ("src/main/java/m/Customer.java", OPT_CUSTOMER.into()),
            ("src/main/java/m/Order.java", OPT_ORDER.into()),
            ("src/main/java/m/Service.java", OPT_SERVICE.into()),
        ];
        let data = TestData::new();
        let app = test_app_with_real_worker(&data).await;
        let cookie = guest_cookie(&app).await;
        let project =
            response_json(upload_bytes(&app, &cookie, "shop.zip", &zip_bytes(&files)).await).await;
        let id = project["id"].as_str().unwrap();
        let base = format!("/api/v1/projects/{id}");
        let (status, analysis) = post_json(
            &app,
            &cookie,
            &format!("{base}/analyze?source_level=17"),
            Value::Null,
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{analysis}");

        // 1. detect
        let list = get_with_cookie(
            &app,
            &cookie,
            &format!("{base}/optimizations?source_level=17"),
        )
        .await;
        assert_eq!(list.status(), StatusCode::OK);
        let list: Value =
            serde_json::from_slice(&to_bytes(list.into_body(), 4 << 20).await.unwrap()).unwrap();
        let proposal = &list["proposals"][0];
        assert_eq!(proposal["applicability"], "applicable");
        assert_eq!(proposal["file"], "src/main/java/m/Service.java");
        let proposal_id = proposal["id"].as_str().unwrap().to_owned();
        let assumptions: Vec<Value> = proposal["assumptions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|a| a["id"].clone())
            .collect();
        assert_eq!(assumptions.len(), 2);

        // 2. preview: exact diff, nothing stored
        let (status, preview) = post_json(
            &app,
            &cookie,
            &format!("{base}/optimizations/{proposal_id}/preview"),
            serde_json::json!({"base_revision": 1, "source_level": "17"}),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{preview}");
        assert_eq!(preview["validation"], "valid");
        let unified = preview["diff"][0]["unified_diff"].as_str().unwrap();
        assert!(
            unified.contains("+        Map<Integer, Customer> customersById = new HashMap<>();"),
            "{unified}"
        );
        assert!(
            unified.contains("-                if (customer.id() == order.customerId()) {"),
            "{unified}"
        );
        assert_eq!(preview["method_complexity_before"], "O(n*m)");
        assert_eq!(preview["method_complexity_after"], "O(n + m)");
        let current = get_with_cookie(&app, &cookie, &base).await;
        assert_eq!(response_json(current).await["revision"], 1);

        // 3. apply needs the right revision, the previewed diff and acknowledged assumptions
        let apply_uri = format!("{base}/optimizations/{proposal_id}/apply");
        let sha = preview["preview_sha256"].clone();
        let (status, body) = post_json(&app, &cookie, &apply_uri, serde_json::json!({
            "base_revision": 7, "source_level": "17", "preview_sha256": sha, "acknowledged_assumptions": assumptions,
        })).await;
        assert_eq!(
            (status, body["code"].as_str()),
            (StatusCode::CONFLICT, Some("revision_conflict"))
        );
        let (status, body) = post_json(&app, &cookie, &apply_uri, serde_json::json!({
            "base_revision": 1, "source_level": "17", "preview_sha256": "0000", "acknowledged_assumptions": assumptions,
        })).await;
        assert_eq!(
            (status, body["code"].as_str()),
            (StatusCode::CONFLICT, Some("preview_mismatch"))
        );
        let (status, body) = post_json(&app, &cookie, &apply_uri, serde_json::json!({
            "base_revision": 1, "source_level": "17", "preview_sha256": sha, "acknowledged_assumptions": [],
        })).await;
        assert_eq!(
            (status, body["code"].as_str()),
            (
                StatusCode::UNPROCESSABLE_ENTITY,
                Some("assumptions_not_acknowledged")
            )
        );
        let (status, body) = post_json(&app, &cookie, &format!("{base}/optimizations/opt_0000000000000000/apply"), serde_json::json!({
            "base_revision": 1, "source_level": "17", "preview_sha256": sha, "acknowledged_assumptions": assumptions,
        })).await;
        assert_eq!(
            (status, body["code"].as_str()),
            (StatusCode::NOT_FOUND, Some("proposal_not_found"))
        );
        assert_eq!(
            response_json(get_with_cookie(&app, &cookie, &base).await).await["revision"],
            1
        );

        // 4. explicit apply -> revision 2, re-analysed
        let (status, applied) = post_json(&app, &cookie, &apply_uri, serde_json::json!({
            "base_revision": 1, "source_level": "17", "preview_sha256": sha, "acknowledged_assumptions": assumptions,
        })).await;
        assert_eq!(status, StatusCode::OK, "{applied}");
        assert_eq!(applied["revision"], 2);
        assert_eq!(applied["analysis_status"], "completed");
        let complexity = get_with_cookie(&app, &cookie, &format!("{base}/complexity")).await;
        let complexity: Value =
            serde_json::from_slice(&to_bytes(complexity.into_body(), 4 << 20).await.unwrap())
                .unwrap();
        let attach = complexity["methods"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| {
                m["method_id"]
                    == "m.Service.attach(List<Order>,List<Customer>,Map<Integer, Integer>)"
            })
            .unwrap();
        assert_eq!(complexity["revision"], 2);
        assert_eq!(attach["time_complexity"], "O(n + m)");
        // untouched files are byte-identical; the file list is unchanged
        let manifest =
            response_json(get_with_cookie(&app, &cookie, &format!("{base}/files")).await).await;
        assert_eq!(manifest["files"].as_array().unwrap().len(), 3);

        // 5. revision comparison and rollback
        let compare = get_with_cookie(&app, &cookie, &format!("{base}/revisions/1/diff/2")).await;
        let compare: Value =
            serde_json::from_slice(&to_bytes(compare.into_body(), 4 << 20).await.unwrap()).unwrap();
        assert_eq!(compare["files"].as_array().unwrap().len(), 1);
        assert_eq!(compare["files"][0]["path"], "src/main/java/m/Service.java");
        assert_eq!(
            compare["files"][0]["unified_diff"],
            preview["diff"][0]["unified_diff"]
        );
        let (status, rolled) = post_json(
            &app,
            &cookie,
            &format!("{base}/rollback"),
            serde_json::json!({"base_revision": 1, "to_revision": 1}),
        )
        .await;
        assert_eq!(
            (status, rolled["code"].as_str()),
            (StatusCode::CONFLICT, Some("revision_conflict"))
        );
        let (status, rolled) = post_json(
            &app,
            &cookie,
            &format!("{base}/rollback"),
            serde_json::json!({"base_revision": 2, "to_revision": 1}),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{rolled}");
        assert_eq!(rolled["revision"], 3);
        let restored = get_with_cookie(
            &app,
            &cookie,
            &format!("{base}/files/src/main/java/m/Service.java"),
        )
        .await;
        let restored = to_bytes(restored.into_body(), 65536).await.unwrap();
        assert_eq!(String::from_utf8_lossy(&restored), OPT_SERVICE);
        let back = get_with_cookie(&app, &cookie, &format!("{base}/revisions/1/diff/3")).await;
        let back: Value =
            serde_json::from_slice(&to_bytes(back.into_body(), 1 << 20).await.unwrap()).unwrap();
        assert!(back["files"].as_array().unwrap().is_empty());
        // revision 3 carries the (re-stamped) analysis that revision 1 had
        let complexity = get_with_cookie(&app, &cookie, &format!("{base}/complexity")).await;
        let complexity: Value =
            serde_json::from_slice(&to_bytes(complexity.into_body(), 4 << 20).await.unwrap())
                .unwrap();
        assert_eq!(complexity["revision"], 3);
    }

    #[tokio::test]
    #[ignore = "requires a built parser JAR and a JDK via BLUEPRINTS_REAL_JAR/BLUEPRINTS_REAL_JAVA"]
    async fn real_multi_file_zip_analysis_end_to_end() {
        let files: Vec<(&str, String)> = vec![
            (
                "src/main/java/com/acme/model/Customer.java",
                "package com.acme.model;\npublic class Customer {\n    private final int id;\n    public Customer(int id) { this.id = id; }\n    public int id() { return id; }\n}\n".into(),
            ),
            (
                "src/main/java/com/acme/service/Repo.java",
                "package com.acme.service;\nimport com.acme.model.Customer;\nimport java.util.ArrayList;\nimport java.util.List;\npublic class Repo {\n    private final List<Customer> items = new ArrayList<Customer>();\n    public void add(Customer c) { items.add(c); }\n    public Customer find(int id) {\n        for (Customer c : items) { if (c.id() == id) { return c; } }\n        return null;\n    }\n}\n".into(),
            ),
            (
                "src/main/java/com/acme/Main.java",
                "package com.acme;\nimport com.acme.model.Customer;\nimport com.acme.service.Repo;\npublic class Main {\n    public static void main(String[] args) {\n        Repo repo = new Repo();\n        repo.add(new Customer(1));\n        System.out.println(repo.find(1) != null);\n    }\n}\n".into(),
            ),
        ];
        let data = TestData::new();
        let app = test_app_with_real_worker(&data).await;
        let cookie = guest_cookie(&app).await;
        let project =
            response_json(upload_bytes(&app, &cookie, "app.zip", &zip_bytes(&files)).await).await;
        let id = project["id"].as_str().unwrap();

        let analysis = app
            .clone()
            .oneshot(
                Request::post(format!("/api/v1/projects/{id}/analyze?source_level=8"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(analysis.status(), StatusCode::OK);
        let analysis = response_json(analysis).await;
        assert_eq!(analysis["status"], "completed");
        let methods = analysis["methods"].as_array().unwrap();
        assert!(methods
            .iter()
            .any(|m| m["id"] == "com.acme.service.Repo.find(int)"
                && m["source_range"]["file"] == "src/main/java/com/acme/service/Repo.java"));

        // Methods endpoint lists CFG-bearing methods of every file with their selectors.
        let listed =
            get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/methods")).await;
        let listed: Value =
            serde_json::from_slice(&to_bytes(listed.into_body(), 4 << 20).await.unwrap()).unwrap();
        let find = listed["methods"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["id"] == "com.acme.service.Repo.find(int)")
            .expect("cross-file method is listed");
        let selector = find["selector"].as_str().unwrap();
        let cfg = get_with_cookie(
            &app,
            &cookie,
            &format!("/api/v1/projects/{id}/methods/{selector}/cfg"),
        )
        .await;
        assert_eq!(cfg.status(), StatusCode::OK);
        let cfg: Value =
            serde_json::from_slice(&to_bytes(cfg.into_body(), 4 << 20).await.unwrap()).unwrap();
        assert!(cfg["nodes"].as_array().unwrap().len() > 3);

        // Project graph holds the cross-file call edge Main.main -> Repo.find.
        let graph = get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/graph")).await;
        let graph: Value =
            serde_json::from_slice(&to_bytes(graph.into_body(), 4 << 20).await.unwrap()).unwrap();
        let nodes = graph["graph"]["nodes"].as_array().unwrap();
        let node_id =
            |method: &str| nodes.iter().find(|n| n["method_id"] == method).unwrap()["id"].clone();
        let (from, to) = (
            node_id("com.acme.Main.main(String[])"),
            node_id("com.acme.service.Repo.find(int)"),
        );
        assert!(graph["graph"]["edges"]
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["kind"] == "calls" && e["from"] == from && e["to"] == to));

        // Complexity estimates are served separately from findings, with engine and limitations.
        let complexity =
            get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/complexity")).await;
        assert_eq!(complexity.status(), StatusCode::OK);
        let complexity: Value =
            serde_json::from_slice(&to_bytes(complexity.into_body(), 4 << 20).await.unwrap())
                .unwrap();
        let find_entry = complexity["methods"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["method_id"] == "com.acme.service.Repo.find(int)")
            .unwrap();
        assert_eq!(find_entry["time_complexity"], "O(n)");
        assert_eq!(find_entry["space_complexity"], Value::Null);
        assert_eq!(find_entry["analysis_engine"], "jdt-static-cost-model/2");
        assert_eq!(
            find_entry["file"],
            "src/main/java/com/acme/service/Repo.java"
        );
        assert!(!find_entry["limitations"].as_array().unwrap().is_empty());
        assert!(!find_entry["cfg_node_ids"].as_array().unwrap().is_empty());
        let findings =
            get_with_cookie(&app, &cookie, &format!("/api/v1/projects/{id}/findings")).await;
        assert_eq!(findings.status(), StatusCode::OK);

        let rejected = app
            .clone()
            .oneshot(
                Request::post(format!("/api/v1/projects/{id}/analyze?source_level=1.4"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(rejected.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    #[ignore = "requires a built parser JAR and OpenJDK 21 via BLUEPRINTS_REAL_JAR/BLUEPRINTS_REAL_JAVA"]
    async fn real_ast_rewrite_http_end_to_end() {
        const SOURCE: &str = "public class Example {\n    public static void main(String[] args) {\n        int count = 1;\n        if (count > 0) {\n            System.out.println(\"positive\");\n        }\n    }\n}\n";

        let data = TestData::new();
        let app = test_app_with_real_worker(&data).await;
        let cookie = guest_cookie(&app).await;
        let project =
            response_json(upload_source(&app, &cookie, "Example.java", SOURCE).await).await;
        let project_id = project["id"].as_str().unwrap();
        let directory = project_directory(&data, &cookie, project_id);

        let response = app
            .clone()
            .oneshot(
                Request::post(format!("/api/v1/projects/{project_id}/analyze"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let initial_analysis = response_json(response).await;
        assert_eq!(initial_analysis["status"], "completed");
        assert!(initial_analysis["diagnostics"]
            .as_array()
            .unwrap()
            .iter()
            .all(|diagnostic| !diagnostic["message"]
                .as_str()
                .unwrap_or_default()
                .contains("must be defined in its own file")));

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/graph"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let graph_response = response_json(response).await;
        assert_eq!(graph_response["revision"], 1);
        assert_eq!(graph_response["graph"]["version"], 1);
        assert_eq!(graph_response["graph"]["scope"], "compilation_unit");
        assert!(graph_response["graph"]["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .any(
                |node| node["method_id"].as_str() == Some("Example.main(String[])")
                    && node["cfg_entry"].as_str() == Some("m0.n0")
            ));
        assert!(graph_response["graph"]["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .any(|node| node["name"] == "Example"));
        // The endpoint must return the parser's persisted graph verbatim.
        let artifact: Value =
            serde_json::from_slice(&fs::read(directory.join("analysis/1.json")).await.unwrap())
                .unwrap();
        assert_eq!(graph_response["graph"], artifact["project_graph"]);

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let methods = response_json(response).await;
        assert_eq!(methods["revision"], 1);
        assert_eq!(methods["methods"][0]["id"], "Example.main(String[])");
        let selector = methods["methods"][0]["selector"].as_str().unwrap();

        let response = app
            .clone()
            .oneshot(
                Request::get(format!(
                    "/api/v1/projects/{project_id}/methods/{selector}/cfg"
                ))
                .header(header::COOKIE, &cookie)
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let cfg = response_json(response).await;
        let branch = cfg["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|node| node["text"] == "count > 0")
            .unwrap();
        let node_id = branch["id"].as_str().unwrap().to_owned();
        assert_eq!(node_id, "m0.n3");
        assert_eq!(
            branch["source_range"]["start"],
            SOURCE[..SOURCE.find("count > 0").unwrap()]
                .encode_utf16()
                .count()
        );
        assert_eq!(
            branch["source_range"]["length"],
            "count > 0".encode_utf16().count()
        );

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.main(String[])",
                &node_id,
                "count >= 0",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let transformed = response_json(response).await;
        assert_eq!(transformed["revision"], 2);
        assert!(transformed["source"]
            .as_str()
            .unwrap()
            .contains("if (count >= 0)"));

        assert_eq!(
            fs::read_to_string(directory.join("revisions/1/Example.java"))
                .await
                .unwrap(),
            SOURCE
        );
        assert!(
            fs::read_to_string(directory.join("revisions/2/Example.java"))
                .await
                .unwrap()
                .contains("if (count >= 0)")
        );
        assert!(!directory.join("revisions/2/Out.java").exists());

        let response = app
            .clone()
            .oneshot(
                Request::post(format!("/api/v1/projects/{project_id}/analyze"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let updated_analysis = response_json(response).await;
        assert_eq!(updated_analysis["revision"], 2);
        assert_eq!(updated_analysis["status"], "completed");
        assert!(updated_analysis["diagnostics"]
            .as_array()
            .unwrap()
            .iter()
            .all(|diagnostic| !diagnostic["message"]
                .as_str()
                .unwrap_or_default()
                .contains("must be defined in its own file")));

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let methods = response_json(response).await;
        assert_eq!(methods["revision"], 2);
        let selector = methods["methods"][0]["selector"].as_str().unwrap();
        let response = app
            .clone()
            .oneshot(
                Request::get(format!(
                    "/api/v1/projects/{project_id}/methods/{selector}/cfg"
                ))
                .header(header::COOKIE, &cookie)
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        let updated_cfg = response_json(response).await;
        let updated_branch = updated_cfg["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|node| node["text"] == "count >= 0")
            .unwrap();
        assert_eq!(updated_branch["id"], "m0.n3");
        assert_eq!(updated_branch["source_range"]["start"], 103);
        assert_eq!(updated_branch["source_range"]["length"], 10);

        let stale = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.main(String[])",
                "m0.n3",
                "count == 0",
            ))
            .await
            .unwrap();
        assert_eq!(stale.status(), StatusCode::CONFLICT);

        let unknown = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                2,
                "Example.main(String[])",
                "m0.n99",
                "count == 0",
            ))
            .await
            .unwrap();
        assert_eq!(unknown.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(unknown).await["code"], "node_not_found");

        let invalid = app
            .oneshot(transform_request(
                &cookie,
                project_id,
                2,
                "Example.main(String[])",
                "m0.n3",
                "count +",
            ))
            .await
            .unwrap();
        assert_eq!(invalid.status(), StatusCode::UNPROCESSABLE_ENTITY);
        let invalid = response_json(invalid).await;
        assert_eq!(invalid["code"], "transformation_failed");
        assert_eq!(
            invalid["message"],
            "Replacement expression contains a syntax error"
        );
        let metadata: Value =
            serde_json::from_slice(&fs::read(directory.join("project.json")).await.unwrap())
                .unwrap();
        assert_eq!(metadata["revision"], 2);
        assert!(!directory.join("revisions/3/Example.java").exists());
        assert!(std::fs::read_dir(&directory).unwrap().all(|entry| !entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".transform-")));
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn transform_commits_validated_source_and_analysis_as_a_new_revision() {
        let data = TestData::new();
        let app = test_app_with_worker(&data, "success", Duration::from_secs(1)).await;
        let cookie = guest_cookie(&app).await;
        let source =
            "class Example { int process(int count) { if (count > 0) return 1; return 0; } }";
        let project =
            response_json(upload_source(&app, &cookie, "Example.java", source).await).await;
        let project_id = project["id"].as_str().unwrap();
        store_analysis(&data, &cookie, project_id, &cfg_artifact(1)).await;

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n2",
                "count >= 0",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let transformed = response_json(response).await;
        assert_eq!(transformed["revision"], 2);
        assert!(transformed["source"]
            .as_str()
            .unwrap()
            .contains("count >= 0"));

        let directory = project_directory(&data, &cookie, project_id);
        let stored_source = fs::read_to_string(directory.join("revisions/2/Example.java"))
            .await
            .unwrap();
        assert!(stored_source.contains("count >= 0"));
        let stored_analysis: Value =
            serde_json::from_slice(&fs::read(directory.join("analysis/2.json")).await.unwrap())
                .unwrap();
        assert_eq!(stored_analysis["revision"], 2);
        assert_eq!(
            stored_analysis["types"][0]["members"][0]["cfg"]["entry"],
            "m0.n0"
        );
        assert!(std::fs::read_dir(&directory).unwrap().all(|entry| !entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".transform-")));

        let stale = app
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n2",
                "count == 0",
            ))
            .await
            .unwrap();
        assert_eq!(stale.status(), StatusCode::CONFLICT);
        assert_eq!(response_json(stale).await["code"], "revision_conflict");
    }

    #[tokio::test]
    async fn transform_rejects_unknown_method_and_node_before_worker_invocation() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        store_analysis(&data, &cookie, project_id, &cfg_artifact(1)).await;

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.missing()",
                "m1.n2",
                "true",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "method_not_found");

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n99",
                "true",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "node_not_found");

        let response = app
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n0",
                "true",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(
            response_json(response).await["code"],
            "invalid_transform_target"
        );
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn transform_rejects_invalid_expression_and_preserves_revision() {
        let data = TestData::new();
        let app = test_app_with_worker(&data, "success", Duration::from_secs(1)).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        store_analysis(&data, &cookie, project_id, &cfg_artifact(1)).await;

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n2",
                "bad +",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        let error = response_json(response).await;
        assert_eq!(error["code"], "transformation_failed");
        assert_eq!(error["message"], "invalid Java expression");

        let metadata = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response_json(metadata).await["revision"], 1);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn transform_rejects_worker_failure_timeout_and_invalid_candidate() {
        for (mode, timeout, expected_status, expected_code) in [
            (
                "failure",
                Duration::from_secs(1),
                StatusCode::UNPROCESSABLE_ENTITY,
                "transformation_failed",
            ),
            (
                "timeout",
                Duration::from_millis(30),
                StatusCode::GATEWAY_TIMEOUT,
                "transform_timeout",
            ),
            (
                "analysis-invalid",
                Duration::from_secs(1),
                StatusCode::UNPROCESSABLE_ENTITY,
                "transformation_invalid",
            ),
        ] {
            let data = TestData::new();
            let app = test_app_with_worker(&data, mode, timeout).await;
            let cookie = guest_cookie(&app).await;
            let project = response_json(upload(&app, &cookie, "Example.java").await).await;
            let project_id = project["id"].as_str().unwrap();
            store_analysis(&data, &cookie, project_id, &cfg_artifact(1)).await;

            let response = app
                .oneshot(transform_request(
                    &cookie,
                    project_id,
                    1,
                    "Example.process(int)",
                    "m1.n2",
                    "count >= 0",
                ))
                .await
                .unwrap();
            assert_eq!(response.status(), expected_status, "mode {mode}");
            assert_eq!(response_json(response).await["code"], expected_code);
            assert!(!project_directory(&data, &cookie, project_id)
                .join("revisions/2/Example.java")
                .exists());
        }
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn concurrent_transforms_cannot_overwrite_each_other() {
        let data = TestData::new();
        let app = test_app_with_worker(&data, "success", Duration::from_secs(1)).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap().to_owned();
        store_analysis(&data, &cookie, &project_id, &cfg_artifact(1)).await;

        let first = app.clone().oneshot(transform_request(
            &cookie,
            &project_id,
            1,
            "Example.process(int)",
            "m1.n2",
            "count >= 0",
        ));
        let second = app.clone().oneshot(transform_request(
            &cookie,
            &project_id,
            1,
            "Example.process(int)",
            "m1.n2",
            "count <= 0",
        ));
        let (first, second) = tokio::join!(first, second);
        let statuses = [first.unwrap().status(), second.unwrap().status()];
        assert_eq!(
            statuses
                .iter()
                .filter(|status| **status == StatusCode::OK)
                .count(),
            1
        );
        assert_eq!(
            statuses
                .iter()
                .filter(|status| **status == StatusCode::CONFLICT)
                .count(),
            1
        );
        let metadata: Value = serde_json::from_slice(
            &fs::read(project_directory(&data, &cookie, &project_id).join("project.json"))
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(metadata["revision"], 2);
        assert!(!project_directory(&data, &cookie, &project_id)
            .join("revisions/3/Example.java")
            .exists());
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn verify_compiles_original_filename_and_handles_multiple_utf8_inputs() {
        let data = TestData::new();
        let app = test_app_with_runner(&data, "", Duration::from_secs(1), 4096).await;
        let cookie = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let source = "// ECHO_STDOUT REQUIRE_EXAMPLE_FILENAME\npublic class Example { public static void main(String[] args) {} }\n";
        let project_id = project_with_revisions(&app, &cookie, source, source).await;
        let cases = serde_json::json!([
            {"name": "positive", "stdin": "5\n"},
            {"name": "negative", "stdin": "-3\n"},
            {"name": "cyrillic", "stdin": "Привет, мир!\n"}
        ]);

        let response = app
            .clone()
            .oneshot(verify_request(&cookie, &project_id, 1, 2, cases))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let report = response_json(response).await;
        let report_id = report["report_id"].as_str().unwrap();
        assert_eq!(report["status"], "passed");
        assert_eq!(report["behaviorally_verified"], true);
        assert_eq!(report["requested_test_cases"], 3);
        assert_eq!(report["baseline_compilation"]["status"], "completed");
        assert_eq!(report["candidate_compilation"]["main_class"], "Example");
        assert_eq!(report["executed_test_cases"], 3);
        assert_eq!(
            report["test_cases"][2]["baseline"]["stdout"]["utf8"],
            "Привет, мир!\n"
        );
        let response = app
            .clone()
            .oneshot(verification_report_request(&cookie, &project_id, report_id))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response_json(response).await, report);

        let response = app
            .clone()
            .oneshot(verification_report_request(&other, &project_id, report_id))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "project_not_found");

        let response = app
            .oneshot(verification_report_request(
                &cookie,
                &project_id,
                &uuid::Uuid::new_v4().to_string(),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(
            response_json(response).await["code"],
            "verification_report_not_found"
        );
        let state = data.0.join("fake-docker.state");
        assert_eq!(std::fs::read_dir(state).unwrap().count(), 0);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn verify_reports_compilation_failure_and_missing_main_without_running_cases() {
        for (candidate, expected_status, expected_code) in [
            (
                "// COMPILE_ERROR\npublic class Example {}\n",
                "failed",
                "javac_failed",
            ),
            (
                "// NO_MAIN\npublic class Example {}\n",
                "missing_main",
                "main_method_not_found",
            ),
        ] {
            let data = TestData::new();
            let app = test_app_with_runner(&data, "", Duration::from_secs(1), 4096).await;
            let cookie = guest_cookie(&app).await;
            let baseline = "public class Example { public static void main(String[] args) {} }\n";
            let project_id = project_with_revisions(&app, &cookie, baseline, candidate).await;
            let response = app
                .oneshot(verify_request(
                    &cookie,
                    &project_id,
                    1,
                    2,
                    serde_json::json!([{"name": "case", "stdin": ""}]),
                ))
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK);
            let report = response_json(response).await;
            assert_eq!(report["status"], "compilation_failed");
            assert_eq!(report["behaviorally_verified"], false);
            assert_eq!(report["candidate_compilation"]["status"], expected_status);
            assert_eq!(
                report["candidate_compilation"]["diagnostics"][0]["code"],
                expected_code
            );
            assert_eq!(report["executed_test_cases"], 0);
        }
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn verify_reports_stdout_stderr_and_exit_code_differences() {
        for (baseline, candidate, field) in [
            (
                "// STDOUT_ONE\nclass Example {}",
                "// STDOUT_TWO\nclass Example {}",
                "stdout",
            ),
            (
                "// STDERR_ONE\nclass Example {}",
                "// STDERR_TWO\nclass Example {}",
                "stderr",
            ),
            (
                "// EXIT_7\nclass Example {}",
                "// EXIT_8\nclass Example {}",
                "exit_code",
            ),
        ] {
            let data = TestData::new();
            let app = test_app_with_runner(&data, "", Duration::from_secs(1), 4096).await;
            let cookie = guest_cookie(&app).await;
            let project_id = project_with_revisions(&app, &cookie, baseline, candidate).await;
            let response = app
                .oneshot(verify_request(
                    &cookie,
                    &project_id,
                    1,
                    2,
                    serde_json::json!([{"name": "different", "stdin": ""}]),
                ))
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK);
            let report = response_json(response).await;
            assert_eq!(report["status"], "failed");
            assert_eq!(report["behaviorally_verified"], false);
            assert_eq!(report["test_cases"][0]["passed"], false);
            assert_eq!(report["test_cases"][0]["mismatches"][0]["field"], field);
        }
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn verify_records_timeout_and_output_overflow_and_cleans_up() {
        for (marker, timeout, output_bytes, observation_field) in [
            ("TIMEOUT", Duration::from_millis(30), 4096, "timed_out"),
            ("OVERFLOW", Duration::from_secs(1), 32, "output_overflow"),
        ] {
            let data = TestData::new();
            let app = test_app_with_runner(&data, "", timeout, output_bytes).await;
            let cookie = guest_cookie(&app).await;
            let source = format!("// {marker}\nclass Example {{}}\n");
            let project_id = project_with_revisions(&app, &cookie, &source, &source).await;
            let response = app
                .oneshot(verify_request(
                    &cookie,
                    &project_id,
                    1,
                    2,
                    serde_json::json!([{"name": "bounded", "stdin": ""}]),
                ))
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK);
            let report = response_json(response).await;
            assert_eq!(report["status"], "failed");
            assert_eq!(report["behaviorally_verified"], false);
            assert_eq!(report["test_cases"][0]["baseline"][observation_field], true);
            assert_eq!(
                std::fs::read_dir(data.0.join("fake-docker.state"))
                    .unwrap()
                    .count(),
                0
            );
        }
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn verify_rejects_missing_revision_and_cross_session_access() {
        let data = TestData::new();
        let app = test_app_with_runner(&data, "", Duration::from_secs(1), 4096).await;
        let owner = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let project = response_json(upload(&app, &owner, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let cases = serde_json::json!([{"name": "case", "stdin": ""}]);

        let missing = app
            .clone()
            .oneshot(verify_request(&owner, project_id, 1, 2, cases.clone()))
            .await
            .unwrap();
        assert_eq!(missing.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(missing).await["code"], "revision_not_found");

        let forbidden = app
            .oneshot(verify_request(&other, project_id, 1, 1, cases))
            .await
            .unwrap();
        assert_eq!(forbidden.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(forbidden).await["code"], "project_not_found");
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn verify_reports_container_failure_as_infrastructure_error() {
        let data = TestData::new();
        let app = test_app_with_runner(&data, "create-failure", Duration::from_secs(1), 4096).await;
        let cookie = guest_cookie(&app).await;
        let source = "class Example {}\n";
        let project_id = project_with_revisions(&app, &cookie, source, source).await;
        let response = app
            .oneshot(verify_request(
                &cookie,
                &project_id,
                1,
                2,
                serde_json::json!([{"name": "case", "stdin": ""}]),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(response_json(response).await["code"], "runner_failed");
        assert!(!project_directory(&data, &cookie, &project_id)
            .join("verification")
            .exists());
    }

    #[tokio::test]
    async fn verify_reports_unconfigured_runner_without_host_fallback() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let source = "class Example {}\n";
        let project_id = project_with_revisions(&app, &cookie, source, source).await;
        let response = app
            .oneshot(verify_request(
                &cookie,
                &project_id,
                1,
                2,
                serde_json::json!([{"name": "case", "stdin": ""}]),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(response_json(response).await["code"], "runner_unavailable");
        assert!(!project_directory(&data, &cookie, &project_id)
            .join("verification")
            .exists());
    }

    #[tokio::test]
    async fn verify_rejects_missing_fixtures_without_persisting_a_success() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let source = "class Example {}\n";
        let project_id = project_with_revisions(&app, &cookie, source, source).await;
        let response = app
            .oneshot(verify_request(
                &cookie,
                &project_id,
                1,
                2,
                serde_json::json!([]),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        assert!(!project_directory(&data, &cookie, &project_id)
            .join("verification")
            .exists());
    }

    #[tokio::test]
    #[ignore = "requires Docker and a built Java 21 runner image via BLUEPRINTS_REAL_RUNNER_IMAGE"]
    async fn real_container_http_verification() {
        let Some(image) = std::env::var("BLUEPRINTS_REAL_RUNNER_IMAGE").ok() else {
            eprintln!("SKIPPED: BLUEPRINTS_REAL_RUNNER_IMAGE is not set");
            return;
        };
        let container_bin = std::env::var_os("BLUEPRINTS_REAL_CONTAINER_BIN")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("docker"));
        let available = tokio::process::Command::new(&container_bin)
            .args(["image", "inspect", &image])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .await
            .is_ok_and(|status| status.success());
        if !available {
            eprintln!("SKIPPED: Docker is unavailable or the configured runner image is missing");
            return;
        }

        let data = TestData::new();
        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data.0.clone(),
            max_project_bytes: 4096,
            import_limits: Default::default(),
            secure_cookies: false,
            analyzer_jar: None,
            java_bin: "java".into(),
            analyzer_timeout: Duration::from_secs(1),
            analyzer_output_bytes: 1024,
            runner_image: Some(image),
            container_bin,
            runner_compile_timeout: Duration::from_secs(15),
            runner_case_timeout: Duration::from_secs(2),
            runner_output_bytes: 64 * 1024,
            runner_benchmark_timeout: Duration::from_secs(30),
        })
        .await
        .unwrap();
        let app = app(state);
        let cookie = guest_cookie(&app).await;
        let baseline = "import java.io.*; public class Example { public static void main(String[] args) throws Exception { System.out.print(new BufferedReader(new InputStreamReader(System.in)).readLine()); } }\n";
        let candidate = "import java.io.*; public class Example { public static void main(String[] args) throws Exception { String value = new BufferedReader(new InputStreamReader(System.in)).readLine(); System.out.print(value); } }\n";
        let project_id = project_with_revisions(&app, &cookie, baseline, candidate).await;
        let response = app
            .oneshot(verify_request(
                &cookie,
                &project_id,
                1,
                2,
                serde_json::json!([{"name": "utf8", "stdin": "Привет\n"}]),
            ))
            .await
            .unwrap();
        let status = response.status();
        let report = response_json(response).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(report["status"], "passed");
        assert_eq!(
            report["test_cases"][0]["baseline"]["stdout"]["utf8"],
            "Привет"
        );
    }

    #[tokio::test]
    async fn analyze_reports_unavailable_worker() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Main.java").await).await;
        let response = app
            .oneshot(
                Request::post(format!(
                    "/api/v1/projects/{}/analyze",
                    project["id"].as_str().unwrap()
                ))
                .header(header::COOKIE, cookie)
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(
            response_json(response).await["code"],
            "analyzer_unavailable"
        );
    }
}
