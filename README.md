<img src="branding/brisa-icon.png" width="96" alt="Icono de Brisa">

# Brisa

Tu tablet Android como webcam Wi-Fi para Linux. Desarrollada y probada en una Samsung Galaxy Tab 3 SM-T210 con Android 4.4.2 y encoder H.264 Marvell. En Linux conserva el nombre **TabletCam** y el dispositivo `/dev/video42` para mantener la selección en las aplicaciones existentes.

## Tres controles

- **Tapar cámara:** la computadora recibe una imagen negra. En la tablet, la vista local continúa en blanco y negro y el botón se pone rojo. **Mostrar cámara** restaura la señal a color.
- **Girar cámara:** cambia entre frontal y trasera conservando la conexión. Durante la transición puede mantenerse brevemente el último cuadro.
- **Girar interfaz:** gira toda la pantalla 180° y ajusta la orientación del video. Permite montar la tablet en el sentido contrario manteniendo la imagen a la izquierda y los controles a la derecha.

La cámara seleccionada, la orientación y la privacidad se recuerdan. La interfaz solo muestra el nombre y la dirección del receptor conectado. Brisa transmite en primer plano, mantiene la pantalla encendida y libera la cámara al salir. Al abrirla nuevamente, Linux se reconecta.

## Instalar en Linux

Requiere `ffmpeg`, `curl`, Python 3, `v4l-utils` y el módulo `v4l2loopback` para el kernel instalado. En Arch/Omarchy suele usarse `v4l2loopback-dkms` con los headers correspondientes al kernel.

Con Brisa abierta y ambos equipos en la misma red:

```sh
chmod +x tablet-webcam-linux brisa-discover install-linux-service
./brisa-discover
./install-linux-service IP_DE_LA_TABLET
```

El instalador copia el receptor a `~/.local/bin`, configura el módulo al arrancar y habilita un servicio que inicia con la sesión del usuario. El servicio se reinicia ante fallos y espera mientras la app está cerrada. Usa la IP inicial y busca Brisa en la red local si deja de responder. La detección utiliza broadcast, multicast y solicitudes UDP directas en subredes locales de hasta 256 direcciones. La tablet también anuncia su dirección al último receptor conocido. El router debe permitir comunicación entre ambos equipos.

Selecciona **TabletCam** en Teams, Discord o tu aplicación de video. La salida es de 640×480. La cadencia nominal de 20 fps puede repetir imágenes durante los intervalos: los FPS reales dependen del sensor y del hardware de la tablet.

```sh
systemctl --user status tablet-webcam@IP_DE_LA_TABLET.service
journalctl --user -u tablet-webcam@IP_DE_LA_TABLET.service
systemctl --user stop tablet-webcam@IP_DE_LA_TABLET.service
systemctl --user disable --now tablet-webcam@IP_DE_LA_TABLET.service
```

Evita ejecutar un puente manual adicional mientras el servicio está activo. Si necesitas cambiar su IP inicial, desactiva la instancia anterior e instala nuevamente con la nueva dirección.

## Android

Instala el APK desde **Releases**. Para compilar, utiliza JDK 17 y Android SDK 35/build-tools 35.0.0. El wrapper fija Gradle 8.7:

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Se conserva el ID `local.tabletwebcam` para actualizar la app anterior. El APK de desarrollo se firma con la clave de depuración del equipo que lo compila. Las claves y configuraciones locales no están en el repositorio.

## Red y rendimiento

H.264 usa TCP 8766; el estado HTTP usa TCP 8765; el descubrimiento usa UDP 8767. El protocolo está diseñado para una LAN privada de confianza y no incorpora autenticación ni cifrado. No utiliza servidores de nube.

Consulta [las notas técnicas](docs/technical-notes.md) para los detalles del encoder y las mediciones de latencia.
