<script lang="ts">
  import { onMount } from "svelte";
  import { open } from "@tauri-apps/plugin-dialog";
  import { listen } from "@tauri-apps/api/event";
  import {
    api,
    errorText,
    loaderLabel,
    loaderLaunchable,
    samePath,
    SOURCE_LABEL,
    type Detected,
    type Instance,
    type VersionOption,
  } from "./api";

  let { instances, versions, onclose, onimported }: {
    instances: Instance[];
    versions: VersionOption[];
    onclose: () => void;
    onimported: (inst: Instance) => void;
  } = $props();

  let found = $state<Detected[] | null>(null);
  let chosenVersion = $state<Record<string, string>>({});
  let busy = $state<string | null>(null);
  let error = $state<string | null>(null);
  let packProgress = $state<{ done: number; total: number } | null>(null);

  onMount(() => {
    api.detectInstallations().then((d) => (found = d)).catch((e) => (error = errorText(e)));
    const off = listen<{ done: number; total: number }>("import-progress", (e) => (packProgress = e.payload));
    return () => void off.then((f) => f());
  });

  const imported = (d: Detected) => instances.some((i) => samePath(i.gameDir, d.gameDir) || samePath(i.source?.path, d.gameDir));

  async function doImport(d: Detected, copy: boolean) {
    error = null;
    busy = d.gameDir;
    try {
      const inst = await api.importInstallation($state.snapshot(d) as Detected, d.mcVersion ?? chosenVersion[d.gameDir] ?? null, copy);
      onimported(inst);
    } catch (e) {
      error = errorText(e);
    } finally {
      busy = null;
    }
  }

  async function pickFolder() {
    const dir = await open({ directory: true, multiple: false, title: "Carpeta del juego (contiene mods/ o instance.cfg)" });
    if (typeof dir !== "string") return;
    try {
      const d = await api.inspectFolder(dir);
      found = [d, ...(found ?? []).filter((x) => !samePath(x.gameDir, d.gameDir))];
    } catch (e) {
      error = errorText(e);
    }
  }

  async function pickPack() {
    const file = await open({ multiple: false, title: "Modpack de Modrinth", filters: [{ name: "Modpack", extensions: ["mrpack"] }] });
    if (typeof file !== "string") return;
    error = null;
    busy = file;
    packProgress = { done: 0, total: 0 };
    try {
      onimported(await api.importMrpack(file));
    } catch (e) {
      error = errorText(e);
    } finally {
      busy = null;
      packProgress = null;
    }
  }
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && !busy && onclose()} />

<div class="backdrop" role="presentation" onclick={(e) => e.target === e.currentTarget && !busy && onclose()}>
  <div class="dialog wide" role="dialog" aria-labelledby="import-title">
    <header>
      <h2 id="import-title">Importar instalaciones</h2>
      <p class="hint">
        «Usar en su sitio» no modifica la instalación original: Cobalmoon se carga encima y lo que añadas desde aquí va a una carpeta
        aparte. «Copiar» crea un perfil independiente.
      </p>
    </header>

    {#if error}<div class="alert" role="alert">{error}</div>{/if}

    <div class="list">
      {#if found === null}
        <p class="hint">Buscando instalaciones…</p>
      {:else if found.length === 0}
        <p class="hint">No se encontraron instalaciones en las ubicaciones habituales. Usa «Elegir carpeta…».</p>
      {:else}
        {#each found as d (d.gameDir + d.name)}
          {@const ok = loaderLaunchable(d.loader)}
          <div class="row" class:disabled={!ok}>
            <div class="info">
              <div class="name">{d.name}</div>
              <div class="meta">
                <span class="badge">{SOURCE_LABEL[d.kind]}</span>
                <span>{d.mcVersion ?? "versión ?"}</span>
                <span class:bad={!ok}>{loaderLabel(d.loader)}</span>
                <span>{d.modCount} mods</span>
              </div>
              {#if !ok}<div class="hint">Usa {loaderLabel(d.loader)}: Cobalmoon funciona sobre Fabric.</div>{/if}
            </div>
            <div class="actions">
              {#if !d.mcVersion}
                <select bind:value={chosenVersion[d.gameDir]} aria-label="Versión de Minecraft">
                  <option value={undefined}>Elige versión</option>
                  {#each versions as v (v.id)}<option value={v.id}>{v.id}</option>{/each}
                </select>
              {/if}
              {#if imported(d)}
                <span class="hint">Ya importada</span>
              {:else}
                <button class="btn" disabled={!!busy || !ok || (!d.mcVersion && !chosenVersion[d.gameDir])} onclick={() => doImport(d, false)}>
                  {busy === d.gameDir ? "Importando…" : "Usar en su sitio"}
                </button>
                <button class="btn ghost" disabled={!!busy || !ok || (!d.mcVersion && !chosenVersion[d.gameDir])} onclick={() => doImport(d, true)}>Copiar</button>
              {/if}
            </div>
          </div>
        {/each}
      {/if}
    </div>

    <footer>
      <div class="left">
        <button class="btn" disabled={!!busy} onclick={pickFolder}>Elegir carpeta…</button>
        <button class="btn" disabled={!!busy} onclick={pickPack}>Importar .mrpack…</button>
        {#if packProgress}
          <span class="hint">Descargando modpack {packProgress.total ? `${packProgress.done}/${packProgress.total}` : "…"}</span>
        {/if}
      </div>
      <button class="btn ghost" disabled={!!busy} onclick={onclose}>Cerrar</button>
    </footer>
  </div>
</div>

<style>
  .wide {
    width: min(760px, calc(100vw - 32px));
    max-height: calc(100vh - 48px);
    display: flex;
    flex-direction: column;
    gap: var(--cm-space-md);
  }
  header p {
    margin: 4px 0 0;
  }
  .list {
    overflow-y: auto;
    display: grid;
    gap: 6px;
    min-height: 120px;
    padding-right: 4px;
  }
  .row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--cm-space-md);
    padding: 10px 12px;
    border-radius: var(--cm-radius-md);
    background: var(--cm-color-night-sunken);
    border: 1px solid color-mix(in srgb, var(--cm-color-lunar) 22%, transparent);
  }
  .row.disabled {
    opacity: 0.75;
  }
  .info {
    min-width: 0;
  }
  .name {
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    color: var(--cm-color-lunar);
    font-size: 12px;
    margin-top: 2px;
  }
  .badge {
    color: var(--cm-color-accent);
  }
  .bad {
    color: var(--cm-color-warning);
  }
  .actions {
    display: flex;
    gap: 6px;
    align-items: center;
    flex-shrink: 0;
  }
  select {
    background: var(--cm-color-night);
    border: 1px solid color-mix(in srgb, var(--cm-color-lunar) 45%, transparent);
    border-radius: var(--cm-radius-sm);
    padding: 6px;
  }
  footer {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--cm-space-sm);
    flex-wrap: wrap;
  }
  .left {
    display: flex;
    gap: var(--cm-space-sm);
    align-items: center;
    flex-wrap: wrap;
  }
  .alert {
    border: 1px solid var(--cm-color-danger);
    border-radius: var(--cm-radius-md);
    padding: 8px 12px;
    user-select: text;
  }
</style>
