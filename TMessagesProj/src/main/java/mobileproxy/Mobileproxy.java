package mobileproxy;

/**
 * Stub for the gomobile-generated Mobileproxy class (package-level Go functions).
 *
 * @see StreamDialer for replacement instructions.
 */
public final class Mobileproxy {

    private Mobileproxy() {}

    /**
     * Creates a StreamDialer from a transport configuration string (e.g. an ss:// key).
     *
     * @param transportConfig The Outline ss:// access key.
     * @param fallback        Fallback dialer (may be null).
     */
    public static StreamDialer newStreamDialerFromConfig(
            String transportConfig, StreamDialer fallback) throws Exception {
        throw new UnsupportedOperationException(
                "Real mobileproxy.aar not present. " +
                "Run: gomobile bind -target=android github.com/Jigsaw-Code/outline-sdk/x/mobileproxy " +
                "and place mobileproxy.aar in TMessagesProj/libs/");
    }

    /**
     * Starts a local SOCKS5 proxy that forwards connections via {@code dialer}.
     *
     * @param localAddr Local address to listen on, e.g. "127.0.0.1:0" (0 = dynamic port).
     * @param dialer    StreamDialer created by {@link #newStreamDialerFromConfig}.
     * @return A running {@link Proxy} instance.
     */
    public static Proxy runProxy(String localAddr, StreamDialer dialer) throws Exception {
        throw new UnsupportedOperationException(
                "Real mobileproxy.aar not present. " +
                "Run: gomobile bind -target=android github.com/Jigsaw-Code/outline-sdk/x/mobileproxy " +
                "and place mobileproxy.aar in TMessagesProj/libs/");
    }
}
