# Compatibilidad

Cobalmoon es un mod de Fabric normal: no parchea el juego ni bloquea nada. Esta página recoge qué se prueba
automáticamente y cómo se integran las instalaciones existentes.

## Mods de terceros (probado en el juego real)

`gradlew :client-core:mc-<versión>:runClientGameTest -Pcompat` arranca el juego con estos mods, crea un mundo,
usa los módulos y el editor de HUD y comprueba que cada mod se ha cargado. Las versiones y sus SHA-512 están fijadas en
[`tests/compat/mods-<versión>.json`](../tests/compat) y se regeneran con `node tests/compat/pin.mjs 26.3 1.21.11`.

| Mod | 26.3 | 1.21.11 |
|---|---|---|
| Fabric API | 0.166.2 | 0.141.6 |
| Sodium | 0.9.2 | 0.8.14 |
| Iris | 1.11.7 | 1.10.8 |
| Lithium | 0.26.3 | 0.21.4 |
| Mod Menu (+ Placeholder API) | 21.0.1 | 17.0.1 |
| ReplayMod | 2.6.28 | 2.6.28 |
| Xaero's Minimap | 26.6.2 | 26.6.2 |

Resultado (15/11/2026): ✅ ambas versiones, sin errores de Mixin.

**Hallazgos:**
- **Iris y Sodium:** la beta más reciente de Sodium para 26.3 (0.9.3-beta.5) es incompatible con Iris 1.11.7. El resolvedor
  (tanto en estas pruebas como en el launcher) respeta la versión exacta de Sodium que fija Iris.
- **Solapamiento del HUD:** el minimapa de Xaero y el indicador «Recording» de ReplayMod se dibujan arriba a la izquierda,
  donde están por defecto FPS/CPS de Cobalmoon. Todo se puede mover con el editor (`Shift derecho`).

## Instalaciones existentes

| Origen | Cómo se detecta | Versión y loader |
|---|---|---|
| Launcher oficial | `.minecraft/launcher_profiles.json` | Del `lastVersionId` (`fabric-loader-0.19.2-1.21.11`, `1.21.11-forge-…`) |
| Prism Launcher / MultiMC | `instances/*/instance.cfg` + `mmc-pack.json` (respeta `InstanceDir`) | De los componentes |
| CurseForge | `curseforge/minecraft/Instances/*/minecraftinstance.json` | `gameVersion` y `baseModLoader` |
| Modrinth App | `ModrinthApp/profiles/*` | Se deduce de los `fabric.mod.json` de sus mods; el jugador lo confirma |
| Cualquier carpeta | Elegida a mano | Igual que arriba |
| Modpack `.mrpack` | Archivo | `modrinth.index.json` (archivos verificados por hash, solo hosts permitidos, protección zip-slip) |

- **«Usar en su sitio»:** el perfil apunta a la instalación original. Cobalmoon **nunca escribe ni borra** en su carpeta
  `mods/`. Cobalmoon y los mods añadidos desde el launcher se cargan con `-Dfabric.addMods` desde una carpeta propia
  (`extra-mods/<perfil>`). El juego sí guarda allí lo que guarda siempre (opciones, mundos, logs).
- **«Copiar»:** crea un perfil independiente (sin `logs/` ni `crash-reports/`).
- **Forge / NeoForge:** se listan, pero no se pueden lanzar, porque Cobalmoon funciona sobre Fabric. Quilt suele funcionar.
- **Fabric Loader:** se usa el de la instalación. Cobalmoon solo exige el mínimo que necesitan sus módulos de Fabric API
  (0.17.3 en 1.21.11 y 0.19.3 en 26.x).
- **Java:** se usa el runtime oficial de Mojang para la versión. Si algún mod declara que necesita un Java más nuevo
  (p. ej. `"java": ">=22"`), el launcher usa el runtime de Mojang más pequeño que lo cumpla.

Probado con instalaciones reales: 27 detectadas en un PC con launcher oficial, CurseForge y Modrinth App. Una copia
del modpack de CurseForge «Immersed With Shaders» (1.21.11, 60 mods, 186 contando los incluidos) arranca con Cobalmoon
encima mediante el mismo comando que usa el launcher.

## Modrinth en el launcher

- **Búsqueda:** solo mods de Fabric para la versión del perfil.
- **Instalación con un clic:** resuelve las dependencias obligatorias, respeta versiones exactas, prefiere versiones
  estables a betas y detecta por hash lo que ya está instalado.
- **Incompatibilidades:** se avisan pero no bloquean nada; la decisión es del jugador.
- **Descargas:** solo desde `cdn.modrinth.com` por HTTPS y con verificación SHA-512.
- **Preset PvP de rendimiento:** Sodium, Lithium, ImmediatelyFast, FerriteCore y EntityCulling (más Fabric API, que
  EntityCulling necesita).
