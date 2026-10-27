package com.example.dsh_apk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

final class RelayHttpURLConnection extends HttpURLConnection {
    private final RelayClient client;
    private final String address;
    private final String token;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private RelayClient.Result result;

    RelayHttpURLConnection(String address, String token, RelayClient client) throws IOException {
        super(new URL(null, address, new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                throw new UnsupportedOperationException();
            }
        }));
        this.address = address;
        this.token = token;
        this.client = client;
    }

    @Override public void connect() throws IOException {
        if (connected) return;
        try {
            result = client.request(address, token, method, body.toByteArray());
            connected = true;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException(error); }
    }

    @Override public int getResponseCode() throws IOException {
        connect();
        return result.status;
    }

    @Override public InputStream getInputStream() throws IOException {
        connect();
        if (result.status >= 400) throw new IOException("HTTP " + result.status);
        return result.stream;
    }

    @Override public InputStream getErrorStream() {
        try { connect(); return result.status >= 400 ? result.stream : null; }
        catch (IOException ignored) { return null; }
    }

    @Override public OutputStream getOutputStream() { return body; }

    @Override public void disconnect() {
        try { if (result != null) result.stream.close(); }
        catch (IOException ignored) { }
        connected = false;
    }

    @Override public boolean usingProxy() { return false; }
}
