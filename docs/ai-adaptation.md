# Guía de instalación y adaptación para una IA

## Objetivo y alcance

Brisa recupera Android antiguos como webcams. Prioriza el hardware que la persona ya tiene, un consumo razonable de recursos y una conexión local estable. Conserva las funciones simples de cámara, privacidad y orientación. No hace falta cambiar el sistema operativo del Android.

Esta guía describe trabajo para cuando una persona **pide instalar o adaptar** el proyecto. No ejecutes cambios de sistema por el mero hecho de leerla. Consulta las instrucciones del entorno y trabaja con la autorización existente.

## 1. Inventario antes de elegir una solución

Recoge y registra solo los datos necesarios:

| Equipo | Datos que determinan la adaptación |
|---|---|
| Computadora | Sistema, distribución, arquitectura, kernel, usuario de escritorio, gestor de paquetes y de servicios. |
| Receptor Linux | Disponibilidad de FFmpeg/V4L2, cámaras `/dev/video*`, módulos ya configurados, permisos y compatibilidad de headers con el kernel. |
| Android | Modelo exacto, API/versión, ROM, cámaras, tamaños y formatos de captura, orientación del sensor, codecs AVC y formatos de entrada. |
| Red | Interfaces conectadas, dirección de la tablet, conectividad entre equipos y filtrado del punto de acceso. |

Herramientas de lectura útiles, según el sistema y los permisos disponibles:

```sh
uname -srmo
cat /etc/os-release
ffmpeg -version
v4l2-ctl --list-devices
ip -j -4 address show
adb devices -l
adb -s SERIAL shell getprop ro.product.model
adb -s SERIAL shell getprop ro.build.version.release
adb -s SERIAL shell getprop ro.build.version.sdk
adb -s SERIAL shell getprop ro.product.cpu.abi
```

`SERIAL` es un marcador, no un valor que deba copiarse. ADB requiere autorización en Android. No cambies permisos USB ni uses root por defecto; determina primero cómo permite el sistema acceder al dispositivo. Para codecs y formatos consulta los mensajes `TabletWebcam` de la app y, si no bastan, añade diagnóstico acotado. Evita volcar un logcat completo con datos de otras aplicaciones.

## 2. Decidir qué se puede usar sin cambios

- **SM-T210 / Android 4.4.2 / Marvell CODA + Linux:** es la referencia comprobada. Instala dependencias y adapta red y cámara virtual al equipo.
- **Otro Android API 19 o posterior:** inspecciona capacidades antes de cambiar el encoder. La selección actual busca nombres MARVELL y CODA; no representa una selección genérica de todos los encoders Android.
- **Sin ese encoder:** la app intenta MJPEG. Comprueba esa ruta en el dispositivo real antes de ofrecerla como solución. Si no basta, añade selección de AVC por capacidades y valida formato, stride, padding, orientación y bitstream para ese codec.
- **Android API 23 o posterior:** incorpora comprobación y solicitud del permiso CAMERA antes de abrirla y maneja denegación/revocación. Mantén el camino API 19; no añadas dependencias que eleven el mínimo sin explicar el cambio. Revisa las restricciones actuales del sistema de destino con documentación oficial.
- **Android anterior a API 19:** el APK actual no sirve. Un port exige analizar las APIs utilizadas y el hardware; cambiar solo `minSdk` no demuestra compatibilidad.
- **Linux sin systemd:** el transporte y la cámara virtual pueden reutilizarse, pero hay que sustituir el instalador y la supervisión del proceso.
- **Windows/macOS:** desarrolla o integra un receptor adecuado para la cámara virtual de ese sistema. No ejecutes scripts de V4L2 ni declares compatibilidad por el solo hecho de decodificar el video en una ventana.

## 3. Preparar la computadora

Instala las dependencias de la distribución consultando su documentación vigente: FFmpeg, curl, Python 3, iproute2, v4l-utils y v4l2loopback. Si se compila el módulo, usa headers del kernel correcto. Resuelve la carga/firma del módulo conforme a la configuración del equipo.

Antes de ejecutar `install-linux-service`, revisa:

- `tablet-webcam-linux`: número 42, etiqueta TabletCam y opciones FFmpeg.
- `tablet-webcam@.service`: ejecutable, argumentos y servicio de usuario.
- `v4l2loopback-options.conf`: `devices`, `video_nr`, `card_label` y `exclusive_caps`.
- Los archivos existentes bajo `/etc/modprobe.d` y `/etc/modules-load.d` que ya afecten a v4l2loopback.

No sobrescribas configuraciones ajenas ni descargues un módulo usado por otra aplicación. Si el módulo ya existe, decide cómo añadir la cámara y cómo persistir la configuración de todas las cámaras necesarias. El instalador de referencia no fusiona configuraciones de otras instalaciones.

Si eliges otro `/dev/videoN`, cambia de manera coherente el script/unidad y la configuración del módulo. El argumento manual del script permite otro número, pero el instalador no ofrece ese parámetro. Conserva nombres y números ya utilizados por las aplicaciones de esa persona cuando corresponda.

## 4. Preparar Android

