package com.sliit.eventsphere.notification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SMTP conversation, checked against something that answers like a
 * mail server rather than against a mock of our own client.
 *
 * What is actually being proven here is that the bytes on the wire are
 * right: the command order, the multi-line EHLO reply being consumed to
 * its last line, the message terminator, and - the one that matters for
 * security - that a newline in a subject or an address cannot be used to
 * inject headers.
 *
 * What is NOT proven: behaviour against a real provider. There is no
 * outbound SMTP from the environment this was written in, so the far end
 * here is a test double that speaks the protocol correctly. That
 * limitation is stated in SmtpClient's own comment too.
 */
class SmtpClientTest {

    private MailProperties properties(int port, String username, String password) {
        // start-tls off: the fake server is plaintext, and a TLS listener
        // would test the JDK's handshake rather than this client.
        return new MailProperties("localhost", port, username, password,
                "no-reply@eventsphere.test", "EventSphere", false, 3000);
    }

    // =================================================================
    // The happy path
    // =================================================================

    @Test
    void sendsTheCommandsInTheOrderSmtpRequires() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            new SmtpClient(properties(server.port(), "", ""))
                    .send("attendee@test.com", "Hello", "Body text");

            List<String> lines = server.received();
            assertTrue(lines.get(0).startsWith("EHLO"), lines.toString());
            assertTrue(lines.contains("MAIL FROM:<no-reply@eventsphere.test>"), lines.toString());
            assertTrue(lines.contains("RCPT TO:<attendee@test.com>"), lines.toString());
            assertTrue(lines.contains("DATA"), lines.toString());
            assertTrue(lines.contains("."), lines.toString());
            assertTrue(lines.contains("QUIT"), lines.toString());
        }
    }

    @Test
    void theMultiLineEhloReplyIsConsumedToItsLastLine() throws Exception {
        // The fake server answers EHLO with three lines. A client that
        // read only the first would mistake a continuation line for the
        // reply to MAIL FROM and fall out of step for the rest of the
        // conversation - so this passing at all is the assertion.
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            assertDoesNotThrow(() -> new SmtpClient(properties(server.port(), "", ""))
                    .send("attendee@test.com", "Hello", "Body"));
        }
    }

    @Test
    void theBodyArrivesIntactAfterDecoding() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            new SmtpClient(properties(server.port(), "", ""))
                    .send("attendee@test.com", "Hello",
                            "Your registration for \"AI Conference\" is confirmed.");

            String raw = server.body();
            String encoded = raw.substring(raw.indexOf("\n\n") + 2).replace("\n", "");
            String decoded = new String(Base64.getMimeDecoder().decode(encoded),
                    StandardCharsets.UTF_8);

            assertTrue(decoded.contains("AI Conference"), decoded);
        }
    }

    @Test
    void aBodyLineThatIsASingleDotCannotTruncateTheMessage() throws Exception {
        // Dot-stuffing is the classic SMTP bug: a line containing only a
        // "." ends the message. Base64 encoding the body removes the
        // possibility rather than relying on remembering to escape it.
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            new SmtpClient(properties(server.port(), "", ""))
                    .send("attendee@test.com", "Hello", "first\n.\nlast");

            String raw = server.body();
            String encoded = raw.substring(raw.indexOf("\n\n") + 2).replace("\n", "");
            String decoded = new String(Base64.getMimeDecoder().decode(encoded),
                    StandardCharsets.UTF_8);

            assertTrue(decoded.contains("last"),
                    "the message was truncated at the lone dot: " + decoded);
        }
    }

    @Test
    void theHeadersCarryTheConfiguredSenderAndSubject() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            new SmtpClient(properties(server.port(), "", ""))
                    .send("attendee@test.com", "Registration confirmation", "Body");

            String raw = server.body();
            assertTrue(raw.contains("From: EventSphere <no-reply@eventsphere.test>"), raw);
            assertTrue(raw.contains("To: <attendee@test.com>"), raw);
            assertTrue(raw.contains("Subject: Registration confirmation"), raw);
            assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8"), raw);
        }
    }

    // =================================================================
    // Header injection
    //
    // The one genuinely dangerous input this class takes. A newline in a
    // subject or an address lets a caller append headers of their own -
    // a Bcc to somewhere else, a different From - or end the headers and
    // write their own body.
    // =================================================================

    @Test
    void aNewlineInTheSubjectIsRefused() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            SmtpClient client = new SmtpClient(properties(server.port(), "", ""));

            assertThrows(IOException.class, () -> client.send("attendee@test.com",
                    "Hello\r\nBcc: attacker@evil.test", "Body"));
        }
    }

    @Test
    void aNewlineInTheRecipientIsRefused() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            SmtpClient client = new SmtpClient(properties(server.port(), "", ""));

            assertThrows(IOException.class, () -> client.send(
                    "attendee@test.com>\r\nRCPT TO:<attacker@evil.test", "Hello", "Body"));
        }
    }

    @Test
    void aBlankRecipientIsRefused() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            SmtpClient client = new SmtpClient(properties(server.port(), "", ""));

            assertThrows(IOException.class, () -> client.send("  ", "Hello", "Body"));
        }
    }

    // =================================================================
    // Authentication and failure
    // =================================================================

    @Test
    void authenticatesWhenAUsernameIsConfigured() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            new SmtpClient(properties(server.port(), "postmaster", "secret"))
                    .send("attendee@test.com", "Hello", "Body");

            assertTrue(server.received().stream().anyMatch(l -> l.startsWith("AUTH PLAIN")),
                    server.received().toString());
        }
    }

    @Test
    void doesNotAuthenticateWhenNoUsernameIsConfigured() throws Exception {
        // Plenty of internal relays accept mail from inside the network
        // unauthenticated. Sending AUTH anyway would break those.
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            new SmtpClient(properties(server.port(), "", ""))
                    .send("attendee@test.com", "Hello", "Body");

            assertTrue(server.received().stream().noneMatch(l -> l.startsWith("AUTH")),
                    server.received().toString());
        }
    }

    @Test
    void aRejectedMessageIsReportedRatherThanSilentlySwallowed() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            server.refuseMessage();
            SmtpClient client = new SmtpClient(properties(server.port(), "", ""));

            IOException ex = assertThrows(IOException.class,
                    () -> client.send("attendee@test.com", "Hello", "Body"));
            assertTrue(ex.getMessage().contains("550"), ex.getMessage());
        }
    }

    @Test
    void anUnreachableServerFailsWithAnError() {
        // Port 1 on localhost: nothing listens there.
        SmtpClient client = new SmtpClient(properties(1, "", ""));

        assertThrows(IOException.class,
                () -> client.send("attendee@test.com", "Hello", "Body"));
    }

    @Test
    void anUnconfiguredClientRefusesToSendRatherThanPretending() {
        MailProperties unconfigured =
                new MailProperties("", 587, "", "", "", "EventSphere", true, 3000);

        assertFalse(unconfigured.isConfigured());
        assertThrows(IOException.class, () -> new SmtpClient(unconfigured)
                .send("attendee@test.com", "Hello", "Body"));
    }
}
