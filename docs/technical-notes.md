# Notas técnicas

## Captura y privacidad

SM-T210, Android 4.4.2, ARMv7/NEON y Camera HAL Marvell. Brisa utiliza Camera y MediaCodec nativos compatibles con KitKat. El encoder principal es `OMX.MARVELL.VIDEO.HW.CODA7542ENCODER`. NV21 se orienta y convierte a la entrada YUV420 anunciada por el codec; los bloques de crominancia se copian por lotes.

La vista local usa TextureView. La privacidad aplica una matriz de color de hardware a esa vista, mientras el encoder recibe cuadros negros independientes. Cambiar entre sensores del mismo tamaño conserva el encoder y el socket. Si cambian las dimensiones codificadas, se reinicia el codec y se publica su nueva configuración en la misma conexión. Cada hilo de drenaje queda asociado a su propia instancia del codec. SPS y PPS se publican juntos.

## Ciclo de vida

Al salir de la actividad se liberan cámara y codec. El servicio receptor tiene un timeout de lectura de cinco segundos y reinicio automático. Al volver a abrir la app se reconecta. El módulo se configura para cargarse al arrancar Linux; el servicio inicia con la sesión del usuario.

## Temporización

El bitstream Marvell anuncia tiempos anómalos que FFmpeg interpreta como `1200k tbr`. El receptor usa **`-r 20` antes de `-i`** para reemplazarlos. Usar únicamente `-framerate 20` causaba que se descartaran casi todos los cuadros. `sustain_framerate=1` repite la última imagen en los intervalos. Antes de abrir el emisor, el dispositivo con `exclusive_caps=1` solo anuncia OUTPUT; el arranque utiliza `--set-output-parm=20`.

Cada cuadro se envía junto a un Access Unit Delimiter para que el parser Annex-B lo emita sin esperar al siguiente cuadro de la cámara. En una reproducción controlada de la misma muestra de la tablet a ocho cuadros por segundo sobre TCP local:

| Receptor | Mediana | Percentil 95 |
|---|---:|---:|
| Sin delimitador | 126,4 ms | 126,9 ms |
| Con delimitador | 1,2 ms | 1,5 ms |

La medición cubre el parser y la decodificación del receptor. Sensor, encoder, Wi-Fi y aplicación de videollamadas añaden su propia demora. Forzar un solo hilo y E/S directa no mejoró esa prueba, por lo que no se aplicaron. La salida se comprobó con cuarenta cuadros consecutivos por V4L2 y con la vista previa de Teams.

## Protocolo

- TCP 8765: `/health`, `/h264/health` y fallback MJPEG en equipos sin el encoder compatible.
- TCP 8766: H.264 Annex-B; un receptor a la vez.
- UDP 8767: solicitud `BRISA_DISCOVER/1`, respuesta `BRISA/1`. Broadcast, grupo multicast `239.255.42.99` y solicitudes directas en subredes conectadas de hasta 256 direcciones. La tablet anuncia su dirección al último receptor conocido cada segundo sin solicitudes.
- El receptor comunica su hostname en `/health?receiver=...`; se muestra cuando su IP coincide con el socket de video conectado.

No se genera JPEG simultáneamente con AVC, para evitar carga extra. Las solicitudes directas permiten descubrir la tablet cuando el punto de acceso filtra multicast/broadcast. El protocolo está pensado para una red privada de confianza.
