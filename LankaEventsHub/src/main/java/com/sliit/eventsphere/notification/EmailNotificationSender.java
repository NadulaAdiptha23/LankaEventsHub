package com.sliit.eventsphere.notification;

import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;
import com.sliit.eventsphere.entity.User;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends notifications by email.
 *
 * Off unless a mail server has been configured. That is the whole
 * behaviour when MAIL_HOST is unset, and it is why adding this class
 * changes nothing for an installation that has not asked for email -
 * {@link #isEnabled()} returns false and the service never calls it.
 *
 * Two deliberate decisions about failure and latency:
 *
 *   - Sending happens on a small background executor, not on the request
 *     thread. An SMTP conversation takes seconds on a good day and
 *     blocks until its timeout on a bad one; doing it inline would make
 *     registering for an event as slow as the slowest mail server.
 *   - Nothing here throws. A notification is a side effect of something
 *     the user actually did, so a mail server being down must not roll
 *     back their registration or show them an error about a channel they
 *     never asked for. Failures are logged with enough to diagnose them
 *     and no more - never the message body, which is somebody's mail.
 *
 * The queue is bounded. If mail backs up faster than it drains, messages
 * are dropped with a warning rather than accumulating until the heap
 * runs out - a system that stops sending email is a nuisance, one that
 * falls over is an outage.
 */
@Component
public class EmailNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSender.class);

    private static final int QUEUE_CAPACITY = 500;

    private final MailProperties properties;
    private final SmtpClient smtpClient;
    private final ThreadPoolExecutor executor;

    /**
     * @Autowired is required, not decorative: there are two constructors
     * here and Spring refuses to guess between them. Without it the
     * context fails to start with "No default constructor found", which
     * no unit test would ever have caught.
     */
    @Autowired
    public EmailNotificationSender(MailProperties properties) {
        this(properties, new SmtpClient(properties));
    }

    /** Test seam: lets a fake client stand in for the socket work. */
    EmailNotificationSender(MailProperties properties, SmtpClient smtpClient) {
        this.properties = properties;
        this.smtpClient = smtpClient;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "eventsphere-mail");
                    // Daemon: a queue of unsent notifications must not be
                    // the reason the application will not shut down.
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());

        if (properties.isConfigured()) {
            log.info("Email notifications enabled - {}", properties);
        } else {
            log.info("Email notifications disabled: no mail host configured "
                    + "(set MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD to enable). "
                    + "In-app notifications are unaffected.");
        }
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public boolean isEnabled() {
        return properties.isConfigured();
    }

    @Override
    public void send(User user, NotificationType type, String message) {
        if (!isEnabled()) {
            return;
        }
        String address = user.getEmail();
        if (address == null || address.isBlank()) {
            return;
        }

        Long userId = user.getId();
        String subject = subjectFor(type);
        String body = bodyFor(user, message);

        boolean queued = offer(() -> {
            try {
                smtpClient.send(address, subject, body);
                log.info("Email notification sent to user {} ({})", userId, type);
            } catch (IOException | RuntimeException ex) {
                // The user id and the reason, never the address and never
                // the body. Enough to find the problem; not a copy of
                // somebody's mail in the application log.
                log.warn("Could not email notification ({}) to user {}: {}",
                        type, userId, ex.getMessage());
            }
        });

        if (!queued) {
            log.warn("Mail queue is full; dropped the {} email for user {}. "
                    + "The in-app notification was still recorded.", type, userId);
        }
    }

    /** DiscardPolicy makes this silent, so the caller is told instead. */
    private boolean offer(Runnable task) {
        int before = executor.getQueue().size();
        executor.execute(task);
        return before < QUEUE_CAPACITY;
    }

    /**
     * A subject line derived from the notification type, so an inbox is
     * scannable without opening anything. Built from the enum rather than
     * from a lookup table that would drift out of step with it the first
     * time somebody adds a value.
     */
    static String subjectFor(NotificationType type) {
        String words = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String sentence = Character.toUpperCase(words.charAt(0)) + words.substring(1);
        return "EventSphere: " + sentence;
    }

    /**
     * The body. The same words the recipient sees in the application -
     * one message, written once, so the email and the notifications page
     * can never say two different things about the same event.
     */
    static String bodyFor(User user, String message) {
        String name = user.getFullName() == null ? "there" : user.getFullName();
        return "Hello " + name + ",\n\n"
                + message + "\n\n"
                + "You can see this and everything else on your EventSphere "
                + "notifications page.\n\n"
                + "-- EventSphere\n";
    }

    @PreDestroy
    void shutDown() {
        executor.shutdown();
        try {
            // A short, bounded wait: worth letting an in-flight message
            // finish, not worth holding up a restart for.
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
