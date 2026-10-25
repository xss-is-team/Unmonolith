//! ZIP import and file access against a real server. No Docker needed.

mod common;

use common::*;
use serde_json::json;

#[tokio::test]
async fn zip_with_packages_exposes_the_exact_file_tree_and_contents() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let entries = fixture_entries("legacy_java8");
    let project = api.create_from_fixture("legacy_java8").await;

    assert_eq!(project.kind, "archive");
    assert_eq!(project.file_count as usize, entries.len());
    assert!(
        entries.len() >= 5,
        "fixture should be a real multi-file project"
    );

    let tree = project.files().await;
    assert_eq!(tree["revision"], 1);
    let listed: Vec<String> = project.file_paths().await;
    let expected: Vec<String> = entries.iter().map(|(p, _)| p.clone()).collect();
    assert_eq!(listed, expected, "paths are preserved exactly, sorted");
    for file in tree["files"].as_array().unwrap() {
        assert_eq!(file["kind"], "java");
        assert_eq!(file["role"], "main");
        assert_eq!(file["source_root"], "src/main/java/");
        assert_eq!(file["sha256"].as_str().unwrap().len(), 64);
    }
    // every file round-trips byte for byte, and its recorded size matches
    for (path, content) in &entries {
        let response = project.file_response(path, None).await.expect_status(200);
        assert_eq!(response.body, *content, "content of {path}");
        assert_eq!(response.header("x-source-revision").as_deref(), Some("1"));
        let size = tree["files"]
            .as_array()
            .unwrap()
            .iter()
            .find(|f| f["path"] == path.as_str())
            .unwrap()["size"]
            .as_u64()
            .unwrap();
        assert_eq!(size as usize, content.len());
    }
    project
        .file_response("src/main/java/com/legacy/model/Missing.java", None)
        .await
        .expect_error(404, "file_not_found");
}

#[tokio::test]
async fn file_edits_create_snapshots_and_respect_optimistic_locking() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let project = api.create_from_fixture("legacy_java8").await;
    let path = "src/main/java/com/legacy/util/Algorithms.java";
    let original = project.source(path, None).await;
    let other = project
        .source("src/main/java/com/legacy/model/Account.java", None)
        .await;

    let edited = original.replace(
        "public static int identity(int x) {",
        "public static int identity(int x) { // edited",
    );
    assert_ne!(edited, original);
    let updated = project
        .put_file(path, &edited, 1)
        .await
        .expect_status(200)
        .json();
    assert_eq!(updated["revision"], 2);

    // stale revision is a conflict and changes nothing
    project
        .put_file(path, "class X {}", 1)
        .await
        .expect_error(409, "revision_conflict");
    assert_eq!(project.revision().await, 2);

    assert_eq!(
        project.source(path, Some(1)).await,
        original,
        "revision 1 is immutable"
    );
    assert_eq!(project.source(path, Some(2)).await, edited);
    assert_eq!(project.source(path, None).await, edited);
    assert_eq!(
        project
            .source("src/main/java/com/legacy/model/Account.java", Some(2))
            .await,
        other
    );
    project
        .file_response(path, Some(9))
        .await
        .expect_error(404, "revision_not_found");
    // unknown file cannot be created through the edit route
    project
        .put_file("src/main/java/Nope.java", "class Nope {}", 2)
        .await
        .expect_error(404, "file_not_found");
    let diff = project.revision_diff(1, 2).await;
    assert_eq!(diff["files"].as_array().unwrap().len(), 1);
    assert_eq!(diff["files"][0]["path"], path);
}

