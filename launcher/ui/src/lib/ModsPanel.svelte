<script lang="ts">
  import { open } from "@tauri-apps/plugin-dialog";
  import { api, errorText, formatDownloads, type Instance, type ModEntry, type SearchHit } from "./api";

  let { instance }: { instance: Instance } = $props();

  let mods = $state<ModEntry[]>([]);
  let query = $state("");
  let hits = $state<SearchHit[] | null>(null);
  let busy = $state<string | null>(null);
  let notes = $state<string[]>([]);
  let error = $state<string | null>(null);
  let searchTimer: ReturnType<typeof setTimeout> | undefined;

  const blocked = $derived(!!instance.unsupportedLoader);

  async function reload() {
    try {
      mods = await api.listMods(instance.id);
    } catch (e) {
      error = errorText(e);
    }
  }

  $effect(() => {
    void instance.id;
    hits = null;
    notes = [];
    error = null;
    reload();
  });

  function onQuery() {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(search, 300);
  }

  async function search() {
    error = null;
    try {
      hits = await api.modrinthSearch(query.trim(), instance.mcVersion);
    } catch (e) {
      error = errorText(e);
    }
  }

  async function install(projects: string[], label: string) {
    error = null;
    busy = label;
    try {
      const r = await api.modrinthInstall(instance.id, projects);
      notes = [
        r.installed.length ? `Instalado: ${r.installed.join(", ")}` : "Ya estaba todo instalado.",
        ...r.warnings,
      ];
      await reload();
    } catch (e) {
      error = errorText(e);
    } finally {
      busy = null;
    }
  }

  async function addJar() {
    const file = await open({ multiple: true, title: "Añadir mods (.jar)", filters: [{ name: "Mod", extensions: ["jar"] }] });
    const files = typeof file === "string" ? [file] : (file ?? []);
    if (!files.length) return;
    error = null;
    const out: string[] = [];
    for (const f of files) {
      try {
        const r = await api.addJar(instance.id, f);
        out.push(r.note ? `${r.fileName}: ${r.note}` : `Añadido: ${r.fileName}`);
      } catch (e) {
        out.push(errorText(e));
      }
    }
    notes = out;
    await reload();
  }

  async function remove(m: ModEntry) {
    if (!confirm(`¿Quitar ${m.name ?? m.fileName}? El archivo se borrará de la carpeta de Cobalmoon.`)) return;
    try {
      await api.removeMod(instance.id, m.path);
      await reload();
    } catch (e) {
      error = errorText(e);
    }
  }

  const installedIds = $derived(new Set(mods.map((m) => m.id).filter(Boolean)));
</script>

