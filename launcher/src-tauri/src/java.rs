//! Java runtimes from Mojang's official runtime manifest (the same builds the vanilla launcher uses).

use crate::download::{fetch_all, get_json, Download};
use crate::error::{Error, Result};
use serde::Deserialize;
use std::collections::HashMap;
use std::path::{Path, PathBuf};

pub const RUNTIMES_INDEX: &str =
    "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

#[derive(Debug, Deserialize)]
struct RuntimeEntry {
    manifest: ManifestRef,
}

#[derive(Debug, Deserialize)]
struct ManifestRef {
    sha1: String,
    url: String,
}

#[derive(Debug, Deserialize)]
struct RuntimeManifest {
    files: HashMap<String, RuntimeFile>,
}

#[derive(Debug, Deserialize)]
struct RuntimeFile {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    executable: bool,
    #[serde(default)]
    downloads: Option<RuntimeDownloads>,
}

#[derive(Debug, Deserialize)]
struct RuntimeDownloads {
    raw: RawDownload,
}

#[derive(Debug, Deserialize)]
struct RawDownload {
    sha1: String,
    size: u64,
    url: String,
}

/// Mojang's platform key for this machine.
pub fn platform_key() -> &'static str {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("windows", "aarch64") => "windows-arm64",
        ("windows", "x86") => "windows-x86",
        ("windows", _) => "windows-x64",
        ("macos", "aarch64") => "mac-os-arm64",
        ("macos", _) => "mac-os",
        ("linux", "x86") => "linux-i386",
        _ => "linux",
    }
}

/// Path of the java executable inside an extracted runtime.
pub fn java_executable(runtime_dir: &Path) -> PathBuf {
    if cfg!(target_os = "windows") {
        runtime_dir.join("bin").join("java.exe")
    } else if cfg!(target_os = "macos") {
        runtime_dir.join("jre.bundle").join("Contents").join("Home").join("bin").join("java")
    } else {
        runtime_dir.join("bin").join("java")
    }
}

/// Ensures the runtime `component` (e.g. "java-runtime-delta") is installed and verified; returns the java path.
pub async fn ensure_runtime<F: Fn(usize, usize)>(
    client: &reqwest::Client,
    runtimes_dir: &Path,
    component: &str,
    progress: F,
) -> Result<PathBuf> {
    let index: HashMap<String, HashMap<String, Vec<RuntimeEntry>>> = get_json(client, RUNTIMES_INDEX, None).await?;
    let entry = index
        .get(platform_key())
        .and_then(|p| p.get(component))
        .and_then(|v| v.first())
        .ok_or_else(|| Error::msg(format!("Mojang does not publish Java runtime '{component}' for {}", platform_key())))?;
    let manifest: RuntimeManifest = get_json(client, &entry.manifest.url, Some(&entry.manifest.sha1)).await?;

    let dir = runtimes_dir.join(component);
    let mut downloads = Vec::new();
    let mut executables = Vec::new();
    for (rel, f) in &manifest.files {
        let path = dir.join(rel);
        match f.kind.as_str() {
            "directory" => tokio::fs::create_dir_all(&path).await?,
            "file" => {
                if let Some(d) = &f.downloads {
                    downloads.push(Download { url: d.raw.url.clone(), path: path.clone(), sha1: Some(d.raw.sha1.clone()), size: Some(d.raw.size) });
                    if f.executable {
                        executables.push(path);
                    }
                }
            }
            _ => {} // symlinks (macOS) are not needed to run java
        }
    }
    fetch_all(client, downloads, 16, progress).await?;

    #[cfg(unix)]
    for p in executables {
        use std::os::unix::fs::PermissionsExt;
        tokio::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o755)).await?;
    }
    #[cfg(not(unix))]
    let _ = executables;

    let java = java_executable(&dir);
    if !java.exists() {
        return Err(Error::msg(format!("Java runtime installed but {} is missing", java.display())));
    }
    Ok(java)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn platform_key_is_known() {
        let known = ["windows-x64", "windows-arm64", "windows-x86", "mac-os", "mac-os-arm64", "linux", "linux-i386"];
        assert!(known.contains(&platform_key()));
    }

    #[test]
    fn java_path_layout() {
        let p = java_executable(Path::new("rt"));
        assert!(p.to_string_lossy().contains("bin"));
    }
}
