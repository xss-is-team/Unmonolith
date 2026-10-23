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
use tokio::{fs, sync::Mutex};
use utoipa::{Modify, OpenApi};
use utoipa_swagger_ui::SwaggerUi;
use uuid::Uuid;

use crate::{
    analyzer::{
        AnalysisResult, AnalysisStatus, Diagnostic, DiagnosticSeverity, JavaAnalyzer, Method,
        SourceRange,
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
        analyze_project
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
        SourceRange
    )),
    modifiers(&SecurityAddon),
    tags(
        (name = "System"),
        (name = "Sessions"),
        (name = "Projects"),
        (name = "Analysis")
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
        if !filename.to_ascii_lowercase().ends_with(".java") {
            return Err(ApiError::bad_request("only one .java file is supported"));
        }

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
        source = Some(bytes);
    }
    let source = source.ok_or_else(|| ApiError::bad_request("missing file field"))?;
    let _guard = state.0.writes.lock().await;
    let project = project::create(&session_dir, &source).await?;
    Ok((StatusCode::CREATED, Json(project)))
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
    let source = project::read_source(&directory, project.revision).await?;
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
            .analyze(directory, project.revision)
            .await?,
    ))
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
    use tower::ServiceExt;

    use super::{app, AppState};
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
        let boundary = "m1-test-boundary";
        let body = format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: text/plain\r\n\r\nclass Main {{}}\n\r\n--{boundary}--\r\n"
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
    async fn upload_rejects_non_java_file() {
        let data = TestData::new();
        let app = test_app(&data).await;
        let cookie = guest_cookie(&app).await;
        let response = upload(&app, &cookie, "project.zip").await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
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
