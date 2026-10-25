//! ZIP project import: validation and extraction into an in-memory file set.
//!
//! Nothing is written to disk here and no build script is ever executed. Every entry is either
//! accepted, recorded in `skipped` with a reason, or causes the whole import to be rejected.

use std::{
    collections::{BTreeMap, HashMap},
    io::{Cursor, Read},
};

use axum::http::StatusCode;
use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

use crate::error::ApiError;

/// Configurable resource limits for archive import.
#[derive(Clone, Debug)]
pub struct ImportLimits {
    /// Maximum size of the uploaded archive itself.
    pub max_archive_bytes: usize,
    /// Maximum number of archive entries (files and directories).
    pub max_entries: usize,
    /// Maximum number of stored (accepted) files.
    pub max_files: usize,
    /// Maximum uncompressed size of one file.
    pub max_file_bytes: usize,
    /// Maximum total uncompressed size of all accepted files.
    pub max_total_bytes: u64,
    /// Maximum uncompressed/compressed ratio of a single entry (decompression-bomb guard).
    pub max_compression_ratio: u64,
    pub max_path_bytes: usize,
    pub max_path_depth: usize,
}

impl Default for ImportLimits {
    fn default() -> Self {
        Self {
            max_archive_bytes: 16 * 1024 * 1024,
            max_entries: 2000,
            max_files: 1000,
            max_file_bytes: 2 * 1024 * 1024,
            max_total_bytes: 32 * 1024 * 1024,
            max_compression_ratio: 100,
            max_path_bytes: 260,
            max_path_depth: 32,
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum FileKind {
    /// A `.java` source file; the only kind that is analysed.
    Java,
    /// An inert text file kept for reference (build descriptors, properties, docs).
    /// Build scripts are stored but never executed.
    Resource,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct SkippedEntry {
    pub path: String,
    pub reason: String,
}

pub struct ExtractedFile {
    pub path: String,
    pub kind: FileKind,
    pub bytes: Vec<u8>,
}

pub struct ExtractedArchive {
    pub files: Vec<ExtractedFile>,
    pub skipped: Vec<SkippedEntry>,
}

const RESOURCE_EXTENSIONS: &[&str] = &[
    "xml",
    "gradle",
    "kts",
    "properties",
    "md",
    "txt",
    "yml",
    "yaml",
    "json",
    "csv",
];
const RESOURCE_NAMES: &[&str] = &["gradlew", ".gitignore", "license", "readme"];
const MAX_REPORTED: usize = 20;

pub fn is_zip(bytes: &[u8]) -> bool {
    bytes.starts_with(b"PK\x03\x04") || bytes.starts_with(b"PK\x05\x06")
}

fn reject(code: &'static str, message: impl Into<String>) -> ApiError {
    ApiError::new(StatusCode::UNPROCESSABLE_ENTITY, code, message)
}

fn too_large(message: impl Into<String>) -> ApiError {
    ApiError::new(StatusCode::PAYLOAD_TOO_LARGE, "project_too_large", message)
}

/// Normalizes an archive entry path. Returns `Err(reason)` for any path that is unsafe.
pub fn normalize_path(raw: &str, limits: &ImportLimits) -> Result<String, String> {
    if raw.contains('\0') || raw.chars().any(char::is_control) {
        return Err("path contains control characters".into());
    }
    if raw.contains('\\') {
        return Err("path contains a backslash".into());
    }
    if raw.starts_with('/') {
        return Err("absolute path".into());
    }
    let mut components = Vec::new();
    for component in raw.split('/') {
        match component {
            "" | "." => return Err("empty or '.' path component".into()),
            ".." => return Err("path traversal ('..')".into()),
            c if c.len() >= 2
                && c.as_bytes()[1] == b':'
                && c.as_bytes()[0].is_ascii_alphabetic() =>
            {
                return Err("drive-letter path".into())
            }
            c => components.push(c),
        }
    }
    if components.len() > limits.max_path_depth {
        return Err(format!("path deeper than {} levels", limits.max_path_depth));
    }
    let path = components.join("/");
    if path.len() > limits.max_path_bytes {
        return Err(format!("path longer than {} bytes", limits.max_path_bytes));
    }
    Ok(path)
}

fn classify(path: &str) -> Option<FileKind> {
    let name = path.rsplit('/').next().unwrap_or(path).to_ascii_lowercase();
    if name.ends_with(".java") {
        return Some(FileKind::Java);
    }
    let extension = name.rsplit_once('.').map(|(_, e)| e);
    if extension.is_some_and(|e| RESOURCE_EXTENSIONS.contains(&e))
        || RESOURCE_NAMES.contains(&name.as_str())
    {
        return Some(FileKind::Resource);
    }
    None
}

fn is_archive_junk(path: &str) -> bool {
    path.starts_with("__MACOSX/")
        || path.contains("/__MACOSX/")
        || path.rsplit('/').next() == Some(".DS_Store")
}

pub fn extract(bytes: &[u8], limits: &ImportLimits) -> Result<ExtractedArchive, ApiError> {
    if bytes.len() > limits.max_archive_bytes {
        return Err(too_large("archive exceeds the configured size limit"));
    }
    let mut archive = zip::ZipArchive::new(Cursor::new(bytes))
        .map_err(|_| reject("invalid_archive", "not a readable ZIP archive"))?;
    if archive.len() == 0 {
        return Err(reject("invalid_archive", "archive is empty"));
    }
    if archive.len() > limits.max_entries {
        return Err(too_large(format!(
            "archive has more than {} entries",
            limits.max_entries
        )));
    }

    let mut files: Vec<ExtractedFile> = Vec::new();
    let mut skipped = Vec::new();
    let mut unsupported = Vec::new();
    let mut seen: HashMap<String, String> = HashMap::new();
    let mut total: u64 = 0;

    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|_| reject("invalid_archive", "archive entry is unreadable"))?;
        let raw_name = entry.name().to_owned();
        if entry.encrypted() {
            return Err(reject(
                "invalid_archive",
                format!("encrypted entry: {raw_name}"),
            ));
        }
        if entry
            .unix_mode()
            .is_some_and(|mode| mode & 0o170000 == 0o120000)
        {
            return Err(reject(
                "archive_symlink",
                format!("symbolic links are not allowed: {raw_name}"),
            ));
        }
        if entry.is_dir() {
            let trimmed = raw_name.trim_end_matches('/');
            if !trimmed.is_empty() {
                normalize_path(trimmed, limits).map_err(|reason| {
                    reject("unsafe_archive_path", format!("{raw_name}: {reason}"))
                })?;
            }
            continue;
        }
        let path = normalize_path(&raw_name, limits)
            .map_err(|reason| reject("unsafe_archive_path", format!("{raw_name}: {reason}")))?;
        if is_archive_junk(&path) {
            skipped.push(SkippedEntry {
                path,
                reason: "archive metadata".into(),
            });
            continue;
        }
        let Some(kind) = classify(&path) else {
            unsupported.push(path);
            continue;
        };
        let key = path.to_lowercase();
        if let Some(previous) = seen.insert(key, path.clone()) {
            return Err(reject(
                "duplicate_archive_path",
                format!("{path} collides with {previous} after normalization"),
            ));
        }
        if files.len() >= limits.max_files {
            return Err(too_large(format!(
                "archive has more than {} project files",
                limits.max_files
            )));
        }
        // Do not trust declared sizes: stream with a hard cap.
        let cap = limits.max_file_bytes as u64;
        let compressed = entry.compressed_size().max(1);
        if entry.size() > cap {
            return Err(too_large(format!("{path} exceeds the per-file size limit")));
        }
        let mut content = Vec::new();
        entry
            .by_ref()
            .take(cap + 1)
            .read_to_end(&mut content)
            .map_err(|_| reject("invalid_archive", format!("{path} cannot be decompressed")))?;
        if content.len() as u64 > cap {
            return Err(too_large(format!("{path} exceeds the per-file size limit")));
        }
        if content.len() as u64 / compressed > limits.max_compression_ratio
            && content.len() > 64 * 1024
        {
            return Err(reject(
                "archive_bomb",
                format!("{path} has a suspicious compression ratio"),
            ));
        }
        total += content.len() as u64;
        if total > limits.max_total_bytes {
            return Err(too_large(
                "archive exceeds the total uncompressed size limit",
            ));
        }
        if std::str::from_utf8(&content).is_err() {
            return Err(reject(
                "invalid_encoding",
                format!("{path} is not valid UTF-8"),
            ));
        }
        files.push(ExtractedFile {
            path,
            kind,
            bytes: content,
        });
    }

    if !unsupported.is_empty() {
        let shown: Vec<_> = unsupported.iter().take(MAX_REPORTED).cloned().collect();
        return Err(reject(
            "unsupported_archive_entry",
            format!(
                "{} unsupported file(s) (only .java and inert text/build files are accepted): {}{}",
                unsupported.len(),
                shown.join(", "),
                if unsupported.len() > MAX_REPORTED {
                    ", ..."
                } else {
                    ""
                }
            ),
        ));
    }
    if !files.iter().any(|file| file.kind == FileKind::Java) {
        return Err(reject(
            "no_java_sources",
            "archive contains no .java source files",
        ));
    }
    reject_file_directory_conflicts(&files)?;
    files.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(ExtractedArchive { files, skipped })
}

fn reject_file_directory_conflicts(files: &[ExtractedFile]) -> Result<(), ApiError> {
    let mut directories: BTreeMap<&str, ()> = BTreeMap::new();
    for file in files {
        let mut end = 0;
        while let Some(offset) = file.path[end..].find('/') {
            end += offset;
            directories.insert(&file.path[..end], ());
            end += 1;
        }
    }
    for file in files {
        if directories.contains_key(file.path.as_str()) {
            return Err(reject(
                "duplicate_archive_path",
                format!("{} is both a file and a directory", file.path),
            ));
        }
    }
    Ok(())
}

/// Validates the path of a single-file edit request against the same rules as import.
pub fn validate_request_path(raw: &str) -> Result<String, ApiError> {
    normalize_path(raw, &ImportLimits::default())
        .map_err(|reason| ApiError::bad_request(format!("invalid file path: {reason}")))
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    fn zip_of(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut writer = zip::ZipWriter::new(Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default();
        for (name, content) in entries {
            writer.start_file(*name, options).unwrap();
            writer.write_all(content).unwrap();
        }
        writer.finish().unwrap().into_inner()
    }

    fn code(result: Result<ExtractedArchive, ApiError>) -> String {
        match result {
            Ok(_) => "ok".into(),
            Err(error) => error.code.to_owned(),
        }
    }

    #[test]
    fn accepts_nested_packages_and_reports_skipped_metadata() {
        let bytes = zip_of(&[
            ("src/main/java/a/A.java", b"package a; class A {}"),
            ("pom.xml", b"<project/>"),
            ("__MACOSX/._A.java", b"\x00\x05"),
        ]);
        let archive = extract(&bytes, &ImportLimits::default()).unwrap();
        let paths: Vec<_> = archive.files.iter().map(|f| f.path.as_str()).collect();
        assert_eq!(paths, ["pom.xml", "src/main/java/a/A.java"]);
        assert_eq!(archive.skipped.len(), 1);
    }

    #[test]
    fn rejects_traversal_absolute_and_backslash_paths() {
        let limits = ImportLimits::default();
        for name in [
            "../A.java",
            "a/../../A.java",
            "/etc/A.java",
            "a\\B.java",
            "C:/A.java",
        ] {
            let bytes = zip_of(&[(name, b"class A {}")]);
            assert_eq!(
                code(extract(&bytes, &limits)),
                "unsafe_archive_path",
                "{name}"
            );
        }
    }

    #[test]
    fn rejects_duplicates_after_case_normalization() {
        let bytes = zip_of(&[("A.java", b"class A {}"), ("a.java", b"class A {}")]);
        assert_eq!(
            code(extract(&bytes, &ImportLimits::default())),
            "duplicate_archive_path"
        );
    }

    #[test]
    fn rejects_file_directory_conflict() {
        let bytes = zip_of(&[("a", b"x"), ("a/B.java", b"class B {}")]);
        // "a" has no recognised extension, so it is reported as unsupported first.
        assert_eq!(
            code(extract(&bytes, &ImportLimits::default())),
            "unsupported_archive_entry"
        );
        let bytes = zip_of(&[("a.txt", b"x"), ("a.txt/B.java", b"class B {}")]);
        assert_eq!(
            code(extract(&bytes, &ImportLimits::default())),
            "duplicate_archive_path"
        );
    }

    #[test]
    fn rejects_unsupported_entries_explicitly() {
        let bytes = zip_of(&[("A.java", b"class A {}"), ("A.class", b"\xca\xfe")]);
        assert_eq!(
            code(extract(&bytes, &ImportLimits::default())),
            "unsupported_archive_entry"
        );
    }

    #[test]
    fn rejects_archives_without_java_and_malformed_archives() {
        let limits = ImportLimits::default();
        assert_eq!(
            code(extract(&zip_of(&[("README.md", b"hi")]), &limits)),
            "no_java_sources"
        );
        assert_eq!(
            code(extract(b"PK\x03\x04garbage", &limits)),
            "invalid_archive"
        );
    }

    #[test]
    fn enforces_entry_file_size_and_total_limits() {
        let bytes = zip_of(&[("A.java", b"class A {}"), ("B.java", b"class B {}")]);
        let limits = ImportLimits {
            max_files: 1,
            ..ImportLimits::default()
        };
        assert_eq!(code(extract(&bytes, &limits)), "project_too_large");
        let limits = ImportLimits {
            max_file_bytes: 4,
            ..ImportLimits::default()
        };
        assert_eq!(code(extract(&bytes, &limits)), "project_too_large");
        let limits = ImportLimits {
            max_total_bytes: 12,
            ..ImportLimits::default()
        };
        assert_eq!(code(extract(&bytes, &limits)), "project_too_large");
        let limits = ImportLimits {
            max_entries: 1,
            ..ImportLimits::default()
        };
        assert_eq!(code(extract(&bytes, &limits)), "project_too_large");
    }

    #[test]
    fn rejects_decompression_bombs() {
        let big = vec![b'a'; 1024 * 1024];
        let bytes = zip_of(&[("A.java", &big)]);
        let limits = ImportLimits {
            max_compression_ratio: 10,
            ..ImportLimits::default()
        };
        assert_eq!(code(extract(&bytes, &limits)), "archive_bomb");
    }

    #[test]
    fn rejects_symlink_entries() {
        let mut writer = zip::ZipWriter::new(Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default();
        writer
            .add_symlink("Link.java", "/etc/passwd", options)
            .unwrap();
        let bytes = writer.finish().unwrap().into_inner();
        assert_eq!(
            code(extract(&bytes, &ImportLimits::default())),
            "archive_symlink"
        );
    }

    #[test]
    fn rejects_invalid_utf8_source() {
        let bytes = zip_of(&[("A.java", b"\xff\xfe class A {}")]);
        assert_eq!(
            code(extract(&bytes, &ImportLimits::default())),
            "invalid_encoding"
        );
    }
}
