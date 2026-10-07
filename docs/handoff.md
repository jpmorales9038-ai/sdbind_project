# Handoff — Réplica de la UI de Dolby Atmos en SD Bind

> Para el siguiente chat. Léelo completo antes de tocar código.
> Ruta del proyecto en el teléfono: `/storage/emulated/0/Download/sdbind_project/` · Rama de trabajo: **`preview`**
> (el workflow `.github/workflows/build.yml` ya compila en push a `main` y `preview`; en `preview` publica un pre-release).

## 1. Qué se pidió
Replicar "tal cual" la interfaz de la app **Dolby Atmos** (capturas en `docs/reference/`) dentro de SD Bind,
manteniendo toda la funcionalidad existente (vínculos, root, explorador, actualizador).

Referencias: `docs/reference/dolby_inicio.png` (Inicio) y `docs/reference/dolby_ecualizador.png` (Ecualizador).
**No se tiene captura de la 3ª pestaña (ajustes) de Dolby**: "Ajustes" se resolvió por inferencia con el mismo lenguaje visual.

## 2. Lenguaje visual extraído (1080 px ≈ 392 dp)
| Elemento | Medida / regla |
|---|---|
| Margen lateral | 16 dp |
| Título de pantalla | 36 sp, SemiBold, alineado a la izquierda; acciones = iconos sin fondo a la derecha (botones de 48 dp) |
| Cards | radio 36 dp, padding 20 dp, color `surfaceContainerHigh`, separación 16 dp |
| Cabecera de card | icono 26 dp en `primary` + título 22 sp Medium |
| Hero (Inicio) | banner de 120 dp con degradado diagonal `secondaryContainer → lerp(tertiaryContainer, tertiary, .5)` + glifo de 11 barras (`primary`); debajo fila con título 24 sp SemiBold, estado 16 sp y `Switch` con ✕/✓ en el pulgar |
| Campos/tiles dentro de cards | `surfaceContainerHighest` (en el kit: `dolbyInnerColor()`) |
| Barra de navegación | píldora flotante `secondaryContainer`, padding 8 dp, items de 56 dp; el activo = píldora `primary` con icono + etiqueta 20 sp, los demás solo icono. Superpuesta al contenido, 16 dp sobre la barra de gestos |
| Colores | todos del `ColorScheme` dinámico (Material You). Nada hardcodeado |

## 3. Qué se hizo (archivos)
- **NUEVO** `app/src/main/java/com/sdcardbind/manager/ui/DolbyKit.kt` — kit reutilizable:
  `DolbyHeader`, `DolbyCard`, `dolbyInnerColor()`, `BarsGlyph`, `DolbyHeroCard`, `DolbyNavBar`/`DolbyNavItem`,
  constantes `DolbyScreenPadding` y `DolbyNavClearance` (120 dp de aire inferior para la barra flotante).
- `MainActivity.kt`:
  - Scaffold sin `bottomBar` ni FAB; la barra (`AppNavBar` → `DolbyNavBar`) se dibuja en el `Box` raíz, alineada abajo.
  - **Inicio** (`HomePane`): header "SD Bind" con acciones ⓘ (va a Ajustes) y ⟳ (refresca almacenamiento);
    `DolbyHeroCard` "Usar vínculos" Activado/Desactivado; card Almacenamiento (anillos; long-press sigue refrescando);
    card Vínculos (lista + botón "Añadir vínculo", que sustituye al FAB).
  - **Interruptor del hero**: ON = `RootOps.saveAndApply(entries)` (lo que hacía "Montar todo"); OFF = `unmountAllNow()`.
    Estado mostrado = `entries.any { status == "MOUNTED" }`. Si no hay SD/OTG o no hay vínculos, muestra snackbar en vez de actuar.
    Se eliminaron `ActionButtons`, `StorageHero` y `HomeHeader` (sus funciones quedaron absorbidas).
  - **Registro** (`LogPane`): header con acciones Compartir y Borrar (el FAB de borrar desapareció).
  - **Ajustes** (antes "Acerca de", `AboutPane`): mismas cards Dolby (Acerca de, Actualizaciones, 4 mini-cards).
  - `BindCard` ahora usa `dolbyInnerColor()` para destacar dentro de su card.
- `res/values*/strings.xml` (es, es-rES, en): nuevas `tab_settings`, `use_binds`, `state_on`, `state_off`.
- Sin cambios: `RootOps`, `Updater`, `Theme`, selector de carpetas y explorador (pantallas completas con `TopAppBar`, fuera del alcance de las capturas).

## 4. Estado / verificación
- 🔧 Primer CI en `preview` falló solo por un import faltante (`androidx.compose.runtime.getValue` en `DolbyKit.kt`); ya corregido, pendiente de confirmar el siguiente build.
- ⚠️ **No se pudo compilar** en el entorno del chat (sin red / sin Gradle). El código se revisó a mano. Primer paso del siguiente chat:
  mirar el resultado del workflow de GitHub Actions en `preview` y corregir errores de compilación si los hay.
  Puntos con más riesgo de API (Compose Material3 `1.5.0-alpha18`): `Switch(thumbContent = …)`, `Button(shapes = …, colors = …)`, `Icons.Filled.Notes` (deprecado pero ya se usaba).
- Pendiente de validar en dispositivo: que el degradado/colores se vean como la captura con el wallpaper del usuario; que la barra flotante no tape el último elemento en pantallas pequeñas y en horizontal.
- Warnings esperados: imports/variables sin uso (`fabSpec`, `scaleIn`, `PagerState`, strings `save_mount`, `root_ok`, `tab_about_short`…). Limpiarlos es opcional.

## 5. Siguientes pasos sugeridos
1. Confirmar que compila (CI) y subir a `preview`.
2. Comparar contra las capturas y afinar medidas (glifo, alturas, tamaños de texto).
3. Si el usuario manda la captura de la 3ª pestaña de Dolby, rehacer `AboutPane` calcándola.
4. Opcional: adoptar el estilo Dolby en `FolderPickerScreen` y `FileBrowserScreen` (header grande + cards) y en los diálogos.
5. Opcional: animar el glifo de barras cuando los vínculos estén activos.
6. Si se cambia algo funcional, subir `versionCode`/`versionName` en `app/build.gradle.kts` **y** `module/module.prop` a la vez (el updater compara `module.prop`).

## 6. Reglas del proyecto a respetar
- Mantener Material 3 Expressive y colores dinámicos; no introducir colores fijos (salvo el verde semáforo de `StatusChip`).
- No tocar la keystore ni `applicationId` (el `pm install -r` desde el módulo depende de la firma).
- Textos siempre en `strings.xml` de **los tres** idiomas (`values`, `values-es-rES`, `values-en`).
- No añadir lógica root nueva en composables: va en `RootOps.kt`.

## 7. Comandos Termux (entorno ya configurado: git, rama `preview`, credenciales)

El zip no trae carpeta raíz, por eso `-d sdbind_project`. Se extrae encima y conserva `.git`.

```bash
cd /storage/emulated/0/Download && unzip -o sdbind_project.zip -d sdbind_project
cd sdbind_project && git add -A && git commit -m "feat(ui): réplica de la interfaz de Dolby Atmos" && git push origin preview
```

`unzip` no borra archivos que ya no existan; si un cambio elimina alguno, bórralo a mano antes del commit.
