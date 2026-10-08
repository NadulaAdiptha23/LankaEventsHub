package com.sliit.eventsphere.notification;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * A small SMTP client, written directly against sockets.
 *
 * WHY THIS EXISTS RATHER THAN spring-boot-starter-mail. The build
 * environment this was written in has no access to a package registry,
 * so the Jakarta Mail jars could not be added or verified. Rather than
 * declare a dependency nobody could resolve - or, worse, leave the EMAIL
 * channel as an enum value with nothing behind it, which is exactly the
 * defect this change exists to fix - the protocol is implemented here.
 * It is about a hundred lines because SMTP for a plain message is a
 * small protocol.
 *
 * WHAT IT DOES: connects, EHLO, optional STARTTLS, optional AUTH
 * (PLAIN or LOGIN), MAIL FROM, RCPT TO, DATA, QUIT. Sends UTF-8 text
 * using base64 transfer encoding, which sidesteps line-length and
 * eight-bit-cleanliness questions entirely.
 *
 * WHAT IT DOES NOT DO: attachments, HTML alternatives, connection
 * pooling, or delivery-status tracking. It is not a mail library and is
 * not trying to be one. {@link NotificationSender} is the seam - if the
 * Jakarta Mail dependency becomes available, an implementation backed by
 * JavaMailSender drops in behind the same interface and this class goes
 * away.
 *
 * HONEST LIMITATION: this has been tested against an in-process fake
 * SMTP server that speaks the protocol (see SmtpClientTest), not against
 * a live mail provider, because the environment has no outbound SMTP.
 * The conversation is correct; what has not been proven here is how a
 * particular provider behaves at the far end.
 */
public class SmtpClient {

    private static final DateTimeFormatter RFC_5322_DATE =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final MailProperties properties;

    public SmtpClient(MailProperties properties) {
        this.properties = properties;
    }

    /**
     * Sends one plain-text message.
     *
     * @throws IOException if the connection fails or the server rejects
     *                     any step. Deliberately thrown rather than
     *                     swallowed: the caller decides what a failure
     *                     means, and for notifications it must never be
     *                     allowed to fail the thing being notified about.
     */
    public void send(String toAddress, String subject, String body) throws IOException {
        if (!properties.isConfigured()) {
            throw new IOException("No mail server is configured.");
        }
        requireHeaderSafe(toAddress, "recipient address");
        requireHeaderSafe(subject, "subject");

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(properties.getHost(), properties.getPort()),
                    properties.getTimeoutMs());
            socket.setSoTimeout(properties.getTimeoutMs());

            Session session = new Session(socket);
            session.expect("220");
            session.command("EHLO " + localName(), "250");

            if (properties.isStartTls()) {
                session.command("STARTTLS", "220");
                socket = upgradeToTls(socket);
                session = new Session(socket);
                // A second EHLO is required after the handshake: the
                // capabilities announced before encryption are not the
                // ones that apply after it, and AUTH is usually only
                // offered on the encrypted side.
                session.command("EHLO " + localName(), "250");
            }

            if (properties.requiresAuthentication()) {
                authenticate(session);
            }

