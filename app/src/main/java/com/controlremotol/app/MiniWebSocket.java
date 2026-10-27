package com.controlremotol.app;

import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.net.ssl.*;

/** Minimal RFC 6455 client with mandatory client masking. No third-party dependencies. */
final class MiniWebSocket {
    interface Listener { void onText(String text); void onClose(String detail); }
    private final Listener listener;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();
    private volatile boolean running = true;

    private MiniWebSocket(Socket socket, Listener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    static MiniWebSocket open(String url, Listener listener) throws Exception {
        URI uri = new URI(url);
        String scheme = uri.getScheme();
        if (!"wss".equals(scheme) && !"ws".equals(scheme)) throw new IOException("Esquema no admitido");
        String host = uri.getHost();
        if (host == null) throw new IOException("Dirección no válida");
        int port = uri.getPort() > 0 ? uri.getPort() : (scheme.equals("wss") ? 443 : 80);
        Socket sock;
        if (scheme.equals("wss")) {
            // Smart TV certificates are often locally issued/self-signed; LAN host is restricted by TvClient.
            TrustManager[] localTvTrust = { new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, localTvTrust, new SecureRandom());
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 4000);
            SSLSocket tls = (SSLSocket)context.getSocketFactory().createSocket(raw, host, port, true);
            tls.setSoTimeout(6000);
            tls.startHandshake();
            sock = tls;
        } else {
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), 4000);
        }
        sock.setSoTimeout(6000);
        try {
            MiniWebSocket ws = new MiniWebSocket(sock, listener);
            byte[] keyData = new byte[16];
            ws.random.nextBytes(keyData);
            String key = Base64.encodeToString(keyData, Base64.NO_WRAP);
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port +
                "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " + key + "\r\n\r\n";
            ws.out.write(req.getBytes(StandardCharsets.UTF_8));
            ws.out.flush();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            while (header.size() < 16000) {
                int c = ws.in.read();
                if (c < 0) throw new EOFException("Sin respuesta del televisor");
                header.write(c);
                byte[] b = header.toByteArray();
                int n = b.length;
                if (n >= 4 && b[n-4] == 13 && b[n-3] == 10 && b[n-2] == 13 && b[n-1] == 10) break;
            }
            String response = header.toString("UTF-8");
            if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101"))
                throw new IOException("WebSocket rechazado: " + response.split("\r?\n")[0]);
            String accept = "";
            for (String line: response.split("\r?\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-accept:"))
                    accept = line.substring(line.indexOf(':')+1).trim();
            }
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String expected = Base64.encodeToString(sha1.digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
            if (!expected.equals(accept)) throw new IOException("Respuesta WebSocket no verificada");
            sock.setSoTimeout(1000);
            Thread reader = new Thread(ws::readLoop, "TV-WS-Reader");
            reader.setDaemon(true);
            reader.start();
            return ws;
        } catch (Exception e) {
            try { sock.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    synchronized void send(String message) throws IOException {
        sendFrame(1, message.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(int opcode, byte[] data) throws IOException {
        if (!running) throw new IOException("Conexión cerrada");
        out.write(0x80 | opcode);
        int len = data.length;
        if (len < 126) out.write(0x80 | len);
        else if (len <= 65535) { out.write(0x80 | 126); out.write(len >>> 8); out.write(len); }
        else { out.write(0x80 | 127); for (int i=7;i>=0;i--) out.write((int)(((long)len >>> (8*i)) & 255)); }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        out.write(mask);
        for (int i=0;i<len;i++) out.write(data[i] ^ mask[i&3]);
        out.flush();
    }

    private void readLoop() {
        String reason = "Conexión finalizada";
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        try {
            while (running) {
                int a;
                try { a = in.read(); } catch (SocketTimeoutException e) { continue; }
                if (a < 0) break;
                int b = readByte();
                if (b < 0) break;
                int opcode = a & 15;
                boolean fin = (a & 0x80) != 0;
                long len = b & 127;
                if (len == 126) len = (readByte() << 8) | readByte();
                else if (len == 127) {
                    len = 0;
                    for (int i=0;i<8;i++) len = (len << 8) | readByte();
                }
                if (len < 0 || len > 2_000_000) throw new IOException("Mensaje demasiado grande");
                boolean masked = (b & 0x80) != 0;
                byte[] mask = new byte[4];
                if (masked) readFully(mask);
                byte[] payload = new byte[(int)len];
                readFully(payload);
                if (masked) for (int i=0;i<payload.length;i++) payload[i] ^= mask[i&3];
                if (opcode == 8) { reason = "La TV cerró la conexión"; break; }
                if (opcode == 9) { sendFrame(10, payload); continue; }
                if (opcode == 10) continue;
                if (opcode == 1 || opcode == 0) {
                    joined.write(payload);
                    if (fin) {
                        listener.onText(joined.toString("UTF-8"));
                        joined.reset();
                    }
                }
            }
        } catch (Exception e) { reason = e.getMessage() == null ? "Conexión perdida" : e.getMessage(); }
        finally {
            close();
            listener.onClose(reason);
        }
    }
    private int readByte() throws IOException {
        int v = in.read();
        if (v < 0) throw new EOFException("Conexión interrumpida");
        return v;
    }
    private void readFully(byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int n = in.read(buffer,off,buffer.length-off);
            if (n < 0) throw new EOFException("Mensaje incompleto");
            off += n;
        }
    }
    void close() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}
    }
}
