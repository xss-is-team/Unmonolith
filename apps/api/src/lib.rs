mod analyzer;
mod api;
mod error;
mod project;
mod runner;
mod session;

use std::{env, io, net::SocketAddr, path::PathBuf, str::FromStr, time::Duration};

pub use api::{app, AppState};

#[derive(Clone, Debug)]
pub struct Config {
    pub bind: SocketAddr,
    pub data_dir: PathBuf,
    pub max_project_bytes: usize,
    pub secure_cookies: bool,
    pub analyzer_jar: Option<PathBuf>,
    pub java_bin: PathBuf,
    pub analyzer_timeout: Duration,
    pub analyzer_output_bytes: u64,
    pub runner_image: Option<String>,
    pub container_bin: PathBuf,
    pub runner_compile_timeout: Duration,
    pub runner_case_timeout: Duration,
    pub runner_output_bytes: u64,
}

impl Config {
    pub fn from_env() -> io::Result<Self> {
        Ok(Self {
            bind: parse_env("BLUEPRINTS_BIND", "127.0.0.1:3000")?,
            data_dir: PathBuf::from(
                env::var("BLUEPRINTS_DATA_DIR").unwrap_or_else(|_| "./data".into()),
            ),
            max_project_bytes: parse_env("BLUEPRINTS_MAX_PROJECT_BYTES", "2097152")?,
            secure_cookies: parse_env("BLUEPRINTS_SECURE_COOKIES", "false")?,
            analyzer_jar: env::var_os("BLUEPRINTS_ANALYZER_JAR").map(PathBuf::from),
            java_bin: PathBuf::from(
                env::var("BLUEPRINTS_JAVA_BIN").unwrap_or_else(|_| "java".into()),
            ),
            analyzer_timeout: Duration::from_millis(parse_env(
                "BLUEPRINTS_ANALYZER_TIMEOUT_MS",
                "10000",
            )?),
            analyzer_output_bytes: parse_env("BLUEPRINTS_ANALYZER_OUTPUT_BYTES", "2097152")?,
            runner_image: env::var("BLUEPRINTS_RUNNER_IMAGE")
                .ok()
                .filter(|image| !image.trim().is_empty()),
            container_bin: PathBuf::from(
                env::var("BLUEPRINTS_CONTAINER_BIN").unwrap_or_else(|_| "docker".into()),
            ),
            runner_compile_timeout: Duration::from_millis(parse_env(
                "BLUEPRINTS_RUNNER_COMPILE_TIMEOUT_MS",
                "10000",
            )?),
            runner_case_timeout: Duration::from_millis(parse_env(
                "BLUEPRINTS_RUNNER_CASE_TIMEOUT_MS",
                "2000",
            )?),
            runner_output_bytes: parse_env("BLUEPRINTS_RUNNER_OUTPUT_BYTES", "65536")?,
        })
    }
}

fn parse_env<T>(name: &str, default: &str) -> io::Result<T>
where
    T: FromStr,
    T::Err: std::fmt::Display,
{
    env::var(name)
        .unwrap_or_else(|_| default.to_owned())
        .parse()
        .map_err(|error| {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("invalid {name}: {error}"),
            )
        })
}
