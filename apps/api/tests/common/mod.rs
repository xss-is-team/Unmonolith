//! Reusable end-to-end harness: a real Axum server per test, a typed HTTP client and fixture helpers.
//!
//! * Every [`TestServer`] binds `127.0.0.1:0`, owns a private temporary data directory and shuts down on
//!   drop (also when the test panics), so tests can run concurrently without sharing any file or port.
//! * The Java side is the real parser JAR built from `apps/parser`; nothing is mocked or faked. The
//!   harness refuses to run against a JAR older than the parser sources.
//! * Docker-backed tests use the real runner image and call [`require_docker`].
//! * Every request and response is recorded; the transcript is printed when a test fails.
#![allow(dead_code)]

use std::{
    collections::BTreeMap,
    io::Write as _,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock},
    time::{Duration, SystemTime},
};

use java_blueprints::{app, AppState, Config, ImportLimits};
use reqwest::header::HeaderMap;
use serde_json::{json, Value};
use tempfile::TempDir;
use tokio::{sync::oneshot, task::JoinHandle};

pub const RUNNER_IMAGE: &str = "java-blueprints-runner:java21";

// ---------------------------------------------------------------- environment

fn api_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

/// The parser JAR; `BLUEPRINTS_REAL_JAR` overrides the default build output.
pub fn parser_jar() -> PathBuf {
    static JAR: OnceLock<PathBuf> = OnceLock::new();
    JAR.get_or_init(|| {
        let jar = std::env::var_os("BLUEPRINTS_REAL_JAR")
            .map(PathBuf::from)
            .unwrap_or_else(|| api_dir().join("../parser/target/ASTPARSER-1.0-SNAPSHOT.jar"));
        let jar = jar.canonicalize().unwrap_or_else(|_| {
            panic!(
                "parser JAR not found at {}; build it with `./dev parser-build` (or `mvn package` in apps/parser)",
                jar.display()
            )
        });
        let built = std::fs::metadata(&jar).and_then(|m| m.modified()).expect("jar mtime");
        let newest = newest_source_mtime(&api_dir().join("../parser/src"))
            .max(newest_source_mtime(&api_dir().join("../parser/pom.xml")));
        assert!(
            built >= newest,
            "{} is older than the parser sources: rebuild it with `./dev parser-build` so the E2E tests exercise the current code",
            jar.display()
        );
        jar
    })
    .clone()
}

fn newest_source_mtime(path: &Path) -> SystemTime {
    let Ok(meta) = std::fs::metadata(path) else {
        return SystemTime::UNIX_EPOCH;
    };
    if meta.is_file() {
        return meta.modified().unwrap_or(SystemTime::UNIX_EPOCH);
    }
    std::fs::read_dir(path)
        .into_iter()
        .flatten()
        .flatten()
        .map(|entry| newest_source_mtime(&entry.path()))
        .max()
        .unwrap_or(SystemTime::UNIX_EPOCH)
}

pub fn java_bin() -> PathBuf {
    std::env::var_os("BLUEPRINTS_REAL_JAVA")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("java"))
}

pub fn runner_image() -> String {
    std::env::var("BLUEPRINTS_REAL_RUNNER_IMAGE").unwrap_or_else(|_| RUNNER_IMAGE.to_owned())
}

/// Panics with an explicit blocker message when the Docker daemon or the runner image is missing.
/// Docker tests are `#[ignore]`d, so this only fires when they were requested.
pub fn require_docker() {
    static CHECK: OnceLock<Result<(), String>> = OnceLock::new();
    let result = CHECK.get_or_init(|| {
        let image = runner_image();
        let output = std::process::Command::new("docker")
            .args(["image", "inspect", &image])
            .output()
            .map_err(|e| format!("cannot run `docker`: {e}"))?;
        if output.status.success() {
            Ok(())
        } else {
            Err(format!(
                "runner image {image} is not available (is the Docker daemon running? build it with `./dev runner-build`): {}",
                String::from_utf8_lossy(&output.stderr).trim()
            ))
        }
    });
    if let Err(blocker) = result {
        panic!("DOCKER BLOCKER: {blocker}");
    }
}

