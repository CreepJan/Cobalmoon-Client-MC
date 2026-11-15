<script lang="ts">
  import { onMount } from "svelte";
  import {
    api,
    events,
    errorText,
    overallProgress,
    STAGE_LABEL,
    SOURCE_LABEL,
    type Account,
    type Instance,
    type LaunchProgress,
    type Settings,
    type VersionOption,
  } from "./lib/api";
  import ImportDialog from "./lib/ImportDialog.svelte";
  import { THEME_TOKENS, applyTheme, color, contrastWarnings } from "./lib/theme";
  import ModsPanel from "./lib/ModsPanel.svelte";

  let showImport = $state(false);
  let tab = $state<"play" | "mods">("play");

  let settings = $state<Settings | null>(null);
  let accounts = $state<Account[]>([]);
  let instances = $state<Instance[]>([]);
  let versions = $state<VersionOption[]>([]);
  let selectedId = $state<string | null>(null);
  let accountUuid = $state<string | null>(null);

  let progress = $state<Record<string, LaunchProgress>>({});
  let running = $state<Record<string, boolean>>({});
  let logs = $state<string[]>([]);
  let error = $state<string | null>(null);
  let busy = $state<"login" | "launch" | null>(null);

  let showNew = $state(false);
  let showSettings = $state(false);
  let newName = $state("PvP");
  let newVersion = $state("26.3");
  let newServer = $state("");
  let newGameDir = $state("");

  const selected = $derived(instances.find((i) => i.id === selectedId) ?? null);
  const account = $derived(accounts.find((a) => a.uuid === accountUuid) ?? accounts[0] ?? null);
  const selProgress = $derived(selected ? progress[selected.id] : undefined);
  const selRunning = $derived(selected ? !!running[selected.id] : false);

  $effect(() => {
    if (settings?.reduceMotion) document.documentElement.setAttribute("data-reduce-motion", "");
    else document.documentElement.removeAttribute("data-reduce-motion");
    applyTheme(settings?.theme ?? {});
  });

  const themeWarnings = $derived(settings ? contrastWarnings(settings.theme ?? {}) : []);

  function setThemeColor(key: string, value: string) {
    if (!settings) return;
    settings.theme = { ...(settings.theme ?? {}), [key]: value.toUpperCase() };
  }

  async function refresh() {
    [settings, accounts, instances] = await Promise.all([api.getSettings(), api.listAccounts(), api.listInstances()]);
    selectedId ??= instances[0]?.id ?? null;
    accountUuid ??= accounts[0]?.uuid ?? null;
  }

  onMount(() => {
    refresh().catch((e) => (error = errorText(e)));
    api
      .listVersions()
      .then((v) => (versions = v))
      .catch(() => (versions = [{ id: "26.3", cobalmoon: true }, { id: "1.21.11", cobalmoon: true }]));
    const offs = [
      events.onProgress((p) => (progress[p.instanceId] = p)),
      events.onLog((l) => {
        logs.push(l.line);
        if (logs.length > 800) logs.splice(0, logs.length - 800);
      }),
      events.onExit((x) => {
        running[x.instanceId] = false;
        delete progress[x.instanceId];
        logs.push(`[Cobalmoon] El juego se cerró (código ${x.code ?? "?"})${x.error ? `: ${x.error}` : ""}`);
      }),
    ];
    return () => offs.forEach((p) => p.then((off) => off()));
  });

  async function login() {
    error = null;
    busy = "login";
    try {
      const acc = await api.login();
      accounts = await api.listAccounts();
      accountUuid = acc.uuid;
    } catch (e) {
      error = errorText(e);
    } finally {
      busy = null;
    }
  }

  async function logout(uuid: string) {
    await api.logout(uuid);
    accounts = await api.listAccounts();
    accountUuid = accounts[0]?.uuid ?? null;
  }

  async function play() {
    if (!selected || !account) return;
    error = null;
    busy = "launch";
    logs = [];
    running[selected.id] = true;
    try {
      await api.launch(selected.id, account.uuid);
    } catch (e) {
      running[selected.id] = false;
      delete progress[selected.id];
      error = errorText(e);
    } finally {
      busy = null;
    }
  }

  async function createProfile(e: SubmitEvent) {
    e.preventDefault();
    try {
      const inst = await api.createInstance({ name: newName, mcVersion: newVersion, server: newServer, gameDir: newGameDir });
      instances = await api.listInstances();
      selectedId = inst.id;
      showNew = false;
      newServer = "";
      newGameDir = "";
    } catch (err) {
      error = errorText(err);
    }
  }

  async function removeProfile(id: string) {
    await api.removeInstance(id);
    instances = await api.listInstances();
    if (selectedId === id) selectedId = instances[0]?.id ?? null;
  }

  async function saveSettings(e: SubmitEvent) {
    e.preventDefault();
    if (!settings) return;
    try {
      settings = await api.saveSettings($state.snapshot(settings));
      showSettings = false;
    } catch (err) {
      error = errorText(err);
    }
  }

  function supported(v: string) {
    return versions.find((x) => x.id === v)?.cobalmoon ?? false;
  }
