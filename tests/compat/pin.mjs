// Pins the third-party mods used by the compatibility test: for each Minecraft version it picks the
// newest Fabric build of every mod in MODS on Modrinth, follows required dependencies, and writes
// tests/compat/mods-<mc>.json with file name, URL and SHA-512 (verified again on download).
//
// Usage: node tests/compat/pin.mjs 26.3 1.21.11
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const MODS = ["sodium", "lithium", "iris", "modmenu", "replaymod", "xaeros-minimap"];
// Already present in the development runtime of the core mod, so not added again.
const PROVIDED = new Set(["P7dR8mSH" /* fabric-api */]);
const API = "https://api.modrinth.com/v2";
const HEADERS = { "User-Agent": "cobalmoon-compat-pin/1.0 (open-source Minecraft PvP client)" };
const here = dirname(fileURLToPath(import.meta.url));

async function get(path) {
  const r = await fetch(API + path, { headers: HEADERS });
  if (!r.ok) throw new Error(`${r.status} ${path}`);
  return r.json();
}

async function latest(projectId, mc) {
  const q = `?loaders=${encodeURIComponent('["fabric"]')}&game_versions=${encodeURIComponent(JSON.stringify([mc]))}`;
  const versions = await get(`/project/${projectId}/version${q}`);
  return versions[0] ?? null;
}

async function pin(mc) {
  const out = new Map();
  // A dependency that names an exact version (e.g. Iris -> a specific Sodium build) wins over "latest".
  const exact = new Set();
  const queue = MODS.map((id) => ({ id }));
  while (queue.length) {
    const { id, versionId } = queue.shift();
    if (PROVIDED.has(id)) continue;
    const v = versionId ? await get(`/version/${versionId}`) : await latest(id, mc);
    if (!v) throw new Error(`${id} has no Fabric build for ${mc}`);
    if (out.has(v.project_id) && (exact.has(v.project_id) || !versionId)) continue;
    if (versionId) exact.add(v.project_id);
    const file = v.files.find((f) => f.primary) ?? v.files[0];
    const project = await get(`/project/${v.project_id}`);
    out.set(v.project_id, {
      slug: project.slug,
      projectId: v.project_id,
      version: v.version_number,
      file: file.filename,
      url: file.url,
      sha512: file.hashes.sha512,
    });
    for (const d of v.dependencies ?? []) {
      if (d.dependency_type === "required" && d.project_id && !PROVIDED.has(d.project_id)) {
        queue.push({ id: d.project_id, versionId: d.version_id ?? undefined });
      }
    }
  }
  const list = [...out.values()].sort((a, b) => a.slug.localeCompare(b.slug));
  writeFileSync(join(here, `mods-${mc}.json`), JSON.stringify({ minecraft: mc, mods: list }, null, 2) + "\n");
  console.log(`${mc}: ${list.map((m) => `${m.slug} ${m.version}`).join(", ")}`);
}

for (const mc of process.argv.slice(2)) await pin(mc);
