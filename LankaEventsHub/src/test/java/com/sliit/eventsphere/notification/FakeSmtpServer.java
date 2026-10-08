package com.sliit.eventsphere.notification;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A real SMTP server, small enough to run inside a test.
 *
 * This exists because the alternative - mocking SmtpClient and asserting
 * it was called - proves nothing about the protocol, and the protocol is
 * the part that can be wrong. Here the client opens a socket, speaks
 * SMTP to something that answers like a mail server, and the test reads
 * back the exact bytes that would have gone to a real one.
 *
 * STARTTLS is deliberately NOT supported: it answers EHLO without
 * advertising it, and the tests configure the client with start-tls off.
 * Standing up a TLS listener would need a keystore and would test the
 * JDK's handshake rather than anything in this codebase.
 */
final class FakeSmtpServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread thread;
    private final CountDownLatch finished = new CountDownLatch(1);

    /** Every line the client sent, in order. */
    private final List<String> received = new ArrayList<>();

    /** Reply code for MAIL FROM onwards - 250 unless a test wants a refusal. */
    private volatile String dataReply = "250 Ok";
    private volatile String authReply = "235 Authenticated";

    FakeSmtpServer() throws IOException {
        // Port 0: the OS picks a free one, so parallel test runs and
        // whatever else is on this machine cannot collide.
        this.serverSocket = new ServerSocket(0);
        this.thread = new Thread(this::serve, "fake-smtp");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    void refuseAuthentication() {
        this.authReply = "535 Authentication failed";
    }

    void refuseMessage() {
        this.dataReply = "550 Rejected";
    }

    List<String> received() throws InterruptedException {
        finished.await(5, TimeUnit.SECONDS);
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    /** The message body as it arrived, between DATA and the lone dot. */
    String body() throws InterruptedException {
        List<String> lines = received();
        int dataAt = lines.indexOf("DATA");
        if (dataAt < 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = dataAt + 1; i < lines.size(); i++) {
            if (".".equals(lines.get(i))) {
                break;
            }
            out.append(lines.get(i)).append('\n');
        }
        return out.toString();
    }

    private void serve() {
        try (Socket socket = serverSocket.accept();
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                     true)) {

            out.print("220 fake.smtp ready\r\n");
            out.flush();

            boolean inData = false;
            String line;
            while ((line = in.readLine()) != null) {
                synchronized (received) {
                    received.add(line);
                }

                if (inData) {
                    if (".".equals(line)) {
                        inData = false;
                        reply(out, dataReply);
                    }
                    continue;
                }

                String upper = line.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                    // A multi-line reply on purpose: it is what a real
                    // server sends, and consuming it correctly is
                    // exactly the kind of thing a hand-written client
                    // gets wrong.
                    out.print("250-fake.smtp greets you\r\n");
                    out.print("250-AUTH PLAIN LOGIN\r\n");
                    out.print("250 SIZE 10240000\r\n");
                    out.flush();
                } else if (upper.startsWith("AUTH LOGIN")) {
                    reply(out, "334 VXNlcm5hbWU6");
                } else if (upper.startsWith("AUTH PLAIN")) {
                    reply(out, authReply);
                } else if (upper.startsWith("MAIL FROM")
                        || upper.startsWith("RCPT TO")) {
                    reply(out, "250 Ok");
                } else if (upper.startsWith("DATA")) {
                    inData = true;
                    reply(out, "354 End data with <CRLF>.<CRLF>");
                } else if (upper.startsWith("QUIT")) {
                    reply(out, "221 Bye");
                    break;
                } else {
                    // The base64 lines of an AUTH LOGIN exchange land
                    // here: username first (334), then password.
                    reply(out, alreadySawUsername() ? authReply : "334 UGFzc3dvcmQ6");
                }
            }
        } catch (IOException ignored) {
            // The client hung up, which several of these tests do on
            // purpose. Nothing to report.
        } finally {
            finished.countDown();
        }
    }

    private boolean alreadySawUsername() {
        synchronized (received) {
            int challenges = 0;
            for (String line : received) {
                if (line.toUpperCase(java.util.Locale.ROOT).startsWith("AUTH LOGIN")) {
                    challenges++;
                }
            }
            if (challenges == 0) {
                return false;
            }
            // Count the opaque lines after AUTH LOGIN: the first is the
            // username, the second the password.
            int afterAuth = 0;
            boolean seenAuth = false;
            for (String line : received) {
                if (line.toUpperCase(java.util.Locale.ROOT).startsWith("AUTH LOGIN")) {
                    seenAuth = true;
                    continue;
                }
                if (seenAuth) {
                    afterAuth++;
                }
            }
            return afterAuth >= 2;
        }
    }

    private static void reply(PrintWriter out, String text) {
        out.print(text + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        thread.interrupt();
    }
}
