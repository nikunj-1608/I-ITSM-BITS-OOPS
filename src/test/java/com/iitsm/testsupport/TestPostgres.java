package com.iitsm.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

/**
 * Starts the in-process PostgreSQL used by tests that must run on the production database engine.
 *
 * <p>Two things the library does not handle on Windows: its random port can fall into a range the
 * OS reserves (PostgreSQL then logs "could not listen on IPv4 address 127.0.0.1: Permission denied",
 * listens on IPv6 only and the client's IPv4 connection is refused), and its default 10 s start-up
 * wait is too short on slow or virus-scanned machines. So the port is chosen by binding it on
 * 127.0.0.1 first, and a failed start is retried with a new port.
 */
public final class TestPostgres {

    private static final Duration STARTUP_WAIT = Duration.ofSeconds(45);
    private static final int MAX_ATTEMPTS = 4;

    private TestPostgres() { }

    public static EmbeddedPostgres start() throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return EmbeddedPostgres.builder()
                        .setOverrideWorkingDirectory(new File("target/embedded-pg").getAbsoluteFile())
                        .setPort(freeLoopbackPort())
                        .setPGStartupWait(STARTUP_WAIT)
                        .start();
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw new IOException("Embedded PostgreSQL did not start after " + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    public static void stop(EmbeddedPostgres postgres) throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    /** A port the OS lets us bind on the IPv4 loopback address, which is where clients connect. */
    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
