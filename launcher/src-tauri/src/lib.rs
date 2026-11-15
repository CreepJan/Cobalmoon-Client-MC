//! Cobalmoon Client launcher — Tauri backend.

pub mod auth;
pub mod download;
pub mod error;
pub mod fabric;
pub mod java;
pub mod launch;
pub mod meta;
pub mod rules;
pub mod store;

use auth::{AccountInfo, Authenticator, Session};
use error::{Error, Result};
use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Stdio;
use store::{Instance, Paths, Settings};
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::Mutex;

pub struct AppState {
    paths: Paths,
    http: reqwest::Client,
    /// Minecraft sessions by account uuid — memory only.
    sessions: Mutex<HashMap<String, Session>>,
    /// Ids of instances currently running.
    running: Mutex<Vec<String>>,
}

#[derive(serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct VersionOption {
    id: String,
    cobalmoon: bool,
}

#[derive(serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct GameLog {
    instance_id: String,
    line: String,
    stream: &'static str,
}

#[derive(serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct LaunchEvent {
    instance_id: String,
    #[serde(flatten)]
    progress: launch::Progress,
}

#[derive(serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct ExitEvent {
    instance_id: String,
    code: Option<i32>,
    error: Option<String>,
}

#[tauri::command]
fn get_settings(state: State<'_, AppState>) -> Settings {
    store::load_settings(&state.paths)
}

#[tauri::command]
fn save_settings(state: State<'_, AppState>, settings: Settings) -> Result<Settings> {
    store::save_settings(&state.paths, &settings)?;
    Ok(store::load_settings(&state.paths))
}

#[tauri::command]
fn list_accounts(state: State<'_, AppState>) -> Vec<AccountInfo> {
    store::load_accounts(&state.paths)
}

#[tauri::command]
async fn login(app: AppHandle, state: State<'_, AppState>) -> Result<AccountInfo> {
    let settings = store::load_settings(&state.paths);
    let auth = Authenticator::new(state.http.clone(), settings.client_id)?;
    let (session, refresh) = auth
        .login_interactive(|url| {
            tauri_plugin_opener::open_url(url, None::<&str>).map_err(|e| Error::msg(format!("could not open the browser: {e}")))
        })
        .await?;
    let _ = app; // window focus could be restored here in a later phase
    auth::store_refresh_token(&session.info.uuid, &refresh)?;
    let mut accounts = store::load_accounts(&state.paths);
    accounts.retain(|a| a.uuid != session.info.uuid);
    accounts.insert(0, session.info.clone());
    store::save_accounts(&state.paths, &accounts)?;
    let info = session.info.clone();
    state.sessions.lock().await.insert(info.uuid.clone(), session);
    Ok(info)
}

#[tauri::command]
async fn logout(state: State<'_, AppState>, uuid: String) -> Result<()> {
    auth::delete_refresh_token(&uuid)?;
    state.sessions.lock().await.remove(&uuid);
    let mut accounts = store::load_accounts(&state.paths);
    accounts.retain(|a| a.uuid != uuid);
    store::save_accounts(&state.paths, &accounts)
}

/// A valid session for the account: cached in memory, or refreshed silently from the keyring.
async fn session_for(state: &AppState, uuid: &str) -> Result<Session> {
    if let Some(s) = state.sessions.lock().await.get(uuid).filter(|s| s.is_fresh()) {
        return Ok(s.clone());
    }
    let settings = store::load_settings(&state.paths);
    let auth = Authenticator::new(state.http.clone(), settings.client_id)?;
    let refresh = auth::load_refresh_token(uuid)?.ok_or_else(|| Error::Auth("Session expired: please sign in again.".into()))?;
    let (session, new_refresh) = auth.refresh(&refresh).await?;
    auth::store_refresh_token(uuid, &new_refresh)?;
    state.sessions.lock().await.insert(uuid.to_string(), session.clone());
    Ok(session)
}

#[tauri::command]
async fn list_versions(state: State<'_, AppState>) -> Result<Vec<VersionOption>> {
    Ok(launch::release_versions(&state.http)
        .await?
        .into_iter()
        .map(|(id, cobalmoon)| VersionOption { id, cobalmoon })
        .collect())
}

