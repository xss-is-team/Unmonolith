use std::{path::PathBuf, sync::Arc};

use axum::{
    body::Body,
    extract::{
        multipart::MultipartRejection, rejection::JsonRejection, DefaultBodyLimit, Multipart, Path,
        State,
    },
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::Response,
    routing::{get, post},
    Json, Router,
};
use serde::Serialize;
use serde_json::Value;
use tokio::{fs, sync::Mutex};
use utoipa::{Modify, OpenApi};
use utoipa_swagger_ui::SwaggerUi;
use uuid::Uuid;

use crate::{
    analyzer::{
        AnalysisResult, AnalysisStatus, Diagnostic, DiagnosticSeverity, JavaAnalyzer, Method,
        SourceRange, TransformOperation, TransformRequest,
    },
    error::{ApiError, ApiErrorBody},
    project::{self, Project, UpdateSourceRequest},
    session, Config,
};

#[derive(Clone)]
pub struct AppState(Arc<AppStateInner>);

struct AppStateInner {
    data_dir: PathBuf,
    max_project_bytes: usize,
    secure_cookies: bool,
    analyzer: JavaAnalyzer,
    writes: Mutex<()>,
}

impl AppState {
    pub async fn new(config: Config) -> std::io::Result<Self> {
        fs::create_dir_all(config.data_dir.join("sessions")).await?;
        let data_dir = fs::canonicalize(&config.data_dir).await?;
        Ok(Self(Arc::new(AppStateInner {
            data_dir,
            max_project_bytes: config.max_project_bytes,
            secure_cookies: config.secure_cookies,
            analyzer: JavaAnalyzer::new(
                config.analyzer_jar,
                config.java_bin,
                config.analyzer_timeout,
                config.analyzer_output_bytes,
            ),
            writes: Mutex::new(()),
        })))
    }
}

#[derive(OpenApi)]
#[openapi(
    paths(
        healthz,
        create_guest_session,
        upload_project,
        get_project,
        get_project_source,
        update_project_source,
        analyze_project,
        list_methods,
        get_method_cfg,
        transform_project
    ),
    components(schemas(
        HealthResponse,
        GuestSessionResponse,
        UploadRequest,
        ApiErrorBody,
        Project,
        UpdateSourceRequest,
        AnalysisResult,
        AnalysisStatus,
        Method,
        Diagnostic,
        DiagnosticSeverity,
        SourceRange,
        MethodsResponse,
        CfgMethod,
        Cfg,
        CfgNode,
        CfgEdge,
        TransformRequest,
        TransformOperation,
        TransformResponse
    )),
    modifiers(&SecurityAddon),
    tags(
        (name = "System"),
        (name = "Sessions"),
        (name = "Projects"),
        (name = "Analysis"),
        (name = "Transformations")
    )
)]
struct ApiDoc;

struct SecurityAddon;

impl Modify for SecurityAddon {
    fn modify(&self, openapi: &mut utoipa::openapi::OpenApi) {
        use utoipa::openapi::security::{ApiKey, ApiKeyValue, SecurityScheme};

        if let Some(components) = openapi.components.as_mut() {
            components.add_security_scheme(
                "guestSession",
                SecurityScheme::ApiKey(ApiKey::Cookie(ApiKeyValue::new(session::COOKIE_NAME))),
            );
        }
    }
}

pub fn app(state: AppState) -> Router {
    let body_limit = state.0.max_project_bytes.saturating_add(64 * 1024);
    let api = Router::new()
        .route("/healthz", get(healthz))
        .route("/sessions/guest", post(create_guest_session))
        .route("/projects", post(upload_project))
        .route("/projects/{project_id}", get(get_project))
        .route(
            "/projects/{project_id}/source",
            get(get_project_source).put(update_project_source),
        )
        .route("/projects/{project_id}/analyze", post(analyze_project))
        .route("/projects/{project_id}/methods", get(list_methods))
        .route(
            "/projects/{project_id}/methods/{method_selector}/cfg",
            get(get_method_cfg),
        )
        .route("/projects/{project_id}/transform", post(transform_project))
        .layer(DefaultBodyLimit::max(body_limit));

    Router::new()
        .nest("/api/v1", api)
        .merge(SwaggerUi::new("/swagger-ui").url("/api/v1/openapi.json", ApiDoc::openapi()))
        .with_state(state)
}

#[derive(Serialize, utoipa::ToSchema)]
struct HealthResponse {
    status: &'static str,
}

#[derive(Serialize, utoipa::ToSchema)]
struct GuestSessionResponse {
    kind: &'static str,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct UploadRequest {
    #[schema(value_type = String, format = Binary)]
    file: Vec<u8>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct MethodsResponse {
    #[schema(minimum = 1)]
    revision: u64,
    methods: Vec<CfgMethod>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct CfgMethod {
    selector: String,
    id: String,
    name: String,
    kind: String,
    source_range: SourceRange,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct Cfg {
    method_id: String,
    entry: String,
    exit: String,
    nodes: Vec<CfgNode>,
    edges: Vec<CfgEdge>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct CfgNode {
    id: String,
    kind: String,
    statement_kind: Option<String>,
    text: Option<String>,
    source_range: SourceRange,
    reachable: Option<bool>,
}

#[derive(utoipa::ToSchema)]
#[allow(dead_code)]
struct CfgEdge {
    from: String,
    to: String,
    kind: String,
    label: Option<String>,
    back: Option<bool>,
}

#[derive(Serialize, utoipa::ToSchema)]
struct TransformResponse {
    #[schema(minimum = 2)]
    revision: u64,
    source: String,
}

#[utoipa::path(
    get,
    path = "/api/v1/healthz",
    tag = "System",
    responses((status = 200, body = HealthResponse))
)]
async fn healthz() -> Json<HealthResponse> {
    Json(HealthResponse { status: "ok" })
}

#[utoipa::path(
    post,
    path = "/api/v1/sessions/guest",
    tag = "Sessions",
    responses((
        status = 201,
        body = GuestSessionResponse,
        headers(("Set-Cookie" = String, description = "Opaque HttpOnly guest session cookie"))
    ))
)]
async fn create_guest_session(
    State(state): State<AppState>,
) -> Result<(StatusCode, HeaderMap, Json<GuestSessionResponse>), ApiError> {
    let new_session = session::create(&state.0.data_dir, state.0.secure_cookies).await?;
    let mut headers = HeaderMap::new();
    headers.insert(
        header::SET_COOKIE,
        HeaderValue::from_str(&new_session.cookie).map_err(|_| {
            ApiError::new(
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                "could not create session cookie",
            )
        })?,
    );
    Ok((
        StatusCode::CREATED,
        headers,
        Json(GuestSessionResponse { kind: "guest" }),
    ))
}

