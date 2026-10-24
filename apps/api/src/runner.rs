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

#[derive(Clone, Debug)]
pub struct JavaRunner {
    container_bin: PathBuf,
    image: Option<String>,
    compile_timeout: Duration,
    case_timeout: Duration,
    max_output_bytes: u64,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub struct VerifyRequest {
    #[schema(minimum = 1)]
    pub baseline_revision: u64,
    #[schema(minimum = 1)]
    pub candidate_revision: u64,
    pub test_cases: Vec<VerifyTestCase>,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub struct VerifyTestCase {
    pub name: String,
    pub stdin: String,
}

#[derive(Debug, Serialize, ToSchema)]
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
}

#[derive(Clone, Copy, Debug, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum VerificationStatus {
    Passed,
    Failed,
    CompilationFailed,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
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

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum CompilationStatus {
    Completed,
    Failed,
    TimedOut,
    OutputOverflow,
    MissingMain,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct RunnerDiagnostic {
    pub code: String,
    pub message: String,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct CapturedStream {
    pub utf8: Option<String>,
    pub base64: String,
    pub truncated: bool,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct TestCaseResult {
    pub name: String,
    pub passed: bool,
    pub mismatches: Vec<BehaviorMismatch>,
    pub baseline: ExecutionObservation,
    pub candidate: ExecutionObservation,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct BehaviorMismatch {
    pub field: MismatchField,
    pub explanation: String,
}

#[derive(Clone, Copy, Debug, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum MismatchField {
    Stdout,
    Stderr,
    ExitCode,
    TimedOut,
    OutputOverflow,
}

#[derive(Debug, Serialize, ToSchema)]
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
        }
    }

    pub async fn verify(
        &self,
        filename: &str,
        baseline_revision: u64,
        baseline_source: &Path,
        candidate_revision: u64,
        candidate_source: &Path,
        test_cases: &[VerifyTestCase],
    ) -> Result<VerificationReport, ApiError> {
        let mut baseline = self.compile_revision(filename, baseline_source).await?;
        let mut candidate = match self.compile_revision(filename, candidate_source).await {
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
            let baseline_observation = self
                .run_case(
                    baseline
                        .container
                        .as_ref()
                        .expect("completed compilation has a container"),
                    &baseline_main,
                    test_case.stdin.as_bytes(),
                )
                .await?;
            let candidate_observation = self
                .run_case(
                    candidate
                        .container
                        .as_ref()
                        .expect("completed compilation has a container"),
                    &candidate_main,
                    test_case.stdin.as_bytes(),
                )
                .await?;
            let baseline_needs_restart =
                baseline_observation.timed_out || baseline_observation.output_overflow;
            let candidate_needs_restart =
                candidate_observation.timed_out || candidate_observation.output_overflow;
            let mismatches = compare_observations(&baseline_observation, &candidate_observation);
            results.push(TestCaseResult {
                name: test_case.name.clone(),
                passed: mismatches.is_empty(),
                mismatches,
                baseline: baseline_observation.into_public(),
                candidate: candidate_observation.into_public(),
            });

            if index + 1 < test_cases.len() && baseline_needs_restart {
                cleanup_optional(&mut baseline.container).await?;
                baseline = self.compile_revision(filename, baseline_source).await?;
                ensure_recompile_succeeded(&baseline.report)?;
            }
            if index + 1 < test_cases.len() && candidate_needs_restart {
                cleanup_optional(&mut candidate.container).await?;
                candidate = self.compile_revision(filename, candidate_source).await?;
                ensure_recompile_succeeded(&candidate.report)?;
            }
        }

        cleanup_optional(&mut baseline.container).await?;
        cleanup_optional(&mut candidate.container).await?;
        let passed = results.iter().all(|result| result.passed);
        Ok(VerificationReport {
            status: if passed {
                VerificationStatus::Passed
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
            } else {
                "one or more provided test cases produced different observable behavior".to_owned()
            },
        })
    }

    async fn compile_revision(
        &self,
        filename: &str,
        source: &Path,
    ) -> Result<CompileAttempt, ApiError> {
        let image = self.image.as_deref().ok_or_else(runner_unavailable)?;
        let source = tokio::fs::canonicalize(source).await.map_err(|error| {
            tracing::error!(%error, path = %source.display(), "runner source is unavailable");
            runner_failed("source revision is unavailable to the runner")
        })?;
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
                    "0.5",
                    "--memory",
                    "256m",
                    "--memory-swap",
                    "256m",
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

        let result = self
            .compile_in_container(&container, filename, &source)
            .await;
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
        filename: &str,
        source: &Path,
    ) -> Result<(CompilationResult, bool), ApiError> {
        let start = self
            .docker(&["start", &container.name], None, CONTROL_TIMEOUT)
            .await?;
        ensure_control_success(&start, "could not start runner container")?;
        let source = tokio::fs::read(source).await.map_err(|error| {
            tracing::error!(%error, "could not read source for isolated runner");
            runner_failed("source revision is unavailable to the runner")
        })?;
        let destination = format!("of={WORK_DIRECTORY}/{filename}");
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
                Some(&source),
                CONTROL_TIMEOUT,
            )
            .await?;
        ensure_control_success(&copy, "could not copy source into runner container")?;

        let container_source = format!("{WORK_DIRECTORY}/{filename}");
        let compile = self
            .docker(
                &[
                    "exec",
                    "--workdir",
                    WORK_DIRECTORY,
                    &container.name,
                    "javac",
                    "-encoding",
                    "UTF-8",
                    "--release",
                    "21",
                    "-d",
                    WORK_DIRECTORY,
                    &container_source,
                ],
                None,
                self.compile_timeout,
            )
            .await?;
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

        let class_filename = format!(
            "{}.class",
            filename
                .strip_suffix(".java")
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
        let main_class = class_name(&find.stdout.bytes, &class_filename);
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
