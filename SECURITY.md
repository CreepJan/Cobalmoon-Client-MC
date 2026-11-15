# Seguridad

## Qué protegemos

- **Tu cuenta de Microsoft.**
  - El launcher usa OAuth con PKCE en el navegador del sistema, así que nunca ve tu contraseña.
  - Los tokens de refresco se guardan en el almacén seguro del sistema operativo (keyring).
  - Ni el mod ni los scripts tienen acceso al token de sesión.
- **Tu ordenador frente a los scripts.** Los scripts Lua se ejecutan en un sandbox sin archivos, red, Java ni comandos, con límites de CPU, memoria y chat. Más detalles en [docs/scripting.md](docs/scripting.md#seguridad-qué-está-probado).
- **Las descargas.**
  - Los archivos de Mojang, Fabric y Java se comprueban con el SHA-1 de los manifiestos oficiales.
  - Los mods de Modrinth se comprueban con su SHA-512.
  - Los `.mrpack` se extraen con protección contra *zip-slip*.
- **Tus instalaciones.** Una instalación importada en su sitio se trata como de solo lectura: Cobalmoon se inyecta con `-Dfabric.addMods` y no toca su carpeta `mods/`.

## Fuera del alcance

- Los mods `.jar` que instalas tú. Son código Java con acceso completo, como en cualquier launcher. Instala solo mods de fuentes de confianza.
- Lo que hagan los servidores de Minecraft.

## Cómo informar de una vulnerabilidad

Usa **GitHub → Security → Report a vulnerability** (aviso privado) en el repositorio del proyecto. No abras un issue público.

Incluye:

- La versión (`Cobalmoon Client x.y.z`, visible en el menú principal).
- La versión de Minecraft y los pasos para reproducirlo.
- Para un escape del sandbox, el `script.json` y el `.lua` mínimos.

Respondemos en un plazo de 7 días. Corregimos primero y publicamos el aviso después.

## Versiones con soporte

Solo la última versión publicada recibe correcciones de seguridad.

## Verificar un instalador

Los instaladores aún no están firmados. Cada release publica `SHA256SUMS.txt`. En Windows:

```powershell
Get-FileHash .\Cobalmoon.Client_1.0.0_x64-setup.exe -Algorithm SHA256
```