// ---------------------------------------------------------------- server

#[derive(Clone)]
pub struct ServerOptions {
    pub docker: bool,
    pub max_project_bytes: usize,
    pub import_limits: ImportLimits,
    pub analyzer_timeout: Duration,
    pub benchmark_timeout: Duration,
}

impl Default for ServerOptions {
    fn default() -> Self {
        Self {
            docker: false,
            max_project_bytes: 2 * 1024 * 1024,
            import_limits: ImportLimits::default(),
            analyzer_timeout: Duration::from_secs(30),
            benchmark_timeout: Duration::from_secs(120),
        }
    }
}

impl ServerOptions {
    pub fn docker() -> Self {
        Self {
            docker: true,
            ..Self::default()
        }
    }
}

pub struct TestServer {
    pub base_url: String,
    pub data_dir: PathBuf,
    _workspace: TempDir,
    shutdown: Option<oneshot::Sender<()>>,
    task: Option<JoinHandle<()>>,
    transcript: Arc<Mutex<Vec<String>>>,
}

impl TestServer {
    pub async fn start() -> Self {
        Self::start_with(ServerOptions::default()).await
    }

    pub async fn start_docker() -> Self {
        require_docker();
        Self::start_with(ServerOptions::docker()).await
    }

    pub async fn start_with(options: ServerOptions) -> Self {
        let workspace = tempfile::Builder::new()
            .prefix("blueprints-e2e-")
            .tempdir()
            .expect("temporary workspace");
        let data_dir = workspace.path().join("data");
        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data_dir.clone(),
            max_project_bytes: options.max_project_bytes,
            import_limits: options.import_limits,
            secure_cookies: false,
            analyzer_jar: Some(parser_jar()),
            java_bin: java_bin(),
            analyzer_timeout: options.analyzer_timeout,
            analyzer_output_bytes: 2 * 1024 * 1024,
            runner_image: options.docker.then(runner_image),
            container_bin: "docker".into(),
            runner_compile_timeout: Duration::from_secs(60),
            runner_case_timeout: Duration::from_secs(20),
            runner_output_bytes: 64 * 1024,
            runner_benchmark_timeout: options.benchmark_timeout,
        })
        .await
        .expect("application state");
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("free port");
        let base_url = format!("http://{}", listener.local_addr().unwrap());
        let (shutdown, signal) = oneshot::channel::<()>();
        let task = tokio::spawn(async move {
            let _ = axum::serve(listener, app(state))
                .with_graceful_shutdown(async {
                    let _ = signal.await;
                })
                .await;
        });
        let server = Self {
            base_url,
            data_dir,
            _workspace: workspace,
            shutdown: Some(shutdown),
            task: Some(task),
            transcript: Arc::default(),
        };
        // Readiness: the socket is already listening, so one bounded request proves the router answers.
        let probe = reqwest::Client::new();
        let response = tokio::time::timeout(
            Duration::from_secs(10),
            probe
                .get(format!("{}/api/v1/healthz", server.base_url))
                .send(),
        )
        .await
        .expect("server readiness timed out")
        .expect("server did not answer");
        assert!(
            response.status().is_success(),
            "healthz: {}",
            response.status()
        );
        server
    }

    /// A fresh client with its own guest session (cookie jar).
    pub async fn client(&self) -> Api {
        let api = Api {
            base: format!("{}/api/v1", self.base_url),
            http: reqwest::Client::builder()
                .cookie_store(true)
                .timeout(Duration::from_secs(300))
                .build()
                .unwrap(),
            transcript: self.transcript.clone(),
        };
        api.post_json("/sessions/guest", &Value::Null)
            .await
            .expect_status(201);
        api
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        if let Some(task) = self.task.take() {
            task.abort();
        }
        if std::thread::panicking() {
            let lines = self
                .transcript
                .lock()
                .map(|t| t.clone())
                .unwrap_or_default();
            eprintln!(
                "---- HTTP transcript of the failed test ({} exchanges) ----",
                lines.len()
            );
            for line in lines.iter().rev().take(40).rev() {
                eprintln!("{line}");
            }
            eprintln!(
                "---- end transcript (server data dir: {}) ----",
                self.data_dir.display()
            );
        }
    }
}

