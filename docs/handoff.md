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
  - **Registro**: header con Compartir y Borrar. **Ajustes** (antes "Acerca de"): mismas cards.
- `ui/Theme.kt`: `SideEffect` que fija `isAppearanceLightStatusBars/NavigationBars = !dark` con el tema **actual**
  (la Activity maneja `uiMode` sin recrearse, y `enableEdgeToEdge()` solo decide al crearla → iconos con el color viejo).
- `ui/Fonts.kt`: la familia ahora se arma con **pesos reales** (400–800). Antes se envolvía un solo `Typeface` y Compose
  ignoraba el peso, por eso la negrita no se veía. Busca Google Sans Rounded/Flex en `/system/fonts`; si no hay, prueba
  familias del sistema por nombre.
- **Icono**: nuevo (SD rosa + carpeta morada + enlace). PNGs en `res/mipmap-xxhdpi/` (`ic_launcher_foreground` con fondo transparente, `ic_launcher_background` sólido #1F1A26, y las versiones legacy cuadrada/redonda) y `module/icon.png`. Los XML adaptativos no cambiaron.
- **Ajustes**: se eliminó `AppMark` (círculos animados); ahora `AppLogo` muestra el icono estático (mismos mipmaps de fondo + primer plano).
- Strings nuevas en los 3 idiomas: `tab_settings`, `use_binds`, `state_on`, `state_off`.
- Sin cambios: `RootOps`, `Updater`, selector de carpetas y explorador.

## 4. Estado / verificación
- El primer CI falló por un import faltante (`getValue` en el kit); corregido. El código de esta versión **no se pudo compilar
  en el chat** (sin red): revisar el resultado de Actions en `preview`.
- Riesgo de API en esta versión: `Font(file, weight, variationSettings)`, `Font(DeviceFontFamilyName(..), weight)` y
  `@file:OptIn(ExperimentalTextApi)` en `Fonts.kt`; `Switch(thumbContent)`; `Button(shapes, colors)`.
- **Limitación de fuentes**: si el teléfono no trae una fuente redondeada en `/system/fonts`, no hay forma de "redondear"
  solo con código. Solución definitiva: meter un `.ttf` redondeado (p. ej. Nunito) en `app/src/main/res/font/` y usarlo
  como familia base. No se pudo bundlear en el chat por no haber red.
- Pendiente de validar en el teléfono: icono en el launcher (también tema monocromo/redondo), difuminado sobre la barra de gestos, tamaños de texto; barra de estado al cambiar claro/oscuro con la app abierta; negrita en títulos;
  que la barra flotante no tape el último elemento en pantallas pequeñas/horizontal.

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
El zip no trae carpeta raíz, por eso `-d sdbind_project`. Se extrae encima y conserva `.git`.

```bash
cd /storage/emulated/0/Download && unzip -o sdbind_project.zip -d sdbind_project
cd sdbind_project && git add -A && git commit -m "feat(ui): nuevo icono, difuminado inferior, hero sólido y fuentes más pequeñas" && git push origin preview
```

`unzip` no borra archivos que ya no existan: si un cambio elimina alguno (p. ej. al renombrar), bórralo a mano antes del commit.
Si todavía existen de la versión anterior, bórralos: `rm -f app/src/main/java/com/sdcardbind/manager/ui/DolbyKit.kt docs/reference/dolby_*.png`.
