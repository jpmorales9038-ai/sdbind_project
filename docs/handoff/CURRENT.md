# HANDOFF ACTUAL — SD Bind v2.9.2

> Léelo completo antes de tocar código. Histórico del estilo visual (v2.9.1, reglas de estilo, riesgos de API,
> comandos Termux): `docs/handoff/archive/handoff_v2.9.1.md` — **sigue vigente** en lo no contradicho aquí.
> Proyecto en el teléfono: `/storage/emulated/0/Download/sdbind_project/` · rama de trabajo: **`preview`**.

## 1. ESTADO GENERAL
- **Proyecto**: SD Bind, módulo KernelSU/KernelSU-Next (`module/`) + app Compose Material 3 Expressive (`app/`) que crea bind mounts SD/OTG → almacenamiento interno.
- **Terminado**: restyling visual (v2.9.1, ver archivo histórico) y, en este chat, la nueva **tarjeta Actualizaciones** (código escrito).
- **En progreso**: validar que v2.9.2 compila (CI) y funciona en el dispositivo.
- **Sin empezar**: nada más pedido.

## 2. PEDIDOS DEL USUARIO
Vigentes del chat anterior (cumplidos, ver archivo histórico): estilo propio sin copiar la app de referencia, sin barras ecualizadoras, fuentes redondeadas + negrita, barra de estado según tema, icono propio, tarjeta principal sin degradado, difuminado sobre barra de gestos, fuentes pequeñas, cierre en Ajustes/Registro corregido (v2.9.1).

**Pedido de este chat (literal)**: «En la sección actualizaciones se mostrarán las actualizaciones de la app, no del módulo y se auto actualizará al tocar un botón, la tarjeta se tornará verde. Al volver a abrir la app, en la misma sección se notificará si la versión de app no coincide con la versión de módulo, tornándose ámbar, en ese caso se podrá descargar el módulo y flashearlo desde la misma app.»
- ✅ (código, NO CONFIRMADO en dispositivo) Actualizaciones = actualizaciones de la **app**; un botón la actualiza sola.
- ✅ (código) Tarjeta verde al estar la app al día / al arrancar la instalación.
- ✅ (código) Al abrir/volver a la app se relee `module.prop` del módulo instalado; si ≠ versión de la app → tarjeta ámbar.
- ✅ (código) En ámbar el botón pasa a «Flashear módulo»: descarga el zip y lo abre en el gestor root (flujo `Updater.openForFlash`, ya existente).
- Pendiente: probar todo en dispositivo. Descartado: nada.

## 3. TRABAJO REALIZADO (todo sin compilar: no hay red en el chat)
- `Updater.kt`: **eliminado** `checkAndDownload`. Añadidos `AppUpdateOutcome`, `moduleVersion()` (lee `version=` de `/data/adb/modules/sdcard_bind_ui/module.prop`), `sameVersion()` (compara solo números, ignora `v` y ceros finales), `updateApp()` (último release → descarga APK suelto; si el release no lo trae, extrae `app/sdcard-bind-manager.apk` del zip del módulo → instala con root), `downloadModule()` (zip del último release sin exigir que sea más nuevo; copia a Descargas), helpers `latestRelease/findAsset/extractFromZip`.
  - Instalación: `nohup sh -c 'pm install -r -S $(stat -c %s APK) < APK > /data/local/tmp/sdbind_install.log; am start --user 0 -n <pkg>/.MainActivity' &`. Se desacopla porque Android mata la app al reemplazarla; se relanza sola. `updateApp` sondea el log 30 s y devuelve `Failed` si ve «Failure/Exception».
- `ui/UiKit.kt`: `UiCard` con parámetros nuevos `containerColor` y `contentColor` (por defecto el comportamiento anterior).
- `MainActivity.kt`: `AboutPane(onSnack)` (ya no recibe `busy/onCheck`); nuevo composable `UpdatesCard` + `enum UpdMode {NEUTRAL, APP_OK, MODULE_MISMATCH}`. Verde `#2E7D32`/blanco, ámbar `#FFB74D`/`#3E2700` (colores fijos tipo semáforo, como `StatusChip`). Precedencia: ámbar > verde > neutra. Versión del módulo se relee en cada `STARTED` (`repeatOnLifecycle`). Import `animateColorAsState`.
- Strings nuevas en los 3 idiomas (`values`, `values-es-rES`, `values-en`): `update_app_ok`, `update_app_working`, `update_app_installing`, `update_install_failed`, `update_install_start`, `update_module_mismatch`, `update_module_downloading`, `update_flash_module`; `updates_desc` reescrita.
- `.github/workflows/build.yml`: el release ahora publica también `module/app/sdcard-bind-manager.apk` (APK suelto).
- Versión: app `2.9.2` / `106`, módulo `v2.9.2` / `356`, badge del README; README (característica de autoactualización).
- Docs: `docs/handoff.md` movido a `docs/handoff/archive/handoff_v2.9.1.md`; este `CURRENT.md`; `docs/prompt_siguiente_chat.md` apunta a la nueva ruta.
- Verificado: los 3 `strings.xml` parsean como XML válido. **No** hay tests ni compilación.

