package local.tabletwebcam;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {
    private static final int PORT = 8765;
    private static final int JPEG_QUALITY = 68;
    private static final long ENCODE_INTERVAL_MS = 50L;

    private TextureView previewView;
    private FrameLayout previewArea;
    private TextView statusView;
    private TextView peerView;
    private TextView privacyButton;
    private TextView switchButton;
    private SharedPreferences preferences;
    private int selectedCameraId = -1;
    private boolean switchingCamera;
    private String cameraError;
    private final Handler uiHandler = new Handler();
    private DiscoveryServer discoveryServer;
    private boolean reverseInterface;
    private int lastDisplayRotation = -1;
    private Camera camera;
    private int cameraId = -1;
    private int previewWidth;
    private int previewHeight;
    private int previewFormat = ImageFormat.NV21;
    private int networkRotation;
    private long lastEncodedAt;
    private boolean loggedPreviewFrame;
    private boolean loggedFrameError;
    private volatile boolean privacyCover;
    private byte[] blackFrame;
    private final FrameStore frames = new FrameStore();
    private H264StreamServer h264Server;
    private AvcEncoder avcEncoder;
    private MjpegServer server;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        preferences = getSharedPreferences("brisa", MODE_PRIVATE);
        selectedCameraId = preferences.getInt("camera", -1);
        privacyCover = preferences.getBoolean("privacy", false);
        reverseInterface = preferences.getBoolean("reverse_interface", false);
        setRequestedOrientation(reverseInterface ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        buildScreen();
        h264Server = new H264StreamServer(this);
        h264Server.start();
        avcEncoder = new AvcEncoder(h264Server);
        logAvcEncoders();
        server = new MjpegServer(frames, PORT, h264Server);
        server.start();
        discoveryServer = new DiscoveryServer(this);
        discoveryServer.start();
        uiHandler.post(connectionRefresh);
    }

    private void logAvcEncoders() {
        try {
            for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
                MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
                if (!codecInfo.isEncoder()) continue;
                for (String type : codecInfo.getSupportedTypes()) {
                    if (!"video/avc".equalsIgnoreCase(type)) continue;
                    MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(type);
                    android.util.Log.i("TabletWebcam", "AVC encoder=" + codecInfo.getName()
                            + " colorFormats=" + java.util.Arrays.toString(caps.colorFormats));
                }
            }
        } catch (Exception e) {
            android.util.Log.e("TabletWebcam", "Could not inspect AVC encoders", e);
        }
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xff081219);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        previewArea = new FrameLayout(this);
        previewArea.setBackgroundColor(Color.BLACK);
        previewView = new TextureView(this);
        previewView.setId(R.id.camera_preview);
        previewView.setSurfaceTextureListener(this);
        previewArea.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(previewArea, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sideParams = new LinearLayout.LayoutParams(dp(220), -1);
        sideParams.leftMargin = dp(24);
        root.addView(sidebar, sideParams);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView name = new TextView(this);
        name.setText("Brisa");
        name.setTextColor(Color.WHITE);
        name.setTextSize(26);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(dp(12), 0, 0, 0);
        brand.addView(name);
        sidebar.addView(brand);

        statusView = new TextView(this);
        statusView.setTextColor(0xffdbe6eb);
        statusView.setTextSize(17);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(32);
        sidebar.addView(statusView, statusParams);
        peerView = new TextView(this);
        peerView.setTextColor(0xff62dac9);
        peerView.setTextSize(14);
        peerView.setTypeface(Typeface.MONOSPACE);
        peerView.setPadding(0, dp(7), 0, dp(26));
        sidebar.addView(peerView);

        privacyButton = control("Tapar cámara", R.id.camera_privacy, UiIcon.PRIVACY);
        sidebar.addView(privacyButton, new LinearLayout.LayoutParams(-1, dp(56)));
        privacyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                privacyCover = !privacyCover;
                preferences.edit().putBoolean("privacy", privacyCover).apply();
                if (privacyCover && previewWidth > 0 && previewHeight > 0) {
                    blackFrame = makeBlackFrame(previewWidth, previewHeight, networkRotation);
                    frames.publish(blackFrame);
                }
                updatePrivacyButton();
            }
        });
        switchButton = control("Girar cámara", R.id.camera_switch, UiIcon.SWITCH);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-1, dp(56));
        switchParams.topMargin = dp(12);
        sidebar.addView(switchButton, switchParams);
        switchButton.setEnabled(Camera.getNumberOfCameras() > 1);
        switchButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { switchCamera(); }
        });
        TextView rotateButton = control("Girar interfaz", R.id.interface_rotate, UiIcon.ROTATE);
        LinearLayout.LayoutParams rotateParams = new LinearLayout.LayoutParams(-1, dp(56));
        rotateParams.topMargin = dp(12);
        sidebar.addView(rotateButton, rotateParams);
        rotateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                reverseInterface = !reverseInterface;
                preferences.edit().putBoolean("reverse_interface", reverseInterface).apply();
                setRequestedOrientation(reverseInterface ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        });
        updatePrivacyButton();
        setContentView(root);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private TextView control(String label, int id, int icon) {
        TextView button = new TextView(this);
        button.setId(id);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(16), 0, dp(12), 0);
        button.setCompoundDrawablePadding(dp(10));
        UiIcon drawable = new UiIcon(icon);
        drawable.setBounds(0, 0, dp(24), dp(24));
        button.setCompoundDrawables(drawable, null, null, null);
        button.setBackground(buttonBackground(0xff1c303b));
        button.setContentDescription(label);
        button.setClickable(true);
        return button;
    }

    private GradientDrawable buttonBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(12));
        return background;
    }

    private final Runnable connectionRefresh = new Runnable() {
        @Override public void run() {
            if (camera != null && lastDisplayRotation != getWindowManager().getDefaultDisplay().getRotation()) {
                try {
                    setPreviewOrientation(camera, cameraId);
                    avcEncoder.start(previewWidth, previewHeight, networkRotation);
                } catch (Exception e) { android.util.Log.w("TabletWebcam", "Orientation update failed", e); }
            }
            if (statusView != null) {
                String peer = h264Server == null ? null : h264Server.getConnectedPeer();
                statusView.setText(cameraError != null ? "Cámara no disponible"
                        : peer == null ? "Esperando conexión" : "Conectado a");
                peerView.setText(cameraError != null ? cameraError : peer == null ? "" : peer);
            }
            uiHandler.postDelayed(this, 750);
        }
    };

    private void switchCamera() {
        int count = Camera.getNumberOfCameras();
        if (count < 2 || switchingCamera || !previewView.isAvailable()) return;
        switchingCamera = true;
        switchButton.setEnabled(false);
        int previous = cameraId;
        selectedCameraId = (previous + 1) % count;
        releaseCamera(false);
        openCamera(previewView.getSurfaceTexture());
        if (camera == null) {
            selectedCameraId = previous;
            openCamera(previewView.getSurfaceTexture());
        }
        if (camera != null) preferences.edit().putInt("camera", cameraId).apply();
        switchingCamera = false;
        switchButton.setEnabled(true);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { openCamera(surface); }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { applyPreviewAspect(); }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { releaseCamera(true); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private void openCamera(SurfaceTexture surface) {
        if (camera != null) return;
        try {
            cameraId = chooseCamera();
            cameraError = null;
            blackFrame = null;
            loggedPreviewFrame = false;
            if (cameraId < 0) throw new IOException("No se encontró una cámara");
            camera = Camera.open(cameraId);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = choosePreviewSize(parameters.getSupportedPreviewSizes());
            if (size != null) {
                previewWidth = size.width;
                previewHeight = size.height;
                parameters.setPreviewSize(previewWidth, previewHeight);
            } else {
                Camera.Size current = parameters.getPreviewSize();
                previewWidth = current.width;
                previewHeight = current.height;
            }

            List<Integer> formats = parameters.getSupportedPreviewFormats();
            if (formats != null && formats.contains(ImageFormat.NV21)) {
                previewFormat = ImageFormat.NV21;
                parameters.setPreviewFormat(previewFormat);
            } else {
                previewFormat = parameters.getPreviewFormat();
            }
            List<int[]> fpsRanges = parameters.getSupportedPreviewFpsRange();
            int[] fastestRange = null;
            if (fpsRanges != null) {
                for (int[] range : fpsRanges) {
                    if (fastestRange == null || range[1] > fastestRange[1]
                            || (range[1] == fastestRange[1] && range[0] > fastestRange[0])) {
                        fastestRange = range;
                    }
                }
                if (fastestRange != null) parameters.setPreviewFpsRange(fastestRange[0], fastestRange[1]);
            }
            android.util.Log.i("TabletWebcam", "Camera preview " + previewWidth + "x" + previewHeight
                    + " format=" + previewFormat + " bits=" + ImageFormat.getBitsPerPixel(previewFormat)
                    + " supported=" + formats + " fpsRanges=" + fpsRanges);
            camera.setParameters(parameters);
            camera.setPreviewTexture(surface);
            setPreviewOrientation(camera, cameraId);

            int encodedWidth = (networkRotation == 90 || networkRotation == 270) ? previewHeight : previewWidth;
            int encodedHeight = (networkRotation == 90 || networkRotation == 270) ? previewWidth : previewHeight;
            if (avcEncoder == null || !avcEncoder.start(previewWidth, previewHeight, networkRotation)) {
                android.util.Log.w("TabletWebcam", "Hardware H.264 unavailable; retaining MJPEG mode");
            }

            queuePreviewBuffers(camera);
            camera.startPreview();
            android.util.Log.i("TabletWebcam", "Brisa camera=" + cameraId + " privacy=" + privacyCover);
        } catch (Exception e) {
            releaseCamera(true);
            cameraError = e.getMessage();
            android.util.Log.e("TabletWebcam", "Camera unavailable", e);
        }
    }

    private void queuePreviewBuffers(Camera activeCamera) throws IOException {
        int bits = ImageFormat.getBitsPerPixel(previewFormat);
        if (bits <= 0) throw new IOException("Formato de video no reconocido por Android: " + previewFormat);
        int bufferSize = previewWidth * previewHeight * bits / 8;
        activeCamera.setPreviewCallbackWithBuffer(this);
        activeCamera.addCallbackBuffer(new byte[bufferSize]);
        activeCamera.addCallbackBuffer(new byte[bufferSize]);
        activeCamera.addCallbackBuffer(new byte[bufferSize]);
    }

    private int chooseCamera() {
        int count = Camera.getNumberOfCameras();
        if (selectedCameraId >= 0 && selectedCameraId < count) return selectedCameraId;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
        }
        return count > 0 ? 0 : -1;
    }

    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = sizes.get(0);
        for (Camera.Size size : sizes) {
            if (size.width <= 640 && size.height <= 480
                    && (size.width * size.height > best.width * best.height
                    || best.width > 640 || best.height > 480)) {
                best = size;
            }
        }
        return best;
    }

    private void setPreviewOrientation(Camera activeCamera, int id) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        lastDisplayRotation = rotation;
        int degrees = rotation == Surface.ROTATION_90 ? 90
                : rotation == Surface.ROTATION_180 ? 180
                : rotation == Surface.ROTATION_270 ? 270 : 0;
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        networkRotation = result;
        activeCamera.setDisplayOrientation(result);
        applyPreviewAspect();
    }

    /** Keep the tablet preview at the exact aspect ratio sent to Linux. */
    private void applyPreviewAspect() {
        if (previewArea == null || previewView == null || previewWidth <= 0 || previewHeight <= 0) return;
        previewArea.post(new Runnable() {
            @Override public void run() {
                int availableWidth = previewArea.getWidth();
                int availableHeight = previewArea.getHeight();
                if (availableWidth <= 0 || availableHeight <= 0) return;
                boolean quarterTurn = networkRotation == 90 || networkRotation == 270;
                int imageWidth = quarterTurn ? previewHeight : previewWidth;
                int imageHeight = quarterTurn ? previewWidth : previewHeight;
                float ratio = (float) imageWidth / (float) imageHeight;
                int width = availableWidth;
                int height = (int) (width / ratio);
                if (height > availableHeight) {
                    height = availableHeight;
                    width = (int) (height * ratio);
                }
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
                previewView.setLayoutParams(params);
            }
        });
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera source) {
        long now = System.currentTimeMillis();
        try {
            if (!loggedPreviewFrame) {
                loggedPreviewFrame = true;
                android.util.Log.i("TabletWebcam", "First frame received: bytes=" + data.length
                        + " format=" + previewFormat + " size=" + previewWidth + "x" + previewHeight);
            }
            if (avcEncoder != null && avcEncoder.isRunning()) {
                avcEncoder.queueFrame(data, privacyCover);
                return;
            }
            if (now - lastEncodedAt >= ENCODE_INTERVAL_MS && previewWidth > 0 && previewHeight > 0) {
                lastEncodedAt = now;
                if (privacyCover) {
                    if (blackFrame == null) blackFrame = makeBlackFrame(previewWidth, previewHeight, networkRotation);
                    frames.publish(blackFrame);
                    return;
                }
                int outputWidth = (networkRotation == 90 || networkRotation == 270)
                        ? previewHeight : previewWidth;
                int outputHeight = (networkRotation == 90 || networkRotation == 270)
                        ? previewWidth : previewHeight;
                byte[] orientedData = previewFormat == ImageFormat.NV21
                        ? rotateNv21(data, previewWidth, previewHeight, networkRotation) : data;
                YuvImage image = new YuvImage(orientedData, previewFormat, outputWidth, outputHeight, null);
                ByteArrayOutputStream output = new ByteArrayOutputStream(96 * 1024);
                if (image.compressToJpeg(new Rect(0, 0, outputWidth, outputHeight), JPEG_QUALITY, output)) {
                    frames.publish(output.toByteArray());
                }
            }
        } catch (RuntimeException e) {
            // A bad frame should not stop the camera preview.
            if (!loggedFrameError) {
                loggedFrameError = true;
                android.util.Log.e("TabletWebcam", "Could not encode camera preview frame", e);
            }
        } finally {
            if (camera == source && source != null) source.addCallbackBuffer(data);
        }
    }

    private void updatePrivacyButton() {
        if (privacyButton == null) return;
        privacyButton.setText(privacyCover ? "Mostrar cámara" : "Tapar cámara");
        privacyButton.setBackground(buttonBackground(privacyCover ? 0xffb93640 : 0xff1c303b));
        privacyButton.setContentDescription(privacyCover ? "Mostrar video en la computadora" : "Tapar video en la computadora");
        if (previewView != null) {
            Paint paint = new Paint();
            if (privacyCover) {
                ColorMatrix matrix = new ColorMatrix();
                matrix.setSaturation(0);
                paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            }
            previewView.setLayerPaint(paint);
        }
    }

    private static byte[] makeBlackFrame(int width, int height, int rotation) {
        int outWidth = (rotation == 90 || rotation == 270) ? height : width;
        int outHeight = (rotation == 90 || rotation == 270) ? width : height;
        Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565);
        bitmap.eraseColor(Color.BLACK);
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    private String streamUrl() {
        String ip = findIpv4Address();
        return "http://" + ip + ":" + PORT + "/stream.mjpg";
    }

    /** Rotate the camera's NV21 luma/chroma planes before JPEG compression. */
    static byte[] rotateNv21(byte[] source, int width, int height, int degrees) {
        int rotation = ((degrees % 360) + 360) % 360;
        if (rotation == 0) return source;
        if ((rotation != 90 && rotation != 180 && rotation != 270)
                || (width & 1) != 0 || (height & 1) != 0) return source;
        int outputWidth = (rotation == 90 || rotation == 270) ? height : width;
        int outputHeight = (rotation == 90 || rotation == 270) ? width : height;
        byte[] output = new byte[source.length];
        if (rotation == 180) {
            int lumaSize = width * height;
            for (int i = 0; i < lumaSize; i++) output[lumaSize - 1 - i] = source[i];
            int chromaPairs = lumaSize / 4;
            for (int i = 0; i < chromaPairs; i++) {
                int from = lumaSize + i * 2;
                int to = lumaSize + (chromaPairs - 1 - i) * 2;
                output[to] = source[from];
                output[to + 1] = source[from + 1];
            }
            return output;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx, dy;
                if (rotation == 90) { dx = height - 1 - y; dy = x; }
                else if (rotation == 180) { dx = width - 1 - x; dy = height - 1 - y; }
                else { dx = y; dy = width - 1 - x; }
                output[dy * outputWidth + dx] = source[y * width + x];
            }
        }
        int sourceChroma = width * height;
        int outputChroma = outputWidth * outputHeight;
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        int outputChromaWidth = outputWidth / 2;
        for (int y = 0; y < chromaHeight; y++) {
            for (int x = 0; x < chromaWidth; x++) {
                int dx, dy;
                if (rotation == 90) { dx = chromaHeight - 1 - y; dy = x; }
                else if (rotation == 180) { dx = chromaWidth - 1 - x; dy = chromaHeight - 1 - y; }
                else { dx = y; dy = chromaWidth - 1 - x; }
                int from = sourceChroma + y * width + x * 2;
                int to = outputChroma + dy * outputWidth + dx * 2;
                output[to] = source[from];
                output[to + 1] = source[from + 1];
            }
        }
        return output;
    }

    private String findIpv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            int ip = wifi.getConnectionInfo().getIpAddress();
            if (ip != 0) return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                    + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
        }
        return "<IP-de-la-tablet>";
    }

    private void releaseCamera(boolean stopEncoder) {
        if (stopEncoder && avcEncoder != null) avcEncoder.stop();
        if (camera == null) return;
        try { camera.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) {}
        try { camera.stopPreview(); } catch (Exception ignored) {}
        try { camera.release(); } catch (Exception ignored) {}
        camera = null;
        frames.publish(null);
    }

    @Override protected void onPause() {
        releaseCamera(true);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (previewView != null && previewView.isAvailable() && camera == null)
            openCamera(previewView.getSurfaceTexture());
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(connectionRefresh);
        releaseCamera(true);
        if (server != null) server.stop();
        if (h264Server != null) h264Server.stop();
        if (discoveryServer != null) discoveryServer.stop();
        super.onDestroy();
    }

    static final class FrameStore {
        private byte[] jpeg;
        private long generation;

        synchronized void publish(byte[] frame) {
            jpeg = frame;
            generation++;
            notifyAll();
        }

        synchronized Frame next(long previousGeneration) throws InterruptedException {
            long end = System.currentTimeMillis() + 2000;
            while (generation == previousGeneration && System.currentTimeMillis() < end) {
                wait(Math.max(1, end - System.currentTimeMillis()));
            }
            return new Frame(jpeg, generation);
        }

        synchronized Frame current() {
            return new Frame(jpeg, generation);
        }
    }

    static final class Frame {
        final byte[] jpeg;
        final long generation;
        Frame(byte[] jpeg, long generation) { this.jpeg = jpeg; this.generation = generation; }
    }
}