// ---------------------------------------------------------------- HTTP client

#[derive(Clone)]
pub struct Api {
    base: String,
    http: reqwest::Client,
    transcript: Arc<Mutex<Vec<String>>>,
}

pub struct Resp {
    pub status: u16,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
    what: String,
}

impl Resp {
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    pub fn json(&self) -> Value {
        serde_json::from_slice(&self.body)
            .unwrap_or_else(|e| panic!("{}: body is not JSON ({e}): {}", self.what, self.text()))
    }

    #[track_caller]
    pub fn expect_status(self, status: u16) -> Self {
        assert_eq!(
            self.status,
            status,
            "{}: expected HTTP {status}, got {} with body: {}",
            self.what,
            self.status,
            truncate(&self.text(), 2000)
        );
        self
    }

    /// Asserts the status and the machine-readable error `code` of an error response.
    #[track_caller]
    pub fn expect_error(self, status: u16, code: &str) -> Value {
        let resp = self.expect_status(status);
        let body = resp.json();
        assert_eq!(body["code"], code, "{}: error body {body}", resp.what);
        body
    }

    pub fn header(&self, name: &str) -> Option<String> {
        self.headers
            .get(name)
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned)
    }
}

fn truncate(text: &str, max: usize) -> String {
    if text.len() <= max {
        text.to_owned()
    } else {
        format!(
            "{}…",
            &text[..text.char_indices().nth(max).map_or(text.len(), |(i, _)| i)]
        )
    }
}

impl Api {
    async fn record(
        &self,
        method: &str,
        path: &str,
        request: &str,
        response: reqwest::Response,
    ) -> Resp {
        let status = response.status().as_u16();
        let headers = response.headers().clone();
        let body = response.bytes().await.expect("response body").to_vec();
        let what = format!("{method} {path}");
        if let Ok(mut transcript) = self.transcript.lock() {
            transcript.push(format!(
                "{what}\n  > {}\n  < {status} {}",
                truncate(request, 600),
                truncate(&String::from_utf8_lossy(&body), 1200)
            ));
        }
        Resp {
            status,
            headers,
            body,
            what,
        }
    }

    pub async fn get(&self, path: &str) -> Resp {
        let response = self
            .http
            .get(format!("{}{path}", self.base))
            .send()
            .await
            .expect("GET");
        self.record("GET", path, "", response).await
    }

    pub async fn post_json(&self, path: &str, body: &Value) -> Resp {
        let request = self.http.post(format!("{}{path}", self.base));
        let request = if body.is_null() {
            request
        } else {
            request.json(body)
        };
        let response = request.send().await.expect("POST");
        self.record("POST", path, &body.to_string(), response).await
    }

    pub async fn put_json(&self, path: &str, body: &Value) -> Resp {
        let response = self
            .http
            .put(format!("{}{path}", self.base))
            .json(body)
            .send()
            .await
            .expect("PUT");
        self.record("PUT", path, &body.to_string(), response).await
    }

    /// `POST /projects` with one multipart `file` field.
    pub async fn upload(&self, filename: &str, bytes: Vec<u8>) -> Resp {
        let part = reqwest::multipart::Part::bytes(bytes.clone())
            .file_name(filename.to_owned())
            .mime_str("application/octet-stream")
            .unwrap();
        let form = reqwest::multipart::Form::new().part("file", part);
        let response = self
            .http
            .post(format!("{}/projects", self.base))
            .multipart(form)
            .send()
            .await
            .expect("upload");
        self.record(
            "POST",
            "/projects",
            &format!("multipart {filename} ({} bytes)", bytes.len()),
            response,
        )
        .await
    }

