# Handoff — SD Bind

> Léelo completo antes de tocar código.
> Teléfono: `/storage/emulated/0/Download/sdbind_project/` · Rama: **`preview`**
> CI (`.github/workflows/build.yml`): compila en push a `main` y `preview`; en `preview` publica pre-release `v<versión>-preview.<run>`. El zip del módulo se llama `sdcard_bind_ui_con_app_v<versión>[-preview.<run>].zip`.
> Estado: **v2.9.8** (app 112 / módulo 362). Todo lo anterior está resuelto; solo se documenta lo vigente.

## 1. Objetivo de estilo
Estilo propio (no un clon) inspirado en una app de ajustes de audio (las capturas de referencia ya se borraron): título grande con acciones, cards muy redondeadas, tarjeta principal con interruptor y barra de navegación flotante en píldora. La funcionalidad no cambia. Sin gráficos decorativos de ecualizador, fuentes redondeadas y negrita en títulos, icono propio.

## 2. Reglas de estilo (1080 px ≈ 392 dp)
| Elemento | Regla |
|---|---|
| Margen lateral | 16 dp |
| Título de pantalla | 28 sp Bold a la izquierda; acciones = iconos sin fondo a la derecha |
| Cards | radio 36 dp, padding 20 dp, `surfaceContainerHigh`, separación 16 dp |
| Cabecera de card | icono 26 dp (`primary`) + título 18 sp Bold |
| Card principal | `secondaryContainer` sólido, título 20 sp Bold, estado 14 sp, `Switch` con ✕/✓ |
| Tiles en cards | `uiInnerColor()` = `surfaceContainerHighest` |
| Barra de navegación | píldora flotante `secondaryContainer`; pestaña activa = píldora `primary` con icono + etiqueta, el resto solo icono; superpuesta al contenido |
| Difuminado inferior | `UiBottomFade`: transparente → `background`, alto = inset de gestos + 128 dp |
| Tipografía | `roundedTypography` ×0.9 |
| Colores | todos del `ColorScheme` dinámico (excepto verde/ámbar semánticos, ver §6) |

## 3. Mapa del código
- `ui/UiKit.kt`: `UiHeader`, `UiCard` (acepta `containerColor/contentColor`), `uiInnerColor()`, `uiSuccessTone()`/`uiWarningTone()`, `UiHeroCard`, `UiNavBar`/`UiNavItem`, `UiBottomFade`, `UiScreenPadding`, `UiNavClearance` (120 dp).
- `ui/Theme.kt`: `SideEffect` fija `isAppearanceLightStatusBars/NavigationBars = !dark` (la Activity maneja `uiMode` sin recrearse).
- `ui/Fonts.kt`: familia con pesos reales. Fuente variable → un `Font` por peso con `variationSettings` (+ eje `ROND=100`); estática → 400 + hermanos `-Medium`/`-Bold`; sin archivo → familias del sistema por nombre. Fallback definitivo si algún día no se ve redonda: bundlear un `.ttf` (p. ej. Nunito) en `res/font/`.
- `MainActivity.kt`:
  - Scaffold sin `bottomBar` ni FAB; `AppNavBar` → `UiNavBar` va en el `Box` raíz.
  - Inicio (`HomePane`): header "Inicio" sin acciones (refrescar = deslizar o mantener pulsada la card Almacenamiento); `UiHeroCard` "Usar vínculos"; cards Almacenamiento (long-press refresca) y Vínculos (botón "Añadir vínculo").
  - Interruptor: ON = `RootOps.saveAndApply(entries)`; OFF = `unmountAllNow()`; estado = `entries.any { status == "MOUNTED" }`. Sin SD/OTG o sin vínculos → snackbar.
  - Registro (`LogPane`): parsea `mount.log` (`YYYY-MM-DD HH:MM:SS mensaje`) en `LogLine` con nivel (`logLevelOf`) y etiqueta (`logTagOf`) por palabras clave; **si cambian los mensajes del módulo hay que ajustarlas**. Buscador, filtro por nivel, compartir/borrar, botones ir al inicio/final. Apaisado (`landscape`): controles y chips de nivel siempre visibles a la izquierda y el registro a toda la altura a la derecha.
  - Anillo de almacenamiento: `CircularProgressIndicator` plano; solo anima el llenado 0→valor (`tween(2200, EaseInOutCubic)`).
  - `PullRefresh` (`PullToRefreshBox`) en Inicio y Registro.
  - `AppLogo` usa `R.drawable.app_logo` (`drawable-nodpi/app_logo.png`), **no** `R.mipmap.ic_launcher` (adaptive-icon que Compose rechaza). Si cambias el icono, copia también ese PNG.
