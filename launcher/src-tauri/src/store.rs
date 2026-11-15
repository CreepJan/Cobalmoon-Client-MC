//! Persistent launcher state (JSON files in the app data dir). Never contains tokens.

use crate::auth::AccountInfo;
use crate::error::Result;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// Minecraft versions the Cobalmoon core mod is built for. Other versions still launch, without it.
pub const SUPPORTED_VERSIONS: &[&str] = &["26.3", "1.21.11"];

#[derive(Debug, Clone)]
pub struct Paths {
    pub root: PathBuf,
}

impl Paths {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }
    pub fn versions(&self) -> PathBuf { self.root.join("versions") }
    pub fn libraries(&self) -> PathBuf { self.root.join("libraries") }
    pub fn assets(&self) -> PathBuf { self.root.join("assets") }
    pub fn runtimes(&self) -> PathBuf { self.root.join("runtimes") }
    pub fn instances(&self) -> PathBuf { self.root.join("instances") }
    pub fn natives(&self, version_id: &str) -> PathBuf { self.root.join("natives").join(version_id) }
    pub fn extra_mods(&self, instance_id: &str) -> PathBuf { self.root.join("extra-mods").join(instance_id) }
    fn file(&self, name: &str) -> PathBuf { self.root.join(name) }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct Settings {
    /// Azure application (client) ID used for Microsoft sign-in. Public, not a secret.
    pub client_id: String,
    /// Folder with cobalmoon-core-mc<version>.jar files. Empty = the copies bundled with the launcher.
    pub core_mods_dir: String,
    pub reduce_motion: bool,
    pub default_memory_mb: u32,
}

impl Settings {
    pub fn with_defaults(mut self) -> Self {
        if self.client_id.is_empty() {
            self.client_id = option_env!("COBALMOON_MSA_CLIENT_ID").unwrap_or_default().to_string();
        }
        if self.default_memory_mb == 0 {
            self.default_memory_mb = 4096;
        }
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Instance {
    pub id: String,
    pub name: String,
    pub mc_version: String,
    /// Fabric Loader version; `None` picks the latest stable at launch time.
    #[serde(default)]
    pub loader_version: Option<String>,
    /// Load the Cobalmoon core mod (only possible on supported versions).
    #[serde(default = "yes")]
    pub cobalmoon: bool,
    /// Custom game directory (e.g. an existing installation). `None` = instances/<id>.
    #[serde(default)]
    pub game_dir: Option<String>,
    #[serde(default)]
    pub memory_mb: Option<u32>,
    #[serde(default)]
    pub jvm_args: Vec<String>,
    /// Server to join directly on launch (server profile).
    #[serde(default)]
    pub server: Option<String>,
    /// Where the instance was imported from, if anywhere.
    #[serde(default)]
    pub source: Option<ImportSource>,
    /// True when Cobalmoon owns the game folder (created here, copied or installed from a .mrpack).
    /// False for installations used in place: their mods/ folder is never written to; mods added
    /// through Cobalmoon go to `extra-mods/<id>` and are loaded with -Dfabric.addMods.
    #[serde(default = "yes")]
    pub managed: bool,
    /// Set when the installation uses a loader Cobalmoon cannot launch (Forge, NeoForge).
    #[serde(default)]
    pub unsupported_loader: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ImportSource {
    pub kind: crate::importers::SourceKind,
    pub path: String,
}

fn yes() -> bool {
    true
}

impl Instance {
    pub fn game_dir(&self, paths: &Paths) -> PathBuf {
        match &self.game_dir {
            Some(d) if !d.trim().is_empty() => PathBuf::from(d),
            _ => paths.instances().join(&self.id),
        }
    }

    /// Folder that receives mods added through Cobalmoon.
    pub fn mods_target(&self, paths: &Paths) -> PathBuf {
        if self.managed {
            self.game_dir(paths).join("mods")
        } else {
            paths.extra_mods(&self.id)
        }
    }
}

/// Turns a display name into a safe, unique folder id.
pub fn slug(name: &str, taken: &[String]) -> String {
    let mut base: String = name
        .to_lowercase()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect::<String>()
        .split('-')
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("-");
    if base.is_empty() {
        base = "perfil".into();
    }
    let mut id = base.clone();
    let mut n = 2;
    while taken.contains(&id) {
        id = format!("{base}-{n}");
        n += 1;
    }
    id
}

fn read<T: DeserializeOwned + Default>(path: &Path) -> T {
    std::fs::read(path).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
}

fn write<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(value)?)?;
    std::fs::rename(tmp, path)?;
    Ok(())
}

pub fn load_settings(p: &Paths) -> Settings { read::<Settings>(&p.file("settings.json")).with_defaults() }
pub fn save_settings(p: &Paths, s: &Settings) -> Result<()> { write(&p.file("settings.json"), s) }
pub fn load_accounts(p: &Paths) -> Vec<AccountInfo> { read(&p.file("accounts.json")) }
pub fn save_accounts(p: &Paths, a: &[AccountInfo]) -> Result<()> { write(&p.file("accounts.json"), &a) }
pub fn load_instances(p: &Paths) -> Vec<Instance> { read(&p.file("instances.json")) }
pub fn save_instances(p: &Paths, i: &[Instance]) -> Result<()> { write(&p.file("instances.json"), &i) }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slugs_are_safe_and_unique() {
        assert_eq!(slug("PvP 26.3 — Hypixel!", &[]), "pvp-26-3-hypixel");
        assert_eq!(slug("PvP", &["pvp".into()]), "pvp-2");
        assert_eq!(slug("¿?", &[]), "perfil");
    }

    #[test]
    fn roundtrip_without_secrets() {
        let dir = tempfile::tempdir().unwrap();
        let p = Paths::new(dir.path().to_path_buf());
        let s = Settings { client_id: "abc".into(), default_memory_mb: 2048, ..Default::default() };
        save_settings(&p, &s).unwrap();
        assert_eq!(load_settings(&p), s);
        save_accounts(&p, &[AccountInfo { uuid: "u".into(), name: "Steve".into(), xuid: String::new() }]).unwrap();
        let raw = std::fs::read_to_string(dir.path().join("accounts.json")).unwrap();
        assert!(!raw.to_lowercase().contains("token"));
    }

    #[test]
    fn instance_defaults() {
        let i: Instance = serde_json::from_str(r#"{"id":"a","name":"A","mcVersion":"26.3"}"#).unwrap();
        assert!(i.cobalmoon);
        let p = Paths::new(PathBuf::from("/data"));
        assert_eq!(i.game_dir(&p), PathBuf::from("/data").join("instances").join("a"));
    }
}
