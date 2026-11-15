# Temas

Cobalmoon tiene dos editores de temas: uno **en el juego** (menús, editor de HUD) y otro **en el launcher**. Los dos
parten de la misma paleta ([design tokens](design-tokens.md)) y avisan si una combinación no se puede leer bien.

| Token | Por defecto | Uso |
|---|---|---|
| `night` | `#10182C` | Fondo |
| `night-raised` | `#141D35` | Paneles |
| `night-sunken` | `#0B1120` | Fondo hundido / barra lateral |
| `night-overlay` | `#222E4F` | Resaltado (hover) |
| `stone` | `#E9EDF2` | Texto |
| `lunar` | `#7486B8` | Texto secundario, bordes |
| `accent` | `#52E0D0` | Acento turquesa |
| `accent-strong` | `#7FF0E3` | Acento al pasar el ratón |
| `on-accent` | `#10182C` | Texto sobre botones turquesa |
| `danger`, `warning` | | Errores y avisos (solo en el juego) |

## En el juego

1. Abre el editor con `Shift derecho`.
2. En el panel izquierdo, baja hasta **Tema de la interfaz**.
3. Pulsa un color para abrir la rueda (tono, brillo, transparencia y paleta). El cambio se aplica al momento.
4. Debajo de los colores se ve el estado del contraste:
   - *Contraste correcto (WCAG AA)*, o
   - *⚠ Contraste bajo:* con cada pareja que falla y su ratio.

   Es un aviso: puedes guardar el tema igualmente.
5. **Restablecer tema Cobalmoon** vuelve a la paleta original.

El tema se guarda en `config/cobalmoon/theme.json` (formato `"token": "#AARRGGBB"`).

### Compartir un tema

- **Copiar tema (código)** pone en el portapapeles un código `CBT1:…`, que es JSON comprimido con deflate y codificado en base64url.
- **Pegar tema** lo aplica. Las claves desconocidas y los valores inválidos se ignoran, así que un código nunca puede romper la interfaz.
- Un código de tema solo contiene colores. Para compartir HUD y módulos se usan los códigos `CBM1:` ([fase 5](phase5.md)).

## En el launcher

1. Abre **Ajustes** y busca **Tema del launcher**.
2. Cada muestra abre el selector de color del sistema. El cambio se ve al momento.
3. Los avisos de contraste aparecen debajo.
4. **Restablecer tema Cobalmoon** borra tus cambios.
5. Pulsa **Guardar**.

Se guarda en `settings.json` (carpeta de datos del launcher), en el campo `theme`.

## Reglas de contraste

| Pareja | Mínimo |
|---|---|
| Texto sobre fondo, paneles y fondo hundido | 4.5:1 |
| Texto secundario sobre fondo / paneles | 4.5:1 |
| Texto sobre acento (y acento intenso) | 4.5:1 |
| Acento sobre fondo / paneles (elemento de interfaz) | 3:1 |

Los ratios siguen la fórmula de luminancia relativa de WCAG 2.x, la misma que usa el CI para los tokens por defecto.
