# Handoff — SD Bind

> Léelo completo antes de tocar código.
> Teléfono: `/storage/emulated/0/Download/sdbind_project/` · Rama: **`preview`**
> CI (`.github/workflows/build.yml`): compila en push a `main` y `preview`; en `preview` publica pre-release `v<versión>-preview.<run>`.
> Estado: **v2.9.3** (app 107 / módulo 357). Todo lo anterior está resuelto; solo se documenta lo vigente.

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
  - Inicio (`HomePane`): header "Inicio" con ⓘ (Ajustes) y ⟳; `UiHeroCard` "Usar vínculos"; cards Almacenamiento (long-press refresca) y Vínculos (botón "Añadir vínculo").
  - Interruptor: ON = `RootOps.saveAndApply(entries)`; OFF = `unmountAllNow()`; estado = `entries.any { status == "MOUNTED" }`. Sin SD/OTG o sin vínculos → snackbar.
  - Registro (`LogPane`): parsea `mount.log` (`YYYY-MM-DD HH:MM:SS mensaje`) en `LogLine` con nivel (`logLevelOf`) y etiqueta (`logTagOf`) por palabras clave; **si cambian los mensajes del módulo hay que ajustarlas**. Buscador, filtro por nivel, compartir/borrar, botones ir al inicio/final.
  - Anillo de almacenamiento: `CircularProgressIndicator` plano; solo anima el llenado 0→valor (`tween(2200, EaseInOutCubic)`).
  - `PullRefresh` (`PullToRefreshBox`) en Inicio y Registro.
  - `AppLogo` usa `R.drawable.app_logo` (`drawable-nodpi/app_logo.png`), **no** `R.mipmap.ic_launcher` (adaptive-icon que Compose rechaza). Si cambias el icono, copia también ese PNG.
- Icono: adaptativo (`mipmap-anydpi-v26/ic_launcher.xml` → `ic_launcher_foreground.png` reducido ~52 % + `ic_launcher_background.png` `#252B69`). Con `minSdk 26` no hacen falta PNG de launcher ni icono redondo. El logo de Ajustes es `drawable-nodpi/app_logo.png` (**no** `R.mipmap.ic_launcher`: Compose rechaza el adaptive-icon); `module/icon.png` es el mismo arte (lo usa el README). Si cambias el icono, actualiza esos 4 archivos.
- Actualizaciones (Ajustes): `Updater.kt` (red, semver, instalación), `UpdateController.kt` (estado), `UpdatesCard.kt` (UI).
  - "Actualizar app": `GET /repos/<repo>/releases?per_page=30`, elige la versión más alta (incluye pre-releases) con asset `.apk`/`.zip`, descarga, verifica paquete y firma, `pm install -r` vía root en proceso desligado que relanza la app; guarda `updated_to` y al reabrir la tarjeta sale verde.
  - Si la versión de la app ≠ la de `module.prop` (o `/data/adb/modules_update/<id>/`) la tarjeta pasa a ámbar con "Descargar y flashear módulo" (`Updater.openForFlash`).
  - Semver: la final > sus pre-releases (`2.9.3` > `2.9.3-preview.99`); `compareBase`/`sameVersion` comparan solo números (módulo sin sufijo), `sameBuild` la build exacta.
  - `versionName = "2.9.3$buildSuffix"`; el CI pasa `-PbuildSuffix="-preview.<run>"`. **No pongas el sufijo a mano.**
  - Gotcha: tras instalar una **final** (`main`), las pre-releases de esa misma versión dejan de ofrecerse; sube versión (app y módulo a la vez) antes de seguir en `preview`.
  - Limitación: la actualización de la app requiere root y la misma firma; sin root no hay instalador de respaldo.
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
- Aviso de uso: avisar al usuario al acercarse al 90 % del uso gratuito; si una tarea queda a medias, documentar qué se hizo y qué falta en `docs/prompt_siguiente_chat.md` y aquí.

## 6. Comandos Termux
Una línea por comando; el zip no trae carpeta raíz y se extrae encima conservando `.git`.

```bash
cd /storage/emulated/0/Download && unzip -o sdbind_project.zip -d sdbind_project
cd sdbind_project
rm -f app/src/main/res/drawable/avatar.jpg module/banner.png module/banner.webp app/src/main/res/mipmap-xxhdpi/ic_launcher.png app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml && rm -rf docs/reference
git add -A
git commit -m "chore: eliminar recursos sin uso (avatar, banner, iconos PNG/redondo, capturas de referencia) y actualizar docs"
git push origin preview
cinotif
```

`unzip` no borra archivos que ya no existan en el zip, por eso el `rm`. Esta entrega elimina solo recursos sin referencias (comprobado por búsqueda); no hay cambios de código.
