package com.conversive.aep.common.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Raw socket HTTP server that answers every request with a 200 header and then {@code body} one byte per
 * {@code gapMillis}: each read is fast, the whole body is slow, which per-read timeouts cannot catch.
 */
final class DripServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final byte[] body;
    private final long gapMillis;

    DripServer(String body, long gapMillis) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.body = body.getBytes(StandardCharsets.UTF_8);
        this.gapMillis = gapMillis;
        this.acceptor = Thread.ofVirtual().start(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            drainRequestHead(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            for (byte b : body) {
                Thread.sleep(gapMillis);
                out.write(b);
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // the client gave up on the call, which is what the tests expect
        }
    }

    private static void drainRequestHead(InputStream in) throws IOException {
        int matched = 0;
        while (matched < 4) {
            int c = in.read();
            if (c < 0) {
                return;
            }
            boolean crlf = (matched % 2 == 0 && c == '\r') || (matched % 2 == 1 && c == '\n');
            matched = crlf ? matched + 1 : (c == '\r' ? 1 : 0);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        acceptor.interrupt();
    }
}
