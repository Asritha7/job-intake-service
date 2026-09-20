package dev.asritha.intake;

/** Process-wide settings for the built-in JDK HTTP provider, before its first initialization. */
final class HttpLimits {
    static {
        int requestSeconds = Integer.parseInt(System.getProperty("intake.http.requestSeconds", "10"));
        if (requestSeconds < 1 || requestSeconds > 60)
            throw new IllegalArgumentException("intake.http.requestSeconds must be 1–60");
        // OpenJDK converts these values from seconds; exercise this in a real socket test.
        System.setProperty("sun.net.httpserver.maxReqTime", Integer.toString(requestSeconds));
        System.setProperty("sun.net.httpserver.maxRspTime", "15");
        System.setProperty("jdk.httpserver.maxConnections", "128");
        System.setProperty("sun.net.httpserver.maxIdleConnections", "16");
        System.setProperty("sun.net.httpserver.idleInterval", "15");
        System.setProperty("sun.net.httpserver.maxReqHeaders", "32");
        System.setProperty("sun.net.httpserver.maxReqHeaderSize", "16384");
        // Do not drain an unread body after rejecting invalid input.
        System.setProperty("sun.net.httpserver.drainAmount", "0");
    }
    static void initialize() { }
    private HttpLimits() { }
}
