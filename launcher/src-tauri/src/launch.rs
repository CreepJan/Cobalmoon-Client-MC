//! Resolves, downloads and starts an instance: vanilla + Fabric + the Cobalmoon core mod.
//!
//! The core mod is added with `-Dfabric.addMods=<jar>`, so the instance's own `mods/` folder is never
//! touched: imported installations keep working exactly as before, with Cobalmoon on top.

use crate::auth::Session;
use crate::download::{fetch, fetch_all, get_json, Download};
use crate::error::{Error, Result};
use crate::meta::{self, ArgValue, Argument, AssetIndex, VersionJson};
use crate::rules::{allowed, Env};
use crate::store::{Instance, Paths, SUPPORTED_VERSIONS};
use crate::{fabric, java};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    pub stage: &'static str,
    pub done: usize,
    pub total: usize,
}

pub struct Prepared {
    pub java: PathBuf,
    pub args: Vec<String>,
    pub game_dir: PathBuf,
    pub warnings: Vec<String>,
}

pub struct LaunchContext<'a> {
    pub http: &'a reqwest::Client,
    pub paths: &'a Paths,
    pub client_id: &'a str,
    /// Directory holding cobalmoon-core-mc<version>.jar.
    pub core_mods_dir: Option<PathBuf>,
}

pub fn core_mod_jar(dir: &Path, mc_version: &str) -> Option<PathBuf> {
    let prefix = format!("cobalmoon-core-mc{mc_version}-");
    let mut found: Vec<PathBuf> = std::fs::read_dir(dir)
        .ok()?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            let n = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
            n.starts_with(&prefix) && n.ends_with(".jar") && !n.contains("-sources")
        })
        .collect();
    found.sort();
    found.pop()
}

