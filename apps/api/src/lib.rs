mod analyzer;
mod api;
mod archive;
mod benchmark;
mod diff;
mod error;
mod project;
mod runner;
mod session;

use std::{env, io, net::SocketAddr, path::PathBuf, str::FromStr, time::Duration};

#[cfg(all(test, unix))]
pub(crate) mod test_util {
    use std::{
        io::Write,
        os::unix::fs::OpenOptionsExt,
        path::Path,
        process::Command,
        time::{Duration, Instant},
    };

    /// Writes an executable stub and returns only once it can really be executed.
    ///
    /// Tests run in parallel inside one process. A `fork` by another test between our `open(O_WRONLY)`
    /// and `close` lets the child inherit the write descriptor, and `exec` of the stub then fails with
    /// ETXTBSY ("text file busy") until that child has exec'ed. Probing until `exec` stops failing
    /// with ETXTBSY removes the race without touching production code.
    pub(crate) fn install_executable(path: &Path, content: &str) {
        {
            let mut file = std::fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o700)
                .open(path)
                .expect("create stub");
            file.write_all(content.as_bytes()).expect("write stub");
        }
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            match Command::new(path).output() {
                Err(error) if error.raw_os_error() == Some(26) && Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(5));
                }
                _ => return,
            }
        }
    }
}

pub use api::{app, AppState};
pub use archive::ImportLimits;

#[derive(Clone, Debug)]
pub struct Config {
    pub bind: SocketAddr,
    pub data_dir: PathBuf,
    /// Per-file size limit (also the single-file upload limit).
    pub max_project_bytes: usize,
    pub import_limits: ImportLimits,
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
    pub runner_benchmark_timeout: Duration,
}

impl Config {
    pub fn from_env() -> io::Result<Self> {
        Ok(Self {
            bind: parse_env("BLUEPRINTS_BIND", "127.0.0.1:3000")?,
            data_dir: PathBuf::from(
                env::var("BLUEPRINTS_DATA_DIR").unwrap_or_else(|_| "./data".into()),
            ),
            max_project_bytes: parse_env("BLUEPRINTS_MAX_PROJECT_BYTES", "2097152")?,
            import_limits: import_limits_from_env()?,
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
            runner_benchmark_timeout: Duration::from_millis(parse_env(
                "BLUEPRINTS_RUNNER_BENCHMARK_TIMEOUT_MS",
                "120000",
            )?),
        })
    }
}

fn import_limits_from_env() -> io::Result<ImportLimits> {
    let defaults = ImportLimits::default();
    let max_file_bytes = parse_env("BLUEPRINTS_MAX_PROJECT_BYTES", "2097152")?;
    Ok(ImportLimits {
        max_archive_bytes: parse_env(
            "BLUEPRINTS_MAX_ARCHIVE_BYTES",
            &defaults.max_archive_bytes.to_string(),
        )?,
        max_entries: parse_env(
            "BLUEPRINTS_MAX_ARCHIVE_ENTRIES",
            &defaults.max_entries.to_string(),
        )?,
        max_files: parse_env(
            "BLUEPRINTS_MAX_ARCHIVE_FILES",
            &defaults.max_files.to_string(),
        )?,
        max_file_bytes,
        max_total_bytes: parse_env(
            "BLUEPRINTS_MAX_ARCHIVE_TOTAL_BYTES",
            &defaults.max_total_bytes.to_string(),
        )?,
        max_compression_ratio: parse_env(
            "BLUEPRINTS_MAX_ARCHIVE_RATIO",
            &defaults.max_compression_ratio.to_string(),
        )?,
        ..defaults
    })
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
