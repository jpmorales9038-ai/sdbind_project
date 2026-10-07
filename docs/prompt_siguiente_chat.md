# Prompt para el siguiente chat

Copia y pega esto junto con el zip actualizado del proyecto:

---

Continúo un trabajo previo en mi app Android **SD Bind** (Kotlin + Jetpack Compose + Material 3 Expressive). El proyecto va adjunto en un zip y en mi teléfono se descomprime en `/storage/emulated/0/Download/sdbind_project/`; todo se sube a la rama **`preview`** de GitHub.

**Antes de hacer nada**, lee `docs/handoff.md` (estado, decisiones y pendientes) y mira las capturas de referencia en `docs/reference/`. El objetivo del trabajo es que la app replique **tal cual** la interfaz de Dolby Atmos (título grande con iconos de acción, card con banner degradado y glifo de barras + interruptor, cards redondeadas de 36 dp con icono y título, barra de navegación flotante en píldora). Esa UI ya está implementada con el kit `ui/DolbyKit.kt`, pero **no se llegó a compilar**.

Tareas, en este orden:
1. Revisa el código en busca de errores de compilación (Compose Material3 `1.5.0-alpha18`, AGP 8.9.1, Kotlin 2.2.20) y corrígelos. Si te paso el log de GitHub Actions, parte de ahí.
2. Compara cada pantalla (Inicio, Registro, Ajustes) con las capturas y afina medidas/colores hasta que se vea igual.
3. [Elige lo que quieras ahora] aplicar el mismo estilo a `FolderPickerScreen` / `FileBrowserScreen`, o lo que indique en este mensaje: ______

Reglas: no cambies la lógica de root (`RootOps.kt`) ni la firma/`applicationId`; usa solo colores del `ColorScheme`; añade cada texto nuevo a los tres `strings.xml` (es, es-rES, en); entrega el proyecto completo en un zip listo para descomprimir sobre `sdbind_project/`. Al terminar, **actualiza `docs/handoff.md`** con lo que cambió y lo que queda pendiente, y deja en la sección 7 del handoff los comandos de Termux (descomprimir y push a `preview`), actualizando el mensaje de commit.
