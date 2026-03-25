package mobileproxy;

/**
 * Stub for the gomobile-generated Proxy class.
 *
 * @see StreamDialer for replacement instructions.
 */
public final class Proxy {

    private Proxy() {}

    /**
     * Returns the local address the proxy is listening on (e.g. "127.0.0.1:12345").
     */
    public String address() {
        throw new UnsupportedOperationException(
                "Real mobileproxy.aar not present. " +
                "Run: gomobile bind -target=android github.com/Jigsaw-Code/outline-sdk/x/mobileproxy " +
                "and place mobileproxy.aar in TMessagesProj/libs/");
    }

    /**
     * Stops the proxy.
     *
     * @param timeoutSeconds seconds to wait for graceful shutdown; 0 = immediate.
     */
    public void stop(long timeoutSeconds) {
        // stub — no-op without the real library
    }
}
