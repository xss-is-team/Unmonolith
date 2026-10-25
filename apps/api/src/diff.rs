//! Unified diffs between project revisions and between a revision and an optimization candidate.

use serde::Serialize;
use sha2::{Digest, Sha256};
use similar::{ChangeTag, TextDiff};
use utoipa::ToSchema;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum DiffStatus {
    Added,
    Removed,
    Modified,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct FileDiff {
    pub path: String,
    pub status: DiffStatus,
    pub additions: usize,
    pub deletions: usize,
    /// Standard unified diff (`--- a/<path>` / `+++ b/<path>`, 3 lines of context).
    pub unified_diff: String,
}

/// Diff of one file; `None` on a side means the file does not exist there. Equal contents give `None`.
pub fn diff_file(path: &str, before: Option<&str>, after: Option<&str>) -> Option<FileDiff> {
    let status = match (before, after) {
        (Some(a), Some(b)) if a == b => return None,
        (Some(_), Some(_)) => DiffStatus::Modified,
        (None, Some(_)) => DiffStatus::Added,
        (Some(_), None) => DiffStatus::Removed,
        (None, None) => return None,
    };
    let a = before.unwrap_or("");
    let b = after.unwrap_or("");
    let diff = TextDiff::from_lines(a, b);
    let (mut additions, mut deletions) = (0, 0);
    for change in diff.iter_all_changes() {
        match change.tag() {
            ChangeTag::Insert => additions += 1,
            ChangeTag::Delete => deletions += 1,
            ChangeTag::Equal => {}
        }
    }
    let old = if before.is_some() {
        format!("a/{path}")
    } else {
        "/dev/null".to_owned()
    };
    let new = if after.is_some() {
        format!("b/{path}")
    } else {
        "/dev/null".to_owned()
    };
    let unified_diff = diff
        .unified_diff()
        .context_radius(3)
        .header(&old, &new)
        .to_string();
    Some(FileDiff {
        path: path.to_owned(),
        status,
        additions,
        deletions,
        unified_diff,
    })
}

/// Stable fingerprint of a set of file diffs, used to bind an apply request to the diff that was previewed.
pub fn fingerprint(diffs: &[FileDiff]) -> String {
    let mut hasher = Sha256::new();
    for diff in diffs {
        hasher.update(diff.path.as_bytes());
        hasher.update([0]);
        hasher.update(diff.unified_diff.as_bytes());
        hasher.update([0]);
    }
    hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modified_added_and_removed_files() {
        let modified = diff_file("A.java", Some("a\nb\nc\n"), Some("a\nB\nc\n")).unwrap();
        assert_eq!(modified.status, DiffStatus::Modified);
        assert_eq!((modified.additions, modified.deletions), (1, 1));
        assert!(modified.unified_diff.contains("--- a/A.java"));
        assert!(modified.unified_diff.contains("-b\n+B\n"));
        assert_eq!(
            diff_file("A.java", Some("x\n"), None).unwrap().status,
            DiffStatus::Removed
        );
        let added = diff_file("B.java", None, Some("x\n")).unwrap();
        assert_eq!(added.status, DiffStatus::Added);
        assert!(added.unified_diff.contains("--- /dev/null"));
        assert!(diff_file("A.java", Some("same"), Some("same")).is_none());
    }

    #[test]
    fn fingerprint_depends_on_content_and_path() {
        let a = diff_file("A.java", Some("a\n"), Some("b\n")).unwrap();
        let b = diff_file("A.java", Some("a\n"), Some("c\n")).unwrap();
        assert_eq!(fingerprint(&[a.clone()]), fingerprint(&[a.clone()]));
        assert_ne!(fingerprint(&[a]), fingerprint(&[b]));
    }
}
