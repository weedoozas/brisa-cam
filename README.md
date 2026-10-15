<img src="branding/brisa-icon.png" width="96" alt="Icono de Brisa">

# Brisa · Una segunda vida como webcam

**Rescata ese teléfono o tablet Android antiguo que ya no utilizas y conviértelo en una cámara web para tu computadora.** Ese es el propósito de Brisa: aprovechar una cámara que todavía funciona y darle un uso sencillo por Wi-Fi.

Este repositorio es un **proyecto base abierto para adaptar con ayuda de una IA** al Android y a la computadora de cada persona. La primera implementación funciona en una Samsung Galaxy Tab 3 SM-T210 con Android 4.4.2 y una computadora Linux. No requiere cambiar la ROM, instalar Linux en la tablet ni rootear Android.

## Instalarlo con ayuda de una IA

Dale este repositorio a un asistente de programación que pueda acceder a tu computadora y, si vas a instalar por USB, a Android mediante ADB. Puedes copiarle este mensaje:

> Quiero reutilizar un Android antiguo como webcam con Brisa: https://github.com/weedoozas/brisa-cam. Lee README.md, AGENTS.md y docs/ai-adaptation.md. Primero identifica mi sistema operativo, distribución y kernel, las cámaras virtuales existentes y el modelo, versión de Android y capacidades de cámara/codificador del dispositivo. Usa este repositorio como base y adapta la instalación a mi equipo; no copies ciegamente la configuración de la máquina original. Instala las dependencias apropiadas, compila o instala el APK compatible y configura el receptor con reconexión automática. Si mi sistema no tiene receptor implementado, explica y desarrolla la adaptación necesaria. Conserva el bloqueo real de la imagen enviada. Comprueba el video recibido, la proporción, la orientación, la privacidad y la recuperación al cerrar y abrir la app. Indica qué funcionó, qué cambiaste y cómo desinstalarlo. No borres aplicaciones ni cambies el firmware sin mi autorización.

**Guía detallada para la IA:** [instalación y adaptación](docs/ai-adaptation.md). El archivo [AGENTS.md](AGENTS.md) contiene las reglas de trabajo del proyecto para agentes de programación. Leer esos documentos no instala nada: el asistente debe ejecutar la instalación en el equipo de destino con la autorización de su usuario.

## Compatibilidad real

| Componente | Estado de esta versión |
|---|---|
| Android comprobado | Samsung Galaxy Tab 3 **SM-T210**, **Android 4.4.2**, ROM ReviveOS, ARMv7 y hardware Marvell. |
| Mínimo declarado | **Android 4.4 / API 19** (`minSdk 19`). Es un límite de instalación, no una garantía de funcionamiento en todos los modelos. |
| Android anterior a 4.4 | No admitido por esta compilación. Requiere un port específico. |
| Otros dispositivos con Android 4.4–5.1 | Candidatos para adaptación; sin verificar. La ruta H.264 actual selecciona un encoder **Marvell CODA**. Sin él, intenta MJPEG, que puede ser más lento. |
| Android 6.0 o posterior | Requiere adaptación y comprobación. La app actual no implementa la solicitud de permiso de cámara en tiempo de ejecución; hay que añadirla antes de ofrecer soporte general. Revisar también las restricciones de la versión de destino. |
| Computadora comprobada | Linux Arch/Omarchy, FFmpeg, V4L2 y `v4l2loopback`, con un servicio de usuario systemd. |
| Otras distribuciones Linux | Adaptar paquetes, headers del kernel, carga del módulo, permisos y gestor de servicios. |
| Windows / macOS | **El repositorio no incluye un receptor de cámara virtual para estos sistemas.** Requieren desarrollar o integrar uno; los scripts Linux no los soportan. |

