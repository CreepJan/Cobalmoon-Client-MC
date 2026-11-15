# Análisis, entrenamiento, perfiles y configuraciones compartibles

Todo lo de esta página funciona **en tu PC**: no hay servidores de Cobalmoon y no se envía nada.

## Análisis post-pelea

*Entrenamiento y análisis → Análisis de peleas* (activado por defecto).

- **Cuándo empieza una pelea:** cuando atacas a otro jugador o cuando te golpean con un jugador a menos de 6 bloques.
  Contra mobs, solo si activas «Contar peleas contra mobs».
- **Cuándo termina:** al morir uno de los dos, o tras unos segundos sin combate (configurable). Si no acertaste ningún
  golpe, no se guarda.
- **Resumen al terminar:** tarjeta movible desde el editor. Muestra precisión (golpes acertados por swing), combo
  máximo, reach medio y máximo, daño dado y recibido en corazones, críticos y tiempo de reacción (desde que te
  golpean hasta tu siguiente ataque). Entre paréntesis va la **diferencia con la media de tus peleas anteriores**.
- **Datos del daño:** el daño se calcula con la salud que el servidor envía al cliente. Si un servidor la oculta,
  aparecerá como 0.
- **Historial:** `config/cobalmoon/fights.json`, con las últimas 50 peleas. «Borrar historial» lo vacía.

## Replay ligero

*Análisis de peleas → Ver repeticiones* abre, dentro del editor, un mapa visto desde arriba de las últimas 10 peleas:

- **Recorridos:** el tuyo en turquesa y el del rival en rojo, muestreados 10 veces por segundo, con una flecha que indica hacia dónde miras.
- **Marcadores de golpe:** turquesa donde acertaste y rojo donde te dieron, también señalados en la línea de tiempo.
- **Controles:** `Espacio` reproduce o pausa, arrastra la barra para moverte por la pelea, `←`/`→` cambian de pelea y `Esc` cierra.

No es una grabación de vídeo. Para eso está ReplayMod, que es compatible con Cobalmoon.

## Modo entrenamiento

*Entrenamiento y análisis → Entrenamiento*. Solo en un **mundo local** (un jugador). Elige ejercicio y duración y pulsa
**Empezar** (cuenta atrás de 3 s). Los ejercicios **solo miden**: no automatizan nada.

| Ejercicio | Qué prepara | Qué mide |
|---|---|---|
| CPS | — | CPS medio, mejor segundo y constancia (desviación por segundo) |
| Puntería al strafear | Un aldeano de práctica invulnerable delante de ti | % del tiempo con la mira sobre él, en total y mientras pulsas A/D, y % de tiempo strafeando |
| W-tap | El mismo objetivo | % de golpes en los que habías soltado el sprint desde el golpe anterior |
| Bridging | 128 de lana en tu inventario | Bloques colocados, bloques/s, distancia y caídas |

Al terminar (o con **Parar**), el objetivo de práctica se elimina.

## Perfiles por servidor

*Otros → Perfiles por servidor* (activado por defecto):

1. Entra en un servidor, coloca el HUD y configura los módulos como quieras para ese servidor.
2. Pulsa **Guardar perfil para este servidor**.
3. A partir de ahí, al entrar en ese servidor (o en uno de sus subdominios: el perfil de `example.net` sirve para
   `mc.example.net`) se carga ese perfil. Al salir, los cambios que hiciste se guardan en el perfil y vuelve tu
   configuración normal (se puede desactivar).

Los perfiles están en `config/cobalmoon/profiles/<servidor>.json`. Las teclas de Minecraft no forman parte del perfil,
porque se guardan en `options.txt` del juego.

## Configuraciones compartibles

En el editor, con nada seleccionado, el panel derecho tiene **Compartir configuración**:

- **Copiar configuración (código):** todos los módulos, ajustes y posiciones del HUD en un código `CBM1:…` en el
  portapapeles. Pégalo en Discord, en el chat o donde quieras.
- **Copiar solo el HUD:** solo qué elementos se ven y dónde y cómo se ven, sin tocar el resto de ajustes de quien lo pegue.
- **Pegar configuración:** aplica el código del portapapeles y dice a cuántos módulos se aplicó y qué módulos no
  tienes instalados.
- **Guardar en archivo:** `config/cobalmoon/exports/cobalmoon-<fecha>.cbm`.

Un código **no contiene** datos personales: ni cuenta, ni servidores, ni rutas. Tampoco incluye tus perfiles de
servidor ni el Modo Servidor Seguro. Los códigos dañados o maliciosos se rechazan con un mensaje, nunca rompen el juego.
