# Handoff — Estilo visual de SD Bind

> Para el siguiente chat. Léelo completo antes de tocar código.
> Proyecto en el teléfono: `/storage/emulated/0/Download/sdbind_project/` · Rama de trabajo: **`preview`**
> (el workflow `.github/workflows/build.yml` compila en push a `main` y `preview`; en `preview` publica un pre-release).

## 1. Qué se pidió
Aplicar a SD Bind el **estilo** (no un clon) de una app de ajustes de audio tomada como referencia
(capturas en `docs/reference/`): título grande con acciones, cards muy redondeadas con icono + título,
interruptor principal y barra de navegación flotante en píldora. La app **no debe copiar** la interfaz
ni llevar nombres/branding de esa app: es SD Bind con otro estilo. La funcionalidad existente no cambia.

Decisiones del usuario (ya aplicadas):
- Sin gráficos decorativos tipo "barras ecualizadoras".
- Fuentes redondeadas y **negrita en títulos**.
- La barra de estado debe cambiar de color con el tema claro/oscuro.
- Icono propio (tarjeta SD + carpeta + enlace), sin animación de círculos en Ajustes, tarjeta principal sin degradado,
  difuminado sobre la barra de gestos y tamaños de fuente más pequeños.

## 2. Reglas de estilo (1080 px ≈ 392 dp)
| Elemento | Regla |
|---|---|
| Margen lateral | 16 dp |
| Título de pantalla | 28 sp **Bold**, a la izquierda; acciones = iconos sin fondo a la derecha |
| Cards | radio 36 dp, padding 20 dp, `surfaceContainerHigh`, separación 16 dp |
| Cabecera de card | icono 26 dp (`primary`) + título 18 sp **Bold** |
| Card principal | fondo sólido `secondaryContainer` (sin degradado), título 20 sp Bold, estado 14 sp y `Switch` con ✕/✓ |
| Tiles dentro de cards | `uiInnerColor()` = `surfaceContainerHighest` |
| Barra de navegación | píldora flotante `secondaryContainer` (items de 52 dp, etiqueta 14 sp); pestaña activa = píldora `primary` con icono + etiqueta; el resto solo icono; superpuesta al contenido |
| Difuminado inferior | `UiBottomFade`: degradado transparente → `background` de (inset de gestos + 128 dp) de alto, detrás de la píldora |
| Tipografía | `roundedTypography` escala todo ×0.9; tamaños fijos en sp reducidos (ver `UiKit.kt` y `MainActivity.kt`) |
| Colores | todos del `ColorScheme` dinámico, nada fijo |

## 3. Implementación
- `ui/UiKit.kt` (kit): `UiHeader`, `UiCard`, `uiInnerColor()`, `UiHeroCard`, `UiNavBar`/`UiNavItem`, `UiBottomFade`,
  constantes `UiScreenPadding` y `UiNavClearance` (120 dp de aire inferior por la barra flotante).