</script>

<div class="shell">
  <aside class="sidebar">
    <div class="brand">
      <img src="/logo.svg" alt="" width="34" height="34" />
      <div>
        <div class="brand-name">Cobalmoon</div>
        <div class="brand-sub">Client</div>
      </div>
    </div>

    <div class="section-head">
      <span>Perfiles</span>
      <span class="head-actions">
        <button class="btn ghost icon" title="Importar instalaciones" aria-label="Importar instalaciones" onclick={() => (showImport = true)}>⇩</button>
        <button class="btn ghost icon" title="Nuevo perfil" aria-label="Nuevo perfil" onclick={() => (showNew = true)}>＋</button>
      </span>
    </div>
    <nav class="profiles">
      {#each instances as inst (inst.id)}
        <button class="profile" class:active={inst.id === selectedId} class:blocked={!!inst.unsupportedLoader} onclick={() => (selectedId = inst.id)}>
          <span class="dot" class:on={running[inst.id]}></span>
          <span class="pname">{inst.name}</span>
          <span class="pver">{inst.mcVersion}</span>
        </button>
      {:else}
        <p class="hint empty">Crea tu primer perfil o importa una instalación existente.</p>
      {/each}
    </nav>

    <div class="sidebar-foot">
      <button class="btn ghost" onclick={() => (showSettings = true)}>Ajustes</button>
    </div>
  </aside>

  <main class="main">
    <header class="topbar">
      {#if account}
        <div class="account">
          <div class="avatar" aria-hidden="true">{account.name.slice(0, 1).toUpperCase()}</div>
          <div>
            <div>{account.name}</div>
            <div class="hint">Cuenta Microsoft</div>
          </div>
          {#if accounts.length > 1}
            <select bind:value={accountUuid} aria-label="Cuenta">
              {#each accounts as a (a.uuid)}<option value={a.uuid}>{a.name}</option>{/each}
            </select>
          {/if}
          <button class="btn ghost" onclick={() => logout(account.uuid)}>Cerrar sesión</button>
        </div>
      {:else}
        <button class="btn primary" disabled={busy === "login"} onclick={login}>
          {busy === "login" ? "Esperando al navegador…" : "Iniciar sesión con Microsoft"}
        </button>
      {/if}
    </header>

    {#if error}
      <div class="alert" role="alert">
        <span>{error}</span>
        <button class="btn ghost" onclick={() => (error = null)} aria-label="Cerrar aviso">✕</button>
      </div>
    {/if}

    {#if selected}
      <section class="hero">
        <div class="hero-text">
          <h1>{selected.name}</h1>
          <p class="muted">
            Minecraft {selected.mcVersion} · Fabric
            {#if selected.cobalmoon && supported(selected.mcVersion)}· Cobalmoon{/if}
            {#if selected.server}· {selected.server}{/if}
            {#if selected.source}· {SOURCE_LABEL[selected.source.kind]}{selected.managed ? " (copia)" : " (en su sitio)"}{/if}
          </p>
          {#if selected.unsupportedLoader}
            <p class="hint warn">Usa {selected.unsupportedLoader}: Cobalmoon funciona sobre Fabric y no puede lanzarla. La instalación no se ha modificado.</p>
          {:else if !supported(selected.mcVersion)}
            <p class="hint">Cobalmoon aún no está disponible para {selected.mcVersion}: se lanzará solo con Fabric y tus mods.</p>
          {/if}
        </div>

        <div class="play-area">
          {#if selProgress}
            <div class="progress" aria-live="polite">
              <div class="bar"><div class="fill" style:width={`${Math.round(overallProgress(selProgress) * 100)}%`}></div></div>
              <span class="hint">
                {STAGE_LABEL[selProgress.stage]}
                {#if selProgress.total > 1}({selProgress.done}/{selProgress.total}){/if}
              </span>
            </div>
          {/if}
          <button class="btn primary play" disabled={!account || selRunning || busy === "launch" || !!selected.unsupportedLoader} onclick={play}>
            {selRunning ? "Jugando" : "Jugar"}
          </button>
          {#if !account}<span class="hint">Inicia sesión para jugar.</span>{/if}
        </div>
      </section>

      <nav class="tabs" aria-label="Secciones del perfil">
        <button class="tab" class:active={tab === "play"} onclick={() => (tab = "play")}>Registro</button>
        <button class="tab" class:active={tab === "mods"} onclick={() => (tab = "mods")}>Mods</button>
        <span class="spacer"></span>
        <button class="btn ghost" onclick={() => removeProfile(selected.id)} title="Solo lo quita de la lista; no borra archivos">Quitar perfil</button>
      </nav>

      {#if tab === "mods"}
        <ModsPanel instance={selected} />
      {:else}
        <section class="console">
          <pre>{logs.join("\n") || "El registro del juego aparecerá aquí."}</pre>
        </section>
      {/if}
    {:else}
      <section class="welcome">
        <img src="/logo.svg" alt="" width="96" height="96" />
        <h1>Bienvenido a Cobalmoon Client</h1>
        <p class="muted">Cliente PvP libre y de código abierto para Minecraft. Sin anuncios, sin telemetría, sin tienda.</p>
        <button class="btn primary" onclick={() => (showNew = true)}>Crear perfil</button>
      </section>
    {/if}
  </main>
</div>

<svelte:window
  onkeydown={(e) => {
    if (e.key !== "Escape") return;
    showNew = false;
    showSettings = false;
  }}
/>

{#if showNew}
  <div class="backdrop" role="presentation" onclick={(e) => e.target === e.currentTarget && (showNew = false)}>
    <form class="dialog" onsubmit={createProfile}>
      <h2>Nuevo perfil</h2>
      <label class="field"><span>Nombre</span><input bind:value={newName} required maxlength="40" /></label>
      <label class="field">
        <span>Versión de Minecraft</span>
        <select bind:value={newVersion}>
          {#each versions as v (v.id)}
            <option value={v.id}>{v.id}{v.cobalmoon ? " — Cobalmoon" : ""}</option>
          {/each}
        </select>
      </label>
      <label class="field">
        <span>Servidor (opcional, se conecta al iniciar)</span>
        <input bind:value={newServer} placeholder="mc.ejemplo.net" />
      </label>
      <label class="field">
        <span>Carpeta del juego (opcional, para usar una instalación existente)</span>
        <input bind:value={newGameDir} placeholder="Por defecto: carpeta propia del perfil" />
      </label>
      <div class="actions">
        <button type="button" class="btn ghost" onclick={() => (showNew = false)}>Cancelar</button>
        <button type="submit" class="btn primary">Crear</button>
      </div>
    </form>
  </div>
{/if}

{#if showImport}
  <ImportDialog
    {instances}
    versions={versions}
    onclose={() => (showImport = false)}
    onimported={async (inst) => {
      instances = await api.listInstances();
      selectedId = inst.id;
      tab = "mods";
      showImport = false;
    }}
  />
{/if}

{#if showSettings && settings}
  <div class="backdrop" role="presentation" onclick={(e) => e.target === e.currentTarget && (showSettings = false)}>
    <form class="dialog" onsubmit={saveSettings}>
      <h2>Ajustes</h2>
      <label class="field">
        <span>Client ID de Azure (inicio de sesión Microsoft)</span>
        <input bind:value={settings.clientId} placeholder="00000000-0000-0000-0000-000000000000" spellcheck="false" />
        <small class="hint">Es público, no un secreto. Cómo obtenerlo: docs/azure-app.md</small>
      </label>
      <label class="field">
        <span>Carpeta del mod núcleo (desarrollo)</span>
        <input bind:value={settings.coreModsDir} placeholder="Vacío: usar el incluido con el launcher" spellcheck="false" />
      </label>
      <label class="field">
        <span>Memoria por defecto (MB)</span>
        <input type="number" min="1024" step="512" bind:value={settings.defaultMemoryMb} />
      </label>
      <label class="check"><input type="checkbox" bind:checked={settings.reduceMotion} /> Reducir animaciones</label>
      <fieldset class="theme">
        <legend>Tema del launcher</legend>
        <div class="swatches">
          {#each Object.entries(THEME_TOKENS) as [key, token] (key)}
            <label class="swatch">
              <input type="color" value={color(settings.theme ?? {}, key).toLowerCase()} oninput={(e) => setThemeColor(key, e.currentTarget.value)} />
              <span>{token.label}</span>
            </label>
          {/each}
        </div>
        {#if themeWarnings.length}
          <ul class="warnings" role="status">{#each themeWarnings as w (w)}<li>⚠ Contraste bajo — {w}</li>{/each}</ul>
        {:else}
          <p class="hint">Contraste correcto (WCAG AA).</p>
        {/if}
        <button type="button" class="btn ghost" onclick={() => settings && (settings.theme = {})}>Restablecer tema Cobalmoon</button>
      </fieldset>
      <p class="hint">Cobalmoon no recoge telemetría. Tus tokens se guardan en el almacén de credenciales del sistema.</p>
      <div class="actions">
        <button type="button" class="btn ghost" onclick={() => (showSettings = false)}>Cancelar</button>
        <button type="submit" class="btn primary">Guardar</button>
      </div>
    </form>
  </div>
{/if}

<style>
  .shell {
    display: grid;
    grid-template-columns: 248px 1fr;
    height: 100%;
  }
  .sidebar {
    background: var(--cm-color-night-sunken);
    border-right: 1px solid color-mix(in srgb, var(--cm-color-lunar) 22%, transparent);
    display: flex;
    flex-direction: column;
    padding: var(--cm-space-md);
    gap: var(--cm-space-md);
    min-height: 0;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 4px 4px 8px;
  }
  .brand img {
    border-radius: 9px;
  }
  .brand-name {
    font-weight: 700;
    letter-spacing: 0.02em;
  }
  .brand-sub {
    color: var(--cm-color-accent);
    font-size: 11px;
    letter-spacing: 0.24em;
    text-transform: uppercase;
  }
  .section-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    color: var(--cm-color-lunar);
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
  }
  .icon {
    padding: 2px 8px;
  }
  .profiles {
    display: grid;
    gap: 4px;
    overflow-y: auto;
    flex: 1;
    align-content: start;
  }
  .profile {
    display: grid;
    grid-template-columns: 10px 1fr auto;
    align-items: center;
    gap: 10px;
    text-align: left;
    background: transparent;
    border: 1px solid transparent;
    border-radius: var(--cm-radius-md);
    padding: 9px 10px;
    cursor: pointer;
    transition: background var(--cm-motion-fast) var(--cm-motion-easing);
  }
  .profile:hover {
    background: var(--cm-color-night-raised);
  }
  .profile.active {
    background: var(--cm-color-night-raised);
    border-color: color-mix(in srgb, var(--cm-color-accent) 55%, transparent);
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: color-mix(in srgb, var(--cm-color-lunar) 60%, transparent);
  }
  .dot.on {
    background: var(--cm-color-accent);
    box-shadow: 0 0 8px var(--cm-color-accent);
  }
  /* Long names wrap (up to two lines) instead of being cut off. */
  .pname {
    overflow-wrap: anywhere;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    line-height: 1.3;
  }
  .pver {
    color: var(--cm-color-lunar);
    font-size: 12px;
    font-family: var(--cm-font-mono);
  }
  .empty {
    padding: 0 4px;
  }
  .main {
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
    padding: var(--cm-space-lg);
    gap: var(--cm-space-lg);
    background: radial-gradient(1200px 500px at 85% -10%, color-mix(in srgb, var(--cm-color-lunar) 14%, transparent), transparent 60%),
      var(--cm-color-night);
  }
  .topbar {
    display: flex;
    justify-content: flex-end;
  }
  .account {
    display: flex;
    align-items: center;
    gap: 12px;
    background: var(--cm-color-night-raised);
    border-radius: var(--cm-radius-lg);
    padding: 6px 6px 6px 8px;
  }
  .account select {
    background: var(--cm-color-night-sunken);
    border: 1px solid color-mix(in srgb, var(--cm-color-lunar) 45%, transparent);
    border-radius: var(--cm-radius-sm);
    padding: 4px;
  }
  .avatar {
    width: 32px;
    height: 32px;
    border-radius: 8px;
    display: grid;
    place-items: center;
    background: var(--cm-color-accent);
    color: var(--cm-color-on-accent);
    font-weight: 700;
  }
  .alert {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--cm-space-md);
    border: 1px solid var(--cm-color-danger);
    background: color-mix(in srgb, var(--cm-color-danger) 10%, var(--cm-color-night));
    color: var(--cm-color-stone);
    border-radius: var(--cm-radius-md);
    padding: 8px 8px 8px 14px;
    animation: cm-fade-up var(--cm-motion-normal) var(--cm-motion-easing);
    user-select: text;
  }
  .hero {
    display: flex;
    justify-content: space-between;
    align-items: flex-end;
    gap: var(--cm-space-lg);
    padding: var(--cm-space-lg) 0;
    animation: cm-fade-up var(--cm-motion-slow) var(--cm-motion-easing);
  }
  h1 {
    margin: 0 0 4px;
    font-size: 34px;
    font-weight: 700;
    letter-spacing: -0.01em;
  }
  h2 {
    margin: 0 0 var(--cm-space-sm);
    font-size: 18px;
  }
  .hero-text p {
    margin: 0;
  }
  .play-area {
    display: grid;
    gap: var(--cm-space-sm);
    justify-items: end;
    min-width: 260px;
  }
  .play {
    font-size: 18px;
    padding: 14px 56px;
    border-radius: var(--cm-radius-lg);
    letter-spacing: 0.04em;
  }
  .progress {
    width: 100%;
    display: grid;
    gap: 6px;
  }
  .bar {
    height: 6px;
    border-radius: 3px;
    background: var(--cm-color-night-overlay);
    overflow: hidden;
  }
  .fill {
    height: 100%;
    background: var(--cm-color-accent);
    transition: width var(--cm-motion-normal) var(--cm-motion-easing);
  }
  .console {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: var(--cm-color-night-sunken);
    border: 1px solid color-mix(in srgb, var(--cm-color-lunar) 22%, transparent);
    border-radius: var(--cm-radius-lg);
  }
  pre {
    flex: 1;
    margin: 0;
    padding: 12px 14px;
    overflow: auto;
    font-family: var(--cm-font-mono);
    font-size: 12px;
    color: var(--cm-color-lunar);
    white-space: pre-wrap;
    word-break: break-all;
    user-select: text;
  }
  .welcome {
    margin: auto;
    text-align: center;
    display: grid;
    justify-items: center;
    gap: var(--cm-space-md);
    max-width: 480px;
    animation: cm-fade-up var(--cm-motion-slow) var(--cm-motion-easing);
  }
  .welcome img {
    border-radius: 22px;
  }
  .welcome p {
    margin: 0;
  }
  .sidebar-foot {
    display: flex;
  }
  .tabs {
    display: flex;
    align-items: center;
    gap: 4px;
    border-bottom: 1px solid color-mix(in srgb, var(--cm-color-lunar) 22%, transparent);
  }
  .tab {
    background: none;
    border: none;
    border-bottom: 2px solid transparent;
    padding: 8px 14px;
    color: var(--cm-color-lunar);
    cursor: pointer;
    transition: color var(--cm-motion-fast) var(--cm-motion-easing), border-color var(--cm-motion-fast) var(--cm-motion-easing);
  }
  .tab:hover {
    color: var(--cm-color-stone);
  }
  .tab.active {
    color: var(--cm-color-stone);
    border-bottom-color: var(--cm-color-accent);
  }
  .spacer {
    flex: 1;
  }
  .head-actions {
    display: flex;
    gap: 2px;
  }
  .profile.blocked .pname {
    color: var(--cm-color-lunar);
  }
  .hint.warn {
    color: var(--cm-color-warning);
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--cm-space-sm);
  }
  .check {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .theme {
    border: 1px solid color-mix(in srgb, var(--cm-color-lunar) 35%, transparent);
    border-radius: var(--cm-radius-md);
    padding: 10px 12px;
    display: grid;
    gap: 10px;
  }
  .theme legend {
    color: var(--cm-color-lunar);
    font-size: 12px;
    padding: 0 4px;
  }
  .swatches {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
    gap: 6px 10px;
  }
  .swatch {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    cursor: pointer;
  }
  .swatch input {
    width: 26px;
    height: 22px;
    padding: 0;
    border: 1px solid color-mix(in srgb, var(--cm-color-lunar) 45%, transparent);
    border-radius: var(--cm-radius-sm);
    background: none;
    cursor: pointer;
  }
  .warnings {
    margin: 0;
    padding-left: 16px;
    color: var(--cm-color-warning);
    font-size: 12px;
  }
  .dialog {
    max-height: calc(100vh - 32px);
    overflow-y: auto;
  }
</style>