<section class="mods">
  {#if blocked}
    <p class="hint">Esta instalación usa {instance.unsupportedLoader}: los mods de Fabric no se cargarían.</p>
  {:else}
    {#if !instance.managed}
      <p class="hint">
        Instalación original usada en su sitio: sus mods no se tocan. Lo que añadas aquí se guarda en una carpeta de Cobalmoon y se carga
        junto a ellos.
      </p>
    {/if}

    <div class="toolbar">
      <input
        class="search"
        type="search"
        placeholder={`Buscar mods de Fabric para ${instance.mcVersion} en Modrinth`}
        bind:value={query}
        oninput={onQuery}
        onkeydown={(e) => e.key === "Enter" && search()}
      />
      <button class="btn" onclick={addJar}>Añadir .jar…</button>
      <button class="btn primary" disabled={!!busy} onclick={() => install(["@pvp-preset"], "preset")}>
        {busy === "preset" ? "Instalando…" : "Preset PvP de rendimiento"}
      </button>
    </div>
    <p class="hint small">Preset: Sodium, Lithium, ImmediatelyFast, FerriteCore y EntityCulling (con sus dependencias).</p>
  {/if}

  {#if error}<div class="alert" role="alert">{error}</div>{/if}
  {#if notes.length}
    <ul class="notes">{#each notes as n, i (i)}<li>{n}</li>{/each}</ul>
  {/if}

  <div class="columns">
    {#if hits}
      <div class="col">
        <h3>Modrinth</h3>
        {#each hits as h (h.projectId)}
          <div class="item">
            {#if h.iconUrl}<img src={h.iconUrl} alt="" width="32" height="32" loading="lazy" />{:else}<div class="icon"></div>{/if}
            <div class="text">
              <div class="title">{h.title} <span class="muted">· {h.author} · {formatDownloads(h.downloads)}</span></div>
              <div class="desc">{h.description}</div>
            </div>
            <button class="btn" disabled={!!busy || blocked} onclick={() => install([h.projectId], h.projectId)}>
              {busy === h.projectId ? "Instalando…" : "Instalar"}
            </button>
          </div>
        {:else}
          <p class="hint">Sin resultados.</p>
        {/each}
      </div>
    {/if}

    <div class="col">
      <h3>Instalados ({mods.length})</h3>
      {#each mods as m (m.path)}
        <div class="item">
          <div class="text">
            <div class="title" class:off={!m.enabled}>
              {m.name ?? m.fileName}
              {#if m.version}<span class="muted">{m.version}</span>{/if}
            </div>
            <div class="desc">
              {m.fileName}
              {#if m.location === "cobalmoon"}<span class="tag">añadido con Cobalmoon</span>{/if}
              {#if !m.fabric}<span class="tag warn">no es de Fabric</span>{/if}
              {#if !m.enabled}<span class="tag">desactivado</span>{/if}
            </div>
          </div>
          {#if m.removable}
            <button class="btn ghost" onclick={() => remove(m)} aria-label={`Quitar ${m.fileName}`}>Quitar</button>
          {/if}
        </div>
      {:else}
        <p class="hint">Este perfil no tiene mods.{installedIds.size ? "" : " Prueba el preset PvP."}</p>
      {/each}
    </div>
  </div>
</section>

<style>
  .mods {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: var(--cm-space-sm);
  }
  .toolbar {
    display: flex;
    gap: var(--cm-space-sm);
    flex-wrap: wrap;
  }
  .search {
    flex: 1;
    min-width: 220px;
    background: var(--cm-color-night-sunken);
    border: 1px solid color-mix(in srgb, var(--cm-color-lunar) 45%, transparent);
    border-radius: var(--cm-radius-md);
    padding: 8px 12px;
    user-select: text;
  }
  .search:focus {
    outline: none;
    border-color: var(--cm-color-accent);
  }
  .small {
    margin: 0;
  }
  .columns {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: var(--cm-space-md);
  }
  .col {
    min-height: 0;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 6px;
    background: var(--cm-color-night-sunken);
    border: 1px solid color-mix(in srgb, var(--cm-color-lunar) 22%, transparent);
    border-radius: var(--cm-radius-lg);
    padding: 10px;
  }
  h3 {
    margin: 0 0 4px;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--cm-color-lunar);
    font-weight: 500;
  }
  .item {
    display: flex;
    gap: 10px;
    align-items: center;
    padding: 6px;
    border-radius: var(--cm-radius-md);
  }
  .item:hover {
    background: var(--cm-color-night-raised);
  }
  .item img,
  .icon {
    width: 32px;
    height: 32px;
    border-radius: 6px;
    flex-shrink: 0;
    background: var(--cm-color-night-overlay);
  }
  .text {
    flex: 1;
    min-width: 0;
  }
  .title {
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .title.off {
    opacity: 0.6;
  }
  .title .muted {
    font-weight: 400;
    font-size: 12px;
  }
  .desc {
    color: var(--cm-color-lunar);
    font-size: 12px;
    overflow-wrap: anywhere;
  }
  .tag {
    margin-left: 6px;
    color: var(--cm-color-accent);
  }
  .tag.warn {
    color: var(--cm-color-warning);
  }
  .notes {
    margin: 0;
    padding-left: 18px;
    color: var(--cm-color-lunar);
    font-size: 13px;
    user-select: text;
  }
  .alert {
    border: 1px solid var(--cm-color-danger);
    border-radius: var(--cm-radius-md);
    padding: 8px 12px;
    user-select: text;
  }
</style>
