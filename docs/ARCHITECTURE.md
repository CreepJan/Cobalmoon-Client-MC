# Cobalmoon Client — Arquitectura y plan por fases

> **Cómo quedó en la v1.0.** Este documento es el plan original. Donde la implementación se apartó de él:
>
> | Plan | v1.0 | Motivo |
> |---|---|---|
> | Stonecutter | Subproyectos `client-core/versions/<mc>` que comparten `client-core/src`; las diferencias de API van en clases puente por versión (`Platform`, `VersionCanvas`, `EditorScreen` y algunos mixins) | 26.x sin ofuscar y 1.21.11 con mapeos de Mojang usan plugins de Loom distintos; los subproyectos lo hacen explícito y sin preprocesador |
> | `/modules` y `/scripting` como proyectos Gradle | Paquetes `modules/` y `script/` dentro de `client-core` | Un solo jar por versión de Minecraft, que el launcher inyecta con `fabric.addMods` |
> | Lua con Cobalt | Lua 5.2 con **LuaJ 3.0.1** (incluido jar-in-jar) | Disponible en Maven Central; sandbox propio con presupuesto de instrucciones ([scripting.md](scripting.md)) |
> | `script.toml`; permisos `input.read`, `http:<dominio>`, `fs.own` | `script.json`; permisos `hud.draw`, `chat.read`, `chat.send`, `player.read`, `combat.read`, `storage` | Sin red ni archivos en la v1.0: menos superficie de ataque. El almacén privado sustituye a `fs.own` |
> | Códigos `CBM1:` con zstd | deflate (JDK) + base64url; temas con `CBT1:` ([themes.md](themes.md)) | Sin dependencias nativas |
> | pnpm | npm (`package-lock.json`) | Viene con Node |
> | Historial de peleas en SQLite o JSON | JSON | Sin dependencias |
> | Entrenamiento con un datapack propio | Ejercicios en cualquier mundo local ([phase5.md](phase5.md)) | No hace falta instalar nada |
> | Updater de Tauri firmado con minisign | Pendiente | Necesita una clave de firma y un sitio de publicación |
> | Instaladores firmados | Sin firmar, con SHA-256 publicados | Los certificados cuestan dinero |

## Contexto
Construir desde cero un cliente PvP para Minecraft Java: gratuito, de código abierto, muy personalizable y compatible con mods e instalaciones externas. Se parte de un directorio vacío. Decisiones ya tomadas por ti:
- **Versiones:** 26.x (última: 26.3) + 1.21.11. La 1.8.9 llega después de la v1.0 como port en Ornithe.
- **Launcher:** Tauri 2 (Rust + Svelte 5/TypeScript).
- **Licencia:** GPLv3 para núcleo, launcher y módulos; LGPLv3 para `/api`.
- **Login:** client ID de Azure configurable. Tú solicitas a Mojang la aprobación de la app y yo escribo la guía (`docs/azure-app.md`).

## Estado actual del ecosistema (noviembre de 2026)
- Minecraft usa versiones por año (26.1, 26.2, 26.3; 26.4 en snapshots). **Desde 26.1 el juego ya no está ofuscado.** Fabric ya no mantiene Yarn y trabaja con los nombres oficiales de Mojang. Para 26.x hace falta Java 25; para 1.21.11, Java 21.
- **Fabric:** el estándar de facto, con Loader 0.18.x. Sodium, Lithium, Iris, Mod Menu, ReplayMod y Xaero son mods de Fabric.
- **Quilt:** casi parado, aunque carga mods de Fabric. Haré una prueba de humo con él, pero no será objetivo principal.
- **NeoForge:** está activo, pero su ecosistema PvP es pequeño. Queda fuera de la v1.0.
- **1.8.9:** se juega con Legacy Fabric u Ornithe. AxolotlClient (LGPL) sirve de referencia para ver qué se puede hacer ahí.
- **Riesgo de login:** las apps nuevas de Azure necesitan el permiso de la API de Minecraft. Sin él, `api.minecraftservices.com` responde 403.

