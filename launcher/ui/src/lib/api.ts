// Typed wrapper over the Tauri backend commands and events.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export interface Settings {
  clientId: string;
  coreModsDir: string;
  reduceMotion: boolean;
  defaultMemoryMb: number;
}

export interface Account {
  uuid: string;
  name: string;
  xuid: string;
}

export type SourceKind = "vanilla" | "prism" | "curseforge" | "modrinth" | "folder";

export type Loader =
  | { kind: "vanilla" | "unknown" }
  | { kind: "fabric" | "quilt" | "forge" | "neoforge"; version: string | null };

export interface Instance {
  id: string;
  name: string;
  mcVersion: string;
  loaderVersion: string | null;
  cobalmoon: boolean;
  gameDir: string | null;
  memoryMb: number | null;
  jvmArgs: string[];
  server: string | null;
  source: { kind: SourceKind; path: string } | null;
  managed: boolean;
  unsupportedLoader: string | null;
}

export interface Detected {
  kind: SourceKind;
  name: string;
  gameDir: string;
  mcVersion: string | null;
  loader: Loader;
  modCount: number;
}

export interface ModEntry {
  fileName: string;
  path: string;
  id: string | null;
  name: string | null;
  version: string | null;
  location: "instance" | "cobalmoon";
  removable: boolean;
  enabled: boolean;
  fabric: boolean;
}

export interface SearchHit {
  projectId: string;
  slug: string;
  title: string;
  description: string;
  iconUrl: string | null;
  downloads: number;
  author: string;
}

export interface InstallReport {
  installed: string[];
  warnings: string[];
}

export interface VersionOption {
  id: string;
  cobalmoon: boolean;
}

export interface LaunchProgress {
  instanceId: string;
  stage: "metadata" | "client" | "libraries" | "assets" | "java" | "starting";
  done: number;
  total: number;
}

export interface GameLog {
  instanceId: string;
  line: string;
  stream: "stdout" | "stderr" | "launcher";
}

export interface GameExit {
  instanceId: string;
  code: number | null;
  error: string | null;
}

export const api = {
  getSettings: () => invoke<Settings>("get_settings"),
  saveSettings: (settings: Settings) => invoke<Settings>("save_settings", { settings }),
  listAccounts: () => invoke<Account[]>("list_accounts"),
  login: () => invoke<Account>("login"),
  logout: (uuid: string) => invoke<void>("logout", { uuid }),
  listVersions: () => invoke<VersionOption[]>("list_versions"),
  listInstances: () => invoke<Instance[]>("list_instances"),
  createInstance: (req: { name: string; mcVersion: string; server?: string; gameDir?: string }) =>
    invoke<Instance>("create_instance", { req }),
  updateInstance: (instance: Instance) => invoke<Instance>("update_instance", { instance }),
  removeInstance: (id: string) => invoke<void>("remove_instance", { id }),
  launch: (instanceId: string, accountUuid: string) => invoke<void>("launch_instance", { instanceId, accountUuid }),
  detectInstallations: () => invoke<Detected[]>("detect_installations"),
  inspectFolder: (path: string) => invoke<Detected>("inspect_folder", { path }),
  importInstallation: (detected: Detected, mcVersion: string | null, copy: boolean) =>
    invoke<Instance>("import_installation", { detected, mcVersion, copy }),
  importMrpack: (path: string) => invoke<Instance>("import_mrpack", { path }),
  listMods: (instanceId: string) => invoke<ModEntry[]>("list_mods", { instanceId }),
  addJar: (instanceId: string, path: string) => invoke<{ fileName: string; note: string | null }>("add_jar", { instanceId, path }),
  removeMod: (instanceId: string, path: string) => invoke<void>("remove_mod", { instanceId, path }),
  modrinthSearch: (query: string, mcVersion: string, offset = 0) => invoke<SearchHit[]>("modrinth_search", { query, mcVersion, offset }),
  modrinthInstall: (instanceId: string, projects: string[]) => invoke<InstallReport>("modrinth_install", { instanceId, projects }),
};

export const SOURCE_LABEL: Record<SourceKind, string> = {
  vanilla: "Launcher oficial",
  prism: "Prism / MultiMC",
  curseforge: "CurseForge",
  modrinth: "Modrinth",
  folder: "Carpeta",
};

export function loaderLabel(l: Loader): string {
  const names = { vanilla: "Vanilla", unknown: "Desconocido", fabric: "Fabric", quilt: "Quilt", forge: "Forge", neoforge: "NeoForge" };
  const v = "version" in l && l.version ? ` ${l.version}` : "";
  return names[l.kind] + v;
}

/** Cobalmoon runs on Fabric; Quilt usually works, Forge/NeoForge cannot. */
export function loaderLaunchable(l: Loader): boolean {
  return l.kind !== "forge" && l.kind !== "neoforge";
}

/** Normalises paths so an already-imported installation can be recognised. */
export function samePath(a: string | null | undefined, b: string | null | undefined): boolean {
  const n = (p: string) => p.replace(/\\/g, "/").replace(/\/+$/, "").toLowerCase();
  return !!a && !!b && n(a) === n(b);
}

export function formatDownloads(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)} M`;
  if (n >= 1_000) return `${Math.round(n / 1_000)} k`;
  return String(n);
}

export const events = {
  onProgress: (cb: (p: LaunchProgress) => void): Promise<UnlistenFn> => listen<LaunchProgress>("launch-progress", (e) => cb(e.payload)),
  onLog: (cb: (l: GameLog) => void): Promise<UnlistenFn> => listen<GameLog>("game-log", (e) => cb(e.payload)),
  onExit: (cb: (x: GameExit) => void): Promise<UnlistenFn> => listen<GameExit>("game-exit", (e) => cb(e.payload)),
};

export const STAGE_LABEL: Record<LaunchProgress["stage"], string> = {
  metadata: "Leyendo versiones",
  client: "Descargando el juego",
  libraries: "Descargando librerías",
  assets: "Descargando recursos",
  java: "Preparando Java",
  starting: "Iniciando",
};

/** 0..1 overall progress across stages, so the bar never jumps backwards. */
export function overallProgress(p: Pick<LaunchProgress, "stage" | "done" | "total">): number {
  const order: LaunchProgress["stage"][] = ["metadata", "client", "libraries", "assets", "java", "starting"];
  const weights = [0.03, 0.1, 0.22, 0.4, 0.2, 0.05];
  const i = order.indexOf(p.stage);
  if (i < 0) return 0;
  const before = weights.slice(0, i).reduce((a, b) => a + b, 0);
  const frac = p.total > 0 ? Math.min(1, p.done / p.total) : 0;
  return Math.min(1, before + weights[i] * frac);
}

/** Error messages from Rust arrive as strings. */
export function errorText(e: unknown): string {
  return typeof e === "string" ? e : e instanceof Error ? e.message : JSON.stringify(e);
}