- `MainActivity.kt`:
  - Scaffold sin `bottomBar` ni FAB; la barra (`AppNavBar` → `UiNavBar`) va en el `Box` raíz.
  - **Inicio** (`HomePane`): header "SD Bind" con ⓘ (va a Ajustes) y ⟳ (refresca); `UiHeroCard` "Usar vínculos";
    card Almacenamiento (long-press sigue refrescando); card Vínculos con lista y botón "Añadir vínculo" (reemplaza al FAB).
  - **Interruptor**: ON = `RootOps.saveAndApply(entries)`; OFF = `unmountAllNow()`. Estado = `entries.any { status == "MOUNTED" }`.
    Sin SD/OTG o sin vínculos muestra snackbar en vez de actuar.
  - **Registro** (`LogPane`): visor tipo terminal. Parsea `mount.log` (`YYYY-MM-DD HH:MM:SS mensaje`) en `LogLine` con nivel (`LogLevel`: E/W/S/I/D) y etiqueta (`LogTag`: MONTAJE/DESMONTAJE/VIGILANCIA/SISTEMA) por palabras clave en `logLevelOf`/`logTagOf` (si cambian los mensajes del módulo, hay que ajustarlas). Cada línea: barra lateral de color, letra de nivel, hora, chip de etiqueta y mensaje en monoespaciada; colores: error=`error`, aviso=ámbar, éxito=verde, info=`primary` (ámbar/verde fijos, como el semáforo de `StatusChip`). Cabecera con Compartir y Borrar, contador "Líneas a–b de n", buscador en píldora, filtro por nivel (FilterChip) y botones flotantes ir al inicio/final. Arranca en la última línea.
  - **Anillo de almacenamiento**: ahora `CircularProgressIndicator` plano (se quitó la animación ondulada infinita). Solo anima el llenado 0→valor al refrescar (mantener presionado, deslizar o cambio de porcentaje), con `tween(2200 ms, EaseInOutCubic)` (lento y suave). **Ajustes** (antes "Acerca de"): mismas cards.
- `ui/Theme.kt`: `SideEffect` que fija `isAppearanceLightStatusBars/NavigationBars = !dark` con el tema **actual**
  (la Activity maneja `uiMode` sin recrearse, y `enableEdgeToEdge()` solo decide al crearla → iconos con el color viejo).
- `ui/Fonts.kt`: la familia ahora se arma con **pesos reales** (400–800). Antes se envolvía un solo `Typeface` y Compose
  ignoraba el peso, por eso la negrita no se veía. Busca Google Sans Rounded/Flex en `/system/fonts`; si no hay, prueba
  familias del sistema por nombre.
- **Icono**: imagen del usuario usada **tal cual** (cadena cian/coral con sombra larga sobre azul marino). `ic_launcher.png` = la imagen recortada con sus esquinas redondeadas (también en `module/icon.png` y en el logo de Ajustes); `ic_launcher_round.png` = recorte circular. Para el icono adaptativo, `ic_launcher_foreground.png` es la misma imagen a pantalla completa, reducida (~52 %) para que la cadena entre en la zona segura de cualquier máscara, y con la sombra/fondo extendidos en diagonal hasta los bordes (sin costuras); `ic_launcher_background.png` es azul sólido `#252B69`. Los XML adaptativos no cambiaron. `module/banner.*` sigue con el diseño anterior.
- **Ajustes**: se eliminó `AppMark` (círculos animados); `AppLogo` muestra el icono estático (`R.mipmap.ic_launcher`).
- **Deslizar para refrescar**: `PullRefresh` (envoltorio de `PullToRefreshBox`) en Inicio (llama a `refresh(true)`: también repite la animación del anillo y muestra el snackbar) y en Registro (`refresh()`: recarga el log). El indicador se mantiene ~1,2 s.
- **Cabecera de Inicio**: ahora dice "Inicio" (`R.string.tab_home`) en lugar de "SD Bind".
- Strings nuevas en los 3 idiomas: `tab_settings`, `use_binds`, `state_on`, `state_off`.
- Sin cambios: `RootOps`, `Updater`, selector de carpetas y explorador.

## 4. Estado / verificación
- El primer CI falló por un import faltante (`getValue` en el kit); corregido. El código de esta versión **no se pudo compilar
  en el chat** (sin red): revisar el resultado de Actions en `preview`.
- Riesgo de API en esta versión: `Font(file, weight, variationSettings)`, `Font(DeviceFontFamilyName(..), weight)` y
  `@file:OptIn(ExperimentalTextApi)` en `Fonts.kt`; `Switch(thumbContent)`; `CircularProgressIndicator(progress, strokeWidth, strokeCap)`; `PullToRefreshBox` (`material3.pulltorefresh`); `SmallFloatingActionButton`; `FilterChip`; `Button(shapes, colors)`.
