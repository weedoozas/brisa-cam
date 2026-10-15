package local.tabletwebcam;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.content.SharedPreferences;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.MulticastSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;

/** Local discovery lets the receiver recover after the tablet's DHCP address changes. */
final class DiscoveryServer {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private volatile boolean running;
    private DatagramSocket socket;
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
        running = true;
        if (wifiLock != null) try { wifiLock.acquire(); } catch (Exception ignored) {}
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    MulticastSocket multicast = new MulticastSocket(8767);
                    socket = multicast;
                    multicast.joinGroup(InetAddress.getByName("239.255.42.99"));
                    socket.setSoTimeout(1000);
                    byte[] buffer = new byte[128];
                    while (running) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        try { socket.receive(packet); } catch (SocketTimeoutException timeout) {
                            String receiver = preferences.getString("receiver_address", null);
                            if (receiver != null) {
                                byte[] announcement = "BRISA/1".getBytes(ASCII);
                                socket.send(new DatagramPacket(announcement, announcement.length,
                                        InetAddress.getByName(receiver), 8767));
                            }
                            continue;
                        }
                        String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), ASCII);
                        if (!"BRISA_DISCOVER/1".equals(request)) continue;
                        byte[] reply = "BRISA/1".getBytes(ASCII);
                        socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                    }
                } catch (Exception e) {
                    if (running) android.util.Log.w("TabletWebcam", "Discovery stopped", e);
                }
            }
        }, "brisa-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (socket != null) socket.close();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }
}
