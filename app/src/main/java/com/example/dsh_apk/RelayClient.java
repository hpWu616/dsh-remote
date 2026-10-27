package com.example.dsh_apk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

final class RelayClient {
    static final class Result {
        final int status;
        final InputStream stream;
        Result(int status, InputStream stream) { this.status = status; this.stream = stream; }
    }

    private static final class Pending {
        final CountDownLatch headers = new CountDownLatch(1);
        final PipedInputStream input;
        final PipedOutputStream output;
        volatile int status = 502;
        volatile IOException error;
        Pending() throws IOException {
            input = new PipedInputStream(128 * 1024);
            output = new PipedOutputStream(input);
        }
        void fail(String reason) {
            error = new IOException(reason);
            headers.countDown();
            try { output.close(); } catch (IOException ignored) { }
        }
    }

    private final Activity activity;
    private final SecretStore secrets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Semaphore requestSlot = new Semaphore(1, true);
    private final WebView webView;
    private final CountDownLatch pageReady = new CountDownLatch(1);
    private volatile String activeUrl = "";
    private volatile String connectedUrl = "";
    private volatile String pairedToken = "";
    private volatile String connectionError = "";
    private volatile CountDownLatch connected;
    private volatile String challenge = "";

    RelayClient(Activity activity, SecretStore secrets) throws IOException {
        this.activity = activity;
        this.secrets = secrets;
        try (InputStream stream = activity.getAssets().open("relay.html")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            String html = bytes.toString("UTF-8");
            webView = new WebView(activity);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(false);
            webView.getSettings().setAllowFileAccess(false);
            webView.getSettings().setAllowContentAccess(false);
            webView.getSettings().setMixedContentMode(
                    android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            webView.addJavascriptInterface(this, "AndroidRelay");
            webView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    pageReady.countDown();
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view,
                        WebResourceRequest request) { return true; }
            });
            webView.loadDataWithBaseURL("https://dsh-remote.invalid/", html,
                    "text/html", "UTF-8", null);
        }
    }

    WebView webView() { return webView; }

    static boolean isRelayUrl(String url) {
        try {
            URI uri = new URI(url);
            return "relay".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getPort() == -1 && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getPath().matches("/[a-f0-9]{12}")
                    && uri.getHost().matches("[a-zA-Z0-9.-]+")
                    && !uri.getHost().contains("..");
        } catch (Exception ignored) { return false; }
    }

    private static String origin(String url) throws Exception {
        URI uri = new URI(url);
        return "relay://" + uri.getHost() + uri.getPath();
    }

    private void script(String javascript) {
        main.post(() -> webView.evaluateJavascript(javascript, null));
    }

    synchronized String pair(String url, String code) throws Exception {
        if (!code.matches("[0-9]{8}")) throw new IOException("请输入 8 位配对码");
        requestSlot.acquire();
        try {
            connect(origin(url), "", code);
            if (pairedToken.isEmpty()) throw new IOException("PC 未返回配对令牌");
            return pairedToken;
        } finally { requestSlot.release(); }
    }

    synchronized Result request(String url, String token, String method,
                                byte[] body) throws Exception {
        requestSlot.acquire();
        boolean handedOff = false;
        try {
        String device = origin(url);
        connect(device, token, "");
        if (body.length > 12 * 1024 * 1024) throw new IOException("请求内容过大");
        String id = UUID.randomUUID().toString().replace("-", "");
        Pending state = new Pending();
        pending.put(id, state);
        try {
            String path = url.substring(device.length());
            if (path.isEmpty()) path = "/v1/health";
            if (!path.startsWith("/v1/")) throw new IOException("无效的远程路径");
            send(new JSONObject().put("type", "request").put("id", id)
                    .put("method", method).put("path", path));
            for (int offset = 0; offset < body.length; offset += 8192) {
                int length = Math.min(8192, body.length - offset);
                byte[] part = new byte[length];
                System.arraycopy(body, offset, part, 0, length);
                send(new JSONObject().put("type", "chunk").put("id", id)
                        .put("data", Base64.encodeToString(part, Base64.NO_WRAP)));
            }
            send(new JSONObject().put("type", "end").put("id", id));
            if (!state.headers.await(120, TimeUnit.SECONDS)) {
                throw new IOException("等待 PC 响应超时");
            }
            if (state.error != null) throw state.error;
            AtomicBoolean released = new AtomicBoolean();
            InputStream stream = new FilterInputStream(state.input) {
                private void release() {
                    if (released.compareAndSet(false, true)) requestSlot.release();
                }
                @Override public int read() throws IOException {
                    int value = super.read();
                    if (value < 0) release();
                    return value;
                }
                @Override public int read(byte[] bytes, int offset, int length)
                        throws IOException {
                    int count = super.read(bytes, offset, length);
                    if (count < 0) release();
                    return count;
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { release(); }
                }
            };
            handedOff = true;
            return new Result(state.status, stream);
        } catch (Exception error) {
            pending.remove(id);
            state.fail(error.getMessage());
            throw error;
        }
        } finally { if (!handedOff) requestSlot.release(); }
    }

    private void send(JSONObject frame) {
        script("window.relaySendFrame(" + JSONObject.quote(frame.toString()) + ")");
    }

    private void connect(String url, String token, String code) throws Exception {
        if (!isRelayUrl(url)) throw new IOException("无效的远程设备地址");
        if (url.equals(connectedUrl) && code.isEmpty()) return;
        if (!pageReady.await(5, TimeUnit.SECONDS)) {
            throw new IOException("手机 WebView 尚未准备好");
        }
        connectedUrl = "";
        activeUrl = url;
        pairedToken = "";
        connectionError = "";
        connected = new CountDownLatch(1);
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder nonce = new StringBuilder();
        for (byte value : random) nonce.append(String.format("%02x", value & 255));
        challenge = nonce.toString();
        URI uri = new URI(url);
        String proof = "";
        if (!token.isEmpty()) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(challenge.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder();
            for (byte value : digest) encoded.append(String.format("%02x", value & 255));
            proof = encoded.toString();
        }
        JSONObject settings = new JSONObject().put("signalUrl",
                "wss://" + uri.getHost() + "/signal")
                .put("hostId", uri.getPath().substring(1))
                .put("nonce", challenge).put("proof", proof).put("code", code);
        script("window.relayConnect(" + settings.toString() + ")");
        if (!connected.await(45, TimeUnit.SECONDS)) {
            throw new IOException("等待远程电脑连接超时");
        }
        if (!connectionError.isEmpty()) throw new IOException(connectionError);
        connectedUrl = url;
    }

    private void verifyIdentity(JSONObject event) throws Exception {
        String pem = event.getString("certificate");
        String hostPublicKey = event.getString("hostPublicKey");
        String phonePublicKey = event.getString("phonePublicKey");
        String signature = event.getString("signature");
        if (!challenge.equals(event.getString("nonce"))) throw new IOException("连接挑战不匹配");
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(
                        pem.getBytes(StandardCharsets.US_ASCII)));
        certificate.checkValidity();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : digest) fingerprint.append(String.format("%02x", value & 255));
        String expected = secrets.fingerprint(activeUrl);
        if (expected.length() < 24 || !fingerprint.toString().startsWith(expected)) {
            throw new IOException("电脑身份码不匹配，请在 PC 插件中核对后重试");
        }
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update((challenge + "\n" + hostPublicKey + "\n" + phonePublicKey)
                .getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(Base64.decode(signature, Base64.DEFAULT))) {
            throw new IOException("电脑信令签名校验失败");
        }
        script("window.relayApproveIdentity(" + JSONObject.quote(hostPublicKey) + ")");
    }

    @JavascriptInterface public void onEvent(String raw) {
        try {
            JSONObject event = new JSONObject(raw);
            String type = event.optString("type");
            if ("identity".equals(type)) {
                try { verifyIdentity(event); }
                catch (Exception error) { fail(error.getMessage()); }
            } else if ("paired".equals(type)) {
                pairedToken = event.optString("token");
                if (pairedToken.matches("[a-f0-9]{64}")) connected.countDown();
                else fail("PC 返回的配对令牌无效");
            } else if ("response".equals(type)) {
                Pending state = pending.get(event.optString("id"));
                if (state != null) {
                    state.status = event.optInt("status", 502);
                    state.headers.countDown();
                }
            } else if ("chunk".equals(type)) {
                Pending state = pending.get(event.optString("id"));
                if (state != null) state.output.write(Base64.decode(
                        event.getString("data"), Base64.DEFAULT));
            } else if ("end".equals(type)) {
                Pending state = pending.remove(event.optString("id"));
                if (state != null) state.output.close();
            } else if ("error".equals(type) || "closed".equals(type)) {
                connectedUrl = "";
                fail(event.optString("message", "远程连接中断"));
            }
        } catch (Exception ignored) { fail("远程数据格式错误"); }
    }

    private void fail(String message) {
        connectionError = message;
        CountDownLatch latch = connected;
        if (latch != null) latch.countDown();
        for (Pending state : pending.values()) state.fail(message);
        pending.clear();
    }

    void close() {
        script("window.relayDisconnect()");
        webView.destroy();
    }
}
