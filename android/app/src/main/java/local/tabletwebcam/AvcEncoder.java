package local.tabletwebcam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** H.264 hardware encoder using the KitKat byte-buffer MediaCodec API. */
final class AvcEncoder {
    private static final String MIME = "video/avc";
    private final H264StreamServer output;
    private volatile MediaCodec codec;
    private ByteBuffer[] inputBuffers;
    private ByteBuffer[] outputBuffers;
    private Thread drainThread;
    private volatile boolean running;
    private int cameraWidth;
    private int cameraHeight;
    private int rotation;
    private int width;
    private int height;
    private byte[] blackNv21;
    private byte[] chromaScratch;
    private int inputColor;
    private boolean loggedFirstOutput;

    AvcEncoder(H264StreamServer output) { this.output = output; }

    boolean start(int cameraWidth, int cameraHeight, int rotation) {
        int normalizedRotation = ((rotation % 360) + 360) % 360;
        boolean turned = normalizedRotation == 90 || normalizedRotation == 270;
        int nextWidth = turned ? cameraHeight : cameraWidth;
        int nextHeight = turned ? cameraWidth : cameraHeight;
        if (running && width == nextWidth && height == nextHeight) {
            this.cameraWidth = cameraWidth;
            this.cameraHeight = cameraHeight;
            this.rotation = normalizedRotation;
            blackNv21 = makeBlackNv21(cameraWidth, cameraHeight);
            return true;
        }
        if (running) stop();
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
        this.rotation = ((rotation % 360) + 360) % 360;
        boolean quarterTurn = this.rotation == 90 || this.rotation == 270;
        this.width = quarterTurn ? cameraHeight : cameraWidth;
        this.height = quarterTurn ? cameraWidth : cameraHeight;
        String codecName = findMarvellEncoder();
        if (codecName == null) {
            Log.w("TabletWebcam", "No compatible Marvell AVC encoder; use MJPEG fallback");
            return false;
        }
        try {
            MediaCodecInfo info = findCodec(codecName);
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME);
            int color = chooseColor(caps.colorFormats);
            inputColor = color;
            if (color < 0) {
                Log.w("TabletWebcam", "Marvell AVC encoder has no supported 4:2:0 byte-buffer input: "
                        + Arrays.toString(caps.colorFormats));
                return false;
            }
            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 900000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 20);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec = MediaCodec.createByCodecName(codecName);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            inputBuffers = codec.getInputBuffers();
            outputBuffers = codec.getOutputBuffers();
            blackNv21 = makeBlackNv21(cameraWidth, cameraHeight);
            chromaScratch = new byte[width * height / 2];
            loggedFirstOutput = false;
            running = true;
            output.setEncoderReady(true);
            final MediaCodec startedCodec = codec;
            final ByteBuffer[] startedBuffers = outputBuffers;
            drainThread = new Thread(new Runnable() {
                @Override public void run() { drainOutput(startedCodec, startedBuffers); }
            }, "avc-drain");
            drainThread.setDaemon(true);
            drainThread.start();
            Log.i("TabletWebcam", "Started hardware AVC encoder " + codecName + " " + width + "x" + height
                    + " color=0x" + Integer.toHexString(color));
            return true;
        } catch (Exception e) {
            Log.e("TabletWebcam", "Could not start hardware AVC encoder", e);
            stop();
            return false;
        }
    }

    private static String findMarvellEncoder() {
        for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (info.isEncoder() && info.getName().toUpperCase().contains("MARVELL")
                    && info.getName().toUpperCase().contains("CODA")) {
                for (String type : info.getSupportedTypes()) if (MIME.equalsIgnoreCase(type)) return info.getName();
            }
        }
        return null;
    }

    private static MediaCodecInfo findCodec(String name) {
        for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (name.equals(info.getName())) return info;
        }
        return null;
    }

    private static int chooseColor(int[] formats) {
        for (int format : formats) if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return format;
        for (int format : formats) if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return format;
        return -1;
    }

    /** Called by Camera's preview callback. Never waits for the encoder. */
    void queueFrame(byte[] nv21, boolean cover) {
        if (!running || codec == null || inputBuffers == null) return;
        try {
            int index = codec.dequeueInputBuffer(0);
            if (index < 0) return;
            ByteBuffer input = inputBuffers[index];
            input.clear();
            byte[] frame = cover ? blackNv21 : nv21;
            byte[] oriented = MainActivity.rotateNv21(frame, cameraWidth, cameraHeight, rotation);
            putYuv420(input, oriented, width, height);
            codec.queueInputBuffer(index, 0, width * height * 3 / 2,
                    System.nanoTime() / 1000L, 0);
        } catch (Exception e) {
            Log.e("TabletWebcam", "Could not queue frame for AVC encoder", e);
        }
    }

    private void putYuv420(ByteBuffer output, byte[] nv21, int width, int height) {
        int ySize = width * height;
        output.put(nv21, 0, ySize);
        for (int i = 0; i < ySize / 2; i += 2) {
            if (inputColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                chromaScratch[i / 2] = nv21[ySize + i + 1];
                chromaScratch[ySize / 4 + i / 2] = nv21[ySize + i];
            } else {
                chromaScratch[i] = nv21[ySize + i + 1];
                chromaScratch[i + 1] = nv21[ySize + i];
            }
        }
        output.put(chromaScratch);
    }

    private void drainOutput(MediaCodec activeCodec, ByteBuffer[] activeBuffers) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running && codec == activeCodec) {
            try {
                int index = activeCodec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    activeBuffers = activeCodec.getOutputBuffers();
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = activeCodec.getOutputFormat();
                    ByteArrayOutputStream config = new ByteArrayOutputStream();
                    for (String key : new String[] {"csd-0", "csd-1"}) {
                        if (format.containsKey(key)) {
                            byte[] bytes = toAnnexB(copy(format.getByteBuffer(key)));
                            if (bytes != null) config.write(bytes, 0, bytes.length);
                        }
                    }
                    output.publishCodecConfig(config.toByteArray());
                    Log.i("TabletWebcam", "AVC output format " + format);
                } else if (index >= 0) {
                    ByteBuffer buffer = activeBuffers[index].duplicate();
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    byte[] raw = new byte[info.size];
                    buffer.get(raw);
                    activeCodec.releaseOutputBuffer(index, false);
                    if (!running || codec != activeCodec) continue;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        output.publishCodecConfig(toAnnexB(raw));
                    } else if (info.size > 0) {
                        byte[] accessUnit = toAnnexB(raw);
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                        if (!loggedFirstOutput) {
                            loggedFirstOutput = true;
                            Log.i("TabletWebcam", "First AVC access unit bytes=" + accessUnit.length + " key=" + key);
                        }
                        output.publishFrame(accessUnit, key);
                    }
                }
            } catch (Exception e) {
                if (running) Log.e("TabletWebcam", "AVC output drain failed", e);
                break;
            }
        }
    }

    private void publishCsd(MediaFormat format, String key) {
        try {
            if (format.containsKey(key)) output.publishCodecConfig(toAnnexB(copy(format.getByteBuffer(key))));
        } catch (Exception e) { Log.w("TabletWebcam", "Could not read " + key, e); }
    }

    private static byte[] copy(ByteBuffer source) {
        if (source == null) return null;
        ByteBuffer data = source.duplicate();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }

    /** Preserve Annex-B if present; otherwise convert four-byte NAL lengths to start codes. */
    private static byte[] toAnnexB(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return bytes;
        if (hasStartCode(bytes)) return bytes;
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 32);
        int offset = 0;
        while (offset + 4 <= bytes.length) {
            int length = ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                    | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
            offset += 4;
            if (length <= 0 || offset + length > bytes.length) {
                out.reset();
                out.write(0); out.write(0); out.write(0); out.write(1);
                out.write(bytes, 0, bytes.length);
                return out.toByteArray();
            }
            out.write(0); out.write(0); out.write(0); out.write(1);
            out.write(bytes, offset, length);
            offset += length;
        }
        return offset == bytes.length ? out.toByteArray() : bytes;
    }

    private static boolean hasStartCode(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0
                    && ((data[i + 2] == 1) || (data[i + 2] == 0 && data[i + 3] == 1))) return true;
        }
        return false;
    }

    private static byte[] makeBlackNv21(int width, int height) {
        byte[] data = new byte[width * height * 3 / 2];
        for (int i = 0; i < width * height; i++) data[i] = 16;
        for (int i = width * height; i < data.length; i++) data[i] = (byte) 128;
        return data;
    }

    boolean isRunning() { return running; }

    void stop() {
        running = false;
        output.setEncoderReady(false);
        MediaCodec previous = codec;
        codec = null;
        if (previous != null) {
            try { previous.stop(); } catch (Exception ignored) {}
            try { previous.release(); } catch (Exception ignored) {}
        }
        Thread previousThread = drainThread;
        drainThread = null;
        if (previousThread != null && previousThread != Thread.currentThread()) {
            try { previousThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        inputBuffers = null;
        outputBuffers = null;
    }
}
