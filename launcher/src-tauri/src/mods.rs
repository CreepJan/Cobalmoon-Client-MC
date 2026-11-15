//! Mods of an instance and importing installations as instances.
//!
//! Safety rules: for installations used in place (`managed == false`) Cobalmoon never writes to or
//! deletes from their folder; anything added goes to `extra-mods/<id>`. Removing a mod is only
//! possible for files inside the folder Cobalmoon manages for that instance.

use crate::error::{Error, Result};
use crate::importers::{self, Detected, Loader};
use crate::store::{slug, ImportSource, Instance, Paths};
use serde::Serialize;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ModEntry {
    pub file_name: String,
    pub path: String,
    pub id: Option<String>,
    pub name: Option<String>,
    pub version: Option<String>,
    /// "instance" = the game folder's mods/, "cobalmoon" = added through Cobalmoon to an in-place installation.
    pub location: &'static str,
    pub removable: bool,
    pub enabled: bool,
    pub fabric: bool,
}

fn jars_in(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = std::fs::read_dir(dir)
        .map(|r| {
            r.filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| {
                    let n = p.file_name().map(|n| n.to_string_lossy().to_lowercase()).unwrap_or_default();
                    p.is_file() && (n.ends_with(".jar") || n.ends_with(".jar.disabled"))
                })
                .collect()
        })
        .unwrap_or_default();
    v.sort();
    v
}

fn entry(path: &Path, location: &'static str, removable: bool) -> ModEntry {
    let info = importers::read_mod_jar(path).ok();
    let file_name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    ModEntry {
        enabled: !file_name.to_lowercase().ends_with(".disabled"),
        file_name,
        path: path.display().to_string(),
        id: info.as_ref().and_then(|i| i.id.clone()),
        name: info.as_ref().and_then(|i| i.name.clone()),
        version: info.as_ref().and_then(|i| i.version.clone()),
        location,
        removable,
        fabric: info.is_some_and(|i| i.loader == importers::JarLoader::Fabric),
    }
}

pub fn list(inst: &Instance, paths: &Paths) -> Vec<ModEntry> {
    let mut out: Vec<ModEntry> = jars_in(&inst.game_dir(paths).join("mods")).iter().map(|p| entry(p, "instance", inst.managed)).collect();
    if !inst.managed {
        out.extend(jars_in(&paths.extra_mods(&inst.id)).iter().map(|p| entry(p, "cobalmoon", true)));
    }
    out
}

/// Copies any .jar the player picks into the instance. No allow/deny lists: the player decides.
/// Returns the stored file name and a note if it does not look like a Fabric mod.
pub fn add_jar(inst: &Instance, paths: &Paths, src: &Path) -> Result<(String, Option<String>)> {
    let name = src.file_name().map(|n| n.to_string_lossy().into_owned()).ok_or_else(|| Error::msg("invalid file"))?;
    if !name.to_lowercase().ends_with(".jar") {
        return Err(Error::msg("Only .jar files can be added as mods."));
    }
    let info = importers::read_mod_jar(src)?;
    let target_dir = inst.mods_target(paths);
    std::fs::create_dir_all(&target_dir)?;
    let mut target = target_dir.join(&name);
    let mut n = 2;
    while target.exists() {
        target = target_dir.join(format!("{}-{n}.jar", name.trim_end_matches(".jar")));
        n += 1;
    }
    std::fs::copy(src, &target)?;
    let note = match info.loader {
        importers::JarLoader::Fabric => None,
        importers::JarLoader::Quilt => Some("This is a Quilt mod; Fabric may not load it.".to_string()),
        importers::JarLoader::Forge | importers::JarLoader::NeoForge => Some("This is a Forge/NeoForge mod; Fabric will ignore it.".to_string()),
        importers::JarLoader::None => Some("This jar has no Fabric metadata; Fabric will ignore it unless it is a library.".to_string()),
    };
    Ok((target.file_name().unwrap().to_string_lossy().into_owned(), note))
}