- Icono: adaptativo (`mipmap-anydpi-v26/ic_launcher.xml` → `ic_launcher_foreground.png` reducido ~52 % + `ic_launcher_background.png` `#252B69`). La cadena va **sin sombra larga**: fondo plano `#252B69` en todos los PNG. Con `minSdk 26` no hacen falta PNG de launcher ni icono redondo. El logo de Ajustes es `drawable-nodpi/app_logo.png` (**no** `R.mipmap.ic_launcher`: Compose rechaza el adaptive-icon); `module/icon.png` es el mismo arte (lo usa el README). Si cambias el icono, actualiza esos 4 archivos.
- Actualizaciones (Ajustes): `Updater.kt` (red, semver, instalación), `UpdateController.kt` (estado), `UpdatesCard.kt` (UI).
  - Automático: al abrir la app (`onForeground`) y cada 30 min en primer plano (`autoCheck`, mín. 10 min entre búsquedas) → `GET /repos/<repo>/releases?per_page=30`, elige la versión más alta (incluye pre-releases) con asset `.apk`/`.zip`. Si es más nueva, **descarga y verifica** el APK (paquete; la firma la valida `pm`) y la tarjeta pasa a `AppPhase.Ready`: **verde** + botón "Instalar actualización" que solo confirma (`installReady()`: `pm install -r` vía root en proceso desligado que relanza la app; guarda `updated_to` y al reabrir sale verde "App actualizada"). Sin actualización no hay botón. Los fallos de la búsqueda automática son silenciosos.
  - Deslizar en Ajustes (`PullRefreshAwait` → `UpdateController.refresh()`): repite la búsqueda al momento, con mensajes ("Ya estás al día" solo si el módulo no está desfasado ni pendiente de reinicio; errores). El APK ya descargado se reutiliza (`ready_version` en prefs + `cacheDir/sdbind_update.apk`).
  - Si la versión de la app ≠ la de `module.prop` (o `/data/adb/modules_update/<id>/`) la tarjeta pasa a ámbar con "Descargar y flashear módulo" (si a la vez hay una actualización lista, manda el verde y el botón de flashear espera a que se instale) El botón flashea **desde la propia app**, sin gestor: `UpdateController.flashModule()` → `Updater.flashModuleZip` copia el zip a `/data/local/tmp`, ejecuta con root `ksud module install` / `apd module install` / `magisk --install-module` en un proceso desligado (el `customize.sh` hace `pm install -r` de la app y mata el proceso; el script relanza la app) y muestra las últimas líneas del instalador en la tarjeta. Sin instalador → mensaje; ya no hay selector de gestores ni `<queries>` en el manifest. **Sin probar en dispositivo**: si `module install` falla en tu ROOT, el log sale en la tarjeta.
  - Semver: la final > sus pre-releases (`2.9.3` > `2.9.3-preview.99`); `compareBase`/`sameVersion` comparan solo números (módulo sin sufijo), `sameBuild` la build exacta.
  - `versionName = "2.9.x$buildSuffix"`; el CI pasa `-PbuildSuffix="-preview.<run>"`. **No pongas el sufijo a mano.**
  - Gotcha: tras instalar una **final** (`main`), las pre-releases de esa misma versión dejan de ofrecerse; sube versión (app y módulo a la vez) antes de seguir en `preview`.
  - Limitación: la actualización de la app requiere root y la misma firma; sin root no hay instalador de respaldo.
  - Zip del módulo ("Descargar y flashear módulo"): `Updater.downloadModule` lo guarda en caché y en `Download/` (copia de respaldo) con el nombre versionado del asset (`sdcard_bind_ui_con_app_v<versión>.zip`) y borra antes las versiones antiguas (`sdcard_bind_ui.zip`, `sdcard_bind_ui_con_app_v*.zip`).
