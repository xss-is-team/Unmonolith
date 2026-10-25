use std::{io, path::Path, path::PathBuf, process::Stdio, time::Duration};

use axum::http::StatusCode;
use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use serde::{Deserialize, Serialize};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWriteExt},
    process::Command,
    time::timeout,
};
use utoipa::ToSchema;
use uuid::Uuid;

use crate::error::ApiError;

const CONTROL_TIMEOUT: Duration = Duration::from_secs(10);
const STDERR_BYTES: u64 = 16 * 1024;
const WORK_DIRECTORY: &str = "/work";
const BENCH_SOURCE: &str = include_str!("../../runner/Bench.java");

/// CPU and memory caps of a runner container (`docker create --cpus/--memory`).
#[derive(Clone, Copy, Debug)]
pub struct ContainerLimits {
    pub cpus: &'static str,
    pub memory: &'static str,
}

impl ContainerLimits {
    pub const VERIFY: Self = Self {
        cpus: "0.5",
        memory: "256m",
    };
    /// One full CPU and a bigger heap: less scheduler noise, room for the harness plus the program.
    pub const BENCHMARK: Self = Self {
        cpus: "1",
        memory: "512m",
    };
}

/// Raw result of benchmarking one revision (statistics and comparison happen in `benchmark.rs`).
#[derive(Debug)]
pub struct RevisionBenchmark {
    pub compilation: CompilationResult,
    /// Container create+start, source copy, javac and main-class inspection. Not algorithm time.
    pub setup_ms: u64,
    /// Copying and compiling the benchmark harness. Not algorithm time.
    pub harness_setup_ms: u64,
    /// Wall time of the whole in-container benchmark JVM, including its startup.
    pub execution_ms: u64,
    pub limits: ContainerLimits,
    pub timed_out: bool,
    pub environment: Option<serde_json::Value>,
    pub workloads: Vec<serde_json::Value>,
    pub harness_stderr_tail: String,
}

#[derive(Clone, Debug)]
pub struct BenchmarkSettings {
    pub warmup_iterations: u32,
    pub measured_iterations: u32,
    pub fresh_class_loader: bool,
    pub iteration_timeout_ms: u64,
}

