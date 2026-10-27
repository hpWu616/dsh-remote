package com.example.dsh_apk;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DiscoveryClient {
    static final class Found {
        final String name;
        final String url;
        final String addressUrl;

        Found(String name, String url, String addressUrl) {
            this.name = name;
            this.url = url;
            this.addressUrl = addressUrl;
        }
    }

    private List<InetAddress> broadcastTargets() throws Exception {
        Set<InetAddress> targets = new LinkedHashSet<>();
        targets.add(InetAddress.getByName("255.255.255.255"));
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            try {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (address.getAddress() instanceof Inet4Address
                            && address.getAddress().isSiteLocalAddress()
                            && address.getBroadcast() instanceof Inet4Address) {
                        targets.add(address.getBroadcast());
                    }
                }
            } catch (Exception ignored) { /* Keep scanning other interfaces. */ }
        }
        return new ArrayList<>(targets);
    }

    List<Found> scan(Context context) throws Exception {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder nonce = new StringBuilder();
        for (byte value : random) nonce.append(String.format("%02x", value & 255));
        byte[] probe = new JSONObject().put("type", "dsh-discover")
                .put("nonce", nonce.toString()).toString().getBytes(StandardCharsets.UTF_8);
        List<Found> found = new ArrayList<>();
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicast = wifi == null ? null
                : wifi.createMulticastLock("dsh-device-discovery");
        boolean multicastHeld = false;
        try {
            if (multicast != null) {
                multicast.acquire();
                multicastHeld = true;
            }
        } catch (RuntimeException ignored) { /* Unicast replies can still arrive. */ }
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(19389));
            socket.setBroadcast(true);
            socket.setSoTimeout(600);
            List<InetAddress> targets = broadcastTargets();
            long deadline = System.currentTimeMillis() + 3500;
            while (System.currentTimeMillis() < deadline) {
                for (InetAddress target : targets) {
                    try { socket.send(new DatagramPacket(probe, probe.length, target, 19389)); }
                    catch (Exception ignored) { /* Try the remaining local interfaces. */ }
                }
                long untilNext = Math.min(deadline, System.currentTimeMillis() + 550);
                while (System.currentTimeMillis() < untilNext) {
                    byte[] buffer = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try { socket.receive(packet); }
                    catch (java.net.SocketTimeoutException ignored) { break; }
                    try {
                        JSONObject reply = new JSONObject(new String(packet.getData(),
                                packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
                        String id = reply.getString("id");
                        int port = reply.getInt("port");
                        String host = reply.getString("host");
                        if (!"dsh-host".equals(reply.getString("type"))
                                || (reply.has("nonce")
                                    && !nonce.toString().equals(reply.getString("nonce")))
                                || !id.matches("[a-f0-9]{12}")
                                || !host.equals("dsh-" + id + ".local")
                                || port < 1 || port > 65535) continue;
                        String url = "http://" + host + ":" + port;
                        if (found.stream().noneMatch(item -> item.url.equals(url))) {
                            found.add(new Found(reply.optString("name", "电脑"), url,
                                    "http://" + packet.getAddress().getHostAddress() + ":" + port));
                        }
                    } catch (Exception ignored) { /* Ignore other LAN traffic. */ }
                }
            }
        } finally {
            if (multicastHeld) multicast.release();
        }
        return found;
    }
}