#[tokio::test]
async fn malformed_and_hostile_archives_are_rejected_explicitly() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let java = b"public class A { }\n".to_vec();

    let cases: Vec<(&str, Vec<u8>, u16, &str)> = vec![
        (
            "not-a-zip.zip",
            b"this is not a zip archive".to_vec(),
            422,
            "invalid_archive",
        ),
        (
            "truncated.zip",
            {
                let mut z = zip_of(&[("A.java".into(), java.clone())]);
                z.truncate(z.len() / 2);
                z
            },
            422,
            "invalid_archive",
        ),
        (
            "traversal.zip",
            zip_of(&[("../Evil.java".into(), java.clone())]),
            422,
            "unsafe_archive_path",
        ),
        (
            "deep-traversal.zip",
            zip_of(&[("a/../../Evil.java".into(), java.clone())]),
            422,
            "unsafe_archive_path",
        ),
        (
            "absolute.zip",
            zip_of(&[("/etc/Evil.java".into(), java.clone())]),
            422,
            "unsafe_archive_path",
        ),
        (
            "backslash.zip",
            zip_of(&[("a\\B.java".into(), java.clone())]),
            422,
            "unsafe_archive_path",
        ),
        (
            "symlink.zip",
            zip_with_symlink("/etc/passwd"),
            422,
            "archive_symlink",
        ),
        (
            "duplicates.zip",
            zip_of(&[
                ("A.java".into(), java.clone()),
                ("a.JAVA".into(), java.clone()),
            ]),
            422,
            "duplicate_archive_path",
        ),
        (
            "binary.zip",
            zip_of(&[
                ("A.java".into(), java.clone()),
                ("lib/x.class".into(), vec![0xCA, 0xFE]),
            ]),
            422,
            "unsupported_archive_entry",
        ),
        (
            "no-java.zip",
            zip_of(&[("README.md".into(), b"hi".to_vec())]),
            422,
            "no_java_sources",
        ),
        (
            "latin1.zip",
            zip_of(&[("A.java".into(), vec![b'/', b'/', 0xE9, 0xFF])]),
            422,
            "invalid_encoding",
        ),
    ];
    for (name, bytes, status, code) in cases {
        api.upload(name, bytes).await.expect_error(status, code);
    }
    // none of the rejected uploads created a project directory
    let projects = std::fs::read_dir(&server.data_dir)
        .map(|sessions| {
            sessions
                .flatten()
                .flat_map(|s| {
                    std::fs::read_dir(s.path().join("projects"))
                        .into_iter()
                        .flatten()
                        .flatten()
                })
                .count()
        })
        .unwrap_or(0);
    assert_eq!(projects, 0, "failed imports must not leave projects behind");
}

#[tokio::test]
async fn configured_resource_limits_are_enforced() {
    let mut limits = java_blueprints::ImportLimits::default();
    limits.max_files = 3;
    limits.max_file_bytes = 4096;
    limits.max_total_bytes = 6000;
    limits.max_compression_ratio = 20;
    let server = TestServer::start_with(ServerOptions {
        max_project_bytes: 4096,
        import_limits: limits,
        ..ServerOptions::default()
    })
    .await;
    let api = server.client().await;
    let small = |name: &str| {
        (
            name.to_owned(),
            format!("class {} {{ }}\n", name.trim_end_matches(".java")).into_bytes(),
        )
    };

    // too many files
    let many: Vec<_> = (0..4).map(|i| small(&format!("C{i}.java"))).collect();
    api.upload("many.zip", zip_of(&many))
        .await
        .expect_error(413, "project_too_large");
    // one file over the per-file limit
    let big = vec![("Big.java".to_owned(), vec![b'x'; 5000])];
    api.upload("big.zip", zip_of(&big))
        .await
        .expect_error(413, "project_too_large");
    // total uncompressed size
    let total: Vec<_> = (0..3)
        .map(|i| (format!("T{i}.java"), vec![b' '; 3000]))
        .collect();
    api.upload("total.zip", zip_of(&total))
        .await
        .expect_error(413, "project_too_large");
    // decompression bomb: a tiny archive that inflates far beyond the allowed ratio
    let bomb_server = TestServer::start_with(ServerOptions {
        import_limits: {
            let mut l = java_blueprints::ImportLimits::default();
            l.max_compression_ratio = 2;
            l
        },
        ..ServerOptions::default()
    })
    .await;
    let bomb_api = bomb_server.client().await;
    bomb_api
        .upload("bomb.zip", zip_with_big_entry(200_000))
        .await
        .expect_error(422, "archive_bomb");
    // an archive over the archive size limit
    let tiny = TestServer::start_with(ServerOptions {
        import_limits: {
            let mut l = java_blueprints::ImportLimits::default();
            l.max_archive_bytes = 200;
            l
        },
        ..ServerOptions::default()
    })
    .await;
    let tiny_api = tiny.client().await;
    let noise: Vec<(String, Vec<u8>)> = (0..30)
        .map(|i| {
            (
                format!("N{i}.java"),
                format!("class N{i} {{ int v = {}; }}\n", i * 7919).into_bytes(),
            )
        })
        .collect();
    tiny_api
        .upload("big.zip", zip_of(&noise))
        .await
        .expect_error(413, "project_too_large");
    // a project within every limit still imports
    api.create_project("ok.zip", zip_of(&many[..3])).await;
}

