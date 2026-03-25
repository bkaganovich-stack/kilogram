/*
 * Outline proxy support for Telegram Android.
 *
 * This class manages the lifecycle of a local SOCKS5 forwarder that translates
 * Telegram's standard SOCKS5 traffic into Shadowsocks AEAD (with optional
 * prefix-padding) connections as specified by an Outline ss:// access key.
 *
 * Architecture
 * ──────────────────────────────────────────────────────────────────
 *  Telegram tgnet (C++)
 *       │  SOCKS5 to 127.0.0.1:localPort
 *       ▼
 *  MobileProxy (Java / gomobile-bound Go)
 *       │  Shadowsocks AEAD + prefix
 *       ▼
 *  Outline server
 *
 * The MobileProxy library must be present as either:
 *   • A JitPack dependency (com.github.Jigsaw-Code:outline-sdk:…), or
 *   • A local AAR in TMessagesProj/libs/mobileproxy.aar generated via:
 *       gomobile bind -target=android github.com/Jigsaw-Code/outline-sdk/x/mobileproxy
 *
 * IPv6 / Happy-Eyeballs note
 * ──────────────────────────────────────────────────────────────────
 * Shadowsocks does not produce early TCP RSTs for unreachable IPv6 routes, which
 * causes tgnet's Happy-Eyeballs code to stall in "Connecting…" for several seconds
 * before falling back to IPv4.  When the Outline proxy is active,
 * ConnectionsManager therefore calls native_setIpStrategy with ONLY_IPV4 to
 * disable direct local IPv6 attempts and force all DNS through the SOCKS5 proxy
 * (remote resolution).  The strategy is restored when Outline is deactivated.
 */
package org.telegram.messenger;

import android.text.TextUtils;

import mobileproxy.Mobileproxy;
import mobileproxy.Proxy;
import mobileproxy.StreamDialer;

/**
 * Singleton that owns the MobileProxy instance for the Outline proxy type.
 *
 * All public methods are thread-safe.
 */
public class OutlineProxyManager {

    private static volatile OutlineProxyManager instance;

    private Proxy    activeProxy;
    private String   activeKey;
    private int      localPort = -1;

    private OutlineProxyManager() {}

    public static OutlineProxyManager getInstance() {
        if (instance == null) {
            synchronized (OutlineProxyManager.class) {
                if (instance == null) {
                    instance = new OutlineProxyManager();
                }
            }
        }
        return instance;
    }

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /**
     * Starts (or restarts) the local SOCKS5 forwarder for the given Outline
     * access key.  If a proxy with the same key is already running the existing
     * port is returned immediately without restarting.
     *
     * @param outlineKey  The ss:// access key string (may include ?prefix=…).
     * @return The local port on 127.0.0.1 that tgnet should connect to.
     * @throws Exception If MobileProxy fails to bind or parse the config.
     */
    public synchronized int startProxy(String outlineKey) throws Exception {
        if (TextUtils.isEmpty(outlineKey)) {
            throw new IllegalArgumentException("Outline access key must not be empty");
        }

        // Avoid a teardown/restart cycle if the key hasn't changed.
        if (activeProxy != null && outlineKey.equals(activeKey)) {
            return localPort;
        }

        stopProxyInternal();

        // Real gomobile API: newStreamDialerFromConfig takes only the config string.
        StreamDialer dialer = Mobileproxy.newStreamDialerFromConfig(outlineKey);
        activeProxy = Mobileproxy.runProxy("127.0.0.1:0", dialer);
        activeKey   = outlineKey;

        // proxy.port() returns the dynamically assigned local port as a long.
        localPort = (int) activeProxy.port();
        if (localPort <= 0) {
            throw new RuntimeException("MobileProxy returned invalid port: " + localPort);
        }
        FileLog.d("OutlineProxyManager: local SOCKS5 started on " + activeProxy.address());
        return localPort;
    }

    /**
     * Stops the running local proxy and resets all state.
     * Safe to call even when no proxy is running.
     */
    public synchronized void stopProxy() {
        stopProxyInternal();
    }

    /** @return The local port currently in use, or -1 if not running. */
    public synchronized int getLocalPort() {
        return localPort;
    }

    /** @return {@code true} if a proxy is currently running. */
    public synchronized boolean isRunning() {
        return activeProxy != null && localPort > 0;
    }

    // ──────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────

    private void stopProxyInternal() {
        if (activeProxy != null) {
            try {
                // timeout = 0 → graceful shutdown without waiting
                activeProxy.stop(0);
            } catch (Exception e) {
                FileLog.e("OutlineProxyManager: error stopping proxy", e);
            }
            activeProxy = null;
            activeKey   = null;
            localPort   = -1;
            FileLog.d("OutlineProxyManager: local SOCKS5 stopped");
        }
    }
}