            session.command("MAIL FROM:<" + properties.getFrom() + ">", "250");
            session.command("RCPT TO:<" + toAddress + ">", "250", "251");
            session.command("DATA", "354");
            session.raw(message(toAddress, subject, body));
            session.expectAfterWrite("250");
            session.command("QUIT", "221");
        } finally {
            closeQuietly(socket);
        }
    }

    private void authenticate(Session session) throws IOException {
        // AUTH PLAIN first - one round trip instead of three, and every
        // server that supports authentication at all supports it. LOGIN
        // is the fallback for the handful that only offer that.
        String plain = "\0" + properties.getUsername() + "\0" + properties.getPassword();
        String encoded = Base64.getEncoder()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        try {
            session.command("AUTH PLAIN " + encoded, "235");
            return;
        } catch (IOException plainFailed) {
            // Fall through to LOGIN. The exception is not logged here:
            // it would be logged once per message, and the LOGIN attempt
            // below produces a better error if it fails too.
        }
        session.command("AUTH LOGIN", "334");
        session.command(base64(properties.getUsername()), "334");
        session.command(base64(properties.getPassword()), "235");
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The message itself, terminated by the lone dot DATA requires.
     *
     * The body is base64-encoded rather than sent as-is. That removes
     * three separate problems at once: the 998-character line limit, the
     * dot-stuffing rule (a line consisting of a single "." would end the
     * message early), and whether the server is eight-bit clean.
     */
    private String message(String toAddress, String subject, String body) {
        String encodedBody = Base64.getMimeEncoder()
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));

        StringBuilder message = new StringBuilder();
        message.append("From: ").append(encodeHeaderWord(properties.getFromName()))
               .append(" <").append(properties.getFrom()).append(">\r\n");
        message.append("To: <").append(toAddress).append(">\r\n");
        message.append("Subject: ").append(encodeHeaderWord(subject)).append("\r\n");
        message.append("Date: ").append(ZonedDateTime.now().format(RFC_5322_DATE)).append("\r\n");
        message.append("MIME-Version: 1.0\r\n");
        message.append("Content-Type: text/plain; charset=UTF-8\r\n");
        message.append("Content-Transfer-Encoding: base64\r\n");
        message.append("\r\n");
        message.append(encodedBody).append("\r\n");
        message.append(".\r\n");
        return message.toString();
    }

    /**
     * RFC 2047 encoded-word, so a subject containing a name like
     * "Nadeesha Wickramasinghe" survives intact whatever characters it
     * happens to contain. Plain ASCII is left alone, because an
     * unnecessarily encoded subject is harder to read in a mail client's
     * raw view and gains nothing.
     */
    private static String encodeHeaderWord(String value) {
        if (value.chars().allMatch(c -> c >= 32 && c < 127)) {
            return value;
        }
        return "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                + "?=";
    }

    /**
     * Refuses a CR or LF inside a value destined for a header.
     *
     * This is header injection, and it is the one genuinely dangerous
     * input this class handles: a newline in a recipient address or
     * subject lets the caller add headers of their own - a Bcc, a
     * different From - or terminate the headers and write their own
     * message body. Rejected rather than stripped, because a value
     * containing a newline is not a value anybody meant to send.
     */
    private static void requireHeaderSafe(String value, String what) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("A " + what + " is required.");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IOException("The " + what + " contains a line break, which is not allowed.");
        }
    }

    private Socket upgradeToTls(Socket plain) throws IOException {
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(plain, properties.getHost(), properties.getPort(), true);
        tls.setUseClientMode(true);
        tls.setSoTimeout(properties.getTimeoutMs());
        tls.startHandshake();
        return tls;
    }

    /** What we call ourselves in EHLO. Not load-bearing; servers rarely care. */
    private static String localName() {
        try {
            String name = java.net.InetAddress.getLocalHost().getHostName();
            return (name == null || name.isBlank()) ? "localhost" : name;
        } catch (IOException ex) {
            return "localhost";
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do, and the caller already has whatever
            // real failure brought us here.
        }
    }

    /**
     * One command/response conversation over an open socket.
     *
     * Multi-line replies (which EHLO always produces) are consumed to
     * their last line - the one whose fourth character is a space rather
     * than a hyphen - so the next command does not read somebody else's
     * continuation line and conclude the server said something it did not.
     */
    private static final class Session {
        private final BufferedReader in;
        private final Writer out;

        Session(Socket socket) throws IOException {
            this.in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream stream = socket.getOutputStream();
            this.out = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        }

        void command(String command, String... acceptedCodes) throws IOException {
            out.write(command + "\r\n");
            out.flush();
            expect(acceptedCodes);
        }

        void raw(String payload) throws IOException {
            out.write(payload);
            out.flush();
        }

        void expectAfterWrite(String... acceptedCodes) throws IOException {
            expect(acceptedCodes);
        }

        void expect(String... acceptedCodes) throws IOException {
            String line = in.readLine();
            if (line == null) {
                throw new IOException("The mail server closed the connection unexpectedly.");
            }
            while (line.length() >= 4 && line.charAt(3) == '-') {
                line = in.readLine();
                if (line == null) {
                    throw new IOException("The mail server closed the connection unexpectedly.");
                }
            }
            for (String accepted : acceptedCodes) {
                if (line.startsWith(accepted)) {
                    return;
                }
            }
            throw new IOException("The mail server replied \"" + line
                    + "\" where " + String.join(" or ", acceptedCodes) + " was expected.");
        }
    }
}
