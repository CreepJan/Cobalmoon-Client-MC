//! Modrinth: search, dependency resolution, verified installs and `.mrpack` modpacks.
//!
//! Resolution rules (mirrors tests/compat/pin.mjs):
//! - the newest Fabric build for the instance's Minecraft version is used,
//! - unless some mod pins an exact version of a dependency (e.g. Iris → a specific Sodium), which wins,
//! - mods already in the instance are detected by hash and not downloaded again,
//! - declared incompatibilities are reported as warnings; nothing is ever blocked.

use crate::download::{fetch_all, Download};
use crate::error::{Error, Result};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet, VecDeque};
use std::io::Read;
use std::path::{Component, Path, PathBuf};

pub const API: &str = "https://api.modrinth.com/v2";

/// Mods of the one-click "PvP performance" preset (Modrinth slugs).
pub const PVP_PRESET: &[&str] = &["sodium", "lithium", "immediatelyfast", "ferrite-core", "entityculling"];

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SearchHit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub author: String,
}

#[derive(Deserialize)]
struct SearchResponse {
    hits: Vec<RawHit>,
}

#[derive(Deserialize)]
struct RawHit {
    project_id: String,
    slug: String,
    title: String,
    description: String,
    icon_url: Option<String>,
    downloads: u64,
    author: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Version {
    pub id: String,
    pub project_id: String,
    pub version_number: String,
    /// "release", "beta" or "alpha".
    #[serde(default = "release")]
    pub version_type: String,
    pub files: Vec<VersionFile>,
    #[serde(default)]
    pub dependencies: Vec<Dependency>,
}

fn release() -> String {
    "release".into()
}

/// Newest stable version, or the newest of any kind if there is no stable one.
/// Modrinth returns versions newest first.
pub fn pick_preferred(versions: Vec<Version>) -> Option<Version> {
    let first_release = versions.iter().position(|v| v.version_type == "release");
    match first_release {
        Some(i) => versions.into_iter().nth(i),
        None => versions.into_iter().next(),
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct VersionFile {
    pub url: String,
    pub filename: String,
    #[serde(default)]
    pub primary: bool,
    pub size: u64,
    pub hashes: FileHashes,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct FileHashes {
    pub sha512: String,
    #[serde(default)]
    pub sha1: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Dependency {
    #[serde(default)]
    pub version_id: Option<String>,
    #[serde(default)]
    pub project_id: Option<String>,
    pub dependency_type: String,
}

impl Version {
    pub fn primary_file(&self) -> Option<&VersionFile> {
        self.files.iter().find(|f| f.primary).or_else(|| self.files.first())
    }
}

/// What the resolver needs from Modrinth; a trait so tests can use an in-memory catalogue.
#[allow(async_fn_in_trait)]
pub trait Catalogue {
    /// Newest Fabric version of a project (id or slug) for a Minecraft version.
    async fn latest(&self, project: &str, mc: &str) -> Result<Option<Version>>;
    async fn version(&self, id: &str) -> Result<Version>;
}

pub struct Modrinth {
    http: reqwest::Client,
}

impl Modrinth {
    pub fn new(http: reqwest::Client) -> Self {
        Self { http }
    }

    async fn get<T: serde::de::DeserializeOwned>(&self, path: &str) -> Result<T> {
        Ok(self.http.get(format!("{API}{path}")).send().await?.error_for_status()?.json().await?)
    }

    pub async fn search(&self, query: &str, mc: &str, offset: u32) -> Result<Vec<SearchHit>> {
        let facets = serde_json::json!([["project_type:mod"], ["categories:fabric"], [format!("versions:{mc}")]]).to_string();
        let mut url = url::Url::parse(&format!("{API}/search")).expect("static url");
        url.query_pairs_mut()
            .append_pair("query", query)
            .append_pair("facets", &facets)
            .append_pair("limit", "20")
            .append_pair("offset", &offset.to_string());
        let r: SearchResponse = self.http.get(url).send().await?.error_for_status()?.json().await?;
        Ok(r.hits
            .into_iter()
            .map(|h| SearchHit {
                project_id: h.project_id,
                slug: h.slug,
                title: h.title,
                description: h.description,
                icon_url: h.icon_url,
                downloads: h.downloads,
                author: h.author,
            })
            .collect())
    }

    /// Identifies local files by SHA-512 (files not on Modrinth are simply absent from the map).
    pub async fn identify(&self, sha512s: &[String]) -> Result<HashMap<String, Version>> {
        if sha512s.is_empty() {
            return Ok(HashMap::new());
        }
        let resp = self
            .http
            .post(format!("{API}/version_files"))
            .json(&serde_json::json!({ "hashes": sha512s, "algorithm": "sha512" }))
            .send()
            .await?
            .error_for_status()?;
        Ok(resp.json().await?)
    }
}

impl Catalogue for Modrinth {
    async fn latest(&self, project: &str, mc: &str) -> Result<Option<Version>> {
        let loaders = urlenc(r#"["fabric"]"#);
        let games = urlenc(&serde_json::json!([mc]).to_string());
        let versions: Vec<Version> = self.get(&format!("/project/{}/version?loaders={loaders}&game_versions={games}", urlenc(project))).await?;
        Ok(pick_preferred(versions))
    }

    async fn version(&self, id: &str) -> Result<Version> {
        self.get(&format!("/version/{}", urlenc(id))).await
    }
}

fn urlenc(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Plan {
    pub install: Vec<Version>,
    pub warnings: Vec<String>,
}

/// Works out which versions to download for `wanted` (project ids or slugs), given what is installed
/// (project id → version). Required dependencies are followed; exact version pins win over "latest".
pub async fn resolve<C: Catalogue>(cat: &C, wanted: &[String], mc: &str, installed: &HashMap<String, Version>) -> Result<Plan> {
    let mut chosen: HashMap<String, Version> = HashMap::new();
    let mut order: Vec<String> = Vec::new();
    let mut pinned: HashSet<String> = HashSet::new();
    let mut warnings = Vec::new();
    let mut queue: VecDeque<(String, Option<String>, bool)> = wanted.iter().map(|w| (w.clone(), None, true)).collect();

    // Pins declared by mods that are already installed also apply.
    for v in installed.values() {
        for d in v.dependencies.iter().filter(|d| d.dependency_type == "required") {
            if let (Some(p), Some(vid)) = (&d.project_id, &d.version_id) {
                if installed.get(p).is_none_or(|have| &have.id != vid) {
                    queue.push_back((p.clone(), Some(vid.clone()), false));
                }
            }
        }
    }

    while let Some((project, version_id, explicit)) = queue.pop_front() {
        let v = match &version_id {
            Some(id) => cat.version(id).await?,
            None => match cat.latest(&project, mc).await? {
                Some(v) => v,
                None if explicit => return Err(Error::msg(format!("'{project}' has no Fabric version for Minecraft {mc}"))),
                None => {
                    warnings.push(format!("A required dependency ({project}) has no Fabric version for Minecraft {mc}."));
                    continue;
                }
            },
        };
        let pid = v.project_id.clone();
        if let Some(have) = installed.get(&pid) {
            if version_id.is_none() || version_id.as_deref() == Some(have.id.as_str()) {
                continue; // already satisfied
            }
            warnings.push(format!(
                "A mod needs {} version {}; the instance has {}. Cobalmoon installs the required version next to it.",
                pid, v.version_number, have.version_number
            ));
        }
        if chosen.contains_key(&pid) && (pinned.contains(&pid) || version_id.is_none()) {
            continue;
        }
        if version_id.is_some() {
            pinned.insert(pid.clone());
        }
        for d in &v.dependencies {
            match (d.dependency_type.as_str(), &d.project_id) {
                ("required", Some(p)) => queue.push_back((p.clone(), d.version_id.clone(), false)),
                ("required", None) => {
                    if let Some(vid) = &d.version_id {
                        queue.push_back((String::new(), Some(vid.clone()), false));
                    }
                }
                _ => {}
            }
        }
        if !order.contains(&pid) {
            order.push(pid.clone());
        }
        chosen.insert(pid, v);
    }

    // Incompatibilities between everything that will be present.
    let present: HashMap<&str, &Version> = installed.iter().map(|(k, v)| (k.as_str(), v)).chain(chosen.iter().map(|(k, v)| (k.as_str(), v))).collect();
    for v in present.values() {
        for d in v.dependencies.iter().filter(|d| d.dependency_type == "incompatible") {
            if let Some(p) = &d.project_id {
                if present.contains_key(p.as_str()) {
                    warnings.push(format!("{} declares itself incompatible with {}. Installed anyway: your choice.", v.project_id, p));
                }
            }
        }
    }

    Ok(Plan { install: order.into_iter().filter_map(|p| chosen.remove(&p)).collect(), warnings })
}

/// Only Modrinth's CDN (and the hosts the .mrpack spec allows) are accepted as download sources.
pub fn allowed_url(url: &str) -> bool {
    const HOSTS: &[&str] = &["cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com"];
    url::Url::parse(url).is_ok_and(|u| u.scheme() == "https" && u.host_str().is_some_and(|h| HOSTS.contains(&h)))
}

/// Downloads the plan's primary files into `dir`, verifying SHA-512. Returns the file names written.
pub async fn install(http: &reqwest::Client, plan: &Plan, dir: &Path) -> Result<Vec<String>> {
    let mut downloads = Vec::new();
    let mut names = Vec::new();
    for v in &plan.install {
        let f = v.primary_file().ok_or_else(|| Error::msg(format!("{} has no files", v.version_number)))?;
        if !allowed_url(&f.url) {
            return Err(Error::msg(format!("refusing to download from an untrusted host: {}", f.url)));
        }
        let name = safe_file_name(&f.filename)?;
        downloads.push(Download { url: f.url.clone(), path: dir.join(&name), sha512: Some(f.hashes.sha512.clone()), size: Some(f.size), ..Default::default() });
        names.push(name);
    }
    tokio::fs::create_dir_all(dir).await?;
    fetch_all(http, downloads, 6, |_, _| {}).await?;
    Ok(names)
}

fn safe_file_name(name: &str) -> Result<String> {
    let ok = !name.is_empty() && !name.contains(['/', '\\', ':']) && name != "." && name != "..";
    if ok { Ok(name.to_string()) } else { Err(Error::msg(format!("invalid file name '{name}'"))) }
}

/// A relative path that cannot escape its base directory (zip-slip protection).
pub fn safe_relative(path: &str) -> Option<PathBuf> {
    let p = Path::new(path);
    if p.is_absolute() || path.contains(':') {
        return None;
    }
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::Normal(s) => out.push(s),
            Component::CurDir => {}
            _ => return None,
        }
    }
    (!out.as_os_str().is_empty()).then_some(out)
}

// ---- .mrpack ---------------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MrpackIndex {
    pub name: String,
    #[serde(default)]
    pub version_id: String,
    pub files: Vec<MrpackFile>,
    pub dependencies: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MrpackFile {
    pub path: String,
    pub hashes: HashMap<String, String>,
    #[serde(default)]
    pub env: Option<HashMap<String, String>>,
    pub downloads: Vec<String>,
    #[serde(default)]
    pub file_size: Option<u64>,
}

pub struct MrpackInfo {
    pub name: String,
    pub mc_version: String,
    pub fabric_loader: Option<String>,
    /// Other loaders (forge, neoforge, quilt-loader) the pack asks for.
    pub other_loader: Option<String>,
}

pub fn read_mrpack_index(path: &Path) -> Result<MrpackIndex> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(path)?).map_err(|e| Error::msg(format!("not a valid .mrpack: {e}")))?;
    let mut s = String::new();
    zip.by_name("modrinth.index.json").map_err(|_| Error::msg("the .mrpack has no modrinth.index.json"))?.read_to_string(&mut s)?;
    Ok(serde_json::from_str(&s)?)
}

pub fn mrpack_info(index: &MrpackIndex) -> Result<MrpackInfo> {
    let mc = index.dependencies.get("minecraft").cloned().ok_or_else(|| Error::msg("the .mrpack does not say which Minecraft version it needs"))?;
    let other = ["forge", "neoforge", "quilt-loader"].iter().find_map(|k| index.dependencies.get(*k).map(|v| format!("{k} {v}")));
    Ok(MrpackInfo { name: index.name.clone(), mc_version: mc, fabric_loader: index.dependencies.get("fabric-loader").cloned(), other_loader: other })
}

/// Installs a modpack into `game_dir`: downloads every client file (hash-verified, trusted hosts only)
/// and extracts `overrides/` and `client-overrides/`.
pub async fn install_mrpack<F: Fn(usize, usize)>(http: &reqwest::Client, pack: &Path, game_dir: &Path, progress: F) -> Result<MrpackInfo> {
    let index = read_mrpack_index(pack)?;
    let info = mrpack_info(&index)?;
    let mut downloads = Vec::new();
    for f in &index.files {
        if f.env.as_ref().and_then(|e| e.get("client")).is_some_and(|c| c == "unsupported") {
            continue;
        }
        let rel = safe_relative(&f.path).ok_or_else(|| Error::msg(format!("unsafe path in .mrpack: {}", f.path)))?;
        let url = f.downloads.iter().find(|u| allowed_url(u)).ok_or_else(|| Error::msg(format!("no trusted download for {}", f.path)))?;
        let sha512 = f.hashes.get("sha512").cloned();
        let sha1 = f.hashes.get("sha1").cloned();
        if sha512.is_none() && sha1.is_none() {
            return Err(Error::msg(format!("{} has no hash in the .mrpack", f.path)));
        }
        downloads.push(Download { url: url.clone(), path: game_dir.join(rel), sha1, sha512, size: f.file_size });
    }
    fetch_all(http, downloads, 6, progress).await?;

    let pack = pack.to_path_buf();
    let dir = game_dir.to_path_buf();
    tokio::task::spawn_blocking(move || extract_overrides(&pack, &dir)).await.map_err(|e| Error::msg(e.to_string()))??;
    Ok(info)
}

fn extract_overrides(pack: &Path, game_dir: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(pack)?).map_err(|e| Error::msg(e.to_string()))?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i).map_err(|e| Error::msg(e.to_string()))?;
        let name = entry.name().to_string();
        let rest = name.strip_prefix("overrides/").or_else(|| name.strip_prefix("client-overrides/"));
        let Some(rest) = rest else { continue };
        if entry.is_dir() || rest.is_empty() {
            continue;
        }
        let rel = safe_relative(rest).ok_or_else(|| Error::msg(format!("unsafe path in .mrpack: {name}")))?;
        let out = game_dir.join(rel);
        if let Some(p) = out.parent() {
            std::fs::create_dir_all(p)?;
        }
        let mut f = std::fs::File::create(&out)?;
        std::io::copy(&mut entry, &mut f)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    fn ver(id: &str, project: &str, deps: Vec<Dependency>) -> Version {
        Version {
            id: id.into(),
            project_id: project.into(),
            version_number: format!("{project}-{id}"),
            version_type: "release".into(),
            files: vec![VersionFile {
                url: format!("https://cdn.modrinth.com/data/{project}/{id}.jar"),
                filename: format!("{project}-{id}.jar"),
                primary: true,
                size: 1,
                hashes: FileHashes { sha512: "00".into(), sha1: None },
            }],
            dependencies: deps,
        }
    }

    fn req(project: &str, version: Option<&str>) -> Dependency {
        Dependency { project_id: Some(project.into()), version_id: version.map(str::to_string), dependency_type: "required".into() }
    }

    fn incompatible(project: &str) -> Dependency {
        Dependency { project_id: Some(project.into()), version_id: None, dependency_type: "incompatible".into() }
    }

    /// In-memory Modrinth: latest version per project and versions by id.
    struct Fake {
        latest: HashMap<String, Version>,
        by_id: HashMap<String, Version>,
        calls: RefCell<Vec<String>>,
    }

    impl Fake {
        fn new(latest: Vec<Version>, extra: Vec<Version>) -> Self {
            let by_id = latest.iter().chain(extra.iter()).map(|v| (v.id.clone(), v.clone())).collect();
            Fake { latest: latest.into_iter().map(|v| (v.project_id.clone(), v)).collect(), by_id, calls: RefCell::new(vec![]) }
        }
    }

    impl Catalogue for Fake {
        async fn latest(&self, project: &str, _mc: &str) -> Result<Option<Version>> {
            self.calls.borrow_mut().push(project.into());
            Ok(self.latest.get(project).cloned())
        }
        async fn version(&self, id: &str) -> Result<Version> {
            self.by_id.get(id).cloned().ok_or_else(|| Error::msg("unknown version"))
        }
    }

    fn ids(p: &Plan) -> Vec<&str> {
        p.install.iter().map(|v| v.id.as_str()).collect()
    }

    #[tokio::test]
    async fn follows_required_dependencies() {
        let cat = Fake::new(vec![ver("mm21", "modmenu", vec![req("placeholder", None)]), ver("ph3", "placeholder", vec![])], vec![]);
        let plan = resolve(&cat, &["modmenu".into()], "26.3", &HashMap::new()).await.unwrap();
        assert_eq!(ids(&plan), vec!["mm21", "ph3"]);
        assert!(plan.warnings.is_empty());
    }

    #[tokio::test]
    async fn exact_pins_win_over_latest() {
        // Like Iris → Sodium: the newest Sodium is a beta Iris does not support.
        let cat = Fake::new(
            vec![ver("iris1", "iris", vec![req("sodium", Some("sod092"))]), ver("sod093b", "sodium", vec![])],
            vec![ver("sod092", "sodium", vec![])],
        );
        let plan = resolve(&cat, &["sodium".into(), "iris".into()], "26.3", &HashMap::new()).await.unwrap();
        assert_eq!(ids(&plan), vec!["sod092", "iris1"]);
    }

    #[tokio::test]
    async fn installed_mods_are_skipped_and_their_pins_apply() {
        let cat = Fake::new(vec![ver("sod093b", "sodium", vec![]), ver("lith", "lithium", vec![])], vec![ver("sod092", "sodium", vec![])]);
        let installed = HashMap::from([("iris".to_string(), ver("iris1", "iris", vec![req("sodium", Some("sod092"))]))]);
        let plan = resolve(&cat, &["lithium".into(), "sodium".into()], "26.3", &installed).await.unwrap();
        assert_eq!(ids(&plan), vec!["lith", "sod092"]);

        let have_all = HashMap::from([("lithium".to_string(), ver("lith", "lithium", vec![]))]);
        let plan = resolve(&cat, &["lithium".into()], "26.3", &have_all).await.unwrap();
        assert!(plan.install.is_empty());
    }

    #[tokio::test]
    async fn incompatibilities_warn_but_do_not_block() {
        let cat = Fake::new(vec![ver("a1", "a", vec![incompatible("b")]), ver("b1", "b", vec![])], vec![]);
        let plan = resolve(&cat, &["a".into(), "b".into()], "26.3", &HashMap::new()).await.unwrap();
        assert_eq!(plan.install.len(), 2);
        assert_eq!(plan.warnings.len(), 1);
        assert!(plan.warnings[0].contains("incompatible"));
    }

    #[tokio::test]
    async fn missing_versions() {
        let cat = Fake::new(vec![ver("a1", "a", vec![req("gone", None)])], vec![]);
        let plan = resolve(&cat, &["a".into()], "26.3", &HashMap::new()).await.unwrap();
        assert_eq!(ids(&plan), vec!["a1"]);
        assert_eq!(plan.warnings.len(), 1);
        assert!(resolve(&cat, &["nope".into()], "26.3", &HashMap::new()).await.is_err());
    }

    #[tokio::test]
    async fn dependency_cycles_terminate() {
        let cat = Fake::new(vec![ver("a1", "a", vec![req("b", None)]), ver("b1", "b", vec![req("a", None)])], vec![]);
        let plan = resolve(&cat, &["a".into()], "26.3", &HashMap::new()).await.unwrap();
        assert_eq!(plan.install.len(), 2);
        assert!(cat.calls.borrow().len() <= 3);
    }

    #[test]
    fn prefers_stable_releases() {
        let mut beta = ver("b", "sodium", vec![]);
        beta.version_type = "beta".into();
        let stable = ver("r", "sodium", vec![]);
        assert_eq!(pick_preferred(vec![beta.clone(), stable]).unwrap().id, "r");
        assert_eq!(pick_preferred(vec![beta]).unwrap().id, "b", "beta when nothing else exists");
        assert!(pick_preferred(vec![]).is_none());
    }

    #[test]
    fn url_and_path_safety() {
        assert!(allowed_url("https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar"));
        assert!(!allowed_url("http://cdn.modrinth.com/x.jar"));
        assert!(!allowed_url("https://evil.example/x.jar"));
        assert!(!allowed_url("https://cdn.modrinth.com.evil.example/x.jar"));
        assert_eq!(safe_relative("mods/a.jar"), Some(PathBuf::from("mods").join("a.jar")));
        assert_eq!(safe_relative("./config/x.json"), Some(PathBuf::from("config").join("x.json")));
        assert!(safe_relative("../outside.jar").is_none());
        assert!(safe_relative("mods/../../x").is_none());
        assert!(safe_relative("/etc/passwd").is_none());
        assert!(safe_relative("C:/Windows/x").is_none());
        assert!(safe_file_name("a/b.jar").is_err());
    }

    #[tokio::test]
    async fn mrpack_overrides_are_extracted_safely() {
        use std::io::Write;
        let dir = tempfile::tempdir().unwrap();
        let pack = dir.path().join("pack.mrpack");
        {
            let mut zip = zip::ZipWriter::new(std::fs::File::create(&pack).unwrap());
            let o = zip::write::SimpleFileOptions::default();
            zip.start_file("modrinth.index.json", o).unwrap();
            zip.write_all(br#"{"formatVersion":1,"game":"minecraft","versionId":"1","name":"Test Pack","files":[],"dependencies":{"minecraft":"26.3","fabric-loader":"0.20.1"}}"#).unwrap();
            zip.start_file("overrides/config/a.txt", o).unwrap();
            zip.write_all(b"hello").unwrap();
            zip.start_file("client-overrides/options.txt", o).unwrap();
            zip.write_all(b"lang:es_es").unwrap();
            zip.finish().unwrap();
        }
        let game = dir.path().join("game");
        let info = install_mrpack(&reqwest::Client::new(), &pack, &game, |_, _| {}).await.unwrap();
        assert_eq!(info.name, "Test Pack");
        assert_eq!(info.mc_version, "26.3");
        assert_eq!(info.fabric_loader.as_deref(), Some("0.20.1"));
        assert_eq!(std::fs::read_to_string(game.join("config/a.txt")).unwrap(), "hello");
        assert!(game.join("options.txt").exists());

        // zip-slip attempt
        let evil = dir.path().join("evil.mrpack");
        {
            let mut zip = zip::ZipWriter::new(std::fs::File::create(&evil).unwrap());
            let o = zip::write::SimpleFileOptions::default();
            zip.start_file("modrinth.index.json", o).unwrap();
            zip.write_all(br#"{"formatVersion":1,"game":"minecraft","name":"Evil","files":[],"dependencies":{"minecraft":"26.3"}}"#).unwrap();
            zip.start_file("overrides/../../escaped.txt", o).unwrap();
            zip.write_all(b"x").unwrap();
            zip.finish().unwrap();
        }
        let err = install_mrpack(&reqwest::Client::new(), &evil, &dir.path().join("game2"), |_, _| {}).await;
        assert!(err.is_err());
        assert!(!dir.path().join("escaped.txt").exists());
    }
}
