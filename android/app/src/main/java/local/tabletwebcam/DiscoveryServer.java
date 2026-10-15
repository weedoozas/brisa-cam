package local.tabletwebcam;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.content.SharedPreferences;
import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;

/** Discovery exists only while the camera activity is in the foreground. */
final class DiscoveryServer {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private volatile MulticastSocket socket;
    private Thread thread;
    private WifiManager.MulticastLock wifiLock;
    private final SharedPreferences preferences;

    DiscoveryServer(Context context) {
        preferences = context.getSharedPreferences("brisa", Context.MODE_PRIVATE);
        WifiManager manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (manager != null) {
            wifiLock = manager.createMulticastLock("brisa-discovery");
            wifiLock.setReferenceCounted(false);
        }
    }

    void start() {
        if (socket != null) return;
        MulticastSocket candidate = null;
        try {
            candidate = new MulticastSocket(null);
            candidate.setReuseAddress(true);
            candidate.bind(new InetSocketAddress(8767));
            candidate.setSoTimeout(1000);
            // Unicast discovery still works when multicast is unavailable.
            try { candidate.joinGroup(InetAddress.getByName("239.255.42.99")); }
            catch (Exception e) { android.util.Log.w("TabletWebcam", "Multicast unavailable; using unicast", e); }
        } catch (Exception e) {
            if (candidate != null) candidate.close();
            android.util.Log.w("TabletWebcam", "Could not bind discovery", e);
            return;
        }
        final MulticastSocket active = candidate;
        socket = active;
        if (wifiLock != null) try { wifiLock.acquire(); } catch (Exception ignored) {}
        thread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buffer = new byte[128];
                byte[] announcement = "BRISA/1".getBytes(ASCII);
                while (socket == active && !active.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        try { active.receive(packet); } catch (SocketTimeoutException timeout) {
                            String receiver = preferences.getString("receiver_address", null);
                            if (receiver != null) active.send(new DatagramPacket(announcement, announcement.length,
                                    InetAddress.getByName(receiver), 8767));
                            continue;
                        }
                        String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), ASCII);
                        if ("BRISA_DISCOVER/1".equals(request)) active.send(new DatagramPacket(
                                announcement, announcement.length, packet.getAddress(), packet.getPort()));
                    } catch (Exception e) {
                        if (socket != active || active.isClosed()) break;
                        android.util.Log.w("TabletWebcam", "Discovery packet failed", e);
                    }
                }
            }
        }, "brisa-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        MulticastSocket previous = socket;
        socket = null;
        if (previous != null) previous.close();
        Thread previousThread = thread;
        thread = null;
        if (previousThread != null) {
            try { previousThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }
}
