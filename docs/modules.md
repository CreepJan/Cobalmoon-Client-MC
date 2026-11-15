# Crear módulos para Cobalmoon

Un módulo de terceros es un mod de Fabric normal que depende de `cobalmoon-api` (LGPL-3.0, así que tu
addon puede usar la licencia que quieras). Si Cobalmoon no está instalado, tu entrypoint simplemente no se llama.

## 1. Declara el entrypoint

`fabric.mod.json`:

```json
{
  "entrypoints": { "cobalmoon": ["com.example.pingaddon.PingAddon"] },
  "suggests": { "cobalmoon": "*" }
}
```

## 2. Registra tus módulos

```java
public final class PingAddon implements CobalmoonEntrypoint {
    @Override
    public void onCobalmoonInit(CobalmoonApi api) {
        api.modules().register(new PingModule());
    }
}
```

## 3. Escribe el módulo

```java
public final class PingModule extends Module {
    private final BooleanSetting showMs = setting(new BooleanSetting("show_ms", "Mostrar ms", true));
    private int ping;

    public PingModule() {
        super("pingaddon:ping", "Ping", "Tu latencia con el servidor", Category.HUD);

        // Elemento de HUD: se puede mover, escalar y re-estilizar en el editor (Shift derecho).
        hud(new TextHudElement("pingaddon:ping", "Ping", Anchor.TOP_RIGHT, 4, 4) {
            @Override
            protected List<String> lines(boolean preview) {
                return List.of(labeled("Ping", ping + (showMs.get() ? " ms" : "")));
            }
        });

        // Solo se ejecuta mientras el módulo está activado.
        on(Events.ClientTick.class, e -> ping = readPingSomehow());
    }
}
```

### Qué obtienes gratis

- **Persistencia:** activado/desactivado, todos los `Setting`s y la posición/estilo de cada elemento de HUD se guardan
  en `config/cobalmoon/modules/<id>.json` (`:` se escribe `__`).
- **Interfaz:** tus ajustes aparecen en el panel del editor según su tipo:

| Setting | Control |
|---|---|
| `BooleanSetting` | Interruptor |
| `NumberSetting(min, max, step)` | Deslizador |
| `ColorSetting` (ARGB) | Muestras de la paleta; la rueda cambia la transparencia |
| `EnumSetting` | Botón que cicla valores (clic derecho: hacia atrás) |
| `StringSetting(maxLength)` | Campo de texto |
| `ActionSetting(runnable)` | Botón que ejecuta una acción (no se guarda) |
| `InfoSetting(supplier)` | Texto de solo lectura: estado, ayuda (no se guarda) |

Cualquier setting se puede ocultar con `setHidden(true)`. Categorías: `HUD`, `VISUAL`, `GAMEPLAY`, `TRAINING`,
`PERFORMANCE`, `SCRIPTS` (reservada a los scripts Lua, ver [scripting.md](scripting.md)) y `OTHER`.

- **Activación en caliente:** los handlers declarados con `on(...)` se suscriben al activar y se eliminan al desactivar.
- **Aislamiento de errores:** si tu handler o tu elemento de HUD lanza una excepción, se registra en el log y el resto del cliente sigue funcionando.

### Textos y traducciones

Nombres y descripciones pueden ser texto plano o claves de traducción (`assets/<tu-mod>/lang/es_es.json`).
Si la clave existe se traduce; si no, se muestra tal cual.

### Elementos de HUD a medida

Extiende `HudElement` e implementa `width`, `height` y `render(HudCanvas, preview)`. Dibuja en coordenadas locales
(0,0 = esquina superior izquierda). Escala, opacidad y posición las aplica Cobalmoon. `preview` es `true` en el editor,
para que muestres datos de ejemplo cuando aún no hay datos reales.

### Eventos disponibles

| Evento | Cuándo |
|---|---|
| `Events.ClientTick` | Final de cada tick del cliente (20/s) |
| `Events.MouseButton` | Pulsar/soltar un botón del ratón (`inGame` = sin pantallas abiertas) |

Se añadirán más en las próximas fases (combate, conexión a servidor, render del mundo).
