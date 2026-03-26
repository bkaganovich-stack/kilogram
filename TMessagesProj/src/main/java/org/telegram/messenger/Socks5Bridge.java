package org.telegram.messenger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal SOCKS5 server that bridges tgnet (SOCKS5 client) to the local HTTP CONNECT proxy
 * started by mobileproxy (Outline SDK).
 *
 * Architecture:
 *   tgnet (SOCKS5) → Socks5Bridge → HTTP CONNECT → mobileproxy → Shadowsocks → Outline server
 *
 * Only CONNECT (TCP tunnel) is supported — no UDP ASSOCIATE, no BIND.
 * No SOCKS5 authentication is required from the client (tgnet always uses no-auth).
 */
public class Socks5Bridge {

    private final ServerSocket serverSocket;
    private final String httpProxyHost;
    private final int httpProxyPort;
    private final ExecutorService executor;
    private volatile boolean stopped = false;

    private static final byte SOCKS5_VERSION   = 0x05;
    private static final byte NO_AUTH          = 0x00;
    private static final byte CMD_CONNECT      = 0x01;
    private static final byte ATYP_IPV4        = 0x01;
    private static final byte ATYP_DOMAIN      = 0x03;
    private static final byte ATYP_IPV6        = 0x04;
    private static final byte REP_SUCCESS      = 0x00;
    private static final byte REP_GENERAL_ERR  = 0x01;

    public Socks5Bridge(String httpProxyHost, int httpProxyPort) throws IOException {
        this.httpProxyHost = httpProxyHost;
        this.httpProxyPort = httpProxyPort;
        // Bind on loopback only, OS picks the port.
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Socks5Bridge-worker");
            t.setDaemon(true);
            return t;
        });
        startAcceptLoop();
    }

    /** @return The local port tgnet should connect to (SOCKS5). */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** Stops the bridge. Existing connections are closed. */
    public void stop() {
        stopped = true;
        try { serverSocket.close(); } catch (IOException ignored) {}
        executor.shutdownNow();
    }

    // ── Accept loop ──────────────────────────────────────────────────────────

    private void startAcceptLoop() {
        Thread acceptThread = new Thread(() -> {
            while (!stopped) {
                try {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (!stopped) FileLog.e("Socks5Bridge: accept error", e);
                }
            }
        }, "Socks5Bridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    // ── Per-connection handler ────────────────────────────────────────────────

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(15_000);
            InputStream in  = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // ── Phase 1: auth negotiation ──────────────────────────────────
            int version = in.read();
            if (version != SOCKS5_VERSION) {
                client.close();
                return;
            }
            int nMethods = in.read();
            boolean hasNoAuth = false;
            for (int i = 0; i < nMethods; i++) {
                if (in.read() == NO_AUTH) hasNoAuth = true;
            }
            if (!hasNoAuth) {
                out.write(new byte[]{SOCKS5_VERSION, (byte) 0xFF}); // no acceptable methods
                client.close();
                return;
            }
            out.write(new byte[]{SOCKS5_VERSION, NO_AUTH});

            // ── Phase 2: CONNECT request ───────────────────────────────────
            if (in.read() != SOCKS5_VERSION) { client.close(); return; }
            byte cmd  = (byte) in.read();
            in.read(); // reserved
            byte atyp = (byte) in.read();

            String targetHost;
            if (atyp == ATYP_IPV4) {
                byte[] addr = readFully(in, 4);
                targetHost = (addr[0] & 0xFF) + "." + (addr[1] & 0xFF) + "."
                           + (addr[2] & 0xFF) + "." + (addr[3] & 0xFF);
            } else if (atyp == ATYP_DOMAIN) {
                int len = in.read();
                byte[] domain = readFully(in, len);
                targetHost = new String(domain, StandardCharsets.UTF_8);
            } else if (atyp == ATYP_IPV6) {
                byte[] addr = readFully(in, 16);
                targetHost = "[" + bytesToIPv6(addr) + "]";
            } else {
                sendSocks5Reply(out, REP_GENERAL_ERR);
                client.close();
                return;
            }
            int high = in.read(), low = in.read();
            int targetPort = (high << 8) | low;

            if (cmd != CMD_CONNECT) {
                sendSocks5Reply(out, (byte) 0x07); // command not supported
                client.close();
                return;
            }

            // ── Phase 3: connect to mobileproxy via HTTP CONNECT ───────────
            Socket upstream = new Socket(httpProxyHost, httpProxyPort);
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(15_000);
            try {
                InputStream  uIn  = upstream.getInputStream();
                OutputStream uOut = upstream.getOutputStream();

                String connectReq = "CONNECT " + targetHost + ":" + targetPort
                        + " HTTP/1.1\r\nHost: " + targetHost + ":" + targetPort
                        + "\r\nProxy-Connection: keep-alive\r\n\r\n";
                uOut.write(connectReq.getBytes(StandardCharsets.US_ASCII));
                uOut.flush();

                // Read HTTP response line + headers
                String responseLine = readHttpLine(uIn);
                if (responseLine == null || !responseLine.contains("200")) {
                    FileLog.e("Socks5Bridge: HTTP CONNECT failed for "
                            + targetHost + ":" + targetPort + " → " + responseLine);
                    sendSocks5Reply(out, REP_GENERAL_ERR);
                    upstream.close();
                    client.close();
                    return;
                }
                // Drain remaining headers
                String headerLine;
                while ((headerLine = readHttpLine(uIn)) != null && !headerLine.isEmpty()) { /* skip */ }

                // ── Phase 4: reply success to tgnet ───────────────────────
                sendSocks5Reply(out, REP_SUCCESS);
                client.setSoTimeout(0);
                upstream.setSoTimeout(0);

                // ── Phase 5: pipe in both directions ──────────────────────
                Thread t = new Thread(() -> pipe(uIn, out, client, upstream),
                        "Socks5Bridge-pipe-up");
                t.setDaemon(true);
                t.start();
                pipe(in, uOut, upstream, client);
                t.join(500);
            } finally {
                try { upstream.close(); } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            FileLog.e("Socks5Bridge: connection error", e);
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void sendSocks5Reply(OutputStream out, byte rep) throws IOException {
        // VER REP RSV ATYP BND.ADDR(4 bytes) BND.PORT(2 bytes)
        out.write(new byte[]{SOCKS5_VERSION, rep, 0x00, ATYP_IPV4,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        out.flush();
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
        return buf;
    }

    /** Reads one CR-LF terminated line (without the CR-LF). Returns null on EOF. */
    private static String readHttpLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read();
                if (next == '\n') break;
                sb.append((char) c);
                if (next != -1) sb.append((char) next);
            } else if (c == '\n') {
                break;
            } else {
                sb.append((char) c);
            }
        }
        return (c == -1 && sb.length() == 0) ? null : sb.toString();
    }

    private static void pipe(InputStream src, OutputStream dst,
                             Socket closeSrc, Socket closeDst) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = src.read(buf)) != -1) {
                dst.write(buf, 0, n);
                dst.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { closeSrc.close(); } catch (IOException ignored2) {}
            try { closeDst.close(); } catch (IOException ignored2) {}
        }
    }

    private static String bytesToIPv6(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%x", ((b[i] & 0xFF) << 8) | (b[i+1] & 0xFF)));
        }
        return sb.toString();
    }
}
