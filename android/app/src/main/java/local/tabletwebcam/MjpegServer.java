package local.tabletwebcam;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;

/** Small single-port MJPEG HTTP server for Android KitKat and newer. */
final class MjpegServer {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final MainActivity.FrameStore frames;
    private final int port;
    private final H264StreamServer h264;
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private final Set<Socket> clients = new HashSet<Socket>();

    MjpegServer(MainActivity.FrameStore frames, int port, H264StreamServer h264) {
        this.frames = frames;
        this.port = port;
        this.h264 = h264;
    }

    void start() {
        if (running) return;
        ServerSocket candidate = null;
        try {
            candidate = new ServerSocket();
            candidate.setReuseAddress(true);
            candidate.bind(new InetSocketAddress(port), 8);
        } catch (IOException e) {
            try { if (candidate != null) candidate.close(); } catch (IOException ignored) {}
            android.util.Log.e("TabletWebcam", "Could not bind HTTP server", e);
            return;
        }
        final ServerSocket listener = candidate;
        serverSocket = listener;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(listener); }
        }, "mjpeg-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void stop() {
        running = false;
        ServerSocket listener = serverSocket;
        serverSocket = null;
        try { if (listener != null) listener.close(); } catch (IOException ignored) {}
        synchronized (clients) {
            for (Socket client : clients) try { client.close(); } catch (IOException ignored) {}
            clients.clear();
        }
        Thread previous = acceptThread;
        acceptThread = null;
        if (previous != null) {
            try { previous.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void acceptLoop(ServerSocket listener) {
        try {
            while (running && serverSocket == listener) {
                final Socket socket = listener.accept();
                synchronized (clients) {
                    if (!running || serverSocket != listener) {
                        socket.close();
                        break;
                    }
                    clients.add(socket);
                }
                socket.setSoTimeout(10000);
                Thread client = new Thread(new Runnable() {
                    @Override public void run() { serve(socket); }
                }, "mjpeg-client");
                client.setDaemon(true);
                client.start();
            }
        } catch (IOException e) {
            if (running && serverSocket == listener) android.util.Log.e("TabletWebcam", "HTTP server stopped", e);
        }
    }

    private void serve(Socket socket) {
        try {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String request = readLine(input);
            if (request == null) return;
            while (true) {
                String header = readLine(input);
                if (header == null || header.length() == 0) break;
            }
            String path = "/";
            String[] parts = request.split(" ");
            if (parts.length >= 2) path = parts[1];

            if (path.startsWith("/health?receiver=")) {
                h264.identifyReceiver(socket.getInetAddress().getHostAddress(),
                        java.net.URLDecoder.decode(path.substring("/health?receiver=".length()), "UTF-8"));
                path = "/health";
            }

            if ("/stream.mjpg".equals(path)) {
                write(output, "HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n\r\n");
                long generation = -1;
                while (running && !socket.isClosed()) {
                    MainActivity.Frame frame = frames.next(generation);
                    generation = frame.generation;
                    if (frame.jpeg == null) continue;
                    write(output, "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                            + frame.jpeg.length + "\r\n\r\n");
                    output.write(frame.jpeg);
                    write(output, "\r\n");
                    output.flush();
                }
            } else if ("/snapshot.jpg".equals(path)) {
                MainActivity.Frame frame = frames.current();
                if (frame.jpeg == null) {
                    response(output, "503 Service Unavailable", "La cámara todavía no produjo un cuadro.");
                } else {
                    write(output, "HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "
                            + frame.jpeg.length + "\r\nConnection: close\r\n\r\n");
                    output.write(frame.jpeg);
                    output.flush();
                }
            } else if ("/health".equals(path)) {
                response(output, "200 OK", (h264.isReady() || frames.current().jpeg != null)
                        ? "camera-ready" : "waiting");
            } else if ("/h264/health".equals(path)) {
                response(output, "200 OK", h264.isReady() ? "h264-ready" : "unavailable");
            } else {
                response(output, "200 OK", "<!doctype html><html><meta name=viewport content='width=device-width'><title>Tablet Webcam</title><body style='background:#111;color:#eee;font:16px sans-serif;text-align:center'><h3>Tablet Wi-Fi Webcam</h3><img style='max-width:100%;height:auto' src='/stream.mjpg'></body></html>");
            }
        } catch (Exception ignored) {
            // Browsers and video clients commonly close MJPEG sockets without a final request.
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            synchronized (clients) { clients.remove(socket); }
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r' && line.length() < 4096) line.append((char) value);
        }
        return value == -1 && line.length() == 0 ? null : line.toString();
    }

    private static void response(OutputStream output, String status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF8);
        write(output, "HTTP/1.0 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + bytes.length + "\r\nConnection: close\r\n\r\n");
        output.write(bytes);
        output.flush();
    }

    private static void write(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(UTF8));
    }
}