#[tokio::test]
async fn projects_are_private_to_their_session() {
    let server = TestServer::start().await;
    let owner = server.client().await;
    let intruder = server.client().await;
    let project = owner.create_from_fixture("legacy_java8").await;
    let path = "src/main/java/com/legacy/model/Account.java";

    let foreign = intruder.project_handle(&project.id);
    foreign
        .api
        .get(&foreign.path(""))
        .await
        .expect_error(404, "project_not_found");
    foreign
        .api
        .get(&foreign.path("/files"))
        .await
        .expect_error(404, "project_not_found");
    foreign
        .file_response(path, None)
        .await
        .expect_error(404, "project_not_found");
    foreign
        .put_file(path, "class X {}", 1)
        .await
        .expect_error(404, "project_not_found");
    foreign
        .api
        .post_json(&foreign.path("/analyze"), &json!(null))
        .await
        .expect_error(404, "project_not_found");
    foreign
        .api
        .get(&foreign.path("/complexity"))
        .await
        .expect_error(404, "project_not_found");
    assert_eq!(project.revision().await, 1, "the intruder changed nothing");

    // no session at all
    let anonymous = reqwest::Client::new()
        .get(format!(
            "{}/api/v1/projects/{}/files",
            server.base_url, project.id
        ))
        .send()
        .await
        .unwrap();
    assert_eq!(anonymous.status(), 401);
}

#[tokio::test]
async fn single_file_uploads_keep_working_unchanged() {
    let server = TestServer::start().await;
    let api = server.client().await;
    let source = fixture_text(
        "legacy_java8",
        "src/main/java/com/legacy/util/Algorithms.java",
    );
    let project = api
        .create_project("Algorithms.java", source.clone().into_bytes())
        .await;
    assert_eq!(project.kind, "single_file");
    assert_eq!(project.file_count, 1);

    // legacy routes
    let served = api.get(&project.path("/source")).await.expect_status(200);
    assert_eq!(served.text(), source);
    let updated = api
        .put_json(
            &project.path("/source"),
            &json!({"source": format!("{source}\n// touched\n"), "expected_revision": 1}),
        )
        .await
        .expect_status(200)
        .json();
    assert_eq!(updated["revision"], 2);
    api.put_json(
        &project.path("/source"),
        &json!({"source": "x", "expected_revision": 1}),
    )
    .await
    .expect_error(409, "revision_conflict");
    // and the new file routes work for it too
    assert_eq!(project.file_paths().await, vec!["Algorithms.java"]);
    assert!(project
        .source("Algorithms.java", None)
        .await
        .ends_with("// touched\n"));
    // non-Java uploads are still refused
    api.upload("notes.txt", b"hello".to_vec())
        .await
        .expect_error(400, "invalid_request");
    // archive-only restrictions are explicit for archives
    let archive = api.create_from_fixture("legacy_java8").await;
    api.get(&archive.path("/source"))
        .await
        .expect_error(409, "multi_file_project");
}
