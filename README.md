# SD Bind Manager — Proyecto completo (módulo KSU + app Compose)

Este paquete contiene:

- `module/` — el módulo de KernelSU (scripts + WebUI de respaldo).
- `app/` — el código fuente de la app Android en Jetpack Compose.
- `.github/workflows/build.yml` — compila el APK automáticamente en GitHub
  y arma el zip final del módulo, sin que necesites Android Studio ni una PC.

## Cómo compilarlo desde el teléfono (paso a paso)

1. **Crea una cuenta de GitHub** si no tienes una (gratis, en github.com).
2. Entra a github.com desde el navegador de tu teléfono → **New repository**
   (botón "+" arriba a la derecha) → dale cualquier nombre (ej.
   `sd-bind-manager`) → **Create repository**. Puede ser privado o público,
   no importa.
3. Dentro del repo recién creado, busca la opción **"uploading an existing
   file"** (aparece en la pantalla inicial del repo vacío) o **Add file →
   Upload files**.
4. Sube **todos los archivos y carpetas** de este proyecto manteniendo la
   misma estructura de carpetas (arrastra o selecciona todo el contenido
   descomprimido). Si tu navegador no te deja subir carpetas completas de
   una vez, sube el `.zip` completo a cualquier app de almacenamiento en la
   nube, ábrela con un explorador de archivos con función "extraer/comprimir"
   (por ejemplo *Material Files* o *ZArchiver*, gratis), y sube los archivos
   ya extraídos desde ahí — el navegador sí permite seleccionar múltiples
   archivos de una carpeta local.
5. Confirma la subida (**Commit changes**).
6. Ve a la pestaña **Actions** del repositorio. Debería aparecer un flujo
   llamado **"Build APK + módulo KSU"** corriendo automáticamente (tarda
   unos 3-6 minutos la primera vez).
7. Cuando termine (ícono verde ✔️), entra a esa ejecución y baja hasta
   **Artifacts** → descarga **`sdcard_bind_ui_con_app`**. Eso te da un
   `.zip` que contiene el zip real del módulo.
8. Extrae ese zip descargado (con Material Files/ZArchiver) — adentro está
   `sdcard_bind_ui_con_app.zip`, que es el que instalas en KernelSU-Next
   Manager → Módulos → Instalar.
9. Reinicia el teléfono. La app **SD Bind Manager** debería instalarse sola
   y aparecer en tu cajón de apps.

## Si algo falla en la compilación (Actions en rojo ❌)

Abre el log del paso que falló (aparece marcado) y pégamelo aquí — lo más
común sería una versión de alguna librería que cambió; lo ajusto y vuelves
a subir el archivo corregido.

## Nota

El build que genera este workflow es un **APK "debug"** (autofirmado por
el propio proceso de compilación). Funciona perfectamente para uso
personal instalado directamente en tu teléfono; no está pensado para
publicarse en una tienda de apps.