pub async fn prepare(ctx: &LaunchContext<'_>, inst: &Instance, session: &Session, progress: impl Fn(Progress)) -> Result<Prepared> {
    let env = Env::current();
    let p = ctx.paths;
    let mut warnings = Vec::new();

    progress(Progress { stage: "metadata", done: 0, total: 1 });
    let manifest = meta::fetch_manifest(ctx.http).await?;
    let vanilla = meta::fetch_version(ctx.http, &manifest, &inst.mc_version).await?;

    let loader = match &inst.loader_version {
        Some(v) if !v.is_empty() => v.clone(),
        _ => fabric::latest_stable_loader(ctx.http, &inst.mc_version).await?,
    };
    let profile = fabric::profile(ctx.http, &inst.mc_version, &loader).await?;
    let version = meta::merge(vanilla.clone(), profile);

    // Cache the resolved JSON next to the client jar for debugging and offline inspection.
    let version_dir = p.versions().join(&inst.mc_version);
    tokio::fs::create_dir_all(&version_dir).await?;
    tokio::fs::write(version_dir.join(format!("{}.json", version.id)), serde_json::to_vec_pretty(&version)?).await?;

    // Client jar
    let client = vanilla.downloads.as_ref().ok_or_else(|| Error::msg("version has no client download"))?;
    let client_jar = version_dir.join(format!("{}.jar", inst.mc_version));
    progress(Progress { stage: "client", done: 0, total: 1 });
    fetch(ctx.http, &Download { url: client.client.url.clone(), path: client_jar.clone(), sha1: Some(client.client.sha1.clone()), size: Some(client.client.size) }).await?;

    // Libraries
    let mut libs = meta::library_downloads(&version, &p.libraries(), &env)?;
    meta::fill_missing_hashes(ctx.http, &mut libs).await?;
    fetch_all(ctx.http, libs, 12, |d, t| progress(Progress { stage: "libraries", done: d, total: t })).await?;

    // Assets
    let index_ref = version.asset_index.clone().ok_or_else(|| Error::msg("version has no asset index"))?;
    let index_path = p.assets().join("indexes").join(format!("{}.json", index_ref.id));
    fetch(ctx.http, &Download { url: index_ref.url.clone(), path: index_path.clone(), sha1: Some(index_ref.sha1.clone()), size: Some(index_ref.size) }).await?;
    let index: AssetIndex = serde_json::from_slice(&tokio::fs::read(&index_path).await?)?;
    fetch_all(ctx.http, meta::asset_downloads(&index, &p.assets()), 24, |d, t| progress(Progress { stage: "assets", done: d, total: t })).await?;

    // Java
    let component = version.java_version.as_ref().map(|j| j.component.clone()).unwrap_or_else(|| "java-runtime-delta".into());
    let java = java::ensure_runtime(ctx.http, &p.runtimes(), &component, |d, t| progress(Progress { stage: "java", done: d, total: t })).await?;

    // Game directory and natives dir
    let game_dir = inst.game_dir(p);
    tokio::fs::create_dir_all(&game_dir).await?;
    let natives = p.natives(&version.id);
    tokio::fs::create_dir_all(&natives).await?;

    // Cobalmoon core mod
    let mut extra_jvm = Vec::new();
    if inst.cobalmoon {
        if !SUPPORTED_VERSIONS.contains(&inst.mc_version.as_str()) {
            warnings.push(format!("Cobalmoon core is not built for {} yet; launching Fabric without it.", inst.mc_version));
        } else {
            match ctx.core_mods_dir.as_deref().and_then(|d| core_mod_jar(d, &inst.mc_version)) {
                Some(jar) => extra_jvm.push(format!("-Dfabric.addMods={}", jar.display())),
                None => warnings.push(format!(
                    "Cobalmoon core jar for {} not found; launching Fabric without it. Build it with `gradlew build` or set the folder in Settings.",
                    inst.mc_version
                )),
            }
        }
    }
    let memory = inst.memory_mb.unwrap_or(4096).max(1024);
    extra_jvm.push(format!("-Xmx{memory}M"));
    extra_jvm.push("-Xms512M".into());
    extra_jvm.extend(inst.jvm_args.iter().cloned());

    let cp = meta::classpath(&version, &p.libraries(), &client_jar, &env)?;
    let sep = if cfg!(windows) { ";" } else { ":" };
    let vars: HashMap<&str, String> = HashMap::from([
        ("auth_player_name", session.info.name.clone()),
        ("version_name", version.id.clone()),
        ("game_directory", game_dir.display().to_string()),
        ("assets_root", p.assets().display().to_string()),
        ("assets_index_name", index_ref.id.clone()),
        ("auth_uuid", session.info.uuid.clone()),
        ("auth_access_token", session.access_token.clone()),
        ("auth_session", session.access_token.clone()),
        ("clientid", ctx.client_id.to_string()),
        ("auth_xuid", session.info.xuid.clone()),
        ("user_type", "msa".into()),
        ("user_properties", "{}".into()),
        ("version_type", version.kind.clone().unwrap_or_else(|| "release".into())),
        ("natives_directory", natives.display().to_string()),
        ("launcher_name", "cobalmoon".into()),
        ("launcher_version", env!("CARGO_PKG_VERSION").into()),
        ("classpath", cp.join(sep)),
        ("classpath_separator", sep.into()),
        ("library_directory", p.libraries().display().to_string()),
    ]);

    let mut args = extra_jvm;
    args.extend(build_jvm_args(&version, &env, &vars));
    args.push(version.main_class.clone());
    args.extend(build_game_args(&version, &env, &vars));
    if let Some(server) = inst.server.as_ref().filter(|s| !s.trim().is_empty()) {
        args.push("--quickPlayMultiplayer".into());
        args.push(server.trim().to_string());
    }
    progress(Progress { stage: "starting", done: 1, total: 1 });
    Ok(Prepared { java, args, game_dir, warnings })
}

