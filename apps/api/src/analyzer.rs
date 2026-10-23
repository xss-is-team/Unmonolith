use std::{path::PathBuf, process::Stdio, time::Duration};

use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use tokio::{
    fs,
    io::{AsyncReadExt, BufReader},
    process::Command,
    time::timeout,
};
use utoipa::ToSchema;
use uuid::Uuid;

use crate::{error::ApiError, project};

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

#[derive(Debug, Deserialize, Serialize, ToSchema)]
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

        let input = project::source_path(&project_dir, revision);
        let analysis_dir = project_dir.join("analysis");
        fs::create_dir_all(&analysis_dir).await?;
        let temporary = analysis_dir.join(format!(".{revision}-{}.tmp", Uuid::new_v4()));

        let mut child = Command::new(&self.java_bin)
            .arg("-jar")
            .arg(&jar)
            .arg("analyze")
            .arg("--input")
            .arg(&input)
            .arg("--output")
            .arg(&temporary)
            .current_dir(&project_dir)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .map_err(|_| unavailable())?;

        let stderr = child.stderr.take().expect("stderr was configured as piped");
        let mut stderr = BufReader::new(stderr).take(16 * 1024 + 1);
        let run = async {
            let mut captured_stderr = Vec::new();
            let (status, read_result) =
                tokio::join!(child.wait(), stderr.read_to_end(&mut captured_stderr));
            read_result.map_err(ApiError::io)?;
            status.map_err(ApiError::io)
        };

        let status = match timeout(self.timeout, run).await {
            Ok(result) => result?,
            Err(_) => {
                let _ = child.kill().await;
                let _ = fs::remove_file(&temporary).await;
                return Err(ApiError::new(
                    StatusCode::GATEWAY_TIMEOUT,
                    "analyzer_timeout",
                    "Java analyzer exceeded its configured timeout",
                ));
            }
        };
        if !status.success() {
            let _ = fs::remove_file(&temporary).await;
            return Err(ApiError::new(
                StatusCode::SERVICE_UNAVAILABLE,
                "analyzer_failed",
                "Java analyzer exited unsuccessfully",
            ));
        }

        let metadata = fs::metadata(&temporary)
            .await
            .map_err(|_| invalid_output())?;
        if metadata.len() > self.max_output_bytes {
            let _ = fs::remove_file(&temporary).await;
            return Err(invalid_output());
        }
        let bytes = fs::read(&temporary).await.map_err(|_| invalid_output())?;
        let result: AnalysisResult =
            serde_json::from_slice(&bytes).map_err(|_| invalid_output())?;
        if result.protocol_version != 1 || result.revision != revision {
            let _ = fs::remove_file(&temporary).await;
            return Err(invalid_output());
        }
        fs::rename(&temporary, analysis_dir.join(format!("{revision}.json"))).await?;
        Ok(result)
    }
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
