# Prompt para el siguiente chat

Copia y pega esto junto con el zip actualizado del proyecto:

---

Continúo el trabajo en mi app Android **SD Bind** (Kotlin + Jetpack Compose + Material 3 Expressive). El proyecto va adjunto en un zip y en mi teléfono se descomprime en `/storage/emulated/0/Download/sdbind_project/`; todo se sube a la rama **`preview`**.

**Antes de hacer nada**, lee `docs/handoff.md` (estado, decisiones y pendientes) y mira las capturas de estilo en `docs/reference/`. La app ya usa un estilo propio (título grande en negrita con acciones, cards redondeadas de 36 dp, tarjeta principal con interruptor, barra de navegación flotante en píldora) implementado en `ui/UiKit.kt`. **No es un clon de ninguna app**: no uses nombres, textos ni gráficos de la app de referencia.

Tareas, en este orden:
1. Si te paso el log de GitHub Actions, corrige los errores de compilación (Compose Material3 `1.5.0-alpha18`, AGP 8.9.1, Kotlin 2.2.20).
2. Verifica los puntos pendientes de la sección 4 del handoff (fuente redonda y negrita, barra de estado en claro/oscuro).
3. [Describe aquí lo que quieres ahora]: ______

(Nota: v2.9.2 y v2.9.3 (pre-releases en el actualizador) no se pudieron compilar en el chat; si Actions falla, pega el log.)

Reglas: no cambies la lógica de root (`RootOps.kt`) ni la firma/`applicationId`; solo colores del `ColorScheme`; cada texto nuevo va en los tres `strings.xml` (es, es-rES, en); entrega el proyecto completo en un zip listo para descomprimir sobre `sdbind_project/`. Al terminar, **actualiza `docs/handoff.md`** (incluida la sección 7 de comandos Termux con el nuevo mensaje de commit y los `rm` de archivos renombrados/eliminados).
