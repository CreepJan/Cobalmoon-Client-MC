<script lang="ts">
  import { onMount } from "svelte";
  import {
    api,
    events,
    errorText,
    overallProgress,
    STAGE_LABEL,
    type Account,
    type Instance,
    type LaunchProgress,
    type Settings,
    type VersionOption,
  } from "./lib/api";

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
  });

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
      <button class="btn ghost icon" title="Nuevo perfil" aria-label="Nuevo perfil" onclick={() => (showNew = true)}>＋</button>
    </div>
    <nav class="profiles">
      {#each instances as inst (inst.id)}
        <button class="profile" class:active={inst.id === selectedId} onclick={() => (selectedId = inst.id)}>
          <span class="dot" class:on={running[inst.id]}></span>
          <span class="pname">{inst.name}</span>
          <span class="pver">{inst.mcVersion}</span>
        </button>
      {:else}
        <p class="hint empty">Crea tu primer perfil para empezar.</p>
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
          </p>
          {#if !supported(selected.mcVersion)}
            <p class="hint">Cobalmoon aún no está disponible para {selected.mcVersion}: se lanzará solo con Fabric.</p>
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
          <button class="btn primary play" disabled={!account || selRunning || busy === "launch"} onclick={play}>
            {selRunning ? "Jugando" : "Jugar"}
          </button>
          {#if !account}<span class="hint">Inicia sesión para jugar.</span>{/if}
        </div>
      </section>

      <section class="console">
        <div class="console-head">
          <span>Registro</span>
          <button class="btn ghost" onclick={() => removeProfile(selected.id)}>Quitar perfil</button>
        </div>
        <pre>{logs.join("\n") || "El registro del juego aparecerá aquí."}</pre>
      </section>
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
  .pname {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
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
  .console-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 6px 6px 6px 14px;
    color: var(--cm-color-lunar);
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    border-bottom: 1px solid color-mix(in srgb, var(--cm-color-lunar) 22%, transparent);
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
  .backdrop {
    position: fixed;
    inset: 0;
    display: grid;
    place-items: center;
    background: color-mix(in srgb, var(--cm-color-night-sunken) 75%, transparent);
    backdrop-filter: blur(4px);
  }
  .dialog {
    width: min(460px, calc(100vw - 32px));
    display: grid;
    gap: var(--cm-space-md);
    background: var(--cm-color-night-raised);
    border: 1px solid color-mix(in srgb, var(--cm-color-lunar) 35%, transparent);
    border-radius: var(--cm-radius-lg);
    padding: var(--cm-space-lg);
    animation: cm-fade-up var(--cm-motion-normal) var(--cm-motion-easing);
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
</style>