fn expand(arg: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = String::with_capacity(arg.len());
    let mut rest = arg;
    while let Some(start) = rest.find("${") {
        out.push_str(&rest[..start]);
        match rest[start..].find('}') {
            Some(end) => {
                let key = &rest[start + 2..start + end];
                match vars.get(key) {
                    Some(v) => out.push_str(v),
                    None => out.push_str(&rest[start..start + end + 1]),
                }
                rest = &rest[start + end + 1..];
            }
            None => {
                out.push_str(&rest[start..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

fn collect(list: &[Argument], env: &Env, vars: &HashMap<&str, String>) -> Vec<String> {
    let mut out = Vec::new();
    for a in list {
        match a {
            Argument::Plain(s) => out.push(expand(s, vars)),
            Argument::Ruled { rules, value } if allowed(rules, env) => match value {
                ArgValue::One(s) => out.push(expand(s, vars)),
                ArgValue::Many(v) => out.extend(v.iter().map(|s| expand(s, vars))),
            },
            _ => {}
        }
    }
    out
}

pub fn build_jvm_args(v: &VersionJson, env: &Env, vars: &HashMap<&str, String>) -> Vec<String> {
    match &v.arguments {
        Some(a) if !a.jvm.is_empty() => collect(&a.jvm, env, vars),
        _ => vec![
            expand("-Djava.library.path=${natives_directory}", vars),
            "-cp".into(),
            expand("${classpath}", vars),
        ],
    }
}

pub fn build_game_args(v: &VersionJson, env: &Env, vars: &HashMap<&str, String>) -> Vec<String> {
    match (&v.arguments, &v.minecraft_arguments) {
        (Some(a), _) if !a.game.is_empty() => collect(&a.game, env, vars),
        (_, Some(legacy)) => legacy.split_whitespace().map(|s| expand(s, vars)).collect(),
        _ => Vec::new(),
    }
}

/// Copy of the command line safe to show in logs: the access token is masked.
pub fn redacted(args: &[String], token: &str) -> String {
    args.iter()
        .map(|a| if !token.is_empty() && a.contains(token) { a.replace(token, "<token>") } else { a.clone() })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Fetches a version JSON from Mojang; exposed for the version picker.
pub async fn release_versions(http: &reqwest::Client) -> Result<Vec<(String, bool)>> {
    let m: meta::VersionManifest = get_json(http, meta::VERSION_MANIFEST, None).await?;
    Ok(m.versions
        .into_iter()
        .filter(|v| v.kind == "release")
        .map(|v| {
            let supported = SUPPORTED_VERSIONS.contains(&v.id.as_str());
            (v.id, supported)
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::meta::Arguments;

    fn vars() -> HashMap<&'static str, String> {
        HashMap::from([("auth_player_name", "Steve".to_string()), ("classpath", "a.jar;b.jar".to_string())])
    }

    #[test]
    fn expands_known_and_keeps_unknown() {
        let v = vars();
        assert_eq!(expand("--username=${auth_player_name}", &v), "--username=Steve");
        assert_eq!(expand("${quickPlayPath}", &v), "${quickPlayPath}");
        assert_eq!(expand("${unterminated", &v), "${unterminated");
        assert_eq!(expand("plain", &v), "plain");
    }

    #[test]
    fn ruled_args_respect_features() {
        let a: Arguments = serde_json::from_str(
            r#"{"game":["--username","${auth_player_name}",{"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}],
                "jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},"-cp","${classpath}"]}"#,
        )
        .unwrap();
        let v = VersionJson { arguments: Some(a), ..Default::default() };
        let env = Env { os: "windows", arch: "x86_64", features: Default::default() };
        assert_eq!(build_game_args(&v, &env, &vars()), vec!["--username", "Steve"]);
        assert_eq!(build_jvm_args(&v, &env, &vars()), vec!["-cp", "a.jar;b.jar"]);
    }

    #[test]
    fn legacy_arguments() {
        let v = VersionJson { minecraft_arguments: Some("--username ${auth_player_name}".into()), ..Default::default() };
        let env = Env::current();
        assert_eq!(build_game_args(&v, &env, &vars()), vec!["--username", "Steve"]);
        assert_eq!(build_jvm_args(&v, &env, &vars())[1], "-cp");
    }

    #[test]
    fn token_is_redacted() {
        let args = vec!["--accessToken".to_string(), "secret123".to_string()];
        assert_eq!(redacted(&args, "secret123"), "--accessToken <token>");
    }

    #[test]
    fn finds_newest_core_jar() {
        let dir = tempfile::tempdir().unwrap();
        for n in ["cobalmoon-core-mc26.3-0.1.0.jar", "cobalmoon-core-mc26.3-0.2.0.jar", "cobalmoon-core-mc26.3-0.2.0-sources.jar", "cobalmoon-core-mc1.21.11-0.2.0.jar"] {
            std::fs::write(dir.path().join(n), b"").unwrap();
        }
        let jar = core_mod_jar(dir.path(), "26.3").unwrap();
        assert!(jar.ends_with("cobalmoon-core-mc26.3-0.2.0.jar"));
        assert!(core_mod_jar(dir.path(), "1.20.1").is_none());
    }
}
