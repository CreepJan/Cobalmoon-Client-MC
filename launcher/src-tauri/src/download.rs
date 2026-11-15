//! Verified downloads. Every file the launcher fetches is checked against a SHA-1 (Mojang/Fabric
//! metadata) before it is moved into place, so a corrupted or tampered file is never used.

use crate::error::{Error, Result};
use futures_util::{stream, StreamExt};
use sha1::{Digest, Sha1};
use std::path::{Path, PathBuf};
use tokio::io::AsyncWriteExt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Download {
    pub url: String,
    pub path: PathBuf,
    /// Lowercase hex SHA-1. `None` only for files whose integrity is checked elsewhere.
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

pub fn http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent(concat!("CobalmoonLauncher/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(std::time::Duration::from_secs(15))
        .build()
        .expect("http client")
}

pub fn sha1_hex(bytes: &[u8]) -> String {
    hex::encode(Sha1::digest(bytes))
}

pub async fn sha1_file(path: &Path) -> Result<String> {
    let bytes = tokio::fs::read(path).await?;
    Ok(sha1_hex(&bytes))
}

/// True when the file exists and matches the expected hash (or size, when no hash is known).
pub async fn is_valid(d: &Download) -> bool {
    let Ok(meta) = tokio::fs::metadata(&d.path).await else { return false };
    if let Some(size) = d.size {
        if meta.len() != size {
            return false;
        }
    }
    match &d.sha1 {
        Some(expected) => sha1_file(&d.path).await.map(|h| h.eq_ignore_ascii_case(expected)).unwrap_or(false),
        None => true,
    }
}

/// Downloads one file (with up to 3 attempts) unless a valid copy already exists.
pub async fn fetch(client: &reqwest::Client, d: &Download) -> Result<()> {
    if is_valid(d).await {
        return Ok(());
    }
    let mut last_err = None;
    for attempt in 0..3 {
        if attempt > 0 {
            tokio::time::sleep(std::time::Duration::from_millis(400 * attempt)).await;
        }
        match fetch_once(client, d).await {
            Ok(()) => return Ok(()),
            Err(e @ Error::Hash { .. }) => return Err(e),
            Err(e) => last_err = Some(e),
        }
    }
    Err(last_err.unwrap_or_else(|| Error::msg("download failed")))
}

async fn fetch_once(client: &reqwest::Client, d: &Download) -> Result<()> {
    if let Some(parent) = d.path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let tmp = d.path.with_extension("part");
    let resp = client.get(&d.url).send().await?.error_for_status()?;
    let mut hasher = Sha1::new();
    let mut file = tokio::fs::File::create(&tmp).await?;
    let mut body = resp.bytes_stream();
    while let Some(chunk) = body.next().await {
        let chunk = chunk?;
        hasher.update(&chunk);
        file.write_all(&chunk).await?;
    }
    file.flush().await?;
    drop(file);
    let actual = hex::encode(hasher.finalize());
    if let Some(expected) = &d.sha1 {
        if !actual.eq_ignore_ascii_case(expected) {
            let _ = tokio::fs::remove_file(&tmp).await;
            return Err(Error::Hash { path: d.path.display().to_string(), expected: expected.clone(), actual });
        }
    }
    tokio::fs::rename(&tmp, &d.path).await?;
    Ok(())
}

/// Downloads many files concurrently. `progress(done, total)` is called after each file.
pub async fn fetch_all<F>(client: &reqwest::Client, items: Vec<Download>, concurrency: usize, progress: F) -> Result<()>
where
    F: Fn(usize, usize),
{
    let total = items.len();
    let mut done = 0usize;
    progress(0, total);
    let mut results = stream::iter(items.into_iter().map(|d| async move { fetch(client, &d).await }))
        .buffer_unordered(concurrency.max(1));
    while let Some(r) = results.next().await {
        r?;
        done += 1;
        progress(done, total);
    }
    Ok(())
}

/// Fetches a JSON document, verifying its SHA-1 when the caller knows it.
pub async fn get_json<T: serde::de::DeserializeOwned>(client: &reqwest::Client, url: &str, sha1: Option<&str>) -> Result<T> {
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?;
    if let Some(expected) = sha1 {
        let actual = sha1_hex(&bytes);
        if !actual.eq_ignore_ascii_case(expected) {
            return Err(Error::Hash { path: url.to_string(), expected: expected.to_string(), actual });
        }
    }
    Ok(serde_json::from_slice(&bytes)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha1_of_known_input() {
        assert_eq!(sha1_hex(b"abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    #[tokio::test]
    async fn is_valid_checks_hash_and_size() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("f.bin");
        tokio::fs::write(&path, b"abc").await.unwrap();
        let mut d = Download { url: String::new(), path, sha1: Some("a9993e364706816aba3e25717850c26c9cd0d89d".into()), size: Some(3) };
        assert!(is_valid(&d).await);
        d.size = Some(4);
        assert!(!is_valid(&d).await);
        d.size = None;
        d.sha1 = Some("0000000000000000000000000000000000000000".into());
        assert!(!is_valid(&d).await);
    }
}
