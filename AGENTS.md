# Brisa: instrucciones para agentes de programación

## Propósito

Reutilizar teléfonos y tablets Android antiguos como webcams para una computadora. Mantener la solución ligera, local y comprensible. Este repositorio es una base adaptable; no es una instalación universal.

## Antes de instalar o adaptar

- Leer `README.md`, `docs/ai-adaptation.md` y `docs/technical-notes.md`.
- Identificar el equipo de destino y el Android real. No reutilizar IP, serial, rutas personales ni parámetros del equipo original.
- Solo está comprobada la SM-T210 con Android 4.4.2 y receptor Arch/Omarchy. `minSdk 19` no garantiza compatibilidad general.
- El encoder H.264 actual selecciona Marvell CODA. Otros codecs requieren adaptación; existe una alternativa MJPEG.
- El receptor incluido es Linux/V4L2. Windows y macOS necesitan implementación propia.

## Al trabajar en el código

- Conservar Android 4.4 / API 19 como mínimo salvo que el usuario solicite otro objetivo. Proteger APIs nuevas según versión.
- Mantener el bloqueo real de los cuadros enviados, separado de la vista local en gris.
- Mantener proporción, orientación, liberación de cámara y reconexión; evitar colas de cuadros que acumulen retraso.
- Respetar los ajustes de otras cámaras virtuales. Adaptar juntos script, unidad systemd y configuración del módulo si cambian nombre o número de dispositivo.
- No eliminar los delimitadores H.264 ni la corrección de tiempos Marvell sin evidencia de que el nuevo flujo los sustituye correctamente.
- No guardar imágenes personales, claves de firma, credenciales ni configuración local en el repositorio. No hacer público ADB por red.
- Añadir soporte de permisos en tiempo de ejecución al adaptar a Android 6 o posterior.

## Alcance de cada tarea

Si la tarea es solo documental, no reinstalar la app ni alterar el sistema. Si el usuario pide instalación o comprobación, seguir el recorrido completo descrito en la guía y comunicar resultados observados. No declarar soporte ni éxito de una videollamada sin comprobarlo. Registrar las adaptaciones y sus límites.
