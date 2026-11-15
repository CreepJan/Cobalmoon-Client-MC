//! Network smoke test of the real launch pipeline: Mojang manifest, Fabric profile, client jar,
//! libraries, assets and Java runtime are downloaded and hash-verified, and the command line is built.
//! It does not sign in and does not start the game (the session below is a placeholder that is never
//! sent anywhere). Run with:  cargo test --test launch_smoke -- --ignored --nocapture
//! Set COBALMOON_SMOKE_VERSION to test another version (default 26.3).

use cobalmoon_launcher_lib::auth::{AccountInfo, Session};
use cobalmoon_launcher_lib::download::http_client;
use cobalmoon_launcher_lib::launch::{prepare, LaunchContext};
use cobalmoon_launcher_lib::store::{Instance, Paths};
use std::path::PathBuf;

#[tokio::test(flavor = "multi_thread")]
#[ignore = "downloads ~500 MB from Mojang/Fabric; run explicitly"]
async fn prepares_a_fabric_instance_with_cobalmoon() {
    let version = std::env::var("COBALMOON_SMOKE_VERSION").unwrap_or_else(|_| "26.3".into());
    let root = std::env::var("COBALMOON_SMOKE_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::temp_dir().join("cobalmoon-smoke"));
    let repo = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let core_dir = repo.join(format!("client-core/versions/{version}/build/libs"));

    let paths = Paths::new(root);
    let http = http_client();
    let ctx = LaunchContext { http: &http, paths: &paths, client_id: "smoke-test", core_mods_dir: Some(core_dir) };

    // COBALMOON_SMOKE_IMPORT=<folder>: test a real existing installation instead. It is imported as a
    // *copy* (the original is only read), then launched with Cobalmoon on top of its mods.
    if let Ok(src) = std::env::var("COBALMOON_SMOKE_IMPORT") {
        let det = cobalmoon_launcher_lib::importers::detect_folder(std::path::Path::new(&src)).expect("detect");
        eprintln!("importing copy of {} ({:?}, {:?}, {} mods)", det.name, det.mc_version, det.loader, det.mod_count);
        let inst = cobalmoon_launcher_lib::mods::import(&det, None, true, &paths, &[]).expect("import copy");
        let core = repo.join(format!("client-core/versions/{}/build/libs", inst.mc_version));
        let ctx = LaunchContext { core_mods_dir: Some(core), ..ctx };
        run_instance(&ctx, &inst).await;
        return;
    }

    let inst = Instance {
        id: format!("smoke-{version}"),
        name: "Smoke".into(),
        mc_version: version.clone(),
        loader_version: None,
        cobalmoon: true,
        game_dir: None,
        memory_mb: Some(2048),
        jvm_args: vec![],
        server: None,
        source: None,
        managed: true,
        unsupported_loader: None,
    };
    run_instance(&ctx, &inst).await;
}

/// Prepares (downloads, verifies) and, with COBALMOON_SMOKE_RUN, starts the instance.
async fn run_instance(ctx: &LaunchContext<'_>, inst: &Instance) {
    let session = Session {
        info: AccountInfo { uuid: "00000000000000000000000000000000".into(), name: "Smoke".into(), xuid: String::new() },
        access_token: "placeholder".into(),
        expires_at: u64::MAX,
    };

    let prepared = prepare(ctx, inst, &session, |p| eprintln!("{:<10} {}/{}", p.stage, p.done, p.total)).await.expect("prepare");

    assert!(prepared.java.exists(), "java runtime missing");
    let out = std::process::Command::new(&prepared.java).arg("-version").output().expect("run java");
    assert!(out.status.success());
    eprintln!("{}", String::from_utf8_lossy(&out.stderr));

    for w in &prepared.warnings {
        eprintln!("launcher notice: {w}");
    }
    assert!(!prepared.warnings.iter().any(|w| w.contains("core")), "Cobalmoon core missing: {:?}", prepared.warnings);
    assert!(prepared.args.iter().any(|a| a.starts_with("-Dfabric.addMods=") && a.contains("cobalmoon-core-mc")));
    assert!(prepared.args.iter().any(|a| a == "net.fabricmc.loader.impl.launch.knot.KnotClient"));
    let cp_idx = prepared.args.iter().position(|a| a == "-cp").expect("-cp");
    let sep = if cfg!(windows) { ';' } else { ':' };
    for entry in prepared.args[cp_idx + 1].split(sep) {
        assert!(PathBuf::from(entry).exists(), "missing classpath entry {entry}");
    }

    // Optional: actually start the game with the launcher's command line and wait until the
    // Cobalmoon core reports it is ready, then close it. Uses the placeholder session (no sign-in),
    // exactly like Loom's dev `runClient`; online features are unavailable in this mode.
    if std::env::var("COBALMOON_SMOKE_RUN").is_ok() {
        use std::io::{BufRead, BufReader};
        let mut child = std::process::Command::new(&prepared.java)
            .args(&prepared.args)
            .current_dir(&prepared.game_dir)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .spawn()
            .expect("spawn game");
        let stdout = child.stdout.take().unwrap();
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                if line.contains("Cobalmoon ready") {
                    let _ = tx.send(line);
                    return;
                }
            }
        });
        let ready = rx.recv_timeout(std::time::Duration::from_secs(300));
        let _ = child.kill();
        let _ = child.wait();
        let line = ready.expect("game did not report 'Cobalmoon ready' within 5 minutes");
        eprintln!("game started: {line}");
    }
}
