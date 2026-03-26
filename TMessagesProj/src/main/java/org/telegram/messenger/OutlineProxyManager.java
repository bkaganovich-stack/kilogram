/*
 * Outline proxy support for Telegram Android.
 *
 * Architecture (corrected)
 * ──────────────────────────────────────────────────────────────────
 *  Telegram tgnet (C++)
 *       │  SOCKS5 CONNECT to 127.0.0.1:socks5Port
 *       ▼
 *  Socks5Bridge (Java — this package)
 *       │  HTTP CONNECT to 127.0.0.1:httpPort
 *       ▼
 *  MobileProxy (gomobile-bound Go, mobileproxy.aar)
 *       │  Shadowsocks AEAD + optional prefix-padding
 *       ▼
 *  Outline server
 *
 * Why the bridge?
 * mobileproxy.RunProxy() starts an HTTP proxy (net/http + HTTP CONNECT handler),
 * NOT a SOCKS5 server.  tgnet speaks SOCKS5.  Socks5Bridge translates between
 * the two protocols entirely in Java, with no third-party dependencies.
 *
 * IPv6 / Happy-Eyeballs note
 * ──────────────────────────────────────────────────────────────────
 * Shadowsocks does not produce early TCP RSTs for unreachable IPv6 routes, which
 * causes tgnet's Happy-Eyeballs code to stall.  When Outline is active,
 * ConnectionsManager calls native_setIpStrategy(USE_IPV4_ONLY).
 */
package org.telegram.messenger;

import android.text.TextUtils;

import mobileproxy.Mobileproxy;
import mobileproxy.Proxy;
import mobileproxy.StreamDialer;

/**
 * Singleton that owns the MobileProxy HTTP proxy + Socks5Bridge instances.
 * All public methods are thread-safe.
 */
public class OutlineProxyManager {

    private static volatile OutlineProxyManager instance;

    /** The HTTP proxy from mobileproxy (Outline Shadowsocks transport). */
    private Proxy         activeHttpProxy;
    /** The SOCKS5 bridge that tgnet connects to. */
    private Socks5Bridge  activeBridge;
    private String        activeKey;
    /** Port of the SOCKS5 bridge — what we hand to native_setProxySettings. */
    private int           socks5Port = -1;

    private OutlineProxyManager() {}

    public static OutlineProxyManager getInstance() {
        if (instance == null) {
            synchronized (OutlineProxyManager.class) {
                if (instance == null) instance = new OutlineProxyManager();
            }
        }
        return instance;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts (or reuses) the local proxy stack for the given Outline access key.
     *
     * @return The SOCKS5 port on 127.0.0.1 for tgnet to connect to.
     */
    public synchronized int startProxy(String outlineKey) throws Exception {
        if (TextUtils.isEmpty(outlineKey)) {
            throw new IllegalArgumentException("Outline access key must not be empty");
        }
        if (activeHttpProxy != null && outlineKey.equals(activeKey)) {
            return socks5Port; // already running with the same key
        }
        stopProxyInternal();

        // 1. Create the Shadowsocks StreamDialer from the ss:// key.
        StreamDialer dialer = Mobileproxy.newStreamDialerFromConfig(outlineKey);

        // 2. Start the HTTP proxy (mobileproxy).  Port 0 → OS picks a free port.
        activeHttpProxy = Mobileproxy.runProxy("127.0.0.1:0", dialer);
        int httpPort = (int) activeHttpProxy.port();
        FileLog.d("OutlineProxyManager: HTTP proxy on " + activeHttpProxy.address());

        // 3. Start the SOCKS5 bridge pointing at the HTTP proxy.
        activeBridge = new Socks5Bridge("127.0.0.1", httpPort);
        socks5Port   = activeBridge.getPort();
        activeKey    = outlineKey;
        FileLog.d("OutlineProxyManager: SOCKS5 bridge on 127.0.0.1:" + socks5Port);
        return socks5Port;
    }

    /** Stops everything. Safe to call even when nothing is running. */
    public synchronized void stopProxy() {
        stopProxyInternal();
    }

    /** @return SOCKS5 port, or -1 if not running. */
    public synchronized int getLocalPort() {
        return socks5Port;
    }

    public synchronized boolean isRunning() {
        return activeHttpProxy != null && socks5Port > 0;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void stopProxyInternal() {
        if (activeBridge != null) {
            activeBridge.stop();
            activeBridge = null;
        }
        if (activeHttpProxy != null) {
            try {
                activeHttpProxy.stop(0);
            } catch (Exception e) {
                FileLog.e("OutlineProxyManager: error stopping HTTP proxy", e);
            }
            activeHttpProxy = null;
        }
        activeKey  = null;
        socks5Port = -1;
        FileLog.d("OutlineProxyManager: stopped");
    }
}
