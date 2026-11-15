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
    #[serde(default)]
    version: Option<RuntimeVersion>,
}

#[derive(Debug, Deserialize)]
struct RuntimeVersion {
    name: String,
}

/// Major Java version from a runtime version name ("21.0.7" → 21, "8u51-…" → 8).
pub fn major_of(name: &str) -> Option<u32> {
    let digits: String = name.chars().take_while(|c| c.is_ascii_digit()).collect();
    digits.parse().ok()
}

/// Lowest Java major version accepted by a Fabric `java` dependency (">=22", ">21", "[22,)", "22").
pub fn min_java(range: &str) -> Option<u32> {
    range
        .split(|c: char| c.is_whitespace() || c == ',' || c == '|')
        .filter(|t| !t.starts_with('<')) // upper bounds do not raise the minimum
        .find_map(|t| {
            let strict = t.starts_with('>') && !t.starts_with(">=");
            let n: u32 = t.trim_start_matches(['>', '=', '~', '^', '[']).split('.').next()?.parse().ok()?;
            Some(if strict { n + 1 } else { n })
        })
}

/// Runtime component to use: the version's own one if it is new enough, otherwise the stable
/// component with the smallest major version that satisfies `min_major`.
pub fn pick_component(available: &[(String, u32)], current: &str, min_major: u32) -> Option<String> {
    let current_major = available.iter().find(|(n, _)| n == current).map(|(_, m)| *m);
    if current_major.is_some_and(|m| m >= min_major) {
        return Some(current.to_string());
    }
    available
        .iter()
        .filter(|(n, m)| *m >= min_major && !n.contains("snapshot") && n.starts_with("java-runtime"))
        .min_by_key(|(_, m)| *m)
        .map(|(n, _)| n.clone())
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

/// Ensures a verified Mojang Java runtime is installed: `component` (from the version JSON) or, if a
/// mod needs a newer Java than that (`min_major`), the smallest newer one. Returns (java path, component).
pub async fn ensure_runtime<F: Fn(usize, usize)>(
    client: &reqwest::Client,
    runtimes_dir: &Path,
    component: &str,
    min_major: u32,
    progress: F,
) -> Result<(PathBuf, String)> {
    let index: HashMap<String, HashMap<String, Vec<RuntimeEntry>>> = get_json(client, RUNTIMES_INDEX, None).await?;
    let platform = index
        .get(platform_key())
        .ok_or_else(|| Error::msg(format!("Mojang publishes no Java runtimes for {}", platform_key())))?;
    let available: Vec<(String, u32)> = platform
        .iter()
        .filter_map(|(name, entries)| {
            let major = entries.first()?.version.as_ref().and_then(|v| major_of(&v.name))?;
            Some((name.clone(), major))
        })
        .collect();
    let component = pick_component(&available, component, min_major)
        .ok_or_else(|| Error::msg(format!("No Mojang Java runtime satisfies Java {min_major} on {}", platform_key())))?;
    let component = component.as_str();
    let entry = platform
        .get(component)
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
                    downloads.push(Download { url: d.raw.url.clone(), path: path.clone(), sha1: Some(d.raw.sha1.clone()), size: Some(d.raw.size), ..Default::default() });
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
    Ok((java, component.to_string()))
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
    fn java_ranges() {
        assert_eq!(min_java(">=22"), Some(22));
        assert_eq!(min_java(">21"), Some(22));
        assert_eq!(min_java(">=17 <=21"), Some(17));
        assert_eq!(min_java("[22,)"), Some(22));
        assert_eq!(min_java("21"), Some(21));
        assert_eq!(min_java("*"), None);
        assert_eq!(major_of("21.0.7"), Some(21));
        assert_eq!(major_of("8u51-cacert462b08"), Some(8));
    }

    #[test]
    fn picks_smallest_sufficient_runtime() {
        let avail: Vec<(String, u32)> = [
            ("java-runtime-gamma", 17), ("java-runtime-delta", 21), ("java-runtime-epsilon", 25),
            ("java-runtime-gamma-snapshot", 17), ("jre-legacy", 8), ("minecraft-java-exe", 14),
        ].iter().map(|(n, m)| (n.to_string(), *m)).collect();
        assert_eq!(pick_component(&avail, "java-runtime-delta", 21).as_deref(), Some("java-runtime-delta"));
        assert_eq!(pick_component(&avail, "java-runtime-delta", 22).as_deref(), Some("java-runtime-epsilon"));
        assert_eq!(pick_component(&avail, "java-runtime-gamma", 18).as_deref(), Some("java-runtime-delta"));
        assert_eq!(pick_component(&avail, "java-runtime-delta", 30), None);
    }

    #[test]
    fn java_path_layout() {
        let p = java_executable(Path::new("rt"));
        assert!(p.to_string_lossy().contains("bin"));
    }
}
