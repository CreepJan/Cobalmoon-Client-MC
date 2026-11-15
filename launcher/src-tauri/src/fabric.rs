//! Fabric Loader profiles from the official Fabric meta API.

use crate::download::get_json;
use crate::error::{Error, Result};
use crate::meta::VersionJson;
use serde::Deserialize;

pub const META: &str = "https://meta.fabricmc.net/v2";

#[derive(Debug, Deserialize)]
struct LoaderEntry {
    loader: LoaderVersion,
}

#[derive(Debug, Deserialize)]
struct LoaderVersion {
    version: String,
    stable: bool,
}

/// Latest stable Fabric Loader for a game version.
pub async fn latest_stable_loader(client: &reqwest::Client, game: &str) -> Result<String> {
    let entries: Vec<LoaderEntry> = get_json(client, &format!("{META}/versions/loader/{}", urlenc(game)), None).await?;
    entries
        .into_iter()
        .find(|e| e.loader.stable)
        .map(|e| e.loader.version)
        .ok_or_else(|| Error::msg(format!("Fabric has no stable loader for Minecraft {game} yet")))
}

/// Launcher profile (version JSON that inherits from vanilla) for a game + loader version.
pub async fn profile(client: &reqwest::Client, game: &str, loader: &str) -> Result<VersionJson> {
    get_json(client, &format!("{META}/versions/loader/{}/{}/profile/json", urlenc(game), urlenc(loader)), None).await
}

fn urlenc(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}