    /// Uploads and returns a typed project handle; asserts `201`.
    pub async fn create_project(&self, filename: &str, bytes: Vec<u8>) -> Project {
        let body = self.upload(filename, bytes).await.expect_status(201).json();
        Project {
            api: self.clone(),
            id: body["id"].as_str().expect("project id").to_owned(),
            kind: body["kind"].as_str().unwrap_or_default().to_owned(),
            file_count: body["file_count"].as_u64().unwrap_or(0),
        }
    }

    pub async fn create_from_fixture(&self, fixture: &str) -> Project {
        self.create_project(&format!("{fixture}.zip"), zip_of(&fixture_entries(fixture)))
            .await
    }

    pub async fn create_from_entries(&self, name: &str, entries: &[(String, Vec<u8>)]) -> Project {
        self.create_project(name, zip_of(entries)).await
    }

    /// A handle to an existing project id (e.g. to probe cross-session access).
    pub fn project_handle(&self, id: &str) -> Project {
        Project {
            api: self.clone(),
            id: id.to_owned(),
            kind: String::new(),
            file_count: 0,
        }
    }
}

// ---------------------------------------------------------------- typed project helper

#[derive(Clone)]
pub struct Project {
    pub api: Api,
    pub id: String,
    pub kind: String,
    pub file_count: u64,
}

impl Project {
    pub fn path(&self, suffix: &str) -> String {
        format!("/projects/{}{suffix}", self.id)
    }

    pub async fn info(&self) -> Value {
        self.api.get(&self.path("")).await.expect_status(200).json()
    }

    pub async fn revision(&self) -> u64 {
        self.info().await["revision"].as_u64().unwrap()
    }

    pub async fn files(&self) -> Value {
        self.api
            .get(&self.path("/files"))
            .await
            .expect_status(200)
            .json()
    }

    pub async fn file_paths(&self) -> Vec<String> {
        self.files().await["files"]
            .as_array()
            .unwrap()
            .iter()
            .map(|f| f["path"].as_str().unwrap().to_owned())
            .collect()
    }

    pub async fn file_response(&self, path: &str, revision: Option<u64>) -> Resp {
        let query = revision
            .map(|r| format!("?revision={r}"))
            .unwrap_or_default();
        self.api
            .get(&self.path(&format!("/files/{path}{query}")))
            .await
    }

    pub async fn source(&self, path: &str, revision: Option<u64>) -> String {
        self.file_response(path, revision)
            .await
            .expect_status(200)
            .text()
    }

    pub async fn put_file(&self, path: &str, source: &str, expected_revision: u64) -> Resp {
        self.api
            .put_json(
                &self.path(&format!("/files/{path}")),
                &json!({"source": source, "expected_revision": expected_revision}),
            )
            .await
    }

    pub async fn analyze(&self, level: &str) -> Value {
        self.api
            .post_json(
                &self.path(&format!("/analyze?source_level={level}")),
                &Value::Null,
            )
            .await
            .expect_status(200)
            .json()
    }

    pub async fn analyze_single_file(&self) -> Value {
        self.api
            .post_json(&self.path("/analyze"), &Value::Null)
            .await
            .expect_status(200)
            .json()
    }

    pub async fn methods(&self) -> Value {
        self.api
            .get(&self.path("/methods"))
            .await
            .expect_status(200)
            .json()
    }

    pub async fn selector(&self, method_id: &str) -> String {
        let methods = self.methods().await;
        methods["methods"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["id"] == method_id)
            .unwrap_or_else(|| panic!("no CFG method {method_id}; have {}", method_ids(&methods)))
            ["selector"]
            .as_str()
            .unwrap()
            .to_owned()
    }