#[utoipa::path(
    post,
    path = "/api/v1/projects",
    tag = "Projects",
    security(("guestSession" = [])),
    request_body(content = inline(UploadRequest), content_type = "multipart/form-data"),
    responses(
        (status = 201, body = Project),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 413, body = ApiErrorBody)
    )
)]
async fn upload_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    multipart: Result<Multipart, MultipartRejection>,
) -> Result<(StatusCode, Json<Project>), ApiError> {
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let mut multipart = multipart.map_err(|error| {
        if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
            ApiError::new(
                StatusCode::PAYLOAD_TOO_LARGE,
                "project_too_large",
                "uploaded source exceeds the configured size limit",
            )
        } else {
            ApiError::bad_request("invalid multipart upload")
        }
    })?;
    let mut source = None;
    while let Some(mut field) = multipart
        .next_field()
        .await
        .map_err(|_| ApiError::bad_request("invalid multipart upload"))?
    {
        if field.name() != Some("file") || source.is_some() {
            return Err(ApiError::bad_request(
                "upload must contain exactly one field named file",
            ));
        }
        let filename = field
            .file_name()
            .ok_or_else(|| ApiError::bad_request("file must include a filename"))?;
        let filename = normalize_java_filename(filename)?;

        let mut bytes = Vec::new();
        while let Some(chunk) = field
            .chunk()
            .await
            .map_err(|_| ApiError::bad_request("invalid multipart upload"))?
        {
            if bytes.len().saturating_add(chunk.len()) > state.0.max_project_bytes {
                return Err(ApiError::new(
                    StatusCode::PAYLOAD_TOO_LARGE,
                    "project_too_large",
                    "uploaded source exceeds the configured size limit",
                ));
            }
            bytes.extend_from_slice(&chunk);
        }
        std::str::from_utf8(&bytes)
            .map_err(|_| ApiError::bad_request("Java source must be valid UTF-8"))?;
        source = Some((filename, bytes));
    }
    let (filename, source) = source.ok_or_else(|| ApiError::bad_request("missing file field"))?;
    let _guard = state.0.writes.lock().await;
    let project = project::create(&session_dir, &filename, &source).await?;
    Ok((StatusCode::CREATED, Json(project)))
}

fn normalize_java_filename(value: &str) -> Result<String, ApiError> {
    let filename = value.rsplit(['/', '\\']).next().unwrap_or_default();
    if filename.is_empty()
        || filename == "."
        || filename == ".."
        || filename.contains('\0')
        || !filename.to_ascii_lowercase().ends_with(".java")
    {
        return Err(ApiError::bad_request("only one .java file is supported"));
    }
    Ok(filename.to_owned())
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, body = Project),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Json<Project>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, _) = project::load(&session_dir, project_id).await?;
    Ok(Json(project))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/source",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, body = String, content_type = "text/plain", headers(("X-Source-Revision" = u64))),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_project_source(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Response, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let source = project::read_source(&directory, project.revision, &project.filename).await?;
    let mut response = Response::new(Body::from(source));
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; charset=utf-8"),
    );
    response.headers_mut().insert(
        "x-source-revision",
        HeaderValue::from_str(&project.revision.to_string()).expect("integer header is valid"),
    );
    Ok(response)
}

#[utoipa::path(
    put,
    path = "/api/v1/projects/{project_id}/source",
    tag = "Projects",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = UpdateSourceRequest,
    responses(
        (status = 200, body = Project),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody),
        (status = 413, body = ApiErrorBody)
    )
)]
async fn update_project_source(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<UpdateSourceRequest>, JsonRejection>,
) -> Result<Json<Project>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid source update"))?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    if request.source.len() > state.0.max_project_bytes {
        return Err(ApiError::bad_request(
            "source exceeds the configured size limit",
        ));
    }
    let _guard = state.0.writes.lock().await;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    Ok(Json(project::update(&directory, project, request).await?))
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/analyze",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, body = AnalysisResult),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn analyze_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Json<AnalysisResult>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    Ok(Json(
        state
            .0
            .analyzer
            .analyze(directory, project.revision, &project.filename)
            .await?,
    ))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/methods",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    responses(
        (status = 200, body = MethodsResponse),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn list_methods(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
) -> Result<Json<MethodsResponse>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let artifact = load_analysis(&directory, project.revision).await?;
    let mut methods = Vec::new();
    visit_cfg_methods(&artifact, &mut |member, cfg| {
        if let Some(method) = cfg_method(member, cfg) {
            methods.push(method);
        }
    });
    Ok(Json(MethodsResponse {
        revision: project.revision,
        methods,
    }))
}

