<!-- Generated from design-tokens/tokens.json by design-tokens/scripts/build.mjs. Do not edit by hand. -->
# Design tokens

Single source of truth: [`design-tokens/tokens.json`](../design-tokens/tokens.json).
The launcher (CSS variables), the in-game UI (`DefaultTokens.java`) and these docs are generated from it.
The Cobalmoon palette is only the **default theme**: every colour can be overridden by a user theme.

## Colours

| Token | Value | Use |
|---|---|---|
| `night` | `#10182C` | Azul noche — main backgrounds, dark panels |
| `lunar` | `#7486B8` | Azul lunar — secondary elements, borders, muted text, inactive states |
| `accent` | `#52E0D0` | Turquesa — primary accent: buttons, selection, active toggles, default crosshair |
| `stone` | `#E9EDF2` | Blanco piedra — primary text, icons, hairlines |
| `night-sunken` | `#0B1120` | Derived: deeper background (window chrome, sidebars) |
| `night-raised` | `#141D35` | Derived: raised panels and cards |
| `night-overlay` | `#222E4F` | Derived: hover / popovers |
| `accent-strong` | `#7FF0E3` | Derived: accent hover/pressed |
| `on-accent` | `#10182C` | Text and icons drawn on top of the accent (stone on accent fails WCAG) |
| `danger` | `#FF7A88` | Errors and destructive actions |
| `warning` | `#FFC86B` | Warnings (e.g. Safe Server Mode notices) |

## Contrast (WCAG 2.x)

| Pair | Ratio | Required | Pass |
|---|---|---|---|
| `stone` on `night` | 15.02:1 | AA (≥ 4.5) | ✅ |
| `stone` on `night-sunken` | 16.01:1 | AA (≥ 4.5) | ✅ |
| `stone` on `night-raised` | 14.21:1 | AA (≥ 4.5) | ✅ |
| `stone` on `night-overlay` | 11.36:1 | AA (≥ 4.5) | ✅ |
| `lunar` on `night` | 4.92:1 | AA (≥ 4.5) | ✅ |
| `lunar` on `night-sunken` | 5.24:1 | AA (≥ 4.5) | ✅ |
| `lunar` on `night-raised` | 4.65:1 | AA (≥ 4.5) | ✅ |
| `accent` on `night` | 10.88:1 | AA (≥ 4.5) | ✅ |
| `accent` on `night-raised` | 10.29:1 | AA (≥ 4.5) | ✅ |
| `on-accent` on `accent` | 10.88:1 | AA (≥ 4.5) | ✅ |
| `on-accent` on `accent-strong` | 13.02:1 | AA (≥ 4.5) | ✅ |
| `danger` on `night` | 7.06:1 | AA (≥ 4.5) | ✅ |
| `warning` on `night` | 11.54:1 | AA (≥ 4.5) | ✅ |
| `lunar` on `night-overlay` | 3.72:1 | UI (≥ 3) | ✅ |

Rule of thumb: text on a turquoise (`accent`) surface is always `on-accent` (night blue), never stone.
