//! Checks against real data, run explicitly:
//!   cargo test --test live -- --ignored --nocapture
//! - `detects_local_installations` only reads this machine's launcher folders.
//! - the Modrinth tests call the public API and download into a temp folder.

use cobalmoon_launcher_lib::download::http_client;
use cobalmoon_launcher_lib::importers::{detect_all, Roots};
use cobalmoon_launcher_lib::modrinth::{install, resolve, Modrinth, PVP_PRESET};
use std::collections::HashMap;

#[test]
#[ignore = "reads this machine's launcher folders"]
fn detects_local_installations() {
    let found = detect_all(&Roots::for_this_os());
    for d in &found {
        eprintln!("{:<10?} {:<40} mc={:<10} loader={:?} mods={}", d.kind, d.name.chars().take(40).collect::<String>(),
            d.mc_version.clone().unwrap_or_else(|| "?".into()), d.loader, d.mod_count);
    }
    eprintln!("{} installations", found.len());
}

#[tokio::test]
#[ignore = "calls the Modrinth API"]
async fn resolves_and_installs_the_pvp_preset() {
    let http = http_client();
    let api = Modrinth::new(http.clone());
    let wanted: Vec<String> = PVP_PRESET.iter().map(|s| s.to_string()).collect();
    for mc in ["26.3", "1.21.11"] {
        let plan = resolve(&api, &wanted, mc, &HashMap::new()).await.expect("resolve");
        let names: Vec<&str> = plan.install.iter().map(|v| v.version_number.as_str()).collect();
        eprintln!("{mc}: {names:?} warnings={:?}", plan.warnings);
        assert!(plan.install.len() >= PVP_PRESET.len());
        let dir = tempfile::tempdir().unwrap();
        let files = install(&http, &plan, dir.path()).await.expect("install (sha512 verified)");
        assert_eq!(files.len(), plan.install.len());
    }
}

#[tokio::test]
#[ignore = "calls the Modrinth API"]
async fn iris_gets_the_sodium_it_pins() {
    let api = Modrinth::new(http_client());
    let plan = resolve(&api, &["sodium".into(), "iris".into()], "26.3", &HashMap::new()).await.unwrap();
    let sodium = plan.install.iter().find(|v| v.project_id == "AANobbMI").expect("sodium planned");
    let iris = plan.install.iter().find(|v| v.project_id == "YL57xq9U").expect("iris planned");
    let pin = iris.dependencies.iter().find(|d| d.project_id.as_deref() == Some("AANobbMI")).and_then(|d| d.version_id.clone());
    eprintln!("iris {} pins sodium {:?}; planned sodium {}", iris.version_number, pin, sodium.version_number);
    if let Some(pin) = pin {
        assert_eq!(sodium.id, pin);
    }
}