/// Deletes a mod file, but only from the folder Cobalmoon manages for this instance.
pub fn remove(inst: &Instance, paths: &Paths, file: &Path) -> Result<()> {
    let allowed = inst.mods_target(paths);
    let file_c = file.canonicalize().map_err(|_| Error::msg("mod file not found"))?;
    let dir_c = allowed.canonicalize().map_err(|_| Error::msg("mod file not found"))?;
    if file_c.parent() != Some(dir_c.as_path()) {
        return Err(Error::msg("Cobalmoon only removes mods it manages; this file belongs to your original installation."));
    }
    std::fs::remove_file(file_c)?;
    Ok(())
}

/// Creates an instance from a detected installation, in place or as a copy.
pub fn import(det: &Detected, mc_override: Option<String>, copy: bool, paths: &Paths, existing: &[Instance]) -> Result<Instance> {
    let mc = mc_override.filter(|v| !v.trim().is_empty()).or_else(|| det.mc_version.clone()).ok_or_else(|| {
        Error::msg("Choose the Minecraft version of this installation; it could not be detected.")
    })?;
    let taken: Vec<String> = existing.iter().map(|i| i.id.clone()).collect();
    let id = slug(&det.name, &taken);
    let unsupported = match &det.loader {
        Loader::Forge(v) => Some(format!("Forge {}", v.clone().unwrap_or_default()).trim().to_string()),
        Loader::NeoForge(v) => Some(format!("NeoForge {}", v.clone().unwrap_or_default()).trim().to_string()),
        _ => None,
    };
    let game_dir = if copy {
        let dest = paths.instances().join(&id);
        copy_dir(&det.game_dir, &dest)?;
        None
    } else {
        Some(det.game_dir.display().to_string())
    };
    Ok(Instance {
        id,
        name: det.name.clone(),
        mc_version: mc,
        loader_version: det.loader.fabric_version(),
        cobalmoon: true,
        game_dir,
        memory_mb: None,
        jvm_args: Vec::new(),
        server: None,
        source: Some(ImportSource { kind: det.kind, path: det.game_dir.display().to_string() }),
        managed: copy,
        unsupported_loader: unsupported,
    })
}

/// Recursive copy, skipping logs and crash reports. Refuses to copy into itself.
pub fn copy_dir(src: &Path, dest: &Path) -> Result<()> {
    const SKIP: &[&str] = &["logs", "crash-reports", ".cache"];
    let src_c = src.canonicalize()?;
    std::fs::create_dir_all(dest)?;
    if dest.canonicalize()?.starts_with(&src_c) {
        return Err(Error::msg("cannot copy an installation into itself"));
    }
    for e in std::fs::read_dir(src)? {
        let e = e?;
        let name = e.file_name();
        if SKIP.iter().any(|s| name == *s) {
            continue;
        }
        let ty = e.file_type()?;
        let to = dest.join(&name);
        if ty.is_dir() {
            copy_dir(&e.path(), &to)?;
        } else if ty.is_file() {
            std::fs::copy(e.path(), to)?;
        }
    }
    Ok(())
}