#[utoipa::path(
    get,
    path = "/api/v1/projects/{project_id}/methods/{method_selector}/cfg",
    tag = "Analysis",
    security(("guestSession" = [])),
    params(
        ("project_id" = Uuid, Path),
        ("method_selector" = String, Path, description = "Opaque revision-local selector returned by the methods endpoint")
    ),
    responses(
        (status = 200, body = Cfg),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody)
    )
)]
async fn get_method_cfg(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((project_id, method_selector)): Path<(String, String)>,
) -> Result<Json<Value>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    let artifact = load_analysis(&directory, project.revision).await?;
    let mut found = None;
    visit_cfg_methods(&artifact, &mut |_member, cfg| {
        if found.is_none() && cfg_selector(cfg) == Some(method_selector.as_str()) {
            found = Some(cfg.clone());
        }
    });
    found.map(Json).ok_or_else(method_not_found)
}

#[utoipa::path(
    post,
    path = "/api/v1/projects/{project_id}/transform",
    tag = "Transformations",
    security(("guestSession" = [])),
    params(("project_id" = Uuid, Path)),
    request_body = TransformRequest,
    responses(
        (status = 200, body = TransformResponse),
        (status = 400, body = ApiErrorBody),
        (status = 401, body = ApiErrorBody),
        (status = 404, body = ApiErrorBody),
        (status = 409, body = ApiErrorBody),
        (status = 422, body = ApiErrorBody),
        (status = 503, body = ApiErrorBody),
        (status = 504, body = ApiErrorBody)
    )
)]
async fn transform_project(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(project_id): Path<String>,
    request: Result<Json<TransformRequest>, JsonRejection>,
) -> Result<Json<TransformResponse>, ApiError> {
    let project_id = parse_project_id(&project_id)?;
    let Json(request) = request.map_err(|_| ApiError::bad_request("invalid transform request"))?;
    if request.operation.expression().trim().is_empty() {
        return Err(ApiError::bad_request(
            "replacement expression must not be empty",
        ));
    }
    let session_dir = session::authenticate(&state.0.data_dir, &headers).await?;
    let _guard = state.0.writes.lock().await;
    let (project, directory) = project::load(&session_dir, project_id).await?;
    if request.base_revision != project.revision {
        return Err(ApiError::new(
            StatusCode::CONFLICT,
            "revision_conflict",
            format!(
                "expected revision {} but current revision is {}",
                request.base_revision, project.revision
            ),
        ));
    }
    let artifact = load_analysis(&directory, project.revision).await?;
    validate_transform_target(&artifact, &request.method_id, &request.node_id)?;

    let transformed = state
        .0
        .analyzer
        .transform(
            &directory,
            &project.filename,
            &request,
            state.0.max_project_bytes,
        )
        .await?;
    let project = project::commit_transformation(
        &directory,
        project,
        request.base_revision,
        transformed.source.as_bytes(),
        &transformed.analysis,
    )
    .await?;
    Ok(Json(TransformResponse {
        revision: project.revision,
        source: transformed.source,
    }))
}

async fn load_analysis(directory: &std::path::Path, revision: u64) -> Result<Value, ApiError> {
    let path = directory.join("analysis").join(format!("{revision}.json"));
    let bytes = match fs::read(path).await {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(ApiError::new(
                StatusCode::NOT_FOUND,
                "analysis_not_found",
                "the current source revision has not been analyzed",
            ));
        }
        Err(error) => return Err(error.into()),
    };
    let artifact: Value = serde_json::from_slice(&bytes).map_err(|error| {
        tracing::error!(%error, revision, "stored analysis artifact is invalid");
        ApiError::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal_error",
            "stored analysis artifact is invalid",
        )
    })?;
    if artifact.get("revision").and_then(Value::as_u64) != Some(revision) {
        return Err(ApiError::new(
            StatusCode::CONFLICT,
            "analysis_revision_mismatch",
            "analysis does not match the current source revision",
        ));
    }
    Ok(artifact)
}

fn visit_cfg_methods<'a>(value: &'a Value, visitor: &mut impl FnMut(&'a Value, &'a Value)) {
    match value {
        Value::Object(object) => {
            let is_method = matches!(
                object.get("kind").and_then(Value::as_str),
                Some("method" | "constructor")
            );
            if is_method {
                if let Some(cfg) = object.get("cfg").filter(|cfg| cfg.is_object()) {
                    visitor(value, cfg);
                }
            }
            for (key, child) in object {
                if key != "cfg" {
                    visit_cfg_methods(child, visitor);
                }
            }
        }
        Value::Array(values) => {
            for child in values {
                visit_cfg_methods(child, visitor);
            }
        }
        _ => {}
    }
}

fn cfg_selector(cfg: &Value) -> Option<&str> {
    let entry = cfg.get("entry")?.as_str()?;
    let (prefix, node) = entry.rsplit_once(".n")?;
    if !prefix.starts_with('m')
        || !prefix[1..].bytes().all(|byte| byte.is_ascii_digit())
        || !node.bytes().all(|byte| byte.is_ascii_digit())
    {
        return None;
    }
    Some(prefix)
}

fn cfg_method(member: &Value, cfg: &Value) -> Option<CfgMethod> {
    Some(CfgMethod {
        selector: cfg_selector(cfg)?.to_owned(),
        id: member.get("id")?.as_str()?.to_owned(),
        name: member.get("name")?.as_str()?.to_owned(),
        kind: member.get("kind")?.as_str()?.to_owned(),
        source_range: serde_json::from_value(member.get("source_range")?.clone()).ok()?,
    })
}

