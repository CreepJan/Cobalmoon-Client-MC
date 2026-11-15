# Módulos PvP

Todos los módulos se activan, se configuran y se colocan desde el editor (`Shift derecho`). Ninguno juega por ti: solo
muestran información, cambian cómo se ve el juego o cómo funciona **tu propia** entrada.

## HUD

| Módulo | Qué muestra | Por defecto |
|---|---|---|
| FPS, CPS, Keystrokes | Fotogramas, clics por segundo y teclas pulsadas | Activados |
| Coordenadas | X/Y/Z y dirección | — |
| Ping | Latencia con el servidor (de la lista de jugadores) | — |
| Combo | Golpes seguidos sobre el mismo objetivo sin recibir daño | — |
| Reach | Distancia de tu último golpe acertado, de tus ojos al hitbox del objetivo | — |
| Críticos | Críticos acertados en la partida, con su porcentaje | — |
| Armadura | Piezas y objeto en mano, con durabilidad (%, restante u oculta) | — |
| Efectos | Pociones activas, nivel y tiempo, en el color de cada efecto | — |
| Cooldown de ataque | Carga del arma como barra | — |
| Temporizadores | Cooldown de perla y manzanas (el que fija el servidor) y mecha de la TNT cercana | — |

Un golpe cuenta como **acertado** cuando, tras tu ataque, empieza la animación de daño del objetivo. Se mide en el
cliente, así que no depende de plugins del servidor.

## Visual

| Módulo | Qué hace |
|---|---|
| Crosshair | Mira propia: 5 estilos, tamaño, hueco, grosor, color y contorno |
| Zoom | Tecla `C` (mantener o alternar). La rueda ajusta el aumento; zoom suave y cámara cinemática opcionales |
| Color de golpe | Cambia el color con el que parpadean las entidades al recibir daño |
| Fuego bajo / Escudo bajo | Bajan las llamas y el escudo en primera persona |
| Sin niebla | Quita la niebla de distancia; la del agua, la lava y la ceguera se mantiene |
| Partículas de golpe | Multiplica las partículas de crítico y afilado en los golpes acertados |
| Hitsounds | Sonido al acertar: orbe, campana, pling, platillo, flecha o uno propio |

**Hitsound propio:** con «Personalizado», Cobalmoon reproduce el evento de sonido `cobalmoon:hitsound.custom`. Para
cambiarlo, crea un resource pack con `assets/cobalmoon/sounds.json`:

```json
{ "hitsound.custom": { "sounds": ["cobalmoon:hitsound/mio"] } }
```

y el archivo `assets/cobalmoon/sounds/hitsound/mio.ogg`.

## Juego

| Módulo | Qué hace |
|---|---|
| Toggle sprint | Activa las opciones «Alternar» de correr y agacharse de Minecraft. Al desactivarlo, restaura las tuyas |
| Atajos de hotbar | Teclas para saltar al slot del arma, la perla, la manzana dorada, los bloques o el arco/caña, y para volver al slot anterior. Vienen sin asignar: configúralas en *Opciones → Controles → Cobalmoon Client* |
| Mensajes automáticos | Cuando una línea del chat contiene uno de tus disparadores (separados por `\|`), envía tu mensaje (o un comando si empieza por `/`), con retraso y espera mínima. **Desactivado por defecto:** muchos servidores regulan el chat automático |

## Modo Servidor Seguro

Ver [safe-server-mode.md](safe-server-mode.md).

## Cómo se ha probado

- **Tests unitarios:** cubren la lógica de combo, reach, formato de tiempos, durabilidad, disparadores de mensajes y la guía de servidores.
- **Gametest en 26.3 y 1.21.11:** en un mundo real, el jugador golpea a un aldeano y se comprueban el golpe acertado y el reach. También se prueban el zoom con `C` y el mensaje automático tras «Winner…».
- **Capturas antes/después:** color de golpe, fuego bajo y escudo bajo.
- **Limitación conocida del test en 26.3:** el clic simulado del framework de pruebas no llega a atacar, y lo mismo pasa sin los Mixins de Cobalmoon. Ahí el test llama al ataque del juego directamente. En 1.21.11 el clic real funciona.