## Decisión técnica: vía A, un conjunto de mods de Fabric más un launcher propio
Motivos:
1. Cualquier mod de Fabric funciona sin parches. Con la vía B tendríamos que mantener un fork del juego y romperíamos esa compatibilidad.
2. Con el juego sin ofuscar, mantener Mixins con `@Inject`/`@ModifyExpressionValue` (MixinExtras) es barato.
3. **La integración no invade nada:** el launcher inyecta Cobalmoon con `-Dfabric.addMods=<ruta>`. Nunca toca la carpeta `mods/` de una instancia importada.
4. Los módulos de terceros son mods de Fabric normales que declaran el entrypoint `cobalmoon`. No hay un sistema de plugins propietario.

Usaré **Stonecutter** para compilar el mismo código fuente para 26.x y 1.21.11, con ramas condicionales solo donde cambie la API del juego.

## Estructura del monorepo
```
/design-tokens   tokens.json (formato W3C DTCG) → genera CSS vars, Tokens.java y una tabla para docs; script de contraste WCAG
/api             (LGPL) Module, Setting<T>, HudElement, EventBus, Theme, ScriptPermission, anotaciones
/client-core     mod de Fabric: ModuleManager, config, eventos (mixins), renderer de HUD, toolkit UI, editor de HUD, perfiles
/modules         módulos incluidos, uno por paquete y registrados por entrypoint (hud/, visual/, gameplay/, analysis/, training/)
/scripting       sandbox de Lua (Cobalt), sistema de permisos, puente con la API
/launcher        Tauri: src-tauri/ (crates auth, meta, java, instances, importers, modrinth, launch) + ui/ (Svelte)
/data            safe-server-guide.json (editable por la comunidad), presets PvP
/docs            arquitectura, guía de módulos, guía de scripts, azure-app.md, temas
/tests           pruebas de carga con mods de terceros (lista fijada con hashes), fixtures de instancias importadas
.github/workflows  ci.yml (build + tests + contraste), compat.yml (matriz de mods), release.yml (instaladores firmados)
```
Builds reproducibles: Gradle con version catalog, `reproducibleFileOrder`, sin timestamps y dependencias con hash verificado. En el launcher, `Cargo.lock` y `pnpm-lock`.

## Diseño de cada componente

### Design tokens
- Una sola fuente de verdad: `tokens.json`, con `night #10182C`, `lunar #7486B8`, `accent #52E0D0` y `stone #E9EDF2`, más los derivados (elevaciones del panel, estados).
- **Contrastes calculados:** stone sobre night ≈ 15:1, accent sobre night ≈ 10.9:1 y lunar sobre night ≈ 4.9:1. Todos pasan AA.
- **Regla:** el texto sobre un botón turquesa va en night, porque stone sobre accent da ≈ 1.4:1 y no pasa. El texto de lunar sobre paneles elevados lo valida el CI.
- Un tema es un JSON con las mismas claves. El editor de temas escribe ahí y se aplica en caliente.

### Núcleo (client-core)
- **Módulos:** cada `Module` tiene id, categoría, `Setting`s tipados (bool, número, color, keybind, enum, posición) y su propia config en JSON (`config/cobalmoon/modules/<id>.json`). Se activan y desactivan en caliente con `onEnable`/`onDisable`.
- **Eventos:** un EventBus propio y de bajo coste (listas de handlers por tipo, sin reflexión en el bucle). Los Mixins usan `@Inject` con prioridad 1100 y nunca `@Overwrite`.
- **HUD:** cada `HudElement` tiene ancla, offset, escala, opacidad, fuente, colores, fondo y animación. Se renderiza en una sola pasada por frame y los textos se cachean.
- **Editor de HUD:** drag & drop, snapping y guías, multiselección. Los perfiles se exportan a archivo o a un código (`CBM1:` + zstd + base64url).
- **Perfiles por servidor:** al conectar se resuelve el perfil por host o patrón y se aplican HUD, keybinds y módulos.
- **Animaciones:** un único sistema de easing con la opción global “reducir animaciones”.