fn validate_transform_target(
    artifact: &Value,
    method_id: &str,
    node_id: &str,
) -> Result<(), ApiError> {
    let mut method_cfg = None;
    visit_cfg_methods(artifact, &mut |member, cfg| {
        if method_cfg.is_none() && member.get("id").and_then(Value::as_str) == Some(method_id) {
            method_cfg = Some(cfg);
        }
    });
    let cfg = method_cfg.ok_or_else(method_not_found)?;
    let node = cfg
        .get("nodes")
        .and_then(Value::as_array)
        .and_then(|nodes| {
            nodes
                .iter()
                .find(|node| node.get("id").and_then(Value::as_str) == Some(node_id))
        })
        .ok_or_else(|| {
            ApiError::new(
                StatusCode::NOT_FOUND,
                "node_not_found",
                "CFG node does not exist in the selected method",
            )
        })?;
    if node.get("kind").and_then(Value::as_str) != Some("branch") {
        return Err(ApiError::new(
            StatusCode::UNPROCESSABLE_ENTITY,
            "invalid_transform_target",
            "replace_condition requires a branch condition node",
        ));
    }
    Ok(())
}

fn method_not_found() -> ApiError {
    ApiError::new(
        StatusCode::NOT_FOUND,
        "method_not_found",
        "method with an available CFG does not exist",
    )
}

fn parse_project_id(value: &str) -> Result<Uuid, ApiError> {
    Uuid::parse_str(value).map_err(|_| ApiError::bad_request("project_id must be a UUID"))
}

#[cfg(test)]
mod tests {
    use std::{path::PathBuf, time::Duration};

    use axum::{
        body::{to_bytes, Body},
        http::{header, Request, StatusCode},
    };
    use serde_json::Value;
    use sha2::{Digest, Sha256};
    use tokio::fs;
    use tower::ServiceExt;

    use super::{app, normalize_java_filename, AppState};
    use crate::Config;

    struct TestData(PathBuf);

    impl TestData {
        fn new() -> Self {
            Self(
                std::env::temp_dir().join(format!("java-blueprints-test-{}", uuid::Uuid::new_v4())),
            )
        }
    }

