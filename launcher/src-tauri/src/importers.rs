//! Detection and import of existing installations: the official launcher (.minecraft), Prism/MultiMC,
//! CurseForge, Modrinth App profiles and plain folders.
//!
//! Importing never modifies the original installation: by default the instance points at it ("in place")
//! and Cobalmoon is added with `-Dfabric.addMods`; extra mods the player adds through Cobalmoon go to a
//! separate folder. Only reads happen here.

use crate::error::{Error, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum SourceKind {
    Vanilla,
    Prism,
    CurseForge,
    Modrinth,
    Folder,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind", content = "version", rename_all = "lowercase")]
pub enum Loader {
    Vanilla,
    Fabric(Option<String>),
    Quilt(Option<String>),
    Forge(Option<String>),
    NeoForge(Option<String>),
    Unknown,
}

impl Loader {
    /// Cobalmoon launches Fabric. Quilt instances usually work too (Quilt-only mods may not).
    pub fn launchable(&self) -> bool {
        matches!(self, Loader::Vanilla | Loader::Fabric(_) | Loader::Quilt(_) | Loader::Unknown)
    }

    pub fn fabric_version(&self) -> Option<String> {
        match self {
            Loader::Fabric(v) => v.clone(),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Detected {
    pub kind: SourceKind,
    pub name: String,
    /// Game directory (contains mods/, saves/, options.txt…).
    pub game_dir: PathBuf,
    /// None when it cannot be determined (the player picks it).
    pub mc_version: Option<String>,
    pub loader: Loader,
    pub mod_count: usize,
}

// ---------------------------------------------------------------------------------------------
// Default locations

pub struct Roots {
    pub vanilla: Vec<PathBuf>,
    pub prism: Vec<PathBuf>,
    pub curseforge: Vec<PathBuf>,
    pub modrinth: Vec<PathBuf>,
}

impl Roots {
    pub fn for_this_os() -> Self {
        let home = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).map(PathBuf::from).unwrap_or_default();
        let appdata = std::env::var_os("APPDATA").map(PathBuf::from);
        let (data, docs) = if cfg!(windows) {
            (appdata.clone().unwrap_or_else(|| home.join("AppData/Roaming")), home.join("Documents"))
        } else if cfg!(target_os = "macos") {
            (home.join("Library/Application Support"), home.join("Documents"))
        } else {
            (
                std::env::var_os("XDG_DATA_HOME").map(PathBuf::from).unwrap_or_else(|| home.join(".local/share")),
                home.join("Documents"),
            )
        };
        let vanilla = if cfg!(windows) {
            data.join(".minecraft")
        } else if cfg!(target_os = "macos") {
            data.join("minecraft")
        } else {
            home.join(".minecraft")
        };
        Roots {
            vanilla: vec![vanilla],
            prism: vec![data.join("PrismLauncher"), data.join("MultiMC"), data.join("multimc")],
            curseforge: vec![home.join("curseforge/minecraft/Instances"), docs.join("curseforge/minecraft/Instances")],
            modrinth: vec![data.join("ModrinthApp/profiles"), data.join("com.modrinth.theseus/profiles")],
        }
    }
}

/// Scans every known location. Problems with one installation never hide the others.
pub fn detect_all(roots: &Roots) -> Vec<Detected> {
    let mut out = Vec::new();
    for v in &roots.vanilla {
        out.extend(parse_vanilla(v).unwrap_or_default());
    }
    for p in &roots.prism {
        let instances = prism_instances_dir(p);
        for d in subdirs(&instances) {
            if let Some(i) = parse_prism(&d) {
                out.push(i);
            }
        }
    }
    for c in &roots.curseforge {
        for d in subdirs(c) {
            if let Some(i) = parse_curseforge(&d) {
                out.push(i);
            }
        }
    }
    for m in &roots.modrinth {
        for d in subdirs(m) {
            let mut i = inspect_folder(&d);
            i.kind = SourceKind::Modrinth;
            out.push(i);
        }
    }
    out
}

/// Recognises whatever folder the player picked.
pub fn detect_folder(dir: &Path) -> Result<Detected> {
    if !dir.is_dir() {
        return Err(Error::msg(format!("{} is not a folder", dir.display())));
    }
    if let Some(i) = parse_prism(dir) {
        return Ok(i);
    }
    if let Some(i) = parse_curseforge(dir) {
        return Ok(i);
    }
    Ok(inspect_folder(dir))
}

fn subdirs(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = std::fs::read_dir(dir)
        .map(|r| r.filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.is_dir()).collect())
        .unwrap_or_default();
    v.sort();
    v
}

fn count_mods(game_dir: &Path) -> usize {
    std::fs::read_dir(game_dir.join("mods"))
        .map(|r| r.filter_map(|e| e.ok()).filter(|e| e.path().extension().is_some_and(|x| x == "jar")).count())
        .unwrap_or(0)
}

// ---------------------------------------------------------------------------------------------
// Official launcher

#[derive(Deserialize)]
struct LauncherProfiles {
    #[serde(default)]
    profiles: HashMap<String, VanillaProfile>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct VanillaProfile {
    #[serde(default)]
    name: String,
    #[serde(default, rename = "type")]
    kind: String,
    #[serde(default)]
    last_version_id: String,
    #[serde(default)]
    game_dir: Option<String>,
}

/// Interprets an official-launcher version id.
pub fn parse_version_id(id: &str) -> (Option<String>, Loader) {
    if let Some(rest) = id.strip_prefix("fabric-loader-") {
        // fabric-loader-<loader>-<mc>
        if let Some((loader, mc)) = rest.split_once('-') {
            return (Some(mc.to_string()), Loader::Fabric(Some(loader.to_string())));
        }
    }
    if let Some(rest) = id.strip_prefix("quilt-loader-") {
        if let Some((loader, mc)) = rest.split_once('-') {
            return (Some(mc.to_string()), Loader::Quilt(Some(loader.to_string())));
        }
    }
    if let Some(rest) = id.strip_prefix("neoforge-") {
        return (None, Loader::NeoForge(Some(rest.to_string())));
    }
    if let Some((mc, forge)) = id.split_once("-forge-") {
        return (Some(mc.to_string()), Loader::Forge(Some(forge.to_string())));
    }
    if id.starts_with("latest-") || id.is_empty() {
        return (None, Loader::Vanilla);
    }
    (Some(id.to_string()), Loader::Vanilla)
}

pub fn parse_vanilla(minecraft_dir: &Path) -> Result<Vec<Detected>> {
    let file = minecraft_dir.join("launcher_profiles.json");
    if !file.is_file() {
        return Ok(Vec::new());
    }
    let profiles: LauncherProfiles = serde_json::from_slice(&std::fs::read(&file)?)?;
    let mut out: Vec<Detected> = profiles
        .profiles
        .into_values()
        .map(|p| {
            let (mc, loader) = parse_version_id(&p.last_version_id);
            let game_dir = p.game_dir.filter(|d| !d.trim().is_empty()).map(PathBuf::from).unwrap_or_else(|| minecraft_dir.to_path_buf());
            let name = if !p.name.trim().is_empty() {
                p.name
            } else if p.kind == "latest-release" {
                "Última versión".into()
            } else if p.kind == "latest-snapshot" {
                "Última snapshot".into()
            } else {
                p.last_version_id.clone()
            };
            Detected { kind: SourceKind::Vanilla, name, mod_count: count_mods(&game_dir), game_dir, mc_version: mc, loader }
        })
        .collect();
    out.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(out)
}

// ---------------------------------------------------------------------------------------------
// Prism Launcher / MultiMC

/// Prism may store instances elsewhere (InstanceDir in prismlauncher.cfg / multimc.cfg).
fn prism_instances_dir(root: &Path) -> PathBuf {
    for cfg in ["prismlauncher.cfg", "multimc.cfg"] {
        if let Ok(text) = std::fs::read_to_string(root.join(cfg)) {
            if let Some(dir) = ini_value(&text, "InstanceDir") {
                let p = PathBuf::from(&dir);
                return if p.is_absolute() { p } else { root.join(p) };
            }
        }
    }
    root.join("instances")
}

fn ini_value(text: &str, key: &str) -> Option<String> {
    text.lines().find_map(|l| {
        let (k, v) = l.split_once('=')?;
        (k.trim() == key).then(|| v.trim().to_string()).filter(|v| !v.is_empty())
    })
}

#[derive(Deserialize)]
struct MmcPack {
    #[serde(default)]
    components: Vec<MmcComponent>,
}

#[derive(Deserialize)]
struct MmcComponent {
    uid: String,
    #[serde(default)]
    version: Option<String>,
}

pub fn parse_prism(instance_dir: &Path) -> Option<Detected> {
    let cfg = std::fs::read_to_string(instance_dir.join("instance.cfg")).ok()?;
    let pack: MmcPack = std::fs::read(instance_dir.join("mmc-pack.json")).ok().and_then(|b| serde_json::from_slice(&b).ok())?;
    let mut mc = None;
    let mut loader = Loader::Vanilla;
    for c in pack.components {
        match c.uid.as_str() {
            "net.minecraft" => mc = c.version,
            "net.fabricmc.fabric-loader" => loader = Loader::Fabric(c.version),
            "org.quiltmc.quilt-loader" => loader = Loader::Quilt(c.version),
            "net.minecraftforge" => loader = Loader::Forge(c.version),
            "net.neoforged" => loader = Loader::NeoForge(c.version),
            _ => {}
        }
    }
    let game_dir = [".minecraft", "minecraft"].iter().map(|d| instance_dir.join(d)).find(|p| p.is_dir()).unwrap_or_else(|| instance_dir.join(".minecraft"));
    let name = ini_value(&cfg, "name").unwrap_or_else(|| file_name(instance_dir));
    Some(Detected { kind: SourceKind::Prism, name, mod_count: count_mods(&game_dir), game_dir, mc_version: mc, loader })
}

// ---------------------------------------------------------------------------------------------
// CurseForge

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CurseInstance {
    #[serde(default)]
    name: String,
    #[serde(default)]
    game_version: Option<String>,
    #[serde(default)]
    base_mod_loader: Option<CurseLoader>,
}

#[derive(Deserialize)]
struct CurseLoader {
    #[serde(default)]
    name: String,
}

/// CurseForge loader names: "fabric-0.18.4-1.21.11", "forge-47.4.0", "neoforge-21.1.218", "quilt-0.29.1-1.21.1".
pub fn parse_curse_loader(name: &str) -> Loader {
    let mut parts = name.splitn(3, '-');
    let kind = parts.next().unwrap_or("");
    let version = parts.next().map(str::to_string);
    match kind {
        "fabric" => Loader::Fabric(version),
        "quilt" => Loader::Quilt(version),
        "forge" => Loader::Forge(version),
        "neoforge" => Loader::NeoForge(version),
        "" => Loader::Vanilla,
        _ => Loader::Unknown,
    }
}

pub fn parse_curseforge(instance_dir: &Path) -> Option<Detected> {
    let json: CurseInstance = std::fs::read(instance_dir.join("minecraftinstance.json")).ok().and_then(|b| serde_json::from_slice(&b).ok())?;
    let loader = json.base_mod_loader.map(|l| parse_curse_loader(&l.name)).unwrap_or(Loader::Vanilla);
    let name = if json.name.trim().is_empty() { file_name(instance_dir) } else { json.name };
    Some(Detected {
        kind: SourceKind::CurseForge,
        name,
        mod_count: count_mods(instance_dir),
        game_dir: instance_dir.to_path_buf(),
        mc_version: json.game_version.filter(|v| !v.is_empty()),
        loader,
    })
}

// ---------------------------------------------------------------------------------------------
// Any folder (Modrinth App profiles, MultiMC copies, hand-made folders)

/// Guesses version and loader from the mods inside a game folder.
pub fn inspect_folder(dir: &Path) -> Detected {
    let mut versions: HashMap<String, usize> = HashMap::new();
    let mut fabric = 0;
    let mut quilt = 0;
    let mut forge = 0;
    let mut neoforge = 0;
    for jar in std::fs::read_dir(dir.join("mods")).into_iter().flatten().filter_map(|e| e.ok()).map(|e| e.path()) {
        if jar.extension().is_none_or(|x| x != "jar") {
            continue;
        }
        let Ok(info) = read_mod_jar(&jar) else { continue };
        match info.loader {
            JarLoader::Fabric => fabric += 1,
            JarLoader::Quilt => quilt += 1,
            JarLoader::Forge => forge += 1,
            JarLoader::NeoForge => neoforge += 1,
            JarLoader::None => {}
        }
        if let Some(v) = info.minecraft.as_deref().and_then(version_in_range) {
            *versions.entry(v).or_default() += 1;
        }
    }
    let loader = if fabric + quilt == 0 && forge + neoforge == 0 {
        Loader::Unknown
    } else if neoforge >= forge.max(fabric).max(quilt) && neoforge > 0 {
        Loader::NeoForge(None)
    } else if forge >= fabric.max(quilt) && forge > 0 {
        Loader::Forge(None)
    } else if quilt > fabric {
        Loader::Quilt(None)
    } else {
        Loader::Fabric(None)
    };
    let mc = versions.into_iter().max_by(|a, b| a.1.cmp(&b.1).then_with(|| b.0.cmp(&a.0))).map(|(v, _)| v);
    Detected { kind: SourceKind::Folder, name: file_name(dir), mod_count: count_mods(dir), game_dir: dir.to_path_buf(), mc_version: mc, loader }
}

/// First concrete version in a Fabric dependency range ("~1.21.11", ">=26.3", "1.21.x" → None).
pub fn version_in_range(range: &str) -> Option<String> {
    range
        .split(|c: char| c.is_whitespace() || c == '|' || c == ',')
        .map(|t| t.trim_start_matches(['~', '^', '=', '>', '<']))
        .find(|t| !t.is_empty() && t.chars().all(|c| c.is_ascii_digit() || c == '.') && t.contains('.'))
        .map(str::to_string)
}

fn file_name(p: &Path) -> String {
    p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| p.display().to_string())
}

// ---------------------------------------------------------------------------------------------
// Mod jars

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum JarLoader {
    Fabric,
    Quilt,
    Forge,
    NeoForge,
    None,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModJar {
    pub id: Option<String>,
    pub name: Option<String>,
    pub version: Option<String>,
    pub loader: JarLoader,
    /// Raw "minecraft" dependency range from fabric.mod.json.
    pub minecraft: Option<String>,
    /// Raw "java" dependency range from fabric.mod.json (e.g. ">=22").
    pub java: Option<String>,
}

#[derive(Deserialize)]
struct FabricModJson {
    id: Option<String>,
    name: Option<String>,
    version: Option<String>,
    #[serde(default)]
    depends: HashMap<String, serde_json::Value>,
}

/// Reads mod metadata from a jar without extracting it. Never executes anything.
pub fn read_mod_jar(path: &Path) -> Result<ModJar> {
    let file = std::fs::File::open(path)?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| Error::msg(format!("{}: not a valid jar ({e})", path.display())))?;
    let mut text = |name: &str| -> Option<String> {
        let mut entry = zip.by_name(name).ok()?;
        if entry.size() > 1_000_000 {
            return None;
        }
        let mut s = String::new();
        entry.read_to_string(&mut s).ok()?;
        Some(s)
    };
    if let Some(json) = text("fabric.mod.json") {
        // Some mods have comments or trailing commas; a failed parse still counts as a Fabric mod.
        let parsed: Option<FabricModJson> = serde_json::from_str(&json).ok();
        let dep = |key: &str| parsed.as_ref().and_then(|p| p.depends.get(key)).and_then(|v| match v {
            serde_json::Value::String(s) => Some(s.clone()),
            serde_json::Value::Array(a) => a.first().and_then(|x| x.as_str()).map(str::to_string),
            _ => None,
        });
        let (minecraft, java) = (dep("minecraft"), dep("java"));
        return Ok(match parsed {
            Some(p) => ModJar { id: p.id, name: p.name, version: p.version, loader: JarLoader::Fabric, minecraft, java },
            None => ModJar { id: None, name: None, version: None, loader: JarLoader::Fabric, minecraft: None, java: None },
        });
    }
    if text("quilt.mod.json").is_some() {
        return Ok(ModJar { id: None, name: None, version: None, loader: JarLoader::Quilt, minecraft: None, java: None });
    }
    if text("META-INF/neoforge.mods.toml").is_some() {
        return Ok(ModJar { id: None, name: None, version: None, loader: JarLoader::NeoForge, minecraft: None, java: None });
    }
    if text("META-INF/mods.toml").is_some() {
        return Ok(ModJar { id: None, name: None, version: None, loader: JarLoader::Forge, minecraft: None, java: None });
    }
    Ok(ModJar { id: None, name: None, version: None, loader: JarLoader::None, minecraft: None, java: None })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn fixtures() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tests/fixtures/instances")
    }

    pub(crate) fn make_jar(path: &Path, entries: &[(&str, &str)]) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        for (name, body) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(body.as_bytes()).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn version_ids() {
        assert_eq!(parse_version_id("fabric-loader-0.19.2-1.21.11"), (Some("1.21.11".into()), Loader::Fabric(Some("0.19.2".into()))));
        assert_eq!(parse_version_id("fabric-loader-0.19.3-26.1.2"), (Some("26.1.2".into()), Loader::Fabric(Some("0.19.3".into()))));
        assert_eq!(parse_version_id("1.21.11"), (Some("1.21.11".into()), Loader::Vanilla));
        assert_eq!(parse_version_id("latest-release"), (None, Loader::Vanilla));
        assert_eq!(parse_version_id("1.20.1-forge-47.2.0"), (Some("1.20.1".into()), Loader::Forge(Some("47.2.0".into()))));
        assert!(matches!(parse_version_id("neoforge-21.1.218").1, Loader::NeoForge(_)));
        assert_eq!(parse_version_id("quilt-loader-0.29.1-1.21.1").0, Some("1.21.1".into()));
    }

    #[test]
    fn curseforge_loader_names() {
        assert_eq!(parse_curse_loader("fabric-0.18.4-1.21.11"), Loader::Fabric(Some("0.18.4".into())));
        assert_eq!(parse_curse_loader("forge-47.4.0"), Loader::Forge(Some("47.4.0".into())));
        assert_eq!(parse_curse_loader("neoforge-21.1.218"), Loader::NeoForge(Some("21.1.218".into())));
        assert!(!parse_curse_loader("neoforge-21.1.218").launchable());
        assert!(parse_curse_loader("fabric-0.18.4-1.21.11").launchable());
    }

    #[test]
    fn ranges() {
        assert_eq!(version_in_range("~1.21.11"), Some("1.21.11".into()));
        assert_eq!(version_in_range(">=26.3 <26.4"), Some("26.3".into()));
        assert_eq!(version_in_range("1.21.x"), None);
        assert_eq!(version_in_range("*"), None);
    }

    #[test]
    fn vanilla_fixture() {
        let found = parse_vanilla(&fixtures().join("vanilla")).unwrap();
        assert_eq!(found.len(), 3);
        let fabric = found.iter().find(|d| d.name == "PvP Fabric").unwrap();
        assert_eq!(fabric.mc_version.as_deref(), Some("1.21.11"));
        assert_eq!(fabric.loader, Loader::Fabric(Some("0.19.2".into())));
        assert!(found.iter().any(|d| d.name == "Última versión" && d.mc_version.is_none()));
        let custom = found.iter().find(|d| d.name == "Custom dir").unwrap();
        assert!(custom.game_dir.ends_with("elsewhere"));
    }

    #[test]
    fn prism_fixture() {
        let d = parse_prism(&fixtures().join("prism/instances/PvP 26.3")).unwrap();
        assert_eq!(d.name, "PvP 26.3");
        assert_eq!(d.mc_version.as_deref(), Some("26.3"));
        assert_eq!(d.loader, Loader::Fabric(Some("0.20.1".into())));
        assert!(d.game_dir.ends_with("minecraft"));
    }

    #[test]
    fn curseforge_fixture() {
        let d = parse_curseforge(&fixtures().join("curseforge/Immersed")).unwrap();
        assert_eq!(d.mc_version.as_deref(), Some("1.21.11"));
        assert_eq!(d.loader, Loader::Fabric(Some("0.18.4".into())));
        let neo = parse_curseforge(&fixtures().join("curseforge/Craftoria")).unwrap();
        assert!(!neo.loader.launchable());
    }

    #[test]
    fn detect_all_uses_every_root() {
        let f = fixtures();
        let roots = Roots {
            vanilla: vec![f.join("vanilla")],
            prism: vec![f.join("prism")],
            curseforge: vec![f.join("curseforge")],
            modrinth: vec![f.join("does-not-exist")],
        };
        let all = detect_all(&roots);
        assert_eq!(all.iter().filter(|d| d.kind == SourceKind::Vanilla).count(), 3);
        assert_eq!(all.iter().filter(|d| d.kind == SourceKind::Prism).count(), 1);
        assert_eq!(all.iter().filter(|d| d.kind == SourceKind::CurseForge).count(), 2);
    }

    #[test]
    fn folder_inference_from_jars() {
        let dir = tempfile::tempdir().unwrap();
        let mods = dir.path().join("mods");
        make_jar(&mods.join("a.jar"), &[("fabric.mod.json", r#"{"id":"a","version":"1","depends":{"minecraft":"~26.3"}}"#)]);
        make_jar(&mods.join("b.jar"), &[("fabric.mod.json", r#"{"id":"b","version":"2","depends":{"minecraft":">=26.3"}}"#)]);
        make_jar(&mods.join("c.jar"), &[("fabric.mod.json", r#"{"id":"c","depends":{"minecraft":"1.21.11"}}"#)]);
        std::fs::write(mods.join("broken.jar"), b"not a zip").unwrap();
        let d = inspect_folder(dir.path());
        assert_eq!(d.loader, Loader::Fabric(None));
        assert_eq!(d.mc_version.as_deref(), Some("26.3"));
        assert_eq!(d.mod_count, 4);

        let neo = tempfile::tempdir().unwrap();
        make_jar(&neo.path().join("mods/x.jar"), &[("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"")]);
        assert_eq!(inspect_folder(neo.path()).loader, Loader::NeoForge(None));
    }

    #[test]
    fn reads_mod_metadata() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("sodium.jar");
        make_jar(&jar, &[("fabric.mod.json", r#"{"schemaVersion":1,"id":"sodium","name":"Sodium","version":"0.8.14","depends":{"minecraft":["~1.21.11"]}}"#)]);
        let m = read_mod_jar(&jar).unwrap();
        assert_eq!(m.id.as_deref(), Some("sodium"));
        assert_eq!(m.name.as_deref(), Some("Sodium"));
        assert_eq!(m.minecraft.as_deref(), Some("~1.21.11"));
        assert_eq!(m.loader, JarLoader::Fabric);
    }
}
