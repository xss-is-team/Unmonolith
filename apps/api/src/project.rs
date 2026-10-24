use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use time::{format_description::well_known::Rfc3339, OffsetDateTime};
use tokio::fs;
use utoipa::ToSchema;
use uuid::Uuid;

use crate::error::ApiError;

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct Project {
    pub id: Uuid,
    pub filename: String,
    #[schema(minimum = 1)]
    pub revision: u64,
    #[schema(value_type = String, format = DateTime)]
    pub created_at: String,
}

#[derive(Debug, Deserialize, ToSchema)]
pub struct UpdateSourceRequest {
    pub source: String,
    #[schema(minimum = 1)]
    pub expected_revision: u64,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct RevisionSummary {
    pub revision: u64,
    pub has_analysis: bool,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct RevisionDetail {
    pub revision: u64,
    pub source: String,
    pub analysis: Option<crate::analyzer::AnalysisResult>,
}

pub async fn list_revisions(
    directory: &Path,
    current: u64,
) -> Result<Vec<RevisionSummary>, ApiError> {
    let mut revisions_dir = fs::read_dir(directory.join("revisions")).await?;
    let mut revisions = Vec::new();
    while let Some(entry) = revisions_dir.next_entry().await? {
        if !entry.file_type().await?.is_dir() {
            continue;
        }
        let Ok(revision) = entry.file_name().to_string_lossy().parse::<u64>() else {
            continue;
        };
        if revision == 0 || revision > current {
            continue;
        }
        let has_analysis =
            fs::metadata(directory.join("analysis").join(format!("{revision}.json")))
                .await
                .is_ok();
        revisions.push(RevisionSummary {
            revision,
            has_analysis,
        });
    }
    revisions.sort_unstable_by_key(|item| std::cmp::Reverse(item.revision));
    Ok(revisions)
}

pub async fn read_revision(
    directory: &Path,
    revision: u64,
    current: u64,
    filename: &str,
) -> Result<RevisionDetail, ApiError> {
    if revision == 0 || revision > current {
        return Err(ApiError::not_found());
    }
    let source = fs::read(source_path(directory, revision, filename))
        .await
        .map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                ApiError::not_found()
            } else {
                ApiError::io(error)
            }
        })?;
    let source = String::from_utf8(source)
        .map_err(|_| ApiError::bad_request("stored source is not UTF-8"))?;
    let artifact = directory.join("analysis").join(format!("{revision}.json"));
    let analysis = match fs::read(artifact).await {
        Ok(bytes) => Some(
            serde_json::from_slice(&bytes)
                .map_err(|_| ApiError::bad_request("stored analysis is invalid"))?,
        ),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
        Err(error) => return Err(ApiError::io(error)),
    };
    Ok(RevisionDetail {
        revision,
        source,
        analysis,
    })
}

pub async fn create(
    session_dir: &Path,
    filename: &str,
    source: &[u8],
) -> Result<Project, ApiError> {
    let project = Project {
        id: Uuid::new_v4(),
        filename: filename.to_owned(),
        revision: 1,
        created_at: OffsetDateTime::now_utc()
            .format(&Rfc3339)
            .expect("RFC 3339 supports all current UTC dates"),
    };
    let directory = project_dir(session_dir, project.id);
    fs::create_dir_all(directory.join("revisions")).await?;
    fs::create_dir_all(directory.join("analysis")).await?;
    atomic_write(&source_path(&directory, 1, filename), source).await?;
    let metadata = serde_json::to_vec_pretty(&project).map_err(internal_json_error)?;
    atomic_write(&directory.join("project.json"), &metadata).await?;
    Ok(project)
}

pub async fn load(session_dir: &Path, id: Uuid) -> Result<(Project, PathBuf), ApiError> {
    let directory = project_dir(session_dir, id);
    let bytes = match fs::read(directory.join("project.json")).await {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(ApiError::not_found())
        }
        Err(error) => return Err(error.into()),
    };
    let project: Project = serde_json::from_slice(&bytes).map_err(internal_json_error)?;
    if project.id != id {
        return Err(ApiError::not_found());
    }
    Ok((project, directory))
}

pub async fn read_source(
    directory: &Path,
    revision: u64,
    filename: &str,
) -> Result<Vec<u8>, ApiError> {
    fs::read(source_path(directory, revision, filename))
        .await
        .map_err(Into::into)
}

pub async fn update(
    directory: &Path,
    mut project: Project,
    request: UpdateSourceRequest,
) -> Result<Project, ApiError> {
    if request.expected_revision != project.revision {
        return Err(ApiError::new(
            axum::http::StatusCode::CONFLICT,
            "revision_conflict",
            format!(
                "expected revision {} but current revision is {}",
                request.expected_revision, project.revision
            ),
        ));
    }
    let revision = project
        .revision
        .checked_add(1)
        .ok_or_else(|| ApiError::bad_request("source revision limit reached"))?;
    atomic_write(
        &source_path(directory, revision, &project.filename),
        request.source.as_bytes(),
    )
    .await?;
    project.revision = revision;
    let metadata = serde_json::to_vec_pretty(&project).map_err(internal_json_error)?;
    atomic_write(&directory.join("project.json"), &metadata).await?;
    Ok(project)
}

pub fn project_dir(session_dir: &Path, id: Uuid) -> PathBuf {
    session_dir.join("projects").join(id.to_string())
}

pub fn source_path(directory: &Path, revision: u64, filename: &str) -> PathBuf {
    directory
        .join("revisions")
        .join(revision.to_string())
        .join(filename)
}

pub async fn atomic_write(path: &Path, contents: &[u8]) -> Result<(), ApiError> {
    let parent = path.parent().ok_or_else(|| {
        ApiError::new(
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "invalid storage path",
        )
    })?;
    fs::create_dir_all(parent).await?;
    let temporary = parent.join(format!(".write-{}.tmp", Uuid::new_v4()));
    fs::write(&temporary, contents).await?;
    if let Err(error) = fs::rename(&temporary, path).await {
        let _ = fs::remove_file(&temporary).await;
        return Err(error.into());
    }
    Ok(())
}

fn internal_json_error(error: serde_json::Error) -> ApiError {
    tracing::error!(%error, "stored project metadata is invalid");
    ApiError::new(
        axum::http::StatusCode::INTERNAL_SERVER_ERROR,
        "internal_error",
        "stored project metadata is invalid",
    )
}
