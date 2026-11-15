//! Mojang version metadata: manifest, version JSON, libraries and assets.
//! Everything is fetched from Mojang's official endpoints at run time; nothing of Mojang's is redistributed.

use crate::download::{get_json, Download};
use crate::error::{Error, Result};
use crate::rules::{allowed, Env, Rule};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::path::Path;

pub const VERSION_MANIFEST: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
pub const RESOURCES_BASE: &str = "https://resources.download.minecraft.net";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionManifest {
    pub latest: Latest,
    pub versions: Vec<ManifestEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Latest {
    pub release: String,
    pub snapshot: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ManifestEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub url: String,
    pub sha1: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct VersionJson {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub inherits_from: Option<String>,
    #[serde(default)]
    pub main_class: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arguments: Option<Arguments>,
    /// Pre-1.13 format; kept so old instances still launch.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub minecraft_arguments: Option<String>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub asset_index: Option<AssetIndexRef>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub assets: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub downloads: Option<VersionDownloads>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub java_version: Option<JavaVersion>,
    #[serde(default, rename = "type", skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Argument>,
    #[serde(default)]
    pub jvm: Vec<Argument>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(untagged)]
pub enum Argument {
    Plain(String),
    Ruled { rules: Vec<Rule>, value: ArgValue },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(untagged)]
pub enum ArgValue {
    One(String),
    Many(Vec<String>),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Library {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub downloads: Option<LibraryDownloads>,
    /// Maven repository base (Fabric-style libraries).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha1: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size: Option<u64>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub rules: Vec<Rule>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct LibraryDownloads {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub artifact: Option<Artifact>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Artifact {
    #[serde(default)]
    pub path: Option<String>,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndexRef {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct VersionDownloads {
    pub client: Artifact,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersion {
    pub component: String,
    pub major_version: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssetIndex {
    pub objects: HashMap<String, AssetObject>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
}

pub async fn fetch_manifest(client: &reqwest::Client) -> Result<VersionManifest> {
    get_json(client, VERSION_MANIFEST, None).await
}

pub async fn fetch_version(client: &reqwest::Client, manifest: &VersionManifest, id: &str) -> Result<VersionJson> {
    let entry = manifest
        .versions
        .iter()
        .find(|v| v.id == id)
        .ok_or_else(|| Error::msg(format!("Minecraft version {id} not found in Mojang's manifest")))?;
    get_json(client, &entry.url, Some(&entry.sha1)).await
}

/// "group:artifact:version[:classifier][@ext]" -> "group/path/artifact/version/artifact-version[-classifier].ext"
pub fn maven_path(name: &str) -> Result<String> {
    let (coords, ext) = match name.split_once('@') {
        Some((c, e)) => (c, e),
        None => (name, "jar"),
    };
    let parts: Vec<&str> = coords.split(':').collect();
    if parts.len() < 3 {
        return Err(Error::msg(format!("invalid maven coordinate: {name}")));
    }
    let (group, artifact, version) = (parts[0], parts[1], parts[2]);
    let classifier = parts.get(3).map(|c| format!("-{c}")).unwrap_or_default();
    Ok(format!("{}/{artifact}/{version}/{artifact}-{version}{classifier}.{ext}", group.replace('.', "/")))
}

/// Key used to de-duplicate libraries across parent/child versions: group:artifact[:classifier].
fn library_key(name: &str) -> String {
    let coords = name.split('@').next().unwrap_or(name);
    let p: Vec<&str> = coords.split(':').collect();
    match p.len() {
        0..=2 => coords.to_string(),
        3 => format!("{}:{}", p[0], p[1]),
        _ => format!("{}:{}:{}", p[0], p[1], p[3]),
    }
}

/// Merges a child profile (e.g. Fabric) onto its parent (vanilla). Child libraries win on conflicts.
pub fn merge(parent: VersionJson, child: VersionJson) -> VersionJson {
    let mut seen = HashSet::new();
    let mut libraries = Vec::new();
    for lib in child.libraries.into_iter().chain(parent.libraries) {
        if seen.insert(library_key(&lib.name)) {
            libraries.push(lib);
        }
    }
    let arguments = match (parent.arguments, child.arguments) {
        (Some(mut p), Some(c)) => {
            p.game.extend(c.game);
            p.jvm.extend(c.jvm);
            Some(p)
        }
        (p, c) => c.or(p),
    };
    VersionJson {
        id: child.id,
        inherits_from: None,
        main_class: if child.main_class.is_empty() { parent.main_class } else { child.main_class },
        arguments,
        minecraft_arguments: child.minecraft_arguments.or(parent.minecraft_arguments),
        libraries,
        asset_index: child.asset_index.or(parent.asset_index),
        assets: child.assets.or(parent.assets),
        downloads: child.downloads.or(parent.downloads),
        java_version: child.java_version.or(parent.java_version),
        kind: child.kind.or(parent.kind),
    }
}

/// Libraries that apply to this OS, as verified downloads into `libraries_dir`.
/// Libraries without a known hash get it from the Maven `.sha1` sidecar (see `fill_missing_hashes`).
pub fn library_downloads(v: &VersionJson, libraries_dir: &Path, env: &Env) -> Result<Vec<Download>> {
    let mut out = Vec::new();
    for lib in v.libraries.iter().filter(|l| allowed(&l.rules, env)) {
        if let Some(a) = lib.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
            let rel = match &a.path {
                Some(p) => p.clone(),
                None => maven_path(&lib.name)?,
            };
            out.push(Download { url: a.url.clone(), path: libraries_dir.join(rel), sha1: Some(a.sha1.clone()), size: Some(a.size) });
        } else if let Some(base) = &lib.url {
            let rel = maven_path(&lib.name)?;
            let url = format!("{}/{}", base.trim_end_matches('/'), rel);
            out.push(Download { url, path: libraries_dir.join(rel), sha1: lib.sha1.clone(), size: lib.size });
        }
        // Libraries with neither (e.g. legacy natives-only entries) are skipped.
    }
    Ok(out)
}

/// For downloads without a hash, fetch the Maven `<file>.sha1` so every file is still verified.
pub async fn fill_missing_hashes(client: &reqwest::Client, downloads: &mut [Download]) -> Result<()> {
    for d in downloads.iter_mut().filter(|d| d.sha1.is_none()) {
        let text = client.get(format!("{}.sha1", d.url)).send().await?.error_for_status()?.text().await?;
        let hash = text.split_whitespace().next().unwrap_or_default().to_lowercase();
        if hash.len() != 40 {
            return Err(Error::msg(format!("no valid checksum published for {}", d.url)));
        }
        d.sha1 = Some(hash);
    }
    Ok(())
}

pub fn classpath(v: &VersionJson, libraries_dir: &Path, client_jar: &Path, env: &Env) -> Result<Vec<String>> {
    let mut cp: Vec<String> = library_downloads(v, libraries_dir, env)?
        .into_iter()
        .map(|d| d.path.display().to_string())
        .collect();
    cp.push(client_jar.display().to_string());
    Ok(cp)
}

pub fn asset_downloads(index: &AssetIndex, assets_dir: &Path) -> Vec<Download> {
    let mut seen = HashSet::new();
    index
        .objects
        .values()
        .filter(|o| seen.insert(o.hash.clone()))
        .map(|o| {
            let prefix = &o.hash[..2];
            Download {
                url: format!("{RESOURCES_BASE}/{prefix}/{}", o.hash),
                path: assets_dir.join("objects").join(prefix).join(&o.hash),
                sha1: Some(o.hash.clone()),
                size: Some(o.size),
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rules::{Action, OsRule};

    #[test]
    fn maven_paths() {
        assert_eq!(maven_path("net.fabricmc:fabric-loader:0.20.1").unwrap(), "net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar");
        assert_eq!(
            maven_path("org.lwjgl:lwjgl:3.3.3:natives-windows").unwrap(),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar"
        );
        assert_eq!(maven_path("a.b:c:1@zip").unwrap(), "a/b/c/1/c-1.zip");
        assert!(maven_path("broken").is_err());
    }

    fn lib(name: &str) -> Library {
        Library { name: name.into(), downloads: None, url: Some("https://maven.example/".into()), sha1: None, size: None, rules: vec![] }
    }

    #[test]
    fn merge_child_wins_and_keeps_natives_separate() {
        let parent = VersionJson {
            id: "26.3".into(),
            main_class: "net.minecraft.client.main.Main".into(),
            libraries: vec![lib("org.ow2.asm:asm:9.6"), lib("org.lwjgl:lwjgl:3.3.3"), lib("org.lwjgl:lwjgl:3.3.3:natives-windows")],
            arguments: Some(Arguments { game: vec![Argument::Plain("--username".into())], jvm: vec![] }),
            ..Default::default()
        };
        let child = VersionJson {
            id: "fabric-loader-0.20.1-26.3".into(),
            inherits_from: Some("26.3".into()),
            main_class: "net.fabricmc.loader.impl.launch.knot.KnotClient".into(),
            libraries: vec![lib("org.ow2.asm:asm:9.8")],
            arguments: Some(Arguments { game: vec![], jvm: vec![Argument::Plain("-DFabricMcEmu=x".into())] }),
            ..Default::default()
        };
        let m = merge(parent, child);
        assert_eq!(m.id, "fabric-loader-0.20.1-26.3");
        assert_eq!(m.main_class, "net.fabricmc.loader.impl.launch.knot.KnotClient");
        let names: Vec<_> = m.libraries.iter().map(|l| l.name.as_str()).collect();
        assert_eq!(names, vec!["org.ow2.asm:asm:9.8", "org.lwjgl:lwjgl:3.3.3", "org.lwjgl:lwjgl:3.3.3:natives-windows"]);
        let args = m.arguments.unwrap();
        assert_eq!(args.game.len(), 1);
        assert_eq!(args.jvm.len(), 1);
    }

    #[test]
    fn library_rules_filter_by_os() {
        let mut win_only = lib("org.lwjgl:lwjgl:3.3.3:natives-windows");
        win_only.rules = vec![Rule { action: Action::Allow, os: Some(OsRule { name: Some("windows".into()), arch: None }), features: None }];
        let v = VersionJson { libraries: vec![lib("a:b:1"), win_only], ..Default::default() };
        let linux = Env { os: "linux", arch: "x86_64", features: Default::default() };
        let windows = Env { os: "windows", arch: "x86_64", features: Default::default() };
        assert_eq!(library_downloads(&v, Path::new("/libs"), &linux).unwrap().len(), 1);
        assert_eq!(library_downloads(&v, Path::new("/libs"), &windows).unwrap().len(), 2);
    }

    #[test]
    fn parses_ruled_arguments() {
        let json = r#"{"game":["--demo",{"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},{"rules":[],"value":["--width","${resolution_width}"]}],"jvm":[]}"#;
        let a: Arguments = serde_json::from_str(json).unwrap();
        assert_eq!(a.game.len(), 3);
        assert!(matches!(a.game[2], Argument::Ruled { value: ArgValue::Many(_), .. }));
    }

    #[test]
    fn asset_paths_use_hash_prefix() {
        let mut objects = HashMap::new();
        objects.insert("a".into(), AssetObject { hash: "abcdef0123".into(), size: 3 });
        objects.insert("b".into(), AssetObject { hash: "abcdef0123".into(), size: 3 });
        let d = asset_downloads(&AssetIndex { objects }, Path::new("/assets"));
        assert_eq!(d.len(), 1, "duplicate hashes are downloaded once");
        assert_eq!(d[0].url, "https://resources.download.minecraft.net/ab/abcdef0123");
        assert!(d[0].path.ends_with("objects/ab/abcdef0123"));
    }
}