### Launcher (Tauri)
- **auth:** OAuth de Microsoft con auth code + PKCE por loopback → Xbox Live → XSTS → Minecraft services. Los refresh tokens van al keyring del sistema operativo. Nunca se guarda una contraseña.
- **meta/java:** manifiesto de piston-meta, libraries y assets descargados de los endpoints oficiales, con verificación SHA-1/SHA-256 y caché compartida. El runtime de Java sale del manifiesto oficial de Mojang.
- **instances:** perfiles por versión y por servidor. Se puede añadir cualquier `.jar`. Hay presets de JVM.
- **importers:** `.minecraft` vanilla (`launcher_profiles.json`), Prism/MultiMC (`instance.cfg` + `mmc-pack.json`), CurseForge (`minecraftinstance.json`) y Modrinth App / `.mrpack`. Por defecto la instancia se usa en su sitio y en solo lectura; copiarla es opcional.
- **modrinth:** búsqueda, instalación con un clic y resolución de dependencias (requeridas, opcionales e incompatibles se avisan pero no se bloquean). Incluye un preset PvP de rendimiento con Sodium, Lithium, ImmediatelyFast, FerriteCore y EntityCulling.
- **Actualizaciones:** updater de Tauri con firma minisign.

### Módulos PvP
- **HUD:** CPS, keystrokes, ping, FPS, coordenadas, combo, reach del último golpe, armadura y durabilidad, pociones, cooldown de ataque, crits y timers de TNT, perla y manzana.
- **Visual:** animaciones configurables, fuego y escudo bajos, partículas, zoom, sin niebla, crosshair editable (turquesa por defecto), hit color y hitsounds del usuario.
- **Gameplay:** toggle sprint/sneak, binds de hotbar, swap de ítems y mensajes automáticos.
- **Optimización:** el preset recomienda e instala mods de Modrinth en lugar de duplicarlos. Si Sodium está presente, se detecta y se ocultan las opciones redundantes.

### Scripting
- **Motor:** Lua con Cobalt, que es Java puro y no da acceso a reflexión ni a clases de Java. Tiene límites de instrucciones y memoria por tick.
- **Permisos:** un manifiesto `script.toml` declara permisos (`hud.draw`, `chat.read`, `chat.send`, `input.read`, `http:<dominio>`, `fs.own`) y el usuario los concede en un diálogo.
- **Protección de la cuenta:** el token de sesión y las rutas fuera de la carpeta del script no se exponen nunca.
- **Libertad del jugador:** no hay listas negras de lo que el jugador instale. Los `.jar` de Fabric se cargan sin comprobaciones.

### Diferenciadores (implemento 5)
1. **Análisis post-pelea:** FightTracker detecta el duelo (te pegan o pegas a un jugador y termina con una muerte o 10 s sin contacto). Registra precisión (golpes conectados frente a swings), combos, daño dado y recibido, reach, tiempo de reacción (golpe recibido → siguiente input) y comparación con tu historial local (SQLite o JSON).
2. **Modo entrenamiento:** mundo local generado por un datapack propio con ejercicios de strafing, bridging, W-tap, block-hit y CPS. Solo mide, no automatiza nada.
3. **Perfiles automáticos por servidor.**
4. **Replay ligero:** un buffer circular de las últimas N peleas (posiciones, swings, golpes) con un visor fantasma en el juego. Si ReplayMod está instalado, se ofrece abrir la grabación completa.
5. **Configs compartibles:** toda la configuración por código o archivo, sin servidor propio.

Propuestas extra: overlay de frametime e input-latency, detector de conflictos de keybinds, diff entre perfiles y galería de temas comunitarios (un repositorio Git, sin tienda).

