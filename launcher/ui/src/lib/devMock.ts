// Development-only stand-in for the Tauri backend, so the UI can be opened in a normal browser
// (`npm run dev`, then http://localhost:1420). Never included in the app: main.ts only loads it
// in dev mode when the Tauri runtime is absent. All data below is fictional sample data.
import type { Detected, Instance, ModEntry, SearchHit } from "./api";

const instances: Instance[] = [
  {
    id: "pvp-26-3", name: "PvP 26.3", mcVersion: "26.3", loaderVersion: null, cobalmoon: true, gameDir: null,
    memoryMb: null, jvmArgs: [], server: "pvp.example.net", source: null, managed: true, unsupportedLoader: null,
  },
  {
    id: "immersed", name: "Immersed With Shaders", mcVersion: "1.21.11", loaderVersion: "0.18.4", cobalmoon: true,
    gameDir: "C:/Users/demo/curseforge/minecraft/Instances/Immersed", memoryMb: null, jvmArgs: [], server: null,
    source: { kind: "curseforge", path: "C:/Users/demo/curseforge/minecraft/Instances/Immersed" }, managed: false, unsupportedLoader: null,
  },
  {
    id: "craftoria", name: "Craftoria", mcVersion: "1.21.1", loaderVersion: null, cobalmoon: true,
    gameDir: "C:/Users/demo/curseforge/minecraft/Instances/Craftoria", memoryMb: null, jvmArgs: [], server: null,
    source: { kind: "curseforge", path: "C:/Users/demo/curseforge/minecraft/Instances/Craftoria" }, managed: false,
    unsupportedLoader: "NeoForge 21.1.215",
  },
];

const detected: Detected[] = [
  { kind: "vanilla", name: "fabric-loader-1.21.11", gameDir: "C:/Users/demo/AppData/Roaming/.minecraft", mcVersion: "1.21.11", loader: { kind: "fabric", version: "0.19.2" }, modCount: 5 },
  { kind: "curseforge", name: "Immersed With Shaders", gameDir: "C:/Users/demo/curseforge/minecraft/Instances/Immersed", mcVersion: "1.21.11", loader: { kind: "fabric", version: "0.18.4" }, modCount: 60 },
  { kind: "curseforge", name: "Better MC [NEOFORGE] BMC5", gameDir: "C:/Users/demo/curseforge/minecraft/Instances/BMC5", mcVersion: "1.21.1", loader: { kind: "neoforge", version: "21.1.228" }, modCount: 338 },
  { kind: "modrinth", name: "Fabric 26.3", gameDir: "C:/Users/demo/AppData/Roaming/ModrinthApp/profiles/Fabric 26.3", mcVersion: null, loader: { kind: "unknown" }, modCount: 0 },
  { kind: "prism", name: "Speedrun", gameDir: "C:/Users/demo/AppData/Roaming/PrismLauncher/instances/Speedrun/minecraft", mcVersion: "26.3", loader: { kind: "fabric", version: "0.20.1" }, modCount: 25 },
];

const mods: Record<string, ModEntry[]> = {
  "pvp-26-3": [
    { fileName: "sodium-fabric-0.9.2+mc26.3.jar", path: "/i/pvp/mods/sodium.jar", id: "sodium", name: "Sodium", version: "0.9.2+mc26.3", location: "instance", removable: true, enabled: true, fabric: true },
    { fileName: "lithium-fabric-0.26.3+mc26.3.jar", path: "/i/pvp/mods/lithium.jar", id: "lithium", name: "Lithium", version: "0.26.3+mc26.3", location: "instance", removable: true, enabled: true, fabric: true },
  ],
  immersed: [
    { fileName: "iris-fabric-1.10.8.jar", path: "/cf/immersed/mods/iris.jar", id: "iris", name: "Iris", version: "1.10.8+mc1.21.11", location: "instance", removable: false, enabled: true, fabric: true },
    { fileName: "old-forge-thing.jar", path: "/cf/immersed/mods/old.jar", id: null, name: null, version: null, location: "instance", removable: false, enabled: true, fabric: false },
    { fileName: "entityculling-1.12.1.jar", path: "/extra/immersed/entityculling.jar", id: "entityculling", name: "EntityCulling", version: "1.12.1", location: "cobalmoon", removable: true, enabled: true, fabric: true },
  ],
};

const hits: SearchHit[] = [
  { projectId: "AANobbMI", slug: "sodium", title: "Sodium", description: "The fastest and most compatible rendering optimization mod for Minecraft.", iconUrl: null, downloads: 98_000_000, author: "jellysquid3" },
  { projectId: "mOgUt4GM", slug: "modmenu", title: "Mod Menu", description: "Adds a mod menu to view the list of mods you have installed.", iconUrl: null, downloads: 52_300_000, author: "Prospector" },
  { projectId: "NNAgCjsB", slug: "entityculling", title: "Entity Culling", description: "Using async path-tracing to skip rendering Tiles/Entities that are not visible.", iconUrl: null, downloads: 41_000_000, author: "tr7zw" },
];

const handlers: Record<string, (a: any) => unknown> = {
  get_settings: () => ({ clientId: "", coreModsDir: "", reduceMotion: false, defaultMemoryMb: 4096, theme: {} }),
  save_settings: (a) => a.settings,
  list_accounts: () => [{ uuid: "0", name: "DemoPlayer", xuid: "" }],
  list_versions: () => ["26.3", "26.2", "26.1.2", "1.21.11", "1.21.10", "1.21.1", "1.20.1"].map((id) => ({ id, cobalmoon: id === "26.3" || id === "1.21.11" })),
  list_instances: () => instances,
  detect_installations: () => new Promise((r) => setTimeout(() => r(detected), 400)),
  list_mods: (a) => mods[a.instanceId] ?? [],
  modrinth_search: () => hits,
  modrinth_install: () => new Promise((r) => setTimeout(() => r({ installed: ["sodium-fabric-0.9.2+mc26.3.jar", "fabric-api-0.166.2+26.3.jar"], warnings: [] }), 600)),
  import_installation: (a) => ({ ...instances[1], id: "imported", name: a.detected.name }),
  "plugin:event|listen": () => 1,
  "plugin:event|unlisten": () => null,
  "plugin:dialog|open": () => null,
};

let nextId = 1;
(window as any).__TAURI_INTERNALS__ = {
  invoke: async (cmd: string, args: unknown) => {
    const h = handlers[cmd];
    if (!h) throw `devMock: ${cmd} is not mocked`;
    return h(args);
  },
  transformCallback: () => nextId++,
  unregisterCallback: () => {},
  metadata: { currentWindow: { label: "main" }, currentWebview: { label: "main", windowLabel: "main" } },
};
(window as any).__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener: () => {} };
console.info("Cobalmoon devMock active: sample data, no backend.");