La compilación usa `compileSdk 35` y `targetSdk 28`. Estos valores no significan que la aplicación se haya probado en Android moderno. Consulta las definiciones oficiales de [minSdk y targetSdk](https://developer.android.com/guide/topics/manifest/uses-sdk-element) y el flujo de [permisos en tiempo de ejecución](https://developer.android.com/training/permissions/requesting).

La tablet transmite por Wi-Fi y puede permanecer conectada a su cargador. USB solo se necesita si eliges instalar o diagnosticar mediante ADB. Brisa transmite video; **no implementa micrófono**.

## Cómo funciona y cómo está hecho

Brisa usa **Java y APIs nativas de Android**, sin un framework web en la tablet. En la computadora, el puente usa Bash, Python estándar y FFmpeg.

```text
Cámara Android (Camera API, NV21)
    ├─ TextureView → vista local en la tablet
    └─ rotación + conversión YUV → MediaCodec H.264
                                  ↓ TCP por la red local
                            FFmpeg en Linux
                                  ↓ V4L2
                         cámara virtual → videollamada
```

Si no arranca el encoder compatible, la app intenta una ruta JPEG/MJPEG por HTTP. No se generan JPEG simultáneamente con H.264. El receptor decodifica el video y lo entrega como cámara virtual; por defecto se llama **TabletCam**, usa `/dev/video42` y produce 640×480. Estos valores son de la instalación original y se deben revisar al adaptar el proyecto.

| Archivo | Responsabilidad |
|---|---|
| `android/app/src/main/java/local/tabletwebcam/MainActivity.java` | Captura, vista local, orientación, selección de cámara y controles. |
| `AvcEncoder.java` en el mismo directorio | Selección del codec, conversión NV21 a YUV420, codificación y ciclo de vida de MediaCodec. |
| `H264StreamServer.java` / `MjpegServer.java` | Transporte H.264, estado HTTP y alternativa MJPEG. |
| `DiscoveryServer.java` | Descubrimiento local y anuncios al último receptor conocido. |
| `UiIcon.java` | Iconos de los controles dibujados con Canvas. |
| `tablet-webcam-linux` | Detección de la app, FFmpeg y salida a la cámara virtual. |
| `brisa-discover` | Descubrimiento UDP con Python estándar. |
| `install-linux-service`, `tablet-webcam@.service`, `v4l2loopback-*.conf` | Instalación persistente del receptor Linux y configuración del módulo. |

Los detalles de temporización, formatos y mediciones están en [notas técnicas](docs/technical-notes.md).

## Los tres controles

- **Tapar cámara:** la computadora recibe una imagen negra. En la tablet, la vista local continúa en blanco y negro y el botón se pone rojo. **Mostrar cámara** restaura la señal a color.
- **Girar cámara:** cambia entre frontal y trasera conservando la conexión. Durante la transición puede mantenerse brevemente el último cuadro.
- **Girar interfaz:** gira toda la pantalla 180° y ajusta la orientación del video. La imagen queda a la izquierda y los controles a la derecha en la orientación elegida.

La cámara seleccionada, la orientación y la privacidad se recuerdan. La interfaz muestra el receptor conectado. La app funciona en primer plano, mantiene la pantalla encendida y libera la cámara al salir. El receptor Linux vuelve a conectarse cuando se abre nuevamente.

## Instalación manual de referencia: Android + Linux con systemd

### 1. Obtener el proyecto

```sh
git clone https://github.com/weedoozas/brisa-cam.git
cd brisa-cam
```

### 2. Instalar Android

Descarga **Brisa.apk** de [Releases](https://github.com/weedoozas/brisa-cam/releases). Puedes instalarlo desde Android permitiendo la instalación desde esa fuente, o mediante ADB con depuración USB autorizada:

```sh
adb devices
adb install -r Brisa.apk
```

El comando supone que descargaste el APK en el directorio actual. Si hay varios dispositivos, selecciona el correcto con `adb -s SERIAL`. Abre **Brisa** en la tablet y conecta ambos equipos a una red local que permita comunicación entre ellos.

Para compilar tú mismo, instala **JDK 17**, Android SDK Platform 35 y Build Tools 35.0.0; configura `ANDROID_HOME` o `android/local.properties` con la ruta de tu SDK. El wrapper fija Gradle 8.7 y el proyecto usa Android Gradle Plugin 8.6.1:

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
cd ..
```

El ID de la app es `local.tabletwebcam`. El APK publicado es de desarrollo. Una compilación propia usa otra clave de depuración y no puede actualizar una instalación firmada con la clave original: conserva tu clave para futuras actualizaciones. Si aparece un conflicto de firma, no desinstales automáticamente, porque se perderían los ajustes locales.

### 3. Preparar Linux

Instala con el gestor de paquetes de tu distribución: **FFmpeg, curl, Python 3, iproute2, v4l-utils y v4l2loopback**, además de headers compatibles con el kernel si el módulo se compila mediante DKMS. El instalador de Brisa configura el receptor, pero **no instala esas dependencias**.

Consulta las instrucciones del [proyecto v4l2loopback](https://github.com/v4l2loopback/v4l2loopback) y las de tu distribución para el módulo y su firma, si el equipo la exige. Revisa los dispositivos existentes antes de asignar `/dev/video42`: las configuraciones incluidas crean una cámara llamada TabletCam y pueden requerir cambios si ya usas otras cámaras virtuales.

### 4. Conectar e instalar el receptor

Con Brisa abierta:

```sh
./brisa-discover
./install-linux-service IP_DE_LA_TABLET
```

Sustituye `IP_DE_LA_TABLET` por la dirección encontrada. El instalador usa `sudo` para dos archivos de configuración del módulo; ejecútalo como tu usuario de escritorio, **no mediante `sudo ./install-linux-service`**.

Copia los ejecutables a `~/.local/bin`, añade una unidad a `~/.config/systemd/user`, configura el módulo en `/etc/modules-load.d/tablet-webcam.conf` y `/etc/modprobe.d/tablet-webcam.conf`, y habilita el servicio al iniciar sesión. Este se reinicia ante fallos y espera mientras la app está cerrada. Si la IP inicial deja de responder, intenta descubrir la tablet de nuevo.

Selecciona **TabletCam** en la aplicación de videollamadas. La salida nominal es de 20 fps, pero puede repetir imágenes: los FPS reales dependen del sensor, la iluminación, el encoder y la red. En la tablet original se observó aproximadamente 8 fps de captura; no se prometen 20 cuadros diferentes por segundo.

```sh
systemctl --user status tablet-webcam@IP_DE_LA_TABLET.service
journalctl --user -u tablet-webcam@IP_DE_LA_TABLET.service
```

Para una ejecución manual, detén primero el servicio correspondiente:

```sh
./tablet-webcam-linux IP_DE_LA_TABLET 42 auto
```

Los argumentos opcionales son el número de dispositivo y el modo `auto`, `h264` o `mjpeg`. El instalador persistente usa los valores predeterminados del script; para otros valores adapta también la unidad y los archivos del módulo.

### 5. Desactivar o retirar

```sh
systemctl --user disable --now tablet-webcam@IP_DE_LA_TABLET.service
```

Para retirar la instalación completa, elimina los ejecutables, la unidad y los dos archivos de configuración indicados arriba, después de comprobar que no los utiliza otra instalación; ejecuta `systemctl --user daemon-reload`. El módulo ya cargado permanece hasta descargarlo cuando no tenga usuarios o reiniciar. Desinstalar Brisa desde Android elimina sus preferencias.

## Red, privacidad y límites

- TCP **8765**: HTTP `/health`, `/h264/health` y `/stream.mjpg`.
- TCP **8766**: video H.264 Annex-B; un receptor a la vez.
- UDP **8767**: descubrimiento `BRISA_DISCOVER/1` → `BRISA/1`. Usa broadcast, multicast local `239.255.42.99` y consultas directas en subredes conectadas de hasta 256 direcciones. La tablet también anuncia su dirección al último receptor conocido.

No utiliza servicios de nube. **El transporte no tiene autenticación ni cifrado: úsalo en una LAN de confianza y no expongas estos puertos a Internet.** El punto de acceso debe permitir comunicación entre los equipos. Cerrar la actividad detiene la captura; fuerza el cierre de la app para terminar también sus servidores de proceso.

La privacidad sustituye los cuadros futuros por negro; no puede retirar imágenes que otra aplicación ya haya recibido, almacenado o grabado. En una desconexión, un consumidor podría mantener su último cuadro.

## Contribuir y reutilizar

Se agradecen adaptaciones documentadas para **otros Android antiguos y otras computadoras**, manteniendo el proyecto ligero. Al compartir un port, indica modelo, versión de Android, ROM, codec, sistema receptor y qué comprobaste realmente. No marques un dispositivo como compatible solo porque el APK se instala.

Código propio bajo [licencia MIT](LICENSE). Los componentes de terceros, incluido el wrapper de Gradle, conservan sus licencias originales. Puedes usar, modificar y distribuir esta base, conservando los avisos de licencia aplicables.
