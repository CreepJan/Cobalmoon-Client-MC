//! Cobalmoon Client launcher — Tauri backend.

pub mod auth;
pub mod download;
pub mod error;
pub mod fabric;
pub mod importers;
pub mod java;
pub mod launch;
pub mod meta;
pub mod modrinth;
pub mod mods;
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
        source: None,
        managed: true,
        unsupported_loader: None,
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

// ---- Phase 3: imports, mods, Modrinth ------------------------------------------------------

fn find_instance(state: &AppState, id: &str) -> Result<Instance> {
    store::load_instances(&state.paths).into_iter().find(|i| i.id == id).ok_or_else(|| Error::msg("profile not found"))
}

fn add_instance(state: &AppState, inst: Instance) -> Result<Instance> {
    let mut all = store::load_instances(&state.paths);
    all.push(inst.clone());
    store::save_instances(&state.paths, &all)?;
    Ok(inst)
}

#[tauri::command]
async fn detect_installations() -> Vec<importers::Detected> {
    tauri::async_runtime::spawn_blocking(|| importers::detect_all(&importers::Roots::for_this_os())).await.unwrap_or_default()
}

#[tauri::command]
fn inspect_folder(path: String) -> Result<importers::Detected> {
    importers::detect_folder(std::path::Path::new(&path))
}

#[tauri::command]
async fn import_installation(state: State<'_, AppState>, detected: importers::Detected, mc_version: Option<String>, copy: bool) -> Result<Instance> {
    let paths = state.paths.clone();
    let existing = store::load_instances(&paths);
    let inst = tauri::async_runtime::spawn_blocking(move || mods::import(&detected, mc_version, copy, &paths, &existing))
        .await
        .map_err(|e| Error::msg(e.to_string()))??;
    add_instance(&state, inst)
}

#[derive(serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct ImportProgress {
    done: usize,
    total: usize,
}

#[tauri::command]
async fn import_mrpack(app: AppHandle, state: State<'_, AppState>, path: String) -> Result<Instance> {
    let pack = PathBuf::from(&path);
    let index = modrinth::read_mrpack_index(&pack)?;
    let info = modrinth::mrpack_info(&index)?;
    if let Some(other) = &info.other_loader {
        return Err(Error::msg(format!("This modpack needs {other}; Cobalmoon runs on Fabric.")));
    }
    let existing = store::load_instances(&state.paths);
    let taken: Vec<String> = existing.iter().map(|i| i.id.clone()).collect();
    let id = store::slug(&info.name, &taken);
    let game_dir = state.paths.instances().join(&id);
    modrinth::install_mrpack(&state.http, &pack, &game_dir, |done, total| {
        let _ = app.emit("import-progress", ImportProgress { done, total });
    })
    .await?;
    add_instance(
        &state,
        Instance {
            id,
            name: info.name,
            mc_version: info.mc_version,
            loader_version: info.fabric_loader,
            cobalmoon: true,
            game_dir: None,
            memory_mb: None,
            jvm_args: Vec::new(),
            server: None,
            source: Some(store::ImportSource { kind: importers::SourceKind::Modrinth, path }),
            managed: true,
            unsupported_loader: None,
        },
    )
}

#[tauri::command]
fn list_mods(state: State<'_, AppState>, instance_id: String) -> Result<Vec<mods::ModEntry>> {
    Ok(mods::list(&find_instance(&state, &instance_id)?, &state.paths))
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct AddJarResult {
    file_name: String,
    note: Option<String>,
}

#[tauri::command]
fn add_jar(state: State<'_, AppState>, instance_id: String, path: String) -> Result<AddJarResult> {
    let (file_name, note) = mods::add_jar(&find_instance(&state, &instance_id)?, &state.paths, std::path::Path::new(&path))?;
    Ok(AddJarResult { file_name, note })
}

#[tauri::command]
fn remove_mod(state: State<'_, AppState>, instance_id: String, path: String) -> Result<()> {
    mods::remove(&find_instance(&state, &instance_id)?, &state.paths, std::path::Path::new(&path))
}

#[tauri::command]
async fn modrinth_search(state: State<'_, AppState>, query: String, mc_version: String, offset: u32) -> Result<Vec<modrinth::SearchHit>> {
    modrinth::Modrinth::new(state.http.clone()).search(&query, &mc_version, offset).await
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct InstallReport {
    installed: Vec<String>,
    warnings: Vec<String>,
}

/// Installs Modrinth projects (ids or slugs) and their required dependencies into the instance.
/// `projects == ["@pvp-preset"]` installs the PvP performance preset.
#[tauri::command]
async fn modrinth_install(state: State<'_, AppState>, instance_id: String, projects: Vec<String>) -> Result<InstallReport> {
    let inst = find_instance(&state, &instance_id)?;
    if let Some(loader) = &inst.unsupported_loader {
        return Err(Error::msg(format!("This installation uses {loader}; Fabric mods would not load.")));
    }
    let wanted: Vec<String> = if projects.iter().any(|p| p == "@pvp-preset") {
        modrinth::PVP_PRESET.iter().map(|s| s.to_string()).collect()
    } else {
        projects
    };
    let api = modrinth::Modrinth::new(state.http.clone());
    let paths = state.paths.clone();
    let inst2 = inst.clone();
    let hashes = tauri::async_runtime::spawn_blocking(move || mods::hashes(&inst2, &paths)).await.map_err(|e| Error::msg(e.to_string()))?;
    let installed: HashMap<String, modrinth::Version> = api.identify(&hashes).await?.into_values().map(|v| (v.project_id.clone(), v)).collect();
    let plan = modrinth::resolve(&api, &wanted, &inst.mc_version, &installed).await?;
    let names = modrinth::install(&state.http, &plan, &inst.mods_target(&state.paths)).await?;
    Ok(InstallReport { installed: names, warnings: plan.warnings })
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
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
            launch_instance,
            detect_installations,
            inspect_folder,
            import_installation,
            import_mrpack,
            list_mods,
            add_jar,
            remove_mod,
            modrinth_search,
            modrinth_install
        ])
        .run(tauri::generate_context!())
        .expect("error while running Cobalmoon launcher");
}