- **Limitación de fuentes**: si el teléfono no trae una fuente redondeada en `/system/fonts`, no hay forma de "redondear"
  solo con código. Solución definitiva: meter un `.ttf` redondeado (p. ej. Nunito) en `app/src/main/res/font/` y usarlo
  como familia base. No se pudo bundlear en el chat por no haber red.
- Pendiente de validar en el teléfono: icono en el launcher (también tema monocromo/redondo), difuminado sobre la barra de gestos, tamaños de texto; barra de estado al cambiar claro/oscuro con la app abierta; negrita en títulos;
  que la barra flotante no tape el último elemento en pantallas pequeñas/horizontal.

### Revisión v2.9.1 (cierre en Registro / Ajustes)
- **Ajustes se cerraba**: `AppLogo` usaba `painterResource(R.mipmap.ic_launcher)`, que en API 26+ resuelve al XML
  `adaptive-icon` y Compose lo rechaza (excepción). Ahora usa `R.drawable.app_logo` (`res/drawable-nodpi/app_logo.png`,
  copia de `mipmap-xxhdpi/ic_launcher.png`). **Si cambias el icono del launcher, copia también ese PNG.**
- **Registro**: no se halló una causa determinista por lectura del código (strings y formatos de los 3 idiomas están bien).
  Se quitó `IntrinsicSize.Min` de `LogRow` (barra lateral con `drawBehind`) y el buscador hereda la fuente del tema.
  Si sigue cerrándose, hace falta el `logcat` (`adb logcat -b crash` o `logcat -d | grep -A30 FATAL`).
- Verificado por lectura (no en dispositivo): barra de estado (`Theme.kt` fija `isAppearanceLight*` con el tema actual y la
  Activity maneja `uiMode`) y fuente con pesos reales (`Fonts.kt`). La detección de familia por nombre es correcta
  (`Typeface.create` devuelve `Typeface.DEFAULT` si no existe). Sin red no se pudo bundlear un `.ttf` redondeado.
- Versión subida a 2.9.1 (app 105 / módulo 355). Tarea 1 (errores de CI): no se recibió log.

## 5. Siguientes pasos sugeridos
1. Confirmar compilación (CI) y revisar en dispositivo.
2. Si la fuente sigue sin verse redonda: bundlear un `.ttf` en `res/font/`.
3. Opcional: mismo estilo en `FolderPickerScreen` / `FileBrowserScreen` y diálogos.
4. Si se cambia algo funcional, subir versión en `app/build.gradle.kts` **y** `module/module.prop` a la vez (el updater compara `module.prop`).

## 6. Reglas del proyecto
- Material 3 Expressive + colores dinámicos; sin colores fijos (salvo el verde semáforo de `StatusChip`).
- No tocar la keystore ni `applicationId` (el `pm install -r` desde el módulo depende de la firma).
- Textos siempre en `strings.xml` de **los tres** idiomas (`values`, `values-es-rES`, `values-en`).
- Nada de lógica root nueva en composables: va en `RootOps.kt`.
- No usar nombres, textos ni gráficos de la app de referencia dentro del código ni de la UI.

## 7. Comandos Termux (entorno ya configurado)
Una línea por comando: unzip del zip descargado del chat, git add, git commit, git push y después `ciwatch` (sin `cd`).
El zip no trae carpeta raíz, por eso `-d sdbind_project`. Se extrae encima y conserva `.git`.

```bash
cd /storage/emulated/0/Download && unzip -o sdbind_project.zip -d sdbind_project
cd sdbind_project
git add -A
git commit -m "fix(ui): cierre en Ajustes (logo adaptativo) y Registro, v2.9.1"
git push origin preview
ciwatch
```

`unzip` no borra archivos que ya no existan. En esta versión no se renombra ni elimina nada (solo se añade `app_logo.png`),
así que no hay `rm` pendientes. Si aún existen de versiones anteriores:
`rm -f app/src/main/java/com/sdcardbind/manager/ui/DolbyKit.kt docs/reference/dolby_*.png`.
