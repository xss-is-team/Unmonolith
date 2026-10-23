use std::path::{Path, PathBuf};

use axum::http::{header, HeaderMap};
use rand::{rngs::OsRng, RngCore};
use sha2::{Digest, Sha256};
use tokio::fs;

use crate::error::ApiError;

pub const COOKIE_NAME: &str = "blueprints_session";

pub struct NewSession {
    pub cookie: String,
}

pub async fn create(data_dir: &Path, secure_cookie: bool) -> Result<NewSession, ApiError> {
    let mut bytes = [0_u8; 32];
    OsRng.fill_bytes(&mut bytes);
    let token = hex(&bytes);
    let session_dir = session_dir(data_dir, &token);
    fs::create_dir_all(session_dir.join("projects")).await?;

    let secure = if secure_cookie { "; Secure" } else { "" };
    Ok(NewSession {
        cookie: format!("{COOKIE_NAME}={token}; Path=/api/v1; HttpOnly; SameSite=Lax{secure}"),
    })
}

pub async fn authenticate(data_dir: &Path, headers: &HeaderMap) -> Result<PathBuf, ApiError> {
    let token = cookie_value(headers, COOKIE_NAME).ok_or_else(ApiError::unauthorized)?;
    if token.len() != 64 || !token.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(ApiError::unauthorized());
    }
    let directory = session_dir(data_dir, token);
    match fs::metadata(&directory).await {
        Ok(metadata) if metadata.is_dir() => Ok(directory),
        Ok(_) | Err(_) => Err(ApiError::unauthorized()),
    }
}

fn cookie_value<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers
        .get(header::COOKIE)?
        .to_str()
        .ok()?
        .split(';')
        .map(str::trim)
        .find_map(|cookie| cookie.strip_prefix(name)?.strip_prefix('='))
}

fn session_dir(data_dir: &Path, token: &str) -> PathBuf {
    let digest = Sha256::digest(token.as_bytes());
    data_dir.join("sessions").join(hex(&digest))
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write;

    let mut result = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        write!(&mut result, "{byte:02x}").expect("writing to a string cannot fail");
    }
    result
}
