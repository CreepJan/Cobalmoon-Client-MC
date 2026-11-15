# Contribuir a Cobalmoon Client

¡Gracias por ayudar! Antes de abrir un PR, lee esto. Son cinco minutos.

## Reglas del proyecto

- **Sin cheats.** No aceptamos nada que dé ventaja automática: auto-clicker, aimbot, reach, ESP, macros de combate ni evasión de anticheats. Los módulos de entrenamiento **miden**, nunca juegan por ti.
- **Sin telemetría.** Nada sale del ordenador del jugador salvo lo que pide explícitamente: login, descargas oficiales y Modrinth.
- **No bloquear lo que instale el jugador.** Avisar sí; impedir no.
- **Compatibilidad.** Antes que reimplementar Sodium, Iris & co., preferimos recomendarlos. Los Mixins usan `@Inject` o MixinExtras, nunca `@Overwrite`.
- **Accesibilidad.** El texto cumple WCAG AA (4.5:1). Si tocas colores, pasa `node design-tokens/scripts/check-contrast.mjs`.

## Preparar el entorno

Requisitos: JDK 25 y JDK 21, Node 22+, Rust estable y, en Windows, Visual Studio C++ Build Tools. Los comandos de compilación y prueba están en el [README](README.md#compilar).

## Antes de enviar un PR

```bash
./gradlew build
```

```bash
./gradlew :client-core:mc-26.3:runClientGameTest :client-core:mc-1.21.11:runClientGameTest
```

```bash
cd launcher && npm run check && npm test
```

```bash
cd launcher/src-tauri && cargo test
```

- Un cambio de comportamiento lleva su test: unitario en `client-core/src/test` o gametest en `client-core/src/gametest`.
- Los textos visibles van en `lang/es_es.json` **y** `lang/en_us.json`.
- Si cambias algo para una sola versión de Minecraft, va en `client-core/versions/<mc>/src`. El código compartido no puede usar APIs que difieran entre versiones.
- Commits pequeños con mensajes que expliquen el porqué.

## Dónde está cada cosa

Lee [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Para addons: [docs/modules.md](docs/modules.md). Para scripts: [docs/scripting.md](docs/scripting.md).

## Licencias

Al contribuir aceptas que tu código se publique con la licencia de la carpeta que tocas: GPL-3.0-or-later, salvo `api/`, que es LGPL-3.0-or-later.

## Vulnerabilidades

No abras un issue público: sigue [SECURITY.md](SECURITY.md).
