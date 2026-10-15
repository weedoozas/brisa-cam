package local.tabletwebcam;

import android.util.Log;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/** Raw Annex-B H.264 TCP source for FFmpeg on the laptop. */
final class H264StreamServer {
    static final int PORT = 8766;
    // Announce the next access unit now, so an Annex-B parser can emit this
    // frame immediately instead of waiting for the camera's next frame.
    private static final byte[] NEXT_ACCESS_UNIT = {0, 0, 0, 1, 9, (byte) 0xf0};
    private volatile boolean running;
    private volatile boolean encoderReady;
    private volatile Socket client;
    private volatile OutputStream clientOutput;
    private volatile byte[] codecConfig;
    private volatile String receiverAddress;
    private volatile String receiverName;
    private ServerSocket serverSocket;
    private final SharedPreferences preferences;

    H264StreamServer(Context context) {
        preferences = context.getSharedPreferences("brisa", Context.MODE_PRIVATE);
    }

    void start() {
        if (running) return;
        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "h264-accept");
        thread.setDaemon(true);
        thread.start();
    }

    void setEncoderReady(boolean ready) { encoderReady = ready; }
    boolean isReady() { return running && encoderReady; }

    String getConnectedPeer() {
        Socket socket = client;
        if (socket == null || socket.isClosed()) return null;
        String address = socket.getInetAddress().getHostAddress();
        String name = receiverName;
        return address.equals(receiverAddress) && name != null ? name + "\n" + address : address;
    }

    void identifyReceiver(String address, String name) {
        if (name == null) return;
        name = name.replaceAll("[^A-Za-z0-9._-]", "");
        if (name.length() > 48) name = name.substring(0, 48);
        receiverAddress = address;
        receiverName = name.length() == 0 ? null : name;
        if (!address.equals(preferences.getString("receiver_address", "")))
            preferences.edit().putString("receiver_address", address).apply();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(PORT, 2);
            serverSocket.setReuseAddress(true);
            while (running) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                client = socket;
                clientOutput = socket.getOutputStream();
                Log.i("TabletWebcam", "H.264 client connected from " + socket.getInetAddress());
                byte[] config = codecConfig;
                if (config != null) writeToClient(config);
                while (running && !socket.isClosed()) {
                    try {
                        if (socket.getInputStream().read() < 0) break;
                    } catch (IOException e) { break; }
                }
                closeClient(socket);
            }
        } catch (IOException e) {
            if (running) Log.e("TabletWebcam", "H.264 TCP server stopped", e);
        }
    }

    synchronized void publishCodecConfig(byte[] config) {
        if (config == null || config.length == 0) return;
        codecConfig = config;
        writeToClient(config);
    }

    synchronized void publishFrame(byte[] accessUnit, boolean keyFrame) {
        OutputStream output = clientOutput;
        if (accessUnit == null || accessUnit.length == 0 || output == null) return;
        byte[] framed = new byte[accessUnit.length + NEXT_ACCESS_UNIT.length];
        System.arraycopy(accessUnit, 0, framed, 0, accessUnit.length);
        System.arraycopy(NEXT_ACCESS_UNIT, 0, framed, accessUnit.length, NEXT_ACCESS_UNIT.length);
        if (keyFrame && codecConfig != null) writeBytes(output, codecConfig);
        writeBytes(output, framed);
    }

    private synchronized void writeToClient(byte[] bytes) {
        OutputStream output = clientOutput;
        if (output != null) writeBytes(output, bytes);
    }

    private void writeBytes(OutputStream output, byte[] bytes) {
        try {
            output.write(bytes);
            output.flush();
        } catch (IOException e) {
            Socket socket = client;
            if (socket != null) closeClient(socket);
        }
    }

    private synchronized void closeClient(Socket socket) {
        if (client != socket) return;
        client = null;
        clientOutput = null;
        try { socket.close(); } catch (IOException ignored) {}
    }

    void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        Socket socket = client;
        if (socket != null) closeClient(socket);
    }
}