    pub async fn cfg(&self, method_id: &str) -> Value {
        let selector = self.selector(method_id).await;
        self.api
            .get(&self.path(&format!("/methods/{selector}/cfg")))
            .await
            .expect_status(200)
            .json()
    }

    pub async fn cfg_node(&self, method_id: &str, text: &str) -> String {
        let cfg = self.cfg(method_id).await;
        cfg["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|n| n["text"] == text)
            .unwrap_or_else(|| panic!("no CFG node with text {text:?} in {method_id}"))["id"]
            .as_str()
            .unwrap()
            .to_owned()
    }

    pub async fn graph(&self) -> Value {
        self.api
            .get(&self.path("/graph"))
            .await
            .expect_status(200)
            .json()["graph"]
            .clone()
    }

    pub async fn complexity(&self) -> Value {
        self.api
            .get(&self.path("/complexity"))
            .await
            .expect_status(200)
            .json()
    }

    pub async fn complexity_of(&self, method_id: &str) -> Value {
        let all = self.complexity().await;
        all["methods"]
            .as_array()
            .unwrap()
            .iter()
            .find(|m| m["method_id"] == method_id)
            .unwrap_or_else(|| panic!("no complexity entry for {method_id}"))
            .clone()
    }

    pub async fn optimizations(&self, level: &str) -> Value {
        self.api
            .get(&self.path(&format!("/optimizations?source_level={level}")))
            .await
            .expect_status(200)
            .json()
    }

    /// The proposal for `method_id` (first loop), asserting it exists.
    pub async fn proposal_for(&self, level: &str, method_id: &str) -> Value {
        let all = self.optimizations(level).await;
        all["proposals"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["method_id"] == method_id)
            .unwrap_or_else(|| panic!("no proposal for {method_id}: {all}"))
            .clone()
    }

    pub async fn preview_optimization(&self, proposal: &str, revision: u64, level: &str) -> Resp {
        self.api
            .post_json(
                &self.path(&format!("/optimizations/{proposal}/preview")),
                &json!({"base_revision": revision, "source_level": level}),
            )
            .await
    }

    pub async fn apply_optimization(
        &self,
        proposal: &Value,
        revision: u64,
        level: &str,
        preview_sha256: &Value,
    ) -> Resp {
        let acknowledged: Vec<Value> = proposal["assumptions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|a| a["id"].clone())
            .collect();
        self.api
            .post_json(
                &self.path(&format!(
                    "/optimizations/{}/apply",
                    proposal["id"].as_str().unwrap()
                )),
                &json!({
                    "base_revision": revision, "source_level": level,
                    "preview_sha256": preview_sha256, "acknowledged_assumptions": acknowledged,
                }),
            )
            .await
    }

    /// Preview + apply of a proposal, asserting both succeed. Returns the apply response body.
    pub async fn optimize(&self, level: &str, method_id: &str) -> Value {
        let revision = self.revision().await;
        let proposal = self.proposal_for(level, method_id).await;
        assert_eq!(proposal["applicability"], "applicable", "{proposal}");
        let preview = self
            .preview_optimization(proposal["id"].as_str().unwrap(), revision, level)
            .await
            .expect_status(200)
            .json();
        self.apply_optimization(&proposal, revision, level, &preview["preview_sha256"])
            .await
            .expect_status(200)
            .json()
    }

    pub fn move_body(
        revision: u64,
        level: &str,
        method_id: &str,
        node: &str,
        target: &str,
        position: &str,
    ) -> Value {
        json!({
            "base_revision": revision, "source_level": level, "method_id": method_id,
            "operation": {"type": "move_statement", "node_id": node, "target_node_id": target, "position": position},
        })
    }

    pub async fn cfg_preview(&self, body: &Value) -> Resp {
        self.api
            .post_json(&self.path("/cfg-edits/preview"), body)
            .await
    }

    pub async fn cfg_apply(&self, body: &Value, preview_sha256: &Value) -> Resp {
        let mut body = body.clone();
        body["preview_sha256"] = preview_sha256.clone();
        self.api
            .post_json(&self.path("/cfg-edits/apply"), &body)
            .await
    }

    pub async fn revision_diff(&self, from: u64, to: u64) -> Value {
        self.api
            .get(&self.path(&format!("/revisions/{from}/diff/{to}")))
            .await
            .expect_status(200)
            .json()
    }

    pub async fn verify(&self, body: &Value) -> Resp {
        self.api.post_json(&self.path("/verify"), body).await
    }

    pub async fn benchmark(&self, body: &Value) -> Resp {
        self.api.post_json(&self.path("/benchmark"), body).await
    }
}

pub fn method_ids(methods: &Value) -> String {
    methods["methods"]
        .as_array()
        .map(|m| {
            m.iter()
                .filter_map(|x| x["id"].as_str())
                .collect::<Vec<_>>()
                .join(", ")
        })
        .unwrap_or_default()
}

// ---------------------------------------------------------------- fixtures and archives

pub fn fixtures_dir() -> PathBuf {
    api_dir().join("tests/fixtures")
}

/// All files of `tests/fixtures/<name>` as (relative path, bytes), sorted by path.
pub fn fixture_entries(name: &str) -> Vec<(String, Vec<u8>)> {
    let root = fixtures_dir().join(name);
    let mut files = BTreeMap::new();
    collect(&root, &root, &mut files);
    assert!(!files.is_empty(), "fixture {name} is empty or missing");
    files.into_iter().collect()
}

fn collect(root: &Path, dir: &Path, out: &mut BTreeMap<String, Vec<u8>>) {
    for entry in std::fs::read_dir(dir).expect("fixture dir").flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect(root, &path, out);
        } else {
            let relative = path
                .strip_prefix(root)
                .unwrap()
                .to_string_lossy()
                .replace('\\', "/");
            out.insert(relative, std::fs::read(&path).unwrap());
        }
    }
}