## 4. ESTADO DEL CÓDIGO
- Rama: `preview` (según el handoff previo). El zip subido **no incluye `.git`** → commit/working tree **NO CONFIRMADO**; se esperan todos los archivos de arriba como cambios sin commit.
- Dependencias: ninguna nueva (usa `java.util.zip`, libsu y `kotlinx.coroutines` ya presentes).
- Manifest: sin cambios (no hace falta `REQUEST_INSTALL_PACKAGES`: instala con root).

## 5. DECISIONES TÉCNICAS
- **Instalar con root (`pm install -r -S`) en vez de PackageInstaller**: la app ya depende de root y la firma es la misma (keystore estable). Alternativa descartada: intent de instalación del sistema (pide permiso «orígenes desconocidos» y más toques). No hacer: tocar keystore/`applicationId`.
- **Fallback de APK dentro del zip**: releases antiguos no traen APK suelto.
- **Ámbar prevalece sobre verde**: tras actualizar la app, módulo y app difieren y eso es justo lo que se debe avisar al reabrir.
- **Comparación por números** (`sameVersion`): `module.prop` usa `v2.9.2`, la app `2.9.2`.
- No tocar `RootOps.kt` (regla del proyecto); la lógica root nueva vive en `Updater.kt`.

## 6. PROBLEMAS Y ERRORES / RIESGOS (todo NO CONFIRMADO)
- No compilado. Puntos a vigilar en CI: `getOrElse { return@withContext … }`, `return@withContext` dentro de `repeat`, lambda final dentro de `when (val out = Updater.updateApp(...) { })`, `LocalContentColor` en `LoadingIndicator`.
- `releases/latest` ignora pre-releases: en `preview` (tags `2.9.2-preview.N`, pre-release) la app **no verá** actualizaciones; solo publica `latest` la rama `main`.
- Si el release más reciente es anterior a la versión instalada (build de preview), el modo ámbar descargaría un módulo que sigue sin coincidir.
- SELinux podría impedir al `pm` leer el APK por stdin desde `cacheDir`: si falla, mirar `/data/local/tmp/sdbind_install.log` (alternativa: copiar a `/data/local/tmp` y `pm install -r <ruta>`).
- Si el usuario instala el módulo manualmente, `customize.sh` ya reinstala el APK incluido.

## 7. PRÓXIMOS PASOS
1. **PRÓXIMO PASO EXACTO**: ejecutar los comandos Termux de abajo y revisar `ciwatch`; si falla la compilación, pedir el log y corregir.
2. Probar en el dispositivo: (a) toque en «Buscar» sin versión nueva → verde; (b) con release nuevo → se instala, la app se reinicia y, tras reabrir, ámbar si el módulo no coincide; (c) «Flashear módulo» abre el gestor root con el zip.
3. Decidir si la app debe poder ver pre-releases (ver riesgos).
4. Pendientes antiguos del archivo histórico (fuente `.ttf` redonda, estilo de explorador de carpetas/diálogos).

## 8. BLOQUEOS
- Sin red en el chat: no se puede compilar; falta el resultado de GitHub Actions.
- Decisión del usuario pendiente: ¿incluir pre-releases en la búsqueda? (por defecto: no).

## 9. ARCHIVOS IMPORTANTES
`app/src/main/java/com/sdcardbind/manager/Updater.kt` (lógica de actualización) · `MainActivity.kt` (`UpdatesCard`, `AboutPane`) · `ui/UiKit.kt` (`UiCard`) · `.github/workflows/build.yml` (assets del release) · `module/module.prop` y `app/build.gradle.kts` (versiones, subir siempre ambas) · `module/customize.sh` (instala el APK al flashear) · `docs/handoff/archive/handoff_v2.9.1.md`.

## INSTRUCCIONES PARA EL PRÓXIMO CHAT
1. Lee este archivo y el histórico; verifica con `git status`/`git log` que la rama es `preview` y qué hay sin commit.
2. Si el usuario trae un log de Actions, corrige errores en `Updater.kt`/`UpdatesCard` primero.
3. Reglas: textos en los 3 `strings.xml`; solo colores del `ColorScheme` salvo semáforo verde/ámbar; no tocar keystore ni `applicationId`; subir versión en `build.gradle.kts` **y** `module.prop`; no usar branding de la app de referencia.
4. Al terminar, actualiza este `CURRENT.md` (y archiva el anterior si cambia mucho).

### Comandos Termux (una línea cada uno)
```bash
cd /storage/emulated/0/Download && unzip -o sdbind_project.zip -d sdbind_project
cd sdbind_project
rm -f docs/handoff.md
git add -A
git commit -m "feat(updates): tarjeta de actualizaciones de la app (verde/ámbar) y flasheo del módulo, v2.9.2"
git push origin preview
ciwatch
```
(`rm -f docs/handoff.md` porque se movió a `docs/handoff/archive/`.)
