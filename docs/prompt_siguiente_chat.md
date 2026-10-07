# Prompt para el siguiente chat

Copia y pega esto junto con el zip actualizado del proyecto:

---

Continúo el trabajo en mi app Android **SD Bind** (Kotlin + Jetpack Compose + Material 3 Expressive). El proyecto va adjunto en un zip y en mi teléfono se descomprime en `/storage/emulated/0/Download/sdbind_project/`; todo se sube a la rama **`preview`**.

**Antes de hacer nada**, lee `docs/handoff.md` (estado, reglas y mapa del código). La app ya usa un estilo propio implementado en `ui/UiKit.kt`. **No es un clon de ninguna app**: no uses nombres, textos ni gráficos de la app de referencia. Si no te menciono un error es porque ya está resuelto.

## Tarea en curso
Ninguna pendiente. (Si un chat anterior dejó algo a medias, aquí va qué se hizo y qué falta.)
Notas v2.9.7 (sin probar en dispositivo): (1) flasheo del módulo desde la app (`Updater.flashModuleZip`); si falla, pega el texto de la tarjeta. (2) tarjeta «Rendimiento» de Ajustes: si algún ajuste no hace efecto, pega las líneas «Rendimiento:» del registro. No se ha podido compilar el APK en el chat: si el CI da error de compilación, pega el log.

## Reglas
- No cambies `RootOps.kt` ni la firma/`applicationId`; solo colores del `ColorScheme`; cada texto nuevo va en los tres `strings.xml` (es, es-rES, en).
- Entrega el proyecto completo en un zip `sdbind_project_v<versión>.zip` (versión en el nombre), listo para descomprimir sobre `sdbind_project/`; el comando `unzip` del handoff usa ese nombre.
- Al terminar, actualiza `docs/handoff.md` (sección de comandos Termux con el nuevo mensaje de commit y los `rm` necesarios) y mantenlo corto: solo lo vigente.
- **Aviso de uso:** avísame cuando estés llegando al 90 % de mi uso gratuito (si no ves el contador, estima por la longitud del chat). Si una tarea queda a medias, documenta en "Tarea en curso" de este prompt, y en el handoff, qué hiciste y qué falta.
- Este prompt siempre termina con la sección "Tareas ahora" (al final del documento).

## Tareas ahora
1. [Describe aquí lo que quieres]: ______
