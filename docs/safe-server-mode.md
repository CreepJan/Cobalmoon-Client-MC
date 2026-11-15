# Modo Servidor Seguro

- **Opcional y desactivado por defecto.** Se activa en el editor (`Shift derecho`) → *Otros* → *Modo Servidor Seguro*.
- **Solo informa.** Al entrar en un servidor que aparece en la guía, muestra durante unos segundos qué módulos **activos**
  suele prohibir ese servidor. **Cobalmoon nunca desactiva ni bloquea nada**: la decisión es tuya.
- **Descartable.** Usa «No avisar en este servidor» y «Volver a mostrar todos los avisos» en sus ajustes.
- **Privado.** No sale nada de tu PC: la guía se lee de archivos locales.

## La guía

Cobalmoon lee dos archivos y combina sus entradas:

1. `config/cobalmoon/safe-server-guide.json`: la tuya; se lee primero.
2. La guía incluida en el mod (`assets/cobalmoon/safe-server-guide.json`): la comunitaria, editable por pull request.

La guía incluida **empieza vacía a propósito**: cada entrada debe basarse en las normas públicas del servidor, enlazadas en
`source`. No se añaden suposiciones.

```json
{
  "servers": [
    {
      "name": "Ejemplo Network",
      "hosts": ["example.net"],
      "restricted": {
        "auto_messages": "Prohíbe mensajes automáticos en el chat",
        "zoom": ""
      },
      "source": "https://example.net/reglas"
    }
  ]
}
```

- **`hosts`:** coincide con el dominio exacto y con sus subdominios (`example.net` cubre `mc.example.net`). El puerto se ignora.
- **`restricted`:** id del módulo → motivo breve, que puede quedar vacío. Los ids están en `config/cobalmoon/modules/`
  (`zoom`, `auto_messages`, `hotbar_binds`, `no_fog`…).
- **Errores:** un archivo con JSON inválido o entradas incompletas se ignora (se avisa en el log). Nunca impide arrancar el juego.