El APK publicado se encuentra en Releases. Para modificarlo usa JDK 17, SDK Platform 35, Build Tools 35.0.0 y el wrapper Gradle 8.7. Define la ruta del SDK localmente; no la subas al repositorio.

```sh
cd android
./gradlew assembleDebug
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

No desinstales automáticamente ante `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: una compilación propia puede tener otra firma. Explica el conflicto y conserva los datos si la persona los necesita. Las claves de firma se mantienen fuera del repositorio; quien mantenga una distribución debe gestionar su propia clave de forma segura.

La app actual usa Camera API clásica, callbacks NV21 y MediaCodec mediante buffers. No sustituyas esta base por una biblioteca que excluya KitKat sin un motivo ligado al dispositivo de destino. Cámara, encoder y vista son partes distintas: una vista local fluida no demuestra que la codificación ni el receptor lo sean.

## 5. Conectar y persistir

Abre Brisa y usa `./brisa-discover`. Si falla, usa la dirección real de la tablet e inspecciona conectividad, aislamiento entre clientes y permisos de red. El descubrimiento hace consultas UDP directas en subredes conectadas pequeñas; ejecútalo en la LAN prevista.

Los endpoints HTTP son `/health`, `/h264/health` y `/stream.mjpg` en TCP 8765. El video AVC es TCP 8766. Solo hay un consumidor H.264 a la vez: no abras un segundo lector para inspeccionarlo mientras el receptor está conectado. Inspecciona la cámara virtual o detén temporalmente el servicio con conocimiento de su usuario.

Instala con `./install-linux-service IP_DE_LA_TABLET` como usuario de escritorio. Si adaptaste rutas o servicios, instala esa versión coherente. No expongas el transporte ni ADB a Internet; los puertos de Brisa no tienen autenticación ni cifrado. Si hace falta una regla de firewall, limítala al equipo/interfaz/red necesarios.

## 6. Comprobar la instalación cuando sea parte del encargo

La comprobación debe recorrer Android → red → decodificador → cámara virtual → aplicación receptora. Realízala sin entrar en llamadas ni enviar imágenes a terceros sin autorización.

1. Confirma que la app captura y que el receptor obtiene cuadros nuevos. Lee mensajes de codec y errores sin extraer información ajena.
2. Comprueba la proporción y orientación con una escena reconocible; la vista de la tablet y la computadora deben representar el mismo encuadre, salvo el espejo propio de cada aplicación.
3. Cambia frontal/trasera y gira la interfaz; confirma recuperación del video y preferencias al abrir de nuevo.
4. Activa privacidad: verifica que el **video recibido** pasa a negro, mientras la vista local queda gris y el botón rojo. Una captura local gris no demuestra privacidad del receptor. Considera los cuadros ya almacenados en el consumidor.
5. Cierra y abre Brisa y confirma reconexión automática, sin arrancar manualmente un segundo puente.
6. Abre la vista previa de la aplicación de videollamadas elegida y comprueba que aparece la cámara. Diferencia un problema de captura, del dispositivo virtual y del consumidor.
7. Si la persona solicita validar persistencia al reiniciar, hazlo cuando pueda interrumpirse la sesión. Habilitar una unidad y comprobar su estado no demuestra por sí solo que se probó un arranque completo.

No guardes capturas personales en Git ni en publicaciones. Comunica resultados concretos: hardware, versión, modo de transporte, cuadros reales observados y límites pendientes. Si no pudiste hacer alguna comprobación, dilo.

## 7. Adaptar rendimiento con mediciones

Empieza con tamaño, formato y FPS reales de captura. Separa sensor, conversión YUV/rotación, encoder, red, parser, decodificador y consumidor. Mide el tramo que vas a modificar y evita acumular cuadros antiguos.

La configuración original utiliza 640×480, 900 kbit/s, GOP nominal de un segundo y 20 fps configurados en MediaCodec. El sensor frontal produjo aproximadamente 8 fps en la configuración observada. La salida nominal de FFmpeg a 20 fps puede repetir cuadros: aumentar ese número no crea detalle temporal nuevo.

En Marvell, conserva estos detalles hasta demostrar una alternativa:

- `-r 20` **antes de `-i`** corrige la temporización anómala del bitstream; usar solo `-framerate` no resolvió el problema original.
- El servidor añade delimitadores de unidades de acceso H.264 para evitar que el parser espere al siguiente cuadro.
- SPS/PPS se envían juntos y de nuevo con cuadros clave.
- Antes de abrir el productor, `exclusive_caps=1` puede anunciar solo OUTPUT. Se usa `--set-output-parm`, no el control de parámetros de captura.
- `sustain_framerate` mantiene la cadencia del dispositivo virtual mediante repetición.

Consulta las mediciones acotadas de `technical-notes.md`. No presentes la mejora del parser como latencia total sensor-pantalla ni prometas los mismos FPS en otro dispositivo.

## 8. Entregar una adaptación mantenible

Explica cómo abrir la app, elegir la webcam, activar privacidad, revisar el servicio y retirarlo. Documenta el modelo y las versiones comprobadas, los cambios y las limitaciones. Si aportas el port al proyecto, comparte código y diagnósticos mínimos reproducibles, sin claves, IP personales, seriales ni capturas privadas. Mantén la licencia MIT y los avisos de componentes de terceros.