pub fn fixture_text(name: &str, path: &str) -> String {
    String::from_utf8(
        fixture_entries(name)
            .into_iter()
            .find(|(p, _)| p == path)
            .unwrap_or_else(|| panic!("no {path} in fixture {name}"))
            .1,
    )
    .unwrap()
}

/// The 976-line acceptance monolith of the parser tests.
pub fn organizer_source() -> Vec<u8> {
    std::fs::read(api_dir().join("../parser/src/test/resources/acceptance/Organizer.java"))
        .expect("Organizer.java acceptance fixture")
}

pub fn entries_with(
    base: Vec<(String, Vec<u8>)>,
    path: &str,
    content: impl Into<Vec<u8>>,
) -> Vec<(String, Vec<u8>)> {
    let mut entries: Vec<_> = base.into_iter().filter(|(p, _)| p != path).collect();
    entries.push((path.to_owned(), content.into()));
    entries.sort();
    entries
}

pub fn zip_of(entries: &[(String, Vec<u8>)]) -> Vec<u8> {
    let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    let options = zip::write::SimpleFileOptions::default();
    for (name, content) in entries {
        writer.start_file(name.as_str(), options).unwrap();
        writer.write_all(content).unwrap();
    }
    writer.finish().unwrap().into_inner()
}

pub fn zip_with_symlink(target_name: &str) -> Vec<u8> {
    let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    let options = zip::write::SimpleFileOptions::default();
    writer.start_file("Ok.java", options).unwrap();
    writer.write_all(b"public class Ok { }\n").unwrap();
    writer
        .add_symlink("Link.java", target_name, options)
        .unwrap();
    writer.finish().unwrap().into_inner()
}

/// A highly compressible entry, to trigger the archive limits.
pub fn zip_with_big_entry(size: usize) -> Vec<u8> {
    zip_of(&[("Big.java".to_owned(), vec![b'a'; size])])
}