/// SHA-512 of every jar of the instance (to recognise what is already installed on Modrinth).
pub fn hashes(inst: &Instance, paths: &Paths) -> Vec<String> {
    list(inst, paths)
        .iter()
        .filter(|m| m.enabled)
        .filter_map(|m| std::fs::read(&m.path).ok())
        .map(|b| crate::download::sha512_hex(&b))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::importers::SourceKind;

    fn jar(path: &Path, fmj: &str) {
        use std::io::Write;
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let mut z = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        z.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
        z.write_all(fmj.as_bytes()).unwrap();
        z.finish().unwrap();
    }

    fn detected(dir: &Path, loader: Loader) -> Detected {
        Detected { kind: SourceKind::Folder, name: "My Pack".into(), game_dir: dir.into(), mc_version: Some("26.3".into()), loader, mod_count: 0 }
    }

    #[test]
    fn in_place_import_never_writes_to_the_original() {
        let tmp = tempfile::tempdir().unwrap();
        let original = tmp.path().join("original");
        jar(&original.join("mods/theirs.jar"), r#"{"id":"theirs","version":"1"}"#);
        let paths = Paths::new(tmp.path().join("data"));

        let inst = import(&detected(&original, Loader::Fabric(Some("0.20.1".into()))), None, false, &paths, &[]).unwrap();
        assert!(!inst.managed);
        assert_eq!(inst.loader_version.as_deref(), Some("0.20.1"));
        assert_eq!(inst.game_dir(&paths), original);

        // Adding a jar goes to Cobalmoon's own folder, not to theirs.
        let src = tmp.path().join("new.jar");
        jar(&src, r#"{"id":"new","version":"2"}"#);
        let (name, note) = add_jar(&inst, &paths, &src).unwrap();
        assert_eq!(name, "new.jar");
        assert!(note.is_none());
        assert!(!original.join("mods/new.jar").exists());
        assert!(paths.extra_mods(&inst.id).join("new.jar").exists());

        let mods = list(&inst, &paths);
        assert_eq!(mods.len(), 2);
        let theirs = mods.iter().find(|m| m.id.as_deref() == Some("theirs")).unwrap();
        assert!(!theirs.removable);
        assert!(remove(&inst, &paths, Path::new(&theirs.path)).is_err());
        assert!(original.join("mods/theirs.jar").exists());

        let ours = mods.iter().find(|m| m.id.as_deref() == Some("new")).unwrap();
        remove(&inst, &paths, Path::new(&ours.path)).unwrap();
        assert_eq!(list(&inst, &paths).len(), 1);
    }

    #[test]
    fn copy_import_is_managed_and_skips_logs() {
        let tmp = tempfile::tempdir().unwrap();
        let original = tmp.path().join("original");
        jar(&original.join("mods/a.jar"), r#"{"id":"a"}"#);
        std::fs::create_dir_all(original.join("logs")).unwrap();
        std::fs::write(original.join("logs/latest.log"), "x").unwrap();
        std::fs::create_dir_all(original.join("saves/World")).unwrap();
        std::fs::write(original.join("saves/World/level.dat"), "x").unwrap();
        let paths = Paths::new(tmp.path().join("data"));

        let inst = import(&detected(&original, Loader::Fabric(None)), None, true, &paths, &[]).unwrap();
        assert!(inst.managed);
        let dir = inst.game_dir(&paths);
        assert!(dir.join("mods/a.jar").exists());
        assert!(dir.join("saves/World/level.dat").exists());
        assert!(!dir.join("logs").exists());
        // Managed: its mods can be removed.
        assert!(list(&inst, &paths)[0].removable);
    }

    #[test]
    fn forge_imports_are_marked_unsupported_and_version_can_be_chosen() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = Paths::new(tmp.path().join("data"));
        let mut d = detected(tmp.path(), Loader::NeoForge(Some("21.1.218".into())));
        d.mc_version = None;
        assert!(import(&d, None, false, &paths, &[]).is_err(), "version unknown and not chosen");
        let inst = import(&d, Some("1.21.1".into()), false, &paths, &[]).unwrap();
        assert_eq!(inst.unsupported_loader.as_deref(), Some("NeoForge 21.1.218"));
        assert_eq!(inst.mc_version, "1.21.1");
    }

    #[test]
    fn non_jar_and_name_clashes() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = Paths::new(tmp.path().join("data"));
        std::fs::create_dir_all(tmp.path().join("g")).unwrap();
        let inst = import(&detected(&tmp.path().join("g"), Loader::Fabric(None)), None, true, &paths, &[]).unwrap();
        let txt = tmp.path().join("readme.txt");
        std::fs::write(&txt, "x").unwrap();
        assert!(add_jar(&inst, &paths, &txt).is_err());
        let src = tmp.path().join("m.jar");
        jar(&src, r#"{"id":"m"}"#);
        assert_eq!(add_jar(&inst, &paths, &src).unwrap().0, "m.jar");
        assert_eq!(add_jar(&inst, &paths, &src).unwrap().0, "m-2.jar");
    }
}
