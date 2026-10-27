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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;

final class DiscoveryClient {
    static final class Found {
        final String name;
        final String id;
        final String secureUrl;
        final String fingerprint;

        Found(String name, String id, String secureUrl, String fingerprint) {
            this.name = name;
            this.id = id;
            this.secureUrl = secureUrl;
            this.fingerprint = fingerprint;
        }
    }

    static boolean isPrivateSecureUrl(String input) {
        try {
            URI uri = new URI(input.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                        && !"/".equals(uri.getRawPath()))) return false;
            String[] parts = uri.getHost().split("\\.", -1);
            if (parts.length != 4) return false;
            int[] octets = new int[4];
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] > 255) return false;
            }
            return octets[0] == 127 && octets[1] == 0
                    && octets[2] == 0 && octets[3] == 1
                    || octets[0] == 10 || octets[0] == 172
                    && octets[1] >= 16 && octets[1] <= 31
                    || octets[0] == 192 && octets[1] == 168;
        } catch (Exception ignored) { return false; }
    }

    Found probeHttps(String input) throws Exception {
        if (!isPrivateSecureUrl(input)) {
            throw new IllegalArgumentException("请输入 HTTPS 电脑地址，例如 https://192.168.1.20:19390");
        }
        URI uri = new URI(input.trim());
        X509TrustManager discoveryTrust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException { throw new CertificateException("不可使用客户端证书"); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // This probe sends no credentials. The user verifies the fingerprint before pairing.
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{discoveryTrust}, new SecureRandom());
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                .createSocket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 4000);
            socket.setSoTimeout(4000);
            socket.startHandshake();
            X509Certificate certificate = (X509Certificate)
                    socket.getSession().getPeerCertificates()[0];
            String subject = certificate.getSubjectX500Principal().getName();
            Matcher match = Pattern.compile("(?:^|,)CN=dsh-([a-f0-9]{12})\\.local(?:,|$)")
                    .matcher(subject);
            if (!match.find()) throw new java.io.IOException("该地址不是 dsh Remote 插件");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder fingerprint = new StringBuilder();
            for (byte value : hash) fingerprint.append(String.format("%02x", value & 255));
            return new Found("电脑", match.group(1),
                    "https://" + uri.getHost() + ":" + uri.getPort(),
                    fingerprint.toString());
        }
    }

    private static String newNonce() {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder nonce = new StringBuilder();
        for (byte value : random) nonce.append(String.format("%02x", value & 255));
        return nonce.toString();
    }

    private static Found parseReply(DatagramPacket packet, String nonce,
                                    boolean requireNonce) {
        try {
            JSONObject reply = new JSONObject(new String(packet.getData(),
                    packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
            String id = reply.getString("id");
            int port = reply.getInt("port");
            String host = reply.getString("host");
            String fingerprint = reply.optString("fingerprint", "");
            if (!"dsh-host".equals(reply.getString("type"))
                    || (requireNonce && !reply.has("nonce"))
                    || (reply.has("nonce") && !nonce.equals(reply.getString("nonce")))
                    || !id.matches("[a-f0-9]{12}")
                    || !host.equals("dsh-" + id + ".local")
                    || port < 1 || port > 65535) return null;
            if (!fingerprint.matches("[a-f0-9]{64}")) return null;
            String address = packet.getAddress().getHostAddress();
            return new Found(reply.optString("name", "电脑"), id,
                    "https://" + address + ":" + port, fingerprint);
        } catch (Exception ignored) { return null; }
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
        String nonce = newNonce();
        byte[] probe = new JSONObject().put("type", "dsh-discover")
                .put("nonce", nonce).toString().getBytes(StandardCharsets.UTF_8);
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
                    Found item = parseReply(packet, nonce, false);
                    if (item != null && found.stream().noneMatch(existing ->
                            existing.secureUrl.equals(item.secureUrl))) found.add(item);
                }
            }
        } finally {
            if (multicastHeld) multicast.release();
        }
        return found;
    }
}