- Rendimiento (Ajustes, `PerformanceCard.kt` + `PerfSettings.kt`; **sin tocar `RootOps.kt`**): `PerfController` lee/escribe `$MODDIR/perf.conf` (`readahead_kb`, `watch_interval`, `light_guard`, `fast_label`, formato `clave=número`) con libsu y ejecuta `webctl.sh perf` (→ `perf_apply`). El módulo (`functions.sh`: `perf_load`, `perf_tune_src`, `perf_restore`, `_protect_media_fuse` con caché de PIDs, `_label_skippable`) lo lee con solo builtins en cada vuelta de `service.sh`. Sin `perf.conf` todo se comporta como antes. `customize.sh` conserva `perf.conf` al actualizar; `uninstall.sh` y «Sistema» devuelven `read_ahead_kb` (originales en `.watch_grace/.ra_orig_*`). Los mensajes del log empiezan por «Rendimiento:» (nivel INFO). **Sin probar en dispositivo**: la lectura anticipada depende de que el sysfs del disco sea escribible; si no, se registra y no se cambia nada.
- Fluidez / arranque (v2.9.8, **sin probar en dispositivo**): causa principal = el APK instalado con `pm install -r` queda solo verificado (JIT/intérprete) hasta el dexopt nocturno, por eso va a tirones y vuelve tras cada actualización. `PerfBoost.kt` ejecuta una vez por `versionName` `cmd package compile -m speed -f <pkg>` (shell root propio, 15 s tras abrir; marca `aot_done` en prefs); se nota desde el **siguiente** arranque. Además: fuente del sistema detectada en hilo de fondo (`BindApplication`, `loadAppFontFamily` memoizada), `perf.load` y búsqueda de actualizaciones diferidas 3 s / 2,5 s en el arranque, sondeo de volúmenes cada 3 s (antes 1,5 s), `parseLog` fuera del hilo principal y el texto del anillo solo recompone al cambiar el entero. `baseline-prof.txt` solo marca clases (sin métodos): mejora opcional con reglas `HSPL…;->**(**)**` (no probada, por eso no se tocó). Si sigue pesado, medir con `adb shell dumpsys package com.sdcardbind.manager | grep -i status` (debe decir `speed`).
- Barra de navegación: pestañas Inicio (`Home`), Registro (`Notes`) y Ajustes (icono `Settings`, la tuerca). En apaisado la píldora va abajo a la izquierda (`BottomStart`) y no hay `UiBottomFade`.
- Sin cambios: `RootOps.kt`, selector de carpetas, explorador.

## 4. Pendiente opcional
- Mismo estilo en `FolderPickerScreen`, `FileBrowserScreen` y diálogos.

## 5. Reglas del proyecto
- Material 3 Expressive + colores dinámicos; sin colores fijos salvo verde/ámbar semánticos (`StatusChip`, tarjeta de actualizaciones).
- No tocar keystore ni `applicationId` (`pm install -r` depende de la firma).
- Textos siempre en los tres `strings.xml` (`values`, `values-es-rES`, `values-en`).
- Lógica root nueva solo en `RootOps.kt`, nunca en composables.
- Si cambias algo funcional, sube versión en `app/build.gradle.kts` **y** `module/module.prop` a la vez.
- No usar nombres, textos ni gráficos de la app de referencia.
- El zip de entrega del proyecto se llama `sdbind_project_v<versión>.zip` (versión de `module.prop`).
- Aviso de uso: avisar al usuario al acercarse al 90 % del uso gratuito; si una tarea queda a medias, documentar qué se hizo y qué falta en `docs/prompt_siguiente_chat.md` y aquí.

## 6. Comandos Termux
Una línea por comando; el zip no trae carpeta raíz y se extrae encima conservando `.git`.

```bash
cd /storage/emulated/0/Download && unzip -o sdbind_project_v2.9.8.zip -d sdbind_project
cd sdbind_project
git add -A
git commit -m "perf: compilación AOT con root, arranque diferido, fuente en segundo plano y menos recomposiciones (v2.9.8)"
git push origin preview
cinotif
```

No hay archivos que borrar (`rm`): solo se añade `PerfBoost.kt`.
