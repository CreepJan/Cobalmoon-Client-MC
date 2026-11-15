// Copies the built Cobalmoon core jars (one per Minecraft version) into the launcher's bundled resources.
// Run `./gradlew build` first. Used by `tauri build` via beforeBuildCommand.
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "..", "..");
const versionsDir = join(repo, "client-core", "versions");
const out = resolve(here, "..", "src-tauri", "resources", "mods");

mkdirSync(out, { recursive: true });
for (const f of readdirSync(out)) if (f.endsWith(".jar")) rmSync(join(out, f));

let copied = 0;
for (const mc of readdirSync(versionsDir)) {
  const libs = join(versionsDir, mc, "build", "libs");
  if (!existsSync(libs)) continue;
  for (const f of readdirSync(libs)) {
    if (f.startsWith("cobalmoon-core-mc") && f.endsWith(".jar") && !f.includes("-sources")) {
      copyFileSync(join(libs, f), join(out, f));
      console.log(`bundled ${f}`);
      copied++;
    }
  }
}
if (!copied) {
  console.error("No core jars found. Run ./gradlew build in the repository root first.");
  process.exit(1);
}
