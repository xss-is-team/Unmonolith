use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use time::{format_description::well_known::Rfc3339, OffsetDateTime};
use tokio::fs;
use utoipa::ToSchema;
use uuid::Uuid;

use sha2::{Digest, Sha256};

use crate::{
    archive::{ExtractedArchive, FileKind, SkippedEntry},
    error::ApiError,
};

#[derive(Clone, Copy, Debug, Default, Deserialize, Eq, PartialEq, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum ProjectKind {
    /// One `.java` file; `filename` is that file.
    #[default]
    SingleFile,
    /// Imported ZIP archive; `filename` is the archive name and files live under their
    /// archive-relative paths.
    Archive,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct Project {
    pub id: Uuid,
    /// Single-file projects: the Java file name. Archive projects: the uploaded archive name.
    pub filename: String,
    #[serde(default)]
    pub kind: ProjectKind,
    #[serde(default = "one")]
    #[schema(minimum = 1)]
    pub file_count: u32,
    #[schema(minimum = 1)]
    pub revision: u64,
    #[schema(value_type = String, format = DateTime)]
    pub created_at: String,
}

fn one() -> u32 {
    1
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum FileRole {
    Main,
    Test,
    Resource,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct FileEntry {
    /// Archive-relative path with `/` separators.
    pub path: String,
    pub kind: FileKind,
    pub role: FileRole,
    /// Maven/Gradle-style source root (`src/main/java/`, `src/test/java/`) when the path is
    /// under one.
    pub source_root: Option<String>,
    pub size: u64,
    pub sha256: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct Manifest {
    #[schema(minimum = 1)]
    pub revision: u64,
    pub files: Vec<FileEntry>,
    /// Archive entries that were deliberately not stored, with the reason. Empty for
    /// single-file projects.
    pub skipped: Vec<SkippedEntry>,
}

#[derive(Debug, Deserialize, ToSchema)]
pub struct UpdateFileRequest {
    pub source: String,
    #[schema(minimum = 1)]
    pub expected_revision: u64,
}

#[derive(Debug, Deserialize, ToSchema)]
pub struct UpdateSourceRequest {
    pub source: String,
    #[schema(minimum = 1)]
    pub expected_revision: u64,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct RevisionSummary {
    #[schema(minimum = 1)]
    pub revision: u64,
    pub has_analysis: bool,
}

#[derive(Debug, Serialize, ToSchema)]
pub struct RevisionDetail {
    #[schema(minimum = 1)]
    pub revision: u64,
    pub source: String,
    pub has_analysis: bool,
}

pub async fn list_revisions(
    directory: &Path,
    current: u64,
    filename: &str,
    archive: bool,
) -> Result<Vec<RevisionSummary>, ApiError> {
    let mut revisions = Vec::new();
    for revision in (1..=current).rev() {
        let source = source_path(directory, revision, filename);
        let present = if archive {
            fs::metadata(directory.join("manifests").join(format!("{revision}.json"))).await
        } else {
            fs::metadata(source).await
        };
        if !matches!(present, Ok(metadata) if metadata.is_file()) {
            continue;
        }
        let has_analysis = matches!(
            fs::metadata(directory.join("analysis").join(format!("{revision}.json"))).await,
            Ok(metadata) if metadata.is_file()
        );
        revisions.push(RevisionSummary {
            revision,
            has_analysis,
        });
    }
    Ok(revisions)
}

pub async fn read_revision(
    directory: &Path,
    revision: u64,
    current: u64,
    filename: &str,
) -> Result<RevisionDetail, ApiError> {
    if revision == 0 || revision > current {
        return Err(revision_not_found());
    }
    let bytes = match fs::read(source_path(directory, revision, filename)).await {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(revision_not_found());
        }
        Err(error) => return Err(error.into()),
    };
    let source = String::from_utf8(bytes).map_err(|_| {
        ApiError::new(
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "stored source is not valid UTF-8",
        )
    })?;
    let has_analysis = matches!(
        fs::metadata(directory.join("analysis").join(format!("{revision}.json"))).await,
        Ok(metadata) if metadata.is_file()
    );
    Ok(RevisionDetail {
        revision,
        source,
        has_analysis,
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
        kind: ProjectKind::SingleFile,
        file_count: 1,
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
        return Err(revision_conflict(
            request.expected_revision,
            project.revision,
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

pub async fn commit_transformation(
    directory: &Path,
    mut project: Project,
    base_revision: u64,
    source: &[u8],
    analysis: &[u8],
) -> Result<Project, ApiError> {
    if base_revision != project.revision {
        return Err(revision_conflict(base_revision, project.revision));
    }
    let revision = project
        .revision
        .checked_add(1)
        .ok_or_else(|| ApiError::bad_request("source revision limit reached"))?;

    // Publish the metadata last. If either artifact write fails, the current project
    // revision remains unchanged and the incomplete next revision is unreachable.
    atomic_write(&source_path(directory, revision, &project.filename), source).await?;
    atomic_write(
        &directory.join("analysis").join(format!("{revision}.json")),
        analysis,
    )
    .await?;
    project.revision = revision;
    let metadata = serde_json::to_vec_pretty(&project).map_err(internal_json_error)?;
    atomic_write(&directory.join("project.json"), &metadata).await?;
    Ok(project)
}

pub fn is_archive(project: &Project) -> bool {
    project.kind == ProjectKind::Archive
}

pub fn multi_file_unsupported(operation: &str) -> ApiError {
    ApiError::new(
        axum::http::StatusCode::CONFLICT,
        "multi_file_project",
        format!("{operation} is not available for archive projects; use the /files endpoints"),
    )
}

fn classify_path(path: &str, kind: FileKind) -> (FileRole, Option<String>) {
    if kind == FileKind::Resource {
        return (FileRole::Resource, None);
    }
    for (marker, role) in [
        ("src/test/java/", FileRole::Test),
        ("src/main/java/", FileRole::Main),
    ] {
        if path.starts_with(marker) {
            return (role, Some(marker.to_owned()));
        }
        if let Some(index) = path.find(&format!("/{marker}")) {
            return (role, Some(path[..index + 1 + marker.len()].to_owned()));
        }
    }
    (FileRole::Main, None)
}

fn file_entry(path: &str, kind: FileKind, bytes: &[u8]) -> FileEntry {
    let (role, source_root) = classify_path(path, kind);
    FileEntry {
        path: path.to_owned(),
        kind,
        role,
        source_root,
        size: bytes.len() as u64,
        sha256: Sha256::digest(bytes)
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect(),
    }
}

fn manifest_path(directory: &Path, revision: u64) -> PathBuf {
    directory.join("manifests").join(format!("{revision}.json"))
}

pub async fn create_archive(
    session_dir: &Path,
    archive_name: &str,
    extracted: ExtractedArchive,
) -> Result<Project, ApiError> {
    let project = Project {
        id: Uuid::new_v4(),
        filename: archive_name.to_owned(),
        kind: ProjectKind::Archive,
        file_count: u32::try_from(extracted.files.len()).unwrap_or(u32::MAX),
        revision: 1,
        created_at: OffsetDateTime::now_utc()
            .format(&Rfc3339)
            .expect("RFC 3339 supports all current UTC dates"),
    };
    let directory = project_dir(session_dir, project.id);
    fs::create_dir_all(directory.join("analysis")).await?;
    let mut entries = Vec::new();
    for file in &extracted.files {
        atomic_write(&source_path(&directory, 1, &file.path), &file.bytes).await?;
        entries.push(file_entry(&file.path, file.kind, &file.bytes));
    }
    let manifest = Manifest {
        revision: 1,
        files: entries,
        skipped: extracted.skipped,
    };
    write_manifest(&directory, &manifest).await?;
    let metadata = serde_json::to_vec_pretty(&project).map_err(internal_json_error)?;
    atomic_write(&directory.join("project.json"), &metadata).await?;
    Ok(project)
}

async fn write_manifest(directory: &Path, manifest: &Manifest) -> Result<(), ApiError> {
    let bytes = serde_json::to_vec_pretty(manifest).map_err(internal_json_error)?;
    atomic_write(&manifest_path(directory, manifest.revision), &bytes).await
}

/// Manifest of a revision. Single-file projects have no stored manifest; theirs is derived from
/// the one stored source file.
pub async fn load_manifest(
    directory: &Path,
    project: &Project,
    revision: u64,
) -> Result<Manifest, ApiError> {
    if revision == 0 || revision > project.revision {
        return Err(revision_not_found());
    }
    if is_archive(project) {
        return match fs::read(manifest_path(directory, revision)).await {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(internal_json_error),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Err(revision_not_found()),
            Err(error) => Err(error.into()),
        };
    }
    let bytes = match fs::read(source_path(directory, revision, &project.filename)).await {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(revision_not_found());
        }
        Err(error) => return Err(error.into()),
    };
    Ok(Manifest {
        revision,
        files: vec![file_entry(&project.filename, FileKind::Java, &bytes)],
        skipped: Vec::new(),
    })
}

/// Reads one file of a revision. The path must be listed in the manifest, which makes
/// traversal outside the revision impossible regardless of the requested string.
pub async fn read_file(
    directory: &Path,
    project: &Project,
    revision: u64,
    path: &str,
) -> Result<Vec<u8>, ApiError> {
    let manifest = load_manifest(directory, project, revision).await?;
    if !manifest.files.iter().any(|file| file.path == path) {
        return Err(file_not_found());
    }
    Ok(fs::read(source_path(directory, revision, path)).await?)
}

/// Creates a new revision that replaces the content of one existing file.
/// Archive revisions are full snapshots, so every revision stays independently readable.
pub async fn update_file(
    directory: &Path,
    project: Project,
    path: &str,
    source: &[u8],
    expected_revision: u64,
) -> Result<Project, ApiError> {
    let previous = load_manifest(directory, &project, project.revision).await?;
    if !previous.files.iter().any(|file| file.path == path) {
        return Err(file_not_found());
    }
    commit_changes(
        directory,
        project,
        expected_revision,
        &[(path.to_owned(), source.to_vec())],
        None,
    )
    .await
}

/// Publishes a new revision that is the current one with `changes` (existing paths only) applied, together
/// with its analysis artifact when one is given. The project metadata is written last, so a failure leaves
/// the current revision untouched.
pub async fn commit_changes(
    directory: &Path,
    project: Project,
    base_revision: u64,
    changes: &[(String, Vec<u8>)],
    analysis: Option<&[u8]>,
) -> Result<Project, ApiError> {
    if base_revision != project.revision {
        return Err(revision_conflict(base_revision, project.revision));
    }
    let from = project.revision;
    publish_snapshot(directory, project, from, changes, &[], analysis).await
}

/// Publishes a new revision that rewrites existing files (`changes`) and creates new ones (`additions`), as the
/// decomposition of a monolith does. A single-file project becomes an archive project here: every earlier revision
/// gets a manifest listing its one file, so all revisions stay readable, comparable and verifiable. Project metadata
/// is written last, so a failure leaves the current revision (and the project kind) untouched.
pub async fn commit_decomposition(
    directory: &Path,
    mut project: Project,
    base_revision: u64,
    changes: &[(String, Vec<u8>)],
    additions: &[(String, Vec<u8>)],
    analysis: Option<&[u8]>,
) -> Result<Project, ApiError> {
    if base_revision != project.revision {
        return Err(revision_conflict(base_revision, project.revision));
    }
    if !is_archive(&project) {
        for revision in 1..=project.revision {
            let bytes = fs::read(source_path(directory, revision, &project.filename))
                .await
                .map_err(|_| revision_not_found())?;
            let manifest = Manifest {
                revision,
                files: vec![file_entry(&project.filename, FileKind::Java, &bytes)],
                skipped: Vec::new(),
            };
            write_manifest(directory, &manifest).await?;
        }
        project.kind = ProjectKind::Archive;
    }
    let from = project.revision;
    publish_snapshot(directory, project, from, changes, additions, analysis).await
}

/// Publishes a new revision whose files are exactly those of `to_revision` (rollback). History is kept:
/// the restored state becomes revision N+1 rather than rewinding the counter.
pub async fn restore_revision(
    directory: &Path,
    project: Project,
    base_revision: u64,
    to_revision: u64,
) -> Result<Project, ApiError> {
    if base_revision != project.revision {
        return Err(revision_conflict(base_revision, project.revision));
    }
    if to_revision == 0 || to_revision >= project.revision {
        return Err(ApiError::bad_request(
            "to_revision must be an earlier revision of this project",
        ));
    }
    let analysis = match fs::read(
        directory
            .join("analysis")
            .join(format!("{to_revision}.json")),
    )
    .await
    {
        Ok(bytes) => {
            let mut value: serde_json::Value =
                serde_json::from_slice(&bytes).map_err(internal_json_error)?;
            let next = project
                .revision
                .checked_add(1)
                .ok_or_else(|| ApiError::bad_request("source revision limit reached"))?;
            if let Some(object) = value.as_object_mut() {
                object.insert("revision".to_owned(), next.into());
            }
            Some(serde_json::to_vec(&value).map_err(internal_json_error)?)
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
        Err(error) => return Err(error.into()),
    };
    publish_snapshot(
        directory,
        project,
        to_revision,
        &[],
        &[],
        analysis.as_deref(),
    )
    .await
}

async fn publish_snapshot(
    directory: &Path,
    mut project: Project,
    from_revision: u64,
    changes: &[(String, Vec<u8>)],
    additions: &[(String, Vec<u8>)],
    analysis: Option<&[u8]>,
) -> Result<Project, ApiError> {
    let base = load_manifest(directory, &project, from_revision).await?;
    for (path, _) in changes {
        if !base.files.iter().any(|file| &file.path == path) {
            return Err(file_not_found());
        }
    }
    for (path, _) in additions {
        if base.files.iter().any(|file| &file.path == path) {
            return Err(ApiError::new(
                axum::http::StatusCode::CONFLICT,
                "file_exists",
                format!("{path} already exists in this project revision"),
            ));
        }
    }
    let revision = project
        .revision
        .checked_add(1)
        .ok_or_else(|| ApiError::bad_request("source revision limit reached"))?;

    if !is_archive(&project) {
        debug_assert!(
            additions.is_empty(),
            "single-file projects hold exactly one file"
        );
        let source = match changes.first() {
            Some((_, bytes)) => bytes.clone(),
            None => fs::read(source_path(directory, from_revision, &project.filename)).await?,
        };
        atomic_write(
            &source_path(directory, revision, &project.filename),
            &source,
        )
        .await?;
        if let Some(analysis) = analysis {
            atomic_write(
                &directory.join("analysis").join(format!("{revision}.json")),
                analysis,
            )
            .await?;
        }
        project.revision = revision;
        let metadata = serde_json::to_vec_pretty(&project).map_err(internal_json_error)?;
        atomic_write(&directory.join("project.json"), &metadata).await?;
        return Ok(project);
    }

    let staging = directory
        .join("revisions")
        .join(format!(".stage-{}", Uuid::new_v4()));
    let result = async {
        let mut files = Vec::with_capacity(base.files.len());
        for entry in &base.files {
            let target = staging.join(&entry.path);
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent).await?;
            }
            if let Some((_, bytes)) = changes.iter().find(|(path, _)| path == &entry.path) {
                fs::write(&target, bytes).await?;
                files.push(file_entry(&entry.path, entry.kind, bytes));
            } else {
                fs::copy(source_path(directory, from_revision, &entry.path), &target).await?;
                files.push(entry.clone());
            }
        }
        for (path, bytes) in additions {
            let target = staging.join(path);
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent).await?;
            }
            fs::write(&target, bytes).await?;
            files.push(file_entry(path, FileKind::Java, bytes));
        }
        fs::rename(&staging, revision_dir(directory, revision)).await?;
        Ok::<_, ApiError>(Manifest {
            revision,
            files,
            skipped: base.skipped.clone(),
        })
    }
    .await;
    let manifest = match result {
        Ok(manifest) => manifest,
        Err(error) => {
            let _ = fs::remove_dir_all(&staging).await;
            return Err(error);
        }
    };
    write_manifest(directory, &manifest).await?;
    if let Some(analysis) = analysis {
        atomic_write(
            &directory.join("analysis").join(format!("{revision}.json")),
            analysis,
        )
        .await?;
    }
    project.revision = revision;
    project.file_count = u32::try_from(manifest.files.len()).unwrap_or(u32::MAX);
    let metadata = serde_json::to_vec_pretty(&project).map_err(internal_json_error)?;
    atomic_write(&directory.join("project.json"), &metadata).await?;
    Ok(project)
}

fn file_not_found() -> ApiError {
    ApiError::new(
        axum::http::StatusCode::NOT_FOUND,
        "file_not_found",
        "file does not exist in this project revision",
    )
}

pub fn project_dir(session_dir: &Path, id: Uuid) -> PathBuf {
    session_dir.join("projects").join(id.to_string())
}

pub fn revision_dir(directory: &Path, revision: u64) -> PathBuf {
    directory.join("revisions").join(revision.to_string())
}

pub fn source_path(directory: &Path, revision: u64, filename: &str) -> PathBuf {
    directory
        .join("revisions")
        .join(revision.to_string())
        .join(filename)
}

pub fn verification_report_path(directory: &Path, report_id: Uuid) -> PathBuf {
    directory
        .join("verification")
        .join(format!("{report_id}.json"))
}

pub fn benchmark_report_path(directory: &Path, report_id: Uuid) -> PathBuf {
    directory
        .join("benchmarks")
        .join(format!("{report_id}.json"))
}

pub async fn store_benchmark_report(
    directory: &Path,
    report_id: Uuid,
    contents: &[u8],
) -> Result<(), ApiError> {
    atomic_write(&benchmark_report_path(directory, report_id), contents).await
}

pub async fn store_verification_report(
    directory: &Path,
    report_id: Uuid,
    contents: &[u8],
) -> Result<(), ApiError> {
    atomic_write(&verification_report_path(directory, report_id), contents).await
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

fn revision_conflict(expected: u64, current: u64) -> ApiError {
    ApiError::new(
        axum::http::StatusCode::CONFLICT,
        "revision_conflict",
        format!("expected revision {expected} but current revision is {current}"),
    )
}

fn revision_not_found() -> ApiError {
    ApiError::new(
        axum::http::StatusCode::NOT_FOUND,
        "revision_not_found",
        "requested source revision does not exist",
    )
}
