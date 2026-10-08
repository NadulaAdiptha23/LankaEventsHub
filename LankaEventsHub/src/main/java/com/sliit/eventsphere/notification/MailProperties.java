package com.sliit.eventsphere.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where mail goes and who it comes from.
 *
 * Every value is read from an environment variable with a development
 * default, exactly as the database credentials are. Nothing here is
 * hard-coded and nothing here belongs in version control: a deployment
 * sets MAIL_HOST, MAIL_PORT, MAIL_USERNAME and MAIL_PASSWORD, and this
 * file never learns what they are.
 *
 * The important default is the one that decides whether mail is sent at
 * all. MAIL_HOST is EMPTY unless somebody sets it, which means email is
 * off unless it has been deliberately configured. That is the right way
 * round: an installation that ships this file unchanged behaves exactly
 * as the system did before email existed - in-app notifications only -
 * rather than silently failing to reach a mail server nobody set up, or
 * worse, reaching one nobody intended.
 */
@Component
public class MailProperties {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final String fromName;
    private final boolean startTls;
    private final int timeoutMs;

    public MailProperties(@Value("${app.mail.host:}") String host,
                          @Value("${app.mail.port:587}") int port,
                          @Value("${app.mail.username:}") String username,
                          @Value("${app.mail.password:}") String password,
                          @Value("${app.mail.from:}") String from,
                          @Value("${app.mail.from-name:EventSphere}") String fromName,
                          @Value("${app.mail.start-tls:true}") boolean startTls,
                          @Value("${app.mail.timeout-ms:10000}") int timeoutMs) {
        this.host = trim(host);
        this.port = port;
        this.username = trim(username);
        this.password = password == null ? "" : password;
        // Falling back to the username is what makes a typical Gmail or
        // SMTP-relay setup work with two variables instead of three.
        this.from = trim(from).isEmpty() ? trim(username) : trim(from);
        this.fromName = trim(fromName);
        this.startTls = startTls;
        this.timeoutMs = timeoutMs;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Whether there is enough configuration to send anything.
     *
     * A host with no from-address would produce mail no server will
     * accept, so both are required. Credentials are not: plenty of
     * internal relays take mail from inside the network unauthenticated,
     * and demanding a username would make those impossible to configure.
     */
    public boolean isConfigured() {
        return !host.isEmpty() && !from.isEmpty() && port > 0;
    }

    public boolean requiresAuthentication() {
        return !username.isEmpty();
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getFrom() { return from; }
    public String getFromName() { return fromName; }
    public boolean isStartTls() { return startTls; }
    public int getTimeoutMs() { return timeoutMs; }

    /**
     * A description safe to write to a log: host, port and from-address,
     * never the password. Logs are read, shipped and kept far more
     * casually than configuration is.
     */
    @Override
    public String toString() {
        return "mail[" + host + ":" + port + " from=" + from
                + " startTls=" + startTls
                + " auth=" + (requiresAuthentication() ? "yes" : "no") + "]";
    }
}
