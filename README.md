<div align="center">
  <img src="module/icon.png" width="140" alt="Icono de SD Bind" />

  # SD Bind

  **Vincula tarjetas SD y unidades OTG dentro del almacenamiento interno de tu Android — sin copiar archivos.**

  

![versión](https://img.shields.io/badge/versión-v2.5.8-c5c0ff?style=for-the-badge&labelColor=131318)


  

![root](https://img.shields.io/badge/root-KernelSU%20%7C%20KernelSU--Next-1c1b21?style=for-the-badge)


  

![plataforma](https://img.shields.io/badge/plataforma-Android-1c1b21?style=for-the-badge)


</div>

<br/>

## Qué es

**SD Bind** es un módulo de **KernelSU / KernelSU-Next** que crea *bind mounts*: monta
carpetas de una tarjeta SD o de una unidad USB (OTG) para que aparezcan **dentro** del
almacenamiento interno del teléfono, en la ruta que tú elijas. No se copia ni se mueve
nada — el contenido sigue físicamente en la SD/OTG, pero cualquier app lo ve como si
estuviera en el almacenamiento interno.

El proyecto trae dos formas de controlarlo:

- **App nativa** (`SD Bind Manager`, Jetpack Compose) — se instala sola junto con el
  módulo y ofrece un explorador de carpetas, anillos de uso de almacenamiento y gestión
  de vínculos con una interfaz Material You.
- **WebUI de respaldo** — la misma funcionalidad servida como página web, accesible
  desde el propio gestor de KernelSU si prefieres no instalar la app.

<br/>

<div align="center">
  <img src="assets/flow-diagram.svg" width="100%" alt="Diagrama: SD/OTG se vincula al almacenamiento interno mediante bind mount con root" />
</div>

<br/>

<div align="center">
  <img src="assets/architecture.svg" width="100%" alt="Arquitectura: app Compose sobre módulo KernelSU sobre el kernel Linux" />
</div>

<br/>

<div align="center">
  <sub>SD Bind · módulo <code>sdcard_bind_ui</code> · hecho para KernelSU / KernelSU-Next</sub>
</div>
