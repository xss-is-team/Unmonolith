use std::{io, path::PathBuf, process::Stdio, time::Duration};

use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use tokio::{
    fs,
    io::{AsyncRead, AsyncReadExt},
    process::Command,
    sync::mpsc::UnboundedSender,
    time::timeout,
};
use utoipa::ToSchema;

use crate::{error::ApiError, project};

const STDERR_BYTES: u64 = 16 * 1024;

#[derive(Clone, Debug)]
pub struct JavaAnalyzer {
    jar: Option<PathBuf>,
    java_bin: PathBuf,
    timeout: Duration,
    max_output_bytes: u64,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
pub struct AnalysisResult {
    pub protocol_version: u32,
    pub status: AnalysisStatus,
    #[schema(minimum = 1)]
    pub revision: u64,
    pub methods: Vec<Method>,
    pub diagnostics: Vec<Diagnostic>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct Cfg {
    pub method_id: String,
    pub nodes: Vec<CfgNode>,
    pub edges: Vec<CfgEdge>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct CfgNode {
    pub id: String,
    pub kind: String,
    pub label: String,
    pub source_range: SourceRange,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct CfgEdge {
    pub source: String,
    pub target: String,
    pub kind: String,
}

#[derive(Debug, Deserialize)]
struct ParserResult {
    protocol_version: u32,
    status: AnalysisStatus,
    methods: Vec<Method>,
    diagnostics: Vec<Diagnostic>,
    #[serde(default)]
    cfgs: Vec<Cfg>,
}

struct CapturedOutput {
    bytes: Vec<u8>,
    overflowed: bool,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "lowercase")]
pub enum AnalysisStatus {
    Completed,
    Partial,
    Failed,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
pub struct Method {
    pub id: String,
    pub name: String,
    pub source_range: SourceRange,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub complexity: Option<MethodComplexity>,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
pub struct MethodComplexity {
    pub cyclomatic_complexity: u32,
    pub cognitive_complexity: u32,
    pub max_nesting_depth: u32,
    #[serde(default)]
    pub hotspots: Vec<ComplexityHotspot>,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
pub struct ComplexityHotspot {
    pub source_range: SourceRange,
    pub kind: String,
    pub cyclomatic_increment: u32,
    pub cognitive_increment: u32,
    pub nesting_depth: u32,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
pub struct Diagnostic {
    pub code: String,
    pub severity: DiagnosticSeverity,
    pub message: String,
    pub source_range: SourceRange,
}

#[derive(Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "lowercase")]
pub enum DiagnosticSeverity {
    Error,
    Warning,
    Info,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct SourceRange {
    pub file: Option<String>,
    pub start: u64,
    pub length: u64,
    pub line: Option<u64>,
}

impl JavaAnalyzer {
    pub fn new(
        jar: Option<PathBuf>,
        java_bin: PathBuf,
        timeout: Duration,
        max_output_bytes: u64,
    ) -> Self {
        Self {
            jar,
            java_bin,
            timeout,
            max_output_bytes,
        }
    }

    pub async fn analyze(
        &self,
        project_dir: PathBuf,
        revision: u64,
        filename: &str,
    ) -> Result<AnalysisResult, ApiError> {
        self.analyze_internal(project_dir, revision, filename, None)
            .await
    }

    pub async fn analyze_with_progress(
        &self,
        project_dir: PathBuf,
        revision: u64,
        filename: &str,
        progress: UnboundedSender<String>,
    ) -> Result<AnalysisResult, ApiError> {
        self.analyze_internal(project_dir, revision, filename, Some(progress))
            .await
    }

    async fn analyze_internal(
        &self,
        project_dir: PathBuf,
        revision: u64,
        filename: &str,
        progress: Option<UnboundedSender<String>>,
    ) -> Result<AnalysisResult, ApiError> {
        let configured_jar = self.jar.as_ref().ok_or_else(unavailable)?;
        let jar = fs::canonicalize(configured_jar)
            .await
            .map_err(|_| unavailable())?;
        if !fs::metadata(&jar)
            .await
            .map(|metadata| metadata.is_file())
            .unwrap_or(false)
        {
            return Err(unavailable());
        }

        let input =
            fs::canonicalize(project::source_path(&project_dir, revision, filename)).await?;
        let analysis_dir = project_dir.join("analysis");
        fs::create_dir_all(&analysis_dir).await?;

        let mut child = Command::new(&self.java_bin)
            .arg("-jar")
            .arg(&jar)
            .arg(&input)
            .current_dir(&project_dir)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .map_err(|_| unavailable())?;

        let stdout = child.stdout.take().expect("stdout was configured as piped");
        let stderr = child.stderr.take().expect("stderr was configured as piped");
        let run = async {
            let (status, stdout, stderr) = tokio::join!(
                child.wait(),
                read_bounded(stdout, self.max_output_bytes, None),
                read_bounded(stderr, STDERR_BYTES, progress)
            );
            Ok::<_, ApiError>((
                status.map_err(ApiError::io)?,
                stdout.map_err(ApiError::io)?,
                stderr.map_err(ApiError::io)?,
            ))
        };

        let (status, stdout, stderr) = match timeout(self.timeout, run).await {
            Ok(result) => result?,
            Err(_) => {
                let _ = child.kill().await;
                return Err(ApiError::new(
                    StatusCode::GATEWAY_TIMEOUT,
                    "analyzer_timeout",
                    "Java analyzer exceeded its configured timeout",
                ));
            }
        };

        if stdout.overflowed {
            return Err(invalid_output());
        }

        if !status.success() {
            tracing::warn!(
                exit_code = status.code(),
                stderr_bytes = stderr.bytes.len(),
                stderr_overflowed = stderr.overflowed,
                "Java analyzer exited unsuccessfully"
            );
            if status.code() == Some(1) {
                let failure: serde_json::Value =
                    serde_json::from_slice(&stdout.bytes).map_err(|_| invalid_output())?;
                if failure.get("status").and_then(|value| value.as_str()) != Some("failed") {
                    return Err(invalid_output());
                }
            }
            return Err(analyzer_failed());
        }

        let mut raw: serde_json::Value =
            serde_json::from_slice(&stdout.bytes).map_err(|_| invalid_output())?;
        let parsed: ParserResult =
            serde_json::from_value(raw.clone()).map_err(|_| invalid_output())?;
        if parsed.protocol_version != 1 || matches!(parsed.status, AnalysisStatus::Failed) {
            return Err(invalid_output());
        }
        validate_cfgs(&parsed.methods, &parsed.cfgs)?;

        raw.as_object_mut()
            .ok_or_else(invalid_output)?
            .insert("revision".to_owned(), revision.into());
        let artifact = serde_json::to_vec(&raw).map_err(|_| invalid_output())?;
        project::atomic_write(&analysis_dir.join(format!("{revision}.json")), &artifact).await?;

        let result = AnalysisResult {
            protocol_version: parsed.protocol_version,
            status: parsed.status,
            revision,
            methods: parsed.methods,
            diagnostics: parsed.diagnostics,
        };
        Ok(result)
    }
}

pub async fn read_cfg(
    project_dir: PathBuf,
    revision: u64,
    method_id: &str,
) -> Result<Cfg, ApiError> {
    let path = project_dir
        .join("analysis")
        .join(format!("{revision}.json"));
    let bytes = match fs::read(path).await {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return Err(cfg_not_found(
                "CFG is not available for the current revision",
            ));
        }
        Err(error) => return Err(ApiError::io(error)),
    };
    let parsed: ParserResult = serde_json::from_slice(&bytes).map_err(|_| invalid_output())?;
    parsed
        .cfgs
        .into_iter()
        .find(|cfg| cfg.method_id == method_id)
        .ok_or_else(|| cfg_not_found("CFG is not available for this method"))
}

fn validate_cfgs(methods: &[Method], cfgs: &[Cfg]) -> Result<(), ApiError> {
    let method_ids: std::collections::HashSet<_> =
        methods.iter().map(|method| method.id.as_str()).collect();
    let mut cfg_methods = std::collections::HashSet::new();
    for cfg in cfgs {
        if !method_ids.contains(cfg.method_id.as_str())
            || !cfg_methods.insert(cfg.method_id.as_str())
        {
            return Err(invalid_output());
        }
        let mut node_ids = std::collections::HashSet::new();
        if cfg
            .nodes
            .iter()
            .any(|node| node.id.is_empty() || !node_ids.insert(node.id.as_str()))
        {
            return Err(invalid_output());
        }
        if cfg.edges.iter().any(|edge| {
            !node_ids.contains(edge.source.as_str()) || !node_ids.contains(edge.target.as_str())
        }) {
            return Err(invalid_output());
        }
    }
    Ok(())
}

async fn read_bounded(
    mut reader: impl AsyncRead + Unpin,
    limit: u64,
    progress: Option<UnboundedSender<String>>,
) -> io::Result<CapturedOutput> {
    let mut bytes = Vec::new();
    let mut overflowed = false;
    let mut buffer = [0_u8; 8192];
    let mut pending = Vec::new();

    loop {
        let read = reader.read(&mut buffer).await?;
        if read == 0 {
            break;
        }
        let remaining = limit.saturating_sub(bytes.len() as u64);
        let keep = read.min(remaining.try_into().unwrap_or(usize::MAX));
        bytes.extend_from_slice(&buffer[..keep]);
        overflowed |= keep < read;
        if let Some(progress) = &progress {
            pending.extend_from_slice(&buffer[..read]);
            if pending.len() > 1024 && !pending.contains(&b'\n') {
                pending.clear();
            }
            while let Some(newline) = pending.iter().position(|byte| *byte == b'\n') {
                let line: Vec<_> = pending.drain(..=newline).collect();
                if let Ok(line) = std::str::from_utf8(&line) {
                    if let Some(stage) = line.trim().strip_prefix("progress: ") {
                        let _ = progress.send(stage.to_owned());
                    }
                }
            }
        }
    }

    Ok(CapturedOutput { bytes, overflowed })
}

fn unavailable() -> ApiError {
    ApiError::new(
        StatusCode::SERVICE_UNAVAILABLE,
        "analyzer_unavailable",
        "Java analyzer is not configured or unavailable",
    )
}

fn invalid_output() -> ApiError {
    ApiError::new(
        StatusCode::SERVICE_UNAVAILABLE,
        "analyzer_invalid_output",
        "Java analyzer returned an invalid response",
    )
}

fn analyzer_failed() -> ApiError {
    ApiError::new(
        StatusCode::SERVICE_UNAVAILABLE,
        "analyzer_failed",
        "Java analyzer exited unsuccessfully",
    )
}

fn cfg_not_found(message: &'static str) -> ApiError {
    ApiError::new(StatusCode::NOT_FOUND, "cfg_not_found", message)
}

#[cfg(all(test, unix))]
mod tests {
    use std::{os::unix::fs::PermissionsExt, path::PathBuf, time::Duration};

    use serde_json::Value;
    use tokio::fs;
    use uuid::Uuid;

    use super::{AnalysisStatus, JavaAnalyzer};
    use crate::project;

    const FAKE_JAVA: &str = r#"#!/bin/sh
if [ "$#" -ne 3 ] || [ "$1" != "-jar" ]; then
  exit 2
fi
case "$3" in
  /*) ;;
  *) exit 2 ;;
esac
mode=
IFS= read -r mode < "$2" || :
case "$mode" in
  valid)
    chunk=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
    i=0
    while [ "$i" -lt 2048 ]; do
      printf %s "$chunk" >&2
      i=$((i + 1))
    done
    printf %s '{"protocol_version":1,"status":"completed","package":null,"types":[{"kind":"class"}],"methods":[{"id":"Main.run()","name":"run","source_range":{"start":13,"length":13,"line":1,"end_line":1}}],"diagnostics":[]}'
    ;;
  partial)
    printf %s '{"protocol_version":1,"status":"partial","types":[],"methods":[],"diagnostics":[{"code":"jdt_test","severity":"error","message":"syntax error","source_range":{"start":12,"length":1,"line":1,"end_line":1}}]}'
    ;;
  failure)
    printf %s '{"status":"failed","message":"could not read source"}'
    exit 1
    ;;
  invalid)
    printf %s '{not-json'
    ;;
  missing)
    ;;
  oversized)
    printf %s 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
    ;;
  timeout)
    exec sleep 2
    ;;
  *)
    exit 2
    ;;
esac
"#;

    struct Fixture {
        root: PathBuf,
        analyzer: JavaAnalyzer,
        project_dir: PathBuf,
        revision: u64,
    }

    impl Fixture {
        async fn new(mode: &str, analyzer_timeout: Duration, max_output_bytes: u64) -> Self {
            let root = std::env::temp_dir()
                .join(format!("java-blueprints-analyzer-test-{}", Uuid::new_v4()));
            let project_dir = root.join("project");
            let revision = 7;
            let source = project::source_path(&project_dir, revision, "Main.java");
            fs::create_dir_all(source.parent().unwrap()).await.unwrap();
            fs::write(&source, b"class Main { void run() {} }")
                .await
                .unwrap();

            let jar = root.join("parser.jar");
            fs::write(&jar, format!("{mode}\n")).await.unwrap();
            let java = root.join("fake-java");
            fs::write(&java, FAKE_JAVA).await.unwrap();
            let mut permissions = fs::metadata(&java).await.unwrap().permissions();
            permissions.set_mode(0o700);
            fs::set_permissions(&java, permissions).await.unwrap();

            Self {
                root,
                analyzer: JavaAnalyzer::new(Some(jar), java, analyzer_timeout, max_output_bytes),
                project_dir,
                revision,
            }
        }

        async fn analyze(&self) -> Result<super::AnalysisResult, crate::error::ApiError> {
            self.analyzer
                .analyze(self.project_dir.clone(), self.revision, "Main.java")
                .await
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    #[tokio::test]
    async fn analyzes_valid_java_and_preserves_raw_artifact() {
        let fixture = Fixture::new("valid", Duration::from_secs(2), 4096).await;

        let result = fixture.analyze().await.unwrap();

        assert!(matches!(result.status, AnalysisStatus::Completed));
        assert_eq!(result.revision, fixture.revision);
        assert_eq!(result.methods.len(), 1);
        let artifact = fs::read(
            fixture
                .project_dir
                .join("analysis")
                .join(format!("{}.json", fixture.revision)),
        )
        .await
        .unwrap();
        let artifact: Value = serde_json::from_slice(&artifact).unwrap();
        assert_eq!(artifact["revision"], fixture.revision);
        assert_eq!(artifact["types"][0]["kind"], "class");
        assert_eq!(artifact["methods"][0]["source_range"]["end_line"], 1);
    }

    #[tokio::test]
    async fn accepts_malformed_java_as_partial_analysis() {
        let fixture = Fixture::new("partial", Duration::from_secs(2), 4096).await;

        let result = fixture.analyze().await.unwrap();

        assert!(matches!(result.status, AnalysisStatus::Partial));
        assert_eq!(result.revision, fixture.revision);
        assert_eq!(result.diagnostics.len(), 1);
        assert_eq!(result.diagnostics[0].code, "jdt_test");
    }

    #[tokio::test]
    async fn reports_parser_failure() {
        let fixture = Fixture::new("failure", Duration::from_secs(2), 4096).await;

        let error = fixture.analyze().await.unwrap_err();

        assert_eq!(error.code, "analyzer_failed");
    }

    #[tokio::test]
    async fn rejects_invalid_json() {
        let fixture = Fixture::new("invalid", Duration::from_secs(2), 4096).await;

        let error = fixture.analyze().await.unwrap_err();

        assert_eq!(error.code, "analyzer_invalid_output");
    }

    #[tokio::test]
    async fn rejects_missing_json() {
        let fixture = Fixture::new("missing", Duration::from_secs(2), 4096).await;

        let error = fixture.analyze().await.unwrap_err();

        assert_eq!(error.code, "analyzer_invalid_output");
    }

    #[tokio::test]
    async fn rejects_oversized_json() {
        let fixture = Fixture::new("oversized", Duration::from_secs(2), 64).await;

        let error = fixture.analyze().await.unwrap_err();

        assert_eq!(error.code, "analyzer_invalid_output");
    }

    #[tokio::test]
    async fn terminates_parser_after_timeout() {
        let fixture = Fixture::new("timeout", Duration::from_millis(50), 4096).await;

        let error = fixture.analyze().await.unwrap_err();

        assert_eq!(error.code, "analyzer_timeout");
    }
}