    impl Drop for TestData {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[cfg(unix)]
    const FAKE_TRANSFORM_JAVA: &str = r#"#!/bin/sh
mode=
IFS= read -r mode < "$2" || :
if [ "$#" -eq 9 ] && [ "$1" = "-jar" ] && [ "$3" = "transform" ] && [ "$4" = "--input" ] && [ "$6" = "--operation" ] && [ "$8" = "--output" ]; then
  case "$5:$7:$9" in
    /*:/*:/*) ;;
    *) exit 2 ;;
  esac
  case "$mode" in
    timeout)
      exec sleep 2
      ;;
    failure)
      printf %s '{"status":"failed","error":{"code":"internal_error","message":"fake worker failure"}}'
      exit 1
      ;;
    *)
      if grep -q '"base_revision"' "$7"; then
        printf %s '{"status":"failed","error":{"code":"invalid_operation","message":"base_revision leaked to worker"}}'
        exit 1
      fi
      if grep -q '"expression":"bad +"' "$7"; then
        printf %s '{"status":"failed","error":{"code":"invalid_expression","message":"invalid Java expression"}}'
        exit 1
      fi
      sed 's/count > 0/count >= 0/' "$5" > "$9"
      printf %s '{"status":"completed"}'
      ;;
  esac
elif [ "$#" -eq 3 ] && [ "$1" = "-jar" ]; then
  case "$3" in
    /*) ;;
    *) exit 2 ;;
  esac
  if [ "$mode" = "analysis-invalid" ]; then
    printf %s '{"protocol_version":1,"status":"partial","types":[],"methods":[],"diagnostics":[{"code":"test_error","severity":"error","message":"invalid candidate","source_range":{"start":0,"length":1,"line":1,"end_line":1}}]}'
  else
    printf %s '{"protocol_version":1,"status":"completed","types":[{"kind":"class","members":[{"kind":"method","id":"Example.process(int)","name":"process","source_range":{"start":16,"length":70,"line":1,"end_line":1},"cfg":{"method_id":"Example.process(int)","entry":"m0.n0","exit":"m0.n1","nodes":[{"id":"m0.n0","kind":"entry","source_range":{"start":16,"length":70,"line":1,"end_line":1}},{"id":"m0.n1","kind":"exit","source_range":{"start":16,"length":70,"line":1,"end_line":1}},{"id":"m0.n2","kind":"branch","statement_kind":"if","text":"count >= 0","source_range":{"start":52,"length":10,"line":1,"end_line":1}}],"edges":[{"from":"m0.n0","to":"m0.n2","kind":"next"},{"from":"m0.n2","to":"m0.n1","kind":"true"},{"from":"m0.n2","to":"m0.n1","kind":"false"}]}}]}],"methods":[{"id":"Example.process(int)","name":"process","source_range":{"start":16,"length":70,"line":1,"end_line":1}}],"diagnostics":[]}'
  fi
else
  exit 2
fi
"#;

    async fn test_app(data: &TestData) -> axum::Router {
        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data.0.clone(),
            max_project_bytes: 1024,
            secure_cookies: false,
            analyzer_jar: None,
            java_bin: "java".into(),
            analyzer_timeout: Duration::from_secs(1),
            analyzer_output_bytes: 1024,
        })
        .await
        .unwrap();
        app(state)
    }

    #[cfg(unix)]
    async fn test_app_with_worker(
        data: &TestData,
        mode: &str,
        analyzer_timeout: Duration,
    ) -> axum::Router {
        use std::os::unix::fs::PermissionsExt;

        fs::create_dir_all(&data.0).await.unwrap();
        let jar = data.0.join("parser.jar");
        fs::write(&jar, format!("{mode}\n")).await.unwrap();
        let java = data.0.join("fake-java");
        fs::write(&java, FAKE_TRANSFORM_JAVA).await.unwrap();
        let mut permissions = fs::metadata(&java).await.unwrap().permissions();
        permissions.set_mode(0o700);
        fs::set_permissions(&java, permissions).await.unwrap();

        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data.0.clone(),
            max_project_bytes: 4096,
            secure_cookies: false,
            analyzer_jar: Some(jar),
            java_bin: java,
            analyzer_timeout,
            analyzer_output_bytes: 16 * 1024,
        })
        .await
        .unwrap();
        app(state)
    }

    async fn test_app_with_real_worker(data: &TestData) -> axum::Router {
        let jar = std::env::var_os("BLUEPRINTS_REAL_JAR")
            .map(PathBuf::from)
            .expect("BLUEPRINTS_REAL_JAR must point to the built parser fat JAR");
        let java = std::env::var_os("BLUEPRINTS_REAL_JAVA")
            .map(PathBuf::from)
            .expect("BLUEPRINTS_REAL_JAVA must point to an OpenJDK 21 java executable");
        let state = AppState::new(Config {
            bind: "127.0.0.1:0".parse().unwrap(),
            data_dir: data.0.clone(),
            max_project_bytes: 4096,
            secure_cookies: false,
            analyzer_jar: Some(jar),
            java_bin: java,
            analyzer_timeout: Duration::from_secs(10),
            analyzer_output_bytes: 1024 * 1024,
        })
        .await
        .unwrap();
        app(state)
    }

    async fn guest_cookie(app: &axum::Router) -> String {
        let response = app
            .clone()
            .oneshot(
                Request::post("/api/v1/sessions/guest")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        response.headers()[header::SET_COOKIE]
            .to_str()
            .unwrap()
            .split(';')
            .next()
            .unwrap()
            .to_owned()
    }

    async fn upload(app: &axum::Router, cookie: &str, filename: &str) -> axum::response::Response {
        upload_source(app, cookie, filename, "class Main {}\n").await
    }

    async fn upload_source(
        app: &axum::Router,
        cookie: &str,
        filename: &str,
        source: &str,
    ) -> axum::response::Response {
        let boundary = "m1-test-boundary";
        let body = format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: text/plain\r\n\r\n{source}\r\n--{boundary}--\r\n"
        );
        app.clone()
            .oneshot(
                Request::post("/api/v1/projects")
                    .header(header::COOKIE, cookie)
                    .header(
                        header::CONTENT_TYPE,
                        format!("multipart/form-data; boundary={boundary}"),
                    )
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap()
    }

    async fn response_json(response: axum::response::Response) -> Value {
        let bytes = to_bytes(response.into_body(), 64 * 1024).await.unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    fn project_directory(data: &TestData, cookie: &str, project_id: &str) -> PathBuf {
        let token = cookie
            .strip_prefix("blueprints_session=")
            .expect("test cookie has expected name");
        let digest = Sha256::digest(token.as_bytes());
        let session_hash = digest
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        data.0
            .join("sessions")
            .join(session_hash)
            .join("projects")
            .join(project_id)
    }

    fn cfg_artifact(revision: u64) -> Value {
        serde_json::json!({
            "protocol_version": 1,
            "status": "completed",
            "revision": revision,
            "types": [{
                "kind": "class",
                "name": "Example",
                "members": [{
                    "kind": "constructor",
                    "id": "Example.Example()",
                    "name": "Example",
                    "source_range": {"start": 16, "length": 11, "line": 1, "end_line": 1},
                    "cfg": {
                        "method_id": "Example.Example()",
                        "entry": "m0.n0",
                        "exit": "m0.n1",
                        "nodes": [
                            {"id": "m0.n0", "kind": "entry", "source_range": {"start": 16, "length": 11, "line": 1, "end_line": 1}},
                            {"id": "m0.n1", "kind": "exit", "source_range": {"start": 16, "length": 11, "line": 1, "end_line": 1}}
                        ],
                        "edges": [{"from": "m0.n0", "to": "m0.n1", "kind": "next"}]
                    }
                }, {
                    "kind": "method",
                    "id": "Example.process(int)",
                    "name": "process",
                    "source_range": {"start": 29, "length": 67, "line": 2, "end_line": 4},
                    "body": [{"kind": "if", "condition": "count > 0"}],
                    "cfg": {
                        "method_id": "Example.process(int)",
                        "entry": "m1.n0",
                        "exit": "m1.n1",
                        "nodes": [
                            {"id": "m1.n0", "kind": "entry", "source_range": {"start": 29, "length": 67, "line": 2, "end_line": 4}},
                            {"id": "m1.n1", "kind": "exit", "source_range": {"start": 29, "length": 67, "line": 2, "end_line": 4}},
                            {"id": "m1.n2", "kind": "branch", "statement_kind": "if", "text": "count > 0", "source_range": {"start": 67, "length": 9, "line": 3, "end_line": 3}}
                        ],
                        "edges": [
                            {"from": "m1.n0", "to": "m1.n2", "kind": "next"},
                            {"from": "m1.n2", "to": "m1.n1", "kind": "true", "label": "kept-exactly"},
                            {"from": "m1.n2", "to": "m1.n1", "kind": "false"}
                        ]
                    }
                }]
            }],
            "methods": [
                {"id": "Example.Example()", "name": "Example", "source_range": {"start": 16, "length": 11, "line": 1, "end_line": 1}},
                {"id": "Example.process(int)", "name": "process", "source_range": {"start": 29, "length": 67, "line": 2, "end_line": 4}}
            ],
            "diagnostics": []
        })
    }

    async fn store_analysis(data: &TestData, cookie: &str, project_id: &str, artifact: &Value) {
        let path = project_directory(data, cookie, project_id)
            .join("analysis")
            .join(format!("{}.json", artifact["revision"].as_u64().unwrap()));
        fs::write(path, serde_json::to_vec(artifact).unwrap())
            .await
            .unwrap();
    }

    fn transform_request(
        cookie: &str,
        project_id: &str,
        base_revision: u64,
        method_id: &str,
        node_id: &str,
        expression: &str,
    ) -> Request<Body> {
        Request::post(format!("/api/v1/projects/{project_id}/transform"))
            .header(header::COOKIE, cookie)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(
                serde_json::json!({
                    "base_revision": base_revision,
                    "method_id": method_id,
                    "node_id": node_id,
                    "operation": {
                        "type": "replace_condition",
                        "expression": expression
                    }
                })
                .to_string(),
            ))
            .unwrap()
    }

    #[tokio::test]
    async fn project_is_private_to_guest_session() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let owner = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let response = upload(&app, &owner, "Main.java").await;
        assert_eq!(response.status(), StatusCode::CREATED);
        let project = response_json(response).await;

        let response = app
            .oneshot(
                Request::get(format!(
                    "/api/v1/projects/{}",
                    project["id"].as_str().unwrap()
                ))
                .header(header::COOKIE, other)
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn lists_nested_methods_and_returns_exact_cfg() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let artifact = cfg_artifact(1);
        store_analysis(&data, &cookie, project_id, &artifact).await;

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let methods = response_json(response).await;
        assert_eq!(methods["revision"], 1);
        assert_eq!(methods["methods"].as_array().unwrap().len(), 2);
        assert_eq!(methods["methods"][0]["kind"], "constructor");
        assert_eq!(methods["methods"][0]["selector"], "m0");
        assert_eq!(methods["methods"][1]["id"], "Example.process(int)");
        assert_eq!(methods["methods"][1]["selector"], "m1");
        assert_eq!(methods["methods"][1]["source_range"]["end_line"], 4);

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods/m1/cfg"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let cfg = response_json(response).await;
        assert_eq!(cfg, artifact["types"][0]["members"][1]["cfg"]);
        assert_eq!(cfg["nodes"][2]["id"], "m1.n2");
        assert_eq!(cfg["nodes"][2]["source_range"]["start"], 67);
        assert_eq!(cfg["nodes"][2]["source_range"]["length"], 9);
        assert_eq!(cfg["edges"][1]["label"], "kept-exactly");
    }

    #[tokio::test]
    async fn methods_reject_missing_analysis_method_and_cross_session_access() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let owner = guest_cookie(&app).await;
        let other = guest_cookie(&app).await;
        let project = response_json(upload(&app, &owner, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "analysis_not_found");

        store_analysis(&data, &owner, project_id, &cfg_artifact(1)).await;
        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods/m99/cfg"))
                    .header(header::COOKIE, &owner)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "method_not_found");

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &other)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "project_not_found");
    }

    #[tokio::test]
    async fn methods_reject_analysis_from_a_different_revision() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        let mut artifact = cfg_artifact(1);
        artifact["revision"] = 7.into();
        let path = project_directory(&data, &cookie, project_id)
            .join("analysis")
            .join("1.json");
        fs::write(path, serde_json::to_vec(&artifact).unwrap())
            .await
            .unwrap();

        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(
            response_json(response).await["code"],
            "analysis_revision_mismatch"
        );
    }

    #[tokio::test]
    async fn project_route_requires_a_session() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let response = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{}", uuid::Uuid::new_v4()))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn generated_openapi_documents_cfg_and_transform_routes() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let response = app
            .oneshot(
                Request::get("/api/v1/openapi.json")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let document = response_json(response).await;
        assert!(document["paths"]["/api/v1/projects/{project_id}/methods"]["get"].is_object());
        assert!(
            document["paths"]["/api/v1/projects/{project_id}/methods/{method_selector}/cfg"]["get"]
                .is_object()
        );
        assert!(document["paths"]["/api/v1/projects/{project_id}/transform"]["post"].is_object());
        assert!(document["components"]["schemas"]["TransformRequest"].is_object());
    }

    #[tokio::test]
    async fn upload_rejects_non_java_file() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let response = upload(&app, &cookie, "project.zip").await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn upload_preserves_java_basename() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;

        let project = response_json(upload(&app, &cookie, "Example.java").await).await;

        assert_eq!(project["filename"], "Example.java");
        assert_eq!(
            normalize_java_filename("C:\\fakepath\\Example.java").unwrap(),
            "Example.java"
        );
        assert_eq!(
            normalize_java_filename("../../Example.java").unwrap(),
            "Example.java"
        );
    }

    #[tokio::test]
    async fn source_update_rejects_stale_revision() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Main.java").await).await;
        let project_id = project["id"].as_str().unwrap();

        let update = |source: &str, revision: u64| {
            Request::put(format!("/api/v1/projects/{project_id}/source"))
                .header(header::COOKIE, &cookie)
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    serde_json::json!({
                        "source": source,
                        "expected_revision": revision
                    })
                    .to_string(),
                ))
                .unwrap()
        };
        let response = app
            .clone()
            .oneshot(update("class Main { int x; }", 1))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let response = app
            .oneshot(update("class Main { int y; }", 1))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(response_json(response).await["code"], "revision_conflict");
    }

    #[tokio::test]
    #[ignore = "requires a built parser JAR and OpenJDK 21 via BLUEPRINTS_REAL_JAR/BLUEPRINTS_REAL_JAVA"]
    async fn real_ast_rewrite_http_end_to_end() {
        const SOURCE: &str = "public class Example {\n    public static void main(String[] args) {\n        int count = 1;\n        if (count > 0) {\n            System.out.println(\"positive\");\n        }\n    }\n}\n";

        let data = TestData::new();
        let app = test_app_with_real_worker(&data).await;
        let cookie = guest_cookie(&app).await;
        let project =
            response_json(upload_source(&app, &cookie, "Example.java", SOURCE).await).await;
        let project_id = project["id"].as_str().unwrap();
        let directory = project_directory(&data, &cookie, project_id);

        let response = app
            .clone()
            .oneshot(
                Request::post(format!("/api/v1/projects/{project_id}/analyze"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let initial_analysis = response_json(response).await;
        assert_eq!(initial_analysis["status"], "completed");
        assert!(initial_analysis["diagnostics"]
            .as_array()
            .unwrap()
            .iter()
            .all(|diagnostic| !diagnostic["message"]
                .as_str()
                .unwrap_or_default()
                .contains("must be defined in its own file")));

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let methods = response_json(response).await;
        assert_eq!(methods["revision"], 1);
        assert_eq!(methods["methods"][0]["id"], "Example.main(String[])");
        let selector = methods["methods"][0]["selector"].as_str().unwrap();

        let response = app
            .clone()
            .oneshot(
                Request::get(format!(
                    "/api/v1/projects/{project_id}/methods/{selector}/cfg"
                ))
                .header(header::COOKIE, &cookie)
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let cfg = response_json(response).await;
        let branch = cfg["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|node| node["text"] == "count > 0")
            .unwrap();
        let node_id = branch["id"].as_str().unwrap().to_owned();
        assert_eq!(node_id, "m0.n3");
        assert_eq!(
            branch["source_range"]["start"],
            SOURCE[..SOURCE.find("count > 0").unwrap()]
                .encode_utf16()
                .count()
        );
        assert_eq!(
            branch["source_range"]["length"],
            "count > 0".encode_utf16().count()
        );

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.main(String[])",
                &node_id,
                "count >= 0",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let transformed = response_json(response).await;
        assert_eq!(transformed["revision"], 2);
        assert!(transformed["source"]
            .as_str()
            .unwrap()
            .contains("if (count >= 0)"));

        assert_eq!(
            fs::read_to_string(directory.join("revisions/1/Example.java"))
                .await
                .unwrap(),
            SOURCE
        );
        assert!(
            fs::read_to_string(directory.join("revisions/2/Example.java"))
                .await
                .unwrap()
                .contains("if (count >= 0)")
        );
        assert!(!directory.join("revisions/2/Out.java").exists());

        let response = app
            .clone()
            .oneshot(
                Request::post(format!("/api/v1/projects/{project_id}/analyze"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let updated_analysis = response_json(response).await;
        assert_eq!(updated_analysis["revision"], 2);
        assert_eq!(updated_analysis["status"], "completed");
        assert!(updated_analysis["diagnostics"]
            .as_array()
            .unwrap()
            .iter()
            .all(|diagnostic| !diagnostic["message"]
                .as_str()
                .unwrap_or_default()
                .contains("must be defined in its own file")));

        let response = app
            .clone()
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}/methods"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let methods = response_json(response).await;
        assert_eq!(methods["revision"], 2);
        let selector = methods["methods"][0]["selector"].as_str().unwrap();
        let response = app
            .clone()
            .oneshot(
                Request::get(format!(
                    "/api/v1/projects/{project_id}/methods/{selector}/cfg"
                ))
                .header(header::COOKIE, &cookie)
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        let updated_cfg = response_json(response).await;
        let updated_branch = updated_cfg["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .find(|node| node["text"] == "count >= 0")
            .unwrap();
        assert_eq!(updated_branch["id"], "m0.n3");
        assert_eq!(updated_branch["source_range"]["start"], 103);
        assert_eq!(updated_branch["source_range"]["length"], 10);

        let stale = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.main(String[])",
                "m0.n3",
                "count == 0",
            ))
            .await
            .unwrap();
        assert_eq!(stale.status(), StatusCode::CONFLICT);

        let unknown = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                2,
                "Example.main(String[])",
                "m0.n99",
                "count == 0",
            ))
            .await
            .unwrap();
        assert_eq!(unknown.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(unknown).await["code"], "node_not_found");

        let invalid = app
            .oneshot(transform_request(
                &cookie,
                project_id,
                2,
                "Example.main(String[])",
                "m0.n3",
                "count +",
            ))
            .await
            .unwrap();
        assert_eq!(invalid.status(), StatusCode::UNPROCESSABLE_ENTITY);
        let invalid = response_json(invalid).await;
        assert_eq!(invalid["code"], "transformation_failed");
        assert_eq!(
            invalid["message"],
            "Replacement expression contains a syntax error"
        );
        let metadata: Value =
            serde_json::from_slice(&fs::read(directory.join("project.json")).await.unwrap())
                .unwrap();
        assert_eq!(metadata["revision"], 2);
        assert!(!directory.join("revisions/3/Example.java").exists());
        assert!(std::fs::read_dir(&directory).unwrap().all(|entry| !entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".transform-")));
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn transform_commits_validated_source_and_analysis_as_a_new_revision() {
        let data = TestData::new();
        let app = test_app_with_worker(&data, "success", Duration::from_secs(1)).await;
        let cookie = guest_cookie(&app).await;
        let source =
            "class Example { int process(int count) { if (count > 0) return 1; return 0; } }";
        let project =
            response_json(upload_source(&app, &cookie, "Example.java", source).await).await;
        let project_id = project["id"].as_str().unwrap();
        store_analysis(&data, &cookie, project_id, &cfg_artifact(1)).await;

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n2",
                "count >= 0",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let transformed = response_json(response).await;
        assert_eq!(transformed["revision"], 2);
        assert!(transformed["source"]
            .as_str()
            .unwrap()
            .contains("count >= 0"));

        let directory = project_directory(&data, &cookie, project_id);
        let stored_source = fs::read_to_string(directory.join("revisions/2/Example.java"))
            .await
            .unwrap();
        assert!(stored_source.contains("count >= 0"));
        let stored_analysis: Value =
            serde_json::from_slice(&fs::read(directory.join("analysis/2.json")).await.unwrap())
                .unwrap();
        assert_eq!(stored_analysis["revision"], 2);
        assert_eq!(
            stored_analysis["types"][0]["members"][0]["cfg"]["entry"],
            "m0.n0"
        );
        assert!(std::fs::read_dir(&directory).unwrap().all(|entry| !entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".transform-")));

        let stale = app
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n2",
                "count == 0",
            ))
            .await
            .unwrap();
        assert_eq!(stale.status(), StatusCode::CONFLICT);
        assert_eq!(response_json(stale).await["code"], "revision_conflict");
    }

    #[tokio::test]
    async fn transform_rejects_unknown_method_and_node_before_worker_invocation() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        store_analysis(&data, &cookie, project_id, &cfg_artifact(1)).await;

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.missing()",
                "m1.n2",
                "true",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "method_not_found");

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n99",
                "true",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response_json(response).await["code"], "node_not_found");

        let response = app
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n0",
                "true",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(
            response_json(response).await["code"],
            "invalid_transform_target"
        );
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn transform_rejects_invalid_expression_and_preserves_revision() {
        let data = TestData::new();
        let app = test_app_with_worker(&data, "success", Duration::from_secs(1)).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap();
        store_analysis(&data, &cookie, project_id, &cfg_artifact(1)).await;

        let response = app
            .clone()
            .oneshot(transform_request(
                &cookie,
                project_id,
                1,
                "Example.process(int)",
                "m1.n2",
                "bad +",
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
        let error = response_json(response).await;
        assert_eq!(error["code"], "transformation_failed");
        assert_eq!(error["message"], "invalid Java expression");

        let metadata = app
            .oneshot(
                Request::get(format!("/api/v1/projects/{project_id}"))
                    .header(header::COOKIE, &cookie)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response_json(metadata).await["revision"], 1);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn transform_rejects_worker_failure_timeout_and_invalid_candidate() {
        for (mode, timeout, expected_status, expected_code) in [
            (
                "failure",
                Duration::from_secs(1),
                StatusCode::UNPROCESSABLE_ENTITY,
                "transformation_failed",
            ),
            (
                "timeout",
                Duration::from_millis(30),
                StatusCode::GATEWAY_TIMEOUT,
                "transform_timeout",
            ),
            (
                "analysis-invalid",
                Duration::from_secs(1),
                StatusCode::UNPROCESSABLE_ENTITY,
                "transformation_invalid",
            ),
        ] {
            let data = TestData::new();
            let app = test_app_with_worker(&data, mode, timeout).await;
            let cookie = guest_cookie(&app).await;
            let project = response_json(upload(&app, &cookie, "Example.java").await).await;
            let project_id = project["id"].as_str().unwrap();
            store_analysis(&data, &cookie, project_id, &cfg_artifact(1)).await;

            let response = app
                .oneshot(transform_request(
                    &cookie,
                    project_id,
                    1,
                    "Example.process(int)",
                    "m1.n2",
                    "count >= 0",
                ))
                .await
                .unwrap();
            assert_eq!(response.status(), expected_status, "mode {mode}");
            assert_eq!(response_json(response).await["code"], expected_code);
            assert!(!project_directory(&data, &cookie, project_id)
                .join("revisions/2/Example.java")
                .exists());
        }
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn concurrent_transforms_cannot_overwrite_each_other() {
        let data = TestData::new();
        let app = test_app_with_worker(&data, "success", Duration::from_secs(1)).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Example.java").await).await;
        let project_id = project["id"].as_str().unwrap().to_owned();
        store_analysis(&data, &cookie, &project_id, &cfg_artifact(1)).await;

        let first = app.clone().oneshot(transform_request(
            &cookie,
            &project_id,
            1,
            "Example.process(int)",
            "m1.n2",
            "count >= 0",
        ));
        let second = app.clone().oneshot(transform_request(
            &cookie,
            &project_id,
            1,
            "Example.process(int)",
            "m1.n2",
            "count <= 0",
        ));
        let (first, second) = tokio::join!(first, second);
        let statuses = [first.unwrap().status(), second.unwrap().status()];
        assert_eq!(
            statuses
                .iter()
                .filter(|status| **status == StatusCode::OK)
                .count(),
            1
        );
        assert_eq!(
            statuses
                .iter()
                .filter(|status| **status == StatusCode::CONFLICT)
                .count(),
            1
        );
        let metadata: Value = serde_json::from_slice(
            &fs::read(project_directory(&data, &cookie, &project_id).join("project.json"))
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(metadata["revision"], 2);
        assert!(!project_directory(&data, &cookie, &project_id)
            .join("revisions/3/Example.java")
            .exists());
    }

    #[tokio::test]
    async fn analyze_reports_unavailable_worker() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let project = response_json(upload(&app, &cookie, "Main.java").await).await;
        let response = app
            .oneshot(
                Request::post(format!(
                    "/api/v1/projects/{}/analyze",
                    project["id"].as_str().unwrap()
                ))
                .header(header::COOKIE, cookie)
                .body(Body::empty())
                .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(
            response_json(response).await["code"],
            "analyzer_unavailable"
        );
    }
}
