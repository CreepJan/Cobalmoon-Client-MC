# Scripts Lua

Los scripts añaden pequeñas funciones sin compilar un mod: un temporizador, un contador, un aviso en el HUD…
Se escriben en **Lua 5.2** (motor [LuaJ](https://github.com/luaj/luaj), Java puro) y se ejecutan en un **sandbox**:
un script solo puede hacer lo que tú le permites.

> ¿Necesitas más (dibujar gráficos, leer el mundo, nuevos ajustes)? Escribe un addon Java con la
> [API de módulos](modules.md). Los scripts son intencionadamente limitados.

## Dónde van

```
.minecraft/config/cobalmoon/scripts/
├── grants.json              ← permisos concedidos (lo gestiona Cobalmoon)
├── session-timer/
│   ├── script.json
│   └── main.lua
└── mi-script/
    ├── script.json
    └── main.lua
```

La primera vez que arranca Cobalmoon copia dos ejemplos (`session-timer` y `best-combo`) **sin permisos**.
Las carpetas nuevas se detectan al reiniciar el juego; los cambios en el código de un script ya cargado se aplican
con **Recargar código**.

Cada script aparece en el editor (`Shift derecho`) en la categoría **Scripts**, con su descripción y los permisos que pide.

## Manifiesto `script.json`

```json
{
  "id": "session-timer",
  "name": "Session timer",
  "version": "1.0",
  "author": "Tu nombre",
  "description": "Tiempo de sesión y golpes conectados.",
  "main": "main.lua",
  "permissions": ["hud.draw", "combat.read"]
}
```

| Campo | Reglas |
|---|---|
| `id` | 1–32 caracteres `a-z 0-9 _ -`. Único. |
| `main` | Un archivo `.lua` dentro de la carpeta del script (sin rutas). |
| `permissions` | Lista de permisos (abajo). Los desconocidos se muestran y **nunca** se conceden. |

## Permisos

| Permiso | Qué permite |
|---|---|
| `hud.draw` | Mostrar un elemento de texto propio en el HUD (`on_hud`), que se mueve y escala en el editor como cualquier otro. |
| `chat.read` | Recibir las líneas del chat (`on_chat`). |
| `chat.send` | `cobalmoon.send_chat(texto)`: 1 mensaje por segundo, máx. 256 caracteres, **nunca comandos** (`/…`). |
| `player.read` | `cobalmoon.player()`: tu posición, orientación, vida, FPS y CPS. |
| `combat.read` | Tus golpes (`on_hit`). |
| `storage` | `cobalmoon.store`: un almacén clave/valor privado de 64 KB que se conserva entre sesiones. |

**No existen** permisos para archivos, red, otros mods, Java o tu cuenta: un script no puede acceder a ellos
aunque lo pida.

### Cómo se conceden

1. El script empieza **desactivado y sin permisos**.
2. En el editor, en su fila, pulsa **Conceder permisos y activar**. Se concede exactamente lo que pide el manifiesto.
3. La concesión queda ligada a un **hash SHA-256 de `script.json` y del código**. Si cualquiera de los dos cambia
   (lo editas tú o lo sustituye otra persona), el script se detiene y hay que volver a conceder.
4. **Revocar permisos** lo desactiva y borra la concesión.

## API

### Funciones que defines (todas opcionales)

| Función | Cuándo se llama | Permiso |
|---|---|---|
| `on_tick()` | Cada tick del cliente (20/s). | — |
| `on_hud()` | Cada tick; devuelve un texto o una tabla de hasta 20 líneas (200 caracteres cada una). | `hud.draw` |
| `on_chat(texto)` | Por cada línea de chat recibida (máx. 1000 caracteres). | `chat.read` |
| `on_hit(e)` | Cuando conectas un golpe. `e.distance` (bloques), `e.critical` (bool), `e.player` (si era un jugador). | `combat.read` |

### Tabla `cobalmoon`

| Función | Devuelve | Permiso |
|---|---|---|
| `cobalmoon.log(texto)` | Escribe en el log del juego (`[script id] …`). | — |
| `cobalmoon.has_permission("chat.send")` | `true`/`false`. | — |
| `cobalmoon.send_chat(texto)` | Envía un mensaje. Error si no hay permiso, si es un comando o si vas demasiado rápido. | `chat.send` |
| `cobalmoon.player()` | Tabla `{x, y, z, yaw, health, fps, cps}`; fuera de un mundo solo `fps` y `cps`. | `player.read` |
| `cobalmoon.store.get(clave)` | Valor guardado o `nil`. | `storage` |
| `cobalmoon.store.set(clave, valor)` | Guarda texto, número o booleano (`nil` borra). Clave ≤ 64 caracteres. | `storage` |

### Librerías disponibles

`string` (sin `dump`; `rep`/`format` limitados a 1 MB), `table`, `math`, `bit32`, `print` (va al log),
`os.time()`, `os.clock()` (segundos desde que se cargó el script), `pairs`, `ipairs`, `pcall`, `error`, `tostring`,
`tonumber`, `type`, `select`, `setmetatable` y `getmetatable` (salvo la de los strings).

Eliminadas: `io`, `os.execute`/`os.getenv`/etc., `debug`, `load`, `loadstring`, `dofile`, `loadfile`, `require`,
`package`, `module`, `coroutine`, `collectgarbage`, `luajava`.

> Lua 5.2: no hay división entera `//`; usa `math.floor(a / b)`.

## Límites

| Límite | Valor | Si se supera |
|---|---|---|
| Tamaño del código | 256 KB | No se carga. |
| CPU al cargar | 5 000 000 instrucciones | Error y el script se desactiva. |
| CPU por llamada (`on_tick`, `on_hud`…) | 300 000 instrucciones | Error y el script se desactiva (un `while true do end` no congela el juego). |
| Strings | 1 MB | Error. |
| Almacén | 64 KB | `store.set` falla y no guarda. |

Cualquier error desactiva el script y lo muestra en su fila del editor (*Error: …*). El resto del cliente sigue
funcionando.

## Ejemplos

### Temporizador de sesión (`hud.draw`, `combat.read`)

```lua
local start = os.clock()
local hits = 0

function on_hit(e)
  hits = hits + 1
end

function on_hud()
  local s = math.floor(os.clock() - start)
  return { string.format("Session %d:%02d", math.floor(s / 60), s % 60), "Hits " .. hits }
end
```

### Mejor combo de siempre (`hud.draw`, `combat.read`, `storage`)

Ver [`examples/scripts/best-combo`](../client-core/src/main/resources/assets/cobalmoon/examples/scripts/best-combo/main.lua).

### Avisar de una palabra en el chat (`chat.read`, `hud.draw`)

```lua
local last = ""

function on_chat(text)
  if text:lower():find("duel") then last = text end
end

function on_hud()
  if last ~= "" then return "Duel: " .. last end
end
```

### Coordenadas compactas (`player.read`, `hud.draw`)

```lua
function on_hud()
  local p = cobalmoon.player()
  if p.x then return string.format("%d / %d / %d", p.x, p.y, p.z) end
end
```

## Seguridad: qué está probado

`ScriptSandboxTest` (en `client-core/src/test`) intenta escapar del sandbox en cada build:
- **Acceso al sistema:** `io`, `os.execute`/`os.exit`/`os.getenv`, `luajava`, `require`, `load`, `debug`, `string.dump` y `collectgarbage`.
- **Strings:** modificar el metatable compartido por otros scripts, y strings gigantes con `rep` y `format`.
- **Recursos:** bucles infinitos al cargar y en cada llamada, y recursión infinita.
- **Chat y almacén:** comandos por chat, spam de chat y desbordar el almacén.

También ejecuta los scripts de ejemplo.

¿Has encontrado una forma de salir del sandbox? Consulta [SECURITY.md](../SECURITY.md).