### Modo Servidor Seguro y filosofía
- **Desactivado por defecto.** Si lo activas, al conectar muestra como mucho un toast descartable (“este servidor suele prohibir X”) con la opción “no volver a mostrar”. Nunca bloquea nada.
- **Datos:** salen de `/data/safe-server-guide.json`. Actualizarlo desde GitHub es opt-in.
- **Sin cheats ni evasión de anticheat.** No hay telemetría: los crash reports se quedan en local.

## Fases y criterios de verificación
| Fase | Entregable | Verificación |
|---|---|---|
| 1 | Monorepo, tokens, launcher mínimo con la identidad Cobalmoon, login con Microsoft, descarga de vanilla + Fabric, lanzamiento con el mod núcleo vacío; CI | `gradlew build` y `cargo test` en verde. `runClient` muestra el logo y la versión de Cobalmoon. El launcher lanza 26.x y 1.21.11 (con login real cuando la app de Azure esté aprobada; antes, se prueba con `runClient` de Loom). |
| 2 | Sistema de módulos, EventBus, editor de HUD, 5 módulos (FPS, CPS, keystrokes, coordenadas, crosshair), tokens aplicados | Tests unitarios de config y settings. Client gametest que activa y desactiva módulos y guarda la posición del HUD. |
| 3 | Importadores, `fabric.addMods`, cualquier `.jar`, Modrinth con dependencias, matriz de compatibilidad | `compat.yml` arranca un cliente headless (xvfb) con Sodium, Lithium, Iris, Mod Menu, Fabric API, ReplayMod y Xaero, y llega a un mundo sin errores de Mixin. Prueba de humo en Quilt. |
| 4 | Todos los módulos PvP y el Modo Servidor Seguro | Tests por módulo y un gametest con escenarios de combate simulados. |
| 5 | Análisis post-pelea, entrenamiento, perfiles por servidor, replay ligero y configs compartibles | Tests del FightTracker con secuencias de eventos sintéticas e ida y vuelta de los códigos de exportación. |
| 6 | Scripting, editor de temas, docs (guía de módulos y scripts con ejemplos), instaladores (msi, dmg, AppImage y deb) | Tests de escape del sandbox (reflexión, acceso a archivos, bucles infinitos), instaladores en una VM o en CI y documentación revisada. |

Al terminar cada fase te paso un resumen de qué funciona, qué falta y cómo probarlo, y espero tu visto bueno antes de seguir. **MVP** = fases 1 y 2, **beta** = fases 3 a 5, **v1.0** = fase 6. Después de la v1.0 viene el port a 1.8.9 en Ornithe (API y un subconjunto de módulos).

## Riesgos y decisiones abiertas
- **Aprobación de Azure:** bloquea el login real en el launcher. La mitigación es el client ID configurable, la guía de registro y el desarrollo con `runClient`.
- **Ritmo de versiones:** sale una versión de Minecraft cada pocos meses. Stonecutter y la API desacoplada limitan el coste de cada port.
- **Conflictos de Mixin con Sodium e Iris en el render del HUD y el crosshair:** el HUD se engancha a los callbacks de Fabric API (`HudElementRegistry`) y no a Mixins de render cuando sea posible.
- **Firma de código en Windows y macOS:** cuesta dinero (certificados). Hasta tenerla, los binarios irán sin firmar y con SHA-256 publicados.
- **1.8.9:** usa otra toolchain y no tiene Sodium ni Iris. Se trata como un proyecto aparte.
- **Abiertas:** nombre del dominio y la web de la documentación; dónde se aloja la galería de temas (propuesta: repositorio de GitHub).

## Primeros pasos tras tu aprobación
1. Inicializar git, mover la sesión a una carpeta que elijas (ahora está en una carpeta temporal de esta sesión) y crear la rama.
2. Fase 1: `design-tokens` → `api` y `client-core` (Gradle + Stonecutter + Loom) → `launcher` (Tauri) → CI.
3. Copiar este documento a `docs/ARCHITECTURE.md`.