#[derive(Clone, Debug)]
pub struct JavaRunner {
    container_bin: PathBuf,
    image: Option<String>,
    compile_timeout: Duration,
    case_timeout: Duration,
    max_output_bytes: u64,
    benchmark_timeout: Duration,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub struct VerifyRequest {
    #[schema(minimum = 1)]
    pub baseline_revision: u64,
    #[schema(minimum = 1)]
    pub candidate_revision: u64,
    pub test_cases: Vec<VerifyTestCase>,
    /// Archive projects only: fully qualified class with `public static void main(String[])`
    /// (e.g. `com.acme.Main`). Required when the project has no single `main` method.
    #[serde(default)]
    pub main_class: Option<String>,
    /// Archive projects only: Java language level of the sources (8, 11, 17 or 21; default 21).
    #[serde(default)]
    pub source_level: Option<String>,
    /// How many times each revision runs every scenario (1..=5, default 1). With more than one run a
    /// revision whose own runs disagree is reported as unstable instead of as a behavior difference.
    #[serde(default)]
    #[schema(minimum = 1, maximum = 5)]
    pub repeats: Option<u8>,
}

/// What to compile in a runner container: the Java files of one revision (container path = project path).
#[derive(Clone, Debug)]
pub struct CompileTarget {
    pub files: Vec<(String, PathBuf)>,
    /// Fully qualified main class; when `None` the single file's own name is used (legacy single-file mode).
    pub main_class: Option<String>,
    /// `javac --release` value.
    pub release: String,
}

impl CompileTarget {
    pub fn single(filename: &str, source: PathBuf) -> Self {
        Self {
            files: vec![(filename.to_owned(), source)],
            main_class: None,
            release: "21".to_owned(),
        }
    }
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub struct VerifyTestCase {
    pub name: String,
    pub stdin: String,
    /// Optional expectation checked against the baseline run (exact UTF-8 text).
    #[serde(default)]
    pub expected_stdout: Option<String>,
    #[serde(default)]
    pub expected_stderr: Option<String>,
    #[serde(default)]
    pub expected_exit_code: Option<i32>,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
pub struct VerificationReport {
    pub status: VerificationStatus,
    #[schema(minimum = 1)]
    pub baseline_revision: u64,
    #[schema(minimum = 1)]
    pub candidate_revision: u64,
    pub baseline_compilation: CompilationResult,
    pub candidate_compilation: CompilationResult,
    pub executed_test_cases: usize,
    pub test_cases: Vec<TestCaseResult>,
    pub explanation: String,
    /// Runs per revision and scenario that were requested; reports stored before this field read as 1.
    #[serde(default = "one_run")]
    pub repeats: u32,
}

fn one_run() -> u32 {
    1
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum VerificationStatus {
    Passed,
    Failed,
    /// No scenario differed between the revisions, but at least one revision disagreed with itself
    /// across repeated runs, so nothing can be concluded.
    Unstable,
    CompilationFailed,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct CompilationResult {
    pub status: CompilationStatus,
    pub exit_code: Option<i32>,
    pub timed_out: bool,
    pub output_overflow: bool,
    pub stdout: CapturedStream,
    pub stderr: CapturedStream,
    pub main_class: Option<String>,
    pub diagnostics: Vec<RunnerDiagnostic>,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum CompilationStatus {
    Completed,
    Failed,
    TimedOut,
    OutputOverflow,
    MissingMain,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct RunnerDiagnostic {
    pub code: String,
    pub message: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct CapturedStream {
    pub utf8: Option<String>,
    pub base64: String,
    pub truncated: bool,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
pub struct TestCaseResult {
    pub name: String,
    pub passed: bool,
    pub mismatches: Vec<BehaviorMismatch>,
    /// Expectations of the scenario that the baseline run did not meet (empty when none were given).
    #[serde(default)]
    pub expectation_failures: Vec<BehaviorMismatch>,
    /// Runs per revision that actually happened (a timed-out or overflowing run is not repeated).
    #[serde(default = "one_run")]
    pub runs: u32,
    pub baseline: ExecutionObservation,
    pub candidate: ExecutionObservation,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
pub struct BehaviorMismatch {
    pub field: MismatchField,
    pub explanation: String,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum MismatchField {
    Stdout,
    Stderr,
    ExitCode,
    TimedOut,
    OutputOverflow,
    /// One revision produced different observations on repeated runs of the same input.
    Unstable,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
pub struct ExecutionObservation {
    pub stdout: CapturedStream,
    pub stderr: CapturedStream,
    pub exit_code: Option<i32>,
    pub timed_out: bool,
    pub output_overflow: bool,
}

struct RawCapture {
    bytes: Vec<u8>,
    overflowed: bool,
}

struct CommandCapture {
    stdout: RawCapture,
    stderr: RawCapture,
    exit_code: Option<i32>,
    success: bool,
    timed_out: bool,
}

struct CompileAttempt {
    report: CompilationResult,
    container: Option<ContainerGuard>,
}

struct ContainerGuard {
    runner: JavaRunner,
    name: String,
    active: bool,
}

impl JavaRunner {
    pub fn new(
        container_bin: PathBuf,
        image: Option<String>,
        compile_timeout: Duration,
        case_timeout: Duration,
        max_output_bytes: u64,
    ) -> Self {
        Self {
            container_bin,
            image,
            compile_timeout,
            case_timeout,
            max_output_bytes,
            benchmark_timeout: Duration::from_secs(120),
        }
    }

    pub fn image(&self) -> Option<&str> {
        self.image.as_deref()
    }

    /// Wall-clock limit of one revision's whole benchmark run (all workloads, all iterations).
    pub fn with_benchmark_timeout(mut self, timeout: Duration) -> Self {
        self.benchmark_timeout = timeout;
        self
    }

    pub async fn verify(
        &self,
        baseline_revision: u64,
        baseline_target: &CompileTarget,
        candidate_revision: u64,
        candidate_target: &CompileTarget,
        test_cases: &[VerifyTestCase],
        repeats: u32,
    ) -> Result<VerificationReport, ApiError> {
        let mut baseline = self.compile_revision(baseline_target).await?;
        let mut candidate = match self.compile_revision(candidate_target).await {
            Ok(candidate) => candidate,
            Err(error) => {
                cleanup_optional(&mut baseline.container).await?;
                return Err(error);
            }
        };

        if baseline.report.status != CompilationStatus::Completed
            || candidate.report.status != CompilationStatus::Completed
        {
            cleanup_optional(&mut baseline.container).await?;
            cleanup_optional(&mut candidate.container).await?;
            return Ok(VerificationReport {
                status: VerificationStatus::CompilationFailed,
                baseline_revision,
                candidate_revision,
                baseline_compilation: baseline.report,
                candidate_compilation: candidate.report,
                executed_test_cases: 0,
                test_cases: Vec::new(),
                explanation: "verification was not run because at least one revision is not a compilable Java program with a conventional main entry point".to_owned(),
                repeats,
            });
        }

        let baseline_main = baseline
            .report
            .main_class
            .clone()
            .expect("completed compilation has a main class");
        let candidate_main = candidate
            .report
            .main_class
            .clone()
            .expect("completed compilation has a main class");
        let mut results = Vec::with_capacity(test_cases.len());

        for (index, test_case) in test_cases.iter().enumerate() {
            let last_case = index + 1 == test_cases.len();
            let mut baseline_runs: Vec<RawObservation> = Vec::new();
            let mut candidate_runs: Vec<RawObservation> = Vec::new();
            for run in 0..repeats {
                let last_run = last_case && run + 1 == repeats;
                // a side whose first run was cut off is not repeated: more runs would only cost time
                let baseline_open = baseline_runs.first().is_none_or(|first| !first.cut_off());
                let candidate_open = candidate_runs.first().is_none_or(|first| !first.cut_off());
                if !baseline_open && !candidate_open {
                    break;
                }
                if baseline_open {
                    let observation = self
                        .run_case(
                            baseline
                                .container
                                .as_ref()
                                .expect("completed compilation has a container"),
                            &baseline_main,
                            test_case.stdin.as_bytes(),
                        )
                        .await?;
                    let restart = observation.cut_off();
                    baseline_runs.push(observation);
                    if restart && !last_run {
                        cleanup_optional(&mut baseline.container).await?;
                        baseline = self.compile_revision(baseline_target).await?;
                        ensure_recompile_succeeded(&baseline.report)?;
                    }
                }
                if candidate_open {
                    let observation = self
                        .run_case(
                            candidate
                                .container
                                .as_ref()
                                .expect("completed compilation has a container"),
                            &candidate_main,
                            test_case.stdin.as_bytes(),
                        )
                        .await?;
                    let restart = observation.cut_off();
                    candidate_runs.push(observation);
                    if restart && !last_run {
                        cleanup_optional(&mut candidate.container).await?;
                        candidate = self.compile_revision(candidate_target).await?;
                        ensure_recompile_succeeded(&candidate.report)?;
                    }
                }
            }
            let runs = baseline_runs.len().max(candidate_runs.len()) as u32;
            let mut unstable = Vec::new();
            for (side, observations) in
                [("baseline", &baseline_runs), ("candidate", &candidate_runs)]
            {
                if observations
                    .iter()
                    .skip(1)
                    .any(|other| !same_observation(&observations[0], other))
                {
                    unstable.push(BehaviorMismatch {
                        field: MismatchField::Unstable,
                        explanation: format!(
                            "the {side} revision produced different observations across {} runs of the same input, so a comparison says nothing",
                            observations.len()
                        ),
                    });
                }
            }
            // differences between the first runs of a self-disagreeing revision are noise, not findings
            let mismatches = if unstable.is_empty() {
                compare_observations(&baseline_runs[0], &candidate_runs[0])
            } else {
                unstable
            };
            let expectation_failures = check_expectations(test_case, &baseline_runs[0]);
            let baseline_observation = baseline_runs.swap_remove(0);
            let candidate_observation = candidate_runs.swap_remove(0);
            results.push(TestCaseResult {
                name: test_case.name.clone(),
                passed: mismatches.is_empty() && expectation_failures.is_empty(),
                mismatches,
                expectation_failures,
                runs,
                baseline: baseline_observation.into_public(),
                candidate: candidate_observation.into_public(),
            });
        }

        cleanup_optional(&mut baseline.container).await?;
        cleanup_optional(&mut candidate.container).await?;
        let passed = results.iter().all(|result| result.passed);
        let only_unstable = !passed
            && results.iter().all(|result| {
                result.expectation_failures.is_empty()
                    && result
                        .mismatches
                        .iter()
                        .all(|mismatch| matches!(mismatch.field, MismatchField::Unstable))
            });
        Ok(VerificationReport {
            status: if passed {
                VerificationStatus::Passed
            } else if only_unstable {
                VerificationStatus::Unstable
            } else {
                VerificationStatus::Failed
            },
            baseline_revision,
            candidate_revision,
            baseline_compilation: baseline.report,
            candidate_compilation: candidate.report,
            executed_test_cases: results.len(),
            test_cases: results,
            explanation: if passed {
                "all provided test cases produced matching observations; this is not a proof of behavioral equivalence".to_owned()
            } else if only_unstable {
                "a revision gave different results on repeated runs of the same input (non-deterministic output such as time or random numbers); the comparison is inconclusive".to_owned()
            } else {
                "one or more provided test cases produced different observable behavior or missed their expectations".to_owned()
            },
            repeats,
        })
    }

    /// Compiles one revision in a fresh container and runs the in-container benchmark harness over the
    /// workloads. Compilation, container start and harness setup are timed separately from the iterations.
    pub async fn benchmark_revision(
        &self,
        target: &CompileTarget,
        workloads: &[Vec<u8>],
        settings: &BenchmarkSettings,
    ) -> Result<RevisionBenchmark, ApiError> {
        let limits = ContainerLimits::BENCHMARK;
        let setup_started = std::time::Instant::now();
        let mut attempt = self.compile_revision_with(target, limits).await?;
        let setup_ms = setup_started.elapsed().as_millis() as u64;
        let compilation = attempt.report.clone();
        let Some(container) = attempt.container.as_mut() else {
            return Ok(RevisionBenchmark {
                compilation,
                setup_ms,
                harness_setup_ms: 0,
                execution_ms: 0,
                limits,
                timed_out: false,
                environment: None,
                workloads: Vec::new(),
                harness_stderr_tail: String::new(),
            });
        };
        let main_class = compilation
            .main_class
            .clone()
            .expect("completed compilation has a main class");
        let result = self
            .run_harness(container, &main_class, workloads, settings)
            .await;
        let cleanup = container.cleanup().await;
        let (harness_setup_ms, execution_ms, timed_out, environment, lines, stderr_tail) = result?;
        cleanup?;
        Ok(RevisionBenchmark {
            compilation,
            setup_ms,
            harness_setup_ms,
            execution_ms,
            limits,
            timed_out,
            environment,
            workloads: lines,
            harness_stderr_tail: stderr_tail,
        })
    }

    #[allow(clippy::type_complexity)]
    async fn run_harness(
        &self,
        container: &ContainerGuard,
        main_class: &str,
        workloads: &[Vec<u8>],
        settings: &BenchmarkSettings,
    ) -> Result<
        (
            u64,
            u64,
            bool,
            Option<serde_json::Value>,
            Vec<serde_json::Value>,
            String,
        ),
        ApiError,
    > {
        let harness_started = std::time::Instant::now();
        let copy = self
            .docker(
                &[
                    "exec",
                    "--interactive",
                    "--workdir",
                    WORK_DIRECTORY,
                    &container.name,
                    "dd",
                    &format!("of={WORK_DIRECTORY}/Bench.java"),
                    "status=none",
                ],
                Some(BENCH_SOURCE.as_bytes()),
                CONTROL_TIMEOUT,
            )
            .await?;
        ensure_control_success(&copy, "could not copy the benchmark harness")?;
        let made = self
            .docker(
                &[
                    "exec",
                    "--workdir",
                    WORK_DIRECTORY,
                    &container.name,
                    "mkdir",
                    "-p",
                    "/work/bench-classes",
                ],
                None,
                CONTROL_TIMEOUT,
            )
            .await?;
        ensure_control_success(&made, "could not create the harness directory")?;
        let compile = self
            .docker(
                &[
                    "exec",
                    "--workdir",
                    WORK_DIRECTORY,
                    &container.name,
                    "javac",
                    "-nowarn",
                    "-d",
                    "/work/bench-classes",
                    "/work/Bench.java",
                ],
                None,
                self.compile_timeout,
            )
            .await?;
        if !compile.success {
            tracing::error!("benchmark harness did not compile");
            return Err(runner_failed("the benchmark harness could not be compiled"));
        }
        let mut workload_files = Vec::new();
        for (index, stdin) in workloads.iter().enumerate() {
            let path = format!("{WORK_DIRECTORY}/w{index}.in");
            let copy = self
                .docker(
                    &[
                        "exec",
                        "--interactive",
                        "--workdir",
                        WORK_DIRECTORY,
                        &container.name,
                        "dd",
                        &format!("of={path}"),
                        "status=none",
                    ],
                    Some(stdin),
                    CONTROL_TIMEOUT,
                )
                .await?;
            ensure_control_success(&copy, "could not copy a benchmark workload")?;
            workload_files.push(path);
        }
        let harness_setup_ms = harness_started.elapsed().as_millis() as u64;

        let warmup = settings.warmup_iterations.to_string();
        let measured = settings.measured_iterations.to_string();
        let iteration_timeout = settings.iteration_timeout_ms.to_string();
        let mut args: Vec<&str> = vec![
            "exec",
            "--workdir",
            WORK_DIRECTORY,
            &container.name,
            "java",
            "-Xmx256m",
            "-XX:+UseSerialGC",
            "-Djava.security.manager=allow",
            "-Dfile.encoding=UTF-8",
            "-cp",
            "/work/bench-classes",
            "Bench",
            WORK_DIRECTORY,
            main_class,
            &warmup,
            &measured,
            if settings.fresh_class_loader {
                "fresh"
            } else {
                "shared"
            },
            &iteration_timeout,
        ];
        args.extend(workload_files.iter().map(String::as_str));
        let started = std::time::Instant::now();
        let run = self.docker(&args, None, self.benchmark_timeout).await?;
        let execution_ms = started.elapsed().as_millis() as u64;
        let mut environment = None;
        let mut lines = Vec::new();
        for line in String::from_utf8_lossy(&run.stdout.bytes).lines() {
            let Ok(value) = serde_json::from_str::<serde_json::Value>(line) else {
                continue;
            };
            if value.get("environment").is_some() {
                environment = value.get("environment").cloned();
            } else if value.get("workload").is_some() {
                lines.push(value);
            }
        }
        let stderr_tail = {
            let text = String::from_utf8_lossy(&run.stderr.bytes);
            let skip = text.chars().count().saturating_sub(2000);
            text.chars().skip(skip).collect::<String>()
        };
        Ok((
            harness_setup_ms,
            execution_ms,
            run.timed_out,
            environment,
            lines,
            stderr_tail,
        ))
    }

    async fn compile_revision(&self, target: &CompileTarget) -> Result<CompileAttempt, ApiError> {
        self.compile_revision_with(target, ContainerLimits::VERIFY)
            .await
    }

    async fn compile_revision_with(
        &self,
        target: &CompileTarget,
        limits: ContainerLimits,
    ) -> Result<CompileAttempt, ApiError> {
        let image = self.image.as_deref().ok_or_else(runner_unavailable)?;
        let mut files = Vec::with_capacity(target.files.len());
        for (path, source) in &target.files {
            let source = tokio::fs::canonicalize(source).await.map_err(|error| {
                tracing::error!(%error, path = %source.display(), "runner source is unavailable");
                runner_failed("source revision is unavailable to the runner")
            })?;
            files.push((path.clone(), source));
        }
        let target = &CompileTarget {
            files,
            main_class: target.main_class.clone(),
            release: target.release.clone(),
        };
        let name = format!("java-blueprints-{}", Uuid::new_v4());
        let create = self
            .docker(
                &[
                    "create",
                    "--name",
                    &name,
                    "--hostname",
                    "runner",
                    "--network",
                    "none",
                    "--read-only",
                    "--tmpfs",
                    "/work:rw,nosuid,nodev,noexec,size=64m,mode=1777",
                    "--tmpfs",
                    "/tmp:rw,nosuid,nodev,noexec,size=16m,mode=1777",
                    "--user",
                    "65532:65532",
                    "--cpus",
                    limits.cpus,
                    "--memory",
                    limits.memory,
                    "--memory-swap",
                    limits.memory,
                    "--pids-limit",
                    "64",
                    "--cap-drop",
                    "ALL",
                    "--security-opt",
                    "no-new-privileges",
                    "--ulimit",
                    "nofile=64:64",
                    "--env",
                    "LANG=C.UTF-8",
                    "--env",
                    "LC_ALL=C.UTF-8",
                    "--workdir",
                    WORK_DIRECTORY,
                    image,
                    "sleep",
                    "300",
                ],
                None,
                CONTROL_TIMEOUT,
            )
            .await?;
        ensure_control_success(&create, "could not create runner container")?;
        let mut container = ContainerGuard {
            runner: self.clone(),
            name,
            active: true,
        };

        let result = self.compile_in_container(&container, target).await;
        match result {
            Ok((report, true)) => Ok(CompileAttempt {
                report,
                container: Some(container),
            }),
            Ok((report, false)) => {
                container.cleanup().await?;
                Ok(CompileAttempt {
                    report,
                    container: None,
                })
            }
            Err(error) => {
                if let Err(cleanup_error) = container.cleanup().await {
                    tracing::warn!(message = %cleanup_error.message, "runner cleanup also failed");
                }
                Err(error)
            }
        }
    }

    async fn compile_in_container(
        &self,
        container: &ContainerGuard,
        target: &CompileTarget,
    ) -> Result<(CompilationResult, bool), ApiError> {
        let start = self
            .docker(&["start", &container.name], None, CONTROL_TIMEOUT)
            .await?;
        ensure_control_success(&start, "could not start runner container")?;
        let mut container_sources = Vec::with_capacity(target.files.len());
        for (path, source) in &target.files {
            let bytes = tokio::fs::read(source).await.map_err(|error| {
                tracing::error!(%error, "could not read source for isolated runner");
                runner_failed("source revision is unavailable to the runner")
            })?;
            if let Some((directory, _)) = path.rsplit_once('/') {
                let made = self
                    .docker(
                        &[
                            "exec",
                            "--workdir",
                            WORK_DIRECTORY,
                            &container.name,
                            "mkdir",
                            "-p",
                            &format!("{WORK_DIRECTORY}/{directory}"),
                        ],
                        None,
                        CONTROL_TIMEOUT,
                    )
                    .await?;
                ensure_control_success(
                    &made,
                    "could not create source directory in runner container",
                )?;
            }
            let destination = format!("of={WORK_DIRECTORY}/{path}");
            let copy = self
                .docker(
                    &[
                        "exec",
                        "--interactive",
                        "--workdir",
                        WORK_DIRECTORY,
                        &container.name,
                        "dd",
                        &destination,
                        "status=none",
                    ],
                    Some(&bytes),
                    CONTROL_TIMEOUT,
                )
                .await?;
            ensure_control_success(&copy, "could not copy source into runner container")?;
            container_sources.push(format!("{WORK_DIRECTORY}/{path}"));
        }

        let mut javac_args: Vec<&str> = vec![
            "exec",
            "--workdir",
            WORK_DIRECTORY,
            &container.name,
            "javac",
            "-encoding",
            "UTF-8",
            "--release",
            &target.release,
            "-d",
            WORK_DIRECTORY,
        ];
        javac_args.extend(container_sources.iter().map(String::as_str));
        let compile = self.docker(&javac_args, None, self.compile_timeout).await?;
        if compile.timed_out {
            return Ok((
                compilation_result(
                    CompilationStatus::TimedOut,
                    compile,
                    None,
                    "javac_timeout",
                    "javac exceeded the configured compilation timeout",
                ),
                false,
            ));
        }
        if compile.stdout.overflowed || compile.stderr.overflowed {
            return Ok((
                compilation_result(
                    CompilationStatus::OutputOverflow,
                    compile,
                    None,
                    "javac_output_overflow",
                    "javac exceeded the configured output limit",
                ),
                false,
            ));
        }
        if !compile.success {
            let message = String::from_utf8_lossy(&compile.stderr.bytes).into_owned();
            return Ok((
                compilation_result(
                    CompilationStatus::Failed,
                    compile,
                    None,
                    "javac_failed",
                    if message.is_empty() {
                        "javac rejected the source".to_owned()
                    } else {
                        message
                    },
                ),
                false,
            ));
        }

        let main_class = match &target.main_class {
            Some(explicit) => Some(explicit.clone()),
            None => {
                let (filename, _) = &target.files[0];
                let class_filename = format!(
                    "{}.class",
                    filename
                        .rsplit('/')
                        .next()
                        .and_then(|name| name.strip_suffix(".java"))
                        .expect("project filenames end in .java")
                );
                let find = self
                    .docker(
                        &[
                            "exec",
                            "--workdir",
                            WORK_DIRECTORY,
                            &container.name,
                            "find",
                            WORK_DIRECTORY,
                            "-type",
                            "f",
                            "-name",
                            &class_filename,
                            "-print",
                        ],
                        None,
                        CONTROL_TIMEOUT,
                    )
                    .await?;
                ensure_control_success(&find, "could not locate the compiled main class")?;
                class_name(&find.stdout.bytes, &class_filename)
            }
        };
        let Some(main_class) = main_class else {
            return Ok((
                compilation_result(
                    CompilationStatus::MissingMain,
                    compile,
                    None,
                    "main_class_not_found",
                    "the compiled source does not define a top-level class matching its original filename",
                ),
                false,
            ));
        };
        let inspect = self
            .docker(
                &[
                    "exec",
                    "--workdir",
                    WORK_DIRECTORY,
                    &container.name,
                    "javap",
                    "-classpath",
                    WORK_DIRECTORY,
                    "-public",
                    &main_class,
                ],
                None,
                CONTROL_TIMEOUT,
            )
            .await?;
        if !inspect.success && target.main_class.is_some() {
            return Ok((
                compilation_result(
                    CompilationStatus::MissingMain,
                    compile,
                    Some(main_class),
                    "main_class_not_found",
                    "the requested main class was not produced by the compilation",
                ),
                false,
            ));
        }
        ensure_control_success(&inspect, "could not inspect the compiled main class")?;
        if !String::from_utf8_lossy(&inspect.stdout.bytes)
            .contains("public static void main(java.lang.String[])")
        {
            return Ok((
                compilation_result(
                    CompilationStatus::MissingMain,
                    compile,
                    Some(main_class),
                    "main_method_not_found",
                    "public static void main(String[] args) is required",
                ),
                false,
            ));
        }

        Ok((
            CompilationResult {
                status: CompilationStatus::Completed,
                exit_code: compile.exit_code,
                timed_out: false,
                output_overflow: false,
                stdout: captured_stream(compile.stdout),
                stderr: captured_stream(compile.stderr),
                main_class: Some(main_class),
                diagnostics: Vec::new(),
            },
            true,
        ))
    }

    async fn run_case(
        &self,
        container: &ContainerGuard,
        main_class: &str,
        stdin: &[u8],
    ) -> Result<RawObservation, ApiError> {
        let output = self
            .docker(
                &[
                    "exec",
                    "--interactive",
                    "--workdir",
                    WORK_DIRECTORY,
                    &container.name,
                    "java",
                    "-Dfile.encoding=UTF-8",
                    "-Dstdout.encoding=UTF-8",
                    "-Dstderr.encoding=UTF-8",
                    "-cp",
                    WORK_DIRECTORY,
                    main_class,
                ],
                Some(stdin),
                self.case_timeout,
            )
            .await?;
        let output_overflow = output.stdout.overflowed || output.stderr.overflowed;
        Ok(RawObservation {
            stdout: output.stdout,
            stderr: output.stderr,
            exit_code: output.exit_code,
            timed_out: output.timed_out,
            output_overflow,
        })
    }

    async fn docker(
        &self,
        args: &[&str],
        stdin: Option<&[u8]>,
        command_timeout: Duration,
    ) -> Result<CommandCapture, ApiError> {
        let captures_user_output = args.first() == Some(&"exec")
            && args
                .iter()
                .any(|argument| matches!(*argument, "java" | "javac"));
        let stdout_limit = if captures_user_output {
            self.max_output_bytes
        } else {
            STDERR_BYTES
        };
        let stderr_limit = if captures_user_output {
            self.max_output_bytes
        } else {
            STDERR_BYTES
        };
        let mut child = Command::new(&self.container_bin)
            .args(args)
            .stdin(if stdin.is_some() {
                Stdio::piped()
            } else {
                Stdio::null()
            })
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .map_err(|_| runner_unavailable())?;
        let stdout = child.stdout.take().expect("stdout is piped");
        let stderr = child.stderr.take().expect("stderr is piped");
        let input = stdin.map(<[u8]>::to_vec);
        let writer = child.stdin.take().map(|mut writer| {
            tokio::spawn(async move {
                if let Some(input) = input {
                    writer.write_all(&input).await?;
                }
                writer.shutdown().await
            })
        });
        let run = async {
            let (status, stdout, stderr) = tokio::join!(
                child.wait(),
                read_bounded(stdout, stdout_limit),
                read_bounded(stderr, stderr_limit)
            );
            if let Some(writer) = writer {
                writer.await.map_err(|error| {
                    io::Error::new(io::ErrorKind::Other, format!("stdin task failed: {error}"))
                })??;
            }
            let status = status?;
            Ok::<_, io::Error>(CommandCapture {
                stdout: stdout?,
                stderr: stderr?,
                exit_code: status.code(),
                success: status.success(),
                timed_out: false,
            })
        };
        match timeout(command_timeout, run).await {
            Ok(Ok(output)) => Ok(output),
            Ok(Err(error)) => {
                tracing::warn!(%error, "container command failed");
                Err(runner_failed("container command failed"))
            }
            Err(_) => {
                let _ = child.kill().await;
                Ok(CommandCapture {
                    stdout: RawCapture {
                        bytes: Vec::new(),
                        overflowed: false,
                    },
                    stderr: RawCapture {
                        bytes: Vec::new(),
                        overflowed: false,
                    },
                    exit_code: None,
                    success: false,
                    timed_out: true,
                })
            }
        }
    }
}

struct RawObservation {
    stdout: RawCapture,
    stderr: RawCapture,
    exit_code: Option<i32>,
    timed_out: bool,
    output_overflow: bool,
}

impl RawObservation {
    /// A run that hit the time or output limit leaves a container that must be recreated.
    fn cut_off(&self) -> bool {
        self.timed_out || self.output_overflow
    }

    fn into_public(self) -> ExecutionObservation {
        ExecutionObservation {
            stdout: captured_stream(self.stdout),
            stderr: captured_stream(self.stderr),
            exit_code: self.exit_code,
            timed_out: self.timed_out,
            output_overflow: self.output_overflow,
        }
    }
}

impl ContainerGuard {
    async fn cleanup(&mut self) -> Result<(), ApiError> {
        if !self.active {
            return Ok(());
        }
        self.active = false;
        let output = self
            .runner
            .docker(&["rm", "--force", &self.name], None, CONTROL_TIMEOUT)
            .await?;
        ensure_control_success(&output, "could not remove runner container")
    }
}

impl Drop for ContainerGuard {
    fn drop(&mut self) {
        if !self.active {
            return;
        }
        let bin = self.runner.container_bin.clone();
        let name = self.name.clone();
        std::thread::spawn(move || {
            let _ = std::process::Command::new(bin)
                .args(["rm", "--force", &name])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
        });
    }
}

async fn cleanup_optional(container: &mut Option<ContainerGuard>) -> Result<(), ApiError> {
    if let Some(mut container) = container.take() {
        container.cleanup().await?;
    }
    Ok(())
}

fn ensure_recompile_succeeded(report: &CompilationResult) -> Result<(), ApiError> {
    if report.status == CompilationStatus::Completed {
        Ok(())
    } else {
        Err(runner_failed(
            "a revision could not be recompiled after a terminated test case",
        ))
    }
}

fn compilation_result(
    status: CompilationStatus,
    output: CommandCapture,
    main_class: Option<String>,
    code: &str,
    message: impl Into<String>,
) -> CompilationResult {
    CompilationResult {
        status,
        exit_code: output.exit_code,
        timed_out: output.timed_out,
        output_overflow: output.stdout.overflowed || output.stderr.overflowed,
        stdout: captured_stream(output.stdout),
        stderr: captured_stream(output.stderr),
        main_class,
        diagnostics: vec![RunnerDiagnostic {
            code: code.to_owned(),
            message: message.into(),
        }],
    }
}

fn captured_stream(capture: RawCapture) -> CapturedStream {
    CapturedStream {
        utf8: String::from_utf8(capture.bytes.clone()).ok(),
        base64: BASE64.encode(capture.bytes),
        truncated: capture.overflowed,
    }
}

fn class_name(output: &[u8], class_filename: &str) -> Option<String> {
    let output = std::str::from_utf8(output).ok()?.trim();
    if output.lines().count() != 1 {
        return None;
    }
    let relative = output.strip_prefix("/work/")?;
    let relative = relative.strip_suffix(class_filename)?;
    let package = relative.strip_suffix('/').unwrap_or(relative);
    let simple = class_filename.strip_suffix(".class")?;
    let mut parts: Vec<&str> = package.split('/').filter(|part| !part.is_empty()).collect();
    parts.push(simple);
    if parts.iter().any(|part| {
        *part == "."
            || *part == ".."
            || !part.chars().all(|character| {
                character.is_alphanumeric() || character == '_' || character == '$'
            })
    }) {
        return None;
    }
    Some(parts.join("."))
}

fn same_observation(left: &RawObservation, right: &RawObservation) -> bool {
    left.stdout.bytes == right.stdout.bytes
        && left.stderr.bytes == right.stderr.bytes
        && left.exit_code == right.exit_code
        && left.timed_out == right.timed_out
        && left.output_overflow == right.output_overflow
}

fn check_expectations(case: &VerifyTestCase, baseline: &RawObservation) -> Vec<BehaviorMismatch> {
    let mut failures = Vec::new();
    if let Some(expected) = &case.expected_stdout {
        if baseline.stdout.bytes != expected.as_bytes() {
            failures.push(BehaviorMismatch {
                field: MismatchField::Stdout,
                explanation: "stdout of the baseline run differs from expected_stdout".to_owned(),
            });
        }
    }
    if let Some(expected) = &case.expected_stderr {
        if baseline.stderr.bytes != expected.as_bytes() {
            failures.push(BehaviorMismatch {
                field: MismatchField::Stderr,
                explanation: "stderr of the baseline run differs from expected_stderr".to_owned(),
            });
        }
    }
    if let Some(expected) = case.expected_exit_code {
        if baseline.exit_code != Some(expected) {
            failures.push(BehaviorMismatch {
                field: MismatchField::ExitCode,
                explanation: format!(
                    "exit code of the baseline run is {:?}, expected {expected}",
                    baseline.exit_code
                ),
            });
        }
    }
    failures
}

fn compare_observations(
    baseline: &RawObservation,
    candidate: &RawObservation,
) -> Vec<BehaviorMismatch> {
    let mut mismatches = Vec::new();
    if baseline.stdout.bytes != candidate.stdout.bytes {
        mismatches.push(BehaviorMismatch {
            field: MismatchField::Stdout,
            explanation: "stdout bytes differ".to_owned(),
        });
    }
    if baseline.stderr.bytes != candidate.stderr.bytes {
        mismatches.push(BehaviorMismatch {
            field: MismatchField::Stderr,
            explanation: "stderr bytes differ".to_owned(),
        });
    }
    if baseline.exit_code != candidate.exit_code {
        mismatches.push(BehaviorMismatch {
            field: MismatchField::ExitCode,
            explanation: "process exit codes differ".to_owned(),
        });
    }
    if baseline.timed_out || candidate.timed_out {
        mismatches.push(BehaviorMismatch {
            field: MismatchField::TimedOut,
            explanation: if baseline.timed_out == candidate.timed_out {
                "both executions timed out, so behavior could not be fully observed".to_owned()
            } else {
                "timeout states differ".to_owned()
            },
        });
    }
    if baseline.output_overflow || candidate.output_overflow {
        mismatches.push(BehaviorMismatch {
            field: MismatchField::OutputOverflow,
            explanation: if baseline.output_overflow == candidate.output_overflow {
                "both executions exceeded an output limit, so their complete output could not be compared".to_owned()
            } else {
                "output-limit states differ".to_owned()
            },
        });
    }
    mismatches
}

async fn read_bounded(mut reader: impl AsyncRead + Unpin, limit: u64) -> io::Result<RawCapture> {
    let mut bytes = Vec::new();
    let mut overflowed = false;
    let mut buffer = [0_u8; 8192];
    loop {
        let read = reader.read(&mut buffer).await?;
        if read == 0 {
            break;
        }
        let remaining = limit.saturating_sub(bytes.len() as u64);
        let keep = read.min(remaining.try_into().unwrap_or(usize::MAX));
        bytes.extend_from_slice(&buffer[..keep]);
        overflowed |= keep < read;
    }
    Ok(RawCapture { bytes, overflowed })
}

fn ensure_control_success(output: &CommandCapture, message: &'static str) -> Result<(), ApiError> {
    if output.timed_out {
        return Err(ApiError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "runner_timeout",
            message,
        ));
    }
    if !output.success || output.stdout.overflowed || output.stderr.overflowed {
        tracing::warn!(
            exit_code = output.exit_code,
            stderr = %String::from_utf8_lossy(&output.stderr.bytes),
            "container control command failed"
        );
        return Err(runner_failed(message));
    }
    Ok(())
}

fn runner_unavailable() -> ApiError {
    ApiError::new(
        StatusCode::SERVICE_UNAVAILABLE,
        "runner_unavailable",
        "isolated Java runner is not configured or unavailable",
    )
}

fn runner_failed(message: impl Into<String>) -> ApiError {
    ApiError::new(StatusCode::SERVICE_UNAVAILABLE, "runner_failed", message)
}

#[cfg(test)]
mod tests {
    use super::{class_name, compare_observations, MismatchField, RawCapture, RawObservation};

    fn observation(stdout: &[u8], stderr: &[u8], exit_code: i32) -> RawObservation {
        RawObservation {
            stdout: RawCapture {
                bytes: stdout.to_vec(),
                overflowed: false,
            },
            stderr: RawCapture {
                bytes: stderr.to_vec(),
                overflowed: false,
            },
            exit_code: Some(exit_code),
            timed_out: false,
            output_overflow: false,
        }
    }

    #[test]
    fn compares_exact_stdout_stderr_and_exit_code() {
        assert!(compare_observations(
            &observation(b"same", b"warning", 0),
            &observation(b"same", b"warning", 0)
        )
        .is_empty());

        let differences = compare_observations(
            &observation(b"one", b"first", 0),
            &observation(b"two", b"second", 7),
        );
        assert!(matches!(differences[0].field, MismatchField::Stdout));
        assert!(matches!(differences[1].field, MismatchField::Stderr));
        assert!(matches!(differences[2].field, MismatchField::ExitCode));
    }

    #[test]
    fn converts_compiled_path_to_main_class_without_accepting_traversal() {
        assert_eq!(
            class_name(b"/work/org/example/Main.class\n", "Main.class").as_deref(),
            Some("org.example.Main")
        );
        assert!(class_name(b"/work/../Main.class\n", "Main.class").is_none());
        assert!(class_name(
            b"/work/one/Main.class\n/work/two/Main.class\n",
            "Main.class"
        )
        .is_none());
    }
}