#[tauri::command]
fn list_instances(state: State<'_, AppState>) -> Vec<Instance> {
    store::load_instances(&state.paths)
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct NewInstance {
    name: String,
    mc_version: String,
    #[serde(default)]
    server: Option<String>,
    #[serde(default)]
    game_dir: Option<String>,
}

#[tauri::command]
fn create_instance(state: State<'_, AppState>, req: NewInstance) -> Result<Instance> {
    let mut all = store::load_instances(&state.paths);
    let name = req.name.trim();
    if name.is_empty() {
        return Err(Error::msg("The profile needs a name."));
    }
    let taken: Vec<String> = all.iter().map(|i| i.id.clone()).collect();
    let inst = Instance {
        id: store::slug(name, &taken),
        name: name.to_string(),
        mc_version: req.mc_version,
        loader_version: None,
        cobalmoon: true,
        game_dir: req.game_dir.filter(|d| !d.trim().is_empty()),
        memory_mb: None,
        jvm_args: Vec::new(),
        server: req.server.filter(|s| !s.trim().is_empty()),
    };
    all.push(inst.clone());
    store::save_instances(&state.paths, &all)?;
    Ok(inst)
}

#[tauri::command]
fn update_instance(state: State<'_, AppState>, instance: Instance) -> Result<Instance> {
    let mut all = store::load_instances(&state.paths);
    let slot = all.iter_mut().find(|i| i.id == instance.id).ok_or_else(|| Error::msg("profile not found"))?;
    *slot = instance.clone();
    store::save_instances(&state.paths, &all)?;
    Ok(instance)
}

/// Removes the profile from the list. Its game folder (worlds, screenshots, mods) is left on disk.
#[tauri::command]
fn remove_instance(state: State<'_, AppState>, id: String) -> Result<()> {
    let mut all = store::load_instances(&state.paths);
    all.retain(|i| i.id != id);
    store::save_instances(&state.paths, &all)
}

fn core_mods_dir(app: &AppHandle, settings: &Settings) -> Option<PathBuf> {
    if !settings.core_mods_dir.trim().is_empty() {
        return Some(PathBuf::from(settings.core_mods_dir.trim()));
    }
    if let Ok(dir) = std::env::var("COBALMOON_CORE_MODS_DIR") {
        return Some(PathBuf::from(dir));
    }
    app.path().resource_dir().ok().map(|d| d.join("mods"))
}

#[tauri::command]
async fn launch_instance(app: AppHandle, state: State<'_, AppState>, instance_id: String, account_uuid: String) -> Result<()> {
    let inst = store::load_instances(&state.paths)
        .into_iter()
        .find(|i| i.id == instance_id)
        .ok_or_else(|| Error::msg("profile not found"))?;
    {
        let mut running = state.running.lock().await;
        if running.contains(&inst.id) {
            return Err(Error::msg("This profile is already running."));
        }
        running.push(inst.id.clone());
    }
    let result = launch_inner(&app, &state, &inst, &account_uuid).await;
    if result.is_err() {
        state.running.lock().await.retain(|i| i != &inst.id);
    }
    result
}

async fn launch_inner(app: &AppHandle, state: &AppState, inst: &Instance, account_uuid: &str) -> Result<()> {
    let session = session_for(state, account_uuid).await?;
    let settings = store::load_settings(&state.paths);
    let ctx = launch::LaunchContext {
        http: &state.http,
        paths: &state.paths,
        client_id: &settings.client_id,
        core_mods_dir: core_mods_dir(app, &settings),
    };
    let id = inst.id.clone();
    let emit_app = app.clone();
    let prepared = launch::prepare(&ctx, inst, &session, move |p| {
        let _ = emit_app.emit("launch-progress", LaunchEvent { instance_id: id.clone(), progress: p });
    })
    .await?;

    let log = |line: String, stream: &'static str| {
        let _ = app.emit("game-log", GameLog { instance_id: inst.id.clone(), line, stream });
    };
    for w in &prepared.warnings {
        log(format!("[Cobalmoon] {w}"), "launcher");
    }
    log(
        format!("[Cobalmoon] {} {}", prepared.java.display(), launch::redacted(&prepared.args, &session.access_token)),
        "launcher",
    );

    let mut cmd = tokio::process::Command::new(&prepared.java);
    cmd.args(&prepared.args)
        .current_dir(&prepared.game_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    let mut child = cmd.spawn()?;

    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let app2 = app.clone();
    let inst_id = inst.id.clone();
    let token = session.access_token.clone();
    tauri::async_runtime::spawn(async move {
        let pump = |reader: Option<_>, stream: &'static str| {
            let app = app2.clone();
            let id = inst_id.clone();
            let token = token.clone();
            async move {
                let Some(r) = reader else { return };
                let mut lines = BufReader::new(r).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    let line = if line.contains(&token) { line.replace(&token, "<token>") } else { line };
                    let _ = app.emit("game-log", GameLog { instance_id: id.clone(), line, stream });
                }
            }
        };
        let out = pump(stdout.map(|s| Box::new(s) as Box<dyn tokio::io::AsyncRead + Unpin + Send>), "stdout");
        let err = pump(stderr.map(|s| Box::new(s) as Box<dyn tokio::io::AsyncRead + Unpin + Send>), "stderr");
        let (status, _, _) = tokio::join!(child.wait(), out, err);
        let (code, error) = match status {
            Ok(s) => (s.code(), None),
            Err(e) => (None, Some(e.to_string())),
        };
        if let Some(state) = app2.try_state::<AppState>() {
            state.running.lock().await.retain(|i| i != &inst_id);
        }
        let _ = app2.emit("game-exit", ExitEvent { instance_id: inst_id, code, error });
    });
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let root = app.path().app_data_dir()?;
            std::fs::create_dir_all(&root)?;
            app.manage(AppState {
                paths: Paths::new(root),
                http: download::http_client(),
                sessions: Mutex::new(HashMap::new()),
                running: Mutex::new(Vec::new()),
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_settings,
            save_settings,
            list_accounts,
            login,
            logout,
            list_versions,
            list_instances,
            create_instance,
            update_instance,
            remove_instance,
            launch_instance
        ])
        .run(tauri::generate_context!())
        .expect("error while running Cobalmoon launcher");
}
